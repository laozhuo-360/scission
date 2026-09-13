package com.linye.netblock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.linye.netblock.R;
import com.linye.netblock.core.NetActionReceiver;
import com.linye.netblock.core.NetBlocker;
import com.linye.netblock.ui.MainActivity;

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

    /**
     * Bug9：intent 为 null 说明是系统回收后重建（START_STICKY 残留），
     * 绝不能默认 ACTION_START 重新打开 VPN —— 必须直接自杀，把控制权还给用户。
     * 用 START_NOT_STICKY 取代 STICKY，杜绝"恢复后 VPN 又被系统拉起"的竞态。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // 系统重建：不自动开 VPN，自杀让下次正常 startService 走完整流程
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPump();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running.get()) {
            startPump();
        }
        return START_NOT_STICKY;
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

    /**
     * 黑洞主循环：所有进 TUN 的包只读不写。
     *
     * Bug9：原来用 FileInputStream.read() 是无限阻塞式调用，
     * stopService 后 TUN 文件描述符要等当前 read 返回才会真正关闭，
     * 导致 drainLoop 线程永久卡住、service 无法完全销毁。
     * 改用 Os.poll() 每次最多阻塞 100ms，回到循环顶部检查 running 标志，
     * 保证 stopPump() 置位后最多 100ms 内循环自然退出。
     */
    private void drainLoop() {
        android.os.ParcelFileDescriptor pfd = tun;
        if (pfd == null) return;
        java.io.FileDescriptor fd = pfd.getFileDescriptor();
        byte[] buf = new byte[32768];
        try {
            while (running.get()) {
                StructPollfd[] fds = new StructPollfd[1];
                fds[0] = new StructPollfd();
                fds[0].fd = fd;
                fds[0].events = (short) OsConstants.POLLIN;
                // 最多阻塞 100ms，超时返回 0 继续检查 running
                int ready = Os.poll(fds, 100);
                if (!running.get()) break;
                if (ready > 0 && (fds[0].revents & OsConstants.POLLIN) != 0) {
                    int n = Os.read(fd, buf, 0, buf.length);
                    if (n <= 0) break;  // 隧道关闭或 EOF
                    // 读到 n 字节，直接丢弃 —— 100% 丢包
                }
            }
        } catch (Exception e) {
            // TUN 关闭或 poll/read 出错，属正常退出路径
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
