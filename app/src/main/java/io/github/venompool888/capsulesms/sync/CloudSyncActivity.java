package io.github.venompool888.capsulesms.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import io.github.venompool888.capsulesms.R;
import io.github.venompool888.capsulesms.UiPalette;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudSyncActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private UiPalette palette;
    private LinearLayout content;
    private ProgressBar progress;
    private TextView status;
    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText enrollmentInput;
    private final Set<String> pushTo = new HashSet<>();
    private final Set<String> receiveFrom = new HashSet<>();

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
        root.setBackgroundColor(palette.background);
        root.setPadding(dp(18), dp(24), dp(18), dp(18));

        TextView title = text("‹  云备份与同步", 24, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setOnClickListener(v -> finish());
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        status = text("", 13, palette.textSecondary);
        status.setPadding(dp(4), 0, dp(4), dp(10));
        root.addView(status);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void renderCurrentState() {
        if (new CloudSyncConfig(this).isConnected()) {
            renderConnectedLoading();
            refreshDevices(false);
        } else {
            renderConnectForm();
        }
    }

    private void renderConnectForm() {
        content.removeAllViews();
        status.setText("未连接");
        addSectionTitle("连接服务器");
        serverInput = input("服务器地址（https://…）", false);
        usernameInput = input("用户名", false);
        passwordInput = input("密码", true);
        enrollmentInput = input("注册码（可选，如 ABCD-EFGH）", false);
        content.addView(serverInput, fieldParams());
        content.addView(usernameInput, fieldParams());
        content.addView(passwordInput, fieldParams());
        content.addView(enrollmentInput, fieldParams());

        LinearLayout actions = row();
        Button scan = secondaryButton("扫描二维码");
        scan.setOnClickListener(v -> scanEnrollment());
        Button connect = primaryButton("连接");
        connect.setOnClickListener(v -> connectRequested());
        actions.addView(scan, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        connectParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(connect, connectParams);
        content.addView(actions, fieldParams());

        TextView note = text(
                "使用注册码时无需输入用户名和密码。HTTP 可用，但会暴露登录凭据，建议使用 HTTPS。",
                13, palette.textMuted);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(note);
    }

    private void connectRequested() {
        String server;
        try {
            server = CloudSyncApi.normalizeServer(serverInput.getText().toString());
        } catch (Exception error) {
            showError(error);
            return;
        }
        if (server.startsWith("http://")) {
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
        String username = usernameInput.getText().toString();
        String password = passwordInput.getText().toString();
        String code = enrollmentInput.getText().toString();
        if (code.isBlank() && (username.isBlank() || password.isBlank())) {
            showError(new IllegalArgumentException("请输入用户名和密码，或填写注册码"));
            return;
        }
        setBusy(true, "正在安全连接…");
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.connect(
                        this, server, username, password, code);
                runOnUiThread(() -> {
                    setBusy(false, "已连接");
                    passwordInput.setText("");
                    renderDevices(state);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "连接失败");
                    showError(error);
                });
            }
        });
    }

    private void scanEnrollment() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> applyEnrollmentUri(barcode.getRawValue()))
                .addOnFailureListener(this::showError);
    }

    private void applyEnrollmentUri(String raw) {
        try {
            Uri uri = Uri.parse(raw == null ? "" : raw);
            if (!"capsulesms".equalsIgnoreCase(uri.getScheme())
                    || !"enroll".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("这不是胶囊短信设备二维码");
            }
            String server = uri.getQueryParameter("server");
            String code = uri.getQueryParameter("code");
            if (server == null || code == null) throw new IllegalArgumentException("二维码缺少服务器或注册码");
            serverInput.setText(CloudSyncApi.normalizeServer(server));
            enrollmentInput.setText(code);
            Toast.makeText(this, "已读取设备注册信息", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void renderConnectedLoading() {
        content.removeAllViews();
        status.setText("正在读取设备…");
        progress.setVisibility(View.VISIBLE);
    }

    private void refreshDevices(boolean toastOnSuccess) {
        if (!new CloudSyncConfig(this).isConnected()) return;
        setBusy(true, "正在刷新设备列表…");
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.getDeviceState(this);
                runOnUiThread(() -> {
                    setBusy(false, "已连接");
                    renderDevices(state);
                    if (toastOnSuccess) Toast.makeText(this, "设备列表已刷新", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "连接异常");
                    showError(error);
                });
            }
        });
    }

    private void renderDevices(CloudSyncEngine.DeviceState state) {
        content.removeAllViews();
        pushTo.clear();
        pushTo.addAll(state.pushTo);
        receiveFrom.clear();
        receiveFrom.addAll(state.receiveFrom);
        CloudSyncConfig config = new CloudSyncConfig(this);

        addSectionTitle("连接状态");
        LinearLayout summary = card();
        summary.addView(text("账号：" + state.account, 15, palette.textPrimary));
        summary.addView(text("服务器：" + config.server(), 13, palette.textSecondary));
        summary.addView(text("本机设备 ID：" + shortId(state.currentDeviceId), 13,
                palette.textSecondary));
        content.addView(summary, fieldParams());

        addSectionTitle("设备列表与推送设置");
        int otherCount = 0;
        for (CloudSyncEngine.Device device : state.devices) {
            if (device.id.equals(state.currentDeviceId)) continue;
            otherCount++;
            LinearLayout deviceCard = card();
            TextView name = text(device.name, 16, palette.textPrimary);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            deviceCard.addView(name);
            String seen = device.lastSeenAt <= 0 ? "尚未在线"
                    : "上次连接：" + DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT).format(new Date(device.lastSeenAt));
            deviceCard.addView(text(device.platform + "  ·  " + seen, 12,
                    palette.textMuted));

            CheckBox push = new CheckBox(this);
            push.setText(getString(R.string.cloud_push_to, device.name));
            push.setTextColor(palette.textPrimary);
            push.setChecked(pushTo.contains(device.id));
            push.setOnCheckedChangeListener((button, checked) -> {
                if (checked) pushTo.add(device.id); else pushTo.remove(device.id);
            });
            deviceCard.addView(push);

            CheckBox receive = new CheckBox(this);
            receive.setText(getString(R.string.cloud_receive_from, device.name));
            receive.setTextColor(palette.textPrimary);
            receive.setChecked(receiveFrom.contains(device.id));
            receive.setOnCheckedChangeListener((button, checked) -> {
                if (checked) receiveFrom.add(device.id); else receiveFrom.remove(device.id);
            });
            deviceCard.addView(receive);
            content.addView(deviceCard, fieldParams());
        }
        if (otherCount == 0) {
            TextView empty = text("还没有其他设备。请在同一账号下连接 Pixel 或 OPPO。", 14,
                    palette.textMuted);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(rounded(palette.surface, 18));
            content.addView(empty, fieldParams());
        }

        Button save = primaryButton("保存推送设置");
        save.setEnabled(otherCount > 0);
        save.setOnClickListener(v -> saveRoutes());
        content.addView(save, fieldParams());

        LinearLayout actions = row();
        Button refresh = secondaryButton("刷新设备");
        refresh.setOnClickListener(v -> refreshDevices(true));
        Button sync = secondaryButton("立即同步");
        sync.setOnClickListener(v -> syncNow());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        syncParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(sync, syncParams);
        content.addView(actions, fieldParams());

        Button disconnect = secondaryButton("断开本机连接");
        disconnect.setTextColor(palette.danger);
        disconnect.setOnClickListener(v -> confirmDisconnect());
        content.addView(disconnect, fieldParams());
    }

    private void saveRoutes() {
        Set<String> outgoing = new HashSet<>(pushTo);
        Set<String> incoming = new HashSet<>(receiveFrom);
        setBusy(true, "正在保存同步关系…");
        io.execute(() -> {
            try {
                CloudSyncEngine.DeviceState state = CloudSyncEngine.saveRoutes(this, outgoing, incoming);
                runOnUiThread(() -> {
                    setBusy(false, "设置已保存");
                    renderDevices(state);
                    Toast.makeText(this, "已保存，关联设备会自动更新", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "保存失败");
                    showError(error);
                });
            }
        });
    }

    private void syncNow() {
        setBusy(true, "正在拉取待同步短信…");
        CloudSyncEngine.syncAsync(this, (count, error) -> runOnUiThread(() -> {
            if (error != null) {
                setBusy(false, "同步失败");
                showError(error);
            } else {
                setBusy(false, "同步完成");
                Toast.makeText(this, count == 0 ? "没有新短信" : "已同步 " + count + " 条短信",
                        Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("断开本机连接？")
                .setMessage("本机会停止云同步，服务器上的设备记录仍可由网页管理。")
                .setNegativeButton("取消", null)
                .setPositiveButton("断开", (dialog, which) -> {
                    new CloudSyncConfig(this).clear();
                    CloudSyncScheduler.cancel(this);
                    renderConnectForm();
                })
                .show();
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(message);
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        new AlertDialog.Builder(this)
                .setTitle("操作未完成")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void addSectionTitle(String value) {
        TextView title = text(value, 14, palette.textSecondary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(14), dp(4), dp(8));
        content.addView(title);
    }

    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(palette.textPrimary);
        field.setHintTextColor(palette.textMuted);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(palette.surface, 16));
        if (password) field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private LinearLayout card() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(16), dp(14), dp(16), dp(14));
        value.setBackground(rounded(palette.surface, 18));
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
        button.setBackground(rounded(palette.accent, 18));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(palette.textPrimary);
        button.setAllCaps(false);
        button.setBackground(rounded(palette.surfaceMuted, 18));
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

    private String shortId(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12) + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
