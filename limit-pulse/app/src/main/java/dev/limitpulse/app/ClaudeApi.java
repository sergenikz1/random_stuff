package dev.limitpulse.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Passive Claude usage reader following the Claude Code subscription OAuth protocol. */
final class ClaudeApi implements UsageSync.Source {
    static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    static final String AUTHORIZE = "https://claude.com/cai/oauth/authorize";
    static final String TOKEN = "https://platform.claude.com/v1/oauth/token";
    static final String MANUAL_REDIRECT = "https://platform.claude.com/oauth/code/callback";
    static final String USAGE = "https://api.anthropic.com/api/oauth/usage";
    // Reading usage needs only the profile scope: no inference, sessions or API key minting.
    static final String SCOPE = "user:profile";
    static final String BETA = "oauth-2025-04-20";

    /** Fallback flow: the Claude page shows {@code code#state}, which the user pastes into the app. */
    static final class ManualLogin {
        final String verifier, state, authorizationUrl;
        ManualLogin() throws Exception {
            verifier = BrowserLogin.random(); state = BrowserLogin.random();
            authorizationUrl = ClaudeApi.authorizationUrl(MANUAL_REDIRECT, BrowserLogin.challenge(verifier), state);
        }
        /** Returns the authorization code, or null when the pasted text is not from this login. */
        String code(String pasted) {
            String text = pasted == null ? "" : pasted.trim();
            int hash = text.indexOf('#');
            String code = hash < 0 ? text : text.substring(0, hash);
            if (hash >= 0 && !MessageDigest.isEqual(text.substring(hash + 1).getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8)))
                return null;
            return code.isEmpty() || code.length() > 4096 || code.matches(".*\\s.*") ? null : code;
        }
    }

    private final OpenAiApi.Transport transport;
    ClaudeApi() { this(new OpenAiApi.HttpsTransport()); }
    ClaudeApi(OpenAiApi.Transport transport) { this.transport = transport; }

    static String authorizationUrl(String redirectUri, String challenge, String state) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "true"); params.put("client_id", CLIENT_ID);
        params.put("response_type", "code"); params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPE); params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256"); params.put("state", state);
        return AUTHORIZE + "?" + BrowserLogin.query(params);
    }
    Credentials exchange(String code, String state, String verifier, String redirectUri, long now) throws Exception {
        OpenAiApi.Response response = transport.send("POST", TOKEN, new JSONObject().put("grant_type", "authorization_code")
                .put("code", code).put("redirect_uri", redirectUri).put("client_id", CLIENT_ID)
                .put("code_verifier", verifier).put("state", state), new HashMap<>());
        requireSuccess(response);
        return credentials(response.json, null, now);
    }
    @Override public Credentials refresh(Credentials old, long now) throws Exception {
        if (old.refreshToken.isEmpty()) throw new OpenAiApi.ApiException(401);
        OpenAiApi.Response response = transport.send("POST", TOKEN, new JSONObject().put("grant_type", "refresh_token")
                .put("refresh_token", old.refreshToken).put("client_id", CLIENT_ID).put("scope", SCOPE), new HashMap<>());
        requireSuccess(response);
        return credentials(response.json, old, now);
    }
    @Override public JSONObject usage(Credentials credentials) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + credentials.accessToken);
        headers.put("anthropic-beta", BETA);
        OpenAiApi.Response response = transport.send("GET", USAGE, null, headers);
        requireSuccess(response);
        return response.json;
    }
    static Credentials credentials(JSONObject tokens, Credentials previous, long now) throws JSONException {
        String access = tokens.getString("access_token");
        if (access.trim().isEmpty()) throw new JSONException("Missing access token");
        String refresh = tokens.optString("refresh_token", previous == null ? "" : previous.refreshToken);
        JSONObject account = tokens.optJSONObject("account"), organization = tokens.optJSONObject("organization");
        String email = account == null ? "" : account.optString("email_address", "");
        if (email.isEmpty() && previous != null) email = previous.email;
        String org = organization == null ? "" : organization.optString("uuid", "");
        if (org.isEmpty() && previous != null) org = previous.accountId;
        long expires = now + Math.max(60, tokens.optLong("expires_in", 3600)) * 1000;
        return new Credentials(access, refresh, org, email, expires);
    }
    private static void requireSuccess(OpenAiApi.Response response) throws OpenAiApi.ApiException {
        if (response.status < 200 || response.status >= 300) throw new OpenAiApi.ApiException(response.status);
    }
}
