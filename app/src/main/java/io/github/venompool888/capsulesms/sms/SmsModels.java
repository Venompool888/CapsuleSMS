package io.github.venompool888.capsulesms.sms;

import android.net.Uri;

import java.util.List;

public final class SmsModels {
    private SmsModels() {}

    public static final class Conversation {
        private final long threadId;
        private final String address;
        private final List<String> recipients;
        private final String preview;
        private final long date;
        private final int unread;
        private final int subscriptionId;
        private final long latestMessageId;
        private final int latestMessageType;
        private final CloudMessageOriginStore.Origin origin;

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType) {
            this(-1L, address, List.of(address), preview, date, unread, subscriptionId,
                    latestMessageId, latestMessageType, false,
                    (CloudMessageOriginStore.Origin) null);
        }

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType,
                String sourceDeviceName) {
            this(-1L, address, List.of(address), preview, date, unread, subscriptionId,
                    latestMessageId, latestMessageType, false, sourceDeviceName == null ? null
                            : CloudMessageOriginStore.Origin.deviceOnly(sourceDeviceName));
        }

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType,
                CloudMessageOriginStore.Origin origin) {
            this(-1L, address, List.of(address), preview, date, unread, subscriptionId,
                    latestMessageId, latestMessageType, false, origin);
        }

        public Conversation(long threadId, String address, List<String> recipients,
                String preview, long date, int unread, int subscriptionId,
                long latestMessageId, int latestMessageType, boolean latestIsMms,
                CloudMessageOriginStore.Origin origin) {
            this.threadId = threadId;
            this.address = address;
            this.recipients = recipients == null || recipients.isEmpty()
                    ? List.of(address) : List.copyOf(recipients);
            this.preview = preview;
            this.date = date;
            this.unread = unread;
            this.subscriptionId = subscriptionId;
            this.latestMessageId = latestMessageId;
            this.latestMessageType = latestMessageType;
            this.origin = origin;
            this.latestIsMms = latestIsMms;
        }

        private final boolean latestIsMms;
        public long threadId() { return threadId; }
        public String address() { return address; }
        public List<String> recipients() { return recipients; }
        public String preview() { return preview; }
        public long date() { return date; }
        public int unread() { return unread; }
        public int subscriptionId() { return subscriptionId; }
        public long latestMessageId() { return latestMessageId; }
        public int latestMessageType() { return latestMessageType; }
        public String sourceDeviceName() { return origin == null ? null : origin.deviceName; }
        public CloudMessageOriginStore.Origin origin() { return origin; }
        public boolean latestIsMms() { return latestIsMms; }

        public boolean latestIsOutgoing() {
            return latestMessageType == 2 || latestMessageType == 4
                    || latestMessageType == 5 || latestMessageType == 6;
        }
    }

    public static final class Message {
        public enum Transport { SMS, MMS }
        public enum Delivery { NONE, SENDING, SENT, DELIVERED, FAILED }

        private final long id;
        private final long providerId;
        private final long threadId;
        private final String address;
        private final String body;
        private final long date;
        private final int type;
        private final CloudMessageOriginStore.Origin origin;
        private final Transport transport;
        private final Delivery delivery;
        private final List<Attachment> attachments;

        public Message(long id, String address, String body, long date, int type) {
            this(id, id, -1L, address, body, date, type,
                    Transport.SMS, deliveryForSms(type, -1), List.of(), null);
        }

        public Message(long id, String address, String body, long date, int type,
                String sourceDeviceName) {
            this(id, id, -1L, address, body, date, type,
                    Transport.SMS, deliveryForSms(type, -1), List.of(), sourceDeviceName == null ? null
                    : CloudMessageOriginStore.Origin.deviceOnly(sourceDeviceName));
        }

        public Message(long id, String address, String body, long date, int type,
                CloudMessageOriginStore.Origin origin) {
            this(id, id, -1L, address, body, date, type,
                    Transport.SMS, deliveryForSms(type, -1), List.of(), origin);
        }

        public Message(long id, long providerId, long threadId, String address,
                String body, long date, int type, Transport transport, Delivery delivery,
                List<Attachment> attachments, CloudMessageOriginStore.Origin origin) {
            this.id = id;
            this.providerId = providerId;
            this.threadId = threadId;
            this.address = address;
            this.body = body;
            this.date = date;
            this.type = type;
            this.origin = origin;
            this.transport = transport;
            this.delivery = delivery;
            this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public long id() { return id; }
        public long providerId() { return providerId; }
        public long threadId() { return threadId; }
        public String address() { return address; }
        public String body() { return body; }
        public long date() { return date; }
        public int type() { return type; }
        public String sourceDeviceName() { return origin == null ? null : origin.deviceName; }
        public CloudMessageOriginStore.Origin origin() { return origin; }
        public Transport transport() { return transport; }
        public Delivery delivery() { return delivery; }
        public List<Attachment> attachments() { return attachments; }
        public boolean isMms() { return transport == Transport.MMS; }

        public boolean isOutgoing() {
            return type == 2 || type == 4 || type == 5 || type == 6;
        }

        public static Delivery deliveryForSms(int type, int status) {
            if (type == 5) return Delivery.FAILED;
            if (type == 4 || type == 6) return Delivery.SENDING;
            if (type != 2) return Delivery.NONE;
            if (status == 0) return Delivery.DELIVERED;
            if (status == 64) return Delivery.FAILED;
            return Delivery.SENT;
        }
    }

    public static final class Attachment {
        private final Uri uri;
        private final String mimeType;
        private final String name;
        private final long size;

        public Attachment(Uri uri, String mimeType, String name, long size) {
            this.uri = uri;
            this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
            this.name = name == null || name.isBlank() ? "附件" : name;
            this.size = Math.max(0L, size);
        }

        public Uri uri() { return uri; }
        public String mimeType() { return mimeType; }
        public String name() { return name; }
        public long size() { return size; }
        public boolean isImage() { return mimeType.startsWith("image/"); }
        public boolean isVideo() { return mimeType.startsWith("video/"); }
        public boolean isAudio() { return mimeType.startsWith("audio/"); }
    }
}
