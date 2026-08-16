package io.github.venompool888.capsulesms.sms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.provider.Telephony;

import java.util.ArrayList;

public final class SmsSender {
    public interface Callback {
        void onResult(boolean success, String message);
    }

    public static boolean send(
            Context context, String address, String body, int subscriptionId, Callback callback) {
        SmsManager manager = context.getSystemService(SmsManager.class);
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            manager = manager.createForSubscriptionId(subscriptionId);
        }
        ArrayList<String> parts = manager.divideMessage(body);
        long timestamp = System.currentTimeMillis();
        long messageId = SmsRepository.queueOutgoing(
                context, address, body, timestamp, subscriptionId);
        if (messageId < 0) {
            callback.onResult(false, "无法写入发件箱");
            return false;
        }
        try {
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(statusIntent(context, SmsStatusReceiver.ACTION_SENT,
                        messageId, i, parts.size()));
                deliveredIntents.add(statusIntent(context, SmsStatusReceiver.ACTION_DELIVERED,
                        messageId, i, parts.size()));
            }
            if (parts.size() == 1) {
                manager.sendTextMessage(address, null, body,
                        sentIntents.get(0), deliveredIntents.get(0));
            } else {
                manager.sendMultipartTextMessage(
                        address, null, parts, sentIntents, deliveredIntents);
            }
            callback.onResult(true, "正在发送");
            return true;
        } catch (RuntimeException error) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
            values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED);
            context.getContentResolver().update(
                    android.net.Uri.withAppendedPath(Telephony.Sms.CONTENT_URI,
                            Long.toString(messageId)), values, null, null);
            callback.onResult(false, "发送失败（" + error.getMessage() + "）");
            return false;
        }
    }

    private static PendingIntent statusIntent(
            Context context, String action, long messageId, int part, int count) {
        Intent intent = new Intent(context, SmsStatusReceiver.class)
                .setAction(action)
                .setData(android.net.Uri.parse("capsulesms://status/" + action.substring(action.lastIndexOf('.') + 1)
                        + "/" + messageId + "/" + part))
                .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
                .putExtra(SmsStatusReceiver.EXTRA_PART_INDEX, part)
                .putExtra(SmsStatusReceiver.EXTRA_PART_COUNT, count);
        int requestCode = java.util.Objects.hash(action, messageId, part);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private SmsSender() {}
}
