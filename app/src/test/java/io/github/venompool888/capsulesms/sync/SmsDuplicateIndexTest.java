package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SmsDuplicateIndexTest {
    private static final int INBOX = 1;
    private static final int SENT = 2;
    private static final long T = 1_700_000_000_000L;

    /** A fake provider that counts queries. */
    private static final class FakeProvider implements SmsDuplicateIndex.Loader {
        final List<String> addresses = new ArrayList<>();
        final List<SmsDuplicateIndex.Row> rows = new ArrayList<>();
        int queries;

        void put(String address, long id, String body, int type, long date) {
            addresses.add(address);
            rows.add(new SmsDuplicateIndex.Row(id, body, type, date));
        }

        @Override public List<SmsDuplicateIndex.Row> load(String address, long from, long to) {
            queries++;
            List<SmsDuplicateIndex.Row> out = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                SmsDuplicateIndex.Row row = rows.get(i);
                if (addresses.get(i).equals(address) && row.date >= from && row.date <= to) out.add(row);
            }
            return out;
        }
    }

    @Test public void matchesLikeTheProviderQuery() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.put("CloudSyncA", 1, "hello", INBOX, T - 1_500);
        provider.put("CloudSyncA", 2, "hello", INBOX, T + 1_000);
        provider.put("CloudSyncA", 3, "hello", SENT, T);
        provider.put("CloudSyncA", 4, "hello", INBOX, T + 3_000);
        provider.put("CloudSyncB", 5, "hello", INBOX, T);
        SmsDuplicateIndex index = new SmsDuplicateIndex(provider);

        assertEquals("latest date within two seconds", 2, index.find("CloudSyncA", "hello", INBOX, T));
        assertEquals(3, index.find("CloudSyncA", "hello", SENT, T));
        assertEquals(-1, index.find("CloudSyncA", "Hello", INBOX, T));
        assertEquals(-1, index.find("CloudSyncA", "hello", INBOX, T - 10_000));
        assertEquals(5, index.find("CloudSyncB", "hello", INBOX, T + 2_000));
        assertEquals(-1, index.find("CloudSyncC", "hello", INBOX, T));
    }

    @Test public void oneQueryPerConversationWithinAnHour() throws Exception {
        FakeProvider provider = new FakeProvider();
        SmsDuplicateIndex index = new SmsDuplicateIndex(provider);
        for (int i = 0; i < 50; i++) {
            index.find("CloudSyncA", "m" + i, INBOX, T + i * 1_000L);
            index.find("CloudSyncB", "m" + i, INBOX, T + i * 1_000L);
        }
        assertEquals(2, provider.queries);
        index.find("CloudSyncA", "late", INBOX, T + 2 * SmsDuplicateIndex.WINDOW_MS);
        assertEquals(3, provider.queries);
    }

    @Test public void rowsAddedDuringThePullAreFound() throws Exception {
        FakeProvider provider = new FakeProvider();
        SmsDuplicateIndex index = new SmsDuplicateIndex(provider);
        assertFalse(index.takeAdded());
        assertEquals(-1, index.find("CloudSyncA", "again", INBOX, T));
        index.add("CloudSyncA", "again", INBOX, T, 42);
        assertTrue(index.takeAdded());
        assertFalse(index.takeAdded());
        assertEquals(42, index.find("CloudSyncA", "again", INBOX, T + 500));
    }

    @Test public void aRowLoadedTwiceIsKeptOnce() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.put("CloudSyncA", 7, "x", INBOX, T);
        SmsDuplicateIndex index = new SmsDuplicateIndex(provider);
        index.add("CloudSyncA", "x", INBOX, T, 7);
        assertEquals(7, index.find("CloudSyncA", "x", INBOX, T));
        assertEquals(1, provider.queries);
    }
}
