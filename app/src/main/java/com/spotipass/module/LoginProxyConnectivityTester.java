package com.spotipass.module;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import android.util.Base64;

final class LoginProxyConnectivityTester {

    private static final String TEST_URL = "https://accounts.spotify.com/";
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
            return new Result(false, "代理主机为空", "请填写登录 HTTP 代理主机。");
        }

        int port = parsePort(trimToEmpty(portText));
        if (port <= 0) {
            return new Result(false, "代理端口无效", "请填写 1 到 65535 之间的代理端口。");
        }

        return null;
    }

    static void testAsync(String host, String portText, String username, String password, Callback callback) {
        Result validation = validate(host, portText);
        if (validation != null) {
            if (callback != null) callback.onResult(validation);
            return;
        }

        final String trimmedHost = trimToEmpty(host);
        final int port = parsePort(trimToEmpty(portText));
        final String trimmedUsername = trimToEmpty(username);
        final String safePassword = password == null ? "" : password;

        new Thread(() -> {
            Result result = runProbe(trimmedHost, port, trimmedUsername, safePassword);
            if (callback != null) {
                callback.onResult(result);
            }
        }, "spotipass-proxy-test").start();
    }

    private static Result runProbe(String host, int port, String username, String password) {
        long startedAt = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
            URL url = new URL(TEST_URL);
            connection = (HttpURLConnection) url.openConnection(proxy);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "SpotiPass-ProxyTester/1.0");
            connection.setRequestProperty("Accept", "*/*");
            if (hasCredentials(username, password)) {
                connection.setRequestProperty("Proxy-Authorization", basicHeader(username, password));
            }

            int code = connection.getResponseCode();
            long elapsedMs = elapsedMs(startedAt);
            String message = buildSuccessMessage(host, port, username, password, code, elapsedMs, connection.getHeaderField("Location"));
            SpotiPassRuntimeLog.append("SpotiPassProxyTest: " + summarizeForLog(host, port, code, elapsedMs));
            return new Result(true, "已收到 HTTP " + code + " 响应", message);
        } catch (Throwable t) {
            long elapsedMs = elapsedMs(startedAt);
            String reason = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            String message = buildFailureMessage(host, port, username, password, elapsedMs, reason);
            SpotiPassRuntimeLog.append("SpotiPassProxyTest: failed " + host + ":" + port + " after " + elapsedMs + " ms - " + reason);
            return new Result(false, "代理连接失败", message);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String buildSuccessMessage(String host, int port, String username, String password, int code, long elapsedMs, String location) {
        StringBuilder sb = new StringBuilder();
        sb.append("代理：").append(host).append(':').append(port);
        sb.append('\n');
        sb.append("认证：").append(hasCredentials(username, password) ? "已配置" : "未配置");
        sb.append('\n');
        sb.append("目标：").append(TEST_URL);
        sb.append('\n');
        sb.append("HTTP 响应：").append(code);
        sb.append('\n');
        sb.append("耗时：").append(elapsedMs).append(" ms");
        if (location != null && !location.isEmpty()) {
            sb.append('\n');
            sb.append("Location：").append(location);
        }
        sb.append('\n');
        sb.append("说明：已通过当前代理收到 Spotify 登录域名响应。");
        return sb.toString();
    }

    private static String buildFailureMessage(String host, int port, String username, String password, long elapsedMs, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("代理：").append(host).append(':').append(port);
        sb.append('\n');
        sb.append("认证：").append(hasCredentials(username, password) ? "已配置" : "未配置");
        sb.append('\n');
        sb.append("目标：").append(TEST_URL);
        sb.append('\n');
        sb.append("耗时：").append(elapsedMs).append(" ms");
        sb.append('\n');
        sb.append("错误：").append(reason);
        sb.append('\n');
        sb.append("说明：未能通过当前代理连到 Spotify 登录域名。");
        return sb.toString();
    }

    private static String summarizeForLog(String host, int port, int code, long elapsedMs) {
        return String.format(Locale.US, "%s:%d -> HTTP %d in %d ms", host, port, code, elapsedMs);
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
