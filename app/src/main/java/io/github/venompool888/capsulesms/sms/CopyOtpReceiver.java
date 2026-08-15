package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Handles the lock-screen notification action without opening the app. */
public final class CopyOtpReceiver extends BroadcastReceiver {
    static final String ACTION_COPY_OTP =
            "io.github.venompool888.capsulesms.action.COPY_OTP";
    static final String EXTRA_OTP = "otp";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_COPY_OTP.equals(intent.getAction())) return;
        String code = intent.getStringExtra(EXTRA_OTP);
        if (code == null || code.isBlank()) return;

        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("验证码", code));
        Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show();
    }
}
