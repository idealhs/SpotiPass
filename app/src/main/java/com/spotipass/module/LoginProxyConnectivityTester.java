package com.spotipass.module;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class LoginProxyConnectivityTester {

    private static final String TARGET_HOST = "accounts.spotify.com";
    private static final int TARGET_PORT = 443;
    private static final int TIMEOUT_MS = 8000;

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final boolean success;
        final String summary;
        final String details;

        Result(boolean success, String summary, String details) {
            this.success = success;
            this.summary = summary;
            this.details = details;
        }
    }

    private LoginProxyConnectivityTester() {}

    static Result validate(String host, String portText) {
        String trimmedHost = trimToEmpty(host);
        if (trimmedHost.isEmpty()) {
            return new Result(false, "代理主机为空", "请填写登录代理主机。");
        }

        int port = parsePort(trimToEmpty(portText));
        if (port <= 0) {
            return new Result(false, "代理端口无效", "请填写 1 到 65535 之间的代理端口。");
        }

        return null;
    }

    static void testAsync(String host, String portText, boolean useTlsToProxy, String username, String password, Callback callback) {
        Result validation = validate(host, portText);
        if (validation != null) {
            if (callback != null) callback.onResult(validation);
            return;
        }

        final String trimmedHost = trimToEmpty(host);
        final int port = parsePort(trimToEmpty(portText));
        final boolean tls = useTlsToProxy;
        final String trimmedUsername = trimToEmpty(username);
        final String safePassword = password == null ? "" : password;

        new Thread(() -> {
            Result result = runProbe(trimmedHost, port, tls, trimmedUsername, safePassword);
            if (callback != null) {
                callback.onResult(result);
            }
        }, "spotipass-proxy-test").start();
    }

    private static Result runProbe(String host, int port, boolean useTlsToProxy, String username, String password) {
        long startedAt = System.nanoTime();
        Socket socket = null;
        BufferedReader reader = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            if (useTlsToProxy) {
                socket = upgradeToTlsProxySocket(socket, host, port);
                socket.setSoTimeout(TIMEOUT_MS);
            }

            OutputStream output = socket.getOutputStream();
            output.write(buildConnectRequest(username, password).getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null || statusLine.isEmpty()) {
                throw new IllegalStateException("proxy returned no response");
            }

            int responseCode = parseStatusCode(statusLine);
            List<String> headers = readHeaders(reader);
            long elapsedMs = elapsedMs(startedAt);

            if (responseCode == 200) {
                String message = buildSuccessMessage(host, port, useTlsToProxy, username, password, elapsedMs, statusLine, headers);
                SpotiPassRuntimeLog.append("SpotiPassProxyTest: " + summarizeForLog(host, port, useTlsToProxy, responseCode, elapsedMs));
                return new Result(true, "代理隧道建立成功", message);
            }

            String message = buildFailureMessage(host, port, useTlsToProxy, username, password, elapsedMs,
                    "proxy returned " + statusLine, headers);
            SpotiPassRuntimeLog.append("SpotiPassProxyTest: failed " + summarizeForLog(host, port, useTlsToProxy, responseCode, elapsedMs));
            return new Result(false, "代理返回非 200 响应", message);
        } catch (Throwable t) {
            long elapsedMs = elapsedMs(startedAt);
            String reason = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            String message = buildFailureMessage(host, port, useTlsToProxy, username, password, elapsedMs, reason, null);
            SpotiPassRuntimeLog.append("SpotiPassProxyTest: failed " + proxyScheme(useTlsToProxy) + host + ":" + port + " after " + elapsedMs + " ms - " + reason);
            return new Result(false, "代理连接失败", message);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Socket upgradeToTlsProxySocket(Socket rawSocket, String host, int port) throws Exception {
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(rawSocket, host, port, true);
        SSLParameters params = sslSocket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(params);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static String buildConnectRequest(String username, String password) {
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(TARGET_HOST).append(':').append(TARGET_PORT).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(TARGET_HOST).append(':').append(TARGET_PORT).append("\r\n");
        request.append("User-Agent: SpotiPass-ProxyTester/1.0\r\n");
        request.append("Proxy-Connection: Keep-Alive\r\n");
        if (hasCredentials(username, password)) {
            request.append("Proxy-Authorization: ").append(basicHeader(username, password)).append("\r\n");
        }
        request.append("\r\n");
        return request.toString();
    }

    private static int parseStatusCode(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalStateException("invalid proxy response: " + statusLine);
        }
        return Integer.parseInt(parts[1]);
    }

    private static List<String> readHeaders(BufferedReader reader) throws Exception {
        ArrayList<String> headers = new ArrayList<>();
        while (true) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) break;
            headers.add(line);
        }
        return headers;
    }

    private static String buildSuccessMessage(
            String host,
            int port,
            boolean useTlsToProxy,
            String username,
            String password,
            long elapsedMs,
            String statusLine,
            List<String> headers
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("代理：").append(proxyScheme(useTlsToProxy)).append(host).append(':').append(port).append('\n');
        sb.append("认证：").append(hasCredentials(username, password) ? "已配置" : "未配置").append('\n');
        sb.append("目标：").append(TARGET_HOST).append(':').append(TARGET_PORT).append('\n');
        sb.append("CONNECT 响应：").append(statusLine).append('\n');
        sb.append("耗时：").append(elapsedMs).append(" ms").append('\n');
        appendHeaders(sb, headers);
        sb.append("说明：已通过当前代理成功建立到 Spotify 登录域名的隧道。");
        return sb.toString();
    }

    private static String buildFailureMessage(
            String host,
            int port,
            boolean useTlsToProxy,
            String username,
            String password,
            long elapsedMs,
            String reason,
            List<String> headers
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("代理：").append(proxyScheme(useTlsToProxy)).append(host).append(':').append(port).append('\n');
        sb.append("认证：").append(hasCredentials(username, password) ? "已配置" : "未配置").append('\n');
        sb.append("目标：").append(TARGET_HOST).append(':').append(TARGET_PORT).append('\n');
        sb.append("耗时：").append(elapsedMs).append(" ms").append('\n');
        sb.append("错误：").append(reason).append('\n');
        appendHeaders(sb, headers);
        sb.append("说明：未能通过当前代理建立到 Spotify 登录域名的隧道。");
        return sb.toString();
    }

    private static void appendHeaders(StringBuilder sb, List<String> headers) {
        if (headers == null || headers.isEmpty()) return;
        sb.append("代理响应头：").append('\n');
        for (String header : headers) {
            sb.append(header).append('\n');
        }
    }

    private static String summarizeForLog(String host, int port, boolean useTlsToProxy, int code, long elapsedMs) {
        return String.format(Locale.US, "%s%s:%d -> CONNECT %d in %d ms",
                proxyScheme(useTlsToProxy), host, port, code, elapsedMs);
    }

    private static String proxyScheme(boolean useTlsToProxy) {
        return useTlsToProxy ? "https://" : "http://";
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static int parsePort(String rawPort) {
        if (rawPort == null || rawPort.isEmpty()) return -1;
        try {
            int port = Integer.parseInt(rawPort);
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String basicHeader(String username, String password) {
        String pair = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        String encoded = Base64.encodeToString(pair.getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
        return "Basic " + encoded;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimmedCredentials(String username, String password) {
        return trimToEmpty(username) + (password == null ? "" : password);
    }

    private static boolean hasCredentials(String username, String password) {
        return !trimmedCredentials(username, password).isEmpty();
    }
}
