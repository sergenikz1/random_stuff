package dev.limitpulse.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** OAuth Authorization Code + PKCE, using Codex's registered loopback redirect. */
final class BrowserLogin implements AutoCloseable {
    static final long TIMEOUT_MS = 10 * 60 * 1000L;
    static final int PORT = 1455;
    final String verifier, state, redirectUri, authorizationUrl;
    private final String callbackHost, callbackPath;
    private final ServerSocket server;
    private final long deadline;
    private volatile boolean closed;

    interface Exchange { Credentials complete(String code) throws Exception; }
    static final class Denied extends IOException { Denied() { super("Authorization declined"); } }

    static BrowserLogin start() throws Exception { return start(PORT, TIMEOUT_MS); }
    static BrowserLogin start(int port, long timeoutMs) throws Exception { return start(port, timeoutMs, false); }
    /** Claude Code registers {@code http://localhost:<any port>/callback}; an OS-assigned port avoids clashes. */
    static BrowserLogin startClaude(long timeoutMs) throws Exception { return start(0, timeoutMs, true); }
    private static BrowserLogin start(int port, long timeoutMs, boolean claude) throws Exception {
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            socket.setSoTimeout(1000);
            return new BrowserLogin(socket, timeoutMs, claude);
        } catch (Exception failure) { socket.close(); throw failure; }
    }
    private BrowserLogin(ServerSocket socket, long timeoutMs, boolean claude) throws Exception {
        server = socket;
        verifier = random(); state = random();
        int port = server.getLocalPort();
        callbackHost = (claude ? "localhost:" : "127.0.0.1:") + port;
        callbackPath = claude ? "/callback" : "/auth/callback";
        redirectUri = "http://" + callbackHost + callbackPath;
        deadline = System.nanoTime() + timeoutMs * 1000000L;
        authorizationUrl = claude ? ClaudeApi.authorizationUrl(redirectUri, challenge(verifier), state) : openAiUrl();
    }
    private String openAiUrl() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code"); params.put("client_id", OpenAiApi.CLIENT_ID);
        params.put("redirect_uri", redirectUri); params.put("scope", "openid profile email offline_access");
        params.put("code_challenge", challenge(verifier)); params.put("code_challenge_method", "S256");
        params.put("state", state); params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true"); params.put("originator", "codex_cli");
        return OpenAiApi.AUTH + "/oauth/authorize?" + query(params);
    }
    static String query(Map<String, String> params) throws Exception {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(param.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(param.getValue(), "UTF-8"));
        }
        return query.toString();
    }
    static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
    static String random() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    /** Invalid/unsolicited requests do not consume the login. A valid code is exchanged once. */
    Credentials await(Exchange exchange) throws Exception {
        while (!closed && System.nanoTime() < deadline) {
            Socket accepted;
            try { accepted = server.accept(); }
            catch (SocketTimeoutException pending) { continue; }
            catch (IOException cancelled) { if (closed) throw new IOException("Login cancelled"); throw cancelled; }
            try (Socket socket = accepted) {
                socket.setSoTimeout(2000);
                String request;
                try { request = headers(socket.getInputStream()); }
                catch (IOException malformed) { continue; }
                Map<String, String> callback;
                try { callback = callback(request, callbackHost, callbackPath, state); }
                catch (IllegalArgumentException malformed) { respond(socket, 400, false); continue; }
                if (callback == null) { respond(socket, 400, false); continue; }
                if (callback.containsKey("error")) {
                    respond(socket, 200, false); throw new Denied();
                }
                if (closed || System.nanoTime() >= deadline) throw new SocketTimeoutException("Login expired");
                Credentials result;
                try { result = exchange.complete(callback.get("code")); }
                catch (Exception failure) {
                    try { respond(socket, 200, false); } catch (IOException ignored) { }
                    throw failure;
                }
                // Successful token persistence must not be undone by a closed browser tab.
                try { respond(socket, 200, true); } catch (IOException ignored) { }
                return result;
            }
        }
        throw new SocketTimeoutException(closed ? "Login cancelled" : "Login expired");
    }
    static Map<String, String> callback(String request, int port, String expectedState) {
        return callback(request, "127.0.0.1:" + port, "/auth/callback", expectedState);
    }
    static Map<String, String> callback(String request, String host, String path, String expectedState) {
        String[] lines = request.split("\r\n");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !"GET".equals(first[0]) || !"HTTP/1.1".equals(first[2])) return null;
        boolean validHost = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && "host".equalsIgnoreCase(lines[i].substring(0, colon))) {
                if (validHost || !host.equals(lines[i].substring(colon + 1).trim())) return null;
                validHost = true;
            }
        }
        if (!validHost) return null;
        URI uri;
        try { uri = new URI(first[1]); } catch (Exception malformed) { return null; }
        if (uri.isAbsolute() || uri.getRawAuthority() != null || !path.equals(uri.getRawPath()) || uri.getRawFragment() != null || uri.getRawQuery() == null) return null;
        Map<String, String> params = new LinkedHashMap<>();
        String[] pairs = uri.getRawQuery().split("&");
        if (pairs.length > 20) return null;
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) return null;
            try {
                String key = URLDecoder.decode(kv[0], "UTF-8"), value = URLDecoder.decode(kv[1], "UTF-8");
                if (params.put(key, value) != null) return null;
            } catch (Exception malformed) { return null; }
        }
        String state = params.get("state");
        if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8))) return null;
        if (params.containsKey("error")) return params;
        String code = params.get("code");
        return code == null || code.isEmpty() || code.length() > 4096 ? null : params;
    }
    private static String headers(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        for (int i = 0; i < 16384; i++) {
            int next = input.read(); if (next < 0) throw new IOException("Incomplete HTTP headers");
            bytes.write(next); tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) return bytes.toString("US-ASCII");
        }
        throw new IOException("HTTP headers too large");
    }
    private static void respond(Socket socket, int status, boolean success) throws IOException {
        String message = success ? "Вход завершён" : "Вход не завершён";
        String body = "<!doctype html><html lang='ru'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='referrer' content='no-referrer'><title>Limit Pulse</title>"
                + "<style>body{background:#101922;color:#e7eff2;font:18px sans-serif;padding:32px;max-width:480px;margin:auto}"
                + "a{display:block;background:#72e6c1;color:#101922;border-radius:16px;padding:18px;text-align:center;text-decoration:none}</style>"
                + "<h1>" + message + "</h1><p>" + (success ? "Можно вернуться в Limit Pulse." : "Вернитесь в приложение и попробуйте снова.") + "</p>"
                + "<a href='intent://login-complete#Intent;scheme=limitpulse;package=dev.limitpulse.app;end'>Вернуться в приложение</a>"
                + (success ? "<script>window.location.replace('intent://login-complete#Intent;scheme=limitpulse;package=dev.limitpulse.app;end');</script>" : "")
                + "</html>";
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + data.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"
                + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(data); socket.getOutputStream().flush();
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
    }
}
