package io.github.venompool888.capsulesms;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public final class TypographySettingsActivity extends Activity {
    private UiPalette palette;
    private TypographyPreferences typography;
    private TextView received;
    private TextView origin;
    private TextView sent;
    private TextView fontValue;
    private TextView lineValue;
    private SeekBar fontSeek;
    private SeekBar lineSeek;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = UiPalette.from(this);
        palette.applySystemBars(this);
        typography = new TypographyPreferences(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(28));
        root.setBackgroundColor(palette.background);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            root.setPadding(dp(20) + bars.left, dp(16) + bars.top,
                    dp(20) + bars.right, dp(28) + bars.bottom);
            return insets;
        });

        TextView back = text("‹  显示与文字", 25, palette.textPrimary);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(dp(56));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView description = text("系统“字体大小”仍然会生效，这里是在系统设置基础上调整消息的显示密度。", 14,
                palette.textMuted);
        description.setLineSpacing(0, 1.25f);
        description.setPadding(dp(4), 0, dp(4), dp(18));
        root.addView(description);

        root.addView(sectionTitle("消息预览"));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(14), dp(16), dp(14), dp(16));
        preview.setBackground(rounded(palette.surface, 22));

        received = text("这是收到的短信\n第二行用来预览行间距", 16, palette.textPrimary);
        received.setPadding(dp(16), dp(11), dp(16), dp(11));
        received.setBackground(rounded(palette.messageIncoming, 19));
        LinearLayout.LayoutParams receivedParams = wrap();
        receivedParams.gravity = Gravity.START;
        preview.addView(received, receivedParams);

        origin = text("来自 Google Pixel 10 · SIM 2", 11, palette.textMuted);
        origin.setPadding(dp(5), dp(3), dp(5), dp(14));
        preview.addView(origin, wrap());

        sent = text("这是发出的短信\n字号和行距会即时更新", 16, Color.WHITE);
        sent.setGravity(Gravity.START);
        sent.setPadding(dp(16), dp(11), dp(16), dp(11));
        sent.setBackground(rounded(palette.accent, 19));
        LinearLayout.LayoutParams sentParams = wrap();
        sentParams.gravity = Gravity.END;
        preview.addView(sent, sentParams);

        LinearLayout.LayoutParams previewParams = matchWrap();
        previewParams.setMargins(0, dp(8), 0, dp(22));
        root.addView(preview, previewParams);

        fontValue = valueLabel();
        root.addView(controlHeader("文字大小", fontValue));
        fontSeek = seekBar(TypographyPreferences.FONT_STEP_MAX
                - TypographyPreferences.FONT_STEP_MIN,
                typography.fontStep() - TypographyPreferences.FONT_STEP_MIN);
        fontSeek.setContentDescription("文字大小");
        root.addView(fontSeek, matchWrap());

        lineValue = valueLabel();
        LinearLayout.LayoutParams lineHeaderParams = matchWrap();
        lineHeaderParams.setMargins(0, dp(18), 0, 0);
        root.addView(controlHeader("行间距", lineValue), lineHeaderParams);
        lineSeek = seekBar(TypographyPreferences.LINE_STEP_MAX
                - TypographyPreferences.LINE_STEP_MIN,
                typography.lineStep() - TypographyPreferences.LINE_STEP_MIN);
        lineSeek.setContentDescription("行间距");
        root.addView(lineSeek, matchWrap());

        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.HORIZONTAL);
        TextView small = text("小", 12, palette.textMuted);
        TextView large = text("大", 12, palette.textMuted);
        large.setGravity(Gravity.END);
        scale.addView(small, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        scale.addView(large, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        scale.setPadding(dp(12), 0, dp(12), 0);
        root.addView(scale, matchWrap());

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setTextSize(14);
        reset.setTextColor(palette.accentText);
        reset.setAllCaps(false);
        reset.setBackground(rounded(palette.accentSurface, 18));
        reset.setOnClickListener(v -> {
            typography.reset();
            fontSeek.setProgress(0 - TypographyPreferences.FONT_STEP_MIN, true);
            lineSeek.setProgress(2 - TypographyPreferences.LINE_STEP_MIN, true);
            renderPreview();
        });
        LinearLayout.LayoutParams resetParams = matchWrap();
        resetParams.setMargins(0, dp(20), 0, 0);
        root.addView(reset, resetParams);

        fontSeek.setOnSeekBarChangeListener(listener(true));
        lineSeek.setOnSeekBarChangeListener(listener(false));
        renderPreview();
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private SeekBar.OnSeekBarChangeListener listener(boolean font) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (font) typography.setFontStep(
                        progress + TypographyPreferences.FONT_STEP_MIN);
                else typography.setLineStep(
                        progress + TypographyPreferences.LINE_STEP_MIN);
                renderPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private void renderPreview() {
        setMessageTextSize(received, 16);
        setMessageTextSize(sent, 16);
        setMessageTextSize(origin, 11);
        float spacing = typography.lineSpacingMultiplier();
        received.setLineSpacing(0, spacing);
        sent.setLineSpacing(0, spacing);
        fontValue.setText(Math.round(TypographyPreferences.fontScaleForStep(
                typography.fontStep()) * 100) + "%");
        lineValue.setText(String.format(Locale.getDefault(), "%.2f×", spacing));
    }

    private void setMessageTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, typography.scaledSp(baseSp));
    }

    private LinearLayout controlHeader(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 16, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value);
        return row;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 15, palette.textSecondary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return title;
    }

    private TextView valueLabel() {
        TextView value = text("", 14, palette.accentText);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    private SeekBar seekBar(int maximum, int progress) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum);
        seek.setProgress(progress);
        seek.setMinHeight(dp(44));
        return seek;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
