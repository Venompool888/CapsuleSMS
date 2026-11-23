package io.github.venompool888.capsulesms.sync;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CloudCrypto {
    private static final String KEY_ALIAS = "capsule_cloud_ecdh_v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    static String publicKeyBase64() throws Exception {
        return Base64.encodeToString(keyPair().getPublic().getEncoded(), Base64.NO_WRAP);
    }

    static JSONObject encrypt(
            String eventId, JSONObject payload, List<CloudSyncEngine.Device> targets) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey contentKey = generator.generateKey();
        byte[] payloadIv = randomIv();
        byte[] payloadCiphertext = aesGcm(
                Cipher.ENCRYPT_MODE, contentKey, payloadIv,
                eventId.getBytes(StandardCharsets.UTF_8),
                payload.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject wrappedKeys = new JSONObject();
        for (CloudSyncEngine.Device target : targets) {
            if (target.publicKey == null || target.publicKey.isBlank()) {
                throw new IllegalStateException("设备“" + target.name + "”没有加密公钥，请重新连接该设备");
            }
            SecretKey wrappingKey = deriveWrappingKey(
                    keyPair().getPrivate(), decodePublicKey(target.publicKey), eventId, target.id);
            byte[] wrapIv = randomIv();
            byte[] wrapped = aesGcm(
                    Cipher.ENCRYPT_MODE, wrappingKey, wrapIv,
                    (eventId + "\n" + target.id).getBytes(StandardCharsets.UTF_8),
                    contentKey.getEncoded());
            wrappedKeys.put(target.id, new JSONObject()
                    .put("iv", encode(wrapIv))
                    .put("ciphertext", encode(wrapped)));
        }
        if (wrappedKeys.length() != targets.size()) {
            throw new IllegalStateException("无法为所有目标设备生成加密信封");
        }
        return new JSONObject()
                .put("version", 1)
                .put("algorithm", "ECDH-P256+A256GCM")
                .put("senderPublicKey", publicKeyBase64())
                .put("payloadIv", encode(payloadIv))
                .put("ciphertext", encode(payloadCiphertext))
                .put("wrappedKeys", wrappedKeys);
    }

    static JSONObject decrypt(
            String eventId, String currentDeviceId, String registeredSourceKey,
            JSONObject envelope) throws Exception {
        if (envelope.optInt("version") != 1
                || !"ECDH-P256+A256GCM".equals(envelope.optString("algorithm"))) {
            throw new IllegalArgumentException("不支持的加密信封");
        }
        String senderKey = envelope.getString("senderPublicKey");
        if (!constantTimeEquals(senderKey, registeredSourceKey)) {
            throw new SecurityException("发送设备公钥与服务器记录不一致");
        }
        JSONObject wrapped = envelope.getJSONObject("wrappedKeys").getJSONObject(currentDeviceId);
        SecretKey wrappingKey = deriveWrappingKey(
                keyPair().getPrivate(), decodePublicKey(senderKey), eventId, currentDeviceId);
        byte[] contentKeyBytes = aesGcm(
                Cipher.DECRYPT_MODE, wrappingKey, decode(wrapped.getString("iv")),
                (eventId + "\n" + currentDeviceId).getBytes(StandardCharsets.UTF_8),
                decode(wrapped.getString("ciphertext")));
        if (contentKeyBytes.length != 32) throw new IllegalArgumentException("内容密钥长度无效");
        SecretKey contentKey = new SecretKeySpec(contentKeyBytes, "AES");
        byte[] plaintext = aesGcm(
                Cipher.DECRYPT_MODE, contentKey, decode(envelope.getString("payloadIv")),
                eventId.getBytes(StandardCharsets.UTF_8),
                decode(envelope.getString("ciphertext")));
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    private static SecretKey deriveWrappingKey(
            PrivateKey ownPrivate, PublicKey otherPublic, String eventId, String targetId) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ownPrivate);
        agreement.doPhase(otherPublic, true);
        byte[] shared = agreement.generateSecret();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("capsule-wrap-v1\n".getBytes(StandardCharsets.UTF_8));
        digest.update(shared);
        digest.update(("\n" + eventId + "\n" + targetId).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest.digest(), "AES");
    }

    private static KeyPair keyPair() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS).getPublicKey();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
            return new KeyPair(publicKey, privateKey);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .build());
        return generator.generateKeyPair();
    }

    private static PublicKey decodePublicKey(String encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP)));
    }

    private static byte[] aesGcm(
            int mode, SecretKey key, byte[] iv, byte[] aad, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] randomIv() {
        byte[] value = new byte[12];
        RANDOM.nextBytes(value);
        return value;
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private CloudCrypto() {}
}
