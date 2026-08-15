package io.github.venompool888.capsulesms.sync;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.venompool888.capsulesms.UiPalette;
import io.github.venompool888.capsulesms.TypographySettingsActivity;

public final class AdvancedSettingsActivity extends Activity {
    private UiPalette palette;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = UiPalette.from(this);
        palette.applySystemBars(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        root.setBackgroundColor(palette.background);

        TextView back = text("‹  高级设置", 25, palette.textPrimary);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView description = text("这些功能仅在你主动开启后运行。", 14,
                palette.textMuted);
        description.setPadding(dp(4), 0, dp(4), dp(18));
        root.addView(description);

        LinearLayout cloud = new LinearLayout(this);
        cloud.setOrientation(LinearLayout.VERTICAL);
        cloud.setPadding(dp(18), dp(16), dp(18), dp(16));
        cloud.setBackground(rounded(palette.surface, 22));
        TextView title = text("云备份与同步", 18, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = text("通过自建服务器在已授权设备间加密同步短信", 13,
                palette.textSecondary);
        subtitle.setPadding(0, dp(6), 0, 0);
        cloud.addView(title);
        cloud.addView(subtitle);
        cloud.setOnClickListener(v -> startActivity(new Intent(this, CloudSyncActivity.class)));
        root.addView(cloud, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout typography = new LinearLayout(this);
        typography.setOrientation(LinearLayout.VERTICAL);
        typography.setPadding(dp(18), dp(16), dp(18), dp(16));
        typography.setBackground(rounded(palette.surface, 22));
        TextView typographyTitle = text("显示与文字", 18, palette.textPrimary);
        typographyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView typographySubtitle = text("调节消息字号和行间距，支持实时预览", 13,
                palette.textSecondary);
        typographySubtitle.setPadding(0, dp(6), 0, 0);
        typography.addView(typographyTitle);
        typography.addView(typographySubtitle);
        typography.setOnClickListener(v -> startActivity(
                new Intent(this, TypographySettingsActivity.class)));
        LinearLayout.LayoutParams typographyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typographyParams.setMargins(0, dp(12), 0, 0);
        root.addView(typography, typographyParams);
        setContentView(root);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
