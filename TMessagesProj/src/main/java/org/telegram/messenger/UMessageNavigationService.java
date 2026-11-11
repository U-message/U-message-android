package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Keeps in-app navigation alive while the app is in the background: a location-type foreground
 * service with an ongoing notification showing the next maneuver. Navigation logic itself stays in
 * UMessageNavigationActivity; this only keeps the process and GPS running.
 */
public class UMessageNavigationService extends Service {

    private static final int NOTIFICATION_ID = 61;
    private static String text = "";
    private static boolean running;

    public static void start(String content) {
        text = content != null ? content : "";
        if (running) {
            update(text);
            return;
        }
        try {
            Intent intent = new Intent(ApplicationLoader.applicationContext, UMessageNavigationService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ApplicationLoader.applicationContext.startForegroundService(intent);
            } else {
                ApplicationLoader.applicationContext.startService(intent);
            }
            running = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void update(String content) {
        text = content != null ? content : "";
        if (!running) return;
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop() {
        if (!running) return;
        running = false;
        try {
            ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, UMessageNavigationService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static android.app.Notification build() {
        Intent open = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(ApplicationLoader.applicationContext, 61, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationsController.checkOtherNotificationsChannel();
        return new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.live_loc)
                .setContentTitle(LocaleController.getString(R.string.UMessageNavOngoing))
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
