package com.linye.netblock.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.linye.netblock.R;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.ui.MainActivity;

/**
 * 音量键控制（需开启无障碍权限）：
 * 音量上键 = 一键开启断网(100% 丢包)
 * 音量下键 = 直接关闭恢复网络
 * 拦截生效时不改变系统音量；可在主界面随时关闭该功能。
 */
public class VolumeKeyService extends AccessibilityService {

    private static final String PREFS = "netblock";
    private static final String KEY_ENABLED = "volumeKeyEnabled";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (!isFeatureEnabled()) return false;

        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP) {
            NetBlocker.get().setBlocked(this, true, false);
            return true; // 消费，不影响系统音量
        }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            NetBlocker.get().setBlocked(this, false, false);
            return true;
        }
        return false;
    }

    private boolean isFeatureEnabled() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ENABLED, false);
    }

    /** 供主界面查询该无障碍服务是否已启用。 */
    public static boolean isServiceRunning(Context context) {
        return MainActivity.isAccessibilityServiceEnabled(
                context, VolumeKeyService.class);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
