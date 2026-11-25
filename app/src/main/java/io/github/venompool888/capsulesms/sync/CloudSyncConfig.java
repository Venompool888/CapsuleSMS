package io.github.venompool888.capsulesms.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CloudSyncConfig {
    private static final String PREFS = "cloud_sync_config";
    private static final String TOKEN_KEY_ALIAS = "capsule_cloud_token_v1";

    private final SharedPreferences prefs;

    public CloudSyncConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConnected() {
        return !server().isBlank() && !deviceId().isBlank() && !token().isBlank();
    }

    public String server() { return prefs.getString("server", ""); }
    public String deviceId() { return prefs.getString("device_id", ""); }
    public String account() { return prefs.getString("account", ""); }

    /** Errors are kept per channel so a clean upload doesn't hide a pull problem, or the reverse. */
    static final String UPLOAD = "upload";
    static final String PULL = "pull";

    /** The most recent error that a later clean run of the same channel hasn't cleared. */
    String lastError() {
        return newestError(
                prefs.getString("last_error_" + UPLOAD, ""), prefs.getLong("last_error_at_" + UPLOAD, 0),
                prefs.getString("last_error_" + PULL, ""), prefs.getLong("last_error_at_" + PULL, 0));
    }

    void recordError(String channel, String message) {
        prefs.edit()
                .putString("last_error_" + channel, message)
                .putLong("last_error_at_" + channel, System.currentTimeMillis())
                .apply();
    }

    /** Clears the channel's error unless it was recorded at or after {@code runStartedAt}. */
    void clearErrorIfNoneSince(String channel, long runStartedAt) {
        if (prefs.getLong("last_error_at_" + channel, 0) >= runStartedAt) return;
        prefs.edit()
                .remove("last_error_" + channel)
                .remove("last_error_at_" + channel)
                // Written by builds before errors were split per channel; it was never cleared.
                .remove("last_error")
                .apply();
    }

    static String newestError(String first, long firstAt, String second, long secondAt) {
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return secondAt > firstAt ? second : first;
    }

    public String token() {
        String encoded = prefs.getString("token_ciphertext", "");
        String iv = prefs.getString("token_iv", "");
        if (encoded.isBlank() || iv.isBlank()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, tokenKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        }
    }

    public void storeConnection(String server, String deviceId, String token, String account) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, tokenKey());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString("server", server)
                    .putString("device_id", deviceId)
                    .putString("account", account)
                    .putString("token_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("token_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException("无法安全保存设备令牌", error);
        }
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private SecretKey tokenKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(TOKEN_KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(TOKEN_KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
