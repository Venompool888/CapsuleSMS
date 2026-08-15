package io.github.venompool888.capsulesms;

import android.content.Context;
import android.content.SharedPreferences;

/** Message typography preferences applied on top of Android's system font scale. */
public final class TypographyPreferences {
    public static final int FONT_STEP_MIN = -3;
    public static final int FONT_STEP_MAX = 6;
    public static final int LINE_STEP_MIN = 0;
    public static final int LINE_STEP_MAX = 10;

    private static final String PREFS = "message_typography";
    private static final String KEY_FONT_STEP = "font_step";
    private static final String KEY_LINE_STEP = "line_step";
    private static final int DEFAULT_FONT_STEP = 0;
    private static final int DEFAULT_LINE_STEP = 2;

    private final SharedPreferences preferences;

    public TypographyPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int fontStep() {
        return clamp(preferences.getInt(KEY_FONT_STEP, DEFAULT_FONT_STEP),
                FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public int lineStep() {
        return clamp(preferences.getInt(KEY_LINE_STEP, DEFAULT_LINE_STEP),
                LINE_STEP_MIN, LINE_STEP_MAX);
    }

    public void setFontStep(int value) {
        preferences.edit().putInt(KEY_FONT_STEP,
                clamp(value, FONT_STEP_MIN, FONT_STEP_MAX)).apply();
    }

    public void setLineStep(int value) {
        preferences.edit().putInt(KEY_LINE_STEP,
                clamp(value, LINE_STEP_MIN, LINE_STEP_MAX)).apply();
    }

    public void reset() {
        preferences.edit()
                .putInt(KEY_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_LINE_STEP, DEFAULT_LINE_STEP)
                .apply();
    }

    public float scaledSp(float baseSp) {
        return baseSp * fontScaleForStep(fontStep());
    }

    public float lineSpacingMultiplier() {
        return lineSpacingForStep(lineStep());
    }

    public String signature() {
        return fontStep() + ":" + lineStep();
    }

    public static float fontScaleForStep(int step) {
        return 1f + clamp(step, FONT_STEP_MIN, FONT_STEP_MAX) * 0.05f;
    }

    public static float lineSpacingForStep(int step) {
        return 1f + clamp(step, LINE_STEP_MIN, LINE_STEP_MAX) * 0.05f;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
