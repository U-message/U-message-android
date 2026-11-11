package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UMessageUpdateSheet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * In-app update for the APK build (never the Play build). Reads app/latest.json from R2 (published from
 * the admin panel "Yangilanish" tab), asks "Yangilash / Keyinroq" with the admin's text, downloads the APK
 * parts, checks SHA-256 + our signing certificate, then hands the file to the system installer.
 * Nothing is installed without the user pressing install.
 */
public final class UMessageUpdater {

    private static final String MANIFEST_URL = "https://pub-2f53c2d97246487ab8508622527c38d9.r2.dev/app/latest.json";
    private static final String USER_URL = "https://pub-2f53c2d97246487ab8508622527c38d9.r2.dev/app/u/";
    private static final String PREFS = "umessage_update";
    // checked on every app open (at most once a minute): admin "Yuborish" / "Hammaga eslatish" arrive right away
    private static final long CHECK_INTERVAL = 60L * 1000;
    private static final long LATER_INTERVAL = 24L * 60 * 60 * 1000;

    private static boolean checking;
    private static boolean dialogShown;
    private static volatile boolean cancelled;
    private static boolean downloading;

    private UMessageUpdater() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static java.lang.ref.WeakReference<Activity> lastActivity;

    /** The admin sent this update to this user ("Yuborish" in the admin panel), via the register reply. */
    public static void offer(JSONObject manifest) {
        final Activity activity = lastActivity == null ? null : lastActivity.get();
        if (activity == null || checking || dialogShown || downloading || isPlayInstall() || !loggedIn()) {
            return;
        }
        onManifest(activity, manifest);
    }

    private static boolean retryPosted;

    /** A new user is not offered an update on the sign-up screens: it waits until an account is logged in. */
    private static boolean loggedIn() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return true;
            }
        }
        return false;
    }

    public static void check(Activity activity) {
        lastActivity = new java.lang.ref.WeakReference<>(activity);
        if (checking || dialogShown || downloading || activity == null || isPlayInstall()) {
            return;
        }
        if (!loggedIn()) {
            // checked again shortly after the login, not only on the next resume
            if (!retryPosted) {
                retryPosted = true;
                AndroidUtilities.runOnUIThread(() -> {
                    retryPosted = false;
                    final Activity last = lastActivity == null ? null : lastActivity.get();
                    if (last != null && !last.isFinishing()) {
                        check(last);
                    }
                }, 30_000);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - prefs().getLong("checked", 0) < CHECK_INTERVAL) {
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject manifest = null;
            try {
                byte[] data = download(MANIFEST_URL + "?t=" + now / 60000);
                if (data != null) {
                    manifest = new JSONObject(new String(data, "UTF-8"));
                    prefs().edit().putLong("checked", now).apply();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            // sent to this user from the admin panel ("Yuborish"): app/u/<user id>.json, newer than the public one
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                try {
                    byte[] data = download(USER_URL + UserConfig.getInstance(a).getClientUserId() + ".json?t=" + now / 60000);
                    if (data != null) {
                        JSONObject sent = new JSONObject(new String(data, "UTF-8"));
                        if (sent.optLong("versionCode") > currentVersionCode() && (manifest == null || !manifest.optBoolean("active", true)
                                || manifest.optLong("versionCode") <= currentVersionCode() || sent.optLong("remindAt") > manifest.optLong("remindAt"))) {
                            manifest = sent;
                        }
                    }
                } catch (Throwable ignore) {
                    // no file: nothing was sent to this user
                }
            }
            final JSONObject result = manifest;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (result != null) {
                    onManifest(activity, result);
                }
            });
        });
    }

    private static void onManifest(Activity activity, JSONObject manifest) {
        long versionCode = manifest.optLong("versionCode");
        org.json.JSONArray urls = manifest.optJSONArray("urls");
        if (urls == null && !TextUtils.isEmpty(manifest.optString("url"))) {
            urls = new org.json.JSONArray().put(manifest.optString("url"));
        }
        String sha256 = manifest.optString("sha256").toLowerCase(Locale.ROOT);
        if (!manifest.optBoolean("active", true) || versionCode <= currentVersionCode() || urls == null || urls.length() == 0 || sha256.length() != 64) {
            return;
        }
        final org.json.JSONArray parts = urls;
        boolean force = manifest.optBoolean("force");
        // "Hammaga qayta eslatish" / per-user send: a reminder newer than the last "Keyinroq" shows the dialog again
        final long later = prefs().getLong("later:" + versionCode, 0);
        final long remindAt = manifest.optLong("remindAt");
        if (!force && System.currentTimeMillis() - later < LATER_INTERVAL && remindAt <= later) {
            return;
        }
        if (activity.isFinishing() || Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
            return;
        }
        // the admin's title/text replace the defaults, exactly as the admin panel preview shows them
        String title = manifest.optString("title").trim();
        String notes = manifest.optString("notes").trim();
        UMessageUpdateSheet sheet = new UMessageUpdateSheet(activity, !force);
        sheet.setOnDismissListener(() -> dialogShown = false);
        Runnable onLater = force ? null : () -> prefs().edit().putLong("later:" + versionCode, System.currentTimeMillis()).apply();
        if (onLater != null) {
            // checks run on every open now: a shown (not forced) offer waits a day or a new reminder, as after "Keyinroq"
            onLater.run();
        }
        sheet.showOffer(TextUtils.isEmpty(title) ? LocaleController.getString(R.string.UMessageUpdateTitle) : title,
                manifest.optString("versionName"), manifest.optLong("size"),
                TextUtils.isEmpty(notes) ? LocaleController.formatString(R.string.UMessageUpdateText, manifest.optString("versionName")) : notes,
                () -> startDownload(activity, sheet, versionCode, parts, sha256, manifest.optLong("size"), manifest.optLong("partSize"), onLater), onLater);
        dialogShown = true;
        sheet.show();
    }

    /** Downloads into the same sheet: progress, then "ready" with the checks, or a plain-words error. */
    private static void startDownload(Activity activity, UMessageUpdateSheet sheet, long versionCode, org.json.JSONArray urls, String sha256, long size, long partSize, Runnable later) {
        // Until the new version is actually installed, the next app start offers it again (no 3h wait).
        // a failed or cancelled download must not hide the offer for a day: only "Keyinroq" sets it again
        prefs().edit().remove("checked").remove("later:" + versionCode).apply();
        File file = new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "cache"), "umessage-update.apk");
        cancelled = false;
        downloading = true;
        sheet.showProgress(() -> {
            cancelled = true;
            sheet.dismiss();
        });
        UMessageUploadService.hold(); // keeps downloading after the user leaves the app
        final long[] got = {0};
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            boolean badFile = false;
            try {
                // hashing the whole APK is slow: done here, not on the UI thread
                if (file.exists() && sha256.equals(fileSha256(file))) {
                    ok = true;
                } else {
                    File dir = file.getParentFile();
                    if (dir != null && !dir.exists()) {
                        dir.mkdirs();
                    }
                    // the partial file is kept per version, so a broken connection resumes where it stopped
                    File part = new File(dir, "umessage-update-" + sha256.substring(0, 12) + ".part");
                    File[] old = dir == null ? null : dir.listFiles((d, name) -> name.startsWith("umessage-update") && name.endsWith(".part") && !name.equals(part.getName()));
                    if (old != null) for (File f : old) f.delete();
                    if (downloadParts(urls, part, size, partSize, (done, total) -> {
                        got[0] = done;
                        AndroidUtilities.runOnUIThread(() -> sheet.setProgress(done, total));
                    }) && !cancelled) {
                        if (sha256.equals(fileSha256(part)) && isOurSignedApk(part)) {
                            file.delete();
                            ok = part.renameTo(file);
                        } else {
                            badFile = true;
                        }
                    }
                    if (badFile) {
                        part.delete();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean success = ok, rejected = badFile;
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                UMessageUploadService.release();
                if (cancelled || activity.isFinishing() || !sheet.isShowing()) {
                    return;
                }
                if (success) {
                    sheet.showReady(() -> install(activity, file));
                } else if (rejected) {
                    sheet.showError(LocaleController.getString(R.string.UMessageUpdateBadFile), null, later != null ? later : sheet::dismiss);
                } else {
                    String text = got[0] > 0
                            ? LocaleController.formatString(R.string.UMessageUpdateResume, AndroidUtilities.formatFileSize(got[0]))
                            : LocaleController.getString(R.string.UMessageUpdateFailed);
                    sheet.showError(text, () -> startDownload(activity, sheet, versionCode, urls, sha256, size, partSize, later), later);
                }
            });
        });
    }

    private static void install(Activity activity, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(activity, null).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = Build.VERSION.SDK_INT >= 24
                    ? FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
                    : Uri.fromFile(file);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private interface Progress {
        void onProgress(long done, long total);
    }

    /** Downloads the APK parts in order into one file; progress over the whole size. */
    private static boolean downloadParts(org.json.JSONArray urls, File target, long size, long partSize, Progress progress) throws Exception {
        // resume: keep the whole parts already on disk, refetch from the first incomplete one
        int first = partSize > 0 && target.exists() ? (int) Math.min(urls.length(), target.length() / partSize) : 0;
        if (first > 0 && first * partSize >= size) {
            first = urls.length() - 1; // refetch the last (shorter) part to be safe
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(target, "rw")) {
            raf.setLength(first * Math.max(0, partSize));
        }
        long done = first * Math.max(0, partSize);
        long lastReport = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream out = new FileOutputStream(target, true)) {
            for (int i = first; i < urls.length(); i++) {
                HttpURLConnection connection = (HttpURLConnection) new URL(urls.getString(i)).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setUseCaches(false);
                try {
                    if (connection.getResponseCode() != 200) {
                        return false;
                    }
                    try (InputStream in = connection.getInputStream()) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            if (cancelled) {
                                return false;
                            }
                            out.write(buffer, 0, read);
                            done += read;
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 150 || done == size) {
                                lastReport = now;
                                progress.onProgress(done, size);
                            }
                        }
                    }
                } finally {
                    connection.disconnect();
                }
            }
        }
        return size <= 0 || done == size;
    }

    /** Small JSON download (the manifest), capped at 64 KB. */
    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < 64 * 1024) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String fileSha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * SHA-256 of the private U message signing certificates. The first is the current release key; the second is the
     * personal key that releases move to next (trusted ahead of time so installed builds keep updating in-app after
     * that rotation). The old Telegram key is public and is never listed here.
     */
    public static final String OUR_CERT_SHA256 = "f806aa4271e73234f0cf016552a5d5726d6f5e4f2da3b0a305b580209a5f644d";
    public static final String OUR_NEXT_CERT_SHA256 = "8372f02c82a324bf670fe156941ed4a41fc315115bba044f26927d5799a69be6";

    public static boolean isOurCertDigest(String digest) {
        return OUR_CERT_SHA256.equals(digest) || OUR_NEXT_CERT_SHA256.equals(digest);
    }

    private static boolean isOurCert(Signature[] signers) {
        if (signers == null) return false;
        try {
            for (Signature s : signers) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
                if (isOurCertDigest(sb.toString())) return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Same package name and signed with the private U message certificate. minSdk is 28, so the v3 signing info is
     * always available; an APK signed only with the public Telegram key is never accepted.
     */
    static boolean isOurSignedApk(File file) {
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            String own = ApplicationLoader.applicationContext.getPackageName();
            PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || !own.equals(archive.packageName) || archive.signingInfo == null) {
                return false;
            }
            return isOurCert(archive.signingInfo.getApkContentsSigners());
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** True when the signing certificate of an APK's v3/v2 signers is the private U message one. */
    static boolean hasOurCert(android.content.pm.SigningInfo info) {
        return info != null && isOurCert(info.getApkContentsSigners());
    }

    @SuppressWarnings("deprecation")
    private static long versionCode;

    static long currentVersionCode() {
        if (versionCode != 0) {
            return versionCode;
        }
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable e) {
            return Long.MAX_VALUE;
        }
    }

    /** Google Play forbids self-updating apps; the store build updates through Play only. */
    @SuppressWarnings("deprecation")
    private static boolean isPlayInstall() {
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            String own = ApplicationLoader.applicationContext.getPackageName();
            String installer = Build.VERSION.SDK_INT >= 30
                    ? pm.getInstallSourceInfo(own).getInstallingPackageName()
                    : pm.getInstallerPackageName(own);
            return "com.android.vending".equals(installer);
        } catch (Throwable e) {
            return false;
        }
    }
}
