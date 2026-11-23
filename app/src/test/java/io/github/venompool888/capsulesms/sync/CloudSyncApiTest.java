package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CloudSyncApiTest {
    private static final int CIPHERTEXT_LENGTH_AT_LIMIT = 65_536 - "{\"ciphertext\":\"\"}".length();

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

    @Test public void acceptsEnvelopeUpToAndIncluding64KiB() throws Exception {
        CloudSyncApi.checkEnvelopeSize(envelope("A".repeat(CIPHERTEXT_LENGTH_AT_LIMIT - 1)));
        CloudSyncApi.checkEnvelopeSize(envelope("A".repeat(CIPHERTEXT_LENGTH_AT_LIMIT)));
    }

    @Test public void rejectsEnvelopeOneByteOver64KiB() {
        CloudSyncApi.RejectedEnvelopeException error = assertThrows(
                CloudSyncApi.RejectedEnvelopeException.class,
                () -> CloudSyncApi.checkEnvelopeSize(envelope("A".repeat(CIPHERTEXT_LENGTH_AT_LIMIT + 1))));
        assertEquals("加密信封超过 64 KiB（65537 字节）", error.getMessage());
    }

    @Test public void measuresUtf8BytesIncludingJsonOverhead() {
        String serialized = envelope("A".repeat(CIPHERTEXT_LENGTH_AT_LIMIT - 2) + "界");
        assertThrows(CloudSyncApi.RejectedEnvelopeException.class,
                () -> CloudSyncApi.checkEnvelopeSize(serialized));
    }

    @Test public void countsBase64SlashAsServerSerializesIt() throws Exception {
        CloudSyncApi.checkEnvelopeSize(envelope("A".repeat(CIPHERTEXT_LENGTH_AT_LIMIT - 1) + "\\/"));
    }

    @Test public void only413WithInvalidEnvelopeCodeIsPermanent() {
        assertTrue(new CloudSyncApi.ApiException(413, "invalid_envelope", "too large").isInvalidEnvelope());
        assertFalse(new CloudSyncApi.ApiException(413, "", "payload too large").isInvalidEnvelope());
        assertFalse(new CloudSyncApi.ApiException(413, "request_too_large", "too large").isInvalidEnvelope());
        assertFalse(new CloudSyncApi.ApiException(500, "invalid_envelope", "retry").isInvalidEnvelope());
    }

    private static String envelope(String ciphertext) {
        return "{\"ciphertext\":\"" + ciphertext + "\"}";
    }
}
