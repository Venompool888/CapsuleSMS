package io.github.venompool888.capsulesms.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.widget.Toast;

public final class MmsDeliverReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) return;
        Toast.makeText(context, "暂不支持彩信，请使用普通短信", Toast.LENGTH_LONG).show();
    }
}
