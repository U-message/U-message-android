package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UMessageSecurityGuard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Second line of defence against droppers: a clean-looking APK opened from a chat installs
 * a second app that carries the real payload. Our pre-open scan never sees that second APK,
 * so every new install on the device is inspected here and the user is told to remove it.
 */
public class UMessagePackageWatcher extends BroadcastReceiver {

    private static final String CHANNEL_ID = "umessage_security";
    private static final Set<String> SYSTEM_INSTALLERS = new HashSet<>(Arrays.asList(
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller", "com.android.permissioncontroller"));
    private static final Set<String> TRUSTED_INSTALLERS = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks",
            "com.oppo.market", "com.heytap.market", "com.vivo.appstore", "com.amazon.venezia", "com.aurora.store"));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) && Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            return;
        }
        final String packageName = intent.getData().getSchemeSpecificPart();
        if (TextUtils.isEmpty(packageName) || packageName.equals(context.getPackageName())) {
            return;
        }
        final PendingResult result = goAsync();
        SCAN_QUEUE.postRunnable(() -> {
            try {
                if (markInspected(context.getApplicationContext(), packageName)) {
                    inspect(context.getApplicationContext(), packageName);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                result.finish();
            }
        });
    }

    private static final String SEEN_PREFS = "umessage_pkg_seen";
    /** Dedicated low-priority thread: scanning installed APKs must not stall Telegram work on globalQueue. */
    private static final DispatchQueue SCAN_QUEUE = new DispatchQueue("umessagePkgScan", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private static boolean registered;

    /**
     * Android 8+ no longer delivers PACKAGE_ADDED to a receiver declared only in the manifest,
     * so the live check needs this registration while the app runs; the startup scan covers the rest.
     */
    public static void registerLive(Context context) {
        if (registered) {
            return;
        }
        registered = true;
        final android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        final Context app = context.getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(new UMessagePackageWatcher(), filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(new UMessagePackageWatcher(), filter);
        }
    }

    /** False when this exact version was already inspected, so the live check and the startup scan never warn twice. */
    private static boolean markInspected(Context app, String packageName) throws PackageManager.NameNotFoundException {
        final PackageInfo pkg = app.getPackageManager().getPackageInfo(packageName, 0);
        final long when = Math.max(pkg.firstInstallTime, pkg.lastUpdateTime);
        final SharedPreferences seen = app.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
        if (seen.getLong(packageName, 0) == when) {
            return false;
        }
        seen.edit().putLong(packageName, when).apply();
        return true;
    }

    private static final long RECENT_WINDOW = 14L * 24 * 60 * 60 * 1000;

    /**
     * Called on every app start. The PACKAGE_ADDED receiver is skipped by Android while this app is
     * in the stopped state (freshly installed, force-stopped, or never opened), so anything installed
     * during that gap is missed. This re-scans every third-party package installed recently and
     * warns about any that are dangerous, guaranteeing a dropper's second stage is caught even if the
     * live broadcast never arrived.
     */
    public static void scanInstalledOnStartup(Context context) {
        SCAN_QUEUE.postRunnable(() -> {
            try {
                Context app = context.getApplicationContext();
                PackageManager pm = app.getPackageManager();
                SharedPreferences seen = app.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                List<PackageInfo> installed = pm.getInstalledPackages(0);
                for (PackageInfo pkg : installed) {
                    String packageName = pkg.packageName;
                    if (packageName == null || packageName.equals(app.getPackageName())) {
                        continue;
                    }
                    ApplicationInfo ai = pkg.applicationInfo;
                    if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                        continue; // preinstalled system apps
                    }
                    long when = Math.max(pkg.firstInstallTime, pkg.lastUpdateTime);
                    if (now - when > RECENT_WINDOW) {
                        continue;
                    }
                    if (seen.getLong(packageName, 0) == when) {
                        continue; // already inspected this exact version
                    }
                    seen.edit().putLong(packageName, when).apply();
                    try {
                        inspect(app, packageName);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void inspect(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        String installer = getInstaller(pm, packageName);
        boolean fromChatApk = UMessageSecurityGuard.wasOpenedFromChat(installer);
        // Store installs are not dropper stages; anything via the system installer or another app is inspected.
        // A package using our name is always inspected: its certificate decides whether it is a fake U message.
        if (installer != null && TRUSTED_INSTALLERS.contains(installer) && !packageName.startsWith("uz.umessage.app")) {
            if (!UMessageSecurityGuard.wasOpenedFromChat(packageName)) {
                return;
            }
        }
        PackageInfo info = pm.getPackageInfo(packageName, UMessageSecurityGuard.INSPECT_FLAGS);
        if (isOwnFamily(context, pm, packageName)) {
            return; // our own app and its rebrand lineage are never the threat
        }
        UMessageSecurityGuard.ApkReport report = UMessageSecurityGuard.analyzePackage(pm, info, new UMessageSecurityGuard.ApkReport());
        if (report.trusted) {
            return;
        }
        // A second stage may ask for little and keep its intent in the code, so read the installed package too.
        if (info.applicationInfo != null && !TextUtils.isEmpty(info.applicationInfo.sourceDir)) {
            UMessageSecurityGuard.inspectContents(new java.io.File(info.applicationInfo.sourceDir), null, report);
        }
        if (fromChatApk) {
            report.criticalRisks.add(0, LocaleController.getString(R.string.UMessageInstalledByChatApk));
        }
        // A single dangerous capability is common in legitimate apps (a messenger reads SMS codes,
        // a browser can install packages). Only a COMBINATION, a malware code signature, or an app
        // that a chat-delivered APK installed is treated as a real threat.
        boolean warrant = fromChatApk || report.isMalware() || dangerousCombo(info);
        if (!warrant || !report.isDangerous()) {
            return;
        }
        warn(context, pm, packageName, installer, report);
    }

    /**
     * Our rebrand lineage: the package name alone can be copied by anyone, so its current signing certificate must be
     * our private one. checkSignatures() is not enough: it also matches the public Telegram key from our rotation lineage.
     */
    private static boolean isOwnFamily(Context context, PackageManager pm, String packageName) {
        if (!packageName.startsWith("uz.umessage.app") && !packageName.startsWith("org.telegram.messenger")) {
            return false;
        }
        try {
            return UMessageUpdater.hasOurCert(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo);
        } catch (Throwable e) {
            return false;
        }
    }

    private static final Set<String> SMS_PERMS = new HashSet<>(Arrays.asList(
            "android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH"));

    /** Counts distinct dangerous CAPABILITIES; two or more is the malware pattern. */
    private static boolean dangerousCombo(PackageInfo info) {
        boolean sms = false, install = false, accessibility = false, notifListener = false, deviceAdmin = false, overlay = false;
        if (info.requestedPermissions != null) {
            for (String perm : info.requestedPermissions) {
                if (perm == null) continue;
                if (SMS_PERMS.contains(perm)) sms = true;
                else if ("android.permission.REQUEST_INSTALL_PACKAGES".equals(perm) || "android.permission.INSTALL_PACKAGES".equals(perm)) install = true;
                else if ("android.permission.SYSTEM_ALERT_WINDOW".equals(perm)) overlay = true;
            }
        }
        if (info.services != null) {
            for (android.content.pm.ServiceInfo si : info.services) {
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(si.permission)) accessibility = true;
                else if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(si.permission)) notifListener = true;
            }
        }
        if (info.receivers != null) {
            for (android.content.pm.ActivityInfo ri : info.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)) deviceAdmin = true;
            }
        }
        int categories = (sms ? 1 : 0) + (install ? 1 : 0) + (accessibility ? 1 : 0)
                + (notifListener ? 1 : 0) + (deviceAdmin ? 1 : 0) + (overlay ? 1 : 0);
        // Accessibility or notification-listener plus any other capability is already decisive.
        if ((accessibility || notifListener || deviceAdmin) && categories >= 2) {
            return true;
        }
        return categories >= 2 && (sms || install);
    }


    /** Blocking dialog when an activity is up (no permission needed); notification otherwise. */
    private static void warn(Context context, PackageManager pm, String packageName, String installer, UMessageSecurityGuard.ApkReport report) {
        final String appName = TextUtils.isEmpty(report.appName) ? packageName : report.appName;
        final String installerLabel = label(pm, installer);
        final CharSequence body = buildBody(appName, installerLabel, report);
        AndroidUtilities.runOnUIThread(() -> {
            Activity activity = org.telegram.ui.LaunchActivity.instance;
            boolean shown = false;
            if (AndroidUtilities.isContextSafe(activity)) {
                try {
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle(LocaleController.getString(R.string.UMessageInstalledDangerTitle));
                    builder.setMessage(body);
                    builder.setPositiveButton(LocaleController.getString(R.string.UMessageUninstallApp), (d, w) -> {
                        try {
                            Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
                            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(uninstall);
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    });
                    builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                    AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                    TextView positive = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                    if (positive != null) {
                        positive.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                    }
                    shown = true;
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else {
            }
            if (!shown) {
                notify(context, pm, packageName, installer, report);
            }
        });
    }

    private static CharSequence buildBody(String appName, String installerLabel, UMessageSecurityGuard.ApkReport report) {
        StringBuilder text = new StringBuilder();
        text.append(LocaleController.formatString(R.string.UMessageInstalledDangerText, appName, installerLabel)).append('\n');
        for (String risk : report.criticalRisks) {
            text.append("\n• ").append(risk);
        }
        text.append("\n\n").append(LocaleController.getString(R.string.UMessageApkDangerFooter));
        return text.toString();
    }

    private static String getInstaller(PackageManager pm, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo source = pm.getInstallSourceInfo(packageName);
                String initiating = source.getInitiatingPackageName();
                if (!TextUtils.isEmpty(initiating) && !SYSTEM_INSTALLERS.contains(initiating) && !TRUSTED_INSTALLERS.contains(initiating)) {
                    return initiating;
                }
                String originating = source.getOriginatingPackageName();
                if (!TextUtils.isEmpty(originating)) {
                    return originating;
                }
                return initiating != null ? initiating : source.getInstallingPackageName();
            }
            //noinspection deprecation
            return pm.getInstallerPackageName(packageName);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String label(PackageManager pm, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return "?";
        }
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))) + " (" + packageName + ")";
        } catch (Throwable e) {
            return packageName;
        }
    }

    private static void notify(Context context, PackageManager pm, String packageName, String installer, UMessageSecurityGuard.ApkReport report) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    LocaleController.getString(R.string.UMessageSecurityChannel), NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(channel);
        }
        StringBuilder text = new StringBuilder();
        String appName = TextUtils.isEmpty(report.appName) ? packageName : report.appName;
        text.append(LocaleController.formatString(R.string.UMessageInstalledDangerText, appName, label(pm, installer))).append('\n');
        for (String risk : report.criticalRisks) {
            text.append("• ").append(risk).append('\n');
        }
        text.append('\n').append(LocaleController.getString(R.string.UMessageApkDangerFooter));

        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent uninstallIntent = PendingIntent.getActivity(context, packageName.hashCode(), uninstall, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.UMessageInstalledDangerTitle))
                .setContentText(LocaleController.formatString(R.string.UMessageInstalledDangerText, appName, label(pm, installer)))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setColor(0xffd32f2f)
                .setContentIntent(uninstallIntent)
                .addAction(0, LocaleController.getString(R.string.UMessageUninstallApp), uninstallIntent)
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(("umessage_pkg:" + packageName).hashCode(), notification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
