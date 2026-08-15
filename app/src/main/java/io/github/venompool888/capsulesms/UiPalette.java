package io.github.venompool888.capsulesms;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.WindowInsetsController;

/** Semantic colors shared by the programmatically-built screens. */
public final class UiPalette {
    public final boolean dark;
    public final int background;
    public final int surface;
    public final int surfaceMuted;
    public final int surfaceSelected;
    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;
    public final int accent;
    public final int accentText;
    public final int accentSurface;
    public final int violet;
    public final int violetText;
    public final int violetSurface;
    public final int danger;
    public final int dangerSurface;
    public final int messageIncoming;
    public final int verificationText;
    public final int verificationSurface;
    public final int scrollThumb;

    private UiPalette(boolean dark) {
        this.dark = dark;
        background = color(dark, 0xFF0E1218, 0xFFFBFCFF);
        surface = color(dark, 0xFF181E27, 0xFFFFFFFF);
        surfaceMuted = color(dark, 0xFF202833, 0xFFEEF3F8);
        surfaceSelected = color(dark, 0xFF1D304A, 0xFFE7F0FF);
        textPrimary = color(dark, 0xFFE7EDF5, 0xFF293948);
        textSecondary = color(dark, 0xFFB8C3CF, 0xFF5B6A78);
        textMuted = color(dark, 0xFF8997A6, 0xFF7B8A97);
        accent = color(dark, 0xFF356FCE, 0xFF2169FF);
        accentText = color(dark, 0xFF8CB7FF, 0xFF2169FF);
        accentSurface = color(dark, 0xFF18355B, 0xFFDBE8FF);
        violet = color(dark, 0xFF6847BD, 0xFF704CCA);
        violetText = color(dark, 0xFFC4B5FD, 0xFF704CCA);
        violetSurface = color(dark, 0xFF30264A, 0xFFECE2FF);
        danger = color(dark, 0xFFFF8791, 0xFFDC414C);
        dangerSurface = color(dark, 0xFF4A2229, 0xFFFFEBEE);
        messageIncoming = color(dark, 0xFF222B36, 0xFFEBF0F6);
        verificationText = color(dark, 0xFF9BC4FF, 0xFF194F9E);
        verificationSurface = color(dark, 0xFF172F4D, 0xFFECF4FF);
        scrollThumb = color(dark, 0x887C8997, 0x684E5C6A);
    }

    public static UiPalette from(Activity activity) {
        int night = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return new UiPalette(night == Configuration.UI_MODE_NIGHT_YES);
    }

    public void applySystemBars(Activity activity) {
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        // Ensure the decor exists before requesting its insets controller on a cold launch.
        WindowInsetsController controller = activity.getWindow().getDecorView()
                .getWindowInsetsController();
        if (controller == null) return;
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
    }

    private static int color(boolean dark, int darkColor, int lightColor) {
        return dark ? darkColor : lightColor;
    }

    public int avatar(int index) {
        int[] light = {
                Color.rgb(255, 101, 111), Color.rgb(255, 139, 93),
                Color.rgb(29, 198, 180), Color.rgb(102, 199, 100),
                Color.rgb(248, 180, 38), Color.rgb(75, 120, 245),
                Color.rgb(130, 88, 230)
        };
        int[] darkColors = {
                0xFFB94F5A, 0xFFB8663F, 0xFF178D83, 0xFF4D914C,
                0xFFAD7E18, 0xFF426BC1, 0xFF7652BF
        };
        int[] colors = dark ? darkColors : light;
        return colors[(index & 0x7fffffff) % colors.length];
    }
}
