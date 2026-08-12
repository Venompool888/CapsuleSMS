package io.github.venompool888.capsulesms.sms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public final class TrashCleanupScheduler {
    private static final long DAY = 24L * 60L * 60L * 1000L;

    public static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent cleanup = PendingIntent.getBroadcast(context, 301,
                new Intent(context, TrashCleanupReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + DAY, DAY, cleanup);
    }

    private TrashCleanupScheduler() {}
}
