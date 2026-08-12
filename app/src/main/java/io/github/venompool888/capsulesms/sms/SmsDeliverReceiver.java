package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

import io.github.venompool888.capsulesms.MainActivity;
import io.github.venompool888.capsulesms.R;

public final class SmsDeliverReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;
        StringBuilder body = new StringBuilder();
        String address = messages[0].getDisplayOriginatingAddress();
        long timestamp = messages[0].getTimestampMillis();
        for (SmsMessage message : messages) body.append(message.getDisplayMessageBody());
        address = address == null ? "未知号码" : address;
        int subscriptionId = intent.getIntExtra(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        SmsRepository.saveIncoming(context, address, body.toString(), timestamp, subscriptionId);
        notifyIncoming(context, address, body.toString());
    }

    private void notifyIncoming(Context context, String address, String body) {
        String channelId = "incoming_sms";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                channelId, "新短信", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.parse("smsto:" + Uri.encode(address)));
        PendingIntent content = PendingIntent.getActivity(
                context, address.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(address)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        manager.notify(address.hashCode(), notification);
    }
}
