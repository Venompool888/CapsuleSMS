package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small durable aggregation store for multipart SmsManager callbacks. */
final class SmsSendStatusStore {
    private static final String PREFS = "sms_send_status";
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
            preferences.edit().putString(key, state.toString()).commit();
        } catch (Exception ignored) {}

        boolean complete = true;
        boolean allSucceeded = true;
        for (int i = 0; i < count; i++) {
            if (results.isNull(i)) complete = false;
            else if (!results.optBoolean(i, false)) allSucceeded = false;
        }
        return new Aggregate(complete, allSucceeded, phaseState.optInt("error", errorCode));
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
