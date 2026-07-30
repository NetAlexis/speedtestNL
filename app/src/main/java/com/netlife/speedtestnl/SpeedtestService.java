package com.netlife.speedtestnl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class SpeedtestService extends Service {

    public static final String CHANNEL_ID = "SpeedtestNL_Channel";
    public static final int    NOTIF_ID   = 1001;

    public static final String ACTION_UPDATE  = "com.netlife.speedtestnl.UPDATE";
    public static final String ACTION_STOP    = "com.netlife.speedtestnl.STOP";
    public static final String EXTRA_STATUS   = "status";
    public static final String EXTRA_PROGRESS = "progress";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String status = intent != null ? intent.getStringExtra(EXTRA_STATUS) : "Ejecutando...";
        String progress = intent != null ? intent.getStringExtra(EXTRA_PROGRESS) : "";
        if (status == null) status = "Ejecutando...";
        if (progress == null) progress = "";

        startForeground(NOTIF_ID, buildNotification(status, progress));
        return START_STICKY;
    }

    public static void update(android.content.Context ctx, String status, String progress) {
        Intent intent = new Intent(ctx, SpeedtestService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_PROGRESS, progress);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(android.content.Context ctx) {
        Intent intent = new Intent(ctx, SpeedtestService.class);
        intent.setAction(ACTION_STOP);
        ctx.startService(intent);
    }

    private Notification buildNotification(String status, String progress) {
        Intent openApp = new Intent(this, RestartableMainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
            Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speedtest NL — Netlife PMO")
            .setContentText(status)
            .setSubText(progress)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Speedtest NL", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Pruebas de velocidad en progreso");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
