package dev.limitpulse.app;

/** Pure planning logic shared by scheduling and delivery validation. */
final class ReminderPlan {
    static final long HOUR = 3600000L;
    static LimitData.Window window(LimitData data, int slot) {
        if (data == null) return null;
        return slot == 0 ? data.primary : slot == 1 ? data.secondary : null;
    }
    static long at(LimitData.Window window, long now, long deliveredReset) {
        if (window == null || window.resetAt <= 0 || window.resetAt == deliveredReset) return -1;
        long reset = window.resetAt * 1000L;
        if (reset <= now) return -1;
        return Math.max(now, reset - HOUR);
    }
    static boolean due(LimitData.Window window, long expectedReset, long now, long deliveredReset) {
        return window != null && window.resetAt == expectedReset && expectedReset > 0
                && deliveredReset != expectedReset && now >= expectedReset * 1000L - HOUR && now < expectedReset * 1000L;
    }
    private ReminderPlan() { }
}
