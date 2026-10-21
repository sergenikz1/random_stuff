package dev.limitpulse.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A server snapshot. Missing data never means zero usage or a full allowance. */
public final class LimitData {
    public static final long STALE_AFTER_MS = 30 * 60 * 1000L;
    public final String plan;
    public final Window primary, secondary;
    public final List<Extra> extra;
    public final Boolean allowed;
    public final String credits;
    public final long fetchedAt;

    public static final class Window {
        public final double usedPercent;
        public final long windowSeconds, resetAt;
        Window(double usedPercent, long windowSeconds, long resetAt) {
            this.usedPercent = Math.max(0, Math.min(100, usedPercent));
            this.windowSeconds = Math.max(0, windowSeconds);
            this.resetAt = Math.max(0, resetAt);
        }
        public int remaining() { return (int) Math.floor(100 - usedPercent); }
        public String label() {
            if (windowSeconds == 604800) return "За неделю";
            if (windowSeconds == 18000) return "За 5 часов";
            if (windowSeconds > 0 && windowSeconds % 86400 == 0) return "За " + windowSeconds / 86400 + " дн.";
            if (windowSeconds > 0 && windowSeconds % 3600 == 0) return "За " + windowSeconds / 3600 + " ч.";
            return "Окно лимита";
        }
    }
    public static final class Extra {
        public final String name;
        public final Window primary, secondary;
        Extra(String name, Window primary, Window secondary) {
            this.name = name; this.primary = primary; this.secondary = secondary;
        }
    }
    private LimitData(String plan, Window primary, Window secondary, List<Extra> extra,
                      Boolean allowed, String credits, long fetchedAt) {
        this.plan = plan; this.primary = primary; this.secondary = secondary;
        this.extra = Collections.unmodifiableList(extra); this.allowed = allowed;
        this.credits = credits; this.fetchedAt = fetchedAt;
    }
    public static LimitData parse(JSONObject root, long fetchedAt) throws JSONException {
        if (!root.has("plan_type") && !root.has("rate_limit") && !root.has("additional_rate_limits"))
            throw new JSONException("Unexpected usage response");
        JSONObject rate = root.optJSONObject("rate_limit");
        Boolean allowed = rate != null && rate.opt("allowed") instanceof Boolean ? rate.getBoolean("allowed") : null;
        List<Extra> extras = new ArrayList<>();
        JSONArray additional = root.optJSONArray("additional_rate_limits");
        if (additional != null) for (int i = 0; i < additional.length(); i++) {
            JSONObject item = additional.optJSONObject(i);
            if (item == null) continue;
            JSONObject limits = item.optJSONObject("rate_limit");
            extras.add(new Extra(item.optString("limit_name", item.optString("metered_feature", "Дополнительный лимит")),
                    window(limits, "primary_window", fetchedAt), window(limits, "secondary_window", fetchedAt)));
        }
        JSONObject credit = root.optJSONObject("credits");
        String credits = null;
        if (credit != null) {
            if (credit.optBoolean("unlimited", false)) credits = "Без ограничений";
            else if (credit.has("balance") && !credit.isNull("balance")) credits = String.valueOf(credit.get("balance"));
        }
        return new LimitData(root.optString("plan_type", "Не указан"), window(rate, "primary_window", fetchedAt),
                window(rate, "secondary_window", fetchedAt), extras, allowed, credits, fetchedAt);
    }
    /** Claude usage: {@code utilization} is the used percent; {@code resets_at} is ISO-8601 or null. */
    public static LimitData claude(JSONObject root, long fetchedAt) throws JSONException {
        if (!root.has("five_hour") && !root.has("seven_day")) throw new JSONException("Unexpected Claude usage response");
        List<Extra> extras = new ArrayList<>();
        String[][] models = { { "seven_day_opus", "Opus · неделя" }, { "seven_day_sonnet", "Sonnet · неделя" } };
        for (String[] model : models) {
            Window weekly = claudeWindow(root, model[0], 604800);
            if (weekly != null) extras.add(new Extra(model[1], weekly, null));
        }
        JSONObject extra = root.optJSONObject("extra_usage");
        String credits = null;
        if (extra != null && extra.optBoolean("is_enabled", false) && extra.opt("utilization") instanceof Number)
            credits = (int) Math.floor(Math.max(0, extra.getDouble("utilization"))) + "% месячного лимита использовано";
        return new LimitData("Claude", claudeWindow(root, "five_hour", 18000), claudeWindow(root, "seven_day", 604800),
                extras, null, credits, fetchedAt);
    }
    private static Window claudeWindow(JSONObject root, String key, long windowSeconds) {
        JSONObject w = root.optJSONObject(key);
        if (w == null || !(w.opt("utilization") instanceof Number)) return null;
        double used = w.optDouble("utilization", Double.NaN);
        if (!Double.isFinite(used)) return null;
        long reset = 0;
        String resetsAt = w.optString("resets_at", "");
        if (!w.isNull("resets_at") && !resetsAt.isEmpty()) {
            try { reset = java.time.OffsetDateTime.parse(resetsAt).toEpochSecond(); }
            catch (java.time.format.DateTimeParseException unknownFormat) { reset = 0; }
        }
        return new Window(used, windowSeconds, reset);
    }
    private static Window window(JSONObject rate, String key, long fetchedAt) {
        if (rate == null) return null;
        JSONObject w = rate.optJSONObject(key);
        if (w == null || !(w.opt("used_percent") instanceof Number)) return null;
        double used = w.optDouble("used_percent", Double.NaN);
        if (!Double.isFinite(used)) return null;
        long reset = w.optLong("reset_at", 0);
        if (reset <= 0 && w.opt("reset_after_seconds") instanceof Number)
            reset = fetchedAt / 1000 + Math.max(0, w.optLong("reset_after_seconds", 0));
        return new Window(used, w.optLong("limit_window_seconds", 0), reset);
    }
    public boolean stale(long now) {
        return now < fetchedAt || now - fetchedAt >= STALE_AFTER_MS
                || (primary != null && primary.resetAt > 0 && now / 1000 >= primary.resetAt)
                || (secondary != null && secondary.resetAt > 0 && now / 1000 >= secondary.resetAt);
    }
}
