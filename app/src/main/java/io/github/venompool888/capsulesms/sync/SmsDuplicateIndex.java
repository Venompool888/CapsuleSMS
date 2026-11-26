package io.github.venompool888.capsulesms.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duplicate check for one pull. A backlog arrives as runs of messages from the same
 * conversation, so instead of one SMS provider query per message, this loads an hour of one
 * conversation at a time and answers the rest from memory. Matching is the provider query's:
 * same address, body and type, date within two seconds, latest date wins.
 */
final class SmsDuplicateIndex {
    static final long TOLERANCE_MS = 2_000L;
    static final long WINDOW_MS = 60L * 60L * 1_000L;

    static final class Row {
        final long id;
        final String body;
        final int type;
        final long date;

        Row(long id, String body, int type, long date) {
            this.id = id;
            this.body = body;
            this.type = type;
            this.date = date;
        }
    }

    interface Loader {
        /** Rows with exactly this address and {@code from <= date <= to}. */
        List<Row> load(String address, long from, long to) throws Exception;
    }

    private final Loader loader;
    private final Map<String, List<long[]>> loadedRanges = new HashMap<>();
    private final Map<String, List<Row>> rowsByAddress = new HashMap<>();
    private final Set<Long> knownIds = new HashSet<>();
    private boolean added;

    SmsDuplicateIndex(Loader loader) {
        this.loader = loader;
    }

    long find(String address, String body, int type, long timestamp) throws Exception {
        long from = Math.max(0L, timestamp - TOLERANCE_MS);
        long to = timestamp + TOLERANCE_MS;
        if (!covered(address, from, to)) {
            long loadFrom = Math.max(0L, timestamp - WINDOW_MS);
            long loadTo = timestamp + WINDOW_MS;
            for (Row row : loader.load(address, loadFrom, loadTo)) remember(address, row);
            loadedRanges.computeIfAbsent(address, key -> new ArrayList<>())
                    .add(new long[]{loadFrom, loadTo});
        }
        long bestId = -1L;
        long bestDate = Long.MIN_VALUE;
        List<Row> rows = rowsByAddress.get(address);
        if (rows == null) return bestId;
        for (Row row : rows) {
            if (row.type == type && row.date >= from && row.date <= to
                    && row.date > bestDate && row.body.equals(body)) {
                bestId = row.id;
                bestDate = row.date;
            }
        }
        return bestId;
    }

    /** Records a row this pull inserted, so a repeat later in the pull is recognised. */
    void add(String address, String body, int type, long date, long id) {
        remember(address, new Row(id, body, type, date));
        added = true;
    }

    /** Whether rows were added since the last call. */
    boolean takeAdded() {
        boolean value = added;
        added = false;
        return value;
    }

    private boolean covered(String address, long from, long to) {
        List<long[]> ranges = loadedRanges.get(address);
        if (ranges == null) return false;
        for (long[] range : ranges) {
            if (range[0] <= from && to <= range[1]) return true;
        }
        return false;
    }

    private void remember(String address, Row row) {
        if (row.body == null || !knownIds.add(row.id)) return;
        rowsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(row);
    }
}
