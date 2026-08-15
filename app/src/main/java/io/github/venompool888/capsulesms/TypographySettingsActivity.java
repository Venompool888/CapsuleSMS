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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public final class TypographySettingsActivity extends Activity {
    private UiPalette palette;
    private TypographyPreferences typography;
    private ScrollView settingsScroll;
    private LinearLayout pageContent;
    private TextView inboxTab;
    private TextView conversationTab;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = UiPalette.from(this);
        palette.applySystemBars(this);
        typography = new TypographyPreferences(this);

        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
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

        TextView description = text("分别调整消息列表和会话界面。系统“字体大小”仍然会在这些设置基础上生效。", 14,
                palette.textMuted);
        description.setLineSpacing(0, 1.25f);
        description.setPadding(dp(4), 0, dp(4), dp(16));
        root.addView(description);

        LinearLayout tabs = row();
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(rounded(palette.surfaceMuted, 20));
        inboxTab = tab("消息列表");
        conversationTab = tab("会话界面");
        tabs.addView(inboxTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        tabs.addView(conversationTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(tabs, matchWrap());

        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pageParams = matchWrap();
        pageParams.setMargins(0, dp(18), 0, 0);
        root.addView(pageContent, pageParams);

        inboxTab.setOnClickListener(v -> showPage(true));
        conversationTab.setOnClickListener(v -> showPage(false));
        showPage(true);
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private void showPage(boolean inbox) {
        styleTab(inboxTab, inbox);
        styleTab(conversationTab, !inbox);
        pageContent.removeAllViews();
        if (inbox) buildInboxPage(); else buildConversationPage();
        if (settingsScroll != null) settingsScroll.post(() -> settingsScroll.smoothScrollTo(0, 0));
    }

    private void buildInboxPage() {
        pageContent.addView(sectionTitle("消息列表预览"));
        LinearLayout preview = previewCard();
        List<InboxPreviewRow> rows = new ArrayList<>();
        rows.add(inboxPreviewRow("+61 403 661 662", "来自 OPPO C · 收到一条新短信", "12:25"));
        rows.add(inboxPreviewRow("CloudRefreshTest", "CloudRefreshProof_20260816", "周六"));
        rows.add(inboxPreviewRow("10086", "【到期提醒】尊敬的客户，您的套餐即将到期…", "周六"));
        for (InboxPreviewRow value : rows) preview.addView(value.root);
        pageContent.addView(preview, previewParams());

        Runnable render = () -> {
            int verticalPadding = typography.inboxItemVerticalPaddingDp();
            for (InboxPreviewRow value : rows) {
                setInboxTextSize(value.title, 16);
                setInboxTextSize(value.preview, 13);
                setInboxTextSize(value.time, 11);
                value.preview.setLineSpacing(0, typography.inboxLineSpacingMultiplier());
                value.root.setPadding(dp(2), dp(verticalPadding), dp(2), dp(verticalPadding));
            }
        };
        render.run();

        addSlider("列表文字大小",
                TypographyPreferences.FONT_STEP_MIN, TypographyPreferences.FONT_STEP_MAX,
                typography.inboxFontStep(), typography::setInboxFontStep,
                step -> percent(TypographyPreferences.fontScaleForStep(step)), render);
        addSlider("摘要行间距",
                TypographyPreferences.LINE_STEP_MIN, TypographyPreferences.LINE_STEP_MAX,
                typography.inboxLineStep(), typography::setInboxLineStep,
                step -> multiplier(TypographyPreferences.lineSpacingForStep(step)), render);
        addSlider("列表项间距",
                TypographyPreferences.ITEM_SPACING_STEP_MIN,
                TypographyPreferences.ITEM_SPACING_STEP_MAX,
                typography.inboxSpacingStep(), typography::setInboxSpacingStep,
                step -> densityLabel(step, 3), render);
        addResetButton("恢复列表默认设置", () -> {
            typography.resetInbox();
            showPage(true);
        });
    }

    private void buildConversationPage() {
        pageContent.addView(sectionTitle("会话界面预览"));
        LinearLayout preview = previewCard();
        TextView header = text("+61 403 661 662", 16, palette.textPrimary);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(dp(6), 0, dp(6), dp(10));
        preview.addView(header);

        List<BubblePreview> bubbles = new ArrayList<>();
        bubbles.add(bubblePreview("收到的短信\n第二行用来预览行距", false));
        bubbles.add(bubblePreview("好的，我收到了", true));
        bubbles.add(bubblePreview("这是另一条消息", false));
        bubbles.add(bubblePreview("字号、行距和气泡间距都可以单独调整", true));
        for (BubblePreview value : bubbles) preview.addView(value.line);
        pageContent.addView(preview, previewParams());

        Runnable render = () -> {
            setConversationTextSize(header, 16);
            int margin = typography.bubbleVerticalMarginDp();
            for (BubblePreview value : bubbles) {
                setConversationTextSize(value.bubble, 15);
                value.bubble.setLineSpacing(0, typography.conversationLineSpacingMultiplier());
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
                        value.bubble.getLayoutParams();
                params.setMargins(0, dp(margin), 0, dp(margin));
                value.bubble.setLayoutParams(params);
            }
        };
        render.run();

        addSlider("气泡文字大小",
                TypographyPreferences.FONT_STEP_MIN, TypographyPreferences.FONT_STEP_MAX,
                typography.conversationFontStep(), typography::setConversationFontStep,
                step -> percent(TypographyPreferences.fontScaleForStep(step)), render);
        addSlider("气泡内行间距",
                TypographyPreferences.LINE_STEP_MIN, TypographyPreferences.LINE_STEP_MAX,
                typography.conversationLineStep(), typography::setConversationLineStep,
                step -> multiplier(TypographyPreferences.lineSpacingForStep(step)), render);
        addSlider("气泡之间的间距",
                TypographyPreferences.BUBBLE_SPACING_STEP_MIN,
                TypographyPreferences.BUBBLE_SPACING_STEP_MAX,
                typography.bubbleSpacingStep(), typography::setBubbleSpacingStep,
                step -> densityLabel(step, 2), render);
        addResetButton("恢复会话默认设置", () -> {
            typography.resetConversation();
            showPage(false);
        });
    }

    private InboxPreviewRow inboxPreviewRow(String titleValue, String previewValue, String timeValue) {
        LinearLayout root = row();
        root.setGravity(Gravity.TOP);
        TextView avatar = text(titleValue.substring(0, 1), 18, Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(palette.avatar(titleValue.hashCode()), 22));
        root.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(7), 0);
        TextView title = text(titleValue, 16, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        TextView message = text(previewValue, 13, palette.textMuted);
        message.setMaxLines(2);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title);
        copy.addView(message);
        root.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = text(timeValue, 11, palette.textMuted);
        time.setGravity(Gravity.END);
        root.addView(time, new LinearLayout.LayoutParams(dp(48),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new InboxPreviewRow(root, title, message, time);
    }

    private BubblePreview bubblePreview(String value, boolean outgoing) {
        LinearLayout line = row();
        line.setGravity(outgoing ? Gravity.END : Gravity.START);
        TextView bubble = text(value, 15, outgoing ? Color.WHITE : palette.textPrimary);
        bubble.setPadding(dp(14), dp(9), dp(14), dp(9));
        bubble.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.72f));
        bubble.setBackground(rounded(outgoing ? palette.accent : palette.messageIncoming, 18));
        line.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new BubblePreview(line, bubble);
    }

    private void addSlider(String label, int minimum, int maximum, int current,
            IntConsumer consumer, IntFunction<String> formatter, Runnable render) {
        LinearLayout header = row();
        TextView title = text(label, 16, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView value = text(formatter.apply(current), 14, palette.accentText);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(value);
        LinearLayout.LayoutParams headerParams = matchWrap();
        headerParams.setMargins(0, pageContent.getChildCount() > 2 ? dp(18) : 0, 0, 0);
        pageContent.addView(header, headerParams);

        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(current - minimum);
        seek.setMinHeight(dp(44));
        seek.setContentDescription(label);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int setting = progress + minimum;
                consumer.accept(setting);
                value.setText(formatter.apply(setting));
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        pageContent.addView(seek, matchWrap());
    }

    private void addResetButton(String label, Runnable reset) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(palette.accentText);
        button.setAllCaps(false);
        button.setBackground(rounded(palette.accentSurface, 18));
        button.setOnClickListener(v -> reset.run());
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(20), 0, 0);
        pageContent.addView(button, params);
    }

    private LinearLayout previewCard() {
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview.setBackground(rounded(palette.surface, 22));
        return preview;
    }

    private LinearLayout.LayoutParams previewParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(8), 0, dp(22));
        return params;
    }

    private TextView tab(String label) {
        TextView tab = text(label, 15, palette.textSecondary);
        tab.setGravity(Gravity.CENTER);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setContentDescription(label + "设置");
        return tab;
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : palette.textSecondary);
        tab.setBackground(rounded(selected ? palette.accent : Color.TRANSPARENT, 17));
        tab.setSelected(selected);
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 15, palette.textSecondary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return title;
    }

    private void setInboxTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, typography.inboxScaledSp(baseSp));
    }

    private void setConversationTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                typography.conversationScaledSp(baseSp));
    }

    private String percent(float value) {
        return Math.round(value * 100) + "%";
    }

    private String multiplier(float value) {
        return String.format(Locale.getDefault(), "%.2f×", value);
    }

    private String densityLabel(int step, int normal) {
        if (step == normal) return "标准";
        return step < normal ? "紧凑 " + (normal - step) : "宽松 " + (step - normal);
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
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

    private static final class InboxPreviewRow {
        final LinearLayout root;
        final TextView title;
        final TextView preview;
        final TextView time;

        InboxPreviewRow(LinearLayout root, TextView title, TextView preview, TextView time) {
            this.root = root;
            this.title = title;
            this.preview = preview;
            this.time = time;
        }
    }

    private static final class BubblePreview {
        final LinearLayout line;
        final TextView bubble;

        BubblePreview(LinearLayout line, TextView bubble) {
            this.line = line;
            this.bubble = bubble;
        }
    }
}
