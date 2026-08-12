package io.github.venompool888.capsulesms.sms;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;

public final class SmsSender {
    private static final String ACTION_SENT = "io.github.venompool888.capsulesms.SMS_SENT";

    public interface Callback {
        void onResult(boolean success, String message);
    }

    public static void send(
            Context context, String address, String body, int subscriptionId, Callback callback) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                boolean success = getResultCode() == android.app.Activity.RESULT_OK;
                if (success) SmsRepository.saveSent(
                        context, address, body, System.currentTimeMillis(), subscriptionId);
                try { context.unregisterReceiver(this); } catch (IllegalArgumentException ignored) {}
                callback.onResult(success, success ? "已发送" : "发送失败（" + getResultCode() + "）");
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_SENT), Context.RECEIVER_NOT_EXPORTED);

        Intent sentIntent = new Intent(ACTION_SENT).setPackage(context.getPackageName());
        PendingIntent sent = PendingIntent.getBroadcast(
                context, (int) System.nanoTime(), sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SmsManager manager = context.getSystemService(SmsManager.class);
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            manager = manager.createForSubscriptionId(subscriptionId);
        }
        ArrayList<String> parts = manager.divideMessage(body);
        if (parts.size() == 1) {
            manager.sendTextMessage(address, null, body, sent, null);
        } else {
            ArrayList<PendingIntent> intents = new ArrayList<>();
            for (int i = 0; i < parts.size() - 1; i++) intents.add(null);
            intents.add(sent);
            manager.sendMultipartTextMessage(address, null, parts, intents, null);
        }
    }

    private SmsSender() {}
}
