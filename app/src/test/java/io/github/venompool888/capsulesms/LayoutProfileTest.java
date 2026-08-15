package io.github.venompool888.capsulesms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LayoutProfileTest {
    @Test public void oppoWidthUsesCompactStructure() {
        LayoutProfile profile = LayoutProfile.forWidthDp(359);
        assertTrue(profile.compact);
        assertEquals(46, profile.avatarSizeDp);
        assertEquals(62, profile.metaWidthDp);
        assertEquals(8, profile.inboxVerticalPaddingDp(10));
    }

    @Test public void pixelWidthKeepsComfortableStructure() {
        LayoutProfile profile = LayoutProfile.forWidthDp(411);
        assertFalse(profile.compact);
        assertEquals(52, profile.avatarSizeDp);
        assertEquals(72, profile.metaWidthDp);
        assertEquals(10, profile.inboxVerticalPaddingDp(10));
    }

    @Test public void compactProfileDoesNotCollapseManualSpacing() {
        assertEquals(4, LayoutProfile.forWidthDp(359).inboxVerticalPaddingDp(4));
    }
}
