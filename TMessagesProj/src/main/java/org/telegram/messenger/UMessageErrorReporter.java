package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Remote error reports for the admin panel ("Xatolar"): crashes (written to disk by the uncaught
 * exception handler and sent on the next launch) and non-fatal exceptions passed to FileLog.e.
 * Non-fatal errors are deduplicated on the phone (one report per stack signature and version a day,
 * at most MAX_PER_DAY a day), so expected exceptions cannot flood the server.
 * The user can turn reports off (U message settings, "Send error reports", on by default and described there).
 * Reports carry no Telegram user id; exception texts are scrubbed of URLs / query strings, e-mail addresses,
 * long digit runs (phone numbers, ids) and file paths before they leave the phone.
 */
public final class UMessageErrorReporter {

    private static final int MAX_PER_DAY = 25;
    private static final int MAX_PENDING = 30;
    private static final int MAX_STACK = 40;
    private static final String CRASH_FILE = "umessage_crash.json";

    private static final int MAX_RECENT = 60;
    private static final ArrayList<JSONObject> pending = new ArrayList<>();
    /** Every recent error, not deduplicated — sent only when the admin asks for this phone's log. */
    private static final ArrayList<JSONObject> recent = new ArrayList<>();
    private static long lastDumpRequest;
    private static boolean flushScheduled;
    private static boolean installed;
    private static final ThreadLocal<Boolean> reporting = new ThreadLocal<>();

    private UMessageErrorReporter() {
    }

    private static final String CFG_PREFS = "umessage_error_reports";

    /** The user's "Send error reports" setting (default on, disclosed in settings). */
    public static boolean isEnabled() {
        try {
            return ApplicationLoader.applicationContext.getSharedPreferences(CFG_PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setEnabled(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences(CFG_PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
        if (!enabled) {
            synchronized (pending) {
                pending.clear();
            }
            synchronized (recent) {
                recent.clear();
            }
            new File(ApplicationLoader.applicationContext.getFilesDir(), CRASH_FILE).delete();
        }
    }

    private static final java.util.regex.Pattern URL_RE = java.util.regex.Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]{1,15}://\\S+");
    private static final java.util.regex.Pattern QUERY_RE = java.util.regex.Pattern.compile("\\?[^\\s?]*=[^\\s]*");
    private static final java.util.regex.Pattern EMAIL_RE = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final java.util.regex.Pattern PATH_RE = java.util.regex.Pattern.compile("(?:/[^/\\s:;,'\"()]+){2,}/?");
    private static final java.util.regex.Pattern DIGITS_RE = java.util.regex.Pattern.compile("\\+?\\d[\\d -]{4,}\\d");

    /** Strips what could identify the user or what they looked at from an exception text. */
    static String scrub(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        s = URL_RE.matcher(s).replaceAll("<url>");
        s = EMAIL_RE.matcher(s).replaceAll("<email>");
        s = QUERY_RE.matcher(s).replaceAll("?<query>");
        s = PATH_RE.matcher(s).replaceAll("<path>");
        s = DIGITS_RE.matcher(s).replaceAll("<n>");
        return s;
    }

    /** Called from ApplicationLoader.onCreate. */
    public static void install() {
        if (installed || ApplicationLoader.applicationContext == null) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (!isEnabled()) {
                    throw new IllegalStateException("reports off");
                }
                final JSONObject o = describe("crash", e, null, thread.getName());
                // the process is about to die: write synchronously, send on the next launch
                try (FileOutputStream out = new FileOutputStream(new File(ApplicationLoader.applicationContext.getFilesDir(), CRASH_FILE))) {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, e);
            }
        });
        AndroidUtilities.runOnUIThread(() -> UMessageNet.run(UMessageErrorReporter::sendSavedCrash), 8000);
    }

    /** Non-fatal exception from FileLog.e. */
    public static void nonFatal(Throwable e, String message) {
        if (e == null || !installed || reporting.get() != null || !isEnabled()
                || e instanceof InterruptedException || e instanceof java.util.concurrent.CancellationException
                || e instanceof org.telegram.messenger.video.MediaCodecVideoConvertor.ConversionCanceledException
                // reply to an already cancelled file request (rpc_answer_dropped_running), upstream skips it too
                || e instanceof org.telegram.tgnet.TLParseException && e.getMessage() != null && e.getMessage().contains("magic cd78e586")) {
            return;
        }
        reporting.set(Boolean.TRUE);
        try {
            final JSONObject o = describe("error", e, message, Thread.currentThread().getName());
            remember(o);
            final String sig = o.getString("sig");
            final SharedPreferences p = prefs();
            final String day = String.valueOf(System.currentTimeMillis() / 86400000L);
            if (!day.equals(p.getString("day", null))) {
                p.edit().clear().putString("install", p.getString("install", null)).putString("day", day).apply();
            }
            final String key = "s_" + sig + "_" + UMessageUpdater.currentVersionCode();
            final int today = p.getInt("count", 0);
            if (p.getBoolean(key, false) || today >= MAX_PER_DAY) {
                return;
            }
            p.edit().putBoolean(key, true).putInt("count", today + 1).apply();
            add(o);
        } catch (Throwable ignore) {
        } finally {
            reporting.remove();
        }
    }

    private static void sendSavedCrash() {
        try {
            final File f = new File(ApplicationLoader.applicationContext.getFilesDir(), CRASH_FILE);
            if (!f.exists()) {
                return;
            }
            if (!isEnabled()) {
                f.delete();
                return;
            }
            final byte[] data = new byte[(int) Math.min(f.length(), 256 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int read = 0;
                while (read < data.length) {
                    final int r = in.read(data, read, data.length - read);
                    if (r < 0) break;
                    read += r;
                }
            }
            f.delete();
            final JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
            remember(o);
            add(o);
        } catch (Throwable ignore) {
        }
    }

    private static void remember(JSONObject o) {
        synchronized (recent) {
            if (recent.size() >= MAX_RECENT) {
                recent.remove(0);
            }
            recent.add(o);
        }
    }

    /** The admin pressed "Qurilmadan so‘rash" (delivered in the /api/register reply as sendLogs). */
    public static void sendDump(long requestTs, long userId) {
        if (requestTs <= 0 || requestTs == lastDumpRequest || !installed || !isEnabled()) {
            return;
        }
        lastDumpRequest = requestTs;
        UMessageNet.run(() -> {
            final ArrayList<JSONObject> items;
            synchronized (recent) {
                items = new ArrayList<>(recent);
            }
            reporting.set(Boolean.TRUE);
            try {
                final JSONObject body = body(items);
                // the admin asked this account for its log: attributed by the account's key, not by a bare id
                body.put("userId", String.valueOf(userId));
                final String token = UMessagePremiumController.getInstance().getToken(userId);
                if (token != null) {
                    body.put("token", token);
                }
                body.put("logRequest", requestTs);
                UMessagePremiumController.httpPost(UMessagePremiumController.BASE_URL + "/api/status", body.toString());
            } catch (Throwable e) {
                lastDumpRequest = 0; // retry with the next register reply
            } finally {
                reporting.remove();
            }
        });
    }

    private static JSONObject body(ArrayList<JSONObject> items) throws Exception {
        final JSONObject body = new JSONObject();
        body.put("install", installId());
        final JSONObject meta = new JSONObject();
        meta.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        meta.put("sdk", Build.VERSION.SDK_INT);
        meta.put("app", BuildVars.BUILD_VERSION_STRING);
        meta.put("code", UMessageUpdater.currentVersionCode());
        meta.put("lang", java.util.Locale.getDefault().toString());
        body.put("meta", meta);
        body.put("errors", new JSONArray(items));
        return body;
    }

    private static void add(JSONObject o) {
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                pending.remove(0);
            }
            pending.add(o);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        AndroidUtilities.runOnUIThread(() -> UMessageNet.run(UMessageErrorReporter::flush), 5000);
    }

    private static void flush() {
        final ArrayList<JSONObject> batch;
        synchronized (pending) {
            flushScheduled = false;
            if (pending.isEmpty()) {
                return;
            }
            if (!isEnabled()) {
                pending.clear();
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        reporting.set(Boolean.TRUE);
        try {
            final JSONObject body = body(batch);
            UMessagePremiumController.httpPost(UMessagePremiumController.BASE_URL + "/api/status", body.toString());
        } catch (Throwable e) {
            // offline: keep them for the next report
            synchronized (pending) {
                pending.addAll(0, batch);
                while (pending.size() > MAX_PENDING) {
                    pending.remove(0);
                }
            }
        } finally {
            reporting.remove();
        }
    }

    private static JSONObject describe(String kind, Throwable e, String message, String thread) throws Exception {
        final JSONObject o = new JSONObject();
        o.put("kind", kind);
        o.put("t", System.currentTimeMillis());
        o.put("type", e.getClass().getName());
        o.put("msg", cut(scrub(e.getMessage()), 500));
        if (message != null) {
            o.put("note", cut(scrub(message), 300));
        }
        o.put("thread", cut(scrub(thread), 60));
        o.put("stack", stack(e));
        o.put("sig", signature(e));
        o.put("app", BuildVars.BUILD_VERSION_STRING);
        // no Telegram user id: reports are grouped by the random install id only
        return o;
    }

    /** "Type: msg\n\tat …" with up to two "Caused by" sections, like Log.getStackTraceString but bounded. */
    private static String stack(Throwable e) {
        final StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        for (int depth = 0; cur != null && depth < 3; depth++, cur = cur.getCause()) {
            if (depth > 0) {
                sb.append("Caused by: ");
            }
            sb.append(cur.getClass().getName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cut(scrub(cur.getMessage()), 300));
            }
            sb.append('\n');
            final StackTraceElement[] st = cur.getStackTrace();
            for (int i = 0; i < Math.min(st.length, MAX_STACK); i++) {
                sb.append("\tat ").append(st[i]).append('\n');
            }
            if (st.length > MAX_STACK) {
                sb.append("\t… ").append(st.length - MAX_STACK).append(" more\n");
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return sb.toString();
    }

    /** Groups the same bug across phones: exception type + first frames of the root cause, no line numbers. */
    private static String signature(Throwable e) {
        Throwable root = e;
        for (int i = 0; i < 5 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        final StringBuilder sb = new StringBuilder(root.getClass().getName());
        final StackTraceElement[] st = root.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 5); i++) {
            sb.append('|').append(st[i].getClassName()).append('.').append(st[i].getMethodName());
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_errors", Context.MODE_PRIVATE);
    }

    private static String installId() {
        final SharedPreferences p = prefs();
        String id = p.getString("install", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString("install", id).apply();
        }
        return id;
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }
}
