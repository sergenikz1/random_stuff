package dev.limitpulse.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReminderPlanTest {
    static final long NOW = 1700000000000L;
    @Test public void shortCountdownShowsHoursAndMinutes() {
        assertEquals("3 ч 17 мин", DisplayText.countdown(new LimitData.Window(42, 18000, NOW / 1000 + 11820), NOW));
        assertEquals("1 ч", DisplayText.countdown(new LimitData.Window(42, 18000, NOW / 1000 + 3600), NOW));
        assertEquals("1 мин", DisplayText.countdown(new LimitData.Window(42, 18000, NOW / 1000 + 1), NOW));
    }
    @Test public void weeklyCountdownShowsDaysAndHoursThenMinutesNearReset() {
        assertEquals("3 дн 5 ч", DisplayText.countdown(new LimitData.Window(10, 604800, NOW / 1000 + 3 * 86400 + 5 * 3600 + 59 * 60), NOW));
        assertEquals("17 ч 20 мин", DisplayText.countdown(new LimitData.Window(10, 604800, NOW / 1000 + 17 * 3600 + 20 * 60), NOW));
    }
    @Test public void expiredAndMissingTimesDoNotInventReset() {
        assertEquals("—", DisplayText.countdown(null, NOW));
        assertEquals("—", DisplayText.countdown(new LimitData.Window(42, 18000, 0), NOW));
        assertEquals("Сброс прошёл", DisplayText.countdown(new LimitData.Window(42, 18000, NOW / 1000), NOW));
    }
    @Test public void alarmIsPlannedExactlyOneHourBeforeEachReset() {
        assertEquals(NOW + 4 * ReminderPlan.HOUR, ReminderPlan.at(new LimitData.Window(42, 18000, NOW / 1000 + 5 * 3600), NOW, 0));
        assertEquals(NOW + 167 * ReminderPlan.HOUR, ReminderPlan.at(new LimitData.Window(42, 604800, NOW / 1000 + 168 * 3600), NOW, 0));
    }
    @Test public void enteringLastHourCatchesUpButPastResetAndDeliveredCyclesDoNotAlert() {
        LimitData.Window window = new LimitData.Window(42, 18000, NOW / 1000 + 1800);
        assertEquals(NOW, ReminderPlan.at(window, NOW, 0));
        assertEquals(-1, ReminderPlan.at(window, NOW, window.resetAt));
        assertEquals(-1, ReminderPlan.at(window, window.resetAt * 1000, 0));
        assertEquals(-1, ReminderPlan.at(null, NOW, 0));
    }
    @Test public void staleAlarmAndEarlyAlarmCannotNotify() {
        LimitData.Window window = new LimitData.Window(42, 18000, NOW / 1000 + 3600);
        assertTrue(ReminderPlan.due(window, window.resetAt, NOW, 0));
        assertFalse(ReminderPlan.due(window, window.resetAt - 1, NOW, 0));
        assertFalse(ReminderPlan.due(window, window.resetAt, NOW - 1, 0));
        assertFalse(ReminderPlan.due(window, window.resetAt, NOW, window.resetAt));
        assertFalse(ReminderPlan.due(window, window.resetAt, window.resetAt * 1000, 0));
    }
    @Test public void notificationIncludesLastKnownPercentTimeAndActualRemainingDuration() throws Exception {
        LimitData data = LimitData.parse(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":42,\"limit_window_seconds\":18000,\"reset_at\":1700003600}}}"), NOW - 1800000);
        String onTime = ResetReminders.body(data, data.primary, NOW);
        assertTrue(onTime.contains("58%")); assertTrue(onTime.contains("по данным на")); assertTrue(onTime.contains("1 ч"));
        assertTrue(ResetReminders.body(data, data.primary, NOW + 15 * 60000).contains("45 мин"));
    }
}
