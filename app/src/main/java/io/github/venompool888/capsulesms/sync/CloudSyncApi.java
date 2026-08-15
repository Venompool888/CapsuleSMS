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
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("服务器地址必须是完整的 HTTP(S) 地址");
        }
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
            throw new ApiException(status, message);
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
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private CloudSyncApi() {}
}
