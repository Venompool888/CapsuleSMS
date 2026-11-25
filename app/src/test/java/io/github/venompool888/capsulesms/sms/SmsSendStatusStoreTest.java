package io.github.venompool888.capsulesms.sms;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SmsSendStatusStoreTest {
    @Test public void entriesExpireOnlyAfterAWeekUntouched() {
        long now = 1_800_000_000_000L;
        assertFalse(SmsSendStatusStore.isStale(now, now));
        assertFalse(SmsSendStatusStore.isStale(now - SmsSendStatusStore.MAX_ENTRY_AGE_MS, now));
        assertTrue(SmsSendStatusStore.isStale(now - SmsSendStatusStore.MAX_ENTRY_AGE_MS - 1, now));
    }
}
