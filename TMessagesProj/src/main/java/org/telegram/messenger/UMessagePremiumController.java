package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message app-only "premium" — completely separate from real Telegram Premium. A small Vercel
 * backend stores which Telegram user ids the app should treat as premium (badge + premium features)
 * for U message users only. This controller reads that state, caches it, and lets the current user
 * turn their own premium display on or off.
 *
 * Nothing here talks to Telegram's servers; the flag only affects how the U message client behaves.
 */
public final class UMessagePremiumController {

    public static final String BASE_URL = "https://umessage-premium-server.vercel.app";

    private static final long TTL_MS = 5 * 60 * 1000;
    private static final long NEGATIVE_TTL_MS = 60 * 1000;

    private static final String PREFS = "umessage_premium";
    private static final String KEY_ENABLED = "self_enabled";
    /** Off by default: only when the user turns it on does Telegram's own code treat this account as Premium. */
    private static final String KEY_LOCAL_TG_PREMIUM = "local_tg_premium";
    private static final String KEY_VERIFY_SECRET = "verify_secret";
    /**
     * The only bot the app presses "/start v_<code>" in. A server reply naming any other bot is ignored, so a
     * compromised or misconfigured server can not make the app unblock and message an arbitrary bot.
     */
    public static final String VERIFY_BOT = "umessage_spambot";

    private static volatile UMessagePremiumController instance;

    public static UMessagePremiumController getInstance() {
        UMessagePremiumController local = instance;
        if (local == null) {
            synchronized (UMessagePremiumController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new UMessagePremiumController();
                }
            }
        }
        return local;
    }

    private static final class Entry {
        boolean premium;
        boolean enabled;
        String stickerSet;
        String uname;
        long fetchedAt;
    }

    /** Result of a username/inbox call: {@code error} is a server error code, or "network". */
    public interface Callback {
        void run(JSONObject result, String error);
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final HashSet<Long> pending = new HashSet<>();
    private final HashSet<Long> inFlight = new HashSet<>();
    private final Object lock = new Object();
    private boolean flushScheduled;
    private volatile Boolean selfEnabled;
    private volatile Boolean localTelegramPremium;
    private final long[] lastRegister = new long[UserConfig.MAX_ACCOUNT_COUNT];
    // Telegram verification of a key-less account (see umessage-premium-server/lib/verify.js); UI thread only
    private final String[] verifyCode = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private final boolean[] verifyBusy = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private final int[] verifyPolls = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private final int[] verifyStarts = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] verifyWindow = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] verifyLastStart = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] verifyBotId = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private final boolean[] verifyHadHistory = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private final boolean[] verifyReblock = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    // v2 verification: the bot answers our /start with "umv_<reply>" in this account's own chat; we send it back
    private final String[] verifyReply = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] verifyPeer = new long[UserConfig.MAX_ACCOUNT_COUNT];
    @SuppressWarnings("unchecked")
    private final ArrayList<Integer>[] verifyReplyIds = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];
    @SuppressWarnings("unchecked")
    private final ArrayList<Integer>[] verifyMsgIds = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];

    private UMessagePremiumController() {
    }

    /** True when this user should be shown as premium in the U message UI (granted and not hidden). */
    public boolean isPremium(long userId) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.UM_PREMIUM)) return false;
        if (userId <= 0) {
            return false;
        }
        final Entry e = cache.get(userId);
        if (e == null || isStale(e)) {
            requestFetch(userId);
        }
        return e != null && e.premium && e.enabled;
    }

    /** True when this user is a registered U message user (premium flag is set for every registered user). */
    public boolean isUMessageUser(long userId) {
        if (userId <= 0) {
            return false;
        }
        final Entry e = cache.get(userId);
        if (e == null || isStale(e)) {
            requestFetch(userId);
        }
        return e != null && e.premium;
    }

    /**
     * Whether Telegram's own code ({@link UserConfig#isPremium()}) should treat this account as Premium although
     * Telegram did not grant it. Only the user's explicit "Local Telegram Premium" setting (off by default) turns
     * this on; the U message badge ({@link #isSelfEnabled()}) is a separate thing.
     */
    public boolean isSelfPremium(int account) {
        return isLocalTelegramPremium();
    }

    public boolean isLocalTelegramPremium() {
        if (true) return false; // removed: U message Premium is enough, local Telegram Premium is no longer offered
        Boolean v = localTelegramPremium;
        if (v == null) {
            v = prefs().getBoolean(KEY_LOCAL_TG_PREMIUM, false);
            localTelegramPremium = v;
        }
        return v;
    }

    public void setLocalTelegramPremium(boolean enabled) {
        localTelegramPremium = enabled;
        prefs().edit().putBoolean(KEY_LOCAL_TG_PREMIUM, enabled).apply();
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
            }
            notifyChanged();
        });
    }

    /**
     * Announce this user to the backend so other U message users see them as premium. Safe to call
     * repeatedly (throttled); every registered user is granted premium automatically.
     */
    private final java.util.HashMap<String, Long> chatAdminReported = new java.util.HashMap<>();

    /**
     * Chats from the admin's list ("Kanallar") this user owns / administers, as [{ id, role }]. The admin
     * panel shows them as the chat's admins; for a blocked chat the server blocks the account (UMessageChatGuard).
     */
    public void reportChatAdmins(int account, JSONArray chats, boolean urgent) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final long now = System.currentTimeMillis();
        final String key = account + ":" + chats;
        final Long last = chatAdminReported.get(key);
        if (selfId <= 0 || last != null && now - last < (urgent ? 60 * 60 * 1000L : 6 * 60 * 60 * 1000L)) {
            return;
        }
        chatAdminReported.put(key, now);
        UMessageNet.run(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("userId", String.valueOf(selfId));
                final String token = getToken(selfId);
                if (token != null) {
                    body.put("token", token);
                }
                body.put("chatAdminOf", chats);
                final JSONObject r = new JSONObject(httpPost(BASE_URL + "/api/register", body.toString()));
                if (r.has("token") && token != null) {
                    prefs().edit().putString("token_" + selfId, r.getString("token")).apply(); // rotated key
                }
                applySelf(account, selfId, r);
            } catch (Throwable ignore) {
            }
        });
    }

    /** Is this phone model blocked by the admin? (GET /api/status?device=) */
    public static boolean fetchDeviceBlocked(String model) throws Exception {
        return httpGet(BASE_URL + "/api/status?device=" + java.net.URLEncoder.encode(model, "UTF-8")).optBoolean("blocked", false);
    }

    /** The server rejected our key (reinstall / admin re-link): re-register now to get a new one. */
    public void reRegister(int account) {
        if (System.currentTimeMillis() - lastRegister[account] > 60_000) {
            lastRegister[account] = 0;
            register(account);
        }
    }

    public void register(int account) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        // blocked accounts re-check on every resume so an unblock applies right away
        if (now - lastRegister[account] < (isBlocked(account) ? 15_000 : 6 * 60 * 60 * 1000)) {
            return;
        }
        lastRegister[account] = now;
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        // Telegram verification (no key yet): send the code the bot has seen, or ask for a new one if allowed
        final String sentCode = verifyCode[account];
        final String sentReply = sentCode == null ? null : verifyReply[account];
        final boolean askCode = sentCode == null && !verifyBusy[account] && canStartVerify(account, false);
        UMessageNet.run(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("userId", String.valueOf(selfId));
                if (getToken(selfId) == null) {
                    // binds the code to this install (servers before v2 ignore it)
                    body.put("verifySecret", verifySecret(selfId));
                    if (sentCode != null) {
                        body.put("verifyCode", sentCode);
                        if (sentReply != null) {
                            body.put("verifyReply", sentReply);
                        }
                    } else if (askCode) {
                        body.put("verify", true);
                    }
                }
                body.put("enabled", isSelfEnabled());
                body.put("device", org.telegram.ui.UMessageUnsupportedDeviceActivity.deviceModel());
                body.put("appCode", UMessageUpdater.currentVersionCode());
                body.put("appVersion", BuildVars.BUILD_VERSION_STRING);
                final String token = getToken(selfId);
                if (token != null) {
                    body.put("token", token);
                }
                if (self != null) {
                    body.put("firstName", self.first_name == null ? "" : self.first_name);
                    body.put("lastName", self.last_name == null ? "" : self.last_name);
                    final String username = UserObject.getPublicUsername(self);
                    body.put("username", username == null ? "" : username);
                    // no phone number: the server has no use for it
                }
                final JSONObject r = new JSONObject(httpPost(BASE_URL + "/api/register", body.toString()));
                if (r.has("token")) {
                    prefs().edit().putString("token_" + selfId, r.getString("token")).remove(KEY_VERIFY_SECRET + "_" + selfId).apply();
                    AndroidUtilities.runOnUIThread(() -> UMessageStories.getInstance(account).refresh(true));
                    AndroidUtilities.runOnUIThread(() -> finishVerify(account));
                } else if (token == null) {
                    final String code = r.optString("verify", "");
                    final String bot = r.optString("bot", "");
                    final boolean waiting = r.optBoolean("pending", false);
                    final boolean needReply = r.optBoolean("needReply", false);
                    AndroidUtilities.runOnUIThread(() -> {
                        lastRegister[account] = 0; // still no key: retry on next resume
                        onVerifyReply(account, sentCode, code, bot, waiting, needReply);
                    });
                }
                applySelf(account, selfId, r);
                if (r.has("deviceBlocked")) {
                    org.telegram.ui.UMessageUnsupportedDeviceActivity.applyBlocked(r.optBoolean("deviceBlocked"));
                }
                UMessageOfficialChat.getInstance(account).refresh(r.has("token"));
                if (r.has("sendLogs")) {
                    UMessageErrorReporter.sendDump(r.optLong("sendLogs"), selfId);
                }
                UMessageMusicBots.setBots(r.optJSONArray("musicBots"));
                final JSONObject update = r.optJSONObject("update");
                if (update != null) {
                    AndroidUtilities.runOnUIThread(() -> UMessageUpdater.offer(update));
                }
            } catch (Throwable ignore) {
                // network/server failure: do not wait 6 h for the next attempt
                AndroidUtilities.runOnUIThread(() -> lastRegister[account] = 0);
            }
        });
    }

    /**
     * Key-less account (new user / admin "reset key"): the server gives a key only after the user proves
     * they own this Telegram account. We press "/start v_<code>" in the server's bot from the user's own
     * account (Telegram guarantees the sender id to the bot), then register again with the code.
     * At most 3 attempts per hour, with growing gaps. UI thread.
     */
    private boolean canStartVerify(int account, boolean consume) {
        final long now = System.currentTimeMillis();
        if (now - verifyWindow[account] > 60 * 60 * 1000L) {
            verifyWindow[account] = now;
            verifyStarts[account] = 0;
        }
        if (verifyStarts[account] >= 3 || now - verifyLastStart[account] < 60_000L * verifyStarts[account]) {
            return false;
        }
        if (consume) {
            verifyStarts[account]++;
            verifyLastStart[account] = now;
        }
        return true;
    }

    /** Per-install secret the verify code is bound to (kept until a key arrives). */
    private static synchronized String verifySecret(long selfId) {
        String s = prefs().getString(KEY_VERIFY_SECRET + "_" + selfId, null);
        if (s == null || s.length() < 32) {
            final byte[] b = new byte[32];
            new java.security.SecureRandom().nextBytes(b);
            s = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            prefs().edit().putString(KEY_VERIFY_SECRET + "_" + selfId, s).apply();
        }
        return s;
    }

    private static boolean isPinnedBot(String bot) {
        return bot != null && VERIFY_BOT.equalsIgnoreCase(bot.trim().replace("@", ""));
    }

    /** register() reply without a key. sentCode: the code we sent (or null); code/bot: the server's verify request. */
    private void onVerifyReply(int account, String sentCode, String code, String bot, boolean waiting, boolean needReply) {
        if (sentCode != null && waiting && needReply && sentCode.equals(code) && sentCode.equals(verifyCode[account])) {
            // v2: the bot has seen our /start and answered in this chat; read "umv_…" and send it back
            if (verifyReply[account] == null && ++verifyPolls[account] <= 5) {
                readVerifyReply(account, sentCode);
            } else {
                cleanupVerifyChat(account, true);
            }
            return;
        }
        if (sentCode != null && waiting && sentCode.equals(code)) {
            // the bot has not seen our /start yet (webhook delay): ask again a few times
            if (sentCode.equals(verifyCode[account]) && ++verifyPolls[account] <= 3) {
                scheduleVerifyPoll(account, 3000L * (verifyPolls[account] + 1));
            } else {
                cleanupVerifyChat(account, true);
            }
            return;
        }
        if (sentCode != null) {
            cleanupVerifyChat(account, true); // expired or rejected
        }
        if (!TextUtils.isEmpty(code) && isPinnedBot(bot)) {
            startVerify(account, code, VERIFY_BOT);
        } else if (!TextUtils.isEmpty(code)) {
            FileLog.e("U message: verification bot " + bot + " is not the pinned one, ignored");
        }
    }

    /** Looks for the bot's "umv_<reply>" answer in the last messages of its chat; registers again with it. UI thread. */
    private void readVerifyReply(int account, String code) {
        final long peer = verifyPeer[account];
        if (peer == 0) {
            cleanupVerifyChat(account, true);
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = mc.getInputPeer(peer);
        req.limit = 10;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!code.equals(verifyCode[account])) {
                return;
            }
            String found = null;
            final ArrayList<Integer> ids = new ArrayList<>();
            if (response instanceof TLRPC.messages_Messages) {
                final java.util.regex.Pattern p = java.util.regex.Pattern.compile("umv_([A-Za-z0-9_-]{16})");
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                    if (m == null || m.out || m.message == null || MessageObject.getFromChatId(m) != peer) {
                        continue;
                    }
                    final java.util.regex.Matcher matcher = p.matcher(m.message);
                    if (matcher.find()) {
                        if (found == null) {
                            found = matcher.group(1); // newest first
                        }
                        ids.add(m.id);
                    }
                }
            }
            if (found != null) {
                verifyReply[account] = found;
                verifyReplyIds[account] = ids;
                lastRegister[account] = 0;
                register(account);
            } else {
                scheduleVerifyPoll(account, 3000L * (verifyPolls[account] + 1));
            }
        }));
    }

    private void scheduleVerifyPoll(int account, long delay) {
        AndroidUtilities.runOnUIThread(() -> {
            lastRegister[account] = 0;
            register(account);
        }, delay);
    }

    private void startVerify(int account, String code, String bot) {
        if (verifyBusy[account] || !canStartVerify(account, true)) {
            return;
        }
        verifyBusy[account] = true;
        final MessagesController mc = MessagesController.getInstance(account);
        mc.getUserNameResolver().resolve(bot, peerId -> AndroidUtilities.runOnUIThread(() -> {
            final TLRPC.User user = peerId == null || peerId <= 0 ? null : mc.getUser(peerId);
            if (user == null || !user.bot || !isPinnedBot(UserObject.getPublicUsername(user))) {
                verifyBusy[account] = false;
                return;
            }
            // did the user already chat with this bot? then only our own message is removed afterwards
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = mc.getInputPeer(user.id);
            req.limit = 1;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.messages_Messages)) {
                    verifyBusy[account] = false;
                    return;
                }
                final boolean hadHistory = !((TLRPC.messages_Messages) response).messages.isEmpty();
                final boolean blocked = mc.blockePeers.indexOfKey(user.id) >= 0;
                if (blocked) {
                    verifyReblock[account] = true;
                    mc.unblockPeer(user.id, () -> sendVerifyStart(account, user, code, hadHistory, true));
                } else {
                    sendVerifyStart(account, user, code, hadHistory, false);
                }
            }));
        }));
    }

    private void sendVerifyStart(int account, TLRPC.User user, String code, boolean hadHistory, boolean unblocked) {
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.TL_messages_startBot req = new TLRPC.TL_messages_startBot();
        req.bot = mc.getInputUser(user);
        req.peer = mc.getInputPeer(user.id);
        req.start_param = "v_" + code;
        req.random_id = Utilities.random.nextLong();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            final ArrayList<Integer> ids = new ArrayList<>();
            if (response instanceof TLRPC.Updates) {
                final TLRPC.Updates updates = (TLRPC.Updates) response;
                for (int i = 0; i < updates.updates.size(); i++) {
                    final TLRPC.Update u = updates.updates.get(i);
                    if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage && ((org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) u).message != null) {
                        ids.add(((org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) u).message.id);
                    }
                }
                if (updates instanceof TLRPC.TL_updateShortSentMessage) {
                    ids.add(updates.id);
                }
                mc.processUpdates(updates, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.Updates) {
                    verifyCode[account] = code;
                    verifyReply[account] = null;
                    verifyReplyIds[account] = null;
                    verifyPeer[account] = user.id;
                    verifyBotId[account] = user.id;
                    verifyHadHistory[account] = hadHistory;
                    verifyMsgIds[account] = ids;
                    verifyPolls[account] = 0;
                    verifyBusy[account] = false;
                    scheduleVerifyPoll(account, 3000);
                    // the bot already has the code (Telegram delivered it): remove our copy at once, posted after
                    // processUpdates' own UI work, so the chat never shows up in the list
                    cleanupVerifyChat(account, false);
                } else if (!unblocked && error != null && "YOU_BLOCKED_USER".equals(error.text)) {
                    verifyReblock[account] = true;
                    mc.unblockPeer(user.id, () -> sendVerifyStart(account, user, code, hadHistory, true));
                } else {
                    verifyBusy[account] = false;
                    if (verifyReblock[account]) {
                        verifyReblock[account] = false;
                        mc.blockPeer(user.id);
                    }
                }
            });
        });
    }

    /** Key received (or the attempt gave up): hide the verification chat again. UI thread. */
    private void finishVerify(int account) {
        cleanupVerifyChat(account, true);
    }

    /**
     * Deletes the "/start" we sent (only for us); when done also the bot's "umv_…" answer, and re-blocks the bot
     * if we unblocked it (not earlier: a blocked bot could not send the answer). done=false keeps the code for polling.
     */
    private void cleanupVerifyChat(int account, boolean done) {
        final long botId = verifyBotId[account];
        final ArrayList<Integer> ids = verifyMsgIds[account];
        final boolean hadHistory = verifyHadHistory[account];
        final long peer = verifyPeer[account];
        final ArrayList<Integer> replyIds = verifyReplyIds[account];
        verifyBotId[account] = 0;
        verifyMsgIds[account] = null;
        final MessagesController mc = MessagesController.getInstance(account);
        if (botId != 0) {
            if (!hadHistory) {
                mc.deleteDialog(botId, 0); // the chat did not exist before: remove it for this user only
            } else if (ids != null && !ids.isEmpty()) {
                mc.deleteMessages(ids, null, null, botId, 0, false, 0);
            }
        }
        if (!done) {
            return;
        }
        final boolean reblock = verifyReblock[account];
        verifyReblock[account] = false;
        verifyCode[account] = null;
        verifyPolls[account] = 0;
        verifyReply[account] = null;
        verifyReplyIds[account] = null;
        verifyPeer[account] = 0;
        final long chat = peer != 0 ? peer : botId;
        if (chat == 0) {
            return;
        }
        if (peer != 0) {
            if (!hadHistory) {
                mc.deleteDialog(peer, 0); // the bot's answer recreated the chat
            } else if (replyIds != null && !replyIds.isEmpty()) {
                mc.deleteMessages(replyIds, null, null, peer, 0, false, 0);
            }
        }
        if (reblock) {
            mc.blockPeer(chat);
        }
    }

    private void applySelf(int account, long selfId, JSONObject r) {
        final boolean blocked = r.optBoolean("blocked", false);
        final boolean wasBlocked = isBlocked(account);
        prefs().edit().putBoolean("blocked_" + selfId, blocked).apply();
        final Entry e = new Entry();
        e.premium = r.optBoolean("premium", false);
        e.enabled = r.optBoolean("enabled", false);
        e.stickerSet = r.isNull("stickerSet") ? null : r.optString("stickerSet", null);
        e.uname = r.isNull("uname") ? null : r.optString("uname", null);
        e.fetchedAt = System.currentTimeMillis();
        cache.put(selfId, e);
        AndroidUtilities.runOnUIThread(() -> {
            notifyChanged();
            if (blocked != wasBlocked) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.umessageBlockedChanged, account);
            }
        });
    }

    String getToken(long userId) {
        return prefs().getString("token_" + userId, null);
    }

    /** The admin blocked this account in the U message admin panel. */
    public boolean isBlocked(int account) {
        return prefs().getBoolean("blocked_" + UserConfig.getInstance(account).getClientUserId(), false);
    }

    /** U message username of a user (without the "@u" suffix), or null. */
    public String getUname(long userId) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.U_USERNAMES)) return null;
        if (userId <= 0) {
            return null;
        }
        final Entry e = cache.get(userId);
        if (e == null || isStale(e)) {
            requestFetch(userId);
        }
        return e == null ? null : e.uname;
    }

    public static String formatUname(String uname) {
        return uname == null ? null : uname + "@u";
    }

    /** Asks the server whether a self-chosen name (5+ chars) is free. Callback runs on the UI thread. */
    public void checkUname(String name, Callback callback) {
        UMessageNet.run(() -> {
            JSONObject r = null;
            String error = null;
            try {
                r = httpGet(BASE_URL + "/api/uname?check=" + java.net.URLEncoder.encode(name, "UTF-8"));
            } catch (Throwable t) {
                error = "network";
            }
            final JSONObject fr = r;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(fr, fe));
        });
    }

    /** Finds who owns "<name>@u": { userId, username } or error "not_found". Callback runs on the UI thread. */
    public void lookupUname(String name, Callback callback) {
        UMessageNet.run(() -> {
            JSONObject r = null;
            String error = null;
            try {
                r = httpGet(BASE_URL + "/api/uname?name=" + java.net.URLEncoder.encode(name, "UTF-8"));
                if (r.has("error")) {
                    error = r.optString("error");
                }
            } catch (Throwable t) {
                error = "not_found";
            }
            final JSONObject fr = r;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(fr, fe));
        });
    }

    /**
     * Owner-only username calls: action "set" (extra = name), "clear", "claim" / "decline"
     * (extra = claim code) and "inbox". Callback runs on the UI thread.
     */
    public void unameAction(int account, String action, String extra, Callback callback) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        UMessageNet.run(() -> {
            JSONObject r = null;
            String error = null;
            try {
                final String token = getToken(selfId);
                if (token == null) {
                    error = "bad_token";
                    AndroidUtilities.runOnUIThread(() -> reRegister(account)); // no key yet: fetch one now
                } else {
                    final JSONObject body = new JSONObject();
                    body.put("userId", String.valueOf(selfId));
                    body.put("token", token);
                    body.put("action", action);
                    if ("set".equals(action)) {
                        body.put("name", extra);
                    } else if (extra != null) {
                        body.put("code", extra);
                    }
                    r = new JSONObject(httpPost(BASE_URL + "/api/uname", body.toString()));
                    if (r.has("error")) {
                        error = r.optString("error");
                        if ("blocked".equals(error)) {
                            prefs().edit().putBoolean("blocked_" + selfId, true).apply();
                        }
                    } else if (!"inbox".equals(action)) {
                        applySelf(account, selfId, r);
                    }
                }
            } catch (Throwable t) {
                error = "network";
            }
            final JSONObject fr = r;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(fr, fe));
        });
    }

    /** True when the current account's user was granted premium (regardless of their display toggle). */
    public boolean isSelfGranted(int account) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final Entry e = cache.get(selfId);
        if (e == null || isStale(e)) {
            requestFetch(selfId);
        }
        return e != null && e.premium;
    }

    public String getStickerSet(long userId) {
        final Entry e = cache.get(userId);
        return e != null && e.premium ? e.stickerSet : null;
    }

    /** The current user's local preference to display their premium (synced to the backend too). */
    public boolean isSelfEnabled() {
        Boolean v = selfEnabled;
        if (v == null) {
            v = prefs().getBoolean(KEY_ENABLED, true);
            selfEnabled = v;
        }
        return v;
    }

    public void setSelfEnabled(int account, boolean enabled) {
        selfEnabled = enabled;
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final Entry e = cache.get(selfId);
        if (e != null) {
            e.enabled = enabled;
        }
        lastRegister[account] = 0; // force the backend upsert to carry the new flag
        register(account);
        AndroidUtilities.runOnUIThread(UMessagePremiumController::notifyChanged);
    }

    private boolean isStale(Entry e) {
        final long ttl = e.premium ? TTL_MS : NEGATIVE_TTL_MS;
        return System.currentTimeMillis() - e.fetchedAt > ttl;
    }

    /** Coalesces id lookups: requests within a short window go out as one batched status call. */
    private void requestFetch(long userId) {
        synchronized (lock) {
            if (inFlight.contains(userId) || !pending.add(userId)) {
                return;
            }
            if (!flushScheduled) {
                flushScheduled = true;
                AndroidUtilities.runOnUIThread(this::flush, 150);
            }
        }
    }

    private void flush() {
        final ArrayList<Long> ids = new ArrayList<>();
        synchronized (lock) {
            flushScheduled = false;
            for (Long id : pending) {
                if (!inFlight.contains(id)) {
                    ids.add(id);
                    inFlight.add(id);
                    if (ids.size() >= 100) {
                        break;
                    }
                }
            }
            pending.removeAll(ids);
        }
        if (ids.isEmpty()) {
            return;
        }
        UMessageNet.run(() -> {
            boolean changed = false;
            try {
                final JSONObject users = fetchStatus(ids);
                final long now = System.currentTimeMillis();
                for (Long id : ids) {
                    final JSONObject u = users.optJSONObject(String.valueOf(id));
                    final Entry e = new Entry();
                    if (u != null) {
                        e.premium = u.optBoolean("premium", false);
                        e.enabled = u.optBoolean("enabled", false);
                        e.stickerSet = u.isNull("stickerSet") ? null : u.optString("stickerSet", null);
                        e.uname = u.isNull("uname") ? null : u.optString("uname", null);
                    }
                    e.fetchedAt = now;
                    final Entry old = cache.put(id, e);
                    changed |= old == null || old.premium != e.premium || old.enabled != e.enabled || !android.text.TextUtils.equals(old.uname, e.uname);
                }
            } catch (Throwable ignore) {
                // keep whatever we had; stale entries retry later
            } finally {
                synchronized (lock) {
                    inFlight.removeAll(ids);
                }
            }
            if (changed) {
                AndroidUtilities.runOnUIThread(UMessagePremiumController::notifyChanged);
            }
        });
    }


    /**
     * Status of up to 100 ids: POST body (ids stay out of URLs and access logs); a server without POST lookups
     * (404 / 405 / a reply without "users") gets the older GET form.
     */
    private static JSONObject fetchStatus(ArrayList<Long> ids) throws Exception {
        final JSONArray arr = new JSONArray();
        for (Long id : ids) arr.put(String.valueOf(id));
        try {
            final JSONObject body = new JSONObject();
            body.put("ids", arr);
            final JSONObject r = new JSONObject(httpPost(BASE_URL + "/api/status", body.toString()));
            final JSONObject users = r.optJSONObject("users");
            if (users != null) {
                return users;
            }
            if ("rate_limited".equals(r.optString("error"))) {
                throw new IllegalStateException("rate_limited");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignore) {
            // fall through to GET
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return httpGet(BASE_URL + "/api/status?ids=" + sb).getJSONObject("users");
    }

    private static void notifyChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, 0);
        }
    }

    static JSONObject httpGet(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        try {
            return new JSONObject(readAll(c));
        } finally {
            c.disconnect();
        }
    }

    static String httpPost(String url, String json) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        try {
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        final java.io.InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) {
            return "{}";
        }
        return UMessageNet.readCappedString(is, UMessageNet.MAX_JSON_BYTES);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }
}
