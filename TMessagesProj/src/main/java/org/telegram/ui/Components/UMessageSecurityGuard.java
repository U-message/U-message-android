package org.telegram.ui.Components;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.net.IDN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;

/** Centralized last-mile checks before an APK or suspicious external link is opened. */
public final class UMessageSecurityGuard {

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Set<String> SHORTENER_HOSTS = new HashSet<>();

    static {
        String[] hosts = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "rebrand.ly", "shorturl.at", "tiny.cc", "ow.ly",
                "clck.ru", "vk.cc", "u.to", "surl.li", "goo.su", "s.id", "t.ly", "v.gd", "qps.ru", "bitly.com", "tinyurl.ru"};
        for (String host : hosts) {
            SHORTENER_HOSTS.add(host);
        }
    }

    private UMessageSecurityGuard() {
    }

    public static boolean isApk(String fileName, String mimeType) {
        if (APK_MIME.equalsIgnoreCase(mimeType)) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm");
    }

    public static boolean guardApk(Activity activity, File file, String fileName, String mimeType,
                                   Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        if (!isApk(fileName, mimeType)) {
            return false;
        }
        // A package must never fall through when no safe UI is available.
        if (activity == null || activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            return true;
        }

        scanApk(activity, file, fileName, resourcesProvider, openAction);
        return true;
    }

    private static final String PREFS = "umessage_security";
    private static final long OPENED_APK_TTL = 7L * 24 * 60 * 60 * 1000;

    private static void scanApk(Activity activity, File file, String fileName,
                                Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        Utilities.globalQueue.postRunnable(() -> {
            ApkReport report = inspectApk(file, fileName);
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    return;
                }
                if (!report.isDangerous()) {
                    rememberOpenedApk(report.packageName);
                    openAction.run();
                } else {
                    showApkDanger(activity, report, resourcesProvider, () -> {
                        rememberOpenedApk(report.packageName);
                        openAction.run();
                    });
                }
            });
        });
    }

    /** Packages the user chose to open from a chat; used to trace second-stage installs. */
    private static void rememberOpenedApk(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("opened:" + packageName, System.currentTimeMillis()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean wasOpenedFromChat(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        long time = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("opened:" + packageName, 0);
        return time > 0 && System.currentTimeMillis() - time < OPENED_APK_TTL;
    }

    private static void showApkDanger(Activity activity, ApkReport report,
                                      Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(LocaleController.getString(report.isMalware() ? R.string.UMessageApkMalwareTitle : R.string.UMessageApkDangerTitle));
        builder.setMessage(report.buildMessage());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, activity, resourcesProvider, true);
    }

    @SuppressWarnings("deprecation")
    private static ApkReport inspectApk(File file, String fileName) {
        ApkReport report = new ApkReport();
        if (file == null || !file.isFile() || !file.canRead()) {
            report.critical(LocaleController.getString(R.string.UMessageApkUnreadableRisk));
            return report;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm")) {
            report.critical(LocaleController.getString(R.string.UMessageApkBundleRisk));
            return report;
        }
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), INSPECT_FLAGS);
            if (info == null) {
                report.critical(LocaleController.getString(R.string.UMessageApkParseRisk));
                return report;
            }
            ApplicationInfo applicationInfo = info.applicationInfo;
            if (applicationInfo != null) {
                applicationInfo.sourceDir = file.getAbsolutePath();
                applicationInfo.publicSourceDir = file.getAbsolutePath();
            }
            analyzePackage(pm, info, report);
            if (report.trusted) {
                return report;
            }
            inspectContents(file, fileName, report);
        } catch (Throwable e) {
            FileLog.e(e);
            report.critical(LocaleController.getString(R.string.UMessageApkParseRisk));
        }
        return report;
    }

    /** Everything beyond the manifest. Also runs on a package a dropper already installed, whose code the manifest never shows. */
    public static void inspectContents(File file, String fileName, ApkReport report) {
        inspectLure(fileName, report);
        inspectCode(file, report);
        if (hasEmbeddedPayload(file)) {
            // Big apps ship nested APKs legitimately (Wear OS companions, WebView stubs, dynamic features).
            if (report.smallApp) {
                report.critical(LocaleController.getString(R.string.UMessageRiskEmbeddedPayload));
            } else {
                report.info(LocaleController.getString(R.string.UMessageRiskEmbeddedPayload));
            }
        }
        applyVerdict(report);
    }

    /**
     * Single signals only say "risky". Combinations that legitimate apps never ship together
     * (SMS + lure name, Accessibility + installer, ...) are reported as confirmed malware.
     */
    private static void applyVerdict(ApkReport report) {
        boolean sms = report.has(R.string.UMessageRiskSms) || report.has(R.string.UMessageRiskSmsCode);
        boolean lure = report.has(R.string.UMessageRiskLureName) || report.has(R.string.UMessageRiskBankImpersonation);
        boolean install = report.has(R.string.UMessageRiskInstallApps);
        boolean accessibility = report.has(R.string.UMessageRiskAccessibility);
        boolean notifications = report.has(R.string.UMessageRiskNotifications);
        boolean admin = report.has(R.string.UMessageRiskDeviceAdmin);
        boolean overlay = report.infoRisks.contains(LocaleController.getString(R.string.UMessageRiskOverlay));
        boolean hidden = report.has(R.string.UMessageRiskPacker) || report.has(R.string.UMessageRiskAntiAnalysis)
                || report.has(R.string.UMessageRiskOldTarget);
        boolean payload = report.has(R.string.UMessageRiskEmbeddedPayload) && report.smallApp
                && (install || report.has(R.string.UMessageRiskDropperCode) || report.infoRisks.contains(LocaleController.getString(R.string.UMessageRiskDynamicCode)));
        // Permission combinations alone stay "risky": dialers, launchers and keyboards legitimately ask
        // for them. Malware needs a lure, a stealer pattern or code/packaging no honest app ships.
        // Public test keys also sign honest hobby builds, so the key alone stays "risky".
        boolean testKey = report.has(R.string.UMessageRiskTestKey) && (sms || install || accessibility || notifications || admin || lure || hidden);
        // Capability combinations only convict a small app: Uzbek SMS stealers and droppers are tiny shells,
        // while big honest apps (Google Messages: SMS + install, Photos: "photo" name + install) ship the same mix.
        final boolean combo = sms && (lure || install || hidden)
                || accessibility && (install || lure) && (sms || overlay || notifications || hidden || lure)
                || install && (lure || hidden)
                || notifications && lure
                || hidden && lure;
        if (payload || report.has(R.string.UMessageRiskDoubleExtension) || testKey || combo && report.smallApp) {
            report.malware = true;
        }
        // "Video Player" or "Photo Editor" alone is not a threat: the word only matters next to a real capability.
        final String lureRisk = LocaleController.getString(R.string.UMessageRiskLureName);
        if (!report.malware && report.criticalRisks.size() == 1 && report.criticalRisks.contains(lureRisk)) {
            report.criticalRisks.clear();
            report.info(lureRisk);
        }
    }

    @SuppressWarnings("deprecation")
    public static final int INSPECT_FLAGS = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
            | PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;

    // Signing certificates (SHA-256) of publishers we know: a partner launcher and U Phone. Our own
    // certificate is added at runtime. A package signed by one of these is never reported.
    private static final Set<String> TRUSTED_CERTS = new HashSet<>(java.util.Arrays.asList(
            "f70d1601567c8276ba64baf4f6d494cecc8976ff8cbf2b8854b46cdccd17c897",
            "4590ebbfee380c6caef37a750b0843cd9ffcf571364c7754869261fbe9c6f6ac"));

    private static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Only the fixed publisher list. Our own GET_SIGNATURES is NOT used: after the v3
     * key rotation it returns the oldest signer, the public Telegram key that anyone can sign with.
     */
    private static boolean isTrustedCert(String digest) {
        return TRUSTED_CERTS.contains(digest);
    }

    /** Current signers (v3 rotation aware); the deprecated signatures field would report the oldest, public key. */
    @SuppressWarnings("deprecation")
    private static android.content.pm.Signature[] currentSigners(PackageInfo info) {
        if (info.signingInfo != null) {
            android.content.pm.Signature[] s = info.signingInfo.getApkContentsSigners();
            if (s != null && s.length > 0) {
                return s;
            }
        }
        return info.signatures;
    }

    /**
     * AOSP test/platform keys are public: anyone can sign with them, and they are what Uzbek SMS-stealer
     * kits ship with. A real publisher always has its own key.
     */
    @SuppressWarnings("deprecation")
    private static void inspectSigner(PackageInfo info, ApkReport report) {
        android.content.pm.Signature[] signers = currentSigners(info);
        boolean ourName = info.packageName != null && info.packageName.startsWith("uz.umessage.app");
        if (signers == null || signers.length == 0) {
            if (ourName) {
                report.malware(LocaleController.getString(R.string.UMessageRiskFakeUMessage));
            }
            return;
        }
        boolean trusted = true;
        for (android.content.pm.Signature signature : signers) {
            byte[] der = signature.toByteArray();
            String digest = sha256(der);
            if (!isTrustedCert(digest)) {
                trusted = false;
            }
            if (AOSP_TEST_KEYS.contains(digest)) {
                report.critical(LocaleController.getString(R.string.UMessageRiskTestKey));
            }
        }
        // Our package name signed with any other certificate (including the public Telegram key) is a fake U message.
        if (ourName) {
            trusted = false;
            report.malware(LocaleController.getString(R.string.UMessageRiskFakeUMessage));
        }
        report.trusted = trusted;
    }

    /**
     * SHA-256 of the public AOSP keys in build/target/product/security (testkey, platform, shared, media,
     * networkstack, sdk_sandbox, bluetooth): anyone can sign with them. Matched by digest only: Google's own
     * release keys (Gmail, YouTube) carry the very same name, O=Android / android@android.com, so a name match
     * reported genuine Google apps as test-signed.
     */
    private static final Set<String> AOSP_TEST_KEYS = new HashSet<>(java.util.Arrays.asList(
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc",
            "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8",
            "28bbfe4a7b97e74681dc55c2fbb6ccb8d6c74963733f6af6ae74d8c3a6e879fd",
            "465983f7791f2abeb43ea2cbdc7f21a8260b72bc08a55c839fc1a43bc741a81e",
            "e1dbadce60dc080d15b58a014b0dcf9400e24de23fa00b287a5a982bfebda2ee",
            "abf21f9e2af1d881cc673fddcefa6ed9c269a437bd64b279cf45844cfd589126",
            "a6ccc500ff0e7421200eb66a7fe174ef1b00e52ca91727070cbedf061ff76c35"));

    /** Shared by the pre-open scan and the post-install watcher. */
    public static ApkReport analyzePackage(PackageManager pm, PackageInfo info, ApkReport report) {
        report.packageName = info.packageName;
        inspectSigner(info, report);
        if (report.trusted) {
            return report;
        }
        ApplicationInfo applicationInfo = info.applicationInfo;
        if (applicationInfo != null) {
            try {
                report.appName = String.valueOf(pm.getApplicationLabel(applicationInfo));
            } catch (Exception ignore) {
            }
            // Apps targeting < 23 get every manifest permission at install time without asking.
            if (applicationInfo.targetSdkVersion > 0 && applicationInfo.targetSdkVersion < 23) {
                report.critical(LocaleController.getString(R.string.UMessageRiskOldTarget));
            }
        }
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) {
                addPermissionRisk(report, permission);
            }
        }
        if (info.services != null) {
            for (ServiceInfo service : info.services) {
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission)) {
                    report.critical(LocaleController.getString(R.string.UMessageRiskAccessibility));
                } else if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(service.permission)) {
                    report.critical(LocaleController.getString(R.string.UMessageRiskNotifications));
                } else if ("android.permission.BIND_INCALL_SERVICE".equals(service.permission)
                        || "android.permission.BIND_SCREENING_SERVICE".equals(service.permission)) {
                    report.dialer = true; // phone app: calls, USSD and call log are its job
                } else if ("android.permission.BIND_VPN_SERVICE".equals(service.permission)) {
                    report.info(LocaleController.getString(R.string.UMessageRiskVpn));
                }
            }
        }
        if (info.receivers != null) {
            for (ActivityInfo receiver : info.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(receiver.permission)) {
                    report.critical(LocaleController.getString(R.string.UMessageRiskDeviceAdmin));
                }
            }
        }
        return report;
    }

    // Critical: signals that banking trojans, OTP stealers and droppers depend on. A single one
    // triggers the warning. Info: common in legit apps, only listed alongside a critical signal.
    private static void addPermissionRisk(ApkReport report, String permission) {
        if (permission == null) {
            return;
        }
        switch (permission) {
            case Manifest.permission.READ_SMS:
            case Manifest.permission.RECEIVE_SMS:
            case Manifest.permission.SEND_SMS:
            case Manifest.permission.RECEIVE_MMS:
            case Manifest.permission.RECEIVE_WAP_PUSH:
                report.critical(LocaleController.getString(R.string.UMessageRiskSms));
                break;
            case "android.permission.REQUEST_INSTALL_PACKAGES":
            case "android.permission.INSTALL_PACKAGES":
                report.critical(LocaleController.getString(R.string.UMessageRiskInstallApps));
                break;
            case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                report.critical(LocaleController.getString(R.string.UMessageRiskAccessibility));
                break;
            case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                report.critical(LocaleController.getString(R.string.UMessageRiskNotifications));
                break;
            case "android.permission.BIND_DEVICE_ADMIN":
                report.critical(LocaleController.getString(R.string.UMessageRiskDeviceAdmin));
                break;
            case Manifest.permission.READ_CONTACTS:
            case Manifest.permission.WRITE_CONTACTS:
            case Manifest.permission.GET_ACCOUNTS:
                report.info(LocaleController.getString(R.string.UMessageRiskContacts));
                break;
            case Manifest.permission.READ_CALL_LOG:
            case Manifest.permission.WRITE_CALL_LOG:
            case Manifest.permission.PROCESS_OUTGOING_CALLS:
            case Manifest.permission.CALL_PHONE:
            case Manifest.permission.READ_PHONE_STATE:
                report.info(LocaleController.getString(R.string.UMessageRiskPhone));
                break;
            case "android.permission.SYSTEM_ALERT_WINDOW":
                report.info(LocaleController.getString(R.string.UMessageRiskOverlay));
                break;
            case "android.permission.QUERY_ALL_PACKAGES":
                report.info(LocaleController.getString(R.string.UMessageRiskInstalledApps));
                break;
        }
    }

    /** Below this a .jar or .dex is a helper (root shell, plugin stub): it cannot hold a banking trojan's second stage. */
    private static final long MIN_PAYLOAD_BYTES = 32 * 1024;
    private static final int MAX_NESTED_ENTRIES = 300;

    /**
     * Droppers ship the real malware inside assets/ or res/raw/ as an .apk, .dex or .jar
     * (sometimes renamed) and install it after the first launch. Looks for such entries.
     */
    private static boolean hasEmbeddedPayload(File file) {
        // No file size limit: only entry headers are read, and padding the APK must not skip the scan.
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lowerName = name.toLowerCase(Locale.ROOT);
                boolean rootDex = !lowerName.contains("/") && lowerName.startsWith("classes") && lowerName.endsWith(".dex");
                if (rootDex || lowerName.startsWith("meta-inf/") || lowerName.equals("resources.arsc") || lowerName.equals("androidmanifest.xml")) {
                    continue;
                }
                if (lowerName.endsWith(".apk") || lowerName.endsWith(".xapk") || lowerName.endsWith(".apks")) {
                    return true;
                }
                if ((lowerName.endsWith(".dex") || lowerName.endsWith(".jar") || lowerName.endsWith(".odex") || lowerName.endsWith(".vdex"))
                        && entry.getSize() >= MIN_PAYLOAD_BYTES) {
                    return true;
                }
                if (entry.getSize() < 8 * 1024) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] magic = new byte[4];
                    int read = in.read(magic);
                    if (read < 4) {
                        continue;
                    }
                    if (magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n'
                            || magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'y' && magic[3] == '\n') {
                        return true;
                    }
                    if (magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4 && nestedZipIsApk(zip.getInputStream(entry))) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    // Lure names used by Uzbek SMS-stealer campaigns (Group-IB, 2024-2026): court notices,
    // wedding invitations/videos, "photos of you", camera recordings, dating, bank apps.
    private static final String[] LURE_WORDS = {"sud ", "sud_", "sud.", "sud-", "sudga", "sudq", "sudilova", "sudi", "суд", "qaror", "chaqiruv", "chaqiriq", "mahkama", "prokuratura", "jarima", "soliq", "taklifnoma",
            "taklif", "to'y", "toy ", "toyga", "to‘y", "toydan", "to'ydan", "video", "albom", "album", "rasm", "foto", "photo", "kamera", "camera",
            "girls", "qizlar", "razdevator", "intim", "скрин", "screenshot", "hujjat", "документ", "ariza", "pasport", "passport", "mygov", "my.gov",
            "davlat", "xizmat", "gov.uz", "eimzo", "e-imzo", "pdf", "docx", "jpg", "jpeg", "png", "mp4", "mov", "zip", "rar", "oyin", "yulduz", "stars",
            "premium", "sovg", "sovg'a", "sovga", "bonus", "kredit", "credit", "nafaqa", "pensiya", "kompensatsiya", "lotereya", "yutuq"};
    private static final String[] BANK_WORDS = {"payme", "click", "humo", "uzcard", "uzum", "kapital", "kapitalbank", "nbu", "milliybank", "agrobank", "asaka",
            "ipoteka", "tbc", "anorbank", "anor", "hamkor", "davr", "ipak", "aloqabank", "turon", "trast", "xalq", "sqb", "qishloq", "infinbank", "octobank",
            "mkbank", "paynet", "oson", "apelsin", "beeline", "ucell", "mobiuz", "uztelecom", "humans", "alif", "xazna", "cbu", "markaziy bank", "bank"};

    private static void inspectLure(String fileName, ApkReport report) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String label = report.appName == null ? "" : report.appName.toLowerCase(Locale.ROOT);
        String base = name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
        // "Sud Qarori.PDF (8).apk", "Siz bilan video.mp4.apk"
        if (base.matches(".*\\.(pdf|docx?|xlsx?|jpe?g|png|gif|mp4|mov|avi|mkv|mp3|zip|rar|txt|ppt|pptx|exe)(\\s*\\(\\d+\\))?$")) {
            report.critical(LocaleController.getString(R.string.UMessageRiskDoubleExtension));
        }
        for (String word : BANK_WORDS) {
            if (containsWord(name, word) || containsWord(label, word)) {
                report.critical(LocaleController.getString(R.string.UMessageRiskBankImpersonation));
                break;
            }
        }
        for (String word : LURE_WORDS) {
            if (startsWord(name, word) || startsWord(label, word)) {
                report.critical(LocaleController.getString(R.string.UMessageRiskLureName));
                break;
            }
        }
    }

    /** Word-start match only, so "toydan", "sudga" hit but "sudoku", "popular" do not. */
    private static boolean startsWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) {
                return true;
            }
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int end = i + word.length();
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    private static boolean isKnownAssetType(String name) {
        String[] ok = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".mp3", ".mp4", ".m4a", ".ogg", ".wav", ".aac", ".webm", ".ttf", ".otf", ".woff",
                ".woff2", ".zip", ".gz", ".pak", ".json", ".js", ".html", ".css", ".txt", ".xml", ".tgs", ".lottie", ".pdf", ".tflite", ".onnx", ".bin", ".flac", ".opus", ".m4v", ".mov", ".avi", ".mkv", ".avif", ".heic", ".ttc", ".xz", ".br", ".7z", ".bz2", ".zst", ".pb",
                ".model", ".db", ".sqlite", ".realm", ".proto", ".mdl", ".ktx", ".pvr", ".obb", ".unity3d", ".resS", ".ress", ".bundle", ".wasm"};
        for (String ext : ok) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Shannon entropy of the first 64 KB; > 7.9 bits means encrypted or already-compressed data. */
    private static boolean isHighEntropy(InputStream in) {
        try (InputStream stream = in) {
            byte[] buf = new byte[64 * 1024];
            int total = 0, read;
            while (total < buf.length && (read = stream.read(buf, total, buf.length - total)) > 0) {
                total += read;
            }
            if (total < 4096) {
                return false;
            }
            // gzip/zip/png/jpeg headers are compressed, not encrypted
            if ((buf[0] & 0xff) == 0x1f && (buf[1] & 0xff) == 0x8b || buf[0] == 'P' && buf[1] == 'K'
                    || (buf[0] & 0xff) == 0x89 && buf[1] == 'P' || (buf[0] & 0xff) == 0xff && (buf[1] & 0xff) == 0xd8) {
                return false;
            }
            int[] counts = new int[256];
            for (int i = 0; i < total; i++) {
                counts[buf[i] & 0xff]++;
            }
            double entropy = 0;
            for (int c : counts) {
                if (c > 0) {
                    double p = (double) c / total;
                    entropy -= p * (Math.log(p) / Math.log(2));
                }
            }
            return entropy > 7.9;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean containsAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        // UTF-16LE string pools
        outer2:
        for (int i = 0; i <= data.length - n.length * 2; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j * 2] != n[j] || data[i + j * 2 + 1] != 0) {
                    continue outer2;
                }
            }
            return true;
        }
        return false;
    }

    /** One dex above this is padding or too big to hold in memory on a cheap phone; the others are still read. */
    private static final long MAX_DEX_BYTES = 40L * 1024 * 1024;

    /**
     * Reads the string tables of every classes*.dex so the scan sees which APIs the code
     * actually calls, not only what the manifest declares. Also detects commercial packers
     * that hide the real code until runtime.
     */
    private static final int SMALL_APP_STRINGS = 5000;

    /** A Bot API token ("123456789:AA…" with 35 token characters): stealers hard-code their bot's. */
    private static final java.util.regex.Pattern BOT_TOKEN = java.util.regex.Pattern.compile("\\b\\d{8,10}:AA[A-Za-z0-9_-]{33}\\b");

    private static void inspectCode(File file, ApkReport report) {
        boolean dynamicLoad = false, crypto = false, installer = false, sms = false, telegramFiles = false,
                root = false, packer = false, accessibilityControl = false, listApps = false;
        boolean telegramBot = false, cardBot = false, ussd = false, frida = false, emulatorCheck = false,
                knownDropper = false, encryptedAsset = false, nativeLib = false, junkNames = false;
        int strings = 0, longNames = 0;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.length() > 200) {
                    junkNames = true; // hundreds-of-characters entry names crash jadx/apktool on purpose
                }
                if (name.equals("resources.arsc")) {
                    // MidnightDat stores the encrypted payload file name under this resource key.
                    byte[] arsc = readFully(zip.getInputStream(entry), (int) Math.min(entry.getSize(), 16L * 1024 * 1024));
                    if (arsc != null && containsAscii(arsc, "abc_toolbar_collapse_description_custom")) {
                        knownDropper = true;
                    }
                    continue;
                }
                if (!entry.isDirectory() && entry.getSize() > 64 * 1024 && !isKnownAssetType(name)
                        && (name.startsWith("res/raw/") || !name.contains("/") && !name.endsWith(".dex") && !name.equals("androidmanifest.xml"))
                        && isHighEntropy(zip.getInputStream(entry))) {
                    encryptedAsset = true;
                }
                if (name.startsWith("lib/") || name.startsWith("assets/")) {
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        nativeLib = true;
                        if (name.contains("libandroidcore_native")) {
                            knownDropper = true;
                        }
                    }
                    if (name.startsWith("assets/") && !entry.isDirectory() && entry.getSize() > 64 * 1024 && !isKnownAssetType(name)
                            && isHighEntropy(zip.getInputStream(entry))) {
                        encryptedAsset = true;
                    }
                    if (name.contains("jiagu") || name.contains("bangcle") || name.contains("secexe") || name.contains("legu")
                            || name.contains("dexprotector") || name.contains("protectclass") || name.contains("dexhelper")
                            || name.contains("libshell") || name.contains("nesec") || name.contains("ijiami") || name.contains("libapp_guard")) {
                        packer = true;
                    }
                    continue;
                }
                if (name.contains("/") || !name.startsWith("classes") || !name.endsWith(".dex")) {
                    continue;
                }
                if (entry.getSize() > MAX_DEX_BYTES) {
                    continue;
                }
                byte[] dex = readFully(zip.getInputStream(entry), (int) entry.getSize());
                if (dex == null) {
                    continue;
                }
                for (String value : dexStrings(dex)) {
                    strings++;
                    if (value.length() > 120 && value.startsWith("L") && value.endsWith(";")) {
                        longNames++;
                    }
                    if (value.contains("api.telegram.org/bot") || BOT_TOKEN.matcher(value).find()) {
                        telegramBot = true;
                    } else if (value.toLowerCase(Locale.ROOT).contains("humocardbot") || value.toLowerCase(Locale.ROOT).contains("cardxabarbot")) {
                        cardBot = true;
                    } else if (value.contains("sendUssdRequest") || value.contains("Ussd") || value.contains("tel:*") || value.contains("tel:%23") || value.contains("%23%23")) {
                        ussd = true;
                    } else if (value.contains("frida-") || value.contains("libfrida") || value.contains("fridaserver") || value.contains("re.frida") || value.contains("de.robv.android.xposed") || value.contains("XposedBridge") || value.contains("XposedHelpers")) {
                        frida = true;
                    } else if (value.contains("ro.kernel.qemu") || value.contains("goldfish") || value.contains("ranchu") || value.contains("Genymotion") || value.contains("qemud")) {
                        emulatorCheck = true;
                    } else if (value.equals("Ldalvik/system/DexClassLoader;") || value.equals("Ldalvik/system/InMemoryDexClassLoader;")) {
                        dynamicLoad = true;
                    } else if (value.contains("javax/crypto/Cipher") || value.contains("Ljavax/crypto/spec/")) {
                        crypto = true;
                    } else if (value.contains("android/content/pm/PackageInstaller") || value.contains("vnd.android.package-archive")
                            || value.contains("INSTALL_PACKAGE") || value.contains("pm install")) {
                        installer = true;
                    } else if (value.contains("android/telephony/SmsManager") || value.contains("content://sms")) {
                        sms = true;
                    } else if (value.contains("tgnet.dat") || value.contains("/shared_prefs/userconfing")
                            || value.contains("com.whatsapp/databases") || value.contains("com.whatsapp/shared_prefs")) {
                        telegramFiles = true;
                    } else if (value.equals("su") || value.contains("/system/bin/su") || value.contains("/system/xbin/su") || value.contains("Superuser.apk")) {
                        root = true;
                    } else if (value.contains("performGlobalAction") || value.contains("dispatchGesture")) {
                        accessibilityControl = true;
                    } else if (value.contains("getInstalledPackages") || value.contains("getInstalledApplications")) {
                        listApps = true;
                    }
                }
            }
        } catch (java.util.zip.ZipException e) {
            // Android installs it, but standard ZIP readers fail: a deliberately broken archive
            // that hides the code from scanners (SpyNote, banking trojans).
            FileLog.e(e);
            report.malware(LocaleController.getString(R.string.UMessageRiskMalformedZip));
            return;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        if (knownDropper) {
            report.malware(LocaleController.getString(R.string.UMessageRiskKnownDropper));
        }
        if (junkNames) {
            report.malware(LocaleController.getString(R.string.UMessageRiskMalformedZip));
        }
        if (packer) {
            report.critical(LocaleController.getString(R.string.UMessageRiskPacker));
        }
        // MidnightDat (.dat + native XOR) and RoundRift (.key + Java AES + PackageInstaller session).
        // Games and media apps ship encrypted assets next to native code and ordinary crypto; only a small app doing so looks like a loader.
        final boolean smallApp = strings < SMALL_APP_STRINGS;
        report.smallApp = smallApp;
        if (encryptedAsset && smallApp && (installer || dynamicLoad || crypto || nativeLib)) {
            report.malware(LocaleController.getString(R.string.UMessageRiskEncryptedAsset));
        }
        if (telegramBot) {
            if (sms || telegramFiles || accessibilityControl && report.has(R.string.UMessageRiskAccessibility) || listApps) {
                report.malware(LocaleController.getString(R.string.UMessageRiskTelegramBotC2));
            } else {
                report.critical(LocaleController.getString(R.string.UMessageRiskTelegramBotC2));
            }
        }
        if (cardBot) {
            report.malware(LocaleController.getString(R.string.UMessageRiskCardBot));
        }
        if (ussd) {
            if (report.dialer) {
                report.info(LocaleController.getString(R.string.UMessageRiskUssd));
            } else {
                report.critical(LocaleController.getString(R.string.UMessageRiskUssd));
            }
        }
        // Emulator strings and long class names alone are normal in big apps (Firebase, Compose), and so are
        // Frida/Xposed checks: Google apps and banks ship anti-tamper code. Only a small app hiding is suspicious.
        if (smallApp && (frida || emulatorCheck && (sms || encryptedAsset))) {
            report.critical(LocaleController.getString(R.string.UMessageRiskAntiAnalysis));
        }
        if (longNames > 20) {
            report.info(LocaleController.getString(R.string.UMessageRiskObfuscatedNames));
        }
        // A tiny loader that only runs hidden code is the dropper signature. Updaters, stores and file managers
        // install packages and use crypto too, so that mix in a normal-sized app only earns a warning.
        if (dynamicLoad && (strings < 400 || encryptedAsset && smallApp)) {
            report.malware(LocaleController.getString(R.string.UMessageRiskDropperCode));
        } else if (dynamicLoad && installer && smallApp) {
            report.critical(LocaleController.getString(R.string.UMessageRiskDropperCode));
        } else if (dynamicLoad) {
            report.info(LocaleController.getString(R.string.UMessageRiskDynamicCode));
        }
        if (telegramFiles && !"org.telegram.messenger".equals(report.packageName)) {
            report.malware(LocaleController.getString(R.string.UMessageRiskSessionStealer));
        }
        // Code strings are not permissions: without SEND_SMS / REQUEST_INSTALL_PACKAGES (checked in the manifest) an app
        // cannot act on them, and big apps reference SmsManager or the APK mime type for ordinary reasons.
        if (sms) {
            if (smallApp) {
                report.critical(LocaleController.getString(R.string.UMessageRiskSmsCode));
            } else {
                report.info(LocaleController.getString(R.string.UMessageRiskSmsCode));
            }
        }
        if (installer && smallApp) {
            report.critical(LocaleController.getString(R.string.UMessageRiskInstallApps));
        }

        if (root) {
            report.info(LocaleController.getString(R.string.UMessageRiskRoot));
        }
        if (listApps) {
            report.info(LocaleController.getString(R.string.UMessageRiskInstalledApps));
        }
    }

    private static byte[] readFully(InputStream in, int size) {
        if (size <= 0x70) {
            return null;
        }
        try (InputStream stream = in) {
            byte[] data = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = stream.read(data, offset, size - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return offset == size ? data : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Minimal DEX parser: header -> string_ids -> MUTF-8 string_data items. */
    private static ArrayList<String> dexStrings(byte[] dex) {
        ArrayList<String> result = new ArrayList<>();
        if (dex.length < 0x70 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
            return result;
        }
        ByteBuffer buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt(0x38);
        int idsOffset = buffer.getInt(0x3C);
        if (count <= 0 || count > 1_000_000 || idsOffset <= 0 || (long) idsOffset + 4L * count > dex.length) {
            return result;
        }
        for (int i = 0; i < count; i++) {
            int offset = buffer.getInt(idsOffset + i * 4);
            if (offset <= 0 || offset >= dex.length) {
                continue;
            }
            // skip ULEB128 utf16 length
            while (offset < dex.length && (dex[offset] & 0x80) != 0) {
                offset++;
            }
            offset++;
            int end = offset;
            while (end < dex.length && dex[end] != 0) {
                end++;
            }
            if (end > offset && end - offset < 512) {
                result.add(new String(dex, offset, end - offset, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static boolean nestedZipIsApk(InputStream stream) {
        try (ZipInputStream nested = new ZipInputStream(stream)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = nested.getNextEntry()) != null && count++ < MAX_NESTED_ENTRIES) {
                String name = entry.getName();
                if ("AndroidManifest.xml".equals(name) || name.endsWith(".dex")) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    // First domain-looking token in link/button text, e.g. "🌐 Kirish: delivery.yandex.uz".
    private static final Pattern SHOWN_HOST = Pattern.compile("(?:https?://)?((?:[\\p{L}0-9-]+\\.)+\\p{L}{2,})(?![\\p{L}0-9-])", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the host the link text pretends to open when it differs from the real target
     * (e.g. text "delivery.yandex.uz" hiding https://yandexdostavka.lol), otherwise null.
     */
    public static String getMaskedHost(CharSequence shownText, String url) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.MASKED_LINK_GUARD)) return null;
        if (TextUtils.isEmpty(shownText) || TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = SHOWN_HOST.matcher(shownText);
            if (!matcher.find()) {
                return null;
            }
            String shownHost = matcher.group(1).toLowerCase(Locale.ROOT);
            String realHost = Uri.parse(url.contains("://") ? url : "https://" + url).getHost();
            if (realHost == null) {
                return null;
            }
            return baseDomain(shownHost).equals(baseDomain(realHost)) ? null : shownHost;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String baseDomain(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            ascii = host.toLowerCase(Locale.ROOT);
        }
        String[] labels = ascii.split("\\.");
        int n = labels.length;
        if (n < 2) {
            return ascii;
        }
        // Second-level public suffixes like co.uk, com.uz keep one more label.
        int keep = n >= 3 && labels[n - 1].length() == 2 && labels[n - 2].length() <= 3 ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /** Warns when the visible link text hides a different real destination. */
    public static void showMaskedLinkDanger(Context context, String shownHost, String url, Runnable openAction) {
        Activity activity = AndroidUtilities.getActivity(context);
        if (!AndroidUtilities.isContextSafe(activity)) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(LocaleController.formatString(R.string.UMessageLinkMaskedShown, shownHost)).append("\n");
        message.append(LocaleController.formatString(R.string.UMessageLinkMaskedReal, url)).append("\n\n");
        message.append(LocaleController.getString(R.string.UMessageLinkDangerReasons)).append("\n\n");
        message.append("• ").append(LocaleController.getString(R.string.UMessageLinkRiskMasked)).append('\n');
        for (String risk : getUrlRisks(url)) {
            message.append("• ").append(risk).append('\n');
        }
        message.append('\n').append(LocaleController.getString(R.string.UMessageLinkDangerFooter));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.UMessageLinkDangerTitle));
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, activity, null, true);
    }

    public static boolean isSuspiciousUrl(String value) {
        return !getUrlRisks(value).isEmpty();
    }

    /** Returns true when the URL was intercepted (including fail-closed cases). */
    public static boolean guardUrl(Context context, Uri uri, Runnable openAction) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.MASKED_LINK_GUARD)) return false;
        if (uri == null) {
            return false;
        }
        final String value = uri.toString();
        final List<String> risks = getUrlRisks(value);
        if (risks.isEmpty()) {
            return false;
        }
        Activity activity = AndroidUtilities.getActivity(context);
        if (!AndroidUtilities.isContextSafe(activity)) {
            Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.UMessageLinkDangerTitle),
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        showLinkDanger(activity, value, risks, openAction);
        return true;
    }

    private static void showLinkDanger(Context context, String url, List<String> risks, Runnable openAction) {
        StringBuilder message = new StringBuilder();
        message.append(url).append("\n\n");
        message.append(LocaleController.getString(R.string.UMessageLinkDangerReasons)).append("\n\n");
        for (String risk : risks) {
            message.append("• ").append(risk).append('\n');
        }
        message.append('\n').append(LocaleController.getString(R.string.UMessageLinkDangerFooter));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.UMessageLinkDangerTitle));
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, context, null, true);
    }

    private static void showProtected(AlertDialog.Builder builder, Context context, Theme.ResourcesProvider resourcesProvider, boolean danger) {
        // The user must accept responsibility before the "open anyway" button unlocks.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        row.setBackground(Theme.getSelectorDrawable(false));
        CheckBoxSquare consent = new CheckBoxSquare(context, true, resourcesProvider);
        row.addView(consent, LayoutHelper.createLinear(18, 18, 0, 0, 16, 0));
        TextView consentText = new TextView(context);
        consentText.setText(LocaleController.getString(R.string.UMessageRiskConsent));
        consentText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        consentText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        row.addView(consentText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        builder.setView(row);
        AlertDialog dialog = builder.create();
        row.setOnClickListener(v -> {
            consent.setChecked(!consent.isChecked(), true);
            setOpenEnabled(dialog, consent.isChecked());
        });
        dialog.setCanceledOnTouchOutside(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dialog.getWindow() != null) {
            try {
                // Throws SecurityException unless HIDE_OVERLAY_WINDOWS is granted.
                dialog.getWindow().setHideOverlayWindows(true);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        dialog.setOnShowListener(ignored -> {
            int[] buttonTypes = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
            for (int buttonType : buttonTypes) {
                View button = dialog.getButton(buttonType);
                if (button != null) {
                    // Reject taps delivered through a screen overlay on older Android versions.
                    button.setFilterTouchesWhenObscured(true);
                }
            }
            if (danger) {
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
                }
            }
            setOpenEnabled(dialog, consent.isChecked());
        });
        try {
            dialog.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void setOpenEnabled(AlertDialog dialog, boolean enabled) {
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private static List<String> getUrlRisks(String value) {
        ArrayList<String> risks = new ArrayList<>();
        if (TextUtils.isEmpty(value)) {
            return risks;
        }
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                if ("intent".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)
                        || "javascript".equalsIgnoreCase(scheme)) {
                    addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskScheme));
                }
                return risks; // Telegram deep links, phone and mail actions keep their existing handling.
            }
            String host = uri.getHost();
            String authority = uri.getEncodedAuthority();
            if (TextUtils.isEmpty(host)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskMalformed));
                return risks;
            }
            String unicodeHost = host.toLowerCase(Locale.ROOT);
            String asciiHost;
            try {
                asciiHost = IDN.toASCII(unicodeHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIdn));
                asciiHost = unicodeHost;
            }
            if (!"https".equalsIgnoreCase(scheme)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskHttp));
            }
            if (!TextUtils.isEmpty(authority) && authority.contains("@")) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskUserInfo));
            }
            String lowerValue = value.toLowerCase(Locale.ROOT);
            if (lowerValue.contains("%40") || lowerValue.contains("\\") || containsControlCharacter(value)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskObfuscated));
            }
            if (asciiHost.contains("xn--") || !unicodeHost.equals(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIdn));
            }
            if (IPV4.matcher(asciiHost).matches() || asciiHost.contains(":")) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIp));
            }
            String lowerPath = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(".apk") || lowerPath.endsWith(".xapk") || lowerPath.endsWith(".apks") || lowerPath.endsWith(".apkm")) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskApkDownload));
            }
            if (looksLikeBrandImpersonation(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskBrandImpersonation));
            }
            if (hasCheapTld(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskCheapTld));
            }
            if (isFreeHosting(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskFreeHosting));
            }
            if (SHORTENER_HOSTS.contains(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskShortener));
            }
            if (looksLikeTelegramImpersonation(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskTelegramImpersonation));
            }
            if (looksLikeInstagramImpersonation(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskInstagramImpersonation));
            }
            if (!isTrustedHost(asciiHost) && containsSuspiciousHostWord(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskPhishingWords));
            }
            if (asciiHost.split("\\.").length > 5) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskSubdomains));
            }
        } catch (Throwable e) {
            addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskMalformed));
        }
        // A single weak signal is common on legitimate links. Keep the warning
        // for impersonation, obfuscation, or combinations of signals.
        if (risks.size() == 1) {
            String onlyRisk = risks.get(0);
            if (onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskHttp))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskIp))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskIdn))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskShortener))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskSubdomains))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskCheapTld))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskFreeHosting))) {
                risks.clear();
            }
        }
        return risks;
    }

    private static boolean isTrustedHost(String host) {
        return host.equals("t.me") || host.endsWith(".t.me") || host.equals("telegram.org") || host.endsWith(".telegram.org")
                || host.equals("telegram.me") || host.endsWith(".telegram.me") || host.equals("telegra.ph") || host.endsWith(".telegra.ph");
    }

    private static boolean looksLikeTelegramImpersonation(String host) {
        if (isTrustedHost(host)) {
            return false;
        }
        if (host.contains("telegram") || host.startsWith("t-me.") || host.contains(".t-me.") || host.contains("t.me.")) {
            return true;
        }
        String[] labels = host.split("\\.");
        for (String label : labels) {
            String normalized = label.replace("-", "").replace('1', 'l').replace('0', 'o');
            if (normalized.length() >= 6 && editDistance(normalized, "telegram") <= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeInstagramImpersonation(String host) {
        if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
            return false;
        }
        return host.contains("instagram") && (host.contains("verif") || host.contains("badge")
                || host.contains("login") || host.contains("instagram-com") || host.contains("instagram.com."));
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), replace);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    // Brands Uzbek phishing pages copy, mapped to their real domains.
    private static final String[][] BRANDS = {
            {"payme", "payme.uz"}, {"clickuz", "click.uz"}, {"uzcard", "uzcard.uz"}, {"humo", "humocard.uz", "humo.uz"}, {"humocard", "humocard.uz"},
            {"uzum", "uzum.uz", "uzum.com"}, {"uzumbank", "uzumbank.uz", "uzum.uz"}, {"kapitalbank", "kapitalbank.uz"}, {"mygov", "gov.uz"},
            {"soliq", "soliq.uz"}, {"nbu", "nbu.uz"}, {"agrobank", "agrobank.uz"}, {"asakabank", "asakabank.uz"},
            {"hamkorbank", "hamkorbank.uz"}, {"ipakyuli", "ipakyulibank.uz"}, {"tbcbank", "tbcbank.uz"}, {"anorbank", "anorbank.uz"},
            {"paynet", "paynet.uz"}, {"ucell", "ucell.uz"}, {"uztelecom", "uztelecom.uz"}, {"octobank", "octobank.uz"},
            {"infinbank", "infinbank.com"}, {"aloqabank", "aloqabank.uz"}, {"xalqbank", "xb.uz"}, {"sqb", "sqb.uz"}, {"davrbank", "davrbank.uz"},
            {"trastbank", "trastbank.uz"}, {"turonbank", "turonbank.uz"}, {"apelsin", "apelsin.uz"}, {"oson", "oson.uz"}, {"alifbank", "alif.uz"},
            {"egov", "gov.uz"}, {"didox", "didox.uz"}, {"uzpost", "pochta.uz"}};

    private static boolean looksLikeBrandImpersonation(String host) {
        String base = baseDomain(host);
        String[] tokens = host.split("[.\\-]");
        for (String[] brand : BRANDS) {
            boolean official = false;
            for (int i = 1; i < brand.length; i++) {
                if (base.equals(brand[i]) || host.equals(brand[i]) || host.endsWith("." + brand[i])) {
                    official = true;
                    break;
                }
            }
            if (official) {
                continue;
            }
            for (int i = 0; i < tokens.length - 1; i++) { // the TLD itself (".gov") is not a brand mention
                if (tokens[i].equals(brand[0]) || tokens[i].length() > brand[0].length() + 1 && brand[0].length() >= 5 && tokens[i].contains(brand[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCheapTld(String host) {
        String[] tlds = {".top", ".xyz", ".lol", ".icu", ".click", ".shop", ".online", ".site", ".cfd", ".sbs", ".monster", ".buzz", ".rest",
                ".cyou", ".bond", ".pw", ".tk", ".ml", ".ga", ".cf", ".gq", ".live", ".fun", ".store", ".vip", ".win", ".best", ".quest", ".mom"};
        for (String tld : tlds) {
            if (host.endsWith(tld)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFreeHosting(String host) {
        String[] suffixes = {".netlify.app", ".vercel.app", ".pages.dev", ".workers.dev", ".web.app", ".firebaseapp.com", ".github.io",
                ".000webhostapp.com", ".glitch.me", ".weebly.com", ".wixsite.com", ".ngrok.io", ".ngrok-free.app", ".onrender.com",
                ".replit.app", ".repl.co", ".blogspot.com", ".tilda.ws", ".square.site", ".r2.dev", ".trycloudflare.com"};
        for (String suffix : suffixes) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSuspiciousHostWord(String host) {
        String[] words = {"login", "verify", "verification", "secure", "account", "support", "wallet", "airdrop", "bonus", "gift", "prize",
                "sovga", "yutuq", "aksiya", "promo", "reward", "claim", "kompensatsiya", "nafaqa", "freestars", "premium-free", "tolov", "karta"};
        for (String word : words) {
            if (host.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void addRisk(List<String> risks, String value) {
        if (!risks.contains(value)) {
            risks.add(value);
        }
    }

    public static class ApkReport {
        public String appName;
        public String packageName;
        public final ArrayList<String> criticalRisks = new ArrayList<>();
        public final ArrayList<String> infoRisks = new ArrayList<>();
        /** Confirmed malware signature or combination, not just a risky permission. */
        public boolean malware;
        /** Signed by a publisher we know (or by U message itself): nothing is reported. */
        public boolean trusted;
        /** A phone/dialer app (InCallService): calls and USSD are expected. */
        public boolean dialer;
        /**
         * Few DEX strings: droppers and loaders are tiny shells. Big apps (browsers, Google apps, games) legitimately
         * ship nested APKs, high-entropy data files, dynamic code loading and anti-tamper checks.
         */
        public boolean smallApp = true;

        void malware(String risk) {
            critical(risk);
            malware = true;
        }

        boolean has(int resId) {
            return criticalRisks.contains(LocaleController.getString(resId));
        }

        public boolean isMalware() {
            return malware;
        }

        void critical(String risk) {
            if (!criticalRisks.contains(risk)) {
                criticalRisks.add(risk);
            }
        }

        void info(String risk) {
            if (!infoRisks.contains(risk)) {
                infoRisks.add(risk);
            }
        }

        public boolean isDangerous() {
            return !criticalRisks.isEmpty();
        }

        public String buildMessage() {
            StringBuilder message = new StringBuilder();
            if (!TextUtils.isEmpty(appName)) {
                message.append(LocaleController.formatString(R.string.UMessageApkAppName, appName)).append('\n');
            }
            if (!TextUtils.isEmpty(packageName)) {
                message.append(LocaleController.formatString(R.string.UMessageApkPackageName, packageName)).append('\n');
            }
            if (message.length() > 0) {
                message.append('\n');
            }
            if (malware) {
                message.append(LocaleController.getString(R.string.UMessageApkMalwareVerdict)).append("\n\n");
            }
            message.append(LocaleController.getString(R.string.UMessageApkRequests)).append("\n\n");
            for (String risk : criticalRisks) {
                message.append("• ").append(risk).append('\n');
            }
            for (String risk : infoRisks) {
                message.append("• ").append(risk).append('\n');
            }
            message.append('\n').append(LocaleController.getString(malware ? R.string.UMessageApkMalwareFooter : R.string.UMessageApkDangerFooter));
            return message.toString();
        }
    }
}
