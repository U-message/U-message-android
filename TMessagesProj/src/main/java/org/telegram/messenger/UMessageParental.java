package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * U message parental control: a parent links a child's account (request → accept, both U message users)
 * and sends rules — a daily time limit and night rest hours. The child's phone enforces them with a lock
 * screen; the child can ask for extra time, which the parent grants with buttons in their chat.
 * Commands travel as hidden links in ordinary Telegram messages, like the Friend Map handshake.
 */
public class UMessageParental implements NotificationCenter.NotificationCenterDelegate {

    /** The other user is my child (I set the rules). */
    public static final int ROLE_CHILD = 0;
    /** The other user is my parent (I follow their rules). */
    public static final int ROLE_PARENT = 1;

    public static final int STATE_OUTGOING = 0;
    public static final int STATE_INCOMING = 1;
    public static final int STATE_ACTIVE = 2;

    public static final int LOCK_NONE = 0;
    public static final int LOCK_LIMIT = 1;
    public static final int LOCK_NIGHT = 2;

    public static class Rules {
        /** Daily limit in minutes, 0 = none. */
        public int limit;
        public boolean night;
        public int nightFrom = 22 * 60, nightTo = 7 * 60;
        /** sha256 of the parent PIN, "" = none. */
        public String pin = "";
        public long version;
        /** Parent's time zone id: the day limit and night hours follow it, not the child's (changeable) device zone. */
        public String tz = "";
        /** Channels and groups (dialog ids) the child can't see or open. */
        public final HashSet<Long> blocked = new HashSet<>();

        JSONObject toJson() throws Exception {
            JSONArray b = new JSONArray();
            for (long id : blocked) b.put(id);
            return new JSONObject().put("l", limit).put("n", night).put("f", nightFrom).put("t", nightTo).put("p", pin).put("v", version).put("b", b).put("z", tz == null ? "" : tz);
        }

        static Rules fromJson(JSONObject o) {
            if (o == null) return null;
            Rules r = new Rules();
            r.limit = Math.max(0, Math.min(24 * 60, o.optInt("l")));
            r.night = o.optBoolean("n");
            r.nightFrom = Math.max(0, Math.min(24 * 60 - 1, o.optInt("f", r.nightFrom)));
            r.nightTo = Math.max(0, Math.min(24 * 60 - 1, o.optInt("t", r.nightTo)));
            r.pin = o.optString("p", "");
            r.version = o.optLong("v");
            r.tz = o.optString("z", "");
            JSONArray b = o.optJSONArray("b");
            for (int i = 0; b != null && i < b.length() && i < MAX_BLOCKED; i++) {
                long id = b.optLong(i);
                if (id < 0) r.blocked.add(id);
            }
            return r;
        }

        public Rules copy() {
            try {
                return fromJson(toJson());
            } catch (Exception e) {
                return new Rules();
            }
        }
    }

    public static class Link {
        public long uid;
        public String id;
        public int role;
        public int state;
        public Rules rules;
        /** Handshake messages, removed from the chat once the link is settled. */
        final ArrayList<Integer> messageIds = new ArrayList<>();
        /** Newest "extra time" message already applied (child side). */
        int lastExtra;
        /** Shared secret for the encrypted reports (sent by the parent along with the rules). */
        byte[] key;
        /** Parent side: the child's last report (decrypted JSON), or null. */
        public JSONObject report;
        /** 1 = legacy (the parent sent the key inside the rules), 2 = X25519 agreement (only public keys travel). */
        int v = 1;
        /** Parent side: our X25519 private key while a request or an upgrade waits for the child's answer. */
        byte[] priv;
        /** Child side: the parent's public key from the request (v2). */
        byte[] parentPub;
        /** Parent side: last time a legacy link was asked to upgrade. */
        long upAt;
    }

    public interface Listener {
        void onParentalChanged();
    }

    // upg / upk (v2 key upgrade) are unknown to older builds, which ignore them
    private static final Pattern COMMAND = Pattern.compile("ump1\\.(req|ok|no|del|rul|more|ext|upg|upk)\\.([A-Za-z0-9_-]{16,2048})");
    private static final String KDF_PAIR = "ump2 pair", KDF_UPGRADE = "ump2 upgrade";
    private static final long UPGRADE_RETRY = 14 * 86_400_000L;
    private static final String LINK = UMessagePremiumController.BASE_URL + "/p#ump1.";
    private static final String BUTTON = "ump:";
    private static final String RELAY = UMessagePremiumController.BASE_URL + "/api/loc";
    /** The relay keeps blobs of up to 1 KB, so a report goes up in pieces. */
    private static final int CHUNK = 1000, MAX_CHUNKS = 24;
    public static final int MAX_BLOCKED = 100;
    private static final java.util.concurrent.ExecutorService network = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final UMessageParental[] instances = new UMessageParental[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageParental getInstance(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        UMessageParental local = instances[account];
        if (local == null || local.ownerUid != uid) {
            synchronized (UMessageParental.class) {
                local = instances[account];
                if (local == null || local.ownerUid != uid) {
                    if (local != null) local.detach();
                    instances[account] = local = new UMessageParental(account, uid);
                }
            }
        }
        return local;
    }

    /** Logout / slot reuse: data stays stored under the Telegram user id, only the slot instance goes. */
    static void resetAccount(int account) {
        synchronized (UMessageParental.class) {
            UMessageParental local = instances[account];
            instances[account] = null;
            if (local != null) local.detach();
        }
    }

    private void detach() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages));
    }

    private final int account;
    private final ConcurrentHashMap<Long, Link> links = new ConcurrentHashMap<>();
    private final HashSet<String> deadIds = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private long lastScan;

    /** Telegram user the data belongs to; the slot may later hold someone else. */
    private final long ownerUid;

    private UMessageParental(int account, long ownerUid) {
        this.account = account;
        this.ownerUid = ownerUid;
        load();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages));
    }

    // ---------------------------------------------------------------- public API

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public ArrayList<Link> getLinks(int role) {
        ArrayList<Link> list = new ArrayList<>();
        for (Link l : links.values()) {
            if (l.role == role) list.add(l);
        }
        Collections.sort(list, (a, b) -> Integer.compare(b.state, a.state));
        return list;
    }

    public Link get(long uid) {
        return links.get(uid);
    }

    public void sendRequest(long uid) {
        Link l = links.get(uid);
        if (l != null && !(l.role == ROLE_CHILD && l.state == STATE_OUTGOING)) return;
        if (l == null) {
            l = new Link();
            l.uid = uid;
            l.id = randomId();
            l.role = ROLE_CHILD;
            l.state = STATE_OUTGOING;
            l.rules = new Rules();
            l.v = 2;
            l.priv = UMessageKeyAgreement.newPrivateKey();
            links.put(uid, l);
        }
        if (l.priv == null) {
            l.v = 2;
            l.priv = UMessageKeyAgreement.newPrivateKey();
        }
        // v2: the request carries our X25519 public key after the id; older builds read only the id and answer v1
        byte[] pub = l.priv != null ? UMessageKeyAgreement.publicKey(l.priv) : null;
        send(uid, R.string.UMessageParentalReqText, "req", pub != null ? l.id + b64(pub) : l.id);
        changed();
    }

    public void accept(long uid) {
        Link l = links.get(uid);
        if (l == null || l.state != STATE_INCOMING) return;
        String answer = l.id;
        if (l.parentPub != null) {
            byte[] priv = UMessageKeyAgreement.newPrivateKey();
            byte[] pub = UMessageKeyAgreement.publicKey(priv);
            byte[] key = pub == null ? null : UMessageKeyAgreement.derive(KDF_PAIR, priv, l.parentPub, l.parentPub, pub);
            java.util.Arrays.fill(priv, (byte) 0);
            if (key != null) {
                l.key = key;
                l.v = 2;
                answer = l.id + b64(pub);
            }
        }
        l.state = STATE_ACTIVE;
        send(uid, R.string.UMessageParentalOkText, "ok", answer);
        deleteMessages(uid, l.messageIds, 0);
        l.messageIds.clear();
        changed();
    }

    /** Declines a request, cancels our own one, or ends an active link. */
    public void remove(long uid) {
        Link l = links.remove(uid);
        if (l == null) return;
        deadIds.add(l.id);
        boolean decline = l.state == STATE_INCOMING;
        send(uid, decline ? R.string.UMessageParentalNoText : R.string.UMessageParentalDelText, decline ? "no" : "del", l.id);
        if (l.state != STATE_ACTIVE) {
            deleteMessages(uid, l.messageIds, 0);
        }
        changed();
    }

    /** Parent side: stores and sends new rules to the child. */
    public void setRules(long uid, Rules rules) {
        Link l = links.get(uid);
        if (l == null || l.role != ROLE_CHILD) return;
        rules.version = System.currentTimeMillis();
        rules.tz = TimeZone.getDefault().getID();
        l.rules = rules;
        if (l.state == STATE_ACTIVE) sendRules(l);
        changed();
    }

    /** Child side: asks every parent for extra time. */
    public static void askMoreTime() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMoreAsk < 5 * 60_000 && lastMoreAsk != 0) return;
        lastMoreAsk = now;
        forEachParent((c, l) -> c.send(l.uid, R.string.UMessageParentalMoreText, "more", l.id));
    }

    public static boolean isMoreTimeAsked() {
        return lastMoreAsk != 0 && SystemClock.elapsedRealtime() - lastMoreAsk < 5 * 60_000;
    }

    private static final String PIN_PBKDF2_PREFIX = "p2$";
    private static final int PIN_PBKDF2_ITERATIONS = 20000;

    /** Salted PBKDF2 of the parent PIN for new rules: "p2$" + salt hex + "$" + hash hex. */
    public static String hashPin(long childUid, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            return PIN_PBKDF2_PREFIX + hex(salt) + "$" + hex(pbkdf2(childUid, pin, salt, PIN_PBKDF2_ITERATIONS));
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    private static byte[] pbkdf2(long childUid, String pin, byte[] salt, int iterations) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(("ump:" + childUid + ":" + pin).toCharArray(), salt, iterations, 256);
        try {
            return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /** Older app versions sent a plain sha256 of "ump:child:pin". */
    private static String legacyHashPin(long childUid, String pin) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("ump:" + childUid + ":" + pin).getBytes(StandardCharsets.UTF_8));
            return hex(h);
        } catch (Exception e) {
            return "";
        }
    }

    /** Accepts both the PBKDF2 format and the legacy sha256 hex. */
    static boolean pinMatches(String stored, long childUid, String pin) {
        if (TextUtils.isEmpty(stored) || pin == null) return false;
        try {
            if (stored.startsWith(PIN_PBKDF2_PREFIX)) {
                String[] parts = stored.substring(PIN_PBKDF2_PREFIX.length()).split("\\$");
                if (parts.length != 2) return false;
                byte[] salt = Utilities.hexToBytes(parts[0]);
                byte[] expected = Utilities.hexToBytes(parts[1]);
                if (salt == null || expected == null || salt.length == 0 || expected.length == 0) return false;
                return MessageDigest.isEqual(expected, pbkdf2(childUid, pin, salt, PIN_PBKDF2_ITERATIONS));
            }
            return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), legacyHashPin(childUid, pin).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Child side: true when the PIN matches the one any parent set. */
    public static boolean checkPin(String pin) {
        boolean[] ok = {false};
        forEachParent((c, l) -> {
            if (!ok[0] && l.rules != null && pinMatches(l.rules.pin, c.myUid(), pin)) ok[0] = true;
        });
        return ok[0];
    }

    public boolean needsPinToRemove(long uid) {
        Link l = links.get(uid);
        return l != null && l.role == ROLE_PARENT && l.state == STATE_ACTIVE && l.rules != null && !TextUtils.isEmpty(l.rules.pin);
    }

    public boolean checkPin(long uid, String pin) {
        Link l = links.get(uid);
        return l != null && l.rules != null && pinMatches(l.rules.pin, myUid(), pin);
    }

    // ---------------------------------------------------------------- enforcement (device wide)

    /** Shows or hides the lock screen; set by LaunchActivity. */
    public static Runnable lockListener;

    private static boolean foreground;
    private static long countedAt;
    private static long lastMoreAsk;
    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            count();
            notifyLock();
            forEachChildAccount(c -> c.uploadReport(false));
            if (foreground) AndroidUtilities.runOnUIThread(this, 15_000);
        }
    };

    public static void onAppResumed() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) getInstance(a).scan(false);
        }
        foreground = true;
        countedAt = SystemClock.elapsedRealtime();
        AndroidUtilities.cancelRunOnUIThread(tick);
        tick.run();
    }

    public static void onAppPaused() {
        count();
        forEachChildAccount(c -> {
            c.saveCounts();
            c.uploadReport(true);
        });
        foreground = false;
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    /** True when this phone follows some parent's rules. */
    public static boolean isControlled() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) return false;
        boolean[] any = {false};
        forEachParent((c, l) -> any[0] = true);
        return any[0];
    }

    /** The newest rules among all parents of all accounts on this phone, or null. */
    public static Rules effectiveRules() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) return null;
        Rules[] best = {null};
        forEachParent((c, l) -> {
            if (l.rules != null && (best[0] == null || l.rules.version > best[0].version)) best[0] = l.rules;
        });
        return best[0];
    }

    public static int getLockReason() {
        Rules r = effectiveRules();
        if (r == null) return LOCK_NONE;
        SharedPreferences p = globalPrefs();
        long now = now();
        if (r.night && now >= p.getLong("nightUntil", 0) && inNight(minuteOfDay(now), r.nightFrom, r.nightTo)) {
            return LOCK_NIGHT;
        }
        if (r.limit > 0 && getUsedTodayMs() >= (r.limit + getBonusToday()) * 60_000L) {
            return LOCK_LIMIT;
        }
        return LOCK_NONE;
    }

    public static long getUsedTodayMs() {
        return globalPrefs().getLong("u_" + dayKey(now()), 0);
    }

    private static int getBonusToday() {
        SharedPreferences p = globalPrefs();
        return p.getInt("bonusDay", 0) == dayKey(now()) ? p.getInt("bonus", 0) : 0;
    }

    /** Extra minutes: added to today's limit and lifting the night lock for that long. */
    public static void grantExtra(int minutes) {
        if (minutes <= 0) return;
        long now = now();
        int day = dayKey(now);
        SharedPreferences p = globalPrefs();
        int bonus = getBonusToday();
        int base = (int) (getUsedTodayMs() / 60_000);
        Rules r = effectiveRules();
        if (r != null && r.limit > 0) {
            // a limit already passed counts from now, not from the limit
            bonus = Math.max(bonus, base - r.limit);
        }
        p.edit().putInt("bonusDay", day).putInt("bonus", bonus + minutes)
                .putLong("nightUntil", now + minutes * 60_000L).apply();
        lastMoreAsk = 0;
        notifyLock();
    }

    private static void count() {
        long t = SystemClock.elapsedRealtime();
        long delta = t - countedAt;
        countedAt = t;
        if (!foreground || delta <= 0 || delta > 60_000 || !isControlled() || getLockReason() != LOCK_NONE) return;
        int day = dayKey(now());
        SharedPreferences p = globalPrefs();
        SharedPreferences.Editor e = p.edit().putLong("u_" + day, p.getLong("u_" + day, 0) + delta);
        if (p.getInt("pruned", 0) != day) {
            // keep two weeks of history
            for (String k : p.getAll().keySet()) {
                if (k.startsWith("u_") && Utilities.parseInt(k.substring(2)) < daysAgo(14)) e.remove(k);
            }
            e.putInt("pruned", day);
        }
        e.apply();
    }

    /** Day key of {@code n} days before today. */
    static int daysAgo(int n) {
        return dayKey(now() - n * 86_400_000L);
    }

    private static void notifyLock() {
        if (lockListener != null) lockListener.run();
    }

    static boolean inNight(int minute, int from, int to) {
        if (from == to) return false;
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    private static long now() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime() * 1000L;
    }

    private static final String DEFAULT_ZONE = "Asia/Tashkent";
    private static String cachedZoneId;
    private static TimeZone cachedZone;

    /** Fixed zone for the limits: the parent's zone from the rules (Asia/Tashkent for rules from older versions). */
    private static TimeZone zone() {
        String id;
        if (isControlled()) {
            Rules r = effectiveRules();
            id = r != null && !TextUtils.isEmpty(r.tz) ? r.tz : DEFAULT_ZONE;
        } else {
            id = TimeZone.getDefault().getID();
        }
        TimeZone z = cachedZone;
        if (z != null && id.equals(cachedZoneId)) return z;
        z = TimeZone.getTimeZone(id);
        if (!id.equals(z.getID())) z = TimeZone.getTimeZone(DEFAULT_ZONE);
        cachedZoneId = id;
        cachedZone = z;
        return z;
    }

    private static int dayKey(long ms) {
        Calendar c = Calendar.getInstance(zone());
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private static int minuteOfDay(long ms) {
        Calendar c = Calendar.getInstance(zone());
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private interface LinkVisitor {
        void visit(UMessageParental c, Link l);
    }

    private static void forEachParent(LinkVisitor v) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            UMessageParental c = getInstance(a);
            for (Link l : c.links.values()) {
                if (l.role == ROLE_PARENT && l.state == STATE_ACTIVE) v.visit(c, l);
            }
        }
    }

    private static SharedPreferences globalPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umparental_global", Context.MODE_PRIVATE);
    }

    public static String formatMinutes(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return LocaleController.formatString(R.string.UMessageParentalMinutes, m);
        if (m == 0) return LocaleController.formatString(R.string.UMessageParentalHours, h);
        return LocaleController.formatString(R.string.UMessageParentalHoursMinutes, h, m);
    }

    // ---------------------------------------------------------------- incoming commands

    public void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 60_000) return;
        lastScan = now;
        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.users_only = true;
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterUrl();
        req.min_date = ConnectionsManager.getInstance(account).getCurrentTime() - 30 * 86400;
        req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.messages_Messages)) return;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(res.users, false);
                ArrayList<TLRPC.Message> list = new ArrayList<>(res.messages);
                Collections.sort(list, (a, b) -> Integer.compare(a.date, b.date));
                boolean any = false;
                HashMap<Long, ArrayList<TLRPC.Message>> touched = new HashMap<>();
                for (TLRPC.Message m : list) {
                    if (process(m)) {
                        any = true;
                        touched.computeIfAbsent(m.peer_id.user_id, k -> new ArrayList<>()).add(m);
                    }
                }
                if (any) changed();
                for (HashMap.Entry<Long, ArrayList<TLRPC.Message>> e : touched.entrySet()) redraw(e.getKey(), e.getValue());
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || acc != account) return;
        if (args.length > 2 && (boolean) args[2]) return; // scheduled
        long did = (long) args[0];
        if (did <= 0) return;
        ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
        countMessages(did, objects);
        boolean any = false;
        ArrayList<TLRPC.Message> touched = new ArrayList<>();
        for (MessageObject obj : objects) {
            if (process(obj.messageOwner)) {
                any = true;
                touched.add(obj.messageOwner);
            }
        }
        if (any) changed();
        redraw(did, touched);
    }

    /** The chat built these messages before we knew what they were; hand it fresh copies with buttons. */
    private void redraw(long did, ArrayList<TLRPC.Message> messages) {
        if (messages.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<MessageObject> fresh = new ArrayList<>();
            for (TLRPC.Message m : messages) {
                decorate(account, m);
                fresh.add(new MessageObject(account, m, true, true));
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, did, fresh);
        });
    }

    private boolean process(TLRPC.Message m) {
        String[] command = parse(m);
        if (command == null) return false;
        long uid = m.peer_id.user_id;
        if (uid == myUid()) return false;
        String cmd = command[0], arg = command[1], lid = arg.substring(0, 16);
        Link l = links.get(uid);
        boolean same = l != null && lid.equals(l.id);
        switch (cmd) {
            case "req": {
                if (deadIds.contains(lid)) return false;
                if (same) {
                    if (!l.messageIds.contains(m.id)) l.messageIds.add(m.id);
                    return false;
                }
                if (l != null && l.state == STATE_ACTIVE) return false;
                l = new Link();
                l.uid = uid;
                l.id = lid;
                l.role = m.out ? ROLE_CHILD : ROLE_PARENT;
                l.state = m.out ? STATE_OUTGOING : STATE_INCOMING;
                l.rules = m.out ? new Rules() : null;
                // our own request seen from another install: its private key is gone, the child's answer triggers an upgrade
                if (m.out) l.key = randomKey();
                if (!m.out && arg.length() > 16) {
                    byte[] pub = unb64(arg.substring(16));
                    if (pub != null && pub.length == 32) l.parentPub = pub;
                }
                l.messageIds.add(m.id);
                links.put(uid, l);
                return true;
            }
            case "ok": {
                if (!same) return false;
                if (!l.messageIds.contains(m.id)) l.messageIds.add(m.id);
                if (l.state == STATE_ACTIVE) return false;
                l.state = STATE_ACTIVE;
                if (!m.out) {
                    // the child agreed: tidy the chat and send the rules chosen meanwhile
                    deleteMessages(uid, l.messageIds, 0);
                    l.messageIds.clear();
                    if (l.role == ROLE_CHILD) {
                        byte[] childPub = arg.length() > 16 ? unb64(arg.substring(16)) : null;
                        byte[] myPub = l.priv != null ? UMessageKeyAgreement.publicKey(l.priv) : null;
                        byte[] key = childPub != null && childPub.length == 32 && myPub != null
                                ? UMessageKeyAgreement.derive(KDF_PAIR, l.priv, childPub, myPub, childPub) : null;
                        if (key != null) {
                            l.key = key;
                            l.v = 2;
                            l.priv = null;
                        } else if (childPub != null && childPub.length == 32) {
                            // a v2 child, but our private key is gone (reinstall): agree on a new key right away
                            l.priv = null;
                            l.v = 1;
                            l.upAt = 0;
                            maybeUpgrade(l);
                        } else {
                            // an older child: the legacy key travels with the rules
                            l.priv = null;
                            l.v = 1;
                            if (l.key == null) l.key = randomKey();
                        }
                        sendRules(l);
                    }
                }
                return true;
            }
            case "upg": {
                // parent → child: agree on a new key (from a legacy link, or after the parent lost its key)
                if (same && m.out && l.role == ROLE_CHILD && !l.messageIds.contains(m.id)) l.messageIds.add(m.id); // removed on "upk"
                if (!same || m.out || l.role != ROLE_PARENT || l.state != STATE_ACTIVE || arg.length() <= 16) return false;
                byte[] parentPub = unb64(arg.substring(16));
                if (parentPub == null || parentPub.length != 32) return false;
                byte[] priv = UMessageKeyAgreement.newPrivateKey();
                byte[] pub = UMessageKeyAgreement.publicKey(priv);
                byte[] key = pub == null ? null : UMessageKeyAgreement.derive(KDF_UPGRADE, priv, parentPub, parentPub, pub);
                java.util.Arrays.fill(priv, (byte) 0);
                if (key == null) return false;
                send(uid, R.string.UMessageParentalUpgradeText, "upk", l.id + b64(pub));
                deleteMessages(uid, null, m.id);
                l.key = key;
                l.v = 2;
                uploadReport(true);
                return true;
            }
            case "upk": {
                if (!same || l.role != ROLE_CHILD || l.state != STATE_ACTIVE || arg.length() <= 16) return false;
                if (m.out) return false;
                byte[] childPub = unb64(arg.substring(16));
                byte[] myPub = l.priv != null ? UMessageKeyAgreement.publicKey(l.priv) : null;
                if (childPub == null || childPub.length != 32 || myPub == null) return false;
                byte[] key = UMessageKeyAgreement.derive(KDF_UPGRADE, l.priv, childPub, myPub, childPub);
                if (key == null) return false;
                deleteMessages(uid, l.messageIds, m.id);
                l.messageIds.clear();
                l.key = key;
                l.v = 2;
                l.priv = null;
                l.report = null;
                return true;
            }
            case "no":
            case "del": {
                // the child's own "del" (another device, or a hand-made message from any Telegram app) does not end
                // control: only the parent's message, or the PIN-protected screen on this phone, may do that
                if ("del".equals(cmd) && m.out && same && l.role == ROLE_PARENT && l.state == STATE_ACTIVE) return false;
                deadIds.add(lid);
                if (!same) return false;
                // a child turning control off stays visible to the parent
                if (!m.out && l.role == ROLE_PARENT) deleteMessages(uid, l.messageIds, m.id);
                links.remove(uid);
                return true;
            }
            case "rul": {
                if (!same || m.out || l.role != ROLE_PARENT || l.state != STATE_ACTIVE) return false;
                Rules r = null;
                try {
                    JSONObject o = new JSONObject(new String(unb64(arg.substring(16)), StandardCharsets.UTF_8));
                    r = Rules.fromJson(o);
                    // a v2 link agreed its key with X25519: a key inside the rules is never taken over it
                    byte[] key = l.v == 2 ? null : unb64(o.optString("k"));
                    if (key != null && key.length == 32) l.key = key;
                } catch (Exception ignore) {
                }
                deleteMessages(uid, null, m.id);
                if (r == null || (l.rules != null && r.version <= l.rules.version)) return false;
                l.rules = r;
                notifyLock();
                uploadReport(true);
                return true;
            }
            case "more": {
                return same && !m.out && l.role == ROLE_CHILD && l.state == STATE_ACTIVE;
            }
            case "ext": {
                if (!same || m.out || l.role != ROLE_PARENT || l.state != STATE_ACTIVE) return false;
                deleteMessages(uid, null, m.id);
                if (m.id <= l.lastExtra) return false;
                l.lastExtra = m.id;
                int minutes = Utilities.parseInt(arg.substring(16));
                if (minutes > 0) grantExtra(Math.min(minutes, 24 * 60));
                return true;
            }
        }
        return false;
    }

    private void sendRules(Link l) {
        try {
            if (l.rules == null) l.rules = new Rules();
            if (TextUtils.isEmpty(l.rules.tz)) l.rules.tz = TimeZone.getDefault().getID();
            JSONObject o = l.rules.toJson();
            if (l.v != 2) {
                // legacy link: the child's older build still needs the key with the rules
                if (l.key == null) l.key = randomKey();
                o.put("k", b64(l.key));
            }
            send(l.uid, R.string.UMessageParentalRulesText, "rul", l.id + b64(o.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Parent side: asks a legacy link (key sent inside the rules) to agree on an X25519 key instead. */
    private void maybeUpgrade(Link l) {
        if (l.role != ROLE_CHILD || l.state != STATE_ACTIVE || l.v == 2) return;
        long now = System.currentTimeMillis();
        if (now - l.upAt < UPGRADE_RETRY) return;
        if (l.priv == null) l.priv = UMessageKeyAgreement.newPrivateKey();
        byte[] pub = UMessageKeyAgreement.publicKey(l.priv);
        if (pub == null) return;
        l.upAt = now;
        send(l.uid, R.string.UMessageParentalUpgradeText, "upg", l.id + b64(pub));
        save();
    }

    private void deleteMessages(long uid, ArrayList<Integer> ids, int extra) {
        ArrayList<Integer> list = new ArrayList<>();
        if (ids != null) list.addAll(ids);
        if (extra != 0) list.add(extra);
        if (list.isEmpty()) return;
        MessagesController.getInstance(account).deleteMessages(list, null, null, uid, 0, true, 0);
    }

    private void send(long uid, int textRes, String cmd, String arg) {
        send(uid, LocaleController.getString(textRes), cmd, arg);
    }

    /** Sends a readable line whose leading emoji carries the command as a hidden link. */
    private void send(long uid, String text, String cmd, String arg) {
        int space = text.indexOf(' ');
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.offset = 0;
        link.length = space > 0 ? space : text.length();
        link.url = LINK + cmd + "." + arg;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(link);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, uid);
        params.entities = entities;
        SendMessagesHelper.getInstance(account).sendMessage(params);
    }

    /** {command, argument} of a parental control message, or null. */
    private static String[] parse(TLRPC.Message m) {
        if (m == null || !(m.peer_id instanceof TLRPC.TL_peerUser) || m.entities == null) return null;
        for (int i = 0; i < m.entities.size(); i++) {
            TLRPC.MessageEntity e = m.entities.get(i);
            if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && e.url.startsWith(LINK)) {
                Matcher matcher = COMMAND.matcher(e.url);
                if (matcher.find()) return new String[]{matcher.group(1), matcher.group(2)};
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- blocked chats (child side)

    private volatile HashSet<Long> blockedDialogs = new HashSet<>();

    private void rebuildBlocked() {
        HashSet<Long> set = new HashSet<>();
        for (Link l : links.values()) {
            if (l.role == ROLE_PARENT && l.state == STATE_ACTIVE && l.rules != null) set.addAll(l.rules.blocked);
        }
        boolean differs = !set.equals(blockedDialogs);
        blockedDialogs = set;
        if (differs) {
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).sortDialogs(null);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            });
        }
    }

    /** True for a channel or group a parent has blocked on this account. */
    public static boolean isBlockedDialog(int account, long dialogId) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) return false;
        UMessageParental c = instances[account];
        return c != null && dialogId < 0 && c.blockedDialogs.contains(dialogId);
    }

    // ---------------------------------------------------------------- reports (child → parent)

    /** Messages per private chat per day: day key → (dialog id → count). Only kept on a controlled account. */
    private final HashMap<Integer, HashMap<Long, Integer>> messageCounts = new HashMap<>();
    private long lastReport;
    private long lastCountsSave;

    private boolean hasParents() {
        for (Link l : links.values()) {
            if (l.role == ROLE_PARENT && l.state == STATE_ACTIVE) return true;
        }
        return false;
    }

    private interface AccountVisitor {
        void visit(UMessageParental c);
    }

    private static void forEachChildAccount(AccountVisitor v) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && instances[a] != null && instances[a].hasParents()) v.visit(instances[a]);
        }
    }

    private void countMessages(long did, ArrayList<MessageObject> objects) {
        if (objects == null || objects.isEmpty() || did == myUid() || !hasParents() || parse(objects.get(0).messageOwner) != null) return;
        TLRPC.User user = MessagesController.getInstance(account).getUser(did);
        if (user == null || user.bot || UserObject.isService(did)) return;
        int day = dayKey(now());
        HashMap<Long, Integer> map = messageCounts.computeIfAbsent(day, k -> new HashMap<>());
        Integer n = map.get(did);
        map.put(did, (n == null ? 0 : n) + objects.size());
        if (System.currentTimeMillis() - lastCountsSave > 60_000) saveCounts();
    }

    private void saveCounts() {
        lastCountsSave = System.currentTimeMillis();
        try {
            int oldest = daysAgo(7);
            JSONObject o = new JSONObject();
            for (java.util.Iterator<HashMap.Entry<Integer, HashMap<Long, Integer>>> it = messageCounts.entrySet().iterator(); it.hasNext(); ) {
                HashMap.Entry<Integer, HashMap<Long, Integer>> e = it.next();
                if (e.getKey() < oldest) {
                    it.remove();
                    continue;
                }
                JSONObject day = new JSONObject();
                for (HashMap.Entry<Long, Integer> c : e.getValue().entrySet()) day.put(String.valueOf(c.getKey()), c.getValue());
                o.put(String.valueOf(e.getKey()), day);
            }
            prefs().edit().putString("counts", o.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** What the parent sees: usage per day, channels and groups, and who the child writes with most (counts only). */
    private JSONObject buildReport() throws Exception {
        JSONObject r = new JSONObject();
        r.put("t", System.currentTimeMillis());
        SharedPreferences p = globalPrefs();
        JSONArray usage = new JSONArray();
        for (int i = 0; i < 7; i++) {
            int day = daysAgo(i);
            usage.put(new JSONArray().put(day).put(p.getLong("u_" + day, 0) / 60_000));
        }
        r.put("u", usage);

        MessagesController mc = MessagesController.getInstance(account);
        JSONArray chats = new JSONArray();
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(mc.getAllDialogs());
        for (TLRPC.Dialog d : dialogs) {
            if (d.id >= 0 || chats.length() >= 200) continue;
            TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || ChatObject.isNotInChat(chat)) continue;
            JSONObject o = new JSONObject().put("i", d.id).put("n", cut(chat.title))
                    .put("k", ChatObject.isChannelAndNotMegaGroup(chat) ? "c" : "g");
            String username = ChatObject.getPublicUsername(chat);
            if (!TextUtils.isEmpty(username)) o.put("u", username);
            chats.put(o);
        }
        r.put("c", chats);

        HashMap<Long, Integer> total = new HashMap<>();
        int oldest = daysAgo(6);
        for (HashMap.Entry<Integer, HashMap<Long, Integer>> e : messageCounts.entrySet()) {
            if (e.getKey() < oldest) continue;
            for (HashMap.Entry<Long, Integer> c : e.getValue().entrySet()) {
                Integer n = total.get(c.getKey());
                total.put(c.getKey(), (n == null ? 0 : n) + c.getValue());
            }
        }
        ArrayList<HashMap.Entry<Long, Integer>> top = new ArrayList<>(total.entrySet());
        Collections.sort(top, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JSONArray people = new JSONArray();
        for (int i = 0; i < top.size() && i < 10; i++) {
            long uid = top.get(i).getKey();
            TLRPC.User user = mc.getUser(uid);
            JSONObject o = new JSONObject().put("i", uid).put("n", cut(user != null ? UserObject.getUserName(user) : String.valueOf(uid))).put("m", top.get(i).getValue());
            String username = user != null ? UserObject.getPublicUsername(user) : null;
            if (!TextUtils.isEmpty(username)) o.put("u", username);
            people.put(o);
        }
        r.put("p", people);
        return r;
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** Child side: encrypts the report for every parent and puts it on the relay (at most every 10 minutes). */
    void uploadReport(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReport < 10 * 60_000 || now - lastReport < 20_000) return;
        lastReport = now;
        final ArrayList<String[]> items = new ArrayList<>();
        try {
            byte[] plain = gzip(buildReport().toString().getBytes(StandardCharsets.UTF_8));
            for (Link l : links.values()) {
                if (l.role != ROLE_PARENT || l.state != STATE_ACTIVE || l.key == null) continue;
                String blob = encrypt(l.key, plain, l.v == 2 ? ("rep:" + myUid()).getBytes(StandardCharsets.UTF_8) : null);
                int n = (blob.length() + CHUNK - 1) / CHUNK;
                if (n > MAX_CHUNKS) continue;
                for (int i = 0; i < n; i++) {
                    items.add(new String[]{blobId(l.key, myUid(), String.valueOf(i)), blob.substring(i * CHUNK, Math.min(blob.length(), (i + 1) * CHUNK))});
                }
                items.add(new String[]{blobId(l.key, myUid(), "n"), String.valueOf(n)});
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        if (items.isEmpty()) return;
        network.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (String[] item : items) arr.put(new JSONObject().put("id", item[0]).put("data", item[1]));
                http("POST", RELAY, new JSONObject().put("items", arr).toString());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Parent side: downloads and decrypts the child's latest report. */
    public void refreshReport(long uid) {
        Link l = links.get(uid);
        if (l == null || l.role != ROLE_CHILD || l.state != STATE_ACTIVE) return;
        maybeUpgrade(l);
        if (l.key == null) return;
        final byte[] key = l.key;
        final byte[] aad = l.v == 2 ? ("rep:" + uid).getBytes(StandardCharsets.UTF_8) : null;
        StringBuilder ids = new StringBuilder(blobId(key, uid, "n"));
        for (int i = 0; i < MAX_CHUNKS; i++) ids.append(',').append(blobId(key, uid, String.valueOf(i)));
        network.execute(() -> {
            try {
                JSONObject items = new JSONObject(http("GET", RELAY + "?ids=" + ids, null)).getJSONObject("items");
                int n = Utilities.parseInt(items.optString(blobId(key, uid, "n"), "0"));
                if (n <= 0 || n > MAX_CHUNKS) return;
                StringBuilder blob = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String part = items.optString(blobId(key, uid, String.valueOf(i)), null);
                    if (part == null || "null".equals(part)) return;
                    blob.append(part);
                }
                byte[] plain = decrypt(key, blob.toString(), aad);
                if (plain == null) return;
                JSONObject report = new JSONObject(new String(gunzip(plain), StandardCharsets.UTF_8));
                AndroidUtilities.runOnUIThread(() -> {
                    Link cur = links.get(uid);
                    if (cur == null || cur.report != null && cur.report.optLong("t") >= report.optLong("t")) return;
                    cur.report = report;
                    changed();
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static String blobId(byte[] key, long childUid, String part) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(("rep:" + childUid + ":" + part).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] encKey(byte[] key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal("rep-enc".getBytes(StandardCharsets.UTF_8));
    }

    private static String encrypt(byte[] key, byte[] plain, byte[] aad) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(encKey(key), "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return b64(out);
    }

    private static byte[] decrypt(byte[] key, String blob, byte[] aad) {
        try {
            byte[] raw = unb64(blob);
            if (raw == null || raw.length < 29) return null;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(encKey(key), "AES"), new javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12));
            if (aad != null) cipher.updateAAD(aad);
            return cipher.doFinal(raw, 12, raw.length - 12);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] gzip(byte[] data) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String http(String method, String url, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try (java.io.InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- in-chat buttons

    /** Gives a pending request (or an extra time request) its buttons, drawn locally. */
    public static void decorate(int account, TLRPC.Message m) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) return;
        if (m == null || (m.reply_markup != null && !isOurMarkup(m.reply_markup))) return;
        String[] command = parse(m);
        if (command == null) return;
        String lid = command[1].substring(0, 16);
        Link l = getInstance(account).links.get(m.peer_id.user_id);
        TLRPC.TL_replyInlineMarkup markup = null;
        if (l != null && lid.equals(l.id)) {
            if ("req".equals(command[0])) {
                if (!m.out && l.state == STATE_INCOMING) {
                    markup = markup(row(button(LocaleController.getString(R.string.UMessageFriendMapAccept), "a", lid, true),
                            button(LocaleController.getString(R.string.UMessageFriendMapDecline), "d", lid, false)));
                } else if (m.out && l.state == STATE_OUTGOING) {
                    markup = markup(row(button(LocaleController.getString(R.string.UMessageFriendMapCancelRequest), "c", lid, false)));
                }
            } else if ("more".equals(command[0]) && !m.out && l.role == ROLE_CHILD && l.state == STATE_ACTIVE) {
                markup = markup(
                        row(button("+" + formatMinutes(15), "e15", lid, true), button("+" + formatMinutes(30), "e30", lid, true), button("+" + formatMinutes(60), "e60", lid, true)),
                        row(button(LocaleController.getString(R.string.UMessageFriendMapDecline), "e0", lid, false)));
            }
        }
        m.reply_markup = markup;
    }

    private static boolean isOurMarkup(TLRPC.ReplyMarkup markup) {
        if (!(markup instanceof TLRPC.TL_replyInlineMarkup)) return false;
        TLRPC.TL_replyInlineMarkup inline = (TLRPC.TL_replyInlineMarkup) markup;
        if (inline.rows.isEmpty() || inline.rows.get(0).buttons.isEmpty()) return false;
        org.telegram.tgnet.tl.TL_keyboard.InlineButtonType type = inline.rows.get(0).buttons.get(0).type;
        return type instanceof org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback
                && new String(((org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback) type).data, StandardCharsets.UTF_8).startsWith(BUTTON);
    }

    private static TLRPC.TL_replyInlineMarkup markup(org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow... rows) {
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        Collections.addAll(markup.rows, rows);
        return markup;
    }

    private static org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow row(org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton... buttons) {
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow row = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow();
        Collections.addAll(row.buttons, buttons);
        return row;
    }

    private static org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton button(String text, String action, String lid, boolean positive) {
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton b = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton();
        b.text = text;
        org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback type = new org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback();
        type.data = (BUTTON + action + ":" + lid).getBytes(StandardCharsets.UTF_8);
        b.type = type;
        b.style = new org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonStyle();
        if (positive) b.style.bg_success = true; else b.style.bg_danger = true;
        return b;
    }

    /** Handles a press on one of our buttons; returns the action ("a", "d", "c", "e15"…) or null. */
    public static String handleButton(int account, MessageObject messageObject, org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonProto button) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) return null;
        if (messageObject == null || !(button instanceof org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton)) return null;
        org.telegram.tgnet.tl.TL_keyboard.InlineButtonType type = ((org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton) button).type;
        if (!(type instanceof org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback)) return null;
        String data = new String(((org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback) type).data, StandardCharsets.UTF_8);
        if (!data.startsWith(BUTTON)) return null;
        String[] parts = data.split(":");
        if (parts.length < 3) return null;
        UMessageParental c = getInstance(account);
        long uid = messageObject.getDialogId();
        Link l = c.links.get(uid);
        if (l != null && parts[2].equals(l.id)) {
            String action = parts[1];
            if (action.startsWith("e")) {
                int minutes = Utilities.parseInt(action.substring(1));
                if (minutes > 0) {
                    c.send(uid, LocaleController.formatString(R.string.UMessageParentalExtText, formatMinutes(minutes)), "ext", l.id + minutes);
                } else {
                    c.send(uid, R.string.UMessageParentalDeniedText, "ext", l.id + "0");
                }
                c.deleteMessages(uid, null, messageObject.getId());
            } else if ("a".equals(action)) {
                c.accept(uid);
            } else {
                c.remove(uid);
            }
        }
        messageObject.messageOwner.reply_markup = null;
        return parts[1];
    }

    private void changed() {
        save();
        rebuildBlocked();
        notifyLock();
        for (Listener l : new ArrayList<>(listeners)) {
            l.onParentalChanged();
        }
    }

    private long myUid() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static byte[] randomKey() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String randomId() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] unb64(String s) {
        try {
            return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- storage

    private SharedPreferences prefs() {
        return UMessageLocalData.userPrefs(UMessageLocalData.FILE_PARENTAL, account, ownerUid);
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs().getString("links", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Link l = new Link();
                l.uid = o.getLong("u");
                l.id = o.getString("i");
                l.role = o.getInt("r");
                l.state = o.getInt("s");
                l.rules = Rules.fromJson(o.optJSONObject("x"));
                l.lastExtra = o.optInt("e");
                l.key = unb64(o.optString("k"));
                if (l.key != null && l.key.length != 32) l.key = null;
                l.v = o.optInt("v", 1);
                if (o.has("pv")) l.priv = unb64(o.getString("pv"));
                if (o.has("pp")) l.parentPub = unb64(o.getString("pp"));
                l.upAt = o.optLong("ua");
                if (l.key == null && l.role == ROLE_CHILD && l.v != 2) l.key = randomKey();
                String report = o.optString("p", null);
                if (report != null) l.report = new JSONObject(report);
                JSONArray ids = o.optJSONArray("h");
                for (int j = 0; ids != null && j < ids.length(); j++) l.messageIds.add(ids.getInt(j));
                links.put(l.uid, l);
            }
            JSONArray dead = new JSONArray(prefs().getString("dead", "[]"));
            for (int i = 0; i < dead.length(); i++) deadIds.add(dead.getString(i));
            JSONObject counts = new JSONObject(prefs().getString("counts", "{}"));
            for (java.util.Iterator<String> it = counts.keys(); it.hasNext(); ) {
                String day = it.next();
                JSONObject o = counts.getJSONObject(day);
                HashMap<Long, Integer> map = new HashMap<>();
                for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                    String did = k.next();
                    map.put(Long.parseLong(did), o.getInt(did));
                }
                messageCounts.put(Integer.parseInt(day), map);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        rebuildBlocked();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Link l : links.values()) {
                JSONArray ids = new JSONArray();
                for (int id : l.messageIds) ids.put(id);
                JSONObject o = new JSONObject().put("u", l.uid).put("i", l.id).put("r", l.role).put("s", l.state).put("e", l.lastExtra).put("h", ids);
                if (l.rules != null) o.put("x", l.rules.toJson());
                if (l.key != null) o.put("k", b64(l.key));
                o.put("v", l.v).put("ua", l.upAt);
                if (l.priv != null) o.put("pv", b64(l.priv));
                if (l.parentPub != null) o.put("pp", b64(l.parentPub));
                if (l.report != null) o.put("p", l.report.toString());
                arr.put(o);
            }
            prefs().edit().putString("links", arr.toString()).putString("dead", new JSONArray(deadIds).toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
