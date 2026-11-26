package io.github.venompool888.capsulesms.sync;

import android.Manifest;
import android.content.Context;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.util.Log;
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
import org.json.JSONException;
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
import java.util.function.Consumer;

public final class CloudSyncEngine {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final String PROCESSED_PREFS = "cloud_sync_processed";
    private static final String PROCESSED_KEY = "event_ids";
    // A remote send is only carried out shortly after it was asked for, never days later when
    // the SIM comes back. Generous enough for clock differences between the two phones.
    static final long REMOTE_SEND_MAX_AGE_MS = 30 * 60 * 1000L;
    /** Matches the server's LIMIT on GET /api/v1/messages. */
    static final int PULL_PAGE_SIZE = 100;
    static final int MAX_PULL_PAGES = 10;
    static final int MAX_PROCESSED = 500;
    private static final String KIND_SMS = "sms";
    private static final String KIND_SEND_SMS = "send_sms";
    /** Clock skew allowed between devices; a command dated further ahead can't be trusted to expire. */
    static final long REMOTE_SEND_MAX_FUTURE_MS = 5 * 60 * 1000L;

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

        boolean isReceiveOnly() {
            return "fluidcapsule-android".equals(platform);
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

    static DeviceState updateRoute(Context context, String deviceId, boolean push, boolean enabled)
            throws Exception {
        DeviceState current = getDeviceState(context);
        Device peer = current.devices.stream().filter(device -> device.id.equals(deviceId))
                .findFirst().orElseThrow(() -> new IllegalStateException("设备已不在当前账号中"));
        Set<String> routes = push ? current.pushTo : current.receiveFrom;
        if (enabled) {
            if (!push && peer.isReceiveOnly()) throw new IllegalArgumentException("流体胶囊只接收短信");
            routes.add(deviceId);
        } else {
            routes.remove(deviceId);
        }
        current.devices.stream().filter(Device::isReceiveOnly)
                .forEach(device -> current.receiveFrom.remove(device.id));
        return saveRoutes(context, current.pushTo, current.receiveFrom);
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
        return uploadEvent(context, config, getDeviceState(context), eventId,
                address, body, timestamp, subscriptionId, type);
    }

    public static synchronized int flushOutbox(Context context) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        long startedAt = System.currentTimeMillis();
        int uploaded = 0;
        // One device list per flush: a backlog of N messages used to cost N extra requests.
        DeviceState state = null;
        for (JSONObject event : CloudSyncOutbox.all(context)) {
            String eventId = event.getString("eventId");
            if (state == null) state = getDeviceState(context);
            try {
                uploadEvent(
                        context, config, state, eventId, event.getString("address"),
                        event.getString("body"), event.getLong("timestamp"),
                        event.optInt("subscriptionId", -1), event.getInt("type"));
                uploaded++;
            } catch (CloudSyncApi.RejectedEnvelopeException error) {
                config.recordError(CloudSyncConfig.UPLOAD, "同步短信已跳过：" + error.getMessage());
            }
            CloudSyncOutbox.remove(context, eventId);
        }
        config.clearErrorIfNoneSince(CloudSyncConfig.UPLOAD, startedAt);
        return uploaded;
    }

    private static int uploadEvent(
            Context context, CloudSyncConfig config, DeviceState state, String eventId,
            String address, String body, long timestamp, int subscriptionId, int type) throws Exception {
        List<Device> targets = new ArrayList<>();
        for (Device device : state.devices) {
            if (!state.pushTo.contains(device.id)) continue;
            // FluidCapsule only shows incoming SMS; don't copy sent messages to it.
            if (device.isReceiveOnly() && type != Telephony.Sms.MESSAGE_TYPE_INBOX) continue;
            if (!CloudCrypto.isUsablePublicKey(device.publicKey)) {
                // One device without a usable key must not hold up the whole outbox.
                config.recordError(CloudSyncConfig.UPLOAD, "设备“" + device.name + "”没有可用的加密公钥，已跳过，请重新连接该设备");
                continue;
            }
            targets.add(device);
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
        JSONObject response = CloudSyncApi.postMessage(config,
                new JSONObject().put("eventId", eventId).put("envelope", envelope));
        return response.optJSONArray("acceptedTargets") == null
                ? 0 : response.getJSONArray("acceptedTargets").length();
    }

    public static synchronized int pull(Context context) throws Exception {
        CloudSyncConfig config = connectedConfig(context);
        long startedAt = System.currentTimeMillis();
        PullTimings timings = new PullTimings();
        long phase = System.nanoTime();
        DeviceState state = getDeviceState(context);
        timings.state += System.nanoTime() - phase;
        Map<String, Device> devices = new HashMap<>();
        for (Device device : state.devices) devices.put(device.id, device);
        Set<String> processed = loadProcessed(context);
        boolean[] processedDirty = {false};
        IncomingNotificationBatch notifications = new IncomingNotificationBatch();
        int inserted = 0;
        RetryLaterException deferred = null;
        long cursor = 0;
        try {
            // The server returns at most 100 deliveries per page. Follow the cursor so a backlog
            // (or a run of deliveries left pending for retry) can't hide the messages behind it.
            for (int page = 0; page < MAX_PULL_PAGES; page++) {
                phase = System.nanoTime();
                JSONObject response = CloudSyncApi.get(config, "/api/v1/messages?after=" + cursor);
                timings.list += System.nanoTime() - phase;
                timings.pages++;
                JSONArray messages = response.optJSONArray("messages");
                if (messages == null || messages.length() == 0) break;
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.getJSONObject(i);
                    long deliveryId = message.getLong("deliveryId");
                    String eventId = message.getString("eventId");
                    timings.messages++;
                    if (processed.contains(eventId)) {
                        acknowledge(config, deliveryId, timings);
                        continue;
                    }
                    String[] kind = {null};
                    try {
                        if (completeDelivery(
                                () -> kind[0] = applyDelivery(
                                        context, state.currentDeviceId, devices, message, notifications, timings),
                                reason -> config.recordError(CloudSyncConfig.PULL, "跳过同步消息 #" + deliveryId + "：" + reason),
                                () -> {
                                    long remembered = System.nanoTime();
                                    addProcessed(processed, eventId);
                                    if (KIND_SEND_SMS.equals(kind[0])) {
                                        // A replayed send command would send the SMS twice: persist
                                        // before acknowledging. Inserted SMS are also de-duplicated
                                        // against the SMS provider, so those can be saved per page.
                                        saveProcessed(context, processed);
                                    } else {
                                        processedDirty[0] = true;
                                    }
                                    timings.remember += System.nanoTime() - remembered;
                                    acknowledge(config, deliveryId, timings);
                                })) {
                            inserted++;
                        }
                    } catch (RetryLaterException error) {
                        // Leave it unacknowledged for the next pull, but keep going so later
                        // messages are not stuck behind it.
                        config.recordError(CloudSyncConfig.PULL, "同步消息 #" + deliveryId + " 稍后重试：" + error.getMessage());
                        deferred = error;
                    }
                }
                flushProcessed(context, processed, processedDirty, timings);
                long next = response.optLong("nextCursor", cursor);
                if (messages.length() < PULL_PAGE_SIZE || next <= cursor) break;
                cursor = next;
            }
        } finally {
            // Also on failure: what was inserted before the error still gets its notification.
            flushProcessed(context, processed, processedDirty, timings);
            postNotifications(context, notifications);
        }
        timings.log(startedAt);
        // Fail the pull after the rest went through, so callers back off instead of
        // spinning on a delivery the server keeps reporting as pending.
        if (deferred != null) throw deferred;
        config.clearErrorIfNoneSince(CloudSyncConfig.PULL, startedAt);
        return inserted;
    }

    @FunctionalInterface
    interface DeliveryAction {
        void run() throws Exception;
    }

    static boolean completeDelivery(
            DeliveryAction apply, Consumer<String> recordError, DeliveryAction markProcessedAndAck)
            throws Exception {
        boolean applied = false;
        try {
            apply.run();
            applied = true;
        } catch (CloudCrypto.InvalidEnvelopeException | InvalidPayloadException error) {
            recordError.accept(error.getMessage());
        } catch (JSONException error) {
            recordError.accept("信封或短信格式无效");
        }
        markProcessedAndAck.run();
        return applied;
    }

    /** Applies one delivery and returns its payload kind. */
    private static String applyDelivery(
            Context context, String currentDeviceId, Map<String, Device> devices, JSONObject message,
            IncomingNotificationBatch notifications, PullTimings timings) throws Exception {
        Device source = devices.get(message.getString("sourceDeviceId"));
        if (source == null || source.publicKey == null || source.publicKey.isBlank()) {
            throw new CloudCrypto.InvalidEnvelopeException("找不到发送设备的公钥");
        }
        if (source.isReceiveOnly()) {
            throw new InvalidPayloadException("流体胶囊只接收短信，已忽略它发来的消息");
        }
        long started = System.nanoTime();
        JSONObject payload = CloudCrypto.decrypt(
                message.getString("eventId"), currentDeviceId, source.publicKey,
                message.getJSONObject("envelope"));
        long decrypted = System.nanoTime();
        timings.decrypt += decrypted - started;
        String kind = processPayload(context, payload, source, notifications);
        timings.apply += System.nanoTime() - decrypted;
        return kind;
    }

    /**
     * Retires this device on the server (token and routes), then forgets the connection
     * locally. The local side always disconnects; returns false only if the server could
     * not be told, so the device is still listed there until revoked on the web.
     */
    public static boolean disconnect(Context context) {
        CloudSyncConfig config = new CloudSyncConfig(context);
        boolean revoked = !config.isConnected();
        if (!revoked) {
            try {
                CloudSyncApi.post(config, "/api/v1/devices/self/revoke", new JSONObject());
                revoked = true;
            } catch (CloudSyncApi.ApiException error) {
                // 401: the token was already revoked, which is the state we wanted.
                revoked = error.status == 401;
            } catch (Exception offlineOrOldServer) {
                revoked = false;
            }
        }
        config.clear();
        CloudSyncScheduler.cancel(context);
        return revoked;
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
        if (target.isReceiveOnly()) throw new IllegalStateException("流体胶囊不能代发短信");
        String eventId = config.deviceId() + ":reply:" + UUID.randomUUID();
        JSONObject payload = new JSONObject()
                .put("kind", "send_sms")
                .put("address", address)
                .put("body", body)
                .put("subscriptionId", route.subscriptionId)
                .put("requestedAt", System.currentTimeMillis());
        JSONObject envelope = CloudCrypto.encrypt(eventId, payload, List.of(target));
        JSONObject response = CloudSyncApi.postMessage(config, new JSONObject()
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

    private static String processPayload(Context context, JSONObject payload, Device source,
            IncomingNotificationBatch notifications) throws Exception {
        String kind = payload.optString("kind");
        if (KIND_SMS.equals(kind)) {
            insertPayload(context, payload, source, notifications);
            return kind;
        }
        if (KIND_SEND_SMS.equals(kind)) {
            executeRemoteSend(context, payload);
            return kind;
        }
        throw new InvalidPayloadException("不支持的同步消息类型");
    }

    private static void executeRemoteSend(Context context, JSONObject payload)
            throws InvalidPayloadException {
        if (isStaleRemoteSend(payload.optLong("requestedAt", 0L), System.currentTimeMillis())) {
            throw new InvalidPayloadException("远端发送指令已过期，未发送");
        }
        String address = payload.optString("address", "").trim();
        String body = payload.optString("body", "");
        int subscriptionId = payload.optInt("subscriptionId", -1);
        if (address.isBlank() || address.length() > 512 || body.isBlank()
                || body.length() > 60_000 || subscriptionId < 0) {
            throw new InvalidPayloadException("远端发送指令的收件人、内容或 SIM 无效");
        }
        if (!isActiveSubscription(context, subscriptionId)) {
            throw new InvalidPayloadException("远端发送指令的 SIM 不可用，未发送");
        }
        boolean dispatched = io.github.venompool888.capsulesms.sms.SmsSender.send(
                context, address, body, subscriptionId, (success, result) -> {});
        if (!dispatched) throw new InvalidPayloadException("无法将短信交给指定 SIM，未发送");
    }

    static boolean isStaleRemoteSend(long requestedAt, long now) {
        return requestedAt <= 0 || now - requestedAt > REMOTE_SEND_MAX_AGE_MS
                || requestedAt - now > REMOTE_SEND_MAX_FUTURE_MS;
    }

    private static void insertPayload(Context context, JSONObject payload, Device source,
            IncomingNotificationBatch notifications) throws Exception {
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
            throw new InvalidPayloadException("同步短信内容无效");
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
        }
        // Checked before notifying, so a retried delivery doesn't notify twice.
        if (saved == null) throw new RetryLaterException("无法写入系统短信库");
        if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
            notifications.add(address, body, origin.displayLabel());
        }
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

    static final class InvalidPayloadException extends Exception {
        InvalidPayloadException(String message) {
            super(message);
        }
    }

    /** A delivery that failed for a local reason that may clear up; retried on a later pull. */
    static final class RetryLaterException extends Exception {
        RetryLaterException(String message) {
            super(message);
        }
    }

    private static void acknowledge(CloudSyncConfig config, long deliveryId, PullTimings timings)
            throws Exception {
        long started = System.nanoTime();
        try {
            CloudSyncApi.post(config, "/api/v1/messages/" + deliveryId + "/ack", new JSONObject());
        } catch (CloudSyncApi.ApiException error) {
            if (error.status != 404) throw error;
        } finally {
            timings.ack += System.nanoTime() - started;
        }
    }

    /** Where a pull spends its time, logged once per pull with counts only (no message data). */
    static final class PullTimings {
        int pages;
        int messages;
        long state;
        long list;
        long decrypt;
        long apply;
        long remember;
        long ack;

        void log(long startedAtMillis) {
            if (messages == 0) return;
            Log.i("CapsuleCloudSync", "pull timing n=" + messages + " pages=" + pages
                    + " totalMs=" + (System.currentTimeMillis() - startedAtMillis)
                    + " stateMs=" + ms(state) + " listMs=" + ms(list)
                    + " decryptMs=" + ms(decrypt) + " applyMs=" + ms(apply)
                    + " rememberMs=" + ms(remember) + " ackMs=" + ms(ack));
        }

        private static long ms(long nanos) {
            return nanos / 1_000_000L;
        }
    }

    private static DeviceState parseState(JSONObject json) throws Exception {
        List<Device> devices = new ArrayList<>();
        JSONArray values = json.getJSONArray("devices");
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            devices.add(new Device(
                    value.getString("id"), value.optString("name", "未命名设备"),
                    value.optString("platform", ""),
                    value.isNull("publicKey") ? "" : value.optString("publicKey", ""),
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

    static void addProcessed(Set<String> processed, String eventId) {
        processed.remove(eventId);
        processed.add(eventId);
        while (processed.size() > MAX_PROCESSED) {
            String first = processed.iterator().next();
            processed.remove(first);
        }
    }

    private static void flushProcessed(Context context, Set<String> processed, boolean[] dirty,
            PullTimings timings) {
        if (!dirty[0]) return;
        long started = System.nanoTime();
        saveProcessed(context, processed);
        dirty[0] = false;
        timings.remember += System.nanoTime() - started;
    }

    private static void postNotifications(Context context, IncomingNotificationBatch notifications) {
        for (IncomingNotificationBatch.Entry entry : notifications.drain()) {
            SmsDeliverReceiver.notifyIncoming(context, entry.address, entry.body, entry.label);
        }
    }

    private static void saveProcessed(Context context, Set<String> processed) {
        JSONArray values = new JSONArray();
        for (String value : processed) values.put(value);
        SharedPreferences prefs = context.getSharedPreferences(PROCESSED_PREFS, Context.MODE_PRIVATE);
        if (!prefs.edit().putString(PROCESSED_KEY, values.toString()).commit()) {
            throw new IllegalStateException("无法保存同步去重记录");
        }
    }

    private CloudSyncEngine() {}
}
