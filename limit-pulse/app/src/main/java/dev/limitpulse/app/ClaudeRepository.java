package dev.limitpulse.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/** Claude subscription account: same serialized-writer and generation-guard model as {@link AccountRepository}. */
final class ClaudeRepository {
    private final Context context;
    private final ClaudeApi api;
    private final SecureStore secure;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> refreshCompletions = new ArrayList<>();
    private volatile long generation;
    private volatile Credentials credentials;
    volatile LimitData data;
    volatile BrowserLogin browser;
    volatile ClaudeApi.ManualLogin manual;
    private volatile boolean browserStarting;
    volatile String error = "";
    volatile boolean loading;
    private boolean refreshing;
    private long lastAttemptElapsed;

    ClaudeRepository(Context context, ClaudeApi api) {
        this.context = context.getApplicationContext(); this.api = api;
        secure = new SecureStore(context, "secure_claude");
        cache = context.getSharedPreferences("claude_usage_cache", Context.MODE_PRIVATE);
        try { credentials = secure.load(); }
        catch (Exception ignored) {
            secure.clear(); cache.edit().clear().apply();
            error = "Защищённое хранилище недоступно. Войдите в Claude снова.";
        }
        if (credentials != null) {
            try {
                JSONObject saved = new JSONObject(cache.getString("snapshot", ""));
                data = LimitData.claude(saved.getJSONObject("data"), saved.getLong("fetched_at"));
            } catch (Exception ignored) { data = null; }
            if (error.isEmpty()) error = cache.getString("error", "");
        } else cache.edit().clear().apply();
    }
    boolean connected() { return credentials != null; }
    String accountLabel() {
        Credentials c = credentials;
        if (c == null) return "Claude не подключён";
        return c.email.isEmpty() ? "Аккаунт Claude подключён" : c.email;
    }
    void observe(Runnable listener) { listeners.add(listener); }
    void removeObserver(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        for (Runnable listener : listeners) listener.run();
        ClaudeWidget.updateAll(context);
        ResetReminders.reconcile(context, ResetReminders.CLAUDE, data, System.currentTimeMillis());
        WidgetTicker.schedule(context);
    }
    boolean browserPending() { return browserStarting || browser != null; }
    boolean loginPending() { return browserPending() || manual != null; }

    void beginBrowserLogin(java.util.function.Consumer<String> openBrowser) {
        if (loading || loginPending() || PulseApplication.repository(context).browserPending()) return;
        final long epoch = ++generation;
        browserStarting = true; loading = true; error = ""; changed();
        try { context.startForegroundService(new android.content.Intent(context, LoginService.class)); }
        catch (Exception failure) {
            browserStarting = false; loading = false;
            error = "Не удалось начать вход. Откройте приложение и попробуйте ещё раз."; changed(); return;
        }
        worker.execute(() -> {
            try (BrowserLogin session = BrowserLogin.startClaude(BrowserLogin.TIMEOUT_MS)) {
                FutureTask<Boolean> ready = new FutureTask<>(() -> {
                    if (generation != epoch) return false;
                    browser = session; browserStarting = false; changed();
                    openBrowser.accept(session.authorizationUrl); return true;
                });
                main.post(ready);
                if (!ready.get()) return;
                session.await(code -> {
                    Credentials next = api.exchange(code, session.state, session.verifier, session.redirectUri, System.currentTimeMillis());
                    if (!persistLogin(next, epoch)) throw new java.io.IOException("Login cancelled");
                    return next;
                });
                main.post(() -> {
                    if (generation != epoch) return;
                    finishBrowserLogin(); changed();
                    RefreshJob.schedule(context); refresh(null);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != epoch) return;
                    finishBrowserLogin();
                    if (failure instanceof BrowserLogin.Denied) error = "Вы отменили вход в Claude. Можно попробовать снова.";
                    else if (failure instanceof java.net.SocketTimeoutException) error = "Время ожидания входа истекло. Нажмите «Войти в Claude» ещё раз.";
                    else error = message(failure, true);
                    changed();
                });
            }
        });
    }
    private void finishBrowserLogin() {
        BrowserLogin session = browser; boolean pending = browserPending();
        browser = null; browserStarting = false; loading = false;
        if (session != null) session.close();
        if (pending) context.stopService(new android.content.Intent(context, LoginService.class));
    }
    /** Starts the paste-the-code fallback and returns the page to open. */
    String beginManualLogin() {
        if (loading || loginPending()) return null;
        generation++;
        try { manual = new ClaudeApi.ManualLogin(); }
        catch (Exception failure) { error = "Не удалось подготовить вход."; changed(); return null; }
        error = ""; changed();
        return manual.authorizationUrl;
    }
    void submitManualCode(String pasted) {
        ClaudeApi.ManualLogin login = manual;
        if (login == null || loading) return;
        String code = login.code(pasted);
        if (code == null) { error = "Код не подходит. Скопируйте его целиком со страницы Claude после входа."; changed(); return; }
        final long epoch = generation;
        loading = true; error = ""; changed();
        worker.execute(() -> {
            try {
                Credentials next = api.exchange(code, login.state, login.verifier, ClaudeApi.MANUAL_REDIRECT, System.currentTimeMillis());
                if (!persistLogin(next, epoch)) return;
                main.post(() -> {
                    if (generation != epoch) return;
                    manual = null; loading = false; changed();
                    RefreshJob.schedule(context); refresh(null);
                });
            } catch (Exception failure) {
                // An authorization code is single-use: a failed exchange needs a fresh login.
                main.post(() -> {
                    if (generation != epoch) return;
                    manual = null; loading = false; error = message(failure, true); changed();
                });
            }
        });
    }
    private boolean persistLogin(Credentials next, long epoch) throws Exception {
        FutureTask<Boolean> persist = new FutureTask<>(() -> {
            if (generation != epoch) return false;
            secure.save(next); cache.edit().clear().apply();
            credentials = next; data = null; error = ""; lastAttemptElapsed = 0;
            return true;
        });
        main.post(persist);
        return persist.get();
    }
    void cancelLogin() { generation++; manual = null; finishBrowserLogin(); changed(); }
    void disconnect() {
        if (!secure.clear()) {
            error = "Не удалось удалить локальную сессию. Освободите место и попробуйте ещё раз.";
            changed(); return;
        }
        generation++; manual = null; finishBrowserLogin(); credentials = null; data = null;
        loading = false; error = ""; lastAttemptElapsed = 0;
        cache.edit().clear().apply(); RefreshJob.cancel(context);
        ResetReminders.clearAccount(context, ResetReminders.CLAUDE); changed();
    }
    void refresh(Runnable completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> refresh(completion)); return; }
        if (refreshing) { if (completion != null) refreshCompletions.add(completion); return; }
        long elapsed = SystemClock.elapsedRealtime();
        if (credentials == null || loginPending() || (lastAttemptElapsed > 0 && elapsed - lastAttemptElapsed < 60000)) {
            if (completion != null) completion.run(); return;
        }
        if (completion != null) refreshCompletions.add(completion);
        refreshing = true; loading = true; lastAttemptElapsed = elapsed;
        final long epoch = generation;
        final Credentials original = credentials;
        changed();
        worker.execute(() -> {
            try {
                JSONObject raw = UsageSync.fetch(api, original, System.currentTimeMillis(), rotated -> persistCredentials(rotated, epoch));
                long now = System.currentTimeMillis();
                LimitData snapshot = LimitData.claude(raw, now);
                JSONObject saved = new JSONObject().put("data", raw).put("fetched_at", now);
                main.post(() -> {
                    if (generation == epoch) {
                        data = snapshot; error = "";
                        cache.edit().putString("snapshot", saved.toString()).remove("error").apply();
                    }
                    finishRefresh(epoch);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation == epoch) {
                        error = message(failure, false);
                        cache.edit().putString("error", error).apply();
                    }
                    finishRefresh(epoch);
                });
            }
        });
    }
    private void persistCredentials(Credentials rotated, long epoch) throws Exception {
        // Claude rotates refresh tokens: persist before the next request can use them.
        FutureTask<Boolean> persist = new FutureTask<>(() -> {
            if (generation != epoch) return false;
            secure.save(rotated); credentials = rotated; return true;
        });
        main.post(persist);
        if (!persist.get()) throw new java.io.IOException("Account changed");
    }
    private void finishRefresh(long epoch) {
        refreshing = false;
        if (generation == epoch) loading = false;
        changed();
        List<Runnable> completions = new ArrayList<>(refreshCompletions); refreshCompletions.clear();
        for (Runnable completion : completions) completion.run();
    }
    static String message(Exception failure, boolean login) {
        if (failure instanceof OpenAiApi.ApiException) {
            int status = ((OpenAiApi.ApiException) failure).status;
            if (login && (status == 400 || status == 401)) return "Claude не принял код входа. Начните вход заново.";
            if (status == 401 || status == 400) return "Сессия Claude истекла. Отключите Claude и войдите снова.";
            if (status == 403) return login ? "Claude отклонил вход для этого аккаунта." : "Anthropic ограничил доступ к данным. Попробуйте позже или откройте Claude.";
            if (status == 404) return "Источник лимитов Claude сейчас недоступен.";
            if (status == 429) return "Слишком частые запросы к Claude. Подождите и обновите позже.";
            return "Claude временно недоступен (HTTP " + status + ").";
        }
        if (failure instanceof org.json.JSONException) return "Формат данных Claude изменился. Лимиты не обновлены.";
        return "Не удалось подключиться к Claude. Проверьте интернет и попробуйте снова.";
    }
}
