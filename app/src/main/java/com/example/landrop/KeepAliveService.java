package com.example.landrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务。
 *
 * LanDrop 的监听线程仍由 MainActivity 持有；这个服务唯一的作用是
 * 拿到前台优先级，让系统在用户切到别的 App 时不会回收本进程，
 * 从而保证 8765 端口监听和已配对设备的传输不被打断。
 *
 * 服务随「启动服务」开始，随「停止服务」结束。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "landrop_keep";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "LanDrop 服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持局域网传输服务运行");
            nm.createNotificationChannel(ch);
        }

        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("LanDrop")
                .setContentText("服务运行中，可接收局域网传输")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
    }
}
