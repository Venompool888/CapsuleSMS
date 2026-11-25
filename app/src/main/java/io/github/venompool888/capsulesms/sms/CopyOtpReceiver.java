package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
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
        clipboard.setPrimaryClip(verificationCodeClip(code));
        Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show();
    }

    /** A verification code clip that Android keeps out of the clipboard preview and keyboard history. */
    public static ClipData verificationCodeClip(String code) {
        ClipData clip = ClipData.newPlainText("验证码", code);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        return clip;
    }
}
