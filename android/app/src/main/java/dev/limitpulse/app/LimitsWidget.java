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
        WidgetTicker.schedule(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (REFRESH.equals(intent.getAction())) {
            RefreshJob.requestNow(context);
        } else super.onReceive(context, intent);
    }
    @Override public void onEnabled(Context context) { RefreshJob.schedule(context); }
    @Override public void onDisabled(Context context) { WidgetTicker.schedule(context); }
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AccountRepository repository = PulseApplication.repository(context);
        if (repository == null) return;
        for (int id : manager.getAppWidgetIds(new ComponentName(context, LimitsWidget.class)))
            manager.updateAppWidget(id, views(context, repository, System.currentTimeMillis()));
    }
    static RemoteViews views(Context context, AccountRepository repository, long now) {
        LimitData data = repository.data;
        RemoteViews view = render(context, data, repository.loading, repository.error, repository.accountLabel(),
                "Codex", "Основное окно", "Дополнительное окно", now);
        if (data != null && Boolean.FALSE.equals(data.allowed))
            view.setTextViewText(R.id.widget_updated, "Codex: доступ приостановлен · " + status(data, repository.loading, repository.error, now));
        bind(context, view, LimitsWidget.class, 0, "https://chatgpt.com/");
        return view;
    }
    /** Shared layout for the Codex and Claude widgets. Missing data is shown as unknown, never as full. */
    static RemoteViews render(Context context, LimitData data, boolean loading, String error, String account,
                              String product, String primaryFallback, String secondaryFallback, long now) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget_limits);
        view.setTextViewText(R.id.widget_account, data == null ? account
                : product + ("Claude".equals(data.plan) ? "" : " · " + data.plan) + " · осталось");
        view.setTextViewText(R.id.widget_primary, DisplayText.percent(data == null ? null : data.primary));
        view.setTextViewText(R.id.widget_secondary, DisplayText.percent(data == null ? null : data.secondary));
        view.setTextViewText(R.id.widget_primary_label, data == null || data.primary == null ? primaryFallback : data.primary.label());
        view.setTextViewText(R.id.widget_secondary_label, data == null || data.secondary == null ? secondaryFallback : data.secondary.label());
        view.setTextViewText(R.id.widget_primary_reset, DisplayText.countdown(data == null ? null : data.primary, now));
        view.setTextViewText(R.id.widget_secondary_reset, DisplayText.countdown(data == null ? null : data.secondary, now));
        view.setTextViewTextSize(R.id.widget_primary_reset, android.util.TypedValue.COMPLEX_UNIT_SP,
                data != null && data.primary != null && data.primary.resetAt > 0 && data.primary.resetAt * 1000 <= now ? 14 : 21);
        view.setTextViewTextSize(R.id.widget_secondary_reset, android.util.TypedValue.COMPLEX_UNIT_SP,
                data != null && data.secondary != null && data.secondary.resetAt > 0 && data.secondary.resetAt * 1000 <= now ? 14 : 21);
        view.setTextViewText(R.id.widget_updated, status(data, loading, error, now));
        boolean stale = data != null && (data.stale(now) || !error.isEmpty());
        view.setTextColor(R.id.widget_primary, Color.parseColor(stale ? "#FFD591" : "#AEFFD0"));
        view.setTextColor(R.id.widget_secondary, Color.parseColor(stale ? "#FFD591" : "#B2DEFF"));
        return view;
    }
    private static String status(LimitData data, boolean loading, String error, long now) {
        String updated = loading ? "Обновление…" : DisplayText.updated(data, !error.isEmpty(), now);
        return error.isEmpty() ? updated : "Ошибка обновления · " + updated;
    }
    /** Request codes are offset per widget class so their PendingIntents stay distinct. */
    static void bind(Context context, RemoteViews view, Class<?> provider, int offset, String chatUrl) {
        PendingIntent open = PendingIntent.getActivity(context, offset, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_root, open);
        PendingIntent refresh = PendingIntent.getBroadcast(context, offset + 1, new Intent(context, provider).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        PendingIntent chat = PendingIntent.getActivity(context, offset + 2, new Intent(Intent.ACTION_VIEW, Uri.parse(chatUrl)), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_chatgpt, chat);
    }
}
