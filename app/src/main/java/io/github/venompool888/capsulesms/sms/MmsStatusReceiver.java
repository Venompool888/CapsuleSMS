package io.github.venompool888.capsulesms.sms;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

import com.klinker.android.send_message.MmsSentReceiver;

import java.io.File;

public final class MmsStatusReceiver extends BroadcastReceiver {
    static final String ACTION_SENT =
            "io.github.venompool888.capsulesms.action.MMS_SENT";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_SENT.equals(intent.getAction())) return;
        String rawUri = intent.getStringExtra(MmsSentReceiver.EXTRA_CONTENT_URI);
        if (rawUri == null || rawUri.isBlank()) return;
        Uri messageUri = Uri.parse(rawUri);
        boolean success = getResultCode() == Activity.RESULT_OK;
        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.MESSAGE_BOX, success
                ? Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED);
        values.put("st", success ? 0 : getResultCode());
        context.getContentResolver().update(messageUri, values, null, null);
        context.getContentResolver().notifyChange(Telephony.Mms.CONTENT_URI, null);
        String filePath = intent.getStringExtra(MmsSentReceiver.EXTRA_FILE_PATH);
        if (filePath != null) new File(filePath).delete();
        if (!success) notifyFailure(context, getResultCode());
    }

    private static void notifyFailure(Context context, int code) {
        String channelId = "mms_transfer";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                channelId, "彩信收发", NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(io.github.venompool888.capsulesms.R.drawable.ic_notification)
                .setContentTitle("彩信发送失败")
                .setContentText("请检查移动数据和运营商 MMS 设置（" + code + "）")
                .setAutoCancel(true)
                .build();
        manager.notify(0x4d4d1000 ^ code, notification);
    }
}
