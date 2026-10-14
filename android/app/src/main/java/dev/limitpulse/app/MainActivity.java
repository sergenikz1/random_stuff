package dev.limitpulse.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 25, 34), CARD = Color.rgb(26, 40, 51);
    private static final int TEXT = Color.rgb(231, 239, 242), MUTED = Color.rgb(160, 181, 192);
    private static final int GREEN = Color.rgb(114, 230, 193), BLUE = Color.rgb(141, 207, 243), AMBER = Color.rgb(240, 187, 112);
    private AccountRepository repository;
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable observer = this::render;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 60000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repository = PulseApplication.repository(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            view.setPadding(0, top, 0, bottom); return insets;
        });
        setContentView(scroll);
        render();
    }
    @Override protected void onStart() {
        super.onStart(); repository.observe(observer); render(); handler.postDelayed(clockTick, 60000);
        RefreshJob.schedule(this);
        if (repository.connected()) repository.refresh(null);
    }
    @Override protected void onStop() {
        repository.removeObserver(observer); handler.removeCallbacks(clockTick); super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        ResetReminders.reconcile(this, repository.data, System.currentTimeMillis());
        LimitsWidget.updateAll(this); WidgetTicker.schedule(this); render();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 7100) {
            ResetReminders.reconcile(this, repository.data, System.currentTimeMillis()); render();
        }
    }
    private void render() {
        if (content == null) return;
        content.removeAllViews();
        TextView brand = text("▥  LIMIT PULSE", 14, GREEN, true);
        brand.setLetterSpacing(0.13f); content.addView(brand);
        space(content, 24);
        content.addView(text("Лимиты\nпод рукой", 36, TEXT, true));
        space(content, 10);
        content.addView(text("Codex — в цифрах. На экране телефона.", 15, MUTED, false));
        space(content, 24);
        LinearLayout account = card();
        account.addView(text(repository.connected() ? "АККАУНТ ПОДКЛЮЧЁН" : "НАЧНИТЕ С АККАУНТА", 11, GREEN, true));
        space(account, 8);
        account.addView(text(repository.accountLabel(), 17, TEXT, true));
        space(account, 12);
        if (repository.connected()) {
            Button refresh = button(repository.loading ? "Обновление…" : "Обновить лимиты", R.id.refresh_button, true,
                    () -> repository.refresh(null));
            refresh.setEnabled(!repository.loading); account.addView(refresh);
            account.addView(button("Отключить аккаунт", R.id.disconnect_button, false, () ->
                    new AlertDialog.Builder(this).setTitle("Отключить аккаунт?")
                            .setMessage("Токены входа и сохранённые лимиты будут удалены с этого телефона.")
                            .setPositiveButton("Отключить", (dialog, which) -> repository.disconnect())
                            .setNegativeButton("Отмена", null).show()));
        } else if (repository.browserPending()) {
            account.addView(text("Завершите вход в браузере OpenAI", 17, TEXT, true));
            space(account, 8);
            account.addView(text("После подтверждения браузер вернёт вас сюда. Если возврат не открылся автоматически, нажмите «Вернуться в приложение» на странице браузера.", 14, MUTED, false));
            if (repository.browser != null) account.addView(button("Открыть страницу входа", R.id.browser_open_button, true,
                    () -> { if (repository.browser != null) open(repository.browser.authorizationUrl); }));
            account.addView(button("Отменить вход", R.id.cancel_login_button, false, repository::cancelLogin));
        } else if (repository.device != null) {
            account.addView(text("1. Откройте OpenAI в браузере.\n2. Введите этот код.\n3. Вернитесь сюда после подтверждения.", 14, MUTED, false));
            space(account, 12);
            TextView code = text(repository.device.code, 30, GREEN, true); code.setTextIsSelectable(true); account.addView(code);
            space(account, 8);
            account.addView(button("Открыть OpenAI", 0, true, () -> open(OpenAiApi.VERIFY_URL)));
            account.addView(button("Скопировать код", 0, false, () -> {
                if (repository.device == null) return;
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Одноразовый код OpenAI", repository.device.code));
                Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show();
            }));
            space(account, 8);
            account.addView(text("Код действует 15 минут. Подтверждайте только вход, который вы начали здесь. Приложение использует вход Codex по коду устройства.", 12, MUTED, false));
            account.addView(button("Отменить вход", 0, false, repository::cancelLogin));
        } else {
            Button login = button(repository.loading ? "Подготовка входа…" : "Войти через браузер", R.id.login_button, true,
                    () -> repository.beginBrowserLogin(url -> { if (!open(url)) repository.cancelLogin(); }));
            login.setEnabled(!repository.loading); account.addView(login);
            space(account, 8);
            account.addView(text("Откроется привычная страница входа OpenAI. Пароль и подтверждение входа остаются в браузере. Переписывать одноразовый код не нужно.", 12, MUTED, false));
            account.addView(button("Запасной способ: код устройства", R.id.device_login_button, false, repository::beginLogin));
        }
        content.addView(account);
        if (!repository.error.isEmpty()) {
            space(content, 12);
            content.addView(text(repository.error, 14, AMBER, false));
        }
        space(content, 24);
        LimitData data = repository.data;
        long now = System.currentTimeMillis();
        content.addView(text("CODEX  /  ОСТАЛОСЬ", 12, MUTED, true));
        space(content, 10);
        LinearLayout windows = new LinearLayout(this); windows.setOrientation(LinearLayout.HORIZONTAL);
        boolean stale = data != null && (data.stale(now) || !repository.error.isEmpty());
        LinearLayout primary = windowCard(data == null ? null : data.primary, "Основное окно", R.id.primary_percent, stale ? AMBER : GREEN, now);
        LinearLayout secondary = windowCard(data == null ? null : data.secondary, "Дополнительное окно", R.id.secondary_percent, stale ? AMBER : BLUE, now);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -1, 1); first.rightMargin = dp(6);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -1, 1); second.leftMargin = dp(6);
        windows.addView(primary, first); windows.addView(secondary, second); content.addView(windows);
        space(content, 10);
        content.addView(text(DisplayText.updated(data, !repository.error.isEmpty(), now), 12, stale ? AMBER : MUTED, false));
        if (stale) content.addView(text("Это сохранённый остаток. После сброса лимита нужен новый запрос к OpenAI.", 12, AMBER, false));
        if (data != null) {
            content.addView(text("План: " + data.plan, 12, MUTED, false));
            if (Boolean.FALSE.equals(data.allowed)) content.addView(text("OpenAI сообщает: доступ Codex приостановлен.", 14, AMBER, true));
            if (data.credits != null) content.addView(text("Дополнительные кредиты: " + data.credits, 12, MUTED, false));
            for (LimitData.Extra extra : data.extra) {
                space(content, 12);
                content.addView(text(extra.name + " · " + DisplayText.percent(extra.primary) + " / " + DisplayText.percent(extra.secondary), 14, TEXT, false));
            }
        } else content.addView(text(repository.connected() ? "Нажмите «Обновить лимиты», чтобы получить данные." : "Остаток появится после входа и первого обновления.", 12, MUTED, false));
        space(content, 24);
        LinearLayout reminders = card();
        reminders.addView(text("За час до сброса", 20, TEXT, true)); space(reminders, 8);
        reminders.addView(text("Напомним отдельно о коротком и недельном лимитах: сколько процентов осталось и через сколько сброс. В уведомлении будет время получения данных.", 14, MUTED, false));
        Switch enabled = new Switch(this); enabled.setId(R.id.reminders_switch);
        enabled.setText("Напоминания о сбросе"); enabled.setTextColor(TEXT); enabled.setTextSize(14);
        enabled.setMinHeight(dp(48)); enabled.setChecked(ResetReminders.enabled(this));
        enabled.setOnCheckedChangeListener((view, checked) -> { ResetReminders.setEnabled(this, checked); render(); });
        reminders.addView(enabled);
        if (ResetReminders.enabled(this)) {
            if (!ResetReminders.allowed(this)) {
                reminders.addView(text("Разрешите уведомления, чтобы получать напоминания.", 13, AMBER, false));
                reminders.addView(button("Разрешить уведомления", R.id.notifications_button, true, this::enableNotifications));
            } else reminders.addView(text("Уведомления включены", 13, GREEN, true));
            if (!ResetReminders.exact(this)) {
                space(reminders, 8);
                reminders.addView(text("Для отправки за час до сброса разрешите точные напоминания. Без разрешения Android может прислать их позже.", 13, MUTED, false));
                reminders.addView(button("Разрешить точное время", R.id.exact_alarms_button, false, () -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); }
                    catch (android.content.ActivityNotFoundException ignored) { Toast.makeText(this, "Откройте системные настройки напоминаний приложения", Toast.LENGTH_LONG).show(); }
                }));
            } else reminders.addView(text("Точное время напоминаний разрешено", 12, MUTED, false));
            if (!repository.connected()) { space(reminders, 8); reminders.addView(text("После входа и обновления лимитов напоминания настроятся автоматически.", 12, MUTED, false)); }
        }
        content.addView(reminders); space(content, 24);
        LinearLayout chat = card();
        chat.addView(text("ChatGPT", 20, TEXT, true)); space(chat, 8);
        chat.addView(text("Точный остаток недоступен", 15, AMBER, true)); space(chat, 8);
        chat.addView(text("OpenAI не предоставляет этому приложению счётчик оставшихся сообщений ChatGPT. Проверяйте доступность моделей и время сброса в самом ChatGPT.", 14, MUTED, false));
        space(chat, 8); chat.addView(button("Открыть ChatGPT ↗", R.id.chatgpt_button, false, () -> open("https://chatgpt.com/"))); content.addView(chat);
        space(content, 16);
        content.addView(button("Добавить виджет на экран", R.id.pin_widget_button, true, this::pinWidget));
        space(content, 8);
        content.addView(text("Обновление примерно раз в 30 минут и по кнопке. Android может задерживать фоновые задачи для экономии батареи.", 12, MUTED, false));
        space(content, 24);
        content.addView(text("Независимое приложение · v" + BuildConfig.VERSION_NAME + "\nТокены зашифрованы Android Keystore. Без рекламы, аналитики и собственного сервера. Источник лимитов Codex может измениться.", 11, MUTED, false));
    }
    private LinearLayout windowCard(LimitData.Window window, String fallback, int id, int color, long now) {
        LinearLayout card = card(); card.setPadding(dp(16), dp(20), dp(16), dp(20));
        card.addView(text(window == null ? fallback : window.label(), 12, MUTED, true)); space(card, 10);
        TextView value = text(DisplayText.percent(window), 34, color, true); value.setId(id); card.addView(value);
        space(card, 12);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(window == null ? 0 : window.remaining());
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        card.addView(bar, new LinearLayout.LayoutParams(-1, dp(5))); space(card, 12);
        card.addView(text(DisplayText.countdown(window, now), 21, TEXT, true));
        card.addView(text(DisplayText.reset(window, now), 11, MUTED, false));
        return card;
    }
    private void pinWidget() {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager.isRequestPinAppWidgetSupported()) manager.requestPinAppWidget(new ComponentName(this, LimitsWidget.class), null, null);
        else new AlertDialog.Builder(this).setTitle("Добавить виджет")
                .setMessage("Зажмите свободное место на главном экране → Виджеты → Limit Pulse. Перетащите виджет на экран.")
                .setPositiveButton("Понятно", null).show();
    }
    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !ResetReminders.prefs(this).getBoolean("permission_requested", false)) {
            ResetReminders.prefs(this).edit().putBoolean("permission_requested", true).apply();
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 7100);
        } else {
            Intent settings = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            try { startActivity(settings); }
            catch (android.content.ActivityNotFoundException ignored) { Toast.makeText(this, "Откройте настройки уведомлений приложения", Toast.LENGTH_LONG).show(); }
        }
    }
    private boolean open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return true; }
        catch (android.content.ActivityNotFoundException ignored) { Toast.makeText(this, "Установите браузер, чтобы открыть OpenAI", Toast.LENGTH_LONG).show(); return false; }
    }
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20)); box.setBackground(background(CARD, 22));
        return box;
    }
    private Button button(String label, int id, boolean primary, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? BG : TEXT); button.setBackground(background(primary ? GREEN : CARD, 14));
        button.setMinHeight(dp(52)); button.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(6); button.setLayoutParams(params);
        if (id != 0) button.setId(id); button.setOnClickListener(view -> action.run()); return button;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setGravity(Gravity.START); text.setLineSpacing(dp(2), 1); return text;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
