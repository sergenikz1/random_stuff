package dev.limitpulse.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Local-only, non-waking updates. Android may batch them while conserving power. */
public final class WidgetTicker extends BroadcastReceiver {
    private static PendingIntent intent(Context context) {
        return PendingIntent.getBroadcast(context, 8100, new Intent(context, WidgetTicker.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void schedule(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean codex = manager.getAppWidgetIds(new ComponentName(context, LimitsWidget.class)).length > 0
                && PulseApplication.repository(context).data != null;
        boolean claude = manager.getAppWidgetIds(new ComponentName(context, ClaudeWidget.class)).length > 0
                && PulseApplication.claude(context).data != null;
        if (!codex && !claude) { cancel(context); return; }
        long nextMinute = 60000 - System.currentTimeMillis() % 60000;
        context.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + nextMinute, intent(context));
    }
    static void cancel(Context context) { context.getSystemService(AlarmManager.class).cancel(intent(context)); }
    @Override public void onReceive(Context context, Intent intent) {
        LimitsWidget.updateAll(context); ClaudeWidget.updateAll(context); schedule(context);
    }
}
