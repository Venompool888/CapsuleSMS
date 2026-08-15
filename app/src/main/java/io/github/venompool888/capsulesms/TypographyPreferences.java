package io.github.venompool888.capsulesms;

import android.content.Context;
import android.content.SharedPreferences;

/** Message typography preferences applied on top of Android's system font scale. */
public final class TypographyPreferences {
    public static final int FONT_STEP_MIN = -3;
    public static final int FONT_STEP_MAX = 6;
    public static final int LINE_STEP_MIN = 0;
    public static final int LINE_STEP_MAX = 10;
    public static final int ITEM_SPACING_STEP_MIN = 0;
    public static final int ITEM_SPACING_STEP_MAX = 7;
    public static final int BUBBLE_SPACING_STEP_MIN = 0;
    public static final int BUBBLE_SPACING_STEP_MAX = 8;

    private static final String PREFS = "message_typography";
    private static final String KEY_FONT_STEP = "font_step";
    private static final String KEY_LINE_STEP = "line_step";
    private static final String KEY_INBOX_FONT_STEP = "inbox_font_step";
    private static final String KEY_INBOX_LINE_STEP = "inbox_line_step";
    private static final String KEY_INBOX_SPACING_STEP = "inbox_spacing_step";
    private static final String KEY_CONVERSATION_FONT_STEP = "conversation_font_step";
    private static final String KEY_CONVERSATION_LINE_STEP = "conversation_line_step";
    private static final String KEY_BUBBLE_SPACING_STEP = "bubble_spacing_step";
    private static final int DEFAULT_FONT_STEP = 0;
    private static final int DEFAULT_LINE_STEP = 2;
    private static final int DEFAULT_INBOX_SPACING_STEP = 3;
    private static final int DEFAULT_BUBBLE_SPACING_STEP = 2;

    private final SharedPreferences preferences;

    public TypographyPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int fontStep() {
        return clamp(preferences.getInt(KEY_FONT_STEP, DEFAULT_FONT_STEP),
                FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public int inboxFontStep() {
        return storedOrLegacy(KEY_INBOX_FONT_STEP, KEY_FONT_STEP, DEFAULT_FONT_STEP,
                FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public int inboxLineStep() {
        return storedOrLegacy(KEY_INBOX_LINE_STEP, KEY_LINE_STEP, DEFAULT_LINE_STEP,
                LINE_STEP_MIN, LINE_STEP_MAX);
    }

    public int inboxSpacingStep() {
        return clamp(preferences.getInt(KEY_INBOX_SPACING_STEP, DEFAULT_INBOX_SPACING_STEP),
                ITEM_SPACING_STEP_MIN, ITEM_SPACING_STEP_MAX);
    }

    public int conversationFontStep() {
        return storedOrLegacy(KEY_CONVERSATION_FONT_STEP, KEY_FONT_STEP, DEFAULT_FONT_STEP,
                FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public int conversationLineStep() {
        return storedOrLegacy(KEY_CONVERSATION_LINE_STEP, KEY_LINE_STEP, DEFAULT_LINE_STEP,
                LINE_STEP_MIN, LINE_STEP_MAX);
    }

    public int bubbleSpacingStep() {
        return clamp(preferences.getInt(KEY_BUBBLE_SPACING_STEP, DEFAULT_BUBBLE_SPACING_STEP),
                BUBBLE_SPACING_STEP_MIN, BUBBLE_SPACING_STEP_MAX);
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

    public void setInboxFontStep(int value) {
        put(KEY_INBOX_FONT_STEP, value, FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public void setInboxLineStep(int value) {
        put(KEY_INBOX_LINE_STEP, value, LINE_STEP_MIN, LINE_STEP_MAX);
    }

    public void setInboxSpacingStep(int value) {
        put(KEY_INBOX_SPACING_STEP, value, ITEM_SPACING_STEP_MIN, ITEM_SPACING_STEP_MAX);
    }

    public void setConversationFontStep(int value) {
        put(KEY_CONVERSATION_FONT_STEP, value, FONT_STEP_MIN, FONT_STEP_MAX);
    }

    public void setConversationLineStep(int value) {
        put(KEY_CONVERSATION_LINE_STEP, value, LINE_STEP_MIN, LINE_STEP_MAX);
    }

    public void setBubbleSpacingStep(int value) {
        put(KEY_BUBBLE_SPACING_STEP, value,
                BUBBLE_SPACING_STEP_MIN, BUBBLE_SPACING_STEP_MAX);
    }

    public void reset() {
        preferences.edit()
                .putInt(KEY_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_LINE_STEP, DEFAULT_LINE_STEP)
                .putInt(KEY_INBOX_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_INBOX_LINE_STEP, DEFAULT_LINE_STEP)
                .putInt(KEY_INBOX_SPACING_STEP, DEFAULT_INBOX_SPACING_STEP)
                .putInt(KEY_CONVERSATION_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_CONVERSATION_LINE_STEP, DEFAULT_LINE_STEP)
                .putInt(KEY_BUBBLE_SPACING_STEP, DEFAULT_BUBBLE_SPACING_STEP)
                .apply();
    }

    public void resetInbox() {
        preferences.edit()
                .putInt(KEY_INBOX_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_INBOX_LINE_STEP, DEFAULT_LINE_STEP)
                .putInt(KEY_INBOX_SPACING_STEP, DEFAULT_INBOX_SPACING_STEP)
                .apply();
    }

    public void resetConversation() {
        preferences.edit()
                .putInt(KEY_CONVERSATION_FONT_STEP, DEFAULT_FONT_STEP)
                .putInt(KEY_CONVERSATION_LINE_STEP, DEFAULT_LINE_STEP)
                .putInt(KEY_BUBBLE_SPACING_STEP, DEFAULT_BUBBLE_SPACING_STEP)
                .apply();
    }

    public float scaledSp(float baseSp) {
        return baseSp * fontScaleForStep(fontStep());
    }

    public float lineSpacingMultiplier() {
        return lineSpacingForStep(lineStep());
    }

    public float inboxScaledSp(float baseSp) {
        return baseSp * fontScaleForStep(inboxFontStep());
    }

    public float conversationScaledSp(float baseSp) {
        return baseSp * fontScaleForStep(conversationFontStep());
    }

    public float inboxLineSpacingMultiplier() {
        return lineSpacingForStep(inboxLineStep());
    }

    public float conversationLineSpacingMultiplier() {
        return lineSpacingForStep(conversationLineStep());
    }

    public int inboxItemVerticalPaddingDp() {
        return inboxPaddingForStep(inboxSpacingStep());
    }

    public int bubbleVerticalMarginDp() {
        return bubbleMarginForStep(bubbleSpacingStep());
    }

    public String signature() {
        return inboxFontStep() + ":" + inboxLineStep() + ":" + inboxSpacingStep()
                + ":" + conversationFontStep() + ":" + conversationLineStep()
                + ":" + bubbleSpacingStep();
    }

    public static float fontScaleForStep(int step) {
        return 1f + clamp(step, FONT_STEP_MIN, FONT_STEP_MAX) * 0.05f;
    }

    public static float lineSpacingForStep(int step) {
        return 1f + clamp(step, LINE_STEP_MIN, LINE_STEP_MAX) * 0.05f;
    }

    public static int inboxPaddingForStep(int step) {
        return 4 + clamp(step, ITEM_SPACING_STEP_MIN, ITEM_SPACING_STEP_MAX) * 2;
    }

    public static int bubbleMarginForStep(int step) {
        return 1 + clamp(step, BUBBLE_SPACING_STEP_MIN, BUBBLE_SPACING_STEP_MAX) * 2;
    }

    private int storedOrLegacy(String key, String legacyKey, int fallback,
            int minimum, int maximum) {
        int value = preferences.contains(key)
                ? preferences.getInt(key, fallback) : preferences.getInt(legacyKey, fallback);
        return clamp(value, minimum, maximum);
    }

    private void put(String key, int value, int minimum, int maximum) {
        preferences.edit().putInt(key, clamp(value, minimum, maximum)).apply();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
