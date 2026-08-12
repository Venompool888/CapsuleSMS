package io.github.venompool888.capsulesms.sms;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class TrashStore {
    private static final String PREFS = "sms_trash";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_RETENTION_DAYS = "retention_days";
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final Context context;
    private final SharedPreferences preferences;

    public TrashStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Entry> all() {
        List<Entry> result = readEntries();
        result.sort(Comparator.comparingLong(Entry::deletedAt).reversed());
        return result;
    }

    public synchronized void add(Entry entry) {
        List<Entry> values = readEntries();
        values.add(entry);
        writeEntries(values);
    }

    public synchronized void addAll(List<Entry> entries) {
        if (entries.isEmpty()) return;
        List<Entry> values = readEntries();
        values.addAll(entries);
        writeEntries(values);
    }

    public synchronized void remove(String trashId) {
        List<Entry> values = readEntries();
        values.removeIf(value -> value.trashId.equals(trashId));
        writeEntries(values);
    }

    public synchronized void removeAll(List<Entry> entries) {
        if (entries.isEmpty()) return;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Entry entry : entries) ids.add(entry.trashId);
        List<Entry> values = readEntries();
        values.removeIf(value -> ids.contains(value.trashId));
        writeEntries(values);
    }

    public synchronized boolean restore(String trashId) {
        Entry target = null;
        for (Entry entry : readEntries()) {
            if (entry.trashId.equals(trashId)) {
                target = entry;
                break;
            }
        }
        if (target == null) return false;
        ContentValues values = new ContentValues();
        values.put("address", target.address);
        values.put("body", target.body);
        values.put("date", target.date);
        values.put("read", target.read);
        values.put("seen", target.seen);
        if (target.subscriptionId >= 0) values.put("sub_id", target.subscriptionId);
        Uri destination = destinationForType(target.type);
        Uri restored = context.getContentResolver().insert(destination, values);
        if (restored == null) return false;
        remove(trashId);
        return true;
    }

    public int retentionDays() {
        return preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
    }

    public void setRetentionDays(int days) {
        preferences.edit().putInt(KEY_RETENTION_DAYS, Math.max(0, days)).apply();
        purgeExpired();
    }

    public synchronized int purgeExpired() {
        int days = retentionDays();
        if (days == 0) return 0;
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        List<Entry> values = readEntries();
        int before = values.size();
        values.removeIf(value -> value.deletedAt < cutoff);
        if (values.size() != before) writeEntries(values);
        return before - values.size();
    }

    private List<Entry> readEntries() {
        List<Entry> result = new ArrayList<>();
        String raw = preferences.getString(KEY_MESSAGES, "[]");
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) result.add(Entry.fromJson(values.getJSONObject(i)));
        } catch (Exception ignored) {}
        return result;
    }

    private void writeEntries(List<Entry> entries) {
        JSONArray values = new JSONArray();
        for (Entry entry : entries) values.put(entry.toJson());
        preferences.edit().putString(KEY_MESSAGES, values.toString()).commit();
    }

    private static Uri destinationForType(int type) {
        if (type == Telephony.Sms.MESSAGE_TYPE_SENT) return Telephony.Sms.Sent.CONTENT_URI;
        if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) return Telephony.Sms.Draft.CONTENT_URI;
        if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                || type == Telephony.Sms.MESSAGE_TYPE_FAILED
                || type == Telephony.Sms.MESSAGE_TYPE_QUEUED) return Telephony.Sms.Outbox.CONTENT_URI;
        return Telephony.Sms.Inbox.CONTENT_URI;
    }

    public static final class Entry {
        private final String trashId;
        private final long originalId;
        private final String address;
        private final String body;
        private final long date;
        private final int type;
        private final int read;
        private final int seen;
        private final int subscriptionId;
        private final long deletedAt;

        public Entry(long originalId, String address, String body, long date, int type,
                int read, int seen, int subscriptionId, long deletedAt) {
            this(UUID.randomUUID().toString(), originalId, address, body, date, type,
                    read, seen, subscriptionId, deletedAt);
        }

        private Entry(String trashId, long originalId, String address, String body, long date,
                int type, int read, int seen, int subscriptionId, long deletedAt) {
            this.trashId = trashId;
            this.originalId = originalId;
            this.address = address;
            this.body = body;
            this.date = date;
            this.type = type;
            this.read = read;
            this.seen = seen;
            this.subscriptionId = subscriptionId;
            this.deletedAt = deletedAt;
        }

        public String trashId() { return trashId; }
        public String address() { return address; }
        public String body() { return body; }
        public long date() { return date; }
        public int type() { return type; }
        public int subscriptionId() { return subscriptionId; }
        public long deletedAt() { return deletedAt; }
        public boolean isOutgoing() {
            return type == Telephony.Sms.MESSAGE_TYPE_SENT
                    || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                    || type == Telephony.Sms.MESSAGE_TYPE_FAILED
                    || type == Telephony.Sms.MESSAGE_TYPE_QUEUED;
        }

        private JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("trashId", trashId).put("originalId", originalId)
                        .put("address", address).put("body", body).put("date", date)
                        .put("type", type).put("read", read).put("seen", seen)
                        .put("subscriptionId", subscriptionId).put("deletedAt", deletedAt);
            } catch (Exception ignored) {}
            return value;
        }

        private static Entry fromJson(JSONObject value) {
            return new Entry(value.optString("trashId", UUID.randomUUID().toString()),
                    value.optLong("originalId", -1),
                    value.optString("address", "未知号码"), value.optString("body", ""),
                    value.optLong("date", 0), value.optInt("type", 1),
                    value.optInt("read", 1), value.optInt("seen", 1),
                    value.optInt("subscriptionId", -1), value.optLong("deletedAt", 0));
        }
    }
}
