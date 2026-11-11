package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Friend Map background mode: keeps sending our position to friends while the app is closed.
 * Runs only when the user turned background mode on and has at least one friend.
 */
public class UMessageFriendLocationService extends Service {

    private static final String ACTION_STOP = "org.telegram.messenger.UMFRIEND_STOP";
    private static final int NOTIFICATION_ID = 0x75f1;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            UMessageFriendLocations.dispatchLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private boolean listening;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
    /** Friends' positions are polled so nearby and zone alerts arrive while closed: every 2 min with a zone, else every 5. */
    private final Runnable zonePoll = new Runnable() {
        @Override
        public void run() {
            boolean zones = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    UMessageFriendLocations c = UMessageFriendLocations.getInstance(a);
                    if (c.hasActiveZones()) zones = true;
                    if (c.hasFriends()) c.refreshLocations();
                }
            }
            handler.postDelayed(this, zones ? 120_000 : 300_000);
        }
    };

    /** One GPS fix every 10 minutes: works without internet, and the receiver sleeps in between. */
    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handler.removeCallbacks(gpsTimeout);
            try {
                ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
            } catch (Throwable ignore) {
            }
            UMessageFriendLocations.dispatchLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    /** No sky (indoors): give up after a minute instead of keeping the receiver on. */
    private final Runnable gpsTimeout = () -> {
        try {
            ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(gpsListener);
        } catch (Throwable ignore) {
        }
    };
    private final Runnable gpsPoll = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            handler.postDelayed(this, 600_000);
            if (!UMessageFriendLocations.hasPermission(UMessageFriendLocationService.this)) return;
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
                lm.removeUpdates(gpsListener);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener, Looper.getMainLooper());
                handler.removeCallbacks(gpsTimeout);
                handler.postDelayed(gpsTimeout, 60_000);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    };

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, UMessageFriendLocationService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, UMessageFriendLocationService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // a system restart after the process was killed: Android forbids starting a location foreground
            // service from the background (ForegroundServiceStartNotAllowedException); the app restarts it when opened
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            UMessageFriendLocations.setBackgroundEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Intent open = new Intent(this, LaunchActivity.class);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent stop = new Intent(this, UMessageFriendLocationService.class).setAction(ACTION_STOP);
            PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationsController.checkOtherNotificationsChannel();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.live_loc)
                    .setContentTitle(LocaleController.getString(R.string.UMessageFriendMap))
                    .setContentText(LocaleController.getString(R.string.UMessageFriendMapBackgroundNotification))
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(0, LocaleController.getString(R.string.UMessageFriendMapBackgroundStop), stopIntent);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!listening && UMessageFriendLocations.hasPermission(this)) {
            try {
                // battery: Wi-Fi/cell fixes need the network, so one GPS fix every 10 min (gpsPoll) covers offline;
                // the passive provider picks up GPS fixes other apps already paid for
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120_000, 50, listener, Looper.getMainLooper());
                }
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000, 50, listener, Looper.getMainLooper());
                listening = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (networkCallback == null && Build.VERSION.SDK_INT >= 24) {
            try {
                // positions taken offline are sent as soon as the internet is back
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        handler.post(UMessageFriendLocations::onNetworkAvailable);
                    }
                };
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(networkCallback);
            } catch (Throwable e) {
                FileLog.e(e);
                networkCallback = null;
            }
        }
        handler.removeCallbacks(zonePoll);
        handler.postDelayed(zonePoll, 5_000);
        handler.removeCallbacks(gpsPoll);
        handler.postDelayed(gpsPoll, 10_000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        UMessageFriendLocations.onServiceStopped();
        handler.removeCallbacks(zonePoll);
        handler.removeCallbacks(gpsPoll);
        handler.removeCallbacks(gpsTimeout);
        try {
            ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(gpsListener);
        } catch (Throwable ignore) {
        }
        if (networkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignore) {
            }
            networkCallback = null;
        }
        if (listening) {
            try {
                ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(listener);
            } catch (Throwable ignore) {
            }
            listening = false;
        }
    }
}
