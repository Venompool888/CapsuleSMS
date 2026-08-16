package io.github.venompool888.capsulesms.sms;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.SendReq;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class MmsSender {
    public interface Callback { void onResult(boolean accepted, String message); }

    public static boolean send(Context context, List<String> recipients, String body,
            List<SmsModels.Attachment> attachments, int subscriptionId,
            long threadId, Callback callback) {
        if (recipients == null || recipients.isEmpty()) {
            callback.onResult(false, "请填写至少一个收件人");
            return false;
        }
        try {
            int maxBytes = carrierLimit(context, subscriptionId);
            int total = body == null ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            SendReq request = new SendReq();
            request.prepareFromAddress(context, null, subscriptionId);
            for (String recipient : recipients) request.addTo(new EncodedStringValue(recipient));
            request.setDate(System.currentTimeMillis() / 1000L);
            request.setPriority(PduHeaders.PRIORITY_NORMAL);
            request.setDeliveryReport(PduHeaders.VALUE_YES);
            request.setReadReport(PduHeaders.VALUE_NO);
            request.setExpiry(7L * 24L * 60L * 60L);
            request.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR
                    .getBytes(StandardCharsets.US_ASCII));
            PduBody pduBody = new PduBody();
            StringBuilder smil = new StringBuilder(
                    "<smil><head><layout><root-layout width=\"100%\" height=\"100%\"/>"
                            + "</layout></head><body><par dur=\"5000ms\">");
            if (body != null && !body.isBlank()) {
                PduPart text = part("text/plain", "text_0.txt",
                        body.getBytes(StandardCharsets.UTF_8));
                text.setCharset(CharacterSets.UTF_8);
                pduBody.addPart(text);
                smil.append("<text src=\"text_0.txt\"/>");
            }
            int attachmentIndex = 0;
            for (SmsModels.Attachment attachment : attachments) {
                byte[] data = readBounded(context, attachment, maxBytes - total);
                total += data.length;
                String safeName = safePartName(attachment.name(), attachmentIndex++);
                pduBody.addPart(part(attachment.mimeType(), safeName, data));
                String tag = attachment.isImage() ? "img"
                        : attachment.isVideo() ? "video" : attachment.isAudio() ? "audio" : "ref";
                smil.append('<').append(tag).append(" src=\"")
                        .append(xmlEscape(safeName)).append("\"/>");
            }
            if (total > maxBytes) {
                callback.onResult(false, "附件超过运营商彩信上限（"
                        + readableBytes(maxBytes) + "）");
                return false;
            }
            smil.append("</par></body></smil>");
            pduBody.addPart(0, part(ContentType.APP_SMIL, "smil.xml",
                    smil.toString().getBytes(StandardCharsets.UTF_8)));
            request.setBody(pduBody);
            request.setMessageSize(total);
            Uri messageUri = PduPersister.getPduPersister(context).persist(
                    request, Telephony.Mms.Outbox.CONTENT_URI, true, true,
                    null, subscriptionId);
            if (messageUri == null) throw new IllegalStateException("无法写入彩信发件箱");
            String fileName = "mms-send-" + UUID.randomUUID() + ".pdu";
            File file = new File(context.getCacheDir(), fileName);
            byte[] pdu = new PduComposer(context, request).make();
            if (pdu == null || pdu.length == 0) throw new IllegalStateException("无法生成彩信数据");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(pdu); }
            Uri contentUri = new Uri.Builder().scheme("content")
                    .authority(context.getPackageName() + ".MmsFileProvider")
                    .path(fileName).build();
            Intent status = new Intent(context, MmsStatusReceiver.class)
                    .setAction(MmsStatusReceiver.ACTION_SENT)
                    .setData(Uri.parse("capsulesms://mms-send/" + messageUri.getLastPathSegment()))
                    .putExtra(com.klinker.android.send_message.MmsSentReceiver.EXTRA_CONTENT_URI,
                            messageUri.toString())
                    .putExtra(com.klinker.android.send_message.MmsSentReceiver.EXTRA_FILE_PATH,
                            file.getAbsolutePath());
            PendingIntent sent = PendingIntent.getBroadcast(context,
                    java.util.Objects.hash(messageUri.toString(), fileName), status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            SmsManager manager = context.getSystemService(SmsManager.class);
            if (subscriptionId >= 0) manager = manager.createForSubscriptionId(subscriptionId);
            Bundle overrides = new Bundle();
            overrides.putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, recipients.size() > 1);
            overrides.putInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, maxBytes);
            manager.sendMultimediaMessage(context, contentUri, null, overrides, sent,
                    messageUri.getLastPathSegment() == null ? 0L
                            : Long.parseLong(messageUri.getLastPathSegment()));
            callback.onResult(true, "正在发送彩信");
            return true;
        } catch (TooLargeException error) {
            callback.onResult(false, error.getMessage());
        } catch (Exception error) {
            callback.onResult(false, "彩信发送失败（" + safeMessage(error) + "）");
        }
        return false;
    }

    private static PduPart part(String mimeType, String name, byte[] data) {
        PduPart part = new PduPart();
        part.setContentType(mimeType.getBytes(StandardCharsets.US_ASCII));
        part.setName(name.getBytes(StandardCharsets.UTF_8));
        part.setFilename(name.getBytes(StandardCharsets.UTF_8));
        part.setContentLocation(name.getBytes(StandardCharsets.UTF_8));
        part.setContentId(("<" + name + ">").getBytes(StandardCharsets.UTF_8));
        part.setData(data);
        return part;
    }

    private static String safePartName(String value, int index) {
        String name = value == null ? "attachment_" + index : value;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "attachment_" + index : name;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int carrierLimit(Context context, int subscriptionId) {
        SmsManager manager = context.getSystemService(SmsManager.class);
        if (subscriptionId >= 0) manager = manager.createForSubscriptionId(subscriptionId);
        try {
            Bundle values = manager.getCarrierConfigValues();
            int configured = values == null ? 0
                    : values.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0);
            if (configured > 0) return Math.min(configured, 5 * 1024 * 1024);
        } catch (RuntimeException ignored) {}
        return 1024 * 1024;
    }

    private static byte[] readBounded(
            Context context, SmsModels.Attachment attachment, int remaining) throws Exception {
        if (remaining <= 0 || (attachment.size() > 0 && attachment.size() > remaining)) {
            throw new TooLargeException("附件超过运营商彩信上限");
        }
        try (InputStream input = context.getContentResolver().openInputStream(attachment.uri());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("无法读取 " + attachment.name());
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > remaining) {
                    throw new TooLargeException("附件超过运营商彩信上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readableBytes(int bytes) {
        if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / 1024) + " KB";
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static final class TooLargeException extends Exception {
        TooLargeException(String message) { super(message); }
    }

    private MmsSender() {}
}
