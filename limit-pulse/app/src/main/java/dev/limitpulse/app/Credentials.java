package dev.limitpulse.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Credentials {
    public final String accessToken, refreshToken, accountId, email;
    public final long expiresAt;
    Credentials(String access, String refresh, String account, String email, long expiry) {
        accessToken = access; refreshToken = refresh; accountId = account; this.email = email; expiresAt = expiry;
    }
    static JSONObject claims(String token) {
        try {
            String[] parts = token.split("\\.");
            return new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONObject(); }
    }
    public static Credentials fromTokens(JSONObject tokens, Credentials previous, long now) throws JSONException {
        String access = tokens.getString("access_token");
        if (access.trim().isEmpty()) throw new JSONException("Missing access token");
        JSONObject id = claims(tokens.optString("id_token", ""));
        JSONObject accessClaims = claims(access);
        // Claims returned over authenticated TLS are used only as routing/display hints.
        JSONObject auth = id.optJSONObject("https://api.openai.com/auth");
        if (auth == null) auth = accessClaims.optJSONObject("https://api.openai.com/auth");
        String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
        if (account.isEmpty() && previous != null) account = previous.accountId;
        String email = id.optString("email", "");
        if (email.isEmpty() && previous != null) email = previous.email;
        String refresh = tokens.optString("refresh_token", previous == null ? "" : previous.refreshToken);
        long expires = accessClaims.optLong("exp", 0) * 1000;
        if (expires <= 0) expires = now + Math.max(60, tokens.optLong("expires_in", 3600)) * 1000;
        return new Credentials(access, refresh, account, email, expires);
    }
    JSONObject toJson() throws JSONException {
        return new JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
                .put("account_id", accountId).put("email", email).put("expires_at", expiresAt);
    }
    static Credentials fromJson(JSONObject json) throws JSONException {
        return new Credentials(json.getString("access_token"), json.optString("refresh_token", ""),
                json.optString("account_id", ""), json.optString("email", ""), json.getLong("expires_at"));
    }
}
