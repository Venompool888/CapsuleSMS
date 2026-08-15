package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.Telephony;

import io.github.venompool888.capsulesms.sync.CloudSyncEngine;

/** ADB-only fake incoming SMS entry point included in debug builds. */
public final class DebugSmsReceiver extends BroadcastReceiver {
    private static final String ACTION_SIMULATE_SMS =
            "io.github.venompool888.capsulesms.debug.SIMULATE_SMS";
    private static final String ACTION_SIMULATE_SENT_SMS =
            "io.github.venompool888.capsulesms.debug.SIMULATE_SENT_SMS";
    private static final String ACTION_DELETE_TEST_SMS =
            "io.github.venompool888.capsulesms.debug.DELETE_TEST_SMS";
    private static final String ACTION_VERIFY_TEST_SMS =
            "io.github.venompool888.capsulesms.debug.VERIFY_TEST_SMS";
    private static final String ACTION_BACKFILL_CLOUD_ORIGIN =
            "io.github.venompool888.capsulesms.debug.BACKFILL_CLOUD_ORIGIN";
    private static final String ACTION_UPLOAD_RECENT_SENT =
            "io.github.venompool888.capsulesms.debug.UPLOAD_RECENT_SENT";
    private static final String ACTION_UPLOAD_CONVERSATION =
            "io.github.venompool888.capsulesms.debug.UPLOAD_CONVERSATION";
    private static final String TEST_ADDRESS_PREFIX = "CloudSync";
    private static final String REALTIME_TEST_ADDRESS_PREFIX = "CloudRealtime";
    private static final String LINE_TEST_ADDRESS = "CloudLineRouteTest";

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_UPLOAD_CONVERSATION.equals(intent.getAction())) {
            uploadConversation(context, intent.getStringExtra("address"));
            return;
        }
        if (ACTION_UPLOAD_RECENT_SENT.equals(intent.getAction())) {
            uploadRecentSent(context, intent.getLongExtra("start", -1L));
            return;
        }
        if (ACTION_BACKFILL_CLOUD_ORIGIN.equals(intent.getAction())) {
            backfillCloudOrigin(context, intent);
            return;
        }
        if (ACTION_VERIFY_TEST_SMS.equals(intent.getAction())) {
            verifyTestMessage(context, intent.getStringExtra("address"),
                    intent.getStringExtra("body"));
            return;
        }
        if (ACTION_DELETE_TEST_SMS.equals(intent.getAction())) {
            deleteTestMessages(context, intent.getStringExtra("address"));
            return;
        }
        if (ACTION_SIMULATE_SENT_SMS.equals(intent.getAction())) {
            simulateSent(context, intent.getStringExtra("address"),
                    intent.getStringExtra("body"));
            return;
        }
        if (!ACTION_SIMULATE_SMS.equals(intent.getAction())) return;
        String address = intent.getStringExtra("address");
        String body = intent.getStringExtra("body");
        if (address == null || address.isBlank() || body == null || body.isBlank()) return;
        SmsDeliverReceiver.simulateIncoming(
                context, address, body, intent.getIntExtra("subscriptionId", -1));
    }

    private void uploadConversation(Context context, String requestedAddress) {
        if (requestedAddress == null || requestedAddress.isBlank()) {
            setResultCode(0);
            return;
        }
        int queued = 0;
        CloudMessageOriginStore origins = new CloudMessageOriginStore(context);
        String[] projection = {"_id", "address", "body", "date", "sub_id", "type"};
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, projection, "address = ?",
                new String[]{requestedAddress}, "date ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (origins.getOrigin(id) != null) continue;
                    int type = cursor.getInt(5);
                    if (type != Telephony.Sms.MESSAGE_TYPE_INBOX
                            && type != Telephony.Sms.MESSAGE_TYPE_SENT) continue;
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);
                    if (address == null || address.isBlank() || body == null) continue;
                    CloudSyncEngine.uploadAsync(context, address, body, cursor.getLong(3),
                            cursor.getInt(4), type);
                    queued++;
                }
            }
        }
        setResultCode(queued);
    }

    private void uploadRecentSent(Context context, long start) {
        if (start < 0) {
            setResultCode(0);
            return;
        }
        int queued = 0;
        CloudMessageOriginStore origins = new CloudMessageOriginStore(context);
        String[] projection = {"_id", "address", "body", "date", "sub_id"};
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, projection, "type = ? AND date >= ?",
                new String[]{Integer.toString(Telephony.Sms.MESSAGE_TYPE_SENT),
                        Long.toString(start)}, "date ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (origins.get(id) != null) continue;
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);
                    if (address == null || address.isBlank() || body == null) continue;
                    CloudSyncEngine.uploadAsync(context, address, body, cursor.getLong(3),
                            cursor.getInt(4), Telephony.Sms.MESSAGE_TYPE_SENT);
                    queued++;
                }
            }
        }
        setResultCode(queued);
    }

    private void backfillCloudOrigin(Context context, Intent intent) {
        long start = intent.getLongExtra("start", -1L);
        long end = intent.getLongExtra("end", -1L);
        String source = intent.getStringExtra("source");
        if (start < 0 || end < start || source == null || source.isBlank()) {
            setResultCode(0);
            return;
        }
        int tagged = 0;
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, new String[]{Telephony.Sms._ID},
                "type = ? AND date BETWEEN ? AND ?",
                new String[]{Integer.toString(Telephony.Sms.MESSAGE_TYPE_INBOX),
                        Long.toString(start), Long.toString(end)}, null)) {
            CloudMessageOriginStore store = new CloudMessageOriginStore(context);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    store.put(cursor.getLong(0), source);
                    tagged++;
                }
            }
        }
        setResultCode(tagged);
    }

    private void simulateSent(Context context, String address, String body) {
        if (!isTestAddress(address)
                || body == null || body.isBlank()) {
            setResultCode(0);
            return;
        }
        long timestamp = System.currentTimeMillis();
        SmsRepository.saveSent(context, address, body, timestamp, -1);
        CloudSyncEngine.uploadAsync(context, address, body, timestamp, -1,
                Telephony.Sms.MESSAGE_TYPE_SENT);
        setResultCode(1);
    }

    private void verifyTestMessage(Context context, String address, String body) {
        if (!isTestAddress(address) || body == null) {
            setResultCode(0);
            return;
        }
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, new String[]{Telephony.Sms._ID},
                "address = ? AND body = ?", new String[]{address, body}, null)) {
            setResultCode(cursor == null ? 0 : cursor.getCount());
        }
    }

    private void deleteTestMessages(Context context, String address) {
        if (!isTestAddress(address)) {
            setResultCode(0);
            return;
        }
        int deleted = context.getContentResolver().delete(
                Telephony.Sms.CONTENT_URI, "address = ?", new String[]{address});
        setResultCode(deleted);
    }

    private boolean isTestAddress(String address) {
        return address != null && (address.startsWith(TEST_ADDRESS_PREFIX)
                || address.startsWith(REALTIME_TEST_ADDRESS_PREFIX)
                || LINE_TEST_ADDRESS.equals(address));
    }
}
