package io.github.venompool888.capsulesms.sync;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class CloudSyncApi {
    private static final int MAX_ENVELOPE_BYTES = 64 * 1024;

    static String normalizeServer(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            if ("http".equalsIgnoreCase(uri.getScheme()) && !isPrivateHost(uri.getHost())) {
                throw new InsecureServerException();
            }
            return value;
        } catch (InsecureServerException error) {
            // Checked before connecting: the server's own 426 would come after the code was sent.
            throw new IllegalArgumentException(
                    "未加密的 HTTP 只能用于本机或私有网络（如 Tailscale IP）。公网服务器请使用 HTTPS 地址。");
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("服务器地址必须是完整的 HTTP(S) 地址");
        }
    }

    private static final class InsecureServerException extends RuntimeException {}

    /** Loopback, LAN, link-local, Tailscale/CGNAT and local-only names. Parsed only, never resolved. */
    static boolean isPrivateHost(String rawHost) {
        String host = rawHost.toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            String v6 = host.substring(1, host.length() - 1);
            return v6.equals("::1") || v6.startsWith("fc") || v6.startsWith("fd")
                    || v6.startsWith("fe8") || v6.startsWith("fe9")
                    || v6.startsWith("fea") || v6.startsWith("feb");
        }
        int[] v4 = parseIpv4(host);
        if (v4 != null) {
            return v4[0] == 127 || v4[0] == 10
                    || (v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31)
                    || (v4[0] == 192 && v4[1] == 168)
                    || (v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127)
                    || (v4[0] == 169 && v4[1] == 254);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("localhost") || !host.contains(".")
                || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan")
                || host.endsWith(".internal") || host.endsWith(".home.arpa") || host.endsWith(".ts.net");
    }

    private static int[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].chars().allMatch(Character::isDigit)) return null;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return null;
        }
        return octets;
    }

    static JSONObject connect(String server, JSONObject body) throws Exception {
        return request(server, "", "POST", "/api/v1/connect", body);
    }

    static JSONObject get(CloudSyncConfig config, String path) throws Exception {
        return request(config.server(), config.token(), "GET", path, null);
    }

    static JSONObject post(CloudSyncConfig config, String path, JSONObject body) throws Exception {
        return request(config.server(), config.token(), "POST", path, body);
    }

    static JSONObject postMessage(CloudSyncConfig config, JSONObject body) throws Exception {
        checkEnvelopeSize(body.getJSONObject("envelope").toString());
        try {
            return post(config, "/api/v1/messages", body);
        } catch (ApiException error) {
            if (!error.isInvalidEnvelope()) throw error;
            throw new RejectedEnvelopeException("服务器拒绝了加密信封（为空或超过 64 KiB）", error);
        }
    }

    static void checkEnvelopeSize(String serializedEnvelope) throws RejectedEnvelopeException {
        // The server measures its reserialized JSON, where Base64 slashes are not escaped.
        int bytes = serializedEnvelope.replace("\\/", "/").getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_ENVELOPE_BYTES) {
            throw new RejectedEnvelopeException("加密信封超过 64 KiB（" + bytes + " 字节）", null);
        }
    }

    static JSONObject put(CloudSyncConfig config, String path, JSONObject body) throws Exception {
        return request(config.server(), config.token(), "PUT", path, body);
    }

    static boolean waitForMessages(CloudSyncConfig config, int timeoutSeconds) throws Exception {
        JSONObject response = request(
                config.server(), config.token(), "GET",
                "/api/v1/messages/wait?timeout=" + timeoutSeconds, null,
                (timeoutSeconds + 10) * 1_000);
        return response.optBoolean("available", false);
    }

    private static JSONObject request(
            String server, String token, String method, String path, JSONObject body) throws Exception {
        return request(server, token, method, path, body, 20_000);
    }

    private static JSONObject request(
            String server, String token, String method, String path, JSONObject body,
            int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CapsuleSMS-Android/0.2");
        if (token != null && !token.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = read(stream);
        connection.disconnect();
        JSONObject json = response.isBlank() ? new JSONObject() : new JSONObject(response);
        if (status < 200 || status >= 300) {
            String message = json.optString("message", "服务器返回 " + status);
            throw new ApiException(status, json.optString("error"), message);
        }
        return json;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    static final class ApiException extends Exception {
        final int status;
        private final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        boolean isInvalidEnvelope() {
            return status == 413 && "invalid_envelope".equals(code);
        }
    }

    static final class RejectedEnvelopeException extends Exception {
        RejectedEnvelopeException(String message, ApiException cause) {
            super(message, cause);
        }
    }

    private CloudSyncApi() {}
}
