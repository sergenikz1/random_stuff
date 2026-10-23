package dev.limitpulse.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LimitDataTest {
    private static final long NOW = 1700000000000L;
    private LimitData parse(String rate) throws Exception {
        return LimitData.parse(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":" + rate + "}"), NOW);
    }
    @Test public void remainingIsOneHundredMinusUsage() throws Exception {
        LimitData data = parse("{\"allowed\":true,\"primary_window\":{\"used_percent\":37,\"limit_window_seconds\":18000,\"reset_at\":1700005000},\"secondary_window\":{\"used_percent\":82,\"limit_window_seconds\":604800,\"reset_at\":1700400000}}");
        assertEquals(63, data.primary.remaining()); assertEquals(18, data.secondary.remaining());
        assertEquals("За 5 часов", data.primary.label()); assertEquals("За неделю", data.secondary.label());
        assertFalse(data.stale(NOW)); assertEquals(Boolean.TRUE, data.allowed);
    }
    @Test public void absentWindowsAreUnknownNotOneHundredPercent() throws Exception {
        LimitData data = parse("null"); assertNull(data.primary); assertNull(data.secondary); assertNull(data.allowed);
        assertEquals("—", DisplayText.percent(data.primary));
    }
    @Test public void missingOrStringUsageIsUnknown() throws Exception {
        LimitData data = parse("{\"primary_window\":{},\"secondary_window\":{\"used_percent\":\"0\"}}");
        assertNull(data.primary); assertNull(data.secondary);
    }
    @Test public void serverPercentIsClampedAndFractionalRemainingRoundsDown() throws Exception {
        assertEquals(0, parse("{\"primary_window\":{\"used_percent\":101}}").primary.remaining());
        assertEquals(100, parse("{\"primary_window\":{\"used_percent\":-5}}").primary.remaining());
        assertEquals(62, parse("{\"primary_window\":{\"used_percent\":37.2}}").primary.remaining());
    }
    @Test public void relativeResetUsesFetchTime() throws Exception {
        assertEquals(NOW / 1000 + 120, parse("{\"primary_window\":{\"used_percent\":10,\"reset_after_seconds\":120}}").primary.resetAt);
    }
    @Test public void resetAndAgeMarkSnapshotStaleWithoutInventingRefill() throws Exception {
        LimitData data = parse("{\"primary_window\":{\"used_percent\":90,\"reset_at\":1700000060}}");
        assertFalse(data.stale(NOW + 59000)); assertTrue(data.stale(NOW + 60000)); assertEquals(10, data.primary.remaining());
        assertTrue(parse("null").stale(NOW + LimitData.STALE_AFTER_MS)); assertTrue(data.stale(NOW - 1));
    }
    @Test public void restrictionCreditsAndAdditionalLimitsArePreserved() throws Exception {
        LimitData data = LimitData.parse(new JSONObject("{\"plan_type\":\"pro\",\"rate_limit\":{\"allowed\":false},\"credits\":{\"balance\":\"12.50\"},\"additional_rate_limits\":[{\"limit_name\":\"Review\",\"rate_limit\":{\"primary_window\":{\"used_percent\":20}}}]}"), NOW);
        assertEquals(Boolean.FALSE, data.allowed); assertEquals("12.50", data.credits);
        assertEquals("Review", data.extra.get(0).name); assertEquals(80, data.extra.get(0).primary.remaining());
    }
    @Test(expected = org.json.JSONException.class) public void unexpectedPayloadDoesNotValidateAsUsage() throws Exception {
        LimitData.parse(new JSONObject("{\"error\":\"unavailable\"}"), NOW);
    }
}
