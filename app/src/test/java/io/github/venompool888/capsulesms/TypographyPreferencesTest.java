package io.github.venompool888.capsulesms;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TypographyPreferencesTest {
    @Test public void fontScaleUsesFivePercentStepsAndClamps() {
        assertEquals(0.85f, TypographyPreferences.fontScaleForStep(-9), 0.001f);
        assertEquals(1.00f, TypographyPreferences.fontScaleForStep(0), 0.001f);
        assertEquals(1.30f, TypographyPreferences.fontScaleForStep(20), 0.001f);
    }

    @Test public void lineSpacingUsesFivePercentStepsAndClamps() {
        assertEquals(1.00f, TypographyPreferences.lineSpacingForStep(-1), 0.001f);
        assertEquals(1.10f, TypographyPreferences.lineSpacingForStep(2), 0.001f);
        assertEquals(1.50f, TypographyPreferences.lineSpacingForStep(30), 0.001f);
    }
}
