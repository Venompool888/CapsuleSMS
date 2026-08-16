package io.github.venompool888.capsulesms.sms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.database.Cursor;

import io.github.venompool888.capsulesms.sync.CloudSyncEngine;

/** Persists per-part SMS sent and delivery callbacks even if the UI process is recreated. */
public final class SmsStatusReceiver extends BroadcastReceiver {
    public static final String ACTION_SENT =
            "io.github.venompool888.capsulesms.action.SMS_SENT";
    public static final String ACTION_DELIVERED =
            "io.github.venompool888.capsulesms.action.SMS_DELIVERED";
    static final String EXTRA_MESSAGE_ID = "message_id";
    static final String EXTRA_PART_INDEX = "part_index";
    static final String EXTRA_PART_COUNT = "part_count";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_SENT.equals(action) && !ACTION_DELIVERED.equals(action)) return;
        long messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L);
        int part = intent.getIntExtra(EXTRA_PART_INDEX, 0);
        int count = Math.max(1, intent.getIntExtra(EXTRA_PART_COUNT, 1));
        if (messageId < 0 || part < 0 || part >= count) return;

        SmsSendStatusStore store = new SmsSendStatusStore(context);
        if (ACTION_SENT.equals(action)) {
            boolean success = getResultCode() == Activity.RESULT_OK;
            SmsSendStatusStore.Aggregate aggregate = store.recordSent(
                    messageId, part, count, success, getResultCode());
            if (aggregate.complete) {
                ContentValues values = new ContentValues();
                values.put(Telephony.Sms.TYPE, aggregate.success
                        ? Telephony.Sms.MESSAGE_TYPE_SENT : Telephony.Sms.MESSAGE_TYPE_FAILED);
                values.put(Telephony.Sms.STATUS, aggregate.success
                        ? Telephony.Sms.STATUS_PENDING : Telephony.Sms.STATUS_FAILED);
                values.put(Telephony.Sms.ERROR_CODE, aggregate.success ? 0 : aggregate.errorCode);
                update(context, messageId, values);
                if (aggregate.success && store.claimUpload(messageId)) {
                    uploadSent(context, messageId);
                }
            }
        } else {
            boolean success = getResultCode() == Activity.RESULT_OK;
            SmsSendStatusStore.Aggregate aggregate = store.recordDelivered(
                    messageId, part, count, success, getResultCode());
            if (aggregate.complete) {
                ContentValues values = new ContentValues();
                values.put(Telephony.Sms.STATUS, aggregate.success
                        ? Telephony.Sms.STATUS_COMPLETE : Telephony.Sms.STATUS_FAILED);
                if (!aggregate.success) values.put(Telephony.Sms.ERROR_CODE, aggregate.errorCode);
                update(context, messageId, values);
                store.clear(messageId);
            }
        }
    }

    private static void update(Context context, long messageId, ContentValues values) {
        Uri uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, Long.toString(messageId));
        context.getContentResolver().update(uri, values, null, null);
        context.getContentResolver().notifyChange(Telephony.Sms.CONTENT_URI, null);
    }

    private static void uploadSent(Context context, long messageId) {
        Uri uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, Long.toString(messageId));
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{"address", "body", "date", "sub_id"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            CloudSyncEngine.uploadAsync(context, cursor.getString(0), cursor.getString(1),
                    cursor.getLong(2), cursor.getInt(3), Telephony.Sms.MESSAGE_TYPE_SENT);
        }
    }
}
