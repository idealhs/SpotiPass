package com.spotipass.module;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class LocalLoginDnsProxyServer implements Closeable {

    interface Logger {
        void log(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int LINE_LIMIT = 8192;

    private final Map<String, List<byte[]>> dnsRules;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    private LocalLoginDnsProxyServer(Map<String, List<byte[]>> dnsRules, Logger logger) {
        this.dnsRules = dnsRules == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(dnsRules);
        this.logger = logger;
    }

    static LocalLoginDnsProxyServer start(Map<String, List<byte[]>> dnsRules, Logger logger) throws IOException {
        LocalLoginDnsProxyServer server = new LocalLoginDnsProxyServer(dnsRules, logger);
        server.startInternal();
        return server;
    }

    String localProxyRule() {
        ServerSocket local = serverSocket;
        if (local == null) return "http://127.0.0.1:0";
        return "http://127.0.0.1:" + local.getLocalPort();
    }

    String describeRoute() {
        return localProxyRule() + " -> DNS rules";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        ServerSocket local = serverSocket;
        serverSocket = null;
        if (local != null) {
            try {
                local.close();
            } catch (Throwable ignored) {
            }
        }

        for (Socket socket : new ArrayList<>(openSockets)) {
            closeSocket(socket);
        }
        openSockets.clear();
    }

    private void startInternal() throws IOException {
        ServerSocket local = new ServerSocket();
        local.setReuseAddress(true);
        local.bind(new InetSocketAddress("127.0.0.1", 0));
        serverSocket = local;

        Thread thread = new Thread(this::acceptLoop, "spotipass-login-dns-proxy");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            ServerSocket local = serverSocket;
            if (local == null) return;

            Socket client = null;
            try {
                client = local.accept();
                if (closed.get()) {
                    closeSocket(client);
                    return;
                }
                client.setTcpNoDelay(true);
                client.setSoTimeout(READ_TIMEOUT_MS);
                openSockets.add(client);

                Socket acceptedClient = client;
                Thread worker = new Thread(() -> handleClient(acceptedClient), "spotipass-login-dns-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!closed.get()) {
                    log("DNS WebView proxy accept failed: " + e);
                }
                closeSocket(client);
                return;
            } catch (Throwable t) {
                log("DNS WebView proxy unexpected accept failure: " + t);
                closeSocket(client);
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        TargetEndpoint endpoint = null;
        AtomicLong bytesUp = new AtomicLong(0L);
        AtomicLong bytesDown = new AtomicLong(0L);
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            String requestLine = readAsciiLine(clientIn);
            if (requestLine == null || requestLine.isEmpty()) return;
            List<String> headers = readHeaders(clientIn);

            String[] parts = requestLine.trim().split("\\s+", 3);
            if (parts.length < 2) {
                writeSimpleResponse(clientOut, "400 Bad Request", "invalid proxy request");
                return;
            }

            String method = parts[0].toUpperCase(Locale.US);
            if (!"CONNECT".equals(method)) {
                writeSimpleResponse(clientOut, "501 Not Implemented", "only CONNECT is supported");
                log("DNS WebView proxy rejected non-CONNECT request: " + requestLine);
                return;
            }

            endpoint = parseConnectTarget(parts[1], headers);
            if (endpoint == null || endpoint.host.isEmpty() || endpoint.port <= 0) {
                writeSimpleResponse(clientOut, "400 Bad Request", "invalid CONNECT target");
                return;
            }

            upstream = connectTarget(endpoint);
            if (upstream == null) {
                writeSimpleResponse(clientOut, "502 Bad Gateway", "unable to connect target");
                return;
            }

            InputStream upstreamIn = upstream.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();

            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            client.setSoTimeout(0);
            upstream.setSoTimeout(0);

            Socket finalUpstream = upstream;
            TargetEndpoint finalEndpoint = endpoint;
            Thread upstreamToClient = new Thread(
                    () -> pipe(upstreamIn, clientOut, client, finalUpstream, bytesDown),
                    "spotipass-login-dns-upstream"
            );
            upstreamToClient.setDaemon(true);
            upstreamToClient.start();

            pipe(clientIn, upstreamOut, finalUpstream, client, bytesUp);

            try {
                upstreamToClient.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            log(String.format(Locale.US,
                    "DNS WebView tunnel closed %s:%d bytesUp=%d bytesDown=%d",
                    finalEndpoint.host, finalEndpoint.port, bytesUp.get(), bytesDown.get()));
        } catch (Throwable t) {
            if (!closed.get()) {
                String target = endpoint == null ? "" : endpoint.host + ":" + endpoint.port;
                log("DNS WebView proxy client failed" + (target.isEmpty() ? "" : " for " + target) + ": " + t);
            }
        } finally {
            closeSocket(upstream);
            closeSocket(client);
        }
    }

    private Socket connectTarget(TargetEndpoint endpoint) {
        String normalizedHost = normalizeHost(endpoint.host);
        List<byte[]> candidates = normalizedHost == null ? null : dnsRules.get(normalizedHost);
        if (candidates != null && !candidates.isEmpty()) {
            Throwable lastFailure = null;
            for (int i = 0; i < candidates.size(); i++) {
                byte[] candidate = candidates.get(i);
                String ipText = formatIpv4(candidate);
                Socket socket = null;
                try {
                    InetAddress address = InetAddress.getByAddress(endpoint.host, candidate);
                    socket = new Socket();
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(address, endpoint.port), CONNECT_TIMEOUT_MS);
                    socket.setSoTimeout(READ_TIMEOUT_MS);
                    openSockets.add(socket);
                    log(String.format(Locale.US,
                            "DNS WebView CONNECT %s:%d -> %s:%d",
                            endpoint.host, endpoint.port, ipText, endpoint.port));
                    return socket;
                } catch (Throwable t) {
                    lastFailure = t;
                    closeSocket(socket);
                    if (i + 1 < candidates.size()) {
                        log(String.format(Locale.US,
                                "DNS WebView CONNECT %s:%d retry %s after %s",
                                endpoint.host, endpoint.port, ipText, summarizeThrowable(t)));
                    }
                }
            }
            log(String.format(Locale.US,
                    "DNS WebView CONNECT %s:%d failed after %d candidates: %s",
                    endpoint.host, endpoint.port, candidates.size(), summarizeThrowable(lastFailure)));
            return null;
        }

        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            openSockets.add(socket);
            log(String.format(Locale.US,
                    "DNS WebView CONNECT %s:%d direct",
                    endpoint.host, endpoint.port));
            return socket;
        } catch (Throwable t) {
            closeSocket(socket);
            log(String.format(Locale.US,
                    "DNS WebView CONNECT %s:%d direct failed: %s",
                    endpoint.host, endpoint.port, summarizeThrowable(t)));
            return null;
        }
    }

    private static TargetEndpoint parseConnectTarget(String target, List<String> headers) {
        String raw = target == null ? "" : target.trim();
        if (raw.isEmpty()) {
            raw = extractHostHeader(headers);
        }
        if (raw == null || raw.isEmpty()) return null;

        if (raw.startsWith("[")) {
            int closing = raw.indexOf(']');
            if (closing <= 0 || closing + 2 > raw.length() || raw.charAt(closing + 1) != ':') return null;
            String host = raw.substring(1, closing);
            int port = parsePort(raw.substring(closing + 2));
            return port > 0 ? new TargetEndpoint(host, port) : null;
        }

        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon >= raw.length() - 1) return null;
        String host = raw.substring(0, colon).trim();
        int port = parsePort(raw.substring(colon + 1));
        return port > 0 ? new TargetEndpoint(host, port) : null;
    }

    private static String extractHostHeader(List<String> headers) {
        if (headers == null) return null;
        for (String header : headers) {
            if (header == null) continue;
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim();
            if (!"host".equalsIgnoreCase(name)) continue;
            return header.substring(colon + 1).trim();
        }
        return null;
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

    private static String normalizeHost(String host) {
        if (host == null) return null;
        String normalized = host.trim().toLowerCase(Locale.US);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String formatIpv4(byte[] value) {
        if (value == null || value.length != 4) return "";
        return (value[0] & 0xff) + "." + (value[1] & 0xff) + "." + (value[2] & 0xff) + "." + (value[3] & 0xff);
    }

    private static String summarizeThrowable(Throwable t) {
        if (t == null) return "unknown";
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    private static List<String> readHeaders(InputStream in) throws IOException {
        ArrayList<String> headers = new ArrayList<>();
        while (true) {
            String line = readAsciiLine(in);
            if (line == null || line.isEmpty()) return headers;
            headers.add(line);
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        if (in == null) return null;
        byte[] buffer = new byte[LINE_LIMIT];
        int count = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (count == 0) return null;
                break;
            }
            if (b == '\n') break;
            if (b == '\r') continue;
            if (count >= buffer.length) {
                throw new IOException("header line too long");
            }
            buffer[count++] = (byte) b;
        }
        return new String(buffer, 0, count, StandardCharsets.ISO_8859_1);
    }

    private static void writeSimpleResponse(OutputStream out, String status, String message) throws IOException {
        if (out == null) return;
        byte[] body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Connection: close\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void pipe(InputStream in, OutputStream out, Socket outSocket, Socket inSocket, AtomicLong counter) {
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                out.write(buffer, 0, read);
                out.flush();
                if (counter != null) counter.addAndGet(read);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                out.flush();
            } catch (Throwable ignored) {
            }
            if (outSocket != null) {
                try {
                    outSocket.shutdownOutput();
                } catch (Throwable ignored) {
                }
            }
            if (inSocket != null) {
                try {
                    inSocket.shutdownInput();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void log(String message) {
        if (logger == null || message == null || message.isEmpty()) return;
        try {
            logger.log(message);
        } catch (Throwable ignored) {
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) return;
        openSockets.remove(socket);
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static final class TargetEndpoint {
        final String host;
        final int port;

        TargetEndpoint(String host, int port) {
            this.host = host == null ? "" : host;
            this.port = port;
        }
    }
}
