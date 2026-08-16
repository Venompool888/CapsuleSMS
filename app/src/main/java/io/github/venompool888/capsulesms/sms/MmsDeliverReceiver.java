package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MmsDeliverReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) return;
        byte[] data = intent.getByteArrayExtra("data");
        if (data == null || data.length == 0) return;
        try {
            GenericPdu pdu = new PduParser(data).parse();
            if (!(pdu instanceof NotificationInd)
                    || pdu.getMessageType() != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) return;
            NotificationInd notification = (NotificationInd) pdu;
            String location = bytes(notification.getContentLocation());
            if (location.isBlank()) return;
            int subscriptionId = intent.getIntExtra(
                    SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            String sender = notification.getFrom() == null ? "未知号码"
                    : notification.getFrom().getString();
            String subject = notification.getSubject() == null ? ""
                    : notification.getSubject().getString();
            Uri notificationUri = null;
            try {
                notificationUri = PduPersister.getPduPersister(context).persist(
                        notification, Telephony.Mms.Inbox.CONTENT_URI, true, true,
                        null, subscriptionId);
            } catch (Exception ignored) {}
            String token = UUID.randomUUID().toString();
            String fileName = "mms-download-" + token + ".pdu";
            MmsTransferStore.Pending pending = new MmsTransferStore.Pending(
                    token, location, fileName,
                    notificationUri == null ? null : notificationUri.toString(),
                    sender, subject, subscriptionId, 0);
            new MmsTransferStore(context).put(pending);
            requestDownload(context, pending);
        } catch (RuntimeException ignored) {}
    }

    static void requestDownload(Context context, MmsTransferStore.Pending previous) {
        String fileName = "mms-download-" + previous.token + "-"
                + (previous.attempts + 1) + ".pdu";
        MmsTransferStore.Pending pending = previous.nextAttempt(fileName);
        new MmsTransferStore(context).put(pending);
        File file = new File(context.getCacheDir(), fileName);
        if (file.exists()) file.delete();
        Uri destination = new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .build();
        Intent callback = new Intent(context, MmsDownloadReceiver.class)
                .setAction(MmsDownloadReceiver.ACTION_DOWNLOADED)
                .setData(Uri.parse("capsulesms://mms-download/" + pending.token
                        + "/" + pending.attempts))
                .putExtra(MmsDownloadReceiver.EXTRA_TOKEN, pending.token);
        PendingIntent result = PendingIntent.getBroadcast(context,
                java.util.Objects.hash(pending.token, pending.attempts), callback,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SmsManager manager = context.getSystemService(SmsManager.class);
        if (pending.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            manager = manager.createForSubscriptionId(pending.subscriptionId);
        }
        try {
            manager.downloadMultimediaMessage(context, pending.contentLocation,
                    destination, null, result, pending.token.hashCode());
            MmsDownloadReceiver.cancelTransferNotification(context, pending.token);
        } catch (RuntimeException error) {
            try { result.send(SmsManager.MMS_ERROR_IO_ERROR); }
            catch (PendingIntent.CanceledException ignored) {}
        }
    }

    private static String bytes(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.ISO_8859_1);
    }
}
