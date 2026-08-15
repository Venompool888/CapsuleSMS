package io.github.venompool888.capsulesms;

import android.content.Context;

/** Structural dimensions that adapt to the device's logical screen width. */
public final class LayoutProfile {
    public static final int COMPACT_WIDTH_DP = 380;

    public final boolean compact;
    public final int contentHorizontalPaddingDp;
    public final int searchHeightDp;
    public final int avatarSizeDp;
    public final int avatarTextSp;
    public final int copyStartPaddingDp;
    public final int copyEndPaddingDp;
    public final int metaWidthDp;
    public final int simHorizontalPaddingDp;
    public final int unreadDotSizeDp;

    private LayoutProfile(boolean compact) {
        this.compact = compact;
        contentHorizontalPaddingDp = compact ? 12 : 16;
        searchHeightDp = compact ? 44 : 46;
        avatarSizeDp = compact ? 46 : 52;
        avatarTextSp = compact ? 20 : 22;
        copyStartPaddingDp = compact ? 10 : 14;
        copyEndPaddingDp = compact ? 5 : 8;
        metaWidthDp = compact ? 62 : 72;
        simHorizontalPaddingDp = compact ? 4 : 6;
        unreadDotSizeDp = compact ? 11 : 13;
    }

    public static LayoutProfile from(Context context) {
        return forWidthDp(context.getResources().getConfiguration().screenWidthDp);
    }

    public static LayoutProfile forWidthDp(int widthDp) {
        return new LayoutProfile(widthDp > 0 && widthDp < COMPACT_WIDTH_DP);
    }

    public int inboxVerticalPaddingDp(int requestedDp) {
        return compact ? Math.max(4, requestedDp - 2) : requestedDp;
    }
}
