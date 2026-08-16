package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class MmsTransferStore {
    private static final String PREFS = "mms_transfers";
    private final SharedPreferences preferences;

    MmsTransferStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void put(Pending pending) {
        preferences.edit().putString(pending.token, pending.toJson().toString()).commit();
    }

    synchronized Pending get(String token) {
        String raw = preferences.getString(token, null);
        if (raw == null) return null;
        try { return Pending.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return null; }
    }

    synchronized void remove(String token) {
        preferences.edit().remove(token).apply();
    }

    static final class Pending {
        final String token;
        final String contentLocation;
        final String fileName;
        final String notificationUri;
        final String sender;
        final String subject;
        final int subscriptionId;
        final int attempts;

        Pending(String token, String contentLocation, String fileName,
                String notificationUri, String sender, String subject,
                int subscriptionId, int attempts) {
            this.token = token;
            this.contentLocation = contentLocation;
            this.fileName = fileName;
            this.notificationUri = notificationUri;
            this.sender = sender;
            this.subject = subject;
            this.subscriptionId = subscriptionId;
            this.attempts = attempts;
        }

        Pending nextAttempt(String nextFileName) {
            return new Pending(token, contentLocation, nextFileName, notificationUri,
                    sender, subject, subscriptionId, attempts + 1);
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("token", token)
                        .put("contentLocation", contentLocation)
                        .put("fileName", fileName)
                        .put("notificationUri", notificationUri)
                        .put("sender", sender)
                        .put("subject", subject)
                        .put("subscriptionId", subscriptionId)
                        .put("attempts", attempts);
            } catch (Exception ignored) {}
            return value;
        }

        static Pending fromJson(JSONObject value) {
            return new Pending(value.optString("token"), value.optString("contentLocation"),
                    value.optString("fileName"), value.optString("notificationUri", null),
                    value.optString("sender", "未知号码"), value.optString("subject", ""),
                    value.optInt("subscriptionId", -1), value.optInt("attempts", 0));
        }
    }
}
