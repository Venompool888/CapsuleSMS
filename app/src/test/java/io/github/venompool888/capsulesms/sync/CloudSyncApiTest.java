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

    @Test public void plainHttpIsOnlyAcceptedForLocalAndPrivateHosts() {
        for (String ok : new String[] {
                "http://127.0.0.1:8787", "http://localhost:8787", "http://10.1.2.3",
                "http://172.16.0.9", "http://172.31.255.1", "http://192.168.1.20:8080",
                "http://100.64.0.1", "http://100.127.255.254", "http://169.254.10.10",
                "http://[::1]:8787", "http://[fd7a:115c:a1e0::1]", "http://myserver",
                "http://nas.local", "http://box.lan", "http://sms.home.arpa",
                "http://server.tail1234.ts.net:8787", "http://LOCALHOST"}) {
            assertEquals(ok, CloudSyncApi.normalizeServer(ok));
        }
        for (String bad : new String[] {
                "http://example.com", "http://8.8.8.8", "http://172.32.0.1", "http://100.128.0.1",
                "http://192.169.1.1", "http://[2001:db8::1]", "http://sms.example.ts.net.evil.com"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CloudSyncApi.normalizeServer(bad));
            assertTrue(bad, error.getMessage().contains("HTTPS"));
        }
        assertEquals("https://example.com", CloudSyncApi.normalizeServer("https://example.com"));
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
