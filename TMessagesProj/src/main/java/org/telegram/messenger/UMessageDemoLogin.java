package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * U message: login code auto-fill for the store-review demo account (Google Play / App Store reviewers get
 * the demo number in the review notes and cannot receive its SMS).
 *
 * Relay side — the owner's phone, where the demo account stays signed in: a login code Telegram sends to that
 * session (a message from 777000) is posted to the server (/api/settings?route=democode), authenticated with
 * the demo account's own /api/register token (or UM_DEMO_RELAY_KEY before it has one).
 *
 * Reviewer side — the login code screen: only when the typed number is exactly {@code UM_DEMO_PHONE}, the
 * app polls the server and types the code in. Any other number: no request, nothing changes.
 *
 * This only types the code. The app is the same for the demo account as for every user: no feature is
 * hidden or changed for it.
 */
public final class UMessageDemoLogin {

    private static final long TELEGRAM_SERVICE_ID = 777000L;
    private static final long POLL_MS = 2000;
    private static final long POLL_TIMEOUT_MS = 120_000;
    /** Tolerates a phone clock behind the server's: a code relayed this long before polling started still counts. */
    private static final long SINCE_SLACK_MS = 60_000;
    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{5,6})(?!\\d)");

    private static volatile String lastRelayed;

    private UMessageDemoLogin() {
    }

    private static String demoPhone() {
        return digits(BuildConfig.UM_DEMO_PHONE);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    public static boolean isDemoPhone(String phone) {
        final String demo = demoPhone();
        return !demo.isEmpty() && demo.equals(digits(phone));
    }

    // ------------------------------------------------------------------ relay (owner's phone)

    /** Called for every batch of new messages; cheap no-op unless this account is the demo account. */
    public static void relay(int account, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty() || demoPhone().isEmpty()) {
            return;
        }
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null || !isDemoPhone(self.phone)) {
            return;
        }
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null || m.isOutOwner() || m.getDialogId() != TELEGRAM_SERVICE_ID) {
                continue;
            }
            if (now - m.messageOwner.date > 300 || TextUtils.isEmpty(m.messageOwner.message)) {
                continue;
            }
            final Matcher matcher = CODE.matcher(m.messageOwner.message);
            if (matcher.find()) {
                post(self.id, matcher.group(1));
            }
        }
    }

    private static void post(long userId, String code) {
        if (code.equals(lastRelayed)) {
            return;
        }
        lastRelayed = code;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                // a newly added demo account has no register token until its verification finishes: the relay key
                // (UM_DEMO_RELAY_KEY) covers that; it can only post a code for the demo account, never read one
                final String token = UMessagePremiumController.getInstance().getToken(userId);
                final String key = BuildConfig.UM_DEMO_RELAY_KEY;
                if (token == null && TextUtils.isEmpty(key)) {
                    FileLog.d("UMessageDemoLogin: demo account has no register token and no relay key");
                    lastRelayed = null;
                    return;
                }
                final JSONObject body = new JSONObject();
                body.put("userId", String.valueOf(userId));
                if (token != null) body.put("token", token);
                if (!TextUtils.isEmpty(key)) body.put("key", key);
                body.put("code", code);
                UMessagePremiumController.httpPost(UMessagePremiumController.BASE_URL + "/api/settings?route=democode", body.toString());
            } catch (Exception e) {
                lastRelayed = null;
                FileLog.e(e);
            }
        });
    }

    // ------------------------------------------------------------------ reviewer side (login screen)

    public interface Callback {
        void onCode(String code);
    }

    /**
     * Polls for the demo code when {@code phone} is the demo number; returns a handle to cancel it, or null when
     * the number is not the demo number (then nothing runs). {@code callback} runs once, on the UI thread.
     */
    public static AtomicBoolean pollIfDemo(String phone, Callback callback) {
        if (!isDemoPhone(phone)) {
            return null;
        }
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final long since = System.currentTimeMillis() - SINCE_SLACK_MS;
        final long deadline = SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS;
        final String url = UMessagePremiumController.BASE_URL + "/api/settings?route=democode&phone=" + demoPhone() + "&since=" + since;
        new Thread(() -> {
            while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    final String code = UMessagePremiumController.httpGet(url).optString("code", "");
                    if (code.matches("\\d{4,8}")) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!cancelled.getAndSet(true)) {
                                callback.onCode(code);
                            }
                        });
                        return;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ignore) {
                    return;
                }
            }
        }, "UMessageDemoLogin").start();
        return cancelled;
    }
}
