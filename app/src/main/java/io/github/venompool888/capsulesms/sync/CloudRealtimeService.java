package io.github.venompool888.capsulesms.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import io.github.venompool888.capsulesms.MainActivity;
import io.github.venompool888.capsulesms.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains an authenticated long-poll so self-hosted servers can wake this device in seconds. */
public final class CloudRealtimeService extends Service {
    private static final String CHANNEL_ID = "cloud_sync_connection";
    private static final int NOTIFICATION_ID = 0xCA553;
    private static final int WAIT_SECONDS = 25;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("云同步已连接"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new CloudSyncConfig(this).isConnected()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) executor.execute(this::runLoop);
        return START_STICKY;
    }

    private void runLoop() {
        long retryMs = 1_000L;
        try {
            while (!Thread.currentThread().isInterrupted()
                    && new CloudSyncConfig(this).isConnected()) {
                CloudSyncConfig config = new CloudSyncConfig(this);
                try {
                    CloudSyncEngine.flushOutbox(this);
                    CloudSyncEngine.pull(this);
                    retryMs = 1_000L;
                    if (CloudSyncApi.waitForMessages(config, WAIT_SECONDS)) {
                        CloudSyncEngine.pull(this);
                    }
                } catch (CloudSyncApi.ApiException error) {
                    if (error.status == 401 || error.status == 403) {
                        stopSelf();
                        return;
                    }
                    updateNotification("云同步重连中");
                    if (!sleep(retryMs)) return;
                    retryMs = Math.min(15_000L, retryMs * 2L);
                } catch (Exception error) {
                    updateNotification("云同步重连中");
                    if (!sleep(retryMs)) return;
                    retryMs = Math.min(15_000L, retryMs * 2L);
                }
                updateNotification("云同步已连接");
            }
        } finally {
            running.set(false);
        }
    }

    private boolean sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "云同步连接", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("保持设备间短信秒级同步");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("胶囊短信")
                .setContentText(status)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String status) {
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(status));
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        running.set(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
