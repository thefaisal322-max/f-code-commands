package com.fainet.fcode;

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

/**
 * Does no work of its own. While it runs, Android treats the app as "in use" (it shows a
 * notification), so the shells in the terminal are not stopped when the app goes to the
 * background. MainActivity starts it with the first shell and stops it with the last one.
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "fcode_terminal";
    private static final int NOTIFICATION_ID = 1;

    static void start(Context context) {
        try {
            // startService rather than startForegroundService: if showing the notification fails
            // on some phone, the service just stops instead of the app being closed by Android.
            context.startService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException ignored) {
            // The app is not in the foreground right now; the terminal works without the service.
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException ignored) {
            // nothing to stop
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while a terminal is running");
            manager.createNotificationChannel(channel);

            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_terminal)
                    .setContentTitle("Fcode")
                    .setContentText("The terminal is running")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
