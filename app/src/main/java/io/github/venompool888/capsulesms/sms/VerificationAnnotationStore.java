package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VerificationAnnotationStore {
    private static final String PREFS = "manual_verification_annotations";
    private final SharedPreferences preferences;
    private final Map<Long, Annotation> automaticCache = new ConcurrentHashMap<>();
    private final Set<Long> automaticMisses = ConcurrentHashMap.newKeySet();

    public VerificationAnnotationStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void put(long messageId, String code) {
        if (messageId < 0 || code == null || code.isBlank()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("code", code.trim());
            value.put("markedAt", System.currentTimeMillis());
            preferences.edit().putString(Long.toString(messageId), value.toString()).apply();
        } catch (Exception ignored) {}
    }

    public Annotation get(long messageId) {
        if (messageId < 0) return null;
        String raw = preferences.getString(Long.toString(messageId), null);
        if (raw == null) return null;
        try {
            JSONObject value = new JSONObject(raw);
            String code = value.optString("code", "").trim();
            return code.isEmpty() ? null : new Annotation(code);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Manual debug annotations win; all normal messages use the local parser. */
    public Annotation resolve(long messageId, String body) {
        Annotation manual = get(messageId);
        if (manual != null) return manual;
        if (messageId >= 0) {
            Annotation cached = automaticCache.get(messageId);
            if (cached != null) return cached;
            if (automaticMisses.contains(messageId)) return null;
        }
        OtpParser.Result parsed = OtpParser.parse(body);
        Annotation automatic = parsed.status() == OtpParser.Result.Status.SUCCESS
                ? new Annotation(parsed.code()) : null;
        if (messageId >= 0) {
            if (automatic == null) automaticMisses.add(messageId);
            else automaticCache.put(messageId, automatic);
        }
        return automatic;
    }

    public static final class Annotation {
        private final String code;

        Annotation(String code) { this.code = code; }
        public String code() { return code; }
    }
}
