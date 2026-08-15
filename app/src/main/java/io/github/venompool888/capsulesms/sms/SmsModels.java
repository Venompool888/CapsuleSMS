package io.github.venompool888.capsulesms.sms;

public final class SmsModels {
    private SmsModels() {}

    public static final class Conversation {
        private final String address;
        private final String preview;
        private final long date;
        private final int unread;
        private final int subscriptionId;
        private final long latestMessageId;
        private final int latestMessageType;
        private final CloudMessageOriginStore.Origin origin;

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType) {
            this(address, preview, date, unread, subscriptionId, latestMessageId,
                    latestMessageType, (CloudMessageOriginStore.Origin) null);
        }

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType,
                String sourceDeviceName) {
            this(address, preview, date, unread, subscriptionId, latestMessageId,
                    latestMessageType, sourceDeviceName == null ? null
                            : CloudMessageOriginStore.Origin.deviceOnly(sourceDeviceName));
        }

        public Conversation(String address, String preview, long date, int unread,
                int subscriptionId, long latestMessageId, int latestMessageType,
                CloudMessageOriginStore.Origin origin) {
            this.address = address;
            this.preview = preview;
            this.date = date;
            this.unread = unread;
            this.subscriptionId = subscriptionId;
            this.latestMessageId = latestMessageId;
            this.latestMessageType = latestMessageType;
            this.origin = origin;
        }

        public String address() { return address; }
        public String preview() { return preview; }
        public long date() { return date; }
        public int unread() { return unread; }
        public int subscriptionId() { return subscriptionId; }
        public long latestMessageId() { return latestMessageId; }
        public int latestMessageType() { return latestMessageType; }
        public String sourceDeviceName() { return origin == null ? null : origin.deviceName; }
        public CloudMessageOriginStore.Origin origin() { return origin; }

        public boolean latestIsOutgoing() {
            return latestMessageType == 2 || latestMessageType == 4
                    || latestMessageType == 5 || latestMessageType == 6;
        }
    }

    public static final class Message {
        private final long id;
        private final String address;
        private final String body;
        private final long date;
        private final int type;
        private final CloudMessageOriginStore.Origin origin;

        public Message(long id, String address, String body, long date, int type) {
            this(id, address, body, date, type, (CloudMessageOriginStore.Origin) null);
        }

        public Message(long id, String address, String body, long date, int type,
                String sourceDeviceName) {
            this(id, address, body, date, type, sourceDeviceName == null ? null
                    : CloudMessageOriginStore.Origin.deviceOnly(sourceDeviceName));
        }

        public Message(long id, String address, String body, long date, int type,
                CloudMessageOriginStore.Origin origin) {
            this.id = id;
            this.address = address;
            this.body = body;
            this.date = date;
            this.type = type;
            this.origin = origin;
        }

        public long id() { return id; }
        public String address() { return address; }
        public String body() { return body; }
        public long date() { return date; }
        public int type() { return type; }
        public String sourceDeviceName() { return origin == null ? null : origin.deviceName; }
        public CloudMessageOriginStore.Origin origin() { return origin; }

        public boolean isOutgoing() {
            return type == 2 || type == 4 || type == 5 || type == 6;
        }
    }
}
