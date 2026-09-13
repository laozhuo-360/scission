package com.linye.netblock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.linye.netblock.R;
import com.linye.netblock.core.NetActionReceiver;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.ui.MainActivity;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无 Root 模式：建立 VPN 隧道把全部流量(0.0.0.0/0 + ::/0)吸进 TUN，
 * 读取后全部丢弃（不转发）—— 出站包石沉大海，等于 100% 丢包。
 */
public class HoleVpnService extends VpnService {

    private static final String TAG = "HoleVpnService";
    public static final String ACTION_START = "com.linye.netblock.action.VPN_START";
    public static final String ACTION_STOP = "com.linye.netblock.action.VPN_STOP";
    private static final String CHANNEL_ID = "netblock_vpn";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pumpThread;
    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopPump();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running.get()) {
            startPump();
        }
        return START_STICKY;
    }

    private synchronized void startPump() {
        if (running.get()) return;
        // Bug6：本服务由 startForegroundService 拉起，Android 8+ 要求 5 秒内
        // 必须 startForeground，否则系统强杀应用 —— 先亮前台通知再建隧道。
        notifyVpnActive();
        try {
            Builder b = new Builder()
                    .setSession("NetBlock")
                    .setMtu(1500)
                    .addAddress("10.66.66.2", 32)
                    .addRoute("0.0.0.0", 0);
            try {
                b.addAddress("fd66:6666:6666::2", 128);
                b.addRoute("::", 0);
            } catch (Exception e) {
                Log.w(TAG, "IPv6 route unavailable: " + e.getMessage());
            }
            tun = b.establish();
            if (tun == null) {
                // Bug3：未授权/被抢占不再静默失败 ——
                // 校正状态并发高优先级通知引导用户授权
                NetBlocker.get().onVpnAuthMissing(this);
                stopSelf();
                return;
            }
            running.set(true);
            NetBlocker.get().confirmVpn(this, true);
            pumpThread = new Thread(this::drainLoop, "nb-drain");
            pumpThread.start();
        } catch (Exception e) {
            Log.e(TAG, "establish failed", e);
            NetBlocker.get().confirmVpn(this, false);
            stopSelf();
        }
    }

    /** 黑洞主循环：所有进 TUN 的包只读不写。 */
    private void drainLoop() {
        byte[] buf = new byte[32768];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running.get()) {
                int n = in.read(buf);
                if (n <= 0) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                // 直接丢弃 n 字节 —— 这就是 100% 丢包
            }
        } catch (IOException e) {
            // TUN 关闭属正常退出路径
        }
    }

    private synchronized void stopPump() {
        running.set(false);
        if (pumpThread != null) {
            pumpThread.interrupt();
            pumpThread = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignore) {
            }
            tun = null;
        }
        NetBlocker.get().confirmVpn(this, false);
    }

    /** 用户在系统设置里撤销 VPN、或被其它 VPN 抢占时回调。 */
    @Override
    public void onRevoke() {
        stopPump();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopPump();
        super.onDestroy();
    }

    /** Bug6：VPN 运行前台通知（含「恢复网络」按钮），建立隧道前后都可见。 */
    private void notifyVpnActive() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Intent main = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piMain = PendingIntent.getActivity(
                this, 2, main,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 通知栏直达「恢复网络」—— 息屏/其它应用内也能一键恢复
        Intent off = new Intent(this, NetActionReceiver.class)
                .setAction(NetActionReceiver.ACTION_BLOCK_OFF);
        PendingIntent piOff = PendingIntent.getBroadcast(
                this, 32, off,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_block)
                .setContentTitle(getString(R.string.vpn_notif_title))
                .setContentText(getString(R.string.vpn_notif_text))
                .setContentIntent(piMain)
                .setOngoing(true)
                .addAction(0, getString(R.string.action_restore), piOff)
                .build();
        startForeground(2002, n);
    }
}
