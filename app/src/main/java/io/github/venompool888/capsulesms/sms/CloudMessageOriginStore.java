package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local presentation metadata for SMS rows received through cloud sync. */
public final class CloudMessageOriginStore {
    private static final String PREFS = "cloud_message_origins";
    private static final int MAX_ENTRIES = 2_000;
    private final SharedPreferences preferences;

    public CloudMessageOriginStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Origin {
        public final String deviceId;
        public final String deviceName;
        public final int subscriptionId;
        public final String lineNumber;
        public final int simSlot;

        public Origin(String deviceId, String deviceName, int subscriptionId,
                String lineNumber, int simSlot) {
            this.deviceId = blankToNull(deviceId);
            this.deviceName = blankToNull(deviceName);
            this.subscriptionId = subscriptionId;
            this.lineNumber = blankToNull(lineNumber);
            this.simSlot = simSlot;
        }

        public String lineLabel() {
            if (lineNumber != null) return lineNumber;
            return simSlot > 0 ? "SIM " + simSlot : null;
        }

        public String displayLabel() {
            String device = deviceName == null ? "远端设备" : deviceName;
            String line = lineLabel();
            return line == null ? device : device + " · " + line;
        }

        public static Origin deviceOnly(String deviceName) {
            return new Origin(null, deviceName, -1, null, -1);
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject()
                    .put("version", 1)
                    .put("subscriptionId", subscriptionId)
                    .put("simSlot", simSlot);
            if (deviceId != null) value.put("deviceId", deviceId);
            if (deviceName != null) value.put("deviceName", deviceName);
            if (lineNumber != null) value.put("lineNumber", lineNumber);
            return value;
        }

        static Origin fromStored(String raw) {
            if (raw == null || raw.isBlank()) return null;
            if (!raw.trim().startsWith("{")) return deviceOnly(raw);
            try {
                JSONObject value = new JSONObject(raw);
                return new Origin(
                        value.optString("deviceId", null),
                        value.optString("deviceName", null),
                        value.optInt("subscriptionId", -1),
                        value.optString("lineNumber", null),
                        value.optInt("simSlot", -1));
            } catch (Exception ignored) {
                return deviceOnly(raw);
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public Origin getOrigin(long messageId) {
        return Origin.fromStored(preferences.getString(Long.toString(messageId), null));
    }

    public String get(long messageId) {
        Origin origin = getOrigin(messageId);
        return origin == null ? null : origin.deviceName;
    }

    public void put(long messageId, String deviceName) {
        put(messageId, Origin.deviceOnly(deviceName));
    }

    public void put(long messageId, Origin origin) {
        if (messageId < 0 || origin == null || origin.deviceName == null) return;
        try {
            preferences.edit().putString(Long.toString(messageId), origin.toJson().toString()).apply();
        } catch (Exception ignored) {
            return;
        }
        pruneIfNeeded();
    }

    private void pruneIfNeeded() {
        if (preferences.getAll().size() <= MAX_ENTRIES) return;
        List<Long> ids = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            try { ids.add(Long.parseLong(key)); } catch (NumberFormatException ignored) {}
        }
        ids.sort(Comparator.naturalOrder());
        SharedPreferences.Editor editor = preferences.edit();
        int remove = Math.max(0, ids.size() - (MAX_ENTRIES * 9 / 10));
        for (int i = 0; i < remove; i++) editor.remove(Long.toString(ids.get(i)));
        editor.apply();
    }
}
