package com.linye.netblock.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.linye.netblock.R;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.core.RootShell;
import com.linye.netblock.service.VolumeKeyService;

/**
 * 首次进入设置向导：欢迎 → 基础权限 → 音量键(可选) → 模式选择 → 完成。
 * 中途退出也视为完成（主界面各功能处仍可补授权限）。
 */
public class SetupActivity extends AppCompatActivity {

    private static final int STEP_COUNT = 5;

    private View[] steps;
    private LinearLayout dots;
    private MaterialButton btnNext, btnSkip;
    private MaterialButton btnNotif, btnOverlay, btnAcc;
    private MaterialCardView cardRoot, cardVpn;
    private TextView tvRootBadge, tvStepLabel;
    private SharedPreferences prefs;
    private int step = 0;
    private boolean rootOk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        prefs = getSharedPreferences("netblock", MODE_PRIVATE);

        steps = new View[]{
                findViewById(R.id.step_welcome),
                findViewById(R.id.step_perm),
                findViewById(R.id.step_acc),
                findViewById(R.id.step_mode),
                findViewById(R.id.step_done)
        };
        dots = findViewById(R.id.setup_dots);
        btnNext = findViewById(R.id.btn_next);
        btnSkip = findViewById(R.id.btn_skip);

        btnNotif = findViewById(R.id.btn_perm_notif);
        btnOverlay = findViewById(R.id.btn_perm_overlay);
        btnAcc = findViewById(R.id.btn_acc_open);
        cardRoot = findViewById(R.id.card_mode_root);
        cardVpn = findViewById(R.id.card_mode_vpn);
        tvRootBadge = findViewById(R.id.tv_setup_root_badge);
        tvStepLabel = findViewById(R.id.setup_step_label);

        /* 权限按钮 */
        btnNotif.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else {
                Toast.makeText(this, R.string.setup_perm_done, Toast.LENGTH_SHORT).show();
                refreshPermButtons();
            }
        });
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        btnAcc.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        /* 模式卡片（checkable 卡片互斥选择） */
        rootOk = RootShell.isAvailable();
        tvRootBadge.setText(rootOk ? R.string.setup_mode_root_badge : R.string.root_not_detected);
        tvRootBadge.setTextColor(ContextCompat.getColor(this,
                rootOk ? R.color.status_green : R.color.status_orange));

        cardRoot.setOnClickListener(v -> pickMode(NetBlocker.MODE_ROOT));
        cardVpn.setOnClickListener(v -> pickMode(NetBlocker.MODE_VPN));

        btnNext.setOnClickListener(v -> {
            if (step < STEP_COUNT - 1) {
                step++;
                showStep();
            } else {
                finishSetup();
            }
        });
        btnSkip.setOnClickListener(v -> {
            step++;
            showStep();
        });

        showStep();
    }

    private void pickMode(int mode) {
        prefs.edit().putInt("mode", mode).putBoolean("modeChosen", true).apply();
        NetBlocker.get().setMode(this, mode);
        cardRoot.setChecked(mode == NetBlocker.MODE_ROOT);
        cardVpn.setChecked(mode == NetBlocker.MODE_VPN);
        cardRoot.setStrokeColor(ContextCompat.getColor(this,
                mode == NetBlocker.MODE_ROOT ? R.color.brand_blue : R.color.pill_day_track));
        cardVpn.setStrokeColor(ContextCompat.getColor(this,
                mode == NetBlocker.MODE_VPN ? R.color.brand_blue : R.color.pill_day_track));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermButtons();
    }

    private void refreshPermButtons() {
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean accOk = MainActivity.isAccessibilityServiceEnabled(this, VolumeKeyService.class);

        stylePermButton(btnNotif, notifOk);
        stylePermButton(btnOverlay, overlayOk);
        stylePermButton(btnAcc, accOk);
    }

    private void stylePermButton(MaterialButton btn, boolean done) {
        btn.setText(done ? R.string.setup_perm_done : R.string.setup_perm_todo);
        btn.setEnabled(!done);
        btn.setAlpha(done ? 0.6f : 1f);
    }

    private void showStep() {
        for (int i = 0; i < STEP_COUNT; i++) {
            steps[i].setVisibility(i == step ? View.VISIBLE : View.GONE);
        }
        // 头部步骤标签
        tvStepLabel.setText(getString(R.string.setup_step_of, step + 1, STEP_COUNT));
        // 底部按钮文案
        if (step == 0) {
            btnNext.setText(R.string.setup_start);
        } else if (step == STEP_COUNT - 1) {
            btnNext.setText(R.string.setup_finish);
        } else {
            btnNext.setText(R.string.setup_next);
        }
        // 音量键步骤显示跳过
        btnSkip.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        renderDots();
        refreshPermButtons();
    }

    private void renderDots() {
        dots.removeAllViews();
        for (int i = 0; i < STEP_COUNT; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMargins(dp(4), 0, dp(4), 0);
            d.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (i == step) {
                // 当前步骤用品牌蓝，尺寸略大
                lp.width = dp(20);
                gd.setColor(ContextCompat.getColor(this, R.color.brand_blue));
            } else {
                gd.setColor(ContextCompat.getColor(this, R.color.pill_day_track));
            }
            d.setBackground(gd);
            dots.addView(d);
        }
    }

    private void finishSetup() {
        prefs.edit().putBoolean("setupDone", true).apply();
        startActivity(new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override
    public void onBackPressed() {
        // 中途退出不强制；下次冷启动若仍未完成会再次进入
        prefs.edit().putBoolean("setupDone", prefs.getBoolean("modeChosen", false)).apply();
        super.onBackPressed();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
