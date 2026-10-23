package dev.limitpulse.app;

import java.text.DateFormat;
import java.util.Date;

final class DisplayText {
    private DisplayText() { }
    static String percent(LimitData.Window window) { return window == null ? "—" : window.remaining() + "%"; }
    static String countdown(LimitData.Window window, long now) {
        if (window == null || window.resetAt <= 0) return "—";
        long seconds = window.resetAt - now / 1000;
        if (seconds <= 0) return "Сброс прошёл";
        // Round up to avoid showing zero minutes before the actual reset.
        long minutes = (seconds + 59) / 60;
        if (window.windowSeconds >= 86400 && seconds >= 86400) {
            long hours = seconds / 3600;
            return hours / 24 + " дн " + hours % 24 + " ч";
        }
        if (minutes >= 60) return minutes / 60 + " ч" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " мин");
        return minutes + " мин";
    }
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
