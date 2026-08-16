package io.github.venompool888.capsulesms.sms;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.MultimediaMessagePdu;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.PduPersister;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class MmsDownloadReceiver extends BroadcastReceiver {
    static final String ACTION_DOWNLOADED =
            "io.github.venompool888.capsulesms.action.MMS_DOWNLOADED";
    static final String ACTION_RETRY =
            "io.github.venompool888.capsulesms.action.MMS_RETRY";
    static final String EXTRA_TOKEN = "transfer_token";

    @Override public void onReceive(Context context, Intent intent) {
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null || token.isBlank()) return;
        MmsTransferStore store = new MmsTransferStore(context);
        MmsTransferStore.Pending pending = store.get(token);
        if (pending == null) return;
        if (ACTION_RETRY.equals(intent.getAction())) {
            MmsDeliverReceiver.requestDownload(context, pending);
            return;
        }
        if (!ACTION_DOWNLOADED.equals(intent.getAction())) return;
        if (getResultCode() != Activity.RESULT_OK) {
            showFailure(context, pending, getResultCode());
            return;
        }

        File file = new File(context.getCacheDir(), pending.fileName);
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            GenericPdu pdu = new PduParser(data).parse();
            if (!(pdu instanceof MultimediaMessagePdu)) {
                throw new IllegalStateException("下载内容不是有效彩信");
            }
            Uri saved = PduPersister.getPduPersister(context).persist(
                    pdu, Telephony.Mms.Inbox.CONTENT_URI, true, true,
                    null, pending.subscriptionId);
            if (saved == null) throw new IllegalStateException("彩信无法写入系统收件箱");
            ContentValues unread = new ContentValues();
            unread.put("read", 0);
            unread.put("seen", 0);
            context.getContentResolver().update(saved, unread, null, null);
            if (pending.notificationUri != null && !pending.notificationUri.isBlank()) {
                context.getContentResolver().delete(Uri.parse(pending.notificationUri), null, null);
            }
            String preview = preview((MultimediaMessagePdu) pdu, pending.subject);
            SmsDeliverReceiver.notifyIncoming(context, pending.sender, preview);
            context.getContentResolver().notifyChange(Telephony.Mms.CONTENT_URI, null);
            store.remove(token);
            cancelTransferNotification(context, token);
        } catch (Exception error) {
            showFailure(context, pending, SmsManager.MMS_ERROR_IO_ERROR);
        } finally {
            if (file.exists()) file.delete();
        }
    }

    private static String preview(MultimediaMessagePdu pdu, String subject) {
        StringBuilder text = new StringBuilder();
        if (subject != null && !subject.isBlank()) text.append(subject);
        PduBody body = pdu.getBody();
        int attachmentCount = 0;
        if (body != null) for (int i = 0; i < body.getPartsNum(); i++) {
            PduPart part = body.getPart(i);
            String mime = part.getContentType() == null ? ""
                    : new String(part.getContentType(), StandardCharsets.US_ASCII);
            if ("application/smil".equalsIgnoreCase(mime)) continue;
            if (mime.startsWith("text/") && part.getData() != null) {
                String value = new String(part.getData(), StandardCharsets.UTF_8).trim();
                if (!value.isBlank() && !text.toString().contains(value)) {
                    if (text.length() > 0) text.append(" · ");
                    text.append(value);
                }
            } else attachmentCount++;
        }
        if (attachmentCount > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(attachmentCount).append(" 个附件");
        }
        return text.length() == 0 ? "收到一条彩信" : text.toString();
    }

    private static void showFailure(Context context, MmsTransferStore.Pending pending, int code) {
        String channelId = "mms_transfer";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                channelId, "彩信收发", NotificationManager.IMPORTANCE_DEFAULT));
        Intent retry = new Intent(context, MmsDownloadReceiver.class)
                .setAction(ACTION_RETRY)
                .setData(Uri.parse("capsulesms://mms-retry/" + pending.token))
                .putExtra(EXTRA_TOKEN, pending.token);
        PendingIntent retryIntent = PendingIntent.getBroadcast(context, pending.token.hashCode(), retry,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(io.github.venompool888.capsulesms.R.drawable.ic_notification)
                .setContentTitle("彩信下载失败")
                .setContentText(errorLabel(code))
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_popup_sync, "重试", retryIntent).build())
                .setAutoCancel(true)
                .build();
        manager.notify(transferNotificationId(pending.token), notification);
    }

    private static String errorLabel(int code) {
        if (code == SmsManager.MMS_ERROR_NO_DATA_NETWORK) return "没有可用的移动数据网络";
        if (code == SmsManager.MMS_ERROR_INVALID_APN) return "运营商 MMS APN 配置无效";
        if (code == SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER) return "运营商已停用 MMS";
        if (code == SmsManager.MMS_ERROR_DATA_DISABLED) return "移动数据已关闭";
        if (code == SmsManager.MMS_ERROR_RETRY) return "网络暂时不可用，请重试";
        return "下载失败（" + code + "）";
    }

    static void cancelTransferNotification(Context context, String token) {
        context.getSystemService(NotificationManager.class)
                .cancel(transferNotificationId(token));
    }

    private static int transferNotificationId(String token) {
        return 0x4d4d0000 ^ token.hashCode();
    }
}
