package io.github.venompool888.capsulesms.sync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class CloudSyncOutbox {
    private static final String PREFS = "cloud_sync_outbox";
    private static final String KEY = "events";
    private static final int MAX_EVENTS = 500;

    static synchronized void enqueue(Context context, JSONObject event) {
        List<JSONObject> events = read(context);
        String eventId = event.optString("eventId");
        for (JSONObject existing : events) {
            if (eventId.equals(existing.optString("eventId"))) return;
        }
        events.add(event);
        while (events.size() > MAX_EVENTS) events.remove(0);
        write(context, events);
    }

    static synchronized List<JSONObject> all(Context context) {
        return new ArrayList<>(read(context));
    }

    static synchronized void remove(Context context, String eventId) {
        List<JSONObject> events = read(context);
        events.removeIf(value -> eventId.equals(value.optString("eventId")));
        write(context, events);
    }

    private static List<JSONObject> read(Context context) {
        List<JSONObject> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]");
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) result.add(values.getJSONObject(i));
        } catch (Exception ignored) {}
        return result;
    }

    private static void write(Context context, List<JSONObject> events) {
        JSONArray values = new JSONArray();
        for (JSONObject event : events) values.put(event);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putString(KEY, values.toString()).apply();
    }

    private CloudSyncOutbox() {}
}
