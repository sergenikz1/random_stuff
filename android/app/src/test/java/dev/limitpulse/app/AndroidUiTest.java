package dev.limitpulse.app;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AndroidUiTest {
    @Test public void widgetShowsBrightPercentAndSeparateHourlyAndWeeklyCountdowns() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication(); AccountRepository repository = PulseApplication.repository(app);
        long now = System.currentTimeMillis();
        repository.data = LimitData.parse(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":37,\"limit_window_seconds\":18000,\"reset_at\":" + (now / 1000 + 11820) + "},\"secondary_window\":{\"used_percent\":18,\"limit_window_seconds\":604800,\"reset_at\":" + (now / 1000 + 3 * 86400 + 5 * 3600) + "}}}"), now);
        View view = LimitsWidget.views(app, repository, now).apply(app, null);
        assertEquals("63%", ((TextView) view.findViewById(R.id.widget_primary)).getText().toString());
        assertEquals("3 ч 17 мин", ((TextView) view.findViewById(R.id.widget_primary_reset)).getText().toString());
        assertEquals("3 дн 5 ч", ((TextView) view.findViewById(R.id.widget_secondary_reset)).getText().toString());
        assertEquals(android.graphics.Color.parseColor("#AEFFD0"), ((TextView) view.findViewById(R.id.widget_primary)).getCurrentTextColor());
        screenshot(view, "widget-countdowns.png", 960, 660);
        assertTrue("Update timestamp must remain visible", view.findViewById(R.id.widget_updated).getHeight() > 0);
        assertTrue(view.findViewById(R.id.widget_updated).getBottom() <= view.getHeight() - view.getPaddingBottom());
    }
    @Test public void exactAlarmButtonOpensSystemAccessSettings() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            controller.get().findViewById(R.id.exact_alarms_button).performClick();
            Intent intent = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.getAction());
            assertEquals("package:dev.limitpulse.app", intent.getDataString());
        }
    }
    @Test public void browserLoginServiceDisplaysForegroundNotification() {
        org.robolectric.android.controller.ServiceController<LoginService> controller = Robolectric.buildService(LoginService.class).create();
        try {
            android.app.Notification notification = Shadows.shadowOf(controller.get()).getLastForegroundNotification();
            assertNotNull(notification);
            assertEquals("Вход в OpenAI", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString());
        } finally { controller.destroy(); }
    }
    @Test public void pendingBrowserLoginOpensOfficialOauthWithoutAskingForDeviceCodeAndCanBeCancelled() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication(); AccountRepository repository = PulseApplication.repository(app);
        try (BrowserLogin session = BrowserLogin.start(0, 2000)) {
            repository.browser = session; repository.loading = true;
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                assertNull(activity.findViewById(R.id.device_login_button));
                activity.findViewById(R.id.browser_open_button).performClick();
                Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
                assertEquals("auth.openai.com", opened.getData().getHost()); assertEquals("/oauth/authorize", opened.getData().getPath());
                activity.findViewById(R.id.cancel_login_button).performClick();
                assertFalse(repository.browserPending()); assertNotNull(activity.findViewById(R.id.login_button));
            }
        }
    }
    @Test public void deepLinkOnlyReturnsToAppAndCannotInjectAuthorizationCode() {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("limitpulse://login-complete?code=forged&state=forged"));
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            assertFalse(PulseApplication.repository(controller.get()).connected());
            assertNotNull(controller.get().findViewById(R.id.login_button));
        }
    }
    @Test @Config(sdk = 26) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void minimumSupportedAndroidStartsActivityAndInflatesWidget() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNotNull(controller.get().findViewById(R.id.pin_widget_button));
            android.app.Application app = RuntimeEnvironment.getApplication();
            View view = LimitsWidget.views(app, PulseApplication.repository(app), System.currentTimeMillis()).apply(app, null);
            assertEquals("—", ((TextView) view.findViewById(R.id.widget_primary)).getText().toString());
        }
    }
    @Test public void disconnectedActivityRendersAndChatButtonOpensRealChatGpt() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            assertNotNull(activity.findViewById(R.id.login_button));
            assertEquals("—", ((TextView) activity.findViewById(R.id.primary_percent)).getText().toString());
            activity.findViewById(R.id.chatgpt_button).performClick();
            Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction()); assertEquals("https://chatgpt.com/", opened.getDataString());
            screenshot(activity.getWindow().getDecorView(), "app-disconnected.png", 1080, 2400);
        }
    }
    @Test public void widgetShowsUnknownWhenDisconnected() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        AccountRepository repository = PulseApplication.repository(app);
        View view = LimitsWidget.views(app, repository, System.currentTimeMillis()).apply(app, null);
        assertEquals("—", ((TextView) view.findViewById(R.id.widget_primary)).getText().toString());
        assertEquals("Ещё не обновлялось", ((TextView) view.findViewById(R.id.widget_updated)).getText().toString());
        assertNotNull(AppWidgetManager.getInstance(app));
    }
    @Test public void widgetRendersRealRemainderAndRetainsStaleSnapshotAfterReset() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication(); AccountRepository repository = PulseApplication.repository(app);
        long fetchedAt = 1700000000000L;
        repository.data = LimitData.parse(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":42,\"limit_window_seconds\":18000,\"reset_at\":1700000060}}}"), fetchedAt);
        View view = LimitsWidget.views(app, repository, fetchedAt + 120000).apply(app, null);
        assertEquals("58%", ((TextView) view.findViewById(R.id.widget_primary)).getText().toString());
        assertEquals("—", ((TextView) view.findViewById(R.id.widget_secondary)).getText().toString());
        assertTrue(((TextView) view.findViewById(R.id.widget_updated)).getText().toString().contains("Сохранённые данные"));
        assertEquals("Сброс прошёл", ((TextView) view.findViewById(R.id.widget_primary_reset)).getText().toString());
        screenshot(view, "widget-sample-stale.png", 960, 660);
        View updated = view.findViewById(R.id.widget_updated);
        assertTrue("Widget status must fit inside its minimum height", updated.getBottom() <= view.getHeight() - view.getPaddingBottom());
    }
    private void screenshot(View view, String name, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir = new java.io.File(System.getProperty("limitpulse.screenshotDir"));
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        bitmap.recycle();
    }
}
