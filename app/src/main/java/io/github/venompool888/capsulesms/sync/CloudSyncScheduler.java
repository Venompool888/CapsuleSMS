package io.github.venompool888.capsulesms.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class CloudSyncScheduler {
    private static final int PERIODIC_JOB_ID = 0xCA551;
    private static final int IMMEDIATE_JOB_ID = 0xCA552;
    private static final long FIFTEEN_MINUTES = 15L * 60L * 1000L;

    public static void schedulePeriodic(Context context) {
        if (!new CloudSyncConfig(context).isConnected()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(
                PERIODIC_JOB_ID, new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(FIFTEEN_MINUTES)
                .build();
        scheduler.schedule(job);
    }

    public static void scheduleImmediate(Context context) {
        if (!new CloudSyncConfig(context).isConnected()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(
                IMMEDIATE_JOB_ID, new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(500L)
                .setOverrideDeadline(5_000L)
                .build();
        scheduler.schedule(job);
    }

    public static void startRealtime(Context context) {
        if (!new CloudSyncConfig(context).isConnected()) return;
        Intent service = new Intent(context, CloudRealtimeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException ignored) {
            // JobScheduler remains the fallback when background FGS start is restricted.
            scheduleImmediate(context);
        }
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(IMMEDIATE_JOB_ID);
        context.stopService(new Intent(context, CloudRealtimeService.class));
    }

    private CloudSyncScheduler() {}
}
