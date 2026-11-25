package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** Small durable aggregation store for multipart SmsManager callbacks. */
final class SmsSendStatusStore {
    private static final String PREFS = "sms_send_status";
    /**
     * Entries normally go once the delivery report arrives, but failed sends and carriers
     * without delivery reports never send one. Anything untouched this long is dropped.
     */
    static final long MAX_ENTRY_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private final SharedPreferences preferences;

    SmsSendStatusStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Aggregate recordSent(
            long id, int part, int count, boolean success, int errorCode) {
        return record(id, "sent", part, count, success, errorCode);
    }

    synchronized Aggregate recordDelivered(
            long id, int part, int count, boolean success, int errorCode) {
        return record(id, "delivered", part, count, success, errorCode);
    }

    synchronized void clear(long id) {
        preferences.edit().remove(Long.toString(id)).apply();
    }

    synchronized boolean claimUpload(long id) {
        String key = Long.toString(id);
        try {
            JSONObject state = new JSONObject(preferences.getString(key, "{}"));
            if (state.optBoolean("uploaded", false)) return false;
            state.put("uploaded", true);
            preferences.edit().putString(key, state.toString()).commit();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private Aggregate record(
            long id, String phase, int part, int count, boolean success, int errorCode) {
        String key = Long.toString(id);
        JSONObject state;
        try {
            state = new JSONObject(preferences.getString(key, "{}"));
        } catch (Exception ignored) {
            state = new JSONObject();
        }
        JSONObject phaseState = state.optJSONObject(phase);
        if (phaseState == null) phaseState = new JSONObject();
        JSONArray results = phaseState.optJSONArray("results");
        if (results == null || results.length() != count) {
            results = new JSONArray();
            for (int i = 0; i < count; i++) results.put(JSONObject.NULL);
        }
        try {
            results.put(part, success);
            phaseState.put("results", results);
            if (!success) phaseState.put("error", errorCode);
            state.put(phase, phaseState);
            long now = System.currentTimeMillis();
            state.put("updatedAt", now);
            SharedPreferences.Editor editor = preferences.edit().putString(key, state.toString());
            pruneStale(editor, key, now);
            editor.commit();
        } catch (Exception ignored) {}

        boolean complete = true;
        boolean allSucceeded = true;
        for (int i = 0; i < count; i++) {
            if (results.isNull(i)) complete = false;
            else if (!results.optBoolean(i, false)) allSucceeded = false;
        }
        return new Aggregate(complete, allSucceeded, phaseState.optInt("error", errorCode));
    }

    private void pruneStale(SharedPreferences.Editor editor, String keep, long now) {
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().equals(keep) || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject state = new JSONObject((String) entry.getValue());
                long updatedAt = state.optLong("updatedAt", 0L);
                if (updatedAt <= 0) {
                    // Written before timestamps existed: start its clock now rather than drop it mid-send.
                    state.put("updatedAt", now);
                    editor.putString(entry.getKey(), state.toString());
                } else if (isStale(updatedAt, now)) {
                    editor.remove(entry.getKey());
                }
            } catch (Exception unreadable) {
                editor.remove(entry.getKey());
            }
        }
    }

    static boolean isStale(long updatedAt, long now) {
        return now - updatedAt > MAX_ENTRY_AGE_MS;
    }

    static final class Aggregate {
        final boolean complete;
        final boolean success;
        final int errorCode;

        Aggregate(boolean complete, boolean success, int errorCode) {
            this.complete = complete;
            this.success = success;
            this.errorCode = errorCode;
        }
    }
}
