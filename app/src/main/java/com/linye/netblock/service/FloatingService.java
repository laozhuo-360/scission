package com.linye.netblock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.linye.netblock.R;
import com.linye.netblock.core.NetActionReceiver;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.ui.MainActivity;

/**
 * 常驻前台服务：承载 ① 状态栏通知（含断网/恢复按钮）② 可拖动快捷悬浮窗。
 * 悬浮窗点击 = 开启/恢复切换；长按 = 打开主界面。
 */
public class FloatingService extends Service implements NetBlocker.Listener {

    /**
     * Bug4/8：服务是否存活的静态标志 ——
     * 主界面 onResume 据此判断要不要重新拉起悬浮窗；
     * 外部据此避免把已停止的服务误拉起（音量键触发时不再莫名弹悬浮窗）。
     */
    public static volatile boolean sRunning = false;

    private static final String CHANNEL_ID = "netblock_status";
    private static final int NOTIF_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View fabContainer;
    private ImageView fabIcon;
    private WindowManager.LayoutParams fabParams;
    private boolean fabAdded;

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        startForegroundWithNotification();
        NetBlocker.get().addListener(this);
        setupFloatingButton();
        onBlockedChanged(NetBlocker.get().isBlocked(), NetBlocker.get().getMode());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 状态刷新由 NetBlocker.Listener 回调驱动（本服务已注册监听），
        // 不再接受外部 REFRESH 指令 —— 避免服务被误拉起后弹出悬浮窗。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        NetBlocker.get().removeListener(this);
        removeFloatingButton();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- 通知 ---------------- */

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void updateNotification(boolean blocked) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(blocked ? R.drawable.ic_stat_block : R.drawable.ic_stat_ok)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(mainIntent())
                .setContentTitle(getString(blocked
                        ? R.string.notif_title_blocked : R.string.notif_title_ok))
                .setContentText(getString(blocked
                        ? R.string.notif_text_blocked : R.string.notif_text_ok));

        int actionLabel = blocked ? R.string.action_restore : R.string.action_block;
        String action = blocked ? NetActionReceiver.ACTION_BLOCK_OFF : NetActionReceiver.ACTION_BLOCK_ON;
        Intent it = new Intent(this, NetActionReceiver.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, blocked ? 11 : 12, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(0, getString(actionLabel), pi);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildForeground(b.build()));
    }

    private Notification buildForeground(Notification base) {
        startForeground(NOTIF_ID, base);
        return base;
    }

    private PendingIntent mainIntent() {
        Intent it = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ---------------- 悬浮窗 ---------------- */

    private void setupFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        FrameLayout container = new FrameLayout(this);
        int size = dp(56);

        // 底色圆
        FrameLayout bgCircle = new FrameLayout(this);
        bgCircle.setBackground(ContextCompat.getDrawable(this, R.drawable.fab_bg));
        container.addView(bgCircle, new FrameLayout.LayoutParams(size, size));

        // 图标
        fabIcon = new ImageView(this);
        fabIcon.setScaleType(ImageView.ScaleType.CENTER);
        container.addView(fabIcon, new FrameLayout.LayoutParams(size, size));

        container.setAlpha(0.92f);
        container.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        container.setClipToOutline(true);
        container.setElevation(dp(6));

        fabContainer = container;

        int layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        fabParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = dp(8);
        fabParams.y = dp(120);

        attachDragAndClick();

        try {
            wm.addView(fabContainer, fabParams);
            fabAdded = true;
        } catch (Exception e) {
            fabAdded = false;
        }
    }

    private void attachDragAndClick() {
        final float[] downRaw = new float[2];
        final float[] startParams = new float[2];
        final boolean[] dragged = {false};
        final long[] downAt = {0};

        fabContainer.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = ev.getRawX();
                    downRaw[1] = ev.getRawY();
                    startParams[0] = fabParams.x;
                    startParams[1] = fabParams.y;
                    dragged[0] = false;
                    downAt[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downRaw[0];
                    float dy = ev.getRawY() - downRaw[1];
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                        dragged[0] = true;
                    }
                    if (dragged[0]) {
                        fabParams.x = (int) (startParams[0] + dx);
                        fabParams.y = (int) (startParams[1] + dy);
                        try {
                            wm.updateViewLayout(fabContainer, fabParams);
                        } catch (Exception ignore) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!dragged[0] && System.currentTimeMillis() - downAt[0] < 400) {
                        NetBlocker.get().toggle(FloatingService.this);
                    } else if (dragged[0]) {
                        snapToEdge();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
        fabContainer.setOnLongClickListener(v -> {
            Intent it = new Intent(FloatingService.this, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(it);
            } catch (Exception ignore) {
            }
            return true;
        });
    }

    /** 松手后吸附近边缘。 */
    private void snapToEdge() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int viewW = dp(56);
        int center = fabParams.x + viewW / 2;
        int targetX = center < screenW / 2 ? 0 : screenW - viewW;
        fabParams.x = targetX;
        try {
            wm.updateViewLayout(fabContainer, fabParams);
        } catch (Exception ignore) {
        }
    }

    private void removeFloatingButton() {
        if (fabAdded && fabContainer != null && wm != null) {
            try {
                wm.removeView(fabContainer);
            } catch (Exception ignore) {
            }
            fabAdded = false;
        }
    }

    /* ---------------- 状态联动 ---------------- */

    @Override
    public void onBlockedChanged(boolean blocked, int mode) {
        handler.post(() -> {
            updateNotification(blocked);
            if (fabAdded && fabContainer != null) {
                fabContainer.setBackground(ContextCompat.getDrawable(this,
                        blocked ? R.drawable.fab_bg_blocked : R.drawable.fab_bg));
                fabIcon.setImageDrawable(ContextCompat.getDrawable(this,
                        blocked ? R.drawable.ic_wifi_off_white : R.drawable.ic_wifi_white));
                fabContainer.setContentDescription(getString(blocked
                        ? R.string.cd_fab_restore : R.string.cd_fab_block));
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
