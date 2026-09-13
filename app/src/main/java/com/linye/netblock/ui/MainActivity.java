package com.linye.netblock.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.linye.netblock.R;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.core.RootShell;
import com.linye.netblock.service.FloatingService;
import com.linye.netblock.service.VolumeKeyService;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Calendar;

/** 主界面：问候语 + 星空胶囊开关 + 模式选择 + 快捷开关引导。 */
public class MainActivity extends AppCompatActivity implements NetBlocker.Listener {

    public static final String EXTRA_AUTH_VPN = "auth_vpn";
    public static final String EXTRA_AUTO_BLOCK = "auto_block";

    private PillSwitchView pill;
    private TextView statusText;
    private TextView statusSub;
    private RadioButton rbRoot, rbVpn;
    private MaterialSwitch swFloating, swVolume;
    private SharedPreferences prefs;

    private boolean pendingVpnAuth = false; // 等待发起 VPN 授权（从引导通知进入）
    private boolean autoAfterAuth = false;  // 授权成功后自动开启断网
    private boolean syncing = false;
    private boolean accWasOk = false;       // 上次 onResume 时无障碍服务是否存活（边沿检测用）

    private final ActivityResultLauncher<Intent> vpnAuthLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                // Bug3：授权框返回后闭环处理 —— 同意则自动断流，拒绝则明确提示
                boolean auto = autoAfterAuth;
                autoAfterAuth = false;
                if (!auto) return;
                if (r.getResultCode() == RESULT_OK) {
                    pill.setChecked(true, true);
                    NetBlocker.get().setBlocked(MainActivity.this, true, false);
                } else {
                    Toast.makeText(MainActivity.this,
                            R.string.toast_vpn_denied, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), r -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("netblock", MODE_PRIVATE);

        // 首次进入：先走设置向导
        if (!prefs.getBoolean("setupDone", false)) {
            startActivity(new Intent(this, SetupActivity.class));
        }

        setContentView(R.layout.activity_main);

        pill = findViewById(R.id.pill_switch);
        statusText = findViewById(R.id.status_text);
        statusSub = findViewById(R.id.status_sub);
        rbRoot = findViewById(R.id.rb_root);
        rbVpn = findViewById(R.id.rb_vpn);
        swFloating = findViewById(R.id.sw_floating);
        swVolume = findViewById(R.id.sw_volume);

        /* ---- 问候语 ---- */
        ((TextView) findViewById(R.id.greeting_title)).setText(greeting());

        /* ---- 总开关 ----
         * Bug1：胶囊是自绘 View，此前只挂了 checked 回调、没人调 setChecked，
         *      回调永远不触发 —— 改为点击驱动：点一下动画反馈 + 发起断流。
         * Bug3：VPN 模式首次开启前，先拉起系统 VPN 授权框，结果在回调闭环。 */
        pill.setOnClickListener(v -> {
            boolean next = !NetBlocker.get().isBlocked();
            if (next && NetBlocker.get().getMode() == NetBlocker.MODE_VPN) {
                Intent prep = VpnService.prepare(this);
                if (prep != null) {
                    autoAfterAuth = true;
                    vpnAuthLauncher.launch(prep);
                    return; // 等授权结果回调后再开启
                }
            }
            pill.setChecked(next, true); // 本地动画反馈；失败时由状态回调拨回
            NetBlocker.get().setBlocked(this, next, false);
        });
        NetBlocker.get().addListener(this);

        /* ---- 模式选择 ---- */
        boolean rootOk = RootShell.isAvailable();
        TextView tvRootState = findViewById(R.id.tv_root_state);
        tvRootState.setText(rootOk ? R.string.root_detected : R.string.root_not_detected);
        tvRootState.setTextColor(ContextCompat.getColor(this,
                rootOk ? R.color.status_green : R.color.status_orange));

        /* Bug2：两个 RadioButton 分属两张卡片（不在同一 RadioGroup），
         *      系统不会自动互斥 —— 手动取消另一个；且不允许两个同时取消。 */
        rbRoot.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            if (checked) {
                if (rbVpn.isChecked()) {
                    syncing = true;
                    rbVpn.setChecked(false);
                    syncing = false;
                }
                NetBlocker.get().setMode(this, NetBlocker.MODE_ROOT);
            } else if (!rbVpn.isChecked()) {
                syncing = true;
                rbRoot.setChecked(true); // 回弹：至少保持一个选中
                syncing = false;
            }
        });
        rbVpn.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            if (checked) {
                if (rbRoot.isChecked()) {
                    syncing = true;
                    rbRoot.setChecked(false);
                    syncing = false;
                }
                NetBlocker.get().setMode(this, NetBlocker.MODE_VPN);
            } else if (!rbRoot.isChecked()) {
                syncing = true;
                rbVpn.setChecked(true);
                syncing = false;
            }
        });

        /* ---- 悬浮窗开关 ---- */
        swFloating.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            prefs.edit().putBoolean("floatingWanted", checked).apply();
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    b.setChecked(false);
                    promptOverlayPermission();
                } else {
                    ContextCompat.startForegroundService(this,
                            new Intent(this, FloatingService.class));
                }
            } else {
                stopService(new Intent(this, FloatingService.class));
            }
        });

        /* ---- 音量键开关 ---- */
        swVolume.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            if (checked) {
                if (!isAccessibilityServiceEnabled(this, VolumeKeyService.class)) {
                    b.setChecked(false);
                    Toast.makeText(this, R.string.toast_go_accessibility, Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } else {
                    prefs.edit().putBoolean("volumeKeyEnabled", true).apply();
                }
            } else {
                prefs.edit().putBoolean("volumeKeyEnabled", false).apply();
            }
        });

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        handleIntent(getIntent());
    }

    /** 按时段返回问候语。 */
    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return getString(R.string.greeting_late);
        if (h < 12) return getString(R.string.greeting_morning);
        if (h < 14) return getString(R.string.greeting_noon);
        if (h < 18) return getString(R.string.greeting_afternoon);
        return getString(R.string.greeting_evening);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTH_VPN, false)) {
            pendingVpnAuth = true;
            autoAfterAuth = intent.getBooleanExtra(EXTRA_AUTO_BLOCK, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bug4：清后台后 FloatingService 被系统杀死 —— 按用户意愿自动恢复悬浮窗
        if (prefs.getBoolean("floatingWanted", false)
                && Settings.canDrawOverlays(this)
                && !FloatingService.sRunning) {
            try {
                ContextCompat.startForegroundService(this, new Intent(this, FloatingService.class));
            } catch (Exception ignore) {
            }
        }
        // 待处理的 VPN 授权（从引导通知点进来）
        if (pendingVpnAuth) {
            pendingVpnAuth = false;
            if (NetBlocker.get().getMode() == NetBlocker.MODE_VPN && !NetBlocker.get().isBlocked()) {
                Intent prep = VpnService.prepare(this);
                if (prep != null) {
                    vpnAuthLauncher.launch(prep);
                } else if (autoAfterAuth) {
                    // 已授权过（重复点通知）：直接断流
                    autoAfterAuth = false;
                    pill.setChecked(true, true);
                    NetBlocker.get().setBlocked(this, true, false);
                }
            }
        }
        syncSwitchStates();
    }

    @Override
    protected void onDestroy() {
        NetBlocker.get().removeListener(this);
        super.onDestroy();
    }

    /** 同步 UI 到真实状态（syncing 标志防止回调回环）。 */
    private void syncSwitchStates() {
        syncing = true;
        int mode = NetBlocker.get().getMode();
        // Bug2：两个都要显式设置 —— 另一个必须取消选中，否则可同时高亮
        boolean rootMode = (mode == NetBlocker.MODE_ROOT);
        rbRoot.setChecked(rootMode);
        rbVpn.setChecked(!rootMode);

        boolean overlayOk = Settings.canDrawOverlays(this);
        swFloating.setChecked(overlayOk && prefs.getBoolean("floatingWanted", false));

        boolean accOk = isAccessibilityServiceEnabled(this, VolumeKeyService.class);
        swVolume.setChecked(accOk && prefs.getBoolean("volumeKeyEnabled", false));

        // Bug7：音量键功能开着但无障碍服务被系统杀掉 —— 边沿触发提醒一次
        // （首次进入不提醒；进程重启 accWasOk 归零，也不会误报）
        if (prefs.getBoolean("volumeKeyEnabled", false) && !accOk && accWasOk) {
            Toast.makeText(this, R.string.toast_acc_killed, Toast.LENGTH_LONG).show();
        }
        accWasOk = accOk;

        onBlockedChanged(NetBlocker.get().isBlocked(), mode);
        syncing = false;
    }

    private void promptOverlayPermission() {
        Toast.makeText(this, R.string.toast_go_overlay, Toast.LENGTH_LONG).show();
        Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(it);
    }

    public static boolean isAccessibilityServiceEnabled(Context context,
                                                        Class<? extends android.accessibilityservice.AccessibilityService> svc) {
        String expected = new ComponentName(context, svc).flattenToString();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        android.text.TextUtils.SimpleStringSplitter splitter =
                new android.text.TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    /* ---- 状态联动 ---- */

    @Override
    public void onBlockedChanged(boolean blocked, int mode) {
        runOnUiThread(() -> {
            pill.setCheckedSilent(blocked);
            statusText.setText(blocked ? R.string.status_blocked : R.string.status_ok);
            statusText.setTextColor(ContextCompat.getColor(this,
                    blocked ? R.color.status_red : R.color.status_green));
            statusSub.setText(blocked
                    ? R.string.status_sub_blocked : R.string.status_sub_ok);
        });
    }
}
