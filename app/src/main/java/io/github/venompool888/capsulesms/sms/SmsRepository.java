package io.github.venompool888.capsulesms.sms;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class SmsRepository {
    private static final Uri SMS_URI = Telephony.Sms.CONTENT_URI;
    private static final Comparator<SmsModels.Message> MESSAGE_ORDER = Comparator
            .comparingLong(SmsModels.Message::date).thenComparingLong(SmsModels.Message::id);
    private static final Comparator<MmsPageRow> MMS_PAGE_ORDER = Comparator
            .comparingLong(MmsPageRow::date).thenComparingLong(row -> -row.id());
    private final Context context;
    private final ContentResolver resolver;
    private final CloudMessageOriginStore originStore;

    public SmsRepository(Context context) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
        originStore = new CloudMessageOriginStore(context);
    }

    public List<SmsModels.Conversation> conversations() {
        Map<Long, ConversationAccumulator> result = new LinkedHashMap<>();
        String[] projection = {
                "_id", "thread_id", "address", "body", "date", "read", "sub_id", "type"
        };
        try (Cursor cursor = resolver.query(SMS_URI, projection, null, null, "date DESC")) {
            if (cursor != null) {
            int idIndex = cursor.getColumnIndexOrThrow("_id");
            int threadIndex = cursor.getColumnIndexOrThrow("thread_id");
            int addressIndex = cursor.getColumnIndexOrThrow("address");
            int bodyIndex = cursor.getColumnIndexOrThrow("body");
            int dateIndex = cursor.getColumnIndexOrThrow("date");
            int readIndex = cursor.getColumnIndexOrThrow("read");
            int subscriptionIndex = cursor.getColumnIndexOrThrow("sub_id");
            int typeIndex = cursor.getColumnIndexOrThrow("type");
            while (cursor.moveToNext()) {
                String address = safe(cursor.getString(addressIndex), "未知号码");
                long id = cursor.getLong(idIndex);
                long threadId = stableThreadId(cursor.getLong(threadIndex), id, false);
                ConversationAccumulator value = result.computeIfAbsent(
                        threadId, ignored -> new ConversationAccumulator(threadId));
                value.unread += cursor.getInt(readIndex) == 0 ? 1 : 0;
                value.offer(address, List.of(address), safe(cursor.getString(bodyIndex), ""),
                        cursor.getLong(dateIndex), cursor.getInt(subscriptionIndex), id,
                        cursor.getInt(typeIndex), false, originStore.getOrigin(id));
            }
            }
        }

        Uri mmsUri = Telephony.Mms.CONTENT_URI;
        String[] mmsProjection = {
                "_id", "thread_id", "date", "date_sent", "read", "sub_id", "msg_box", "sub"
        };
        try (Cursor cursor = resolver.query(mmsUri, mmsProjection, null, null, "date DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long threadId = stableThreadId(cursor.getLong(1), id, true);
                long date = mmsDateMillis(cursor.getLong(2), cursor.getLong(3));
                ConversationAccumulator value = result.computeIfAbsent(
                        threadId, ignored -> new ConversationAccumulator(threadId));
                value.unread += cursor.getInt(4) == 0 ? 1 : 0;
                List<String> recipients = value.recipients.isEmpty() || date >= value.date
                        ? mmsRecipients(id, cursor.getInt(5), cursor.getInt(6))
                        : value.recipients;
                String address = recipients.isEmpty() ? "未知号码" : String.join("、", recipients);
                String subject = safe(cursor.getString(7), "");
                value.offer(address, recipients, subject.isBlank() ? "[彩信]" : subject,
                        date, cursor.getInt(5), -id, cursor.getInt(6), true, null);
            }
        }
        List<SmsModels.Conversation> conversations = new ArrayList<>();
        for (ConversationAccumulator value : result.values()) conversations.add(value.build());
        conversations.sort((left, right) -> Long.compare(right.date(), left.date()));
        return conversations;
    }

    public List<SmsModels.Message> recentMessages(String address, int limit) {
        return recentMessages(-1L, address, limit);
    }

    public List<SmsModels.Message> recentMessages(long threadId, String address, int limit) {
        return messagesBefore(threadId, address, null, limit);
    }

    /** Returns the page strictly before the cursor in timestamp and message ID order. */
    public List<SmsModels.Message> messagesBefore(
            long threadId, String address, SmsModels.Message before, int limit) {
        if (limit <= 0) return List.of();
        List<SmsModels.Message> result = new ArrayList<>();
        String[] projection = {
                "_id", "thread_id", "address", "body", "date", "type", "status"
        };
        String selection = threadId > 0 ? "thread_id = ?" : "address = ?";
        String[] args = {threadId > 0 ? Long.toString(threadId) : address};
        if (before != null) {
            selection += " AND (date < ? OR (date = ? AND _id < ?))";
            String date = Long.toString(before.date());
            args = new String[]{args[0], date, date, Long.toString(before.id())};
        }
        try (Cursor cursor = resolver.query(
                SMS_URI, projection, selection, args,
                "date DESC, _id DESC")) {
            if (cursor != null) while (result.size() < limit && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                result.add(new SmsModels.Message(
                        id, id, cursor.getLong(1), safe(cursor.getString(2), address),
                        safe(cursor.getString(3), ""), cursor.getLong(4), cursor.getInt(5),
                        SmsModels.Message.Transport.SMS,
                        SmsModels.Message.deliveryForSms(cursor.getInt(5), cursor.getInt(6)),
                        List.of(), originStore.getOrigin(id)));
            }
        }
        if (threadId > 0) for (MmsPageRow row : mmsPage(threadId, before, limit)) {
            result.add(readMmsMessage(threadId, address, row));
        }
        result.sort(MESSAGE_ORDER);
        if (result.size() > limit) return new ArrayList<>(
                result.subList(result.size() - limit, result.size()));
        return result;
    }

    private Iterable<MmsPageRow> mmsPage(long threadId, SmsModels.Message before, int limit) {
        String[] projection = {"_id", "date", "date_sent", "msg_box", "sub_id", "sub", "st"};
        PriorityQueue<MmsPageRow> rows = new PriorityQueue<>(MMS_PAGE_ORDER);
        // The displayed date can come from date_sent and either source can be in seconds.
        try (Cursor cursor = resolver.query(Telephony.Mms.CONTENT_URI, projection,
                "thread_id = ?", new String[]{Long.toString(threadId)}, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long date = mmsDateMillis(cursor.getLong(1), cursor.getLong(2));
                if (before != null && (date > before.date()
                        || (date == before.date() && -id >= before.id()))) continue;
                rows.add(new MmsPageRow(id, date, cursor.getInt(3), cursor.getInt(4),
                        safe(cursor.getString(5), ""), cursor.getInt(6)));
                if (rows.size() > limit) rows.remove();
            }
        }
        return rows;
    }

    private SmsModels.Message readMmsMessage(long threadId, String address, MmsPageRow row) {
        List<SmsModels.Attachment> attachments = new ArrayList<>();
        String partText = mmsBodyAndAttachments(row.id(), attachments);
        String body = joinMmsText(row.subject(), partText, attachments);
        List<String> recipients = mmsRecipients(row.id(), row.subscriptionId(), row.box());
        String sender = recipients.isEmpty() ? address : String.join("、", recipients);
        return new SmsModels.Message(-row.id(), row.id(), threadId, sender, body, row.date(),
                row.box(), SmsModels.Message.Transport.MMS, mmsDelivery(row.box(), row.status()),
                attachments, null);
    }

    private static final class MmsPageRow {
        private final long id;
        private final long date;
        private final int box;
        private final int subscriptionId;
        private final String subject;
        private final int status;

        MmsPageRow(long id, long date, int box, int subscriptionId, String subject, int status) {
            this.id = id;
            this.date = date;
            this.box = box;
            this.subscriptionId = subscriptionId;
            this.subject = subject;
            this.status = status;
        }

        long id() { return id; }
        long date() { return date; }
        int box() { return box; }
        int subscriptionId() { return subscriptionId; }
        String subject() { return subject; }
        int status() { return status; }
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

    public int latestSubscriptionId(long threadId, String address) {
        if (threadId <= 0) return latestSubscriptionId(address);
        try (Cursor cursor = resolver.query(SMS_URI, new String[]{"sub_id"},
                "thread_id = ?", new String[]{Long.toString(threadId)}, "date DESC LIMIT 1")) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getInt(0);
        }
        try (Cursor cursor = resolver.query(Telephony.Mms.CONTENT_URI, new String[]{"sub_id"},
                "thread_id = ?", new String[]{Long.toString(threadId)}, "date DESC LIMIT 1")) {
            return cursor != null && cursor.moveToFirst()
                    ? cursor.getInt(0) : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    public void markRead(String address) {
        ContentValues values = new ContentValues();
        values.put("read", 1);
        resolver.update(SMS_URI, values, "address = ? AND read = 0", new String[]{address});
    }

    public void markRead(long threadId, String address) {
        if (threadId <= 0) {
            markRead(address);
            return;
        }
        ContentValues values = new ContentValues();
        values.put("read", 1);
        values.put("seen", 1);
        String[] args = {Long.toString(threadId)};
        resolver.update(SMS_URI, values, "thread_id = ? AND read = 0", args);
        resolver.update(Telephony.Mms.CONTENT_URI, values, "thread_id = ? AND read = 0", args);
    }

    public long findThreadId(String address) {
        try (Cursor cursor = resolver.query(SMS_URI, new String[]{"thread_id"},
                "address = ?", new String[]{address}, "date DESC LIMIT 1")) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        }
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

    public int deleteSelectedMms(Set<Long> messageIds) {
        int deleted = 0;
        for (long compositeId : messageIds) {
            if (compositeId >= 0) continue;
            long id = -compositeId;
            deleted += resolver.delete(Uri.withAppendedPath(
                    Telephony.Mms.CONTENT_URI, Long.toString(id)), null, null);
        }
        return deleted;
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

    public int moveConversationToTrash(Context context, long threadId, String address) {
        if (threadId <= 0) return moveConversationToTrash(context, address);
        TrashStore trash = new TrashStore(context);
        List<TrashStore.Entry> entries = new ArrayList<>();
        String[] projection = {
                "_id", "address", "body", "date", "type", "read", "seen", "sub_id"
        };
        try (Cursor cursor = resolver.query(SMS_URI, projection, "thread_id = ?",
                new String[]{Long.toString(threadId)}, "date ASC")) {
            if (cursor != null) {
                long deletedAt = System.currentTimeMillis();
                while (cursor.moveToNext()) {
                    entries.add(new TrashStore.Entry(
                            cursor.getLong(0), safe(cursor.getString(1), "未知号码"),
                            safe(cursor.getString(2), ""), cursor.getLong(3), cursor.getInt(4),
                            cursor.getInt(5), cursor.getInt(6), cursor.getInt(7), deletedAt));
                }
            }
        }
        trash.addAll(entries);
        String[] args = {Long.toString(threadId)};
        int smsDeleted = resolver.delete(SMS_URI, "thread_id = ?", args);
        if (smsDeleted == 0) trash.removeAll(entries);
        int mmsDeleted = resolver.delete(Telephony.Mms.CONTENT_URI, "thread_id = ?", args);
        return smsDeleted + mmsDeleted;
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

    public static long queueOutgoing(
            Context context, String address, String body, long timestamp, int subscriptionId) {
        ContentValues values = baseValues(address, body, timestamp);
        values.put("read", 1);
        values.put("seen", 1);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING);
        if (subscriptionId >= 0) values.put("sub_id", subscriptionId);
        Uri saved = context.getContentResolver().insert(Telephony.Sms.Outbox.CONTENT_URI, values);
        if (saved == null) return -1L;
        context.getContentResolver().notifyChange(SMS_URI, null);
        try { return Long.parseLong(saved.getLastPathSegment()); }
        catch (Exception ignored) { return -1L; }
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

    private static long stableThreadId(long threadId, long messageId, boolean mms) {
        if (threadId > 0) return threadId;
        return mms ? Long.MIN_VALUE + messageId : Long.MAX_VALUE - messageId;
    }

    private static long mmsDateMillis(long date, long dateSent) {
        long value = dateSent > 0 ? dateSent : date;
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private List<String> mmsRecipients(long messageId, int subscriptionId, int messageBox) {
        List<String> result = new ArrayList<>();
        String self = null;
        try {
            if (subscriptionId >= 0
                    && context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                            == PackageManager.PERMISSION_GRANTED) {
                self = context.getSystemService(
                        SubscriptionManager.class).getPhoneNumber(subscriptionId);
            }
        } catch (Exception ignored) {}
        Uri uri = Uri.parse("content://mms/" + messageId + "/addr");
        try (Cursor cursor = resolver.query(uri, new String[]{"address", "type"},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String address = safe(cursor.getString(0), "");
                int type = cursor.getInt(1);
                if (address.isBlank() || "insert-address-token".equals(address)) continue;
                if (messageBox == Telephony.Mms.MESSAGE_BOX_SENT
                        || messageBox == Telephony.Mms.MESSAGE_BOX_OUTBOX
                        || messageBox == Telephony.Mms.MESSAGE_BOX_DRAFTS
                        || messageBox == Telephony.Mms.MESSAGE_BOX_FAILED) {
                    if (type != 151 && type != 130) continue;
                } else if (type != 137 && type != 151 && type != 130) continue;
                if (self != null && samePhone(address, self)) continue;
                if (!result.contains(address)) result.add(address);
            }
        }
        return result;
    }

    private static boolean samePhone(String left, String right) {
        String a = PhoneNumberUtils.normalizeNumber(left);
        String b = PhoneNumberUtils.normalizeNumber(right);
        if (a.isBlank() || b.isBlank()) return false;
        int length = Math.min(8, Math.min(a.length(), b.length()));
        return a.regionMatches(a.length() - length, b, b.length() - length, length);
    }

    private String mmsBodyAndAttachments(long messageId, List<SmsModels.Attachment> attachments) {
        StringBuilder text = new StringBuilder();
        Uri uri = Uri.parse("content://mms/" + messageId + "/part");
        String[] projection = {"_id", "ct", "name", "fn", "text", "_data"};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                long partId = cursor.getLong(0);
                String mime = safe(cursor.getString(1), "application/octet-stream");
                if ("application/smil".equalsIgnoreCase(mime)) continue;
                Uri partUri = Uri.parse("content://mms/part/" + partId);
                if (mime.startsWith("text/")) {
                    String value = cursor.getString(4);
                    if ((value == null || value.isBlank()) && cursor.getString(5) != null) {
                        value = readText(partUri);
                    }
                    if (value != null && !value.isBlank()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(value);
                    }
                    continue;
                }
                String name = safe(cursor.getString(3), safe(cursor.getString(2), "附件"));
                attachments.add(new SmsModels.Attachment(partUri, mime, name, partSize(partUri)));
            }
        }
        return text.toString();
    }

    private String readText(Uri uri) {
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return "";
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private long partSize(Uri uri) {
        try (android.content.res.AssetFileDescriptor descriptor =
                     resolver.openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? 0L : Math.max(0L, descriptor.getLength());
        } catch (Exception ignored) { return 0L; }
    }

    private static String joinMmsText(
            String subject, String body, List<SmsModels.Attachment> attachments) {
        StringBuilder value = new StringBuilder();
        if (!subject.isBlank()) value.append(subject);
        if (!body.isBlank()) {
            if (value.length() > 0 && !body.equals(subject)) value.append('\n');
            if (!body.equals(subject)) value.append(body);
        }
        if (value.length() == 0 && !attachments.isEmpty()) {
            value.append(attachments.size()).append(" 个附件");
        }
        return value.length() == 0 ? "彩信" : value.toString();
    }

    private static SmsModels.Message.Delivery mmsDelivery(int box, int status) {
        if (box == Telephony.Mms.MESSAGE_BOX_FAILED) return SmsModels.Message.Delivery.FAILED;
        if (box == Telephony.Mms.MESSAGE_BOX_OUTBOX) return SmsModels.Message.Delivery.SENDING;
        if (box == Telephony.Mms.MESSAGE_BOX_SENT) return SmsModels.Message.Delivery.SENT;
        return SmsModels.Message.Delivery.NONE;
    }

    private static final class ConversationAccumulator {
        final long threadId;
        List<String> recipients = List.of();
        String address = "未知号码";
        String preview = "";
        long date = Long.MIN_VALUE;
        int unread;
        int subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        long latestMessageId = -1L;
        int latestMessageType = Telephony.Sms.MESSAGE_TYPE_INBOX;
        boolean latestIsMms;
        CloudMessageOriginStore.Origin origin;

        ConversationAccumulator(long threadId) { this.threadId = threadId; }

        void offer(String address, List<String> recipients, String preview, long date,
                int subscriptionId, long messageId, int type, boolean isMms,
                CloudMessageOriginStore.Origin origin) {
            if (!recipients.isEmpty() && (this.recipients.isEmpty() || recipients.size() > 1)) {
                this.recipients = List.copyOf(recipients);
            }
            if (date < this.date) return;
            this.address = address;
            this.preview = preview;
            this.date = date;
            this.subscriptionId = subscriptionId;
            this.latestMessageId = messageId;
            this.latestMessageType = type;
            this.latestIsMms = isMms;
            this.origin = origin;
            if (!recipients.isEmpty()) this.recipients = List.copyOf(recipients);
        }

        SmsModels.Conversation build() {
            List<String> values = recipients.isEmpty() ? List.of(address) : recipients;
            String label = values.size() > 1 ? String.join("、", values) : address;
            return new SmsModels.Conversation(threadId, label, values, preview, date, unread,
                    subscriptionId, latestMessageId, latestMessageType, latestIsMms, origin);
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
