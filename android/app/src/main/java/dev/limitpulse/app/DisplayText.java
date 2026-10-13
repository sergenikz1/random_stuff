package dev.limitpulse.app;

import java.text.DateFormat;
import java.util.Date;

final class DisplayText {
    private DisplayText() { }
    static String percent(LimitData.Window window) { return window == null ? "—" : window.remaining() + "%"; }
    static String reset(LimitData.Window window, long now) {
        if (window == null || window.resetAt <= 0) return "Сброс не указан";
        if (window.resetAt * 1000 <= now) return "Нужно обновить после сброса";
        return "Сброс " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(window.resetAt * 1000));
    }
    static String updated(LimitData data, boolean error, long now) {
        if (data == null) return "Ещё не обновлялось";
        String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(data.fetchedAt));
        return (error || data.stale(now) ? "Сохранённые данные · " : "Обновлено · ") + time;
    }
}
