package dev.limitpulse.app;

import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ClaudeApiTest {
    static final long NOW = 1700000000000L;
    private static Map<String, String> query(String url) throws Exception {
        Map<String, String> params = new HashMap<>();
        for (String pair : new URI(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return params;
    }
    @Test public void authorizationUsesClaudeCodeClientPkceAndOnlyProfileScope() throws Exception {
        ClaudeApi.ManualLogin login = new ClaudeApi.ManualLogin();
        URI url = new URI(login.authorizationUrl);
        assertEquals("https", url.getScheme()); assertEquals("claude.com", url.getHost()); assertEquals("/cai/oauth/authorize", url.getPath());
        Map<String, String> params = query(login.authorizationUrl);
        assertEquals(ClaudeApi.CLIENT_ID, params.get("client_id")); assertEquals("code", params.get("response_type"));
        assertEquals("user:profile", params.get("scope")); assertEquals("S256", params.get("code_challenge_method"));
        assertEquals(BrowserLogin.challenge(login.verifier), params.get("code_challenge"));
        assertEquals(login.state, params.get("state")); assertEquals(ClaudeApi.MANUAL_REDIRECT, params.get("redirect_uri"));
        assertFalse(login.authorizationUrl.contains(login.verifier));
    }
    @Test public void pastedCodeMustBelongToThisLogin() throws Exception {
        ClaudeApi.ManualLogin login = new ClaudeApi.ManualLogin();
        assertEquals("abc", login.code("  abc#" + login.state + "\n"));
        assertEquals("abc", login.code("abc"));
        assertNull(login.code("abc#forged"));
        assertNull(login.code("#" + login.state)); assertNull(login.code("")); assertNull(login.code(null));
        assertNull(login.code("a b#" + login.state));
    }
    @Test public void loopbackLoginUsesLocalhostCallbackAndValidatesIt() throws Exception {
        try (BrowserLogin login = BrowserLogin.startClaude(2000)) {
            int port = new URI(login.redirectUri).getPort();
            assertEquals("http://localhost:" + port + "/callback", login.redirectUri);
            assertEquals(login.redirectUri, query(login.authorizationUrl).get("redirect_uri"));
            assertEquals("claude.com", new URI(login.authorizationUrl).getHost());
            String valid = "GET /callback?code=c&state=" + login.state + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n";
            assertEquals("c", BrowserLogin.callback(valid, "localhost:" + port, "/callback", login.state).get("code"));
            assertNull(BrowserLogin.callback(valid.replace("localhost", "evil.invalid"), "localhost:" + port, "/callback", login.state));
            assertNull(BrowserLogin.callback(valid, "localhost:" + port, "/callback", "other"));
        }
    }
    @Test public void exchangeSendsJsonGrantWithStateAndReadsAccount() throws Exception {
        OpenAiApiTest.Fake fake = new OpenAiApiTest.Fake().reply(200, "{\"access_token\":\"sk-ant-oat-test\",\"refresh_token\":\"sk-ant-ort-test\",\"expires_in\":28800,"
                + "\"account\":{\"uuid\":\"a\",\"email_address\":\"me@example.invalid\"},\"organization\":{\"uuid\":\"org-1\"}}");
        Credentials result = new ClaudeApi(fake).exchange("code", "state", "verifier", ClaudeApi.MANUAL_REDIRECT, NOW);
        assertEquals(ClaudeApi.TOKEN, fake.urls.get(0));
        JSONObject body = fake.bodies.get(0);
        assertEquals("authorization_code", body.getString("grant_type")); assertEquals("state", body.getString("state"));
        assertEquals("verifier", body.getString("code_verifier")); assertEquals(ClaudeApi.CLIENT_ID, body.getString("client_id"));
        assertEquals(ClaudeApi.MANUAL_REDIRECT, body.getString("redirect_uri"));
        assertEquals("application/json", OpenAiApi.encodeBody(ClaudeApi.TOKEN, body).contentType);
        assertEquals("me@example.invalid", result.email); assertEquals("org-1", result.accountId);
        assertEquals(NOW + 28800000L, result.expiresAt); assertEquals("sk-ant-ort-test", result.refreshToken);
    }
    @Test public void usageSendsBearerAndOauthBetaThenRefreshesRotatedTokenOn401() throws Exception {
        OpenAiApiTest.Fake fake = new OpenAiApiTest.Fake().reply(401, "{}")
                .reply(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}")
                .reply(200, "{\"five_hour\":{\"utilization\":10,\"resets_at\":null}}");
        Credentials[] saved = new Credentials[1];
        JSONObject usage = UsageSync.fetch(new ClaudeApi(fake), OpenAiApiTest.credential(NOW + 3600000), NOW, c -> saved[0] = c);
        assertTrue(usage.has("five_hour"));
        assertEquals(ClaudeApi.USAGE, fake.urls.get(0));
        assertEquals("Bearer old-access", fake.headers.get(0).get("Authorization"));
        assertEquals("oauth-2025-04-20", fake.headers.get(0).get("anthropic-beta"));
        JSONObject refresh = fake.bodies.get(1);
        assertEquals("refresh_token", refresh.getString("grant_type")); assertEquals("old-refresh", refresh.getString("refresh_token"));
        assertEquals("user:profile", refresh.getString("scope"));
        assertEquals("new-refresh", saved[0].refreshToken); assertEquals("test@example.invalid", saved[0].email);
        assertEquals("Bearer new-access", fake.headers.get(2).get("Authorization"));
    }
    @Test public void claudeUsageMapsUtilizationAndIsoResets() throws Exception {
        LimitData data = LimitData.claude(new JSONObject("{\"five_hour\":{\"utilization\":23.5,\"resets_at\":\"2023-11-14T23:13:20.123456+00:00\"},"
                + "\"seven_day\":{\"utilization\":61,\"resets_at\":\"2023-11-20T00:00:00Z\"},\"seven_day_opus\":{\"utilization\":80,\"resets_at\":null},"
                + "\"seven_day_sonnet\":null,\"extra_usage\":{\"is_enabled\":true,\"utilization\":12.7}}"), NOW);
        assertEquals(76, data.primary.remaining()); assertEquals(1700003600L, data.primary.resetAt); assertEquals("За 5 часов", data.primary.label());
        assertEquals(39, data.secondary.remaining()); assertEquals(1700438400L, data.secondary.resetAt); assertEquals("За неделю", data.secondary.label());
        assertEquals(1, data.extra.size()); assertEquals("Opus · неделя", data.extra.get(0).name); assertEquals(20, data.extra.get(0).primary.remaining());
        assertEquals(0, data.extra.get(0).primary.resetAt); assertNull(data.extra.get(0).secondary);
        assertEquals("12% месячного лимита использовано", data.credits); assertNull(data.allowed);
        assertFalse(data.stale(NOW)); assertTrue(data.stale(1700003600000L));
    }
    @Test public void missingClaudeWindowsAreUnknownAndForeignResponsesRejected() throws Exception {
        LimitData data = LimitData.claude(new JSONObject("{\"five_hour\":null,\"seven_day\":{\"utilization\":\"5\"}}"), NOW);
        assertNull(data.primary); assertNull(data.secondary); assertNull(data.credits);
        try { LimitData.claude(new JSONObject("{\"plan_type\":\"plus\"}"), NOW); fail(); }
        catch (org.json.JSONException expected) { }
    }
    @Test public void errorsDoNotPretendDataIsFresh() {
        assertEquals("Слишком частые запросы к Claude. Подождите и обновите позже.", ClaudeRepository.message(new OpenAiApi.ApiException(429), false));
        assertTrue(ClaudeRepository.message(new OpenAiApi.ApiException(401), false).contains("истекла"));
        assertTrue(ClaudeRepository.message(new OpenAiApi.ApiException(400), true).contains("код входа"));
    }
}
