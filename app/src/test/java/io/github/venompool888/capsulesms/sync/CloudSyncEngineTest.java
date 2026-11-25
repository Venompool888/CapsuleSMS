package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CloudSyncEngineTest {
    @Test public void recordsBadEnvelopeBeforeCompletionAndAppliesNextDelivery() throws Exception {
        List<String> steps = new ArrayList<>();

        assertFalse(CloudSyncEngine.completeDelivery(
                () -> { throw new CloudCrypto.InvalidEnvelopeException("bad envelope"); },
                steps::add, () -> steps.add("complete bad delivery")));
        assertTrue(CloudSyncEngine.completeDelivery(
                () -> steps.add("apply good delivery"),
                steps::add, () -> steps.add("complete good delivery")));

        assertEquals(List.of("bad envelope", "complete bad delivery", "apply good delivery",
                "complete good delivery"), steps);
    }

    @Test public void propagatesHttpAckFailureAfterRecordingBadEnvelope() {
        List<String> steps = new ArrayList<>();
        CloudSyncApi.ApiException unavailable = new CloudSyncApi.ApiException(503, "", "unavailable");

        assertSame(unavailable, assertThrows(CloudSyncApi.ApiException.class,
                () -> CloudSyncEngine.completeDelivery(
                        () -> { throw new CloudCrypto.InvalidEnvelopeException("bad envelope"); },
                        steps::add,
                        () -> {
                            steps.add("mark processed");
                            throw unavailable;
                        })));

        assertEquals(List.of("bad envelope", "mark processed"), steps);
    }

    @Test public void leavesDeliveryPendingWhenApplicationFails() {
        List<String> steps = new ArrayList<>();
        IOException unavailable = new IOException("local store unavailable");

        assertSame(unavailable, assertThrows(IOException.class,
                () -> CloudSyncEngine.completeDelivery(
                        () -> { throw unavailable; }, steps::add, () -> steps.add("complete delivery"))));

        assertTrue(steps.isEmpty());
    }

    @Test public void remoteSendIsOnlyCarriedOutShortlyAfterItWasRequested() {
        long now = 1_800_000_000_000L;
        assertFalse(CloudSyncEngine.isStaleRemoteSend(now - 60_000L, now));
        assertFalse(CloudSyncEngine.isStaleRemoteSend(now + 60_000L, now));
        assertTrue(CloudSyncEngine.isStaleRemoteSend(
                now - CloudSyncEngine.REMOTE_SEND_MAX_AGE_MS - 1, now));
        assertTrue(CloudSyncEngine.isStaleRemoteSend(0L, now));
    }

    @Test public void retryLaterLeavesDeliveryUnacknowledged() {
        List<String> steps = new ArrayList<>();

        assertThrows(CloudSyncEngine.RetryLaterException.class,
                () -> CloudSyncEngine.completeDelivery(
                        () -> { throw new CloudSyncEngine.RetryLaterException("store busy"); },
                        steps::add, () -> steps.add("complete delivery")));

        assertTrue(steps.isEmpty());
    }
}
