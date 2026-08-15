package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CloudSyncApiTest {
    @Test public void normalizeServerAcceptsHttpAndRemovesTrailingSlash() {
        assertEquals("https://example.com:8444",
                CloudSyncApi.normalizeServer(" https://example.com:8444/// "));
        assertEquals("http://100.64.0.1", CloudSyncApi.normalizeServer("http://100.64.0.1/"));
    }

    @Test public void normalizeServerRejectsCredentialsAndQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudSyncApi.normalizeServer("https://user@example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> CloudSyncApi.normalizeServer("https://example.com/?token=secret"));
    }
}
