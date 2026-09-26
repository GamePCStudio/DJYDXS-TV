package com.supermov.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 下载前台服务：把进程保活 + 通知栏显示进度。
 *
 * 真正的下载逻辑在 {@link DlEngine}（单例，进程内存队列）。服务只做三件事：
 *   1. startForeground 保证下载过程中进程不被系统回收
 *   2. 订阅引擎回调，把进度同步到通知栏
 *   3. 队列清空后自己收工（撤通知 + stopSelf），不留常驻通知
 *
 * 为什么不用 WorkManager：下载是「用户可感知的长时间任务」，需要实时进度、
 * 随时暂停/续传和常驻通知，WorkManager 的延迟调度模型并不合适，还多背一堆依赖。
 * 前台服务 + 自研队列是这类场景的标准做法。
 */
public class DlService extends Service implements DlEngine.Observer {

    private static final String TAG = "SupeMov";
    private static final String CHANNEL = "supermov_dl";
    private static final int NOTI_ID = 9101;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground = false;
    private boolean stoppingSelf = false;

    /** 启动/唤起下载服务。 */
    public static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, DlService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable e) {
            Log.d(TAG, "dl service start fail " + e);
        }
    }

    public static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, DlService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DlEngine.get().init(this);
        createChannel();
        DlEngine.get().addObserver(this);
        try {
            startForeground(NOTI_ID, buildNotification());
            foreground = true;
        } catch (Throwable e) {
            // Android 12+ 若后台启动会抛 ForegroundServiceStartNotAllowedException；
            // 这时只是没有常驻通知，下载本身照跑，不致命
            Log.d(TAG, "startForeground fail " + e);
            foreground = false;
        }
        DlEngine.get().start();
        Log.d(TAG, "dl service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DlEngine.get().init(this);
        DlEngine.get().start();
        refresh();
        return START_STICKY;
    }

    @Override
    public void onChanged() {
        main.post(this::refresh);
    }

    private void refresh() {
        if (stoppingSelf) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            if (DlEngine.get().drained()) {
                // 队列空了：收工，不留常驻通知
                stoppingSelf = true;
                if (nm != null) nm.cancel(NOTI_ID);
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {
                }
                DlEngine.get().removeObserver(this);
                stopSelf();
                Log.d(TAG, "dl service idle -> stopSelf");
                return;
            }
            if (nm == null) return;
            if (!foreground) {
                startForeground(NOTI_ID, buildNotification());
                foreground = true;
            } else {
                nm.notify(NOTI_ID, buildNotification());
            }
        } catch (Throwable e) {
            Log.d(TAG, "dl noti fail " + e);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, DownloadActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        DlEngine e = DlEngine.get();
        Dl cur = e.currentTask();
        int total = 0;
        int done = 0;
        int active = 0;
        for (Dl t : e.snapshot()) {
            if (t.active()) active++;
        }
        String title;
        String text;
        if (cur != null) {
            title = "正在下载：" + cur.displayName();
            text = (e.note().isEmpty() ? cur.statusText() : e.note())
                    + (active > 1 ? "（队列还有 " + (active - 1) + " 个）" : "");
            total = 100;
            done = cur.percent();
        } else {
            title = "山姆影库 下载队列";
            text = active > 0 ? ("排队中 " + active + " 个任务") : "收尾中…";
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21 && total > 0) {
            b.setProgress(total, done, false);
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "下载",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("影片下载进度");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        DlEngine.get().removeObserver(this);
        // 整体挂起：正在下的停下、未完成的回到队列（.dlpart + .dlpart.json 都保留），
        // 下次打开 App / 回到下载页会自动从断点接着下。
        DlEngine.get().suspendAll();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Log.d(TAG, "dl service destroyed");
        super.onDestroy();
    }

    /**
     * 从最近任务里划掉 App 时**不要**跟着停下载。
     *
     * <p>{@code stopWithTask} 默认就是 false，但有些 ROM 会把「划卡片」当成停服务的信号，
     * 这里主动再唤一次做兜底：队列里还有活才重启，空队列就安静收工。</p>
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            if (DlEngine.get().busy()) {
                Log.d(TAG, "task removed but downloads alive -> keep service");
                startService(new Intent(this, DlService.class));
            }
        } catch (Throwable e) {
            Log.d(TAG, "restart after task removed fail " + e);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
