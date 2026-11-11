package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Remote login diagnostics: while an account is not logged in, every auth.* request (send code, QR,
 * password …) and its result is sent to the U message server (POST /api/status) and shown in the
 * admin panel ("Kirish loglari"). Phone numbers are masked; codes and passwords are never logged.
 */
public final class UMessageLoginLog {

    private static final int MAX_PENDING = 200;
    private static final ArrayList<JSONObject> pending = new ArrayList<>();
    private static boolean flushScheduled;
    private static final Runnable flush = UMessageLoginLog::flush;

    private UMessageLoginLog() {
    }

    /** Requests worth logging: anything auth.*, plus the nearest DC (shows the country Telegram sees). */
    public static boolean tracks(int account, TLObject req) {
        if (req == null || UserConfig.getInstance(account).isClientActivated()) {
            return false;
        }
        final String name = req.getClass().getSimpleName();
        return name.startsWith("TL_auth_") && !name.equals("TL_auth_bindTempAuthKey") || name.equals("TL_help_getNearestDc") || name.equals("getPassword");
    }

    public static void request(int account, TLObject req, int dc) {
        String detail = ""; // the phone number is never sent
        if (req instanceof TLRPC.TL_auth_resendCode && ((TLRPC.TL_auth_resendCode) req).reason != null) {
            detail = "reason=" + ((TLRPC.TL_auth_resendCode) req).reason;
        }
        event(account, "→ " + name(req), detail + (dc != ConnectionsManager.DEFAULT_DATACENTER_ID ? " dc=" + dc : ""));
    }

    public static void response(int account, TLObject req, TLObject resp, TLRPC.TL_error error, int dc) {
        String detail;
        if (error != null) {
            detail = "ERROR " + error.code + " " + error.text;
        } else if (resp instanceof TLRPC.auth_SentCode) {
            final TLRPC.auth_SentCode c = (TLRPC.auth_SentCode) resp;
            detail = name(resp) + (c.type != null ? " type=" + name(c.type) : "") + (c.next_type != null ? " next=" + name(c.next_type) : "") + (c.timeout != 0 ? " timeout=" + c.timeout : "");
        } else if (resp instanceof TLRPC.TL_nearestDc) {
            final TLRPC.TL_nearestDc d = (TLRPC.TL_nearestDc) resp;
            detail = "country=" + d.country + " this_dc=" + d.this_dc + " nearest_dc=" + d.nearest_dc;
        } else if (resp instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            detail = "loginTokenMigrateTo dc=" + ((TLRPC.TL_auth_loginTokenMigrateTo) resp).dc_id;
        } else if (resp instanceof TLRPC.TL_auth_loginToken) {
            detail = "loginToken expires_in=" + (((TLRPC.TL_auth_loginToken) resp).expires - ConnectionsManager.getInstance(account).getCurrentTime()) + "s";
        } else {
            detail = resp == null ? "null" : name(resp);
        }
        event(account, "← " + name(req), detail + " dc=" + dc);
    }

    /** Free-form step, e.g. "qr_scanned" or a Play Integrity outcome. */
    public static void event(int account, String what, String detail) {
        try {
            final JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("e", what);
            o.put("d", detail == null ? "" : detail.length() > 300 ? detail.substring(0, 300) : detail);
            o.put("a", account);
            synchronized (pending) {
                if (pending.size() >= MAX_PENDING) {
                    pending.remove(0);
                }
                pending.add(o);
            }
            if (!flushScheduled) {
                flushScheduled = true;
                AndroidUtilities.runOnUIThread(flush, 2000);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void flush() {
        flushScheduled = false;
        final ArrayList<JSONObject> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        UMessageNet.run(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("install", installId());
                final JSONObject meta = new JSONObject();
                meta.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                meta.put("sdk", Build.VERSION.SDK_INT);
                meta.put("app", BuildVars.BUILD_VERSION_STRING);
                meta.put("lang", java.util.Locale.getDefault().toString());
                meta.put("proxy", SharedConfig.isProxyEnabled() || UMessageConfig.isProxyFallbackEnabled());
                meta.put("net", ApplicationLoader.isConnectedOrConnectingToWiFi() ? "wifi" : "mobile");
                body.put("meta", meta);
                body.put("events", new JSONArray(batch));
                UMessagePremiumController.httpPost(UMessagePremiumController.BASE_URL + "/api/status", body.toString());
            } catch (Throwable e) {
                // offline: put the events back for the next flush
                synchronized (pending) {
                    pending.addAll(0, batch);
                    while (pending.size() > MAX_PENDING) {
                        pending.remove(0);
                    }
                }
            }
        });
    }

    private static String installId() {
        final SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("umessage_loginlog", Context.MODE_PRIVATE);
        String id = p.getString("install", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString("install", id).apply();
        }
        return id;
    }

    private static String name(Object o) {
        return o.getClass().getSimpleName().replace("TL_", "");
    }
}
