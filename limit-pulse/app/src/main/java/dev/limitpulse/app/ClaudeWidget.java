package dev.limitpulse.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Same layout as {@link LimitsWidget}, showing the Claude 5-hour and weekly limits. */
public final class ClaudeWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context); RefreshJob.schedule(context);
        RefreshJob.requestNow(context);
        WidgetTicker.schedule(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (LimitsWidget.REFRESH.equals(intent.getAction())) RefreshJob.requestNow(context);
        else super.onReceive(context, intent);
    }
    @Override public void onEnabled(Context context) { RefreshJob.schedule(context); }
    @Override public void onDisabled(Context context) { WidgetTicker.schedule(context); }
    static void updateAll(Context context) {
        ClaudeRepository repository = PulseApplication.claude(context);
        if (repository == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, ClaudeWidget.class)))
            manager.updateAppWidget(id, views(context, repository, System.currentTimeMillis()));
    }
    static RemoteViews views(Context context, ClaudeRepository repository, long now) {
        RemoteViews view = LimitsWidget.render(context, repository.data, repository.loading, repository.error,
                repository.accountLabel(), "Claude", "Claude · 5 часов", "Claude · неделя", now);
        view.setTextViewText(R.id.widget_title, "LIMIT PULSE · CLAUDE");
        view.setTextViewText(R.id.widget_chat_label, "Claude · подписка");
        view.setTextViewText(R.id.widget_chatgpt, "Claude ↗");
        LimitsWidget.bind(context, view, ClaudeWidget.class, 20, "https://claude.ai/");
        return view;
    }
}
