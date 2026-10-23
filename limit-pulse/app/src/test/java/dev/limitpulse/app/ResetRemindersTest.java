package dev.limitpulse.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35)
public class ResetRemindersTest {
    private Application app;
    @Before public void allowNotifications() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(app.getSystemService(NotificationManager.class)).setNotificationsEnabled(true);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
    }
    private LimitData data(long now, long primaryDelay, long weeklyDelay) throws Exception {
        return LimitData.parse(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":42,\"limit_window_seconds\":18000,\"reset_at\":" + (now / 1000 + primaryDelay) + "},\"secondary_window\":{\"used_percent\":15,\"limit_window_seconds\":604800,\"reset_at\":" + (now / 1000 + weeklyDelay) + "}}}"), now);
    }
    private List<ShadowAlarmManager.ScheduledAlarm> alarms() {
        return Shadows.shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().stream()
                .filter(alarm -> Shadows.shadowOf(alarm.operation).getSavedIntent().getComponent().getClassName().equals(ResetReminderReceiver.class.getName()))
                .collect(Collectors.toList());
    }
    @Test public void schedulesBothExactWakeupAlarmsAndReplacesChangedReset() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 18000, 604800);
        ResetReminders.reconcile(app, data, now);
        assertEquals(2, alarms().size());
        assertEquals(data.primary.resetAt * 1000 - ReminderPlan.HOUR, alarms().get(0).getTriggerAtMs());
        assertEquals(AlarmManager.RTC_WAKEUP, alarms().get(0).getType()); assertEquals(0, alarms().get(0).getWindowLengthMs());
        assertTrue(alarms().get(0).isAllowWhileIdle());
        LimitData changed = data(now, 21600, 604800); ResetReminders.reconcile(app, changed, now);
        assertEquals(2, alarms().size()); assertEquals(changed.primary.resetAt * 1000 - ReminderPlan.HOUR, alarms().get(0).getTriggerAtMs());
    }
    @Test public void missingExactPermissionUsesAllowedInexactAlarm() throws Exception {
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        ResetReminders.reconcile(app, data(System.currentTimeMillis(), 18000, 604800), System.currentTimeMillis());
        assertEquals(2, alarms().size()); assertNotEquals(0, alarms().get(0).getWindowLengthMs());
    }
    @Test public void deniedNotificationPermissionDoesNotScheduleOrConsumeCycle() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 18000, 604800);
        ResetReminders.reconcile(app, data, now); assertEquals(2, alarms().size());
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ResetReminders.reconcile(app, data, now); assertTrue(alarms().isEmpty());
        assertFalse(ResetReminders.deliver(app, data, 0, data.primary.resetAt, data.primary.resetAt * 1000 - ReminderPlan.HOUR));
        assertEquals(0, ResetReminders.prefs(app).getLong("delivered_0", 0));
    }
    @Test public void notificationReportsPercentAndOnlyOnceForEachWindow() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 3600, 3600);
        long due = data.primary.resetAt * 1000 - ReminderPlan.HOUR;
        assertTrue(ResetReminders.deliver(app, data, 0, data.primary.resetAt, due));
        assertFalse(ResetReminders.deliver(app, data, 0, data.primary.resetAt, due));
        assertTrue(ResetReminders.deliver(app, data, 1, data.secondary.resetAt, due));
        org.robolectric.shadows.ShadowNotificationManager manager = Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        assertEquals(2, manager.size());
        Notification primary = manager.getNotification(8400);
        assertTrue(primary.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("58%"));
        assertTrue(primary.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("по данным на"));
        assertTrue(manager.getNotification(8401).extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("недельного"));
        assertEquals(data.primary.resetAt, app.getSharedPreferences("reset_reminders", 0).getLong("delivered_0", 0));
    }
    @Test public void staleIntentAndPastResetDoNotPost() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 3600, 604800);
        assertFalse(ResetReminders.deliver(app, data, 0, data.primary.resetAt - 60, now));
        assertFalse(ResetReminders.deliver(app, data, 0, data.primary.resetAt, data.primary.resetAt * 1000));
        assertEquals(0, Shadows.shadowOf(app.getSystemService(NotificationManager.class)).size());
    }
    @Test public void logoutCancelsAlarmsAndExistingNotifications() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 3600, 604800);
        AccountRepository repository = PulseApplication.repository(app); repository.data = data;
        ResetReminders.reconcile(app, data, now);
        assertTrue(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).size() > 0);
        repository.disconnect(); assertTrue(alarms().isEmpty());
        assertEquals(0, Shadows.shadowOf(app.getSystemService(NotificationManager.class)).size());
        assertEquals(0, ResetReminders.prefs(app).getLong("delivered_0", 0));
    }
    @Test public void claudeRemindersUseSeparateSlotsAndSurviveCodexLogout() throws Exception {
        long now = System.currentTimeMillis();
        LimitData claude = LimitData.claude(new JSONObject("{\"five_hour\":{\"utilization\":30,\"resets_at\":\"" + java.time.Instant.ofEpochSecond(now / 1000 + 1800) + "\"},"
                + "\"seven_day\":{\"utilization\":50,\"resets_at\":\"" + java.time.Instant.ofEpochSecond(now / 1000 + 604800) + "\"}}"), now);
        PulseApplication.claude(app).data = claude;
        ResetReminders.reconcile(app, ResetReminders.CLAUDE, claude, now);
        org.robolectric.shadows.ShadowNotificationManager manager = Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        Notification due = manager.getNotification(8402);
        assertNotNull("Window resetting within the hour notifies immediately", due);
        assertTrue(due.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Claude"));
        assertTrue(due.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("70%"));
        assertEquals(1, alarms().size());
        assertEquals(claude.secondary.resetAt * 1000 - ReminderPlan.HOUR, alarms().get(0).getTriggerAtMs());
        PulseApplication.repository(app).disconnect();
        assertEquals(1, alarms().size()); assertNotNull(manager.getNotification(8402));
        PulseApplication.claude(app).disconnect();
        assertTrue(alarms().isEmpty()); assertNull(manager.getNotification(8402));
        assertEquals(0, ResetReminders.prefs(app).getLong("delivered_2", 0));
    }
    @Test public void disableCancelsAndBootRestoresEnabledFutureAlarms() throws Exception {
        long now = System.currentTimeMillis(); LimitData data = data(now, 18000, 604800);
        PulseApplication.repository(app).data = data; ResetReminders.reconcile(app, data, now);
        ResetReminders.setEnabled(app, false); assertTrue(alarms().isEmpty());
        ResetReminders.setEnabled(app, true); assertEquals(2, alarms().size());
        app.getSystemService(AlarmManager.class).cancelAll(); assertTrue(alarms().isEmpty());
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED)); assertEquals(2, alarms().size());
    }
}
