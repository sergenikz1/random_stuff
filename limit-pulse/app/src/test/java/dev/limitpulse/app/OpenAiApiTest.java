package dev.limitpulse.app;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class OpenAiApiTest {
    static final long NOW = 1700000000000L;
    static class Fake implements OpenAiApi.Transport {
        final ArrayDeque<OpenAiApi.Response> replies = new ArrayDeque<>();
        final List<JSONObject> bodies = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        Fake reply(int code, String json) throws Exception { replies.add(new OpenAiApi.Response(code, new JSONObject(json))); return this; }
        @Override public OpenAiApi.Response send(String method, String url, JSONObject body, Map<String, String> headers) {
            urls.add(url); bodies.add(body); this.headers.add(headers); return replies.remove();
        }
    }
    static Credentials credential(long expiry) { return new Credentials("old-access", "old-refresh", "account-1", "test@example.invalid", expiry); }
    @Test public void deviceCodeAliasesAndStringPollingIntervalWork() throws Exception {
        Fake fake = new Fake().reply(200, "{\"device_auth_id\":\"device\",\"usercode\":\"ABCD-EFGH\",\"interval\":\"5\"}");
        OpenAiApi.Device device = new OpenAiApi(fake).beginLogin();
        assertEquals("ABCD-EFGH", device.code); assertEquals(5, device.intervalSeconds);
        assertEquals(OpenAiApi.CLIENT_ID, fake.bodies.get(0).getString("client_id"));
    }
    @Test public void pendingLoginDoesNotExchangeTokens() throws Exception {
        Fake fake = new Fake().reply(403, "{}").reply(404, "{}"); OpenAiApi api = new OpenAiApi(fake);
        OpenAiApi.Device device = new OpenAiApi.Device(new JSONObject("{\"device_auth_id\":\"d\",\"user_code\":\"c\"}"));
        assertNull(api.pollLogin(device, NOW)); assertNull(api.pollLogin(device, NOW)); assertEquals(2, fake.urls.size());
    }
    @Test public void completedLoginExchangesCodeWithVerifierOnce() throws Exception {
        Fake fake = new Fake().reply(200, "{\"authorization_code\":\"code\",\"code_verifier\":\"verifier\"}")
                .reply(200, "{\"access_token\":\"token\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        Credentials result = new OpenAiApi(fake).pollLogin(new OpenAiApi.Device(new JSONObject("{\"device_auth_id\":\"d\",\"user_code\":\"c\"}")), NOW);
        assertEquals("token", result.accessToken); assertEquals("verifier", fake.bodies.get(1).getString("code_verifier"));
        assertEquals(OpenAiApi.AUTH + "/deviceauth/callback", fake.bodies.get(1).getString("redirect_uri")); assertEquals(2, fake.urls.size());
    }
    @Test public void refreshRetainsIdentityAndRefreshTokenWhenOmitted() throws Exception {
        Fake fake = new Fake().reply(200, "{\"access_token\":\"new\",\"expires_in\":1800}");
        Credentials next = new OpenAiApi(fake).refresh(credential(NOW), NOW);
        assertEquals("old-refresh", next.refreshToken); assertEquals("account-1", next.accountId); assertEquals("test@example.invalid", next.email);
    }
    @Test public void jwtClaimsSupplyAccountAndTokenExpiry() throws Exception {
        String claims = "{\"exp\":1700003600,\"email\":\"me@example.invalid\",\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"workspace\"}}";
        String jwt = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
        Credentials value = Credentials.fromTokens(new JSONObject().put("access_token", jwt).put("id_token", jwt), null, NOW);
        assertEquals("workspace", value.accountId); assertEquals(1700003600000L, value.expiresAt);
    }
    @Test public void usageSendsCredentialsOnlyToFixedUsageEndpoint() throws Exception {
        Fake fake = new Fake().reply(200, "{\"plan_type\":\"plus\"}"); new OpenAiApi(fake).usage(credential(NOW));
        assertEquals(OpenAiApi.USAGE, fake.urls.get(0)); assertEquals("Bearer old-access", fake.headers.get(0).get("Authorization"));
        assertEquals("account-1", fake.headers.get(0).get("ChatGPT-Account-Id"));
    }
    @Test public void unauthorizedRefreshesOnceAndPersistsBeforeRetry() throws Exception {
        Fake fake = new Fake().reply(401, "{}").reply(200, "{\"access_token\":\"new\",\"refresh_token\":\"rotated\"}").reply(200, "{\"plan_type\":\"plus\"}");
        List<Credentials> saved = new ArrayList<>();
        JSONObject result = UsageSync.fetch(new OpenAiApi(fake), credential(NOW + 3600000), NOW, next -> { assertEquals(2, fake.urls.size()); saved.add(next); });
        assertEquals("plus", result.getString("plan_type")); assertEquals("rotated", saved.get(0).refreshToken);
        assertEquals("Bearer new", fake.headers.get(2).get("Authorization"));
    }
    @Test public void expiryRefreshesBeforeUsage() throws Exception {
        Fake fake = new Fake().reply(200, "{\"access_token\":\"new\"}").reply(200, "{\"plan_type\":\"plus\"}");
        UsageSync.fetch(new OpenAiApi(fake), credential(NOW), NOW, next -> { });
        assertEquals(OpenAiApi.AUTH + "/oauth/token", fake.urls.get(0)); assertEquals(2, fake.urls.size());
    }
    @Test public void forbiddenDoesNotRefreshOrReplay() throws Exception {
        Fake fake = new Fake().reply(403, "{}");
        try { UsageSync.fetch(new OpenAiApi(fake), credential(NOW + 3600000), NOW, next -> fail()); fail(); }
        catch (OpenAiApi.ApiException expected) { assertEquals(403, expected.status); }
        assertEquals(1, fake.urls.size());
    }
    @Test public void secondUnauthorizedDoesNotLoop() throws Exception {
        Fake fake = new Fake().reply(401, "{}").reply(200, "{\"access_token\":\"new\"}").reply(401, "{}");
        try { UsageSync.fetch(new OpenAiApi(fake), credential(NOW + 3600000), NOW, next -> { }); fail(); }
        catch (OpenAiApi.ApiException expected) { assertEquals(401, expected.status); }
        assertEquals(3, fake.urls.size());
    }
    @Test public void persistFailureStopsUseOfRotatedCredentials() throws Exception {
        Fake fake = new Fake().reply(200, "{\"access_token\":\"new\"}");
        try { UsageSync.fetch(new OpenAiApi(fake), credential(NOW), NOW, next -> { throw new java.io.IOException("storage unavailable"); }); fail(); }
        catch (java.io.IOException expected) { assertEquals("storage unavailable", expected.getMessage()); }
        assertEquals(1, fake.urls.size());
    }
    @Test public void transportRejectsCleartextAndUnapprovedDestinationsBeforeNetwork() throws Exception {
        for (String url : new String[] {"http://auth.openai.com/oauth/token", "https://example.invalid/"}) {
            try { new OpenAiApi.HttpsTransport().send("GET", url, null, new java.util.HashMap<>()); fail(); }
            catch (java.io.IOException expected) { assertEquals("Unsupported destination", expected.getMessage()); }
        }
    }
}
