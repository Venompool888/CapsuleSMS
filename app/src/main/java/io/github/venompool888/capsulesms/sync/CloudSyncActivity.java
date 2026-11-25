package io.github.venompool888.capsulesms.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import io.github.venompool888.capsulesms.UiPalette;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudSyncActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> pushTo = new HashSet<>();
    private final Set<String> receiveFrom = new HashSet<>();
    private final List<View> connectControls = new ArrayList<>();
    private final List<Switch> routeSwitches = new ArrayList<>();
    private final List<TextView> routeErrors = new ArrayList<>();

    private UiPalette palette;
    private LinearLayout content;
    private ScrollView scrollView;
    private ProgressBar progress;
    private int activeOperations;

    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText enrollmentInput;
    private Button connectButton;
    private TextView formError;
    private boolean useEnrollmentCode = true;
    private boolean connectBusy;

    private CloudSyncEngine.DeviceState lastState;
    private Exception lastError;
    private View statusDot;
    private TextView statusText;
    private TextView statusHint;
    private Button syncButton;
    private Button refreshButton;
    private Button disconnectButton;
    private boolean requestInFlight;
    private boolean savingRoutes;
    private boolean bindingSwitch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = UiPalette.from(this);
        palette.applySystemBars(this);
        renderShell();
        renderCurrentState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (new CloudSyncConfig(this).isConnected()) refreshDevices(false);
    }

    private void renderShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToPadding(false);
        root.setBackgroundColor(palette.background);

        TextView title = text("‹  云备份与同步", 24, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(56));
        title.setOnClickListener(v -> finish());
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        if (progress.getIndeterminateDrawable() != null) {
            progress.getIndeterminateDrawable().setTint(palette.accent);
        }
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(28));
        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        applyWindowInsets(root);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void applyWindowInsets(View root) {
        final int horizontal = dp(18);
        final int top = dp(8);
        final int bottom = dp(8);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
            int bottomInset = keyboardVisible
                    ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
            view.setPadding(
                    horizontal + bars.left,
                    top + bars.top,
                    horizontal + bars.right,
                    bottom + bottomInset);
            return insets;
        });
        root.setPadding(horizontal, top, horizontal, bottom);
    }

    private void renderCurrentState() {
        if (new CloudSyncConfig(this).isConnected()) {
            lastError = null;
            refreshDevices(false);
        } else {
            renderConnectForm();
        }
    }

    private void renderConnectForm() {
        content.removeAllViews();
        resetTransientState();
        lastState = null;
        statusDot = null;
        statusText = null;
        statusHint = null;
        syncButton = null;
        refreshButton = null;
        disconnectButton = null;
        routeSwitches.clear();
        routeErrors.clear();
        connectControls.clear();

        String server = currentText(serverInput);
        String username = currentText(usernameInput);
        String password = currentText(passwordInput);
        String code = currentText(enrollmentInput);

        TextView intro = text("同一账号下的设备会通过你的服务器同步短信。", 13, palette.textMuted);
        intro.setLineSpacing(0, 1.2f);
        intro.setPadding(dp(4), 0, dp(4), dp(2));
        content.addView(intro);

        addSectionTitle("连接服务器");
        LinearLayout card = card();

        card.addView(fieldLabel("服务器地址"));
        serverInput = input("https://sms.example.com", false);
        serverInput.setText(server);
        card.addView(serverInput, inputParams());
        connectControls.add(serverInput);

        card.addView(fieldLabel("连接方式"), topParams(16));
        card.addView(modeSelector(), heightParams(42, 8));

        if (useEnrollmentCode) {
            card.addView(fieldLabel("注册码"), topParams(16));
            LinearLayout codeRow = row();
            enrollmentInput = input("例如 ABCD-EFGH", false);
            enrollmentInput.setText(code);
            codeRow.addView(enrollmentInput, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            Button scanButton = secondaryButton("扫描二维码");
            scanButton.setTextSize(13);
            scanButton.setOnClickListener(v -> scanEnrollment());
            LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            scanParams.setMargins(dp(8), 0, 0, 0);
            codeRow.addView(scanButton, scanParams);
            card.addView(codeRow, heightParams(48, 8));
            connectControls.add(enrollmentInput);
            connectControls.add(scanButton);
        } else {
            card.addView(fieldLabel("用户名"), topParams(16));
            usernameInput = input("手机号或用户名", false);
            usernameInput.setText(username);
            card.addView(usernameInput, inputParams());
            connectControls.add(usernameInput);

            card.addView(fieldLabel("密码"), topParams(14));
            passwordInput = input("密码", true);
            passwordInput.setText(password);
            card.addView(passwordInput, inputParams());
            connectControls.add(passwordInput);
        }

        connectButton = primaryButton("连接");
        connectButton.setOnClickListener(v -> connectRequested());
        card.addView(connectButton, heightParams(50, 20));

        content.addView(card, fieldParams());

        formError = errorBanner();
        content.addView(formError, fieldParams());

        TextView note = text("HTTP 连接会明文传输登录凭据，建议使用 HTTPS。", 12,
                palette.textMuted);
        note.setPadding(dp(4), dp(2), dp(4), 0);
        content.addView(note);
    }

    private LinearLayout modeSelector() {
        LinearLayout box = row();
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(rounded(palette.surfaceMuted, 14));

        TextView codeTab = modeTab("注册码", useEnrollmentCode);
        TextView accountTab = modeTab("账号密码", !useEnrollmentCode);
        codeTab.setOnClickListener(v -> {
            if (connectBusy || useEnrollmentCode) return;
            useEnrollmentCode = true;
            renderConnectForm();
        });
        accountTab.setOnClickListener(v -> {
            if (connectBusy || !useEnrollmentCode) return;
            useEnrollmentCode = false;
            renderConnectForm();
        });
        box.addView(codeTab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        accountParams.setMargins(dp(4), 0, 0, 0);
        box.addView(accountTab, accountParams);
        connectControls.add(codeTab);
        connectControls.add(accountTab);
        return box;
    }

    private TextView modeTab(String label, boolean selected) {
        TextView tab = text(label, 14,
                selected ? Color.WHITE : palette.textSecondary);
        tab.setGravity(Gravity.CENTER);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setBackground(rounded(selected ? palette.accent : Color.TRANSPARENT, 11));
        tab.setContentDescription(label + "连接方式");
        return tab;
    }

    private void connectRequested() {
        String server;
        try {
            server = CloudSyncApi.normalizeServer(serverInput.getText().toString());
        } catch (Exception error) {
            showFormError(error);
            return;
        }
        if (server.regionMatches(true, 0, "http://", 0, 7)) {
            new AlertDialog.Builder(this)
                    .setTitle("使用未加密连接？")
                    .setMessage("HTTP 会让网络中间人看到用户名、密码和同步元数据。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续", (dialog, which) -> performConnect(server))
                    .show();
        } else {
            performConnect(server);
        }
    }

    private void performConnect(String server) {
        if (connectBusy) return;
        String username = useEnrollmentCode ? "" : currentText(usernameInput).trim();
        String password = useEnrollmentCode ? "" : currentText(passwordInput);
        String code = useEnrollmentCode ? currentText(enrollmentInput).trim() : "";
        if (useEnrollmentCode && code.isEmpty()) {
            showFormError("请填写注册码，或改用账号密码。");
            return;
        }
        if (!useEnrollmentCode && (username.isEmpty() || password.isEmpty())) {
            showFormError("请输入用户名和密码。");
            return;
        }
        setConnectBusy(true);
        beginOperation();
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.connect(
                        this, server, username, password, code);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setConnectBusy(false);
                    endOperation();
                    if (passwordInput != null) passwordInput.setText("");
                    lastError = null;
                    renderDevices(state);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setConnectBusy(false);
                    endOperation();
                    if (new CloudSyncConfig(this).isConnected()) {
                        if (passwordInput != null) passwordInput.setText("");
                        renderLoadError(error);
                    } else {
                        showFormError(error);
                    }
                });
            }
        });
    }

    private void setConnectBusy(boolean busy) {
        connectBusy = busy;
        if (connectButton != null) {
            connectButton.setEnabled(!busy);
            connectButton.setText(busy ? "正在连接…" : "连接");
            connectButton.setAlpha(busy ? 0.6f : 1f);
        }
        for (View control : connectControls) control.setEnabled(!busy);
        if (busy && formError != null) formError.setVisibility(View.GONE);
    }

    private void scanEnrollment() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> applyEnrollmentUri(barcode.getRawValue()))
                .addOnFailureListener(this::showFormError);
    }

    private void applyEnrollmentUri(String raw) {
        if (isFinishing() || isDestroyed() || !useEnrollmentCode || connectBusy) return;
        try {
            Uri uri = Uri.parse(raw == null ? "" : raw);
            if (!"capsulesms".equalsIgnoreCase(uri.getScheme())
                    || !"enroll".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("这不是胶囊短信设备二维码");
            }
            String server = uri.getQueryParameter("server");
            String code = uri.getQueryParameter("code");
            if (server == null || code == null) {
                throw new IllegalArgumentException("二维码缺少服务器或注册码");
            }
            serverInput.setText(CloudSyncApi.normalizeServer(server));
            enrollmentInput.setText(code);
            Toast.makeText(this, "已读取设备注册信息", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            showFormError(error);
        }
    }

    private void refreshDevices(boolean toastOnSuccess) {
        if (!new CloudSyncConfig(this).isConnected()) {
            renderConnectForm();
            return;
        }
        if (requestInFlight || savingRoutes) return;
        requestInFlight = true;
        boolean initialLoad = lastState == null;
        if (initialLoad) {
            renderConnectedLoading();
        } else {
            disableFooter("立即同步", "正在刷新…");
            setRouteSwitchesEnabled(false);
        }
        beginOperation();
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.getDeviceState(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    requestInFlight = false;
                    endOperation();
                    if (!new CloudSyncConfig(this).isConnected()) return;
                    lastError = null;
                    renderDevices(state);
                    if (toastOnSuccess) {
                        Toast.makeText(this, "设备列表已刷新", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    requestInFlight = false;
                    endOperation();
                    if (!new CloudSyncConfig(this).isConnected()) return;
                    lastError = error;
                    if (lastState == null) {
                        renderLoadError(error);
                    } else {
                        enableFooter();
                        setRouteSwitchesEnabled(true);
                        applyStatus();
                        scrollToTop();
                        Toast.makeText(this, "刷新失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void renderConnectedLoading() {
        content.removeAllViews();
        statusDot = null;
        statusText = null;
        statusHint = null;
        syncButton = null;
        refreshButton = null;
        disconnectButton = null;
        routeSwitches.clear();
        routeErrors.clear();
        addSectionTitle("连接状态");
        LinearLayout card = card();
        TextView loading = text("正在读取设备…", 15, palette.textPrimary);
        loading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(loading);
        TextView hint = text("正在获取账号和同步设备列表。", 12, palette.textMuted);
        hint.setPadding(0, dp(6), 0, 0);
        card.addView(hint);
        content.addView(card, fieldParams());
    }

    private void renderLoadError(Exception error) {
        content.removeAllViews();
        statusDot = null;
        statusText = null;
        statusHint = null;
        syncButton = null;
        refreshButton = null;
        routeSwitches.clear();
        routeErrors.clear();
        addSectionTitle("连接状态");
        LinearLayout card = card();
        LinearLayout heading = row();
        heading.addView(dot(palette.danger), new LinearLayout.LayoutParams(
                dp(10), dp(10)));
        TextView title = text("无法读取设备列表", 16, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        heading.addView(title);
        card.addView(heading);

        TextView message = text("请检查网络或服务器状态后重试。\n" + errorMessage(error), 13,
                palette.textSecondary);
        message.setLineSpacing(0, 1.3f);
        message.setPadding(0, dp(10), 0, 0);
        card.addView(message);

        CloudSyncConfig config = new CloudSyncConfig(this);
        card.addView(detailRow("账号", valueOrDash(config.account())), topParams(10));
        card.addView(detailRow("服务器", hostOf(config.server())));

        Button retry = primaryButton("重试");
        retry.setOnClickListener(v -> refreshDevices(false));
        card.addView(retry, heightParams(46, 16));
        content.addView(card, fieldParams());

        addDisconnectButton();
    }

    private void renderDevices(CloudSyncEngine.DeviceState state) {
        lastState = state;
        lastError = null;
        pushTo.clear();
        pushTo.addAll(state.pushTo);
        receiveFrom.clear();
        receiveFrom.addAll(state.receiveFrom);
        content.removeAllViews();
        statusDot = null;
        statusText = null;
        statusHint = null;
        syncButton = null;
        refreshButton = null;
        routeSwitches.clear();
        routeErrors.clear();

        addSectionTitle("连接状态");
        content.addView(statusCard(state), fieldParams());

        addSectionTitle("同步设备");
        TextView hint = text("开关改动会立即保存。", 12, palette.textMuted);
        hint.setPadding(dp(4), 0, dp(4), dp(6));
        content.addView(hint);

        int otherCount = 0;
        for (CloudSyncEngine.Device device : state.devices) {
            if (device.id.equals(state.currentDeviceId)) continue;
            otherCount++;
            content.addView(deviceCard(device), fieldParams());
        }
        if (otherCount == 0) {
            LinearLayout emptyCard = card();
            TextView empty = text(
                    "还没有其他设备。在另一台手机上用同一账号连接后，它会出现在这里。",
                    14, palette.textMuted);
            empty.setLineSpacing(0, 1.35f);
            emptyCard.addView(empty);
            content.addView(emptyCard, fieldParams());
        }

        LinearLayout actions = row();
        syncButton = secondaryButton("立即同步");
        syncButton.setOnClickListener(v -> syncNow());
        refreshButton = secondaryButton("刷新设备");
        refreshButton.setOnClickListener(v -> refreshDevices(true));
        actions.addView(syncButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        refreshParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(refreshButton, refreshParams);
        content.addView(actions, heightParams(46, 18));

        addDisconnectButton();

        applyStatus();
    }

    private LinearLayout statusCard(CloudSyncEngine.DeviceState state) {
        LinearLayout card = card();
        LinearLayout statusRow = row();
        statusDot = new View(this);
        statusRow.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusText = text("", 16, palette.textPrimary);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusText.setMaxLines(3);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(statusText);
        card.addView(statusRow);

        statusHint = text("", 12, palette.textMuted);
        statusHint.setLineSpacing(0, 1.2f);
        statusHint.setPadding(dp(18), dp(4), 0, 0);
        card.addView(statusHint);

        card.addView(divider(), heightParams(1, 10));
        card.addView(detailRow("账号", valueOrDash(state.account)), topParams(8));
        card.addView(detailRow("服务器", hostOf(new CloudSyncConfig(this).server())));
        card.addView(detailRow("本机", currentDeviceName(state)));
        return card;
    }

    private void applyStatus() {
        if (statusDot == null || statusText == null || statusHint == null) return;
        int color;
        String label;
        String hint;
        String syncError = new CloudSyncConfig(this).lastError();
        boolean hasError = lastError != null || !syncError.isBlank();
        if (hasError) {
            color = palette.danger;
            label = lastError != null ? errorMessage(lastError) : syncError;
            hint = lastError != null ? "可点“刷新设备”重试。" : "";
        } else if (pushTo.isEmpty() && receiveFrom.isEmpty()) {
            color = warningColor();
            label = "未设置同步设备";
            hint = "打开下方开关以设置短信同步。";
        } else {
            color = successColor();
            label = "已设置同步设备";
            hint = "新短信会按下方设置同步。";
        }
        statusDot.setBackground(dotDrawable(color));
        statusText.setText(label);
        statusText.setTextColor(hasError ? palette.danger : palette.textPrimary);
        statusHint.setText(hint);
    }

    private LinearLayout deviceCard(CloudSyncEngine.Device device) {
        boolean fluid = device.isReceiveOnly();
        LinearLayout card = card();

        TextView name = text(device.name, 16, palette.textPrimary);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        LinearLayout meta = row();
        meta.setPadding(0, dp(8), 0, dp(10));
        TextView platform = text(fluid ? "流体胶囊（通知）" : platformLabel(device.platform), 11,
                fluid ? palette.violetText : palette.accentText);
        platform.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        platform.setBackground(rounded(
                fluid ? palette.violetSurface : palette.accentSurface, 8));
        platform.setPadding(dp(8), dp(3), dp(8), dp(3));
        meta.addView(platform);
        TextView seen = text("最后在线：" + relativeTime(device.lastSeenAt), 12,
                palette.textMuted);
        seen.setGravity(Gravity.END);
        meta.addView(seen, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(meta);

        card.addView(divider(), heightParams(1, 0));
        TextView inlineError = text("", 12, palette.danger);
        inlineError.setLineSpacing(0, 1.2f);
        inlineError.setPadding(0, dp(8), 0, 0);
        inlineError.setVisibility(View.GONE);
        routeErrors.add(inlineError);

        if (fluid) {
            addSwitchRow(card, device.id, true,
                    "推送收到的短信到流体胶囊。开启后，请在流体胶囊的同步页面刷新设备。",
                    pushTo.contains(device.id), inlineError);
        } else {
            addSwitchRow(card, device.id, true,
                    "把我的短信推送到这台设备",
                    pushTo.contains(device.id), inlineError);
            addSwitchRow(card, device.id, false,
                    "接收这台设备的短信",
                    receiveFrom.contains(device.id), inlineError);
        }
        card.addView(inlineError);
        return card;
    }

    private void addSwitchRow(
            LinearLayout card, String deviceId, boolean push, String label,
            boolean checked, TextView inlineError) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(4), 0, dp(2));
        TextView title = text(label, 14, palette.textPrimary);
        title.setLineSpacing(0, 1.15f);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(checked);
        toggle.setContentDescription(label);
        toggle.setOnCheckedChangeListener((button, value) -> {
            if (bindingSwitch) return;
            toggleRoute(deviceId, push, value, button, inlineError);
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(dp(12), 0, 0, 0);
        row.addView(toggle, toggleParams);
        card.addView(row);
        routeSwitches.add(toggle);
    }

    private void toggleRoute(
            String deviceId, boolean push, boolean checked,
            CompoundButton button, TextView inlineError) {
        if (savingRoutes || requestInFlight || connectBusy) {
            setSwitchSilently(button, !checked);
            return;
        }
        boolean previous = push ? pushTo.contains(deviceId) : receiveFrom.contains(deviceId);
        savingRoutes = true;
        for (TextView error : routeErrors) error.setVisibility(View.GONE);
        setRouteSwitchesEnabled(false);
        disableFooter("立即同步", "刷新设备");
        beginOperation();
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.updateRoute(
                        this, deviceId, push, checked);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    savingRoutes = false;
                    endOperation();
                    if (!new CloudSyncConfig(this).isConnected()) return;
                    renderDevices(state);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    savingRoutes = false;
                    endOperation();
                    setSwitchSilently(button, previous);
                    setRouteSwitchesEnabled(true);
                    enableFooter();
                    inlineError.setText(errorMessage(error));
                    inlineError.setVisibility(View.VISIBLE);
                    lastError = error;
                    applyStatus();
                });
            }
        });
    }

    private void setRouteSwitchesEnabled(boolean enabled) {
        for (Switch toggle : routeSwitches) toggle.setEnabled(enabled);
    }

    private void setSwitchSilently(CompoundButton button, boolean checked) {
        bindingSwitch = true;
        try {
            button.setChecked(checked);
        } finally {
            bindingSwitch = false;
        }
    }

    private void syncNow() {
        if (requestInFlight || savingRoutes || lastState == null) return;
        requestInFlight = true;
        disableFooter("正在同步…", "刷新设备");
        setRouteSwitchesEnabled(false);
        beginOperation();
        CloudSyncEngine.syncAsync(this, (count, error) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            requestInFlight = false;
            endOperation();
            if (!new CloudSyncConfig(this).isConnected()) return;
            enableFooter();
            setRouteSwitchesEnabled(true);
            if (error != null) {
                lastError = error;
                applyStatus();
                scrollToTop();
                Toast.makeText(this, "同步失败", Toast.LENGTH_SHORT).show();
            } else {
                lastError = null;
                applyStatus();
                Toast.makeText(this,
                        count == 0 ? "没有新短信" : "已同步 " + count + " 条短信",
                        Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void addDisconnectButton() {
        disconnectButton = destructiveButton("断开本机连接");
        disconnectButton.setOnClickListener(v -> confirmDisconnect());
        content.addView(disconnectButton, heightParams(48, 10));
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("断开本机连接？")
                .setMessage("本机会停止云同步，并从服务器撤销这台设备。以后重新连接需要新的注册码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("断开", (dialog, which) -> {
                    if (requestInFlight || savingRoutes) return;
                    requestInFlight = true;
                    disableFooter("立即同步", "刷新设备");
                    if (disconnectButton != null) disconnectButton.setText("正在断开…");
                    beginOperation();
                    io.execute(() -> {
                        boolean revoked = CloudSyncEngine.disconnect(this);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            requestInFlight = false;
                            endOperation();
                            useEnrollmentCode = true;
                            lastState = null;
                            lastError = null;
                            pushTo.clear();
                            receiveFrom.clear();
                            renderConnectForm();
                            if (!revoked) {
                                Toast.makeText(this, "已在本机断开，但没能通知服务器。可在网页管理中撤销这台设备。",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .show();
    }

    private void disableFooter(String syncLabel, String refreshLabel) {
        if (disconnectButton != null) disconnectButton.setEnabled(false);
        if (syncButton != null) {
            syncButton.setEnabled(false);
            syncButton.setText(syncLabel);
            syncButton.setAlpha(0.6f);
        }
        if (refreshButton != null) {
            refreshButton.setEnabled(false);
            refreshButton.setText(refreshLabel);
            refreshButton.setAlpha(0.6f);
        }
    }

    private void enableFooter() {
        if (disconnectButton != null) disconnectButton.setEnabled(true);
        if (syncButton != null) {
            syncButton.setEnabled(true);
            syncButton.setText("立即同步");
            syncButton.setAlpha(1f);
        }
        if (refreshButton != null) {
            refreshButton.setEnabled(true);
            refreshButton.setText("刷新设备");
            refreshButton.setAlpha(1f);
        }
    }

    private void resetTransientState() {
        connectBusy = false;
        requestInFlight = false;
        savingRoutes = false;
        activeOperations = 0;
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void beginOperation() {
        activeOperations++;
        progress.setVisibility(View.VISIBLE);
    }

    private void endOperation() {
        if (activeOperations > 0) activeOperations--;
        if (activeOperations == 0) progress.setVisibility(View.GONE);
    }

    private void scrollToTop() {
        if (scrollView != null) scrollView.smoothScrollTo(0, 0);
    }

    private String currentDeviceName(CloudSyncEngine.DeviceState state) {
        for (CloudSyncEngine.Device device : state.devices) {
            if (device.id.equals(state.currentDeviceId)) return device.name;
        }
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    private String platformLabel(String platform) {
        if (platform == null || platform.isBlank()) return "未知平台";
        if (platform.startsWith("android-")) {
            String version = platform.substring("android-".length()).trim();
            return version.isEmpty() ? "Android" : "Android " + version;
        }
        return platform;
    }

    private String relativeTime(long lastSeenAt) {
        if (lastSeenAt <= 0) return "尚未在线";
        long elapsed = System.currentTimeMillis() - lastSeenAt;
        if (elapsed < 0) elapsed = 0;
        if (elapsed < 60_000L) return "刚刚";
        if (elapsed < 3_600_000L) return (elapsed / 60_000L) + " 分钟前";
        if (elapsed < 86_400_000L) return (elapsed / 3_600_000L) + " 小时前";
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(lastSeenAt));
    }

    private String hostOf(String server) {
        if (server == null || server.isBlank()) return "—";
        try {
            Uri uri = Uri.parse(server);
            String authority = uri.getAuthority();
            if (authority != null && !authority.isBlank()) return authority;
            String host = uri.getHost();
            if (host != null && !host.isBlank()) return host;
        } catch (Exception ignored) {}
        return server;
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String errorMessage(Exception error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message;
    }

    private void showFormError(Exception error) {
        showFormError(errorMessage(error));
    }

    private void showFormError(String message) {
        if (formError == null) return;
        formError.setText(message);
        formError.setVisibility(View.VISIBLE);
    }

    private LinearLayout detailRow(String label, String value) {
        LinearLayout row = row();
        row.setPadding(0, dp(4), 0, dp(2));
        TextView key = text(label, 13, palette.textMuted);
        key.setMinWidth(dp(56));
        TextView detail = text(value, 13, palette.textPrimary);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(key);
        row.addView(detail, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(palette.surfaceMuted);
        return divider;
    }

    private View dot(int color) {
        View dot = new View(this);
        dot.setBackground(dotDrawable(color));
        return dot;
    }

    private GradientDrawable dotDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int successColor() {
        return palette.dark ? 0xFF63D68C : 0xFF148A4B;
    }

    private int warningColor() {
        return palette.dark ? 0xFFE6B75B : 0xFFB26A00;
    }

    private void addSectionTitle(String value) {
        TextView title = text(value, 14, palette.textSecondary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(14), dp(4), dp(8));
        content.addView(title);
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 13, palette.textSecondary);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(2), 0, dp(2), dp(6));
        return label;
    }

    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(palette.textPrimary);
        field.setHintTextColor(palette.textMuted);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(palette.surfaceMuted, 14));
        field.setMinHeight(dp(48));
        if (password) field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private TextView errorBanner() {
        TextView banner = text("", 13, palette.danger);
        banner.setLineSpacing(0, 1.2f);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        banner.setBackground(rounded(palette.dangerSurface, 14));
        banner.setVisibility(View.GONE);
        return banner;
    }

    private LinearLayout card() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(16), dp(16), dp(16), dp(16));
        value.setBackground(rounded(palette.surface, 20));
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(rounded(palette.accent, 16));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(palette.textPrimary);
        button.setAllCaps(false);
        button.setBackground(rounded(palette.surfaceMuted, 16));
        return button;
    }

    private Button destructiveButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(palette.danger);
        button.setAllCaps(false);
        button.setBackground(rounded(palette.dangerSurface, 16));
        return button;
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

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private LinearLayout.LayoutParams inputParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    }

    private LinearLayout.LayoutParams topParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams heightParams(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    private String currentText(EditText field) {
        return field == null ? "" : field.getText().toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

}
