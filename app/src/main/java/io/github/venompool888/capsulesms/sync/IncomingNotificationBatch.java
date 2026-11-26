package io.github.venompool888.capsulesms.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New-SMS notifications collected during one pull. Each conversation has one notification
 * slot, so posting every message of a backlog only replaces the same notification again and
 * again (and Android rate-limits the rest). Keep the newest message per address instead and
 * post once per conversation at the end, in the order the conversations last changed.
 */
final class IncomingNotificationBatch {
    static final class Entry {
        final String address;
        final String body;
        final String label;

        Entry(String address, String body, String label) {
            this.address = address;
            this.body = body;
            this.label = label;
        }
    }

    private final Map<String, Entry> latest = new LinkedHashMap<>();

    void add(String address, String body, String label) {
        latest.remove(address);
        latest.put(address, new Entry(address, body, label));
    }

    /** Returns the pending notifications, oldest conversation first, and empties the batch. */
    List<Entry> drain() {
        List<Entry> entries = new ArrayList<>(latest.values());
        latest.clear();
        return entries;
    }
}
