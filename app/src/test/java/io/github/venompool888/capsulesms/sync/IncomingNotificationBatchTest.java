package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class IncomingNotificationBatchTest {
    @Test public void keepsOnlyTheNewestMessagePerConversation() {
        IncomingNotificationBatch batch = new IncomingNotificationBatch();
        batch.add("CloudSyncA", "a1", "Mock");
        batch.add("CloudSyncB", "b1", "Mock");
        batch.add("CloudSyncA", "a2", "Mock · SIM 1");

        List<IncomingNotificationBatch.Entry> entries = batch.drain();

        assertEquals(2, entries.size());
        assertEquals("CloudSyncB", entries.get(0).address);
        assertEquals("b1", entries.get(0).body);
        assertEquals("CloudSyncA", entries.get(1).address);
        assertEquals("a2", entries.get(1).body);
        assertEquals("Mock · SIM 1", entries.get(1).label);
        assertTrue(batch.drain().isEmpty());
    }

    @Test public void processedListKeepsTheNewestIds() {
        Set<String> processed = new LinkedHashSet<>();
        for (int i = 0; i < CloudSyncEngine.MAX_PROCESSED + 5; i++) {
            CloudSyncEngine.addProcessed(processed, "event-" + i);
        }
        assertEquals(CloudSyncEngine.MAX_PROCESSED, processed.size());
        assertTrue(processed.contains("event-" + (CloudSyncEngine.MAX_PROCESSED + 4)));
        assertTrue(!processed.contains("event-0"));

        CloudSyncEngine.addProcessed(processed, "event-5");
        CloudSyncEngine.addProcessed(processed, "event-new");
        assertTrue("re-added id moves to the newest end", processed.contains("event-5"));
    }
}
