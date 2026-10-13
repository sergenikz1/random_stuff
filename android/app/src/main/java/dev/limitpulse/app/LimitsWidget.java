package dev.limitpulse.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.widget.RemoteViews;

public final class LimitsWidget extends AppWidgetProvider {
    static final String REFRESH = "dev.limitpulse.app.REFRESH";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context); RefreshJob.schedule(context);
        RefreshJob.requestNow(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (REFRESH.equals(intent.getAction())) {
            RefreshJob.requestNow(context);
        } else super.onReceive(context, intent);
    }
    @Override public void onEnabled(Context context) { RefreshJob.schedule(context); }
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AccountRepository repository = PulseApplication.repository(context);
        if (repository == null) return;
        for (int id : manager.getAppWidgetIds(new ComponentName(context, LimitsWidget.class)))
            manager.updateAppWidget(id, views(context, repository, System.currentTimeMillis()));
    }
    static RemoteViews views(Context context, AccountRepository repository, long now) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget_limits);
        LimitData data = repository.data;
        view.setTextViewText(R.id.widget_account, data == null ? repository.accountLabel() : "Codex · " + data.plan + " · осталось");
        view.setTextViewText(R.id.widget_primary, DisplayText.percent(data == null ? null : data.primary));
        view.setTextViewText(R.id.widget_secondary, DisplayText.percent(data == null ? null : data.secondary));
        view.setTextViewText(R.id.widget_primary_label, data == null || data.primary == null ? "Основное окно" : data.primary.label());
        view.setTextViewText(R.id.widget_secondary_label, data == null || data.secondary == null ? "Дополнительное окно" : data.secondary.label());
        view.setTextViewText(R.id.widget_primary_reset, data == null ? "" : DisplayText.reset(data.primary, now));
        view.setTextViewText(R.id.widget_secondary_reset, data == null ? "" : DisplayText.reset(data.secondary, now));
        String updated = repository.loading ? "Обновление…" : DisplayText.updated(data, !repository.error.isEmpty(), now);
        if (!repository.error.isEmpty()) updated = "Ошибка обновления · " + updated;
        if (data != null && Boolean.FALSE.equals(data.allowed)) updated = "Codex: доступ приостановлен · " + updated;
        view.setTextViewText(R.id.widget_updated, updated);
        boolean stale = data != null && (data.stale(now) || !repository.error.isEmpty());
        view.setTextColor(R.id.widget_primary, Color.parseColor(stale ? "#F0BB70" : "#72E6C1"));
        view.setTextColor(R.id.widget_secondary, Color.parseColor(stale ? "#F0BB70" : "#8DCFF3"));
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_root, open);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 1, new Intent(context, LimitsWidget.class).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        PendingIntent chat = PendingIntent.getActivity(context, 2, new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_chatgpt, chat);
        return view;
    }
}
