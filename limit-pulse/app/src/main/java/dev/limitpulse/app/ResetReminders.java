package dev.limitpulse.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.text.DateFormat;
import java.util.Date;

final class ResetReminders {
    static final String CHANNEL = "limit_reset_reminders";
    static final String SLOT = "slot", RESET = "reset_at";
    /** Providers own two slots each: Codex 0–1, Claude 2–3 (short window, then weekly). */
    static final int CODEX = 0, CLAUDE = 1, SLOTS = 4;
    static LimitData data(Context context, int provider) {
        return provider == CLAUDE ? PulseApplication.claude(context).data
                : provider == CODEX ? PulseApplication.repository(context).data : null;
    }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("reset_reminders", Context.MODE_PRIVATE); }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }
    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
        if (!enabled) for (int slot = 0; slot < SLOTS; slot++) context.getSystemService(NotificationManager.class).cancel(8400 + slot);
        reconcileAll(context, System.currentTimeMillis());
    }
    static void channel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "За час до сброса лимитов", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Остаток процентов и время до сброса короткого и недельного лимитов Codex и Claude");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean allowed(Context context) {
        channel(context);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static boolean exact(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    private static PendingIntent operation(Context context, int slot, long reset) {
        Intent intent = new Intent(context, ResetReminderReceiver.class).putExtra(SLOT, slot).putExtra(RESET, reset);
        return PendingIntent.getBroadcast(context, 8200 + slot, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void reconcileAll(Context context, long now) {
        reconcile(context, CODEX, data(context, CODEX), now);
        reconcile(context, CLAUDE, data(context, CLAUDE), now);
    }
    static void reconcile(Context context, LimitData data, long now) { reconcile(context, CODEX, data, now); }
    static void reconcile(Context context, int provider, LimitData data, long now) {
        boolean active = data != null && enabled(context) && allowed(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (int slot = provider * 2; slot < provider * 2 + 2; slot++) {
            alarms.cancel(operation(context, slot, 0));
            if (!active) continue;
            LimitData.Window window = ReminderPlan.window(data, slot % 2);
            long delivered = prefs(context).getLong("delivered_" + slot, 0);
            long when = ReminderPlan.at(window, now, delivered);
            if (when < 0) continue;
            if (when <= now) { deliver(context, data, slot, window.resetAt, now); continue; }
            PendingIntent operation = operation(context, slot, window.resetAt);
            try {
                if (exact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, operation);
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, operation);
            } catch (SecurityException permissionChanged) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, operation);
            }
        }
    }
    static boolean deliver(Context context, LimitData data, int slot, long expectedReset, long now) {
        if (slot < 0 || slot >= SLOTS || !enabled(context) || !allowed(context)) return false;
        LimitData.Window window = ReminderPlan.window(data, slot % 2);
        if (!ReminderPlan.due(window, expectedReset, now, prefs(context).getLong("delivered_" + slot, 0))) return false;
        String product = slot / 2 == CLAUDE ? "Claude" : "Codex";
        String title = window.windowSeconds >= 604800 ? "Скоро сброс недельного лимита " + product : "Скоро сброс лимита " + product;
        String body = body(data, window, now);
        PendingIntent open = PendingIntent.getActivity(context, 8300 + slot, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_login_notification)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(expectedReset * 1000 - now).build();
        try { context.getSystemService(NotificationManager.class).notify(8400 + slot, notification); }
        catch (SecurityException permissionChanged) { return false; }
        // Persist the cycle marker so refreshes, repeated alarms and restarts do not alert twice.
        prefs(context).edit().putLong("delivered_" + slot, expectedReset).apply();
        return true;
    }
    static String body(LimitData data, LimitData.Window window, long now) {
        String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(data.fetchedAt));
        return "Осталось " + DisplayText.percent(window) + " по данным на " + time + ". До сброса — " + DisplayText.countdown(window, now) + ".";
    }
    static void clearAccount(Context context) { clearAccount(context, CODEX); }
    static void clearAccount(Context context, int provider) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int slot = provider * 2; slot < provider * 2 + 2; slot++) {
            alarms.cancel(operation(context, slot, 0));
            context.getSystemService(NotificationManager.class).cancel(8400 + slot);
            editor.remove("delivered_" + slot);
        }
        editor.apply();
    }
    private ResetReminders() { }
}
