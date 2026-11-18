package com.jack.zcodeapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground keep-alive service: a low-priority persistent notification plus a
 * partial wake lock, so Android does not freeze the remote-control WebSocket
 * when the app is backgrounded or the screen turns off.
 */
public class KeepAliveService extends Service {

    static final String CHANNEL_ID = "keepalive";
    static final int NOTIFICATION_ID = 1;
    static final String ACTION_STOP = "com.jack.zcodeapp.STOP";

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "远程连接保持", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持 ZCode 远程控制连接");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zcodeapp:keepalive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        PendingIntent stopIntent = PendingIntent.getService(this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent scanIntent = PendingIntent.getActivity(this, 2,
                new Intent(this, ScanActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        b.setContentTitle("ZCode App")
                .setContentText("远程控制连接保持中，点按返回")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_launcher_fg, "扫码", scanIntent).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_launcher_fg, "退出", stopIntent).build());
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
