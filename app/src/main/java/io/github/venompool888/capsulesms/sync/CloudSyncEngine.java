package io.github.venompool888.capsulesms.sync;

import android.Manifest;
import android.content.Context;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import io.github.venompool888.capsulesms.sms.SmsDeliverReceiver;
import io.github.venompool888.capsulesms.sms.CloudMessageOriginStore;
import io.github.venompool888.capsulesms.sms.SmsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudSyncEngine {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final String PROCESSED_PREFS = "cloud_sync_processed";
    private static final String PROCESSED_KEY = "event_ids";

    public interface ResultCallback<T> {
        void onResult(T value, Exception error);
    }

    public static final class Device {
        public final String id;
        public final String name;
        public final String platform;
        public final String publicKey;
        public final long lastSeenAt;

        Device(String id, String name, String platform, String publicKey, long lastSeenAt) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.publicKey = publicKey;
            this.lastSeenAt = lastSeenAt;
        }
    }

    public static final class DeviceState {
        public final String currentDeviceId;
        public final String account;
        public final List<Device> devices;
        public final Set<String> pushTo;
        public final Set<String> receiveFrom;

        DeviceState(
                String currentDeviceId, String account, List<Device> devices,
                Set<String> pushTo, Set<String> receiveFrom) {
            this.currentDeviceId = currentDeviceId;
            this.account = account;
            this.devices = devices;
            this.pushTo = pushTo;
            this.receiveFrom = receiveFrom;
        }
    }

    public static DeviceState connect(
            Context context, String rawServer, String username, String password,
            String enrollmentCode) throws Exception {
        String server = CloudSyncApi.normalizeServer(rawServer);
        JSONObject request = new JSONObject()
                .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                .put("platform", "android-" + Build.VERSION.RELEASE)
                .put("publicKey", CloudCrypto.publicKeyBase64());
        if (enrollmentCode != null && !enrollmentCode.isBlank()) {
            request.put("enrollmentCode", enrollmentCode.trim().toUpperCase(Locale.ROOT));
        } else {
            request.put("username", username == null ? "" : username.trim());
            request.put("password", password == null ? "" : password);
        }
        JSONObject response = CloudSyncApi.connect(server, request);
        CloudSyncConfig config = new CloudSyncConfig(context);
        config.storeConnection(
                server, response.getString("deviceId"), response.getString("deviceToken"),
                response.optString("account", username));
        CloudSyncScheduler.schedulePeriodic(context);
        CloudSyncScheduler.scheduleImmediate(context);
        CloudSyncScheduler.startRealtime(context);
        return getDeviceState(context);
    }

    public static DeviceState getDeviceState(Context context) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        return parseState(CloudSyncApi.get(config, "/api/v1/devices"));
    }

    public static DeviceState saveRoutes(
            Context context, Set<String> pushTo, Set<String> receiveFrom) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        JSONObject body = new JSONObject()
                .put("pushTo", new JSONArray(pushTo))
                .put("receiveFrom", new JSONArray(receiveFrom));
        DeviceState state = parseState(CloudSyncApi.put(config, "/api/v1/sync-routes", body));
        CloudSyncScheduler.scheduleImmediate(context);
        CloudSyncScheduler.startRealtime(context);
        return state;
    }

    public static void uploadAsync(
            Context context, String address, String body, long timestamp,
            int subscriptionId, int type) {
        Context app = context.getApplicationContext();
        CloudSyncConfig config = new CloudSyncConfig(app);
        if (!config.isConnected()) return;
        String eventId = config.deviceId() + ":" + UUID.randomUUID();
        JSONObject pending = new JSONObject();
        try {
            pending.put("eventId", eventId)
                    .put("address", address)
                    .put("body", body)
                    .put("timestamp", timestamp)
                    .put("subscriptionId", subscriptionId)
                    .put("type", type);
            CloudSyncOutbox.enqueue(app, pending);
        } catch (Exception ignored) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                flushOutbox(app);
            } catch (Exception ignored) {
                CloudSyncScheduler.scheduleImmediate(app);
            }
        });
    }

    public static int upload(
            Context context, String address, String body, long timestamp,
            int subscriptionId, int type) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        String eventId = config.deviceId() + ":" + UUID.randomUUID();
        return uploadEvent(context, config, eventId, address, body, timestamp, subscriptionId, type);
    }

    public static synchronized int flushOutbox(Context context) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        int uploaded = 0;
        for (JSONObject event : CloudSyncOutbox.all(context)) {
            String eventId = event.getString("eventId");
            uploadEvent(
                    context, config, eventId, event.getString("address"),
                    event.getString("body"), event.getLong("timestamp"),
                    event.optInt("subscriptionId", -1), event.getInt("type"));
            CloudSyncOutbox.remove(context, eventId);
            uploaded++;
        }
        return uploaded;
    }

    private static int uploadEvent(
            Context context, CloudSyncConfig config, String eventId,
            String address, String body, long timestamp, int subscriptionId, int type) throws Exception {
        DeviceState state = getDeviceState(context);
        List<Device> targets = new ArrayList<>();
        for (Device device : state.devices) {
            if (state.pushTo.contains(device.id)) targets.add(device);
        }
        if (targets.isEmpty()) return 0;
        JSONObject payload = new JSONObject()
                .put("kind", "sms")
                .put("address", address)
                .put("body", body)
                .put("timestamp", timestamp)
                .put("subscriptionId", subscriptionId)
                .put("type", type);
        LineIdentity line = resolveLineIdentity(context, subscriptionId);
        if (line.number != null) payload.put("lineNumber", line.number);
        if (line.simSlot > 0) payload.put("simSlot", line.simSlot);
        JSONObject envelope = CloudCrypto.encrypt(eventId, payload, targets);
        JSONObject response = CloudSyncApi.post(config, "/api/v1/messages",
                new JSONObject().put("eventId", eventId).put("envelope", envelope));
        return response.optJSONArray("acceptedTargets") == null
                ? 0 : response.getJSONArray("acceptedTargets").length();
    }

    public static synchronized int pull(Context context) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        DeviceState state = getDeviceState(context);
        Map<String, Device> devices = new HashMap<>();
        for (Device device : state.devices) devices.put(device.id, device);
        JSONObject response = CloudSyncApi.get(config, "/api/v1/messages?after=0");
        JSONArray messages = response.optJSONArray("messages");
        if (messages == null) return 0;
        Set<String> processed = loadProcessed(context);
        int inserted = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            long deliveryId = message.getLong("deliveryId");
            String eventId = message.getString("eventId");
            if (processed.contains(eventId)) {
                acknowledge(config, deliveryId);
                continue;
            }
            Device source = devices.get(message.getString("sourceDeviceId"));
            if (source == null || source.publicKey == null || source.publicKey.isBlank()) {
                throw new SecurityException("找不到发送设备的公钥");
            }
            JSONObject payload = CloudCrypto.decrypt(
                    eventId, state.currentDeviceId, source.publicKey,
                    message.getJSONObject("envelope"));
            processPayload(context, payload, source);
            rememberProcessed(context, processed, eventId);
            acknowledge(config, deliveryId);
            inserted++;
        }
        return inserted;
    }

    public static void syncAsync(Context context, ResultCallback<Integer> callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                callback.onResult(pull(app), null);
            } catch (Exception error) {
                callback.onResult(0, error);
            }
        });
    }

    public static void sendRemoteAsync(
            Context context, CloudMessageOriginStore.Origin route,
            String address, String body, ResultCallback<Boolean> callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                callback.onResult(sendRemote(app, route, address, body), null);
            } catch (Exception error) {
                callback.onResult(false, error);
            }
        });
    }

    static boolean sendRemote(
            Context context, CloudMessageOriginStore.Origin route,
            String address, String body) throws Exception {
        if (route == null || route.deviceId == null || route.subscriptionId < 0) {
            throw new IllegalArgumentException("这条短信没有可用的远端发送线路");
        }
        if (address == null || address.isBlank() || address.length() > 512
                || body == null || body.isBlank() || body.length() > 60_000) {
            throw new IllegalArgumentException("短信内容无效");
        }
        CloudSyncConfig config = connectedConfig(context);
        DeviceState state = getDeviceState(context);
        Device target = null;
        for (Device device : state.devices) {
            if (route.deviceId.equals(device.id)) {
                target = device;
                break;
            }
        }
        if (target == null) throw new IllegalStateException("来源设备已不在当前账号中");
        String eventId = config.deviceId() + ":reply:" + UUID.randomUUID();
        JSONObject payload = new JSONObject()
                .put("kind", "send_sms")
                .put("address", address)
                .put("body", body)
                .put("subscriptionId", route.subscriptionId)
                .put("requestedAt", System.currentTimeMillis());
        JSONObject envelope = CloudCrypto.encrypt(eventId, payload, List.of(target));
        JSONObject response = CloudSyncApi.post(config, "/api/v1/messages", new JSONObject()
                .put("eventId", eventId)
                .put("targetDeviceId", target.id)
                .put("envelope", envelope));
        JSONArray accepted = response.optJSONArray("acceptedTargets");
        if (accepted == null || accepted.length() != 1
                || !target.id.equals(accepted.optString(0))) {
            throw new SecurityException("服务器未授权这条回源线路");
        }
        CloudSyncScheduler.startRealtime(context);
        return true;
    }

    private static void processPayload(Context context, JSONObject payload, Device source)
            throws Exception {
        String kind = payload.optString("kind");
        if ("sms".equals(kind)) {
            insertPayload(context, payload, source);
            return;
        }
        if ("send_sms".equals(kind)) {
            executeRemoteSend(context, payload);
            return;
        }
        throw new IllegalArgumentException("不支持的同步消息类型");
    }

    private static void executeRemoteSend(Context context, JSONObject payload) {
        String address = payload.optString("address", "").trim();
        String body = payload.optString("body", "");
        int subscriptionId = payload.optInt("subscriptionId", -1);
        if (address.isBlank() || address.length() > 512 || body.isBlank()
                || body.length() > 60_000 || !isActiveSubscription(context, subscriptionId)) {
            throw new IllegalArgumentException("远端发送指令的收件人、内容或 SIM 已失效");
        }
        boolean dispatched = io.github.venompool888.capsulesms.sms.SmsSender.send(
                context, address, body, subscriptionId, (success, result) -> {});
        if (!dispatched) throw new IllegalStateException("无法将短信交给指定 SIM");
    }

    private static void insertPayload(
            Context context, JSONObject payload, Device source) throws Exception {
        String address = payload.getString("address");
        String body = payload.getString("body");
        long timestamp = payload.getLong("timestamp");
        int type = payload.getInt("type");
        CloudMessageOriginStore.Origin origin = new CloudMessageOriginStore.Origin(
                source.id, source.name, payload.optInt("subscriptionId", -1),
                payload.optString("lineNumber", null), payload.optInt("simSlot", -1));
        if (address.isBlank() || address.length() > 512 || body.length() > 60_000
                || (type != Telephony.Sms.MESSAGE_TYPE_INBOX
                && type != Telephony.Sms.MESSAGE_TYPE_SENT)) {
            throw new IllegalArgumentException("同步短信内容无效");
        }
        long existingId = SmsRepository.findMessageId(context, address, body, timestamp, type);
        if (existingId >= 0) {
            new CloudMessageOriginStore(context).put(existingId, origin);
            return;
        }
        Uri saved;
        // Subscription IDs belong to the source phone and are not portable. Passing an
        // OPPO SIM ID to Pixel can make the target SmsProvider reject the insert.
        int targetSubscriptionId = -1;
        if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
            saved = SmsRepository.saveSent(context, address, body, timestamp, targetSubscriptionId);
        } else {
            saved = SmsRepository.saveIncoming(context, address, body, timestamp, targetSubscriptionId);
            SmsDeliverReceiver.notifyIncoming(context, address, body, origin.displayLabel());
        }
        if (saved == null) throw new IllegalStateException("无法写入系统短信库");
        new CloudMessageOriginStore(context).put(ContentUris.parseId(saved), origin);
    }

    private static LineIdentity resolveLineIdentity(Context context, int subscriptionId) {
        if (subscriptionId < 0 || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return new LineIdentity(null, -1);
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = manager.getActiveSubscriptionInfoList();
            if (active == null) return new LineIdentity(null, -1);
            for (SubscriptionInfo info : active) {
                if (info.getSubscriptionId() != subscriptionId) continue;
                String number = null;
                if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                        == PackageManager.PERMISSION_GRANTED) {
                    number = Build.VERSION.SDK_INT >= 33
                            ? manager.getPhoneNumber(subscriptionId) : info.getNumber();
                    if ((number == null || number.isBlank()) && info.getNumber() != null) {
                        number = info.getNumber();
                    }
                }
                return new LineIdentity(number, info.getSimSlotIndex() + 1);
            }
        } catch (SecurityException ignored) {}
        return new LineIdentity(null, -1);
    }

    private static boolean isActiveSubscription(Context context, int subscriptionId) {
        if (subscriptionId < 0 || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = manager.getActiveSubscriptionInfoList();
            if (active == null) return false;
            for (SubscriptionInfo info : active) {
                if (info.getSubscriptionId() == subscriptionId && info.getSimSlotIndex() >= 0) {
                    return true;
                }
            }
        } catch (SecurityException ignored) {}
        return false;
    }

    private static final class LineIdentity {
        final String number;
        final int simSlot;

        LineIdentity(String number, int simSlot) {
            this.number = number == null || number.isBlank() ? null : number.trim();
            this.simSlot = simSlot;
        }
    }

    private static void acknowledge(CloudSyncConfig config, long deliveryId) throws Exception {
        CloudSyncApi.post(config, "/api/v1/messages/" + deliveryId + "/ack", new JSONObject());
    }

    private static DeviceState parseState(JSONObject json) throws Exception {
        List<Device> devices = new ArrayList<>();
        JSONArray values = json.getJSONArray("devices");
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            devices.add(new Device(
                    value.getString("id"), value.optString("name", "未命名设备"),
                    value.optString("platform", ""), value.optString("publicKey", ""),
                    value.optLong("lastSeenAt", 0L)));
        }
        return new DeviceState(
                json.getString("currentDeviceId"), json.optString("account", ""), devices,
                stringSet(json.optJSONArray("pushTo")),
                stringSet(json.optJSONArray("receiveFrom")));
    }

    private static Set<String> stringSet(JSONArray array) {
        Set<String> result = new HashSet<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private static CloudSyncConfig connectedConfig(Context context) {
        CloudSyncConfig config = new CloudSyncConfig(context);
        if (!config.isConnected()) throw new IllegalStateException("尚未连接云同步服务器");
        return config;
    }

    private static Set<String> loadProcessed(Context context) {
        Set<String> result = new LinkedHashSet<>();
        String raw = context.getSharedPreferences(PROCESSED_PREFS, Context.MODE_PRIVATE)
                .getString(PROCESSED_KEY, "[]");
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        } catch (Exception ignored) {}
        return result;
    }

    private static void rememberProcessed(Context context, Set<String> processed, String eventId) {
        processed.add(eventId);
        while (processed.size() > 500) {
            String first = processed.iterator().next();
            processed.remove(first);
        }
        JSONArray values = new JSONArray();
        for (String value : processed) values.put(value);
        SharedPreferences prefs = context.getSharedPreferences(PROCESSED_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PROCESSED_KEY, values.toString()).apply();
    }

    private CloudSyncEngine() {}
}
