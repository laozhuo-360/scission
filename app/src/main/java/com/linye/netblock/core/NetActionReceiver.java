package com.linye.netblock.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知栏按钮动作入口：断网 / 恢复。 */
public class NetActionReceiver extends BroadcastReceiver {

    public static final String ACTION_BLOCK_ON = "com.linye.netblock.action.BLOCK_ON";
    public static final String ACTION_BLOCK_OFF = "com.linye.netblock.action.BLOCK_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_BLOCK_ON.equals(action)) {
            NetBlocker.get().setBlocked(context, true, true);
        } else if (ACTION_BLOCK_OFF.equals(action)) {
            NetBlocker.get().setBlocked(context, false, true);
        }
    }
}
