package com.linye.netblock.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.linye.netblock.R;
import com.linye.netblock.service.HoleVpnService;
import com.linye.netblock.ui.MainActivity;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心状态机：管理断网开/关与运行模式（Root iptables / 无 Root VPN）。
 * 所有入口（主界面、悬浮窗、通知、音量键）统一经过这里。
 *
 * 防抖设计（Bug6）：setBlocked 只记录「期望状态」即返回，
 * 由单线程 drainApply 循环消化 —— 快速连按时中间态全部被跳过，
 * 只执行最终值；Toast 统一节流（先取消上一条 + 同文案 800ms 去重）。
 *
 * Bug8：状态变化靠 Listener 回调广播（FloatingService 已注册监听自动刷新），
 * 不再主动 startService 拉起任何服务。
 */
public final class NetBlocker {

    public static final int MODE_ROOT = 0;
    public static final int MODE_VPN = 1;

    private static final String CHANNEL_VPN_AUTH = "vpn_auth";
    private static final int NOTIF_VPN_AUTH = 3003;
    private static final long TOAST_MIN_INTERVAL_MS = 800;

    public interface Listener {
        void onBlockedChanged(boolean blocked, int mode);
    }

    private static volatile NetBlocker sInstance;

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 期望状态：快速连按时只保留最后一次的目标值。 */
    private final AtomicBoolean desiredBlocked = new AtomicBoolean(false);

    private SharedPreferences prefs;
    /** 实际生效状态（volatile：executor 线程写，任意线程读）。 */
    private volatile boolean blocked;
    private volatile int mode = MODE_ROOT;
    /** 最后一次 setBlocked 的静默标记（通知栏按钮场景静默）。 */
    private volatile boolean lastSilent = false;

    /* Toast 节流状态（仅主线程访问） */
    private Toast lastToast;
    private int lastToastRes;
    private long lastToastAt;

    private NetBlocker() {
    }

    public static NetBlocker get() {
        if (sInstance == null) {
            synchronized (NetBlocker.class) {
                if (sInstance == null) {
                    sInstance = new NetBlocker();
                }
            }
        }
        return sInstance;
    }

    public void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext()
                .getSharedPreferences("netblock", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", MODE_ROOT);
        // 进程冷启动时做一次安全清理：若上次是 Root 模式断网中被杀，
        // iptables 规则会残留在内核里，这里无条件清空自定义链，保证网络可恢复。
        RootShell.cleanupQuietly();
        blocked = false;
        desiredBlocked.set(false);
        saveState();
    }

    public boolean isBlocked() {
        return blocked;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(Context context, int newMode) {
        if (newMode == mode) return;
        // 切换模式前先恢复网络，避免两套机制叠加
        if (blocked) {
            setBlocked(context.getApplicationContext(), false, true);
        }
        mode = newMode;
        prefs.edit().putInt("mode", mode).apply();
        notifyListeners();
    }

    public void toggle(Context context) {
        setBlocked(context, !blocked, false);
    }

    /**
     * 统一入口：开启(100% 丢包) / 关闭(恢复)。
     * 立即记录期望值即返回（永不阻塞调用线程，音量键连按不卡界面）；
     * 实际动作由后台线程按最终值执行。
     *
     * @param silent true 时不弹 Toast（通知栏按钮等场景）
     */
    public void setBlocked(Context context, boolean target, boolean silent) {
        final Context app = context.getApplicationContext();
        lastSilent = silent;
        desiredBlocked.set(target);
        executor.execute(() -> drainApply(app));
    }

    /**
     * 单线程消化循环：执行期望状态；若执行期间期望又变了，
     * 继续循环执行新值 —— 快速连按 N 次中间态全部被跳过，不堆积任务。
     *
     * Bug9：读 desiredBlocked 与 blocked 的对比加锁，
     * 防止"开启"还没执行完时用户连点两次"恢复"，
     * 第二次请求因 target==blocked 被直接吞掉、什么都不发生。
     */
    private void drainApply(Context app) {
        while (true) {
            final boolean target;
            synchronized (desiredBlocked) {
                target = desiredBlocked.get();
                if (target == blocked) {
                    main.post(() -> toastState(app, target, lastSilent));
                    return;
                }
            }
            final int m = mode;
            final boolean ok = (m == MODE_ROOT) ? RootShell.apply(target) : applyVpn(app, target);
            if (ok) {
                blocked = target;
                saveState();
                main.post(this::notifyListeners);
                if (desiredBlocked.get() == target) {
                    main.post(() -> toastState(app, target, lastSilent));
                    return;
                }
                // 执行期间用户又按了：继续消化新目标（防抖）
            } else {
                // 动作失败：期望回滚到实际状态，避免状态错位
                desiredBlocked.set(blocked);
                final int errRes = (m == MODE_ROOT)
                        ? R.string.toast_root_failed : R.string.toast_vpn_failed;
                main.post(() -> toastRes(app, errRes));
                return;
            }
        }
    }

    /**
     * VPN 模式动作。
     * Bug6：开启前先查授权（Bug3），未授权发通知引导而非静默失败；
     * 启动服务必须用 startForegroundService（Android 8+ 后台 startService 会抛异常）。
     */
    private boolean applyVpn(Context app, boolean target) {
        if (!target) {
            // Bug9：stopService 只是"通知系统回收"，若 drainLoop 阻塞在服务回收之前，
            // stopPump 永远不会被调到。改为主动发 ACTION_STOP 让 service 自己走关闭流程，
            // 确保 tun.close() 一定被执行。
            Intent stop = new Intent(app, HoleVpnService.class)
                    .setAction(HoleVpnService.ACTION_STOP);
            try {
                app.startService(stop);
            } catch (Exception e) {
                // service 可能已被系统回收，忽略
            }
            return true;
        }
        if (VpnService.prepare(app) != null) {
            // 尚未授权 VPN：后台无法直接弹系统授权框，发高优先级通知引导用户
            notifyVpnAuthNeeded(app);
            return false;
        }
        Intent it = new Intent(app, HoleVpnService.class).setAction(HoleVpnService.ACTION_START);
        try {
            ContextCompat.startForegroundService(app, it);
            return true; // 真正建立与否由 HoleVpnService 回调 confirmVpn 校正
        } catch (Exception e) {
            return false;
        }
    }

    /** HoleVpnService.establish() 失败（未授权/被抢占）时回调：校正状态 + 通知引导。 */
    public void onVpnAuthMissing(Context context) {
        confirmVpn(context, false);
        notifyVpnAuthNeeded(context.getApplicationContext());
    }

    /** Bug3：VPN 未授权时的高优先级引导通知，点击进应用自动拉起系统授权框。 */
    private void notifyVpnAuthNeeded(Context app) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_VPN_AUTH, app.getString(R.string.vpn_auth_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }
        Intent it = new Intent(app, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTH_VPN, true)
                .putExtra(MainActivity.EXTRA_AUTO_BLOCK, true);
        PendingIntent pi = PendingIntent.getActivity(app, 21, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(app, CHANNEL_VPN_AUTH)
                .setSmallIcon(R.drawable.ic_stat_block)
                .setContentTitle(app.getString(R.string.vpn_auth_title))
                .setContentText(app.getString(R.string.vpn_auth_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        try {
            nm.notify(NOTIF_VPN_AUTH, n);
        } catch (Exception ignore) {
        }
    }

    /** HoleVpnService 在 VPN 真正建立/被撤销时回调，校正状态。 */
    public void confirmVpn(Context context, boolean established) {
        if (established != blocked) {
            blocked = established;
            saveState();
            notifyListeners();
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onBlockedChanged(blocked, mode);
        }
    }

    /* ---------------- Toast 节流（仅主线程调用） ---------------- */

    private void toastState(Context app, boolean target, boolean silent) {
        if (silent) return;
        toastRes(app, target ? R.string.toast_blocked_on : R.string.toast_blocked_off);
    }

    /** 先取消上一条；同一文案 800ms 内不重复弹 —— 连按不再 Toast 风暴。 */
    private void toastRes(Context app, int res) {
        long now = SystemClock.elapsedRealtime();
        if (lastToast != null) {
            if (res == lastToastRes && now - lastToastAt < TOAST_MIN_INTERVAL_MS) {
                return;
            }
            lastToast.cancel();
        }
        lastToast = Toast.makeText(app, res, Toast.LENGTH_SHORT);
        lastToast.show();
        lastToastRes = res;
        lastToastAt = now;
    }

    private void saveState() {
        if (prefs != null) {
            prefs.edit().putBoolean("blocked", blocked).apply();
        }
    }
}
