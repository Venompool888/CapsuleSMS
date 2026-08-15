package io.github.venompool888.capsulesms.sms;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CloudMessageOriginStoreTest {
    @Test public void displayLabelIncludesReceivingNumberWhenKnown() {
        CloudMessageOriginStore.Origin origin = new CloudMessageOriginStore.Origin(
                "oppo-id", "OPPO C", 12, "+61422412170", 2);
        assertEquals("OPPO C · +61422412170", origin.displayLabel());
    }

    @Test public void displayLabelFallsBackToPhysicalSimSlot() {
        CloudMessageOriginStore.Origin origin = new CloudMessageOriginStore.Origin(
                "oppo-id", "OPPO C", 12, null, 2);
        assertEquals("OPPO C · SIM 2", origin.displayLabel());
    }
}
