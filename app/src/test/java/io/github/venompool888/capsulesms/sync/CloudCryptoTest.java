package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CloudCryptoTest {
    @Test
    public void wrappingKeyKeepsTheProtocolDerivation() throws Exception {
        // SHA-256("capsule-wrap-v1\n" + shared + "\n" + eventId + "\n" + targetId). FluidCapsule
        // derives the same key, so this must not change when the shared secret is cached.
        byte[] shared = new byte[32];
        for (int i = 0; i < shared.length; i++) shared[i] = (byte) i;
        byte[] key = CloudCrypto.wrappingKey(shared, "evt-1", "device-b").getEncoded();
        assertEquals("c61eea940f1bcbba2fdbc55fe309133a3a320d7c48c54dec43ffa43204e3d62a",
                hex(key));
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
