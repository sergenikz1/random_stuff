package dev.limitpulse.app;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BrowserLoginTest {
    @Test public void tokenExchangeUsesFormEncodingAndRefreshKeepsJson() throws Exception {
        JSONObject grant = new JSONObject().put("grant_type", "authorization_code").put("code", "a+b&c")
                .put("code_verifier", "verifier").put("redirect_uri", "http://127.0.0.1:1455/auth/callback");
        OpenAiApi.EncodedBody form = OpenAiApi.encodeBody(OpenAiApi.AUTH + "/oauth/token", grant);
        assertEquals("application/x-www-form-urlencoded", form.contentType);
        String text = new String(form.bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("code=a%2Bb%26c")); assertTrue(text.contains("code_verifier=verifier"));
        assertTrue(text.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"));
        OpenAiApi.EncodedBody refresh = OpenAiApi.encodeBody(OpenAiApi.AUTH + "/oauth/token", new JSONObject().put("grant_type", "refresh_token"));
        assertEquals("application/json", refresh.contentType);
    }
    private static String request(String path) { return "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:1455\r\n\r\n"; }
    @Test public void pkceMatchesRfc7636VectorAndDoesNotExposeVerifier() throws Exception {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", BrowserLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        try (BrowserLogin login = BrowserLogin.start(0, 2000)) {
            URI url = new URI(login.authorizationUrl);
            assertEquals("https", url.getScheme()); assertEquals("auth.openai.com", url.getHost());
            assertEquals("/oauth/authorize", url.getPath()); assertTrue(url.getQuery().contains("code_challenge_method=S256"));
            assertFalse(login.authorizationUrl.contains(login.verifier)); assertNotEquals(login.state, login.verifier);
            assertEquals(43, login.verifier.length());
        }
    }
    @Test public void callbackRequiresMatchingStatePathHostAndUniqueParameters() {
        assertNull(BrowserLogin.callback(request("/auth/callback?code=x&state=wrong"), 1455, "expected"));
        assertNull(BrowserLogin.callback(request("/auth/callback?code=x"), 1455, "expected"));
        assertNull(BrowserLogin.callback(request("/other?code=x&state=expected"), 1455, "expected"));
        assertNull(BrowserLogin.callback(request("/auth/callback?code=x&state=expected&state=expected"), 1455, "expected"));
        assertNull(BrowserLogin.callback(request("/auth/callback?code=x&state=expected").replace("127.0.0.1", "evil.invalid"), 1455, "expected"));
        assertNull(BrowserLogin.callback(request("//evil.invalid/auth/callback?code=x&state=expected"), 1455, "expected"));
        Map<String, String> valid = BrowserLogin.callback(request("/auth/callback?code=a%2Bb&state=expected"), 1455, "expected");
        assertEquals("a+b", valid.get("code"));
    }
    @Test public void browserCodeExchangeUsesActualRedirectAndPkceVerifier() throws Exception {
        try (BrowserLogin login = BrowserLogin.start(0, 2000)) {
            OpenAiApiTest.Fake fake = new OpenAiApiTest.Fake().reply(200, "{\"access_token\":\"browser-token\",\"refresh_token\":\"refresh\"}");
            Credentials credentials = new OpenAiApi(fake).exchangeBrowserCode(login, "one-time-code", OpenAiApiTest.NOW);
            assertEquals("browser-token", credentials.accessToken);
            JSONObject grant = fake.bodies.get(0);
            assertEquals("authorization_code", grant.getString("grant_type"));
            assertEquals(login.redirectUri, grant.getString("redirect_uri")); assertEquals(login.verifier, grant.getString("code_verifier"));
            assertEquals(1, fake.urls.size());
        }
    }
    @Test public void realLoopbackRejectsWrongStateThenExchangesOnceAndReturnsToAppWithoutLeakingSecrets() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (BrowserLogin login = BrowserLogin.start(0, 5000)) {
            AtomicInteger count = new AtomicInteger();
            Future<Credentials> completed = worker.submit(() -> login.await(code -> {
                assertEquals("test-code", code); count.incrementAndGet(); return OpenAiApiTest.credential(OpenAiApiTest.NOW);
            }));
            assertTrue(send(login, "code=test-code&state=wrong").startsWith("HTTP/1.1 400"));
            assertEquals(0, count.get());
            String response = send(login, "code=test-code&state=" + login.state);
            assertEquals("old-access", completed.get(3, TimeUnit.SECONDS).accessToken);
            assertEquals(1, count.get()); assertTrue(response.startsWith("HTTP/1.1 200"));
            assertTrue(response.contains("Cache-Control: no-store")); assertTrue(response.contains("Referrer-Policy: no-referrer"));
            assertTrue(response.contains("intent://login-complete"));
            assertFalse(response.contains("test-code")); assertFalse(response.contains(login.state));
            assertFalse(response.contains(login.verifier)); assertFalse(response.contains("old-access"));
        } finally { worker.shutdownNow(); }
    }
    @Test public void closingListenerCancelsWaitingAndReleasesPort() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BrowserLogin login = BrowserLogin.start(0, 5000); int port = new URI(login.redirectUri).getPort();
        Future<Credentials> completed = worker.submit(() -> login.await(code -> { fail(); return null; }));
        login.close();
        try { completed.get(2, TimeUnit.SECONDS); fail(); } catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
        worker.shutdownNow();
        try (BrowserLogin replacement = BrowserLogin.start(port, 1000)) { assertNotNull(replacement); }
    }
    @Test public void declinedAuthorizationDoesNotExchangeCode() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (BrowserLogin login = BrowserLogin.start(0, 5000)) {
            Future<Credentials> completed = worker.submit(() -> login.await(code -> { fail(); return null; }));
            assertTrue(send(login, "error=access_denied&state=" + login.state).contains("Вход не завершён"));
            try { completed.get(2, TimeUnit.SECONDS); fail(); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof BrowserLogin.Denied); }
        } finally { worker.shutdownNow(); }
    }
    @Test public void expiredLoginNeverAcceptsCode() throws Exception {
        try (BrowserLogin login = BrowserLogin.start(0, 0)) {
            try { login.await(code -> { fail(); return null; }); fail(); }
            catch (java.net.SocketTimeoutException expected) { assertEquals("Login expired", expected.getMessage()); }
        }
    }
    private static String send(BrowserLogin login, String query) throws Exception {
        int port = new URI(login.redirectUri).getPort();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET /auth/callback?" + query + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = socket.getInputStream().read(buffer)) != -1) data.write(buffer, 0, n);
            return data.toString("UTF-8");
        }
    }
}
