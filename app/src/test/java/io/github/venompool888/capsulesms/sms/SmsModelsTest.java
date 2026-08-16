package io.github.venompool888.capsulesms.sms;

import android.provider.Telephony;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SmsModelsTest {
    @Test public void smsDeliveryMapsProviderStates() {
        assertEquals(SmsModels.Message.Delivery.SENDING,
                SmsModels.Message.deliveryForSms(Telephony.Sms.MESSAGE_TYPE_OUTBOX, -1));
        assertEquals(SmsModels.Message.Delivery.FAILED,
                SmsModels.Message.deliveryForSms(Telephony.Sms.MESSAGE_TYPE_FAILED, 64));
        assertEquals(SmsModels.Message.Delivery.DELIVERED,
                SmsModels.Message.deliveryForSms(Telephony.Sms.MESSAGE_TYPE_SENT,
                        Telephony.Sms.STATUS_COMPLETE));
        assertEquals(SmsModels.Message.Delivery.SENT,
                SmsModels.Message.deliveryForSms(Telephony.Sms.MESSAGE_TYPE_SENT,
                        Telephony.Sms.STATUS_PENDING));
    }

    @Test public void conversationKeepsThreadAndGroupRecipients() {
        SmsModels.Conversation conversation = new SmsModels.Conversation(
                42L, "+61400、+61401", List.of("+61400", "+61401"), "hello",
                1000L, 1, 7, -9L, Telephony.Mms.MESSAGE_BOX_INBOX, true, null);

        assertEquals(42L, conversation.threadId());
        assertEquals(List.of("+61400", "+61401"), conversation.recipients());
        assertTrue(conversation.latestIsMms());
    }
}
