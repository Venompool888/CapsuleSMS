package io.github.venompool888.capsulesms.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CloudSyncBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        CloudSyncScheduler.schedulePeriodic(context);
        CloudSyncScheduler.scheduleImmediate(context);
        CloudSyncScheduler.startRealtime(context);
    }
}
