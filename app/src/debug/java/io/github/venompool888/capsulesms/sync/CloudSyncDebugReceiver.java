package io.github.venompool888.capsulesms.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.venompool888.capsulesms.sms.CloudMessageOriginStore;
import io.github.venompool888.capsulesms.sms.SmsRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** ADB-only cloud-sync test controls included in debug builds. */
public final class CloudSyncDebugReceiver extends BroadcastReceiver {
    private static final String CONNECT =
            "io.github.venompool888.capsulesms.debug.CONNECT_CLOUD";
    private static final String ENABLE_ALL =
            "io.github.venompool888.capsulesms.debug.ENABLE_ALL_ROUTES";
    private static final String SYNC =
            "io.github.venompool888.capsulesms.debug.SYNC_CLOUD";
    private static final String CLEAR_PROCESSED =
            "io.github.venompool888.capsulesms.debug.CLEAR_CLOUD_PROCESSED";
    private static final String REMOTE_SEND_TEST =
            "io.github.venompool888.capsulesms.debug.REMOTE_SEND_TEST";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (CLEAR_PROCESSED.equals(action)) {
            context.getSharedPreferences("cloud_sync_processed", Context.MODE_PRIVATE)
                    .edit().clear().apply();
            setResultCode(1);
            return;
        }
        if (!CONNECT.equals(action) && !ENABLE_ALL.equals(action) && !SYNC.equals(action)
                && !REMOTE_SEND_TEST.equals(action)) return;
        PendingResult pending = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                if (REMOTE_SEND_TEST.equals(action)) {
                    String address = intent.getStringExtra("address");
                    String body = intent.getStringExtra("body");
                    CloudMessageOriginStore.Origin origin =
                            new SmsRepository(context).latestCloudOrigin(address);
                    CloudSyncEngine.sendRemote(context, origin, address, body);
                } else if (CONNECT.equals(action)) {
                    CloudSyncEngine.connect(
                            context, intent.getStringExtra("server"), "", "",
                            intent.getStringExtra("code"));
                } else if (ENABLE_ALL.equals(action)) {
                    CloudSyncEngine.DeviceState state = CloudSyncEngine.getDeviceState(context);
                    Set<String> others = new HashSet<>();
                    for (CloudSyncEngine.Device device : state.devices) {
                        if (!device.id.equals(state.currentDeviceId)) others.add(device.id);
                    }
                    CloudSyncEngine.saveRoutes(context, others, others);
                } else {
                    CloudSyncEngine.flushOutbox(context);
                    CloudSyncEngine.pull(context);
                }
                pending.setResultCode(1);
            } catch (Exception error) {
                pending.setResultCode(2);
                pending.setResultData(error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                pending.finish();
                executor.shutdown();
            }
        });
    }
}
