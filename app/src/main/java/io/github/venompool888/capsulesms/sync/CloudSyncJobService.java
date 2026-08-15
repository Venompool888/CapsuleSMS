package io.github.venompool888.capsulesms.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudSyncJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            boolean retry = false;
            try {
                if (new CloudSyncConfig(this).isConnected()) {
                    CloudSyncEngine.flushOutbox(this);
                    CloudSyncEngine.pull(this);
                }
            } catch (Exception error) {
                retry = true;
            }
            jobFinished(params, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
