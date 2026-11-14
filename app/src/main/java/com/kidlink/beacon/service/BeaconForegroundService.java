package com.kidlink.beacon.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kidlink.beacon.R;
import com.kidlink.beacon.ui.MainActivity;

/**
 * 后台保活用的前台服务。
 *
 * 它自己**不做任何扫描** —— 扫描、滤波、震动、切回前台这套逻辑全在 MainActivity 里，
 * 这个服务唯一的作用是挂一条常驻通知，把进程优先级提上去，
 * 这样用户按 Home / 切到别的应用之后，Activity 的扫描回调不会被系统掐掉。
 *
 * 对应的开关是设置页的「允许后台运行和扫描」，默认开。
 */
public class BeaconForegroundService extends Service {

    private static final String CHANNEL_ID = "kidlink_bg_scan";
    private static final int NOTIFICATION_ID = 0x4B1D;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, BeaconForegroundService.class));
        } catch (Exception ignored) {
            // Android 12+ 后台启动前台服务受限，失败就退回「只能前台扫描」，不影响主流程
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, BeaconForegroundService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // 这里用 START_NOT_STICKY：扫描逻辑全在 MainActivity 里，服务本身不扫。
        // 进程真被系统杀掉后就算把服务拉起来，也没有任何扫描在跑，只会在通知栏
        // 留一条点不掉的通知 —— 而且会让「接送成功后退出 APP」退不干净。
        // 要恢复监听，重新打开 APP 就行（MainActivity.onResume 会再 start 一次）。
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        createChannel();

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 从通知进来 = 用户想开界面；带上这个标记，就不受「打开后自动最小化」影响
        open.putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_beacon)
                .setContentTitle(getString(R.string.fg_bg_title))
                .setContentText(getString(R.string.fg_bg_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.settings_title),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.fg_bg_text));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
