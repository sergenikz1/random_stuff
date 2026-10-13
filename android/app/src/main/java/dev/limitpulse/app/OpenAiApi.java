package dev.limitpulse.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Passive usage reader following the open-source Codex OAuth protocol. */
public final class OpenAiApi {
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String VERIFY_URL = "https://auth.openai.com/codex/device";
    static final String AUTH = "https://auth.openai.com";
    static final String USAGE = "https://chatgpt.com/backend-api/wham/usage";
    public interface Transport {
        Response send(String method, String url, JSONObject body, Map<String, String> headers) throws Exception;
    }
    public static final class Response {
        public final int status;
        public final JSONObject json;
        public Response(int status, JSONObject json) { this.status = status; this.json = json; }
    }
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status) { super("HTTP " + status); this.status = status; }
    }
    public static final class Device {
        public final String id, code;
        public final int intervalSeconds;
        Device(JSONObject json) throws JSONException {
            id = json.getString("device_auth_id");
            code = json.optString("user_code", json.optString("usercode", ""));
            if (id.isEmpty() || code.isEmpty()) throw new JSONException("Missing device code");
            intervalSeconds = Math.max(5, Math.min(60, json.optInt("interval", 5)));
        }
    }
    private final Transport transport;
    public OpenAiApi() { this(new HttpsTransport()); }
    public OpenAiApi(Transport transport) { this.transport = transport; }
    public Device beginLogin() throws Exception {
        Response response = post("/api/accounts/deviceauth/usercode", new JSONObject().put("client_id", CLIENT_ID));
        requireSuccess(response);
        return new Device(response.json);
    }
    /** Returns null while pending. Does not repeat a one-time token exchange. */
    public Credentials pollLogin(Device device, long now) throws Exception {
        Response response = post("/api/accounts/deviceauth/token",
                new JSONObject().put("device_auth_id", device.id).put("user_code", device.code));
        if (response.status == 403 || response.status == 404) return null;
        requireSuccess(response);
        JSONObject grant = new JSONObject().put("grant_type", "authorization_code").put("client_id", CLIENT_ID)
                .put("code", response.json.getString("authorization_code"))
                .put("code_verifier", response.json.getString("code_verifier"))
                .put("redirect_uri", AUTH + "/deviceauth/callback");
        Response tokens = post("/oauth/token", grant);
        requireSuccess(tokens);
        return Credentials.fromTokens(tokens.json, null, now);
    }
    public Credentials refresh(Credentials old, long now) throws Exception {
        if (old.refreshToken.isEmpty()) throw new ApiException(401);
        Response response = post("/oauth/token", new JSONObject().put("grant_type", "refresh_token")
                .put("client_id", CLIENT_ID).put("refresh_token", old.refreshToken));
        requireSuccess(response);
        return Credentials.fromTokens(response.json, old, now);
    }
    Credentials exchangeBrowserCode(BrowserLogin login, String code, long now) throws Exception {
        Response response = post("/oauth/token", new JSONObject().put("grant_type", "authorization_code")
                .put("client_id", CLIENT_ID).put("code", code)
                .put("code_verifier", login.verifier).put("redirect_uri", login.redirectUri));
        requireSuccess(response);
        return Credentials.fromTokens(response.json, null, now);
    }
    public JSONObject usage(Credentials credentials) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + credentials.accessToken);
        if (!credentials.accountId.isEmpty()) headers.put("ChatGPT-Account-Id", credentials.accountId);
        Response response = transport.send("GET", USAGE, null, headers);
        requireSuccess(response);
        return response.json;
    }
    private Response post(String path, JSONObject body) throws Exception {
        return transport.send("POST", AUTH + path, body, new HashMap<>());
    }
    private static void requireSuccess(Response response) throws ApiException {
        if (response.status < 200 || response.status >= 300) throw new ApiException(response.status);
    }
    static final class EncodedBody {
        final String contentType;
        final byte[] bytes;
        EncodedBody(String type, String body) { contentType = type; bytes = body.getBytes(StandardCharsets.UTF_8); }
    }
    static EncodedBody encodeBody(String url, JSONObject body) throws Exception {
        // Codex sends authorization-code grants as form data and refresh grants as JSON.
        if ((AUTH + "/oauth/token").equals(url) && "authorization_code".equals(body.optString("grant_type"))) {
            StringBuilder form = new StringBuilder();
            java.util.Iterator<String> names = body.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (form.length() > 0) form.append('&');
                form.append(URLEncoder.encode(name, "UTF-8")).append('=')
                        .append(URLEncoder.encode(body.getString(name), "UTF-8"));
            }
            return new EncodedBody("application/x-www-form-urlencoded", form.toString());
        }
        return new EncodedBody("application/json", body.toString());
    }
    static final class HttpsTransport implements Transport {
        @Override public Response send(String method, String urlString, JSONObject body, Map<String, String> headers) throws Exception {
            URL url = new URL(urlString);
            if (!"https".equals(url.getProtocol()) || !("auth.openai.com".equals(url.getHost()) || "chatgpt.com".equals(url.getHost())))
                throw new IOException("Unsupported destination");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            // Credentials must never be forwarded to a redirect target.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "LimitPulse/" + BuildConfig.VERSION_NAME + " Android");
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            try {
                if (body != null) {
                    EncodedBody encoded = encodeBody(urlString, body);
                    byte[] bytes = encoded.bytes;
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", encoded.contentType);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                // Do not parse or display potentially sensitive server error bodies.
                if (status < 200 || status >= 300) return new Response(status, new JSONObject());
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 1024 * 1024) throw new IOException("Response too large");
                        output.write(buffer, 0, count);
                    }
                    return new Response(status, new JSONObject(output.toString("UTF-8")));
                }
            } finally { connection.disconnect(); }
        }
    }
}
