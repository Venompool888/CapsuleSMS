package io.github.venompool888.capsulesms.sms;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

public final class SmsRepository {
    private static final Uri SMS_URI = Telephony.Sms.CONTENT_URI;
    private final ContentResolver resolver;
    private final CloudMessageOriginStore originStore;

    public SmsRepository(Context context) {
        resolver = context.getContentResolver();
        originStore = new CloudMessageOriginStore(context);
    }

    public List<SmsModels.Conversation> conversations() {
        Map<String, SmsModels.Conversation> result = new LinkedHashMap<>();
        String[] projection = {"_id", "address", "body", "date", "read", "sub_id", "type"};
        try (Cursor cursor = resolver.query(SMS_URI, projection, null, null, "date DESC")) {
            if (cursor == null) return List.of();
            int idIndex = cursor.getColumnIndexOrThrow("_id");
            int addressIndex = cursor.getColumnIndexOrThrow("address");
            int bodyIndex = cursor.getColumnIndexOrThrow("body");
            int dateIndex = cursor.getColumnIndexOrThrow("date");
            int readIndex = cursor.getColumnIndexOrThrow("read");
            int subscriptionIndex = cursor.getColumnIndexOrThrow("sub_id");
            int typeIndex = cursor.getColumnIndexOrThrow("type");
            while (cursor.moveToNext()) {
                String address = safe(cursor.getString(addressIndex), "未知号码");
                SmsModels.Conversation previous = result.get(address);
                int unread = (previous == null ? 0 : previous.unread()) + (cursor.getInt(readIndex) == 0 ? 1 : 0);
                if (previous == null) {
                    result.put(address, new SmsModels.Conversation(
                            address, safe(cursor.getString(bodyIndex), ""), cursor.getLong(dateIndex), unread,
                            cursor.getInt(subscriptionIndex), cursor.getLong(idIndex),
                            cursor.getInt(typeIndex), originStore.getOrigin(cursor.getLong(idIndex))));
                } else if (unread != previous.unread()) {
                    result.put(address, new SmsModels.Conversation(
                            previous.address(), previous.preview(), previous.date(), unread,
                            previous.subscriptionId(), previous.latestMessageId(),
                            previous.latestMessageType(), previous.origin()));
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    public List<SmsModels.Message> recentMessages(String address, int limit) {
        List<SmsModels.Message> result = new ArrayList<>();
        String[] projection = {"_id", "address", "body", "date", "type"};
        try (Cursor cursor = resolver.query(
                SMS_URI, projection, "address = ?", new String[]{address},
                "date DESC LIMIT " + Math.max(1, limit))) {
            if (cursor == null) return result;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                result.add(new SmsModels.Message(
                        id, safe(cursor.getString(1), address),
                        safe(cursor.getString(2), ""), cursor.getLong(3), cursor.getInt(4),
                        originStore.getOrigin(id)));
            }
        }
        Collections.reverse(result);
        return result;
    }

    public CloudMessageOriginStore.Origin latestCloudOrigin(String address) {
        try (Cursor cursor = resolver.query(
                SMS_URI, new String[]{"_id", "type"}, "address = ?", new String[]{address},
                "date DESC LIMIT 100")) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) != Telephony.Sms.MESSAGE_TYPE_INBOX) continue;
                CloudMessageOriginStore.Origin origin = originStore.getOrigin(cursor.getLong(0));
                // A local incoming SMS is authoritative: reply locally. Legacy cloud rows only
                // had a device name, so keep looking for the newest row with a complete route.
                if (origin == null) return null;
                if (origin.deviceId != null && origin.subscriptionId >= 0) return origin;
            }
        }
        return null;
    }

    public int latestSubscriptionId(String address) {
        try (Cursor cursor = resolver.query(
                SMS_URI, new String[]{"sub_id"}, "address = ?", new String[]{address},
                "date DESC LIMIT 1")) {
            return cursor != null && cursor.moveToFirst()
                    ? cursor.getInt(0) : android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    public void markRead(String address) {
        ContentValues values = new ContentValues();
        values.put("read", 1);
        resolver.update(SMS_URI, values, "address = ? AND read = 0", new String[]{address});
    }

    public int moveToTrash(Context context, Set<Long> messageIds) {
        TrashStore trash = new TrashStore(context);
        int moved = 0;
        String[] projection = {
                "_id", "address", "body", "date", "type", "read", "seen", "sub_id"
        };
        for (long id : messageIds) {
            try (Cursor cursor = resolver.query(SMS_URI, projection, "_id = ?",
                    new String[]{Long.toString(id)}, null)) {
                if (cursor == null || !cursor.moveToFirst()) continue;
                TrashStore.Entry entry = new TrashStore.Entry(
                        cursor.getLong(0), safe(cursor.getString(1), "未知号码"),
                        safe(cursor.getString(2), ""), cursor.getLong(3), cursor.getInt(4),
                        cursor.getInt(5), cursor.getInt(6), cursor.getInt(7),
                        System.currentTimeMillis());
                trash.add(entry);
                int deleted = resolver.delete(SMS_URI, "_id = ?", new String[]{Long.toString(id)});
                if (deleted == 1) moved++;
                else trash.remove(entry.trashId());
            }
        }
        return moved;
    }

    public int moveConversationToTrash(Context context, String address) {
        TrashStore trash = new TrashStore(context);
        List<TrashStore.Entry> entries = new ArrayList<>();
        String[] projection = {
                "_id", "address", "body", "date", "type", "read", "seen", "sub_id"
        };
        try (Cursor cursor = resolver.query(SMS_URI, projection, "address = ?",
                new String[]{address}, "date ASC")) {
            if (cursor == null) return 0;
            long deletedAt = System.currentTimeMillis();
            while (cursor.moveToNext()) {
                entries.add(new TrashStore.Entry(
                        cursor.getLong(0), safe(cursor.getString(1), "未知号码"),
                        safe(cursor.getString(2), ""), cursor.getLong(3), cursor.getInt(4),
                        cursor.getInt(5), cursor.getInt(6), cursor.getInt(7), deletedAt));
            }
        }
        if (entries.isEmpty()) return 0;
        trash.addAll(entries);
        int deleted = resolver.delete(SMS_URI, "address = ?", new String[]{address});
        if (deleted == 0) trash.removeAll(entries);
        return deleted;
    }

    public static Uri saveIncoming(
            Context context, String address, String body, long timestamp, int subscriptionId) {
        ContentValues values = baseValues(address, body, timestamp);
        values.put("read", 0);
        values.put("seen", 0);
        if (subscriptionId >= 0) values.put("sub_id", subscriptionId);
        Uri saved = context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        if (saved != null) context.getContentResolver().notifyChange(SMS_URI, null);
        return saved;
    }

    public static Uri saveSent(
            Context context, String address, String body, long timestamp, int subscriptionId) {
        ContentValues values = baseValues(address, body, timestamp);
        values.put("read", 1);
        values.put("seen", 1);
        if (subscriptionId >= 0) values.put("sub_id", subscriptionId);
        Uri saved = context.getContentResolver().insert(Telephony.Sms.Sent.CONTENT_URI, values);
        if (saved != null) context.getContentResolver().notifyChange(SMS_URI, null);
        return saved;
    }

    public static boolean containsMessage(
            Context context, String address, String body, long timestamp, int type) {
        long tolerance = 2_000L;
        String selection = "address = ? AND body = ? AND type = ? AND date BETWEEN ? AND ?";
        String[] args = {
                address, body, Integer.toString(type),
                Long.toString(Math.max(0L, timestamp - tolerance)),
                Long.toString(timestamp + tolerance)
        };
        try (Cursor cursor = context.getContentResolver().query(
                SMS_URI, new String[]{"_id"}, selection, args, "date DESC LIMIT 1")) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    public static long findMessageId(
            Context context, String address, String body, long timestamp, int type) {
        long tolerance = 2_000L;
        String selection = "address = ? AND body = ? AND type = ? AND date BETWEEN ? AND ?";
        String[] args = {
                address, body, Integer.toString(type),
                Long.toString(Math.max(0L, timestamp - tolerance)),
                Long.toString(timestamp + tolerance)
        };
        try (Cursor cursor = context.getContentResolver().query(
                SMS_URI, new String[]{"_id"}, selection, args, "date DESC LIMIT 1")) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        }
    }

    private static ContentValues baseValues(String address, String body, long timestamp) {
        ContentValues values = new ContentValues();
        values.put("address", address);
        values.put("body", body);
        values.put("date", timestamp);
        return values;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
