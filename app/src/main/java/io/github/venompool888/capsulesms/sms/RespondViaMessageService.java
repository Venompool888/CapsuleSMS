package io.github.venompool888.capsulesms.sms;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SubscriptionManager;

public final class RespondViaMessageService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Uri data = intent.getData();
        String body = intent.getStringExtra(Intent.EXTRA_TEXT);
        String address = data == null ? null : data.getSchemeSpecificPart();
        if (address != null && body != null && !body.isBlank()) {
            SmsSender.send(this, address, body,
                    SubscriptionManager.getDefaultSmsSubscriptionId(),
                    (success, message) -> stopSelf(startId));
        } else {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
