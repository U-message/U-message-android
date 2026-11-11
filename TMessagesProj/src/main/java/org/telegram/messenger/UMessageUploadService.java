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

import java.util.HashMap;

/**
 * Keeps the process alive while files are being uploaded, so a photo, video, file or voice message the user
 * already hit "send" on keeps going after they leave the chat or the app. Android freezes or kills background
 * processes; a dataSync foreground service with a progress notification is the sanctioned way to finish.
 * Started when an upload begins (the app is in front then, so the start is allowed) and stopped a few seconds
 * after the last upload ends, which leaves time for the final send request.
 */
public class UMessageUploadService extends Service implements NotificationCenter.NotificationCenterDelegate {

    private static final int NOTIFICATION_ID = 0x75f2;
    private static final long CHECK_MS = 2000;
    private static final long GRACE_MS = 10_000;

    private static UMessageUploadService instance;
    private static boolean starting;

    private NotificationCompat.Builder builder;
    private final HashMap<String, long[]> progress = new HashMap<>(); // location -> uploaded, total
    private long idleSince;
    private int lastPercent = -1;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (anyUploads()) {
                idleSince = 0;
            } else {
                long now = System.currentTimeMillis();
                if (idleSince == 0) idleSince = now;
                if (now - idleSince >= GRACE_MS) {
                    stopSelf();
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(this, CHECK_MS);
        }
    };

    /** Called by FileLoader when an upload starts. */
    public static void onUploadStarted() {
        AndroidUtilities.runOnUIThread(() -> {
            if (instance != null || starting) return;
            starting = true;
            try {
                Intent intent = new Intent(ApplicationLoader.applicationContext, UMessageUploadService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    ApplicationLoader.applicationContext.startForegroundService(intent);
                } else {
                    ApplicationLoader.applicationContext.startService(intent);
                }
            } catch (Throwable e) {
                starting = false; // e.g. ForegroundServiceStartNotAllowedException: the upload still runs while we live
                FileLog.e(e);
            }
        });
    }

    private static int holds;

    /** Other long transfers (the in-app update download) keep the process alive the same way while held. */
    public static void hold() {
        AndroidUtilities.runOnUIThread(() -> holds++);
        onUploadStarted();
    }

    public static void release() {
        AndroidUtilities.runOnUIThread(() -> holds = Math.max(0, holds - 1));
    }

    private static boolean anyUploads() {
        if (holds > 0) return true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && FileLoader.getInstance(a).hasUploads()) return true;
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        starting = false;
        if (instance == this) return START_NOT_STICKY;
        instance = this;
        NotificationsController.checkOtherNotificationsChannel();
        Intent open = new Intent(this, LaunchActivity.class);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        builder = new NotificationCompat.Builder(this, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(LocaleController.getString(R.string.AppName))
                .setContentText(LocaleController.getString(R.string.UploadingStory))
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, 0, true);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            // not in the foreground: stop now, or the system kills the app for a started service without startForeground
            FileLog.e(e);
            instance = null;
            stopSelf();
            return START_NOT_STICKY;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.fileUploadProgressChanged);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.fileUploaded);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.fileUploadFailed);
        }
        AndroidUtilities.cancelRunOnUIThread(check);
        AndroidUtilities.runOnUIThread(check, CHECK_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        String location = (String) args[0];
        if (id == NotificationCenter.fileUploadProgressChanged) {
            long loaded = (Long) args[1], total = (Long) args[2];
            progress.put(account + location, new long[]{loaded, Math.max(loaded, total)});
        } else {
            progress.remove(account + location);
        }
        updateProgress();
    }

    private void updateProgress() {
        if (builder == null) return;
        long loaded = 0, total = 0;
        for (long[] p : progress.values()) {
            loaded += p[0];
            total += p[1];
        }
        int percent = total > 0 ? (int) (loaded * 100 / total) : -1;
        if (percent == lastPercent) return;
        lastPercent = percent;
        builder.setProgress(100, Math.max(0, percent), percent < 0);
        builder.setContentText(LocaleController.getString(R.string.UploadingStory) + (percent >= 0 ? " " + percent + "%" : ""));
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e); // no notification permission: the service still keeps the upload alive
        }
    }

    /** Android 15 caps dataSync services at 6 h a day. */
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
        AndroidUtilities.cancelRunOnUIThread(check);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.fileUploadProgressChanged);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.fileUploaded);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.fileUploadFailed);
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
    }
}
