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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One serialized account writer, with generation guards against logout/login races. */
final class AccountRepository {
    private final Context context;
    private final OpenAiApi api;
    private final SecureStore secure;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> refreshCompletions = new ArrayList<>();
    private volatile long generation;
    private volatile Credentials credentials;
    volatile LimitData data;
    volatile OpenAiApi.Device device;
    volatile BrowserLogin browser;
    private volatile boolean browserStarting;
    volatile String error = "";
    volatile boolean loading;
    private boolean refreshing;
    private long lastAttemptElapsed;
    private long loginDeadline;

    AccountRepository(Context context, OpenAiApi api) {
        this.context = context.getApplicationContext(); this.api = api;
        secure = new SecureStore(context);
        cache = context.getSharedPreferences("usage_cache", Context.MODE_PRIVATE);
        try { credentials = secure.load(); }
        catch (Exception ignored) {
            secure.clear(); cache.edit().clear().apply();
            error = "Защищённое хранилище недоступно. Войдите снова.";
        }
        if (credentials != null) {
            try {
                JSONObject saved = new JSONObject(cache.getString("snapshot", ""));
                data = LimitData.parse(saved.getJSONObject("data"), saved.getLong("fetched_at"));
            } catch (Exception ignored) { data = null; }
            if (error.isEmpty()) error = cache.getString("error", "");
        } else cache.edit().clear().apply();
    }
    boolean connected() { return credentials != null; }
    String accountLabel() {
        Credentials c = credentials;
        if (c == null) return "Аккаунт не подключён";
        return c.email.isEmpty() ? "Аккаунт OpenAI подключён" : c.email;
    }
    void observe(Runnable listener) { listeners.add(listener); }
    void removeObserver(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        for (Runnable listener : listeners) listener.run();
        LimitsWidget.updateAll(context);
        ResetReminders.reconcile(context, data, System.currentTimeMillis());
        WidgetTicker.schedule(context);
    }
    boolean browserPending() { return browserStarting || browser != null; }
    void beginBrowserLogin(java.util.function.Consumer<String> openBrowser) {
        if (loading || device != null || browserPending() || PulseApplication.claude(context).browserPending()) return;
        final long epoch = ++generation;
        browserStarting = true; loading = true; error = ""; changed();
        try { context.startForegroundService(new android.content.Intent(context, LoginService.class)); }
        catch (Exception failure) {
            browserStarting = false; loading = false;
            error = "Не удалось начать вход. Откройте приложение и попробуйте ещё раз."; changed(); return;
        }
        worker.execute(() -> {
            try (BrowserLogin session = BrowserLogin.start()) {
                java.util.concurrent.FutureTask<Boolean> ready = new java.util.concurrent.FutureTask<>(() -> {
                    if (generation != epoch) return false;
                    browser = session; browserStarting = false; changed();
                    openBrowser.accept(session.authorizationUrl); return true;
                });
                main.post(ready);
                if (!ready.get()) return;
                session.await(code -> {
                    Credentials next = api.exchangeBrowserCode(session, code, System.currentTimeMillis());
                    java.util.concurrent.FutureTask<Boolean> persist = new java.util.concurrent.FutureTask<>(() -> {
                        if (generation != epoch) return false;
                        secure.save(next); cache.edit().clear().apply();
                        credentials = next; data = null; error = ""; lastAttemptElapsed = 0;
                        return true;
                    });
                    main.post(persist);
                    if (!persist.get()) throw new java.io.IOException("Login cancelled");
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
                    if (failure instanceof java.net.BindException)
                        error = "Вход уже выполняется в другом приложении. Закройте его и повторите вход.";
                    else if (failure instanceof BrowserLogin.Denied)
                        error = "Вы отменили вход в OpenAI. Можно попробовать снова.";
                    else if (failure instanceof java.net.SocketTimeoutException)
                        error = "Время ожидания входа истекло. Нажмите «Войти» ещё раз.";
                    else error = message(failure, false);
                    changed();
                });
            }
        });
    }
    private void finishBrowserLogin() {
        BrowserLogin session = browser; boolean pending = browserPending();
        browser = null; browserStarting = false; loading = false;
        if (session != null) session.close();
        // The service may be keeping a Claude login alive instead.
        if (pending) context.stopService(new android.content.Intent(context, LoginService.class));
    }
    void beginLogin() {
        if (loading || device != null || browserPending()) return;
        final long epoch = ++generation;
        loading = true; error = ""; changed();
        worker.execute(() -> {
            try {
                OpenAiApi.Device next = api.beginLogin();
                main.post(() -> {
                    if (generation != epoch) return;
                    device = next; loginDeadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000L;
                    loading = false; changed(); schedulePoll(epoch, next);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != epoch) return;
                    loading = false; error = message(failure, true); changed();
                });
            }
        });
    }
    private void schedulePoll(long epoch, OpenAiApi.Device requested) {
        worker.schedule(() -> {
            if (generation != epoch || device != requested) return;
            if (SystemClock.elapsedRealtime() >= loginDeadline) {
                main.post(() -> endLogin(epoch, "Код истёк. Начните вход заново.")); return;
            }
            try {
                Credentials next = api.pollLogin(requested, System.currentTimeMillis());
                if (next == null) { schedulePoll(epoch, requested); return; }
                main.post(() -> {
                    if (generation != epoch) return;
                    try {
                        secure.save(next);
                        cache.edit().clear().apply();
                        credentials = next; data = null; device = null; error = "";
                        lastAttemptElapsed = 0; changed();
                        RefreshJob.schedule(context); refresh(null);
                    } catch (Exception ignored) { endLogin(epoch, "Не удалось сохранить вход в защищённом хранилище."); }
                });
            } catch (Exception failure) {
                // Never blindly replay the one-time authorization-code exchange.
                main.post(() -> endLogin(epoch, message(failure, true)));
            }
        }, requested.intervalSeconds, TimeUnit.SECONDS);
    }
    private void endLogin(long epoch, String reason) {
        if (generation != epoch) return;
        device = null; loading = false; error = reason; changed();
    }
    void cancelLogin() { generation++; device = null; finishBrowserLogin(); changed(); }
    void disconnect() {
        if (!secure.clear()) {
            error = "Не удалось удалить локальную сессию. Освободите место и попробуйте ещё раз.";
            changed(); return;
        }
        generation++; device = null; finishBrowserLogin(); credentials = null; data = null;
        loading = false; error = ""; lastAttemptElapsed = 0;
        cache.edit().clear().apply(); RefreshJob.cancel(context); ResetReminders.clearAccount(context); changed();
    }
    void refresh(Runnable completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> refresh(completion)); return; }
        if (refreshing) { if (completion != null) refreshCompletions.add(completion); return; }
        long elapsed = SystemClock.elapsedRealtime();
        if (credentials == null || device != null || browserPending() || (lastAttemptElapsed > 0 && elapsed - lastAttemptElapsed < 60000)) {
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
                LimitData snapshot = LimitData.parse(raw, now);
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
        // Serialize persistence with main-thread logout, before using a rotating refresh token again.
        java.util.concurrent.FutureTask<Boolean> persist = new java.util.concurrent.FutureTask<>(() -> {
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
            if (status == 401 || (status == 400 && !login)) return "Сессия истекла. Отключите аккаунт и войдите снова.";
            if (status == 403) return login ? "OpenAI отклонил вход. Разрешите вход по коду устройства в настройках безопасности ChatGPT."
                    : "OpenAI ограничил доступ к данным. Попробуйте позже или откройте Codex.";
            if (status == 404) return "Этот способ входа или источник лимитов сейчас недоступен.";
            if (status == 429) return "Слишком частые запросы. Подождите и обновите позже.";
            return "OpenAI временно недоступен (HTTP " + status + ").";
        }
        if (failure instanceof org.json.JSONException) return "Формат данных OpenAI изменился. Лимиты не обновлены.";
        return "Не удалось подключиться. Проверьте интернет и попробуйте снова.";
    }
}
