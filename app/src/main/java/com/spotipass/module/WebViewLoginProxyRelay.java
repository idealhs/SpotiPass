package com.spotipass.module;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class WebViewLoginProxyRelay implements Closeable {

    interface Logger {
        void log(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int LINE_LIMIT = 8192;

    private final String upstreamHost;
    private final int upstreamPort;
    private final boolean useTlsToProxy;
    private final String upstreamAuthHeader;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    private WebViewLoginProxyRelay(
            String upstreamHost,
            int upstreamPort,
            boolean useTlsToProxy,
            String upstreamAuthHeader,
            Logger logger
    ) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.useTlsToProxy = useTlsToProxy;
        this.upstreamAuthHeader = upstreamAuthHeader == null ? "" : upstreamAuthHeader;
        this.logger = logger;
    }

    static WebViewLoginProxyRelay start(
            String upstreamHost,
            int upstreamPort,
            boolean useTlsToProxy,
            String upstreamAuthHeader,
            Logger logger
    ) throws IOException {
        WebViewLoginProxyRelay relay = new WebViewLoginProxyRelay(
                upstreamHost,
                upstreamPort,
                useTlsToProxy,
                upstreamAuthHeader,
                logger
        );
        relay.startInternal();
        return relay;
    }

    String localProxyRule() {
        ServerSocket local = serverSocket;
        if (local == null) return "http://127.0.0.1:0";
        return "http://127.0.0.1:" + local.getLocalPort();
    }

    String describeRoute() {
        return localProxyRule() + " -> " + upstreamScheme() + upstreamHost + ":" + upstreamPort;
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

        Thread thread = new Thread(this::acceptLoop, "spotipass-webview-proxy-relay");
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
                Thread worker = new Thread(() -> handleClient(acceptedClient), "spotipass-webview-proxy-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!closed.get()) {
                    log("login WebView relay accept failed: " + e);
                }
                closeSocket(client);
                return;
            } catch (Throwable t) {
                log("login WebView relay unexpected accept failure: " + t);
                closeSocket(client);
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
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
            String target = parts[1];
            if (!"CONNECT".equals(method)) {
                writeSimpleResponse(clientOut, "501 Not Implemented", "only CONNECT is supported");
                log("login WebView relay rejected non-CONNECT request: " + requestLine);
                return;
            }

            upstream = openUpstreamSocket();
            InputStream upstreamIn = upstream.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();

            writeConnectRequest(upstreamOut, target, headers);
            ProxyResponse response = readProxyResponse(upstreamIn);
            if (response.code != 200) {
                log("login WebView relay upstream CONNECT failed for " + target + ": " + response.statusLine);
                writeSimpleResponse(clientOut, "502 Bad Gateway", "upstream proxy connect failed");
                return;
            }

            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            client.setSoTimeout(0);
            upstream.setSoTimeout(0);

            Socket finalUpstream = upstream;
            Thread upstreamToClient = new Thread(
                    () -> pipe(upstreamIn, clientOut, client, finalUpstream),
                    "spotipass-webview-proxy-upstream"
            );
            upstreamToClient.setDaemon(true);
            upstreamToClient.start();

            pipe(clientIn, upstreamOut, finalUpstream, client);

            try {
                upstreamToClient.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                log("login WebView relay client handling failed: " + t);
            }
        } finally {
            closeSocket(upstream);
            closeSocket(client);
        }
    }

    private Socket openUpstreamSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        openSockets.add(socket);

        if (!useTlsToProxy) {
            return socket;
        }

        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, upstreamHost, upstreamPort, true);
        sslSocket.setUseClientMode(true);
        sslSocket.setSoTimeout(READ_TIMEOUT_MS);
        SSLParameters params = sslSocket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(params);
        sslSocket.startHandshake();

        openSockets.remove(socket);
        openSockets.add(sslSocket);
        return sslSocket;
    }

    private void writeConnectRequest(OutputStream upstreamOut, String target, List<String> clientHeaders) throws IOException {
        String hostHeader = extractHostHeader(target, clientHeaders);
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader).append("\r\n");
        request.append("Proxy-Connection: Keep-Alive\r\n");
        request.append("User-Agent: SpotiPass-WebViewRelay/1.0\r\n");
        if (!upstreamAuthHeader.isEmpty()) {
            request.append("Proxy-Authorization: ").append(upstreamAuthHeader).append("\r\n");
        }
        request.append("\r\n");
        upstreamOut.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        upstreamOut.flush();
    }

    private ProxyResponse readProxyResponse(InputStream upstreamIn) throws IOException {
        String statusLine = readAsciiLine(upstreamIn);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("upstream proxy returned empty response");
        }
        List<String> headers = readHeaders(upstreamIn);

        String[] parts = statusLine.trim().split("\\s+", 3);
        int code = -1;
        if (parts.length >= 2) {
            try {
                code = Integer.parseInt(parts[1]);
            } catch (Throwable ignored) {
                code = -1;
            }
        }
        return new ProxyResponse(statusLine, code, headers);
    }

    private static String extractHostHeader(String target, List<String> clientHeaders) {
        if (clientHeaders != null) {
            for (String header : clientHeaders) {
                if (header == null) continue;
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                String name = header.substring(0, colon).trim();
                if (!"host".equalsIgnoreCase(name)) continue;
                String value = header.substring(colon + 1).trim();
                if (!value.isEmpty()) return value;
            }
        }
        return target;
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
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;
            }
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

    private static void pipe(InputStream in, OutputStream out, Socket outSocket, Socket inSocket) {
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                out.write(buffer, 0, read);
                out.flush();
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

    private String upstreamScheme() {
        return useTlsToProxy ? "https://" : "http://";
    }

    private void closeSocket(Socket socket) {
        if (socket == null) return;
        openSockets.remove(socket);
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static final class ProxyResponse {
        final String statusLine;
        final int code;
        final List<String> headers;

        ProxyResponse(String statusLine, int code, List<String> headers) {
            this.statusLine = statusLine;
            this.code = code;
            this.headers = headers;
        }
    }
}
