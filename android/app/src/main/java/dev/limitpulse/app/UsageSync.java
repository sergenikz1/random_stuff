package dev.limitpulse.app;

import org.json.JSONObject;

/** Rotating tokens are persisted before subsequent requests, including failure paths. */
final class UsageSync {
    interface Persist { void save(Credentials credentials) throws Exception; }
    interface Source {
        Credentials refresh(Credentials old, long now) throws Exception;
        JSONObject usage(Credentials credentials) throws Exception;
    }
    static JSONObject fetch(Source api, Credentials original, long now, Persist persist) throws Exception {
        Credentials current = original;
        boolean refreshed = false;
        if (current.expiresAt <= now + 60000) {
            current = api.refresh(current, now); persist.save(current); refreshed = true;
        }
        try { return api.usage(current); }
        catch (OpenAiApi.ApiException unauthorized) {
            if (unauthorized.status != 401 || refreshed) throw unauthorized;
            current = api.refresh(current, now); persist.save(current);
            return api.usage(current);
        }
    }
    private UsageSync() { }
}
