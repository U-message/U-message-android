package org.telegram.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Friend Map pairing and location relay.
 *
 * Pairing runs over ordinary Telegram messages, so Telegram guarantees who sent a request:
 * A sends B "umf2 req &lt;X25519 public key&gt;", B accepts with "umf2 ok &lt;ref&gt;&lt;public key&gt;"; both derive the
 * pair key with HKDF and never send it. Legacy pairs (v1: a random key inside "umf1 req") keep working and are
 * moved to v2 with "umf2 up" / "umf2 upok" once both phones run a build that knows it. v2 blobs use per-direction
 * keys and bind each blob to its relay slot (AES-GCM AAD). Each side uploads its position AES-GCM-encrypted under an HMAC-derived id to the
 * U message relay, which only ever sees opaque blobs. Handshake messages are deleted once processed.
 */
public class UMessageFriendLocations implements NotificationCenter.NotificationCenterDelegate {

    public static final int STATE_OUTGOING = 0;
    public static final int STATE_INCOMING = 1;
    public static final int STATE_FRIEND = 2;

    public static class Friend {
        public long uid;
        public int state;
        byte[] key;
        String ref;
        final ArrayList<Integer> handshakeIds = new ArrayList<>();
        public Loc location;
        public Zone zone;
        Boolean near; // last known side of the nearby radius
        /** 1 = legacy pair (random key sent in a "umf1 req"), 2 = X25519 agreement (only public keys travel). */
        int v = 1;
        /** Our X25519 private key while a request or an upgrade waits for the answer. */
        byte[] priv;
        /** Public keys of the initiator and the responder (v2): feed the HKDF and the safety code. */
        byte[] pubI, pubR;
        /** Last time we asked a legacy friend to upgrade to v2. */
        long upAt;
        private byte[] outKey, inKey; // per-direction keys (v2), derived lazily

        /** Short code both people can compare to be sure nobody swapped keys, or null for legacy pairs. */
        public String getSafetyCode() {
            return v == 2 && state == STATE_FRIEND ? UMessageKeyAgreement.safetyCode("umf2 sas", pubI, pubR) : null;
        }
    }

    public static class Loc {
        public double lat, lng;
        public float accuracy;
        public long time;      // ms
        public float speedKmh; // -1 when unknown
        public String idle;      // what their walker does while standing (calm, wave, dance, jump)
    }

    public static final int ZONE_OUTGOING = 0;
    public static final int ZONE_INCOMING = 1;
    public static final int ZONE_ACTIVE = 2;

    /** An agreed area: crossing its edge alerts the other person. Proposed by one, accepted by the other. */
    public static class Zone {
        public double lat, lng;
        public int radius; // metres
        public int state;
        String zid;
        Boolean inside; // last known side of the edge for the friend
        final ArrayList<Integer> messageIds = new ArrayList<>();
    }

    public interface Listener {
        void onFriendsChanged();
    }

    private static final String TAG = "#umfriend";
    private static final Pattern COMMAND = Pattern.compile("umf1 (req|ok|no|del|zone|zok|zno|zdel) ([A-Za-z0-9_-]{8,512})");
    private static final String URL = UMessagePremiumController.BASE_URL + "/api/loc";
    // handshake payload rides in a hidden link on the leading emoji; the fragment never reaches a server
    private static final String LINK = UMessagePremiumController.BASE_URL + "/f#umf1.";
    private static final Pattern LINK_COMMAND = Pattern.compile("umf1\\.(req|ok|no|del|zone|zok|zno|zdel)\\.([A-Za-z0-9_-]{8,512})");
    // v2 handshake: only X25519 public keys travel; older builds do not match this prefix and ignore it
    private static final String LINK2 = UMessagePremiumController.BASE_URL + "/f#umf2.";
    private static final Pattern LINK2_COMMAND = Pattern.compile("umf2\\.(req|ok|up|upok)\\.([A-Za-z0-9_-]{8,512})");
    private static final String KDF_PAIR = "umf2 pair", KDF_UPGRADE = "umf2 upgrade";
    private static final long UPGRADE_RETRY = 14 * 86_400_000L;
    private static final String BUTTON = "umf:";
    private static final ExecutorService network = Executors.newSingleThreadExecutor();

    private static final UMessageFriendLocations[] instances = new UMessageFriendLocations[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageFriendLocations getInstance(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        UMessageFriendLocations local = instances[account];
        if (local == null || local.ownerUid != uid) {
            synchronized (UMessageFriendLocations.class) {
                local = instances[account];
                if (local == null || local.ownerUid != uid) {
                    if (local != null) local.detach();
                    instances[account] = local = new UMessageFriendLocations(account, uid);
                }
            }
        }
        return local;
    }

    /** Logout / slot reuse: data stays stored under the Telegram user id, only the slot instance goes. */
    static void resetAccount(int account) {
        synchronized (UMessageFriendLocations.class) {
            UMessageFriendLocations local = instances[account];
            instances[account] = null;
            if (local != null) local.detach();
        }
    }

    private void detach() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages));
    }

    private final int account;
    private final java.util.concurrent.ConcurrentHashMap<Long, Friend> friends = new java.util.concurrent.ConcurrentHashMap<>();
    private final HashSet<String> deadRefs = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private long lastScan;
    private long lastUpload;
    private Location lastSent;
    private boolean pendingForce;
    private boolean uploading;

    /** Telegram user the data belongs to; the slot may later hold someone else. */
    private final long ownerUid;

    private UMessageFriendLocations(int account, long ownerUid) {
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

    public ArrayList<Friend> getAll() {
        ArrayList<Friend> list = new ArrayList<>(friends.values());
        Collections.sort(list, (a, b) -> {
            if (a.state != b.state) return Integer.compare(order(a.state), order(b.state));
            long ta = a.location != null ? a.location.time : 0, tb = b.location != null ? b.location.time : 0;
            return Long.compare(tb, ta);
        });
        return list;
    }

    private static int order(int state) {
        return state == STATE_INCOMING ? 0 : state == STATE_FRIEND ? 1 : 2;
    }

    public Friend get(long uid) {
        return friends.get(uid);
    }

    public boolean hasFriends() {
        for (Friend f : friends.values()) {
            if (f.state == STATE_FRIEND) return true;
        }
        return false;
    }

    public void sendRequest(long uid) {
        Friend f = friends.get(uid);
        if (f != null && f.state != STATE_OUTGOING) {
            return;
        }
        if (f != null && (f.v != 2 || f.priv == null)) {
            // a legacy request (key in the message) is replaced by a v2 one; its message goes away
            deadRefs.add(f.ref);
            deleteHandshake(f);
            friends.remove(uid);
            f = null;
        }
        if (f == null) {
            f = new Friend();
            f.uid = uid;
            f.v = 2;
            f.priv = UMessageKeyAgreement.newPrivateKey();
            f.pubI = UMessageKeyAgreement.publicKey(f.priv);
            if (f.pubI == null) return;
            f.ref = ref(f.pubI);
            f.state = STATE_OUTGOING;
            friends.put(uid, f);
        }
        send2(uid, LocaleController.getString(R.string.UMessageFriendReqText), "req", b64(f.pubI));
        changed();
    }

    public void accept(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.state != STATE_INCOMING) return;
        if (f.v == 2) {
            byte[] priv = UMessageKeyAgreement.newPrivateKey();
            byte[] pubR = UMessageKeyAgreement.publicKey(priv);
            byte[] master = pubR == null ? null : UMessageKeyAgreement.derive(KDF_PAIR, priv, f.pubI, f.pubI, pubR);
            java.util.Arrays.fill(priv, (byte) 0);
            if (master == null) return;
            f.pubR = pubR;
            setKey(f, master);
            f.state = STATE_FRIEND;
            send2(uid, LocaleController.getString(R.string.UMessageFriendOkText), "ok", f.ref + b64(pubR));
        } else {
            f.state = STATE_FRIEND;
            send(uid, R.string.UMessageFriendOkText, "ok", f.ref);
        }
        deleteHandshake(f); // their request has done its job
        changed();
        uploadNow();
        // the accepting side may never have opened the map, so it was never asked for location
        askPermission(org.telegram.ui.LaunchActivity.instance, true);
        restartTracking();
    }

    /** Declines a request, cancels our own request, or removes a friend. */
    public void remove(long uid) {
        Friend f = friends.remove(uid);
        if (f == null) return;
        deadRefs.add(f.ref);
        boolean decline = f.state == STATE_INCOMING;
        send(uid, decline ? R.string.UMessageFriendNoText : R.string.UMessageFriendDelText, decline ? "no" : "del", f.ref);
        if (f.state != STATE_FRIEND) {
            deleteHandshake(f);
        }
        if (f.state == STATE_FRIEND && f.key != null) {
            postItems(Collections.singletonList(new String[]{locId(outKey(f), myUid()), null}));
        }
        changed();
    }

    // ---------------------------------------------------------------- v2 keys

    private void setKey(Friend f, byte[] master) {
        f.key = master;
        f.outKey = f.inKey = null;
        f.priv = null;
    }

    /** The key we write our position with (v2: only for this friend, only in this direction). */
    private byte[] outKey(Friend f) {
        if (f.v != 2) return f.key;
        if (f.outKey == null && f.key != null) f.outKey = UMessageKeyAgreement.subKey(f.key, "dir:" + myUid() + ">" + f.uid);
        return f.outKey;
    }

    /** The key the friend writes its position with. */
    private byte[] inKey(Friend f) {
        if (f.v != 2) return f.key;
        if (f.inKey == null && f.key != null) f.inKey = UMessageKeyAgreement.subKey(f.key, "dir:" + f.uid + ">" + myUid());
        return f.inKey;
    }

    /** Asks a legacy (v1) friend to move to the X25519 scheme; builds without v2 just ignore it. */
    private void maybeUpgrade(Friend f) {
        if (f.v != 1 || f.state != STATE_FRIEND || f.ref == null) return;
        long now = System.currentTimeMillis();
        if (now - f.upAt < UPGRADE_RETRY) return;
        if (f.priv == null) {
            f.priv = UMessageKeyAgreement.newPrivateKey();
            f.pubI = UMessageKeyAgreement.publicKey(f.priv);
        }
        if (f.pubI == null) return;
        f.upAt = now;
        send2(f.uid, LocaleController.getString(R.string.UMessageFriendUpgradeText), "up", f.ref + b64(f.pubI));
        save();
    }

    /** Switches a legacy pair to the v2 master key and clears our old relay slot. */
    private void finishUpgrade(Friend f, byte[] master, byte[] pubI, byte[] pubR) {
        byte[] oldKey = f.key;
        f.v = 2;
        f.pubI = pubI;
        f.pubR = pubR;
        setKey(f, master);
        if (oldKey != null) postItems(Collections.singletonList(new String[]{locId(oldKey, myUid()), null}));
        uploadNow();
    }

    // ---------------------------------------------------------------- zones (geofence alerts)

    /** Proposes an alert zone to a friend; it is active for both once they accept. */
    public void proposeZone(long uid, double lat, double lng, int radiusM) {
        Friend f = friends.get(uid);
        if (f == null || f.state != STATE_FRIEND) return;
        if (f.zone != null) removeZone(uid);
        Zone z = new Zone();
        z.lat = lat;
        z.lng = lng;
        z.radius = radiusM;
        z.state = ZONE_OUTGOING;
        byte[] id = new byte[6];
        new SecureRandom().nextBytes(id);
        z.zid = hex(id);
        f.zone = z;
        String payload = encryptZone(f.key, z);
        if (payload == null) return;
        send(uid, LocaleController.formatString(R.string.UMessageZoneProposalText, formatRadius(radiusM)), "zone", payload);
        changed();
    }

    public void acceptZone(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.zone == null || f.zone.state != ZONE_INCOMING) return;
        f.zone.state = ZONE_ACTIVE;
        f.zone.inside = null;
        send(uid, LocaleController.getString(R.string.UMessageZoneOkText), "zok", f.zone.zid);
        changed();
    }

    /** Declines a proposal, cancels our own, or ends an active zone. */
    public void removeZone(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.zone == null) return;
        Zone z = f.zone;
        f.zone = null;
        boolean decline = z.state == ZONE_INCOMING;
        send(uid, LocaleController.getString(decline ? R.string.UMessageZoneNoText : R.string.UMessageZoneDelText), decline ? "zno" : "zdel", z.zid);
        deleteMessages(uid, z.messageIds, 0);
        changed();
    }

    public static String formatRadius(int metres) {
        return LocaleController.formatDistance(metres, 2);
    }

    /** Alerts when a friend crosses an active zone's edge (compared with the previous fix). */
    private void checkZone(Friend f) {
        Zone z = f.zone;
        if (z == null || z.state != ZONE_ACTIVE || f.location == null) return;
        float[] d = new float[1];
        Location.distanceBetween(z.lat, z.lng, f.location.lat, f.location.lng, d);
        // a small hysteresis band so GPS wobble on the edge does not ping-pong
        boolean inside = z.inside == null ? d[0] <= z.radius : (z.inside ? d[0] <= z.radius + 50 : d[0] < z.radius - 50);
        if (z.inside != null && z.inside != inside) {
            notifyZone(f, inside);
        }
        z.inside = inside;
    }

    private void notifyZone(Friend f, boolean entered) {
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(f.uid);
            String name = user != null ? UserObject.getFirstName(user) : "";
            String text = LocaleController.formatString(entered ? R.string.UMessageZoneEntered : R.string.UMessageZoneLeft, name, formatRadius(f.zone.radius));
            Context context = ApplicationLoader.applicationContext;
            NotificationsController.checkOtherNotificationsChannel();
            android.content.Intent open = new android.content.Intent(context, org.telegram.ui.LaunchActivity.class);
            open.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.live_loc)
                    .setContentTitle(LocaleController.getString(R.string.UMessageFriendMap))
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
            androidx.core.app.NotificationManagerCompat.from(context).notify((int) (0x7a000000 + (f.uid & 0xffffff)), b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static final int NEAR_RADIUS = 300;
    private static final long NEAR_REPEAT = 3 * 60 * 60_000L;

    /** Alerts once when a friend comes within NEAR_RADIUS of us; re-arms after they move well away. */
    private void checkNear(Friend f) {
        Location me = lastLocation;
        if (me == null || f.state != STATE_FRIEND || f.location == null) return;
        long now = System.currentTimeMillis();
        if (now - f.location.time > 15 * 60_000L || now - me.getTime() > 15 * 60_000L) return; // stale fixes say nothing
        float[] d = new float[1];
        Location.distanceBetween(me.getLatitude(), me.getLongitude(), f.location.lat, f.location.lng, d);
        boolean near = f.near != null && f.near ? d[0] <= NEAR_RADIUS * 2 : d[0] <= NEAR_RADIUS;
        if (near && (f.near == null || !f.near)) {
            String key = "near_" + account + "_" + f.uid;
            if (now - globalPrefs().getLong(key, 0) > NEAR_REPEAT) {
                globalPrefs().edit().putLong(key, now).apply();
                notifyNear(f);
            }
        }
        f.near = near;
    }

    private void notifyNear(Friend f) {
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(f.uid);
            if (user == null) return;
            String username = UserObject.getPublicUsername(user);
            String name = !TextUtils.isEmpty(username) ? "@" + username : UserObject.getFirstName(user);
            Context context = ApplicationLoader.applicationContext;
            NotificationsController.checkOtherNotificationsChannel();
            android.content.Intent open = new android.content.Intent(context, org.telegram.ui.LaunchActivity.class);
            open.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            String text = LocaleController.formatString(R.string.UMessageFriendNearby, name);
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.live_loc)
                    .setContentTitle(LocaleController.getString(R.string.UMessageFriendMap))
                    .setContentText(text)
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
            androidx.core.app.NotificationManagerCompat.from(context).notify((int) (0x7b000000 + (f.uid & 0xffffff)), b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** True when any friend has an active zone (the background service then polls positions). */
    public boolean hasActiveZones() {
        for (Friend f : friends.values()) {
            if (f.zone != null && f.zone.state == ZONE_ACTIVE) return true;
        }
        return false;
    }

    /** Looks for handshake messages (at most once a minute unless forced). */
    public void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 60_000) return;
        lastScan = now;
        search("", new TLRPC.TL_inputMessagesFilterUrl());
        search(TAG, new TLRPC.TL_inputMessagesFilterEmpty()); // requests sent by older builds
    }

    private void search(String q, TLRPC.MessagesFilter filter) {
        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.users_only = true;
        req.q = q;
        req.filter = filter;
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

    /** Downloads and decrypts the latest positions of all friends. */
    public void refreshLocations() {
        // a standing phone gets no new fixes (distance filters), so every poll also re-sends the last one;
        // upload() lets it through once a minute while the fix is under 2 minutes old (the "updated just now" heartbeat)
        upload(lastLocation, false);
        ArrayList<Friend> list = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        HashMap<String, Friend> byId = new HashMap<>();
        HashMap<Friend, byte[]> inKeys = new HashMap<>();
        for (Friend f : friends.values()) {
            if (f.state != STATE_FRIEND || inKey(f) == null) continue;
            maybeUpgrade(f);
            inKeys.put(f, inKey(f));
            String id = locId(inKey(f), f.uid);
            byId.put(id, f);
            ids.add(id);
            list.add(f);
        }
        if (list.isEmpty()) return;
        // the same request carries the "someone is watching me" flags, so live mode costs no extra polling
        long me = myUid();
        HashMap<String, Friend> watchIds = new HashMap<>();
        for (Friend f : list) {
            String id = watchId(inKeys.get(f), me);
            watchIds.put(id, f);
            ids.add(id);
        }
        network.execute(() -> {
            try {
                // the relay answers at most 100 ids per request
                JSONObject items = new JSONObject();
                for (int i = 0; i < ids.size(); i += 100) {
                    String query = TextUtils.join(",", ids.subList(i, Math.min(ids.size(), i + 100)));
                    JSONObject part = new JSONObject(http("GET", URL + "?ids=" + query, null)).getJSONObject("items");
                    java.util.Iterator<String> keys = part.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        items.put(k, part.get(k));
                    }
                }
                long watchedUntil = 0;
                for (HashMap.Entry<String, Friend> e : watchIds.entrySet()) {
                    String blob = items.optString(e.getKey(), null);
                    if (blob == null || "null".equals(blob)) continue;
                    Friend wf = e.getValue();
                    watchedUntil = Math.max(watchedUntil, decryptWatch(inKeys.get(wf), blob, wf.v == 2 ? e.getKey() : null));
                }
                long until = watchedUntil;
                if (until > System.currentTimeMillis()) AndroidUtilities.runOnUIThread(() -> setLiveUntil(until));
                HashMap<Friend, Loc> result = new HashMap<>();
                for (HashMap.Entry<String, Friend> e : byId.entrySet()) {
                    String blob = items.optString(e.getKey(), null);
                    if (blob == null || "null".equals(blob)) continue;
                    Friend lf = e.getValue();
                    // v2 binds the blob to its slot (AAD), so the relay cannot move one friend's blob into another's slot
                    Loc loc = decrypt(inKeys.get(lf), blob, lf.v == 2 ? e.getKey() : null);
                    if (loc != null) result.put(e.getValue(), loc);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    boolean any = false;
                    for (HashMap.Entry<Friend, Loc> e : result.entrySet()) {
                        Loc old = e.getKey().location;
                        if (old == null || old.time != e.getValue().time) {
                            e.getKey().location = e.getValue();
                            checkZone(e.getKey());
                            checkNear(e.getKey());
                            any = true;
                        }
                    }
                    if (any) changed();
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Encrypts our position once per friend and uploads it (throttled). */
    public void upload(Location location, boolean force) {
        long now = System.currentTimeMillis();
        if (location == null || isSharingPaused()) return;
        if (uploading) {
            // a forced write (new friend, character change) must not be lost to a write already in flight
            if (force) pendingForce = true;
            return;
        }
        if (!force && now - lastUpload < (trackingMode >= 2 ? 2000 : 4000)) return;
        // a heartbeat with an old fix would only repeat it; friends see its real age instead
        if (!force && now - fixTime(location) > 120_000) return;
        // standing still (the filter locks the position): no write until it moves, or a heartbeat every minute
        if (!force && lastSent != null && now - lastUpload < 60_000 && lastSent.distanceTo(location) < 3) return;
        lastSent = location;
        long me = myUid();
        ArrayList<String[]> items = new ArrayList<>();
        for (Friend f : friends.values()) {
            byte[] key = f.state == STATE_FRIEND ? outKey(f) : null;
            if (key == null) continue;
            String id = locId(key, me);
            String blob = encrypt(key, location, f.v == 2 ? id : null);
            if (blob != null) items.add(new String[]{id, blob});
        }
        if (items.isEmpty()) return;
        lastUpload = now;
        postItems(items);
    }

    public void uploadNow() {
        upload(lastLocation, true);
    }

    /** Internet is back: send the position we took offline right away. */
    static void onNetworkAvailable() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null && instances[a].hasFriends()) {
                instances[a].uploadNow();
                instances[a].refreshLocations();
            }
        }
    }

    private static final long WATCH_MS = 6 * 60_000L;
    private long lastWatch;

    /**
     * Tells every friend's phone "I am looking at you right now" (encrypted flag on the relay, valid 6 min).
     * Their phone picks it up on its next poll (instantly when open, within 2 min in the background)
     * and switches to precise 1-second GPS until the flag runs out. Called while our map is open.
     */
    public void watchFriends() {
        long now = System.currentTimeMillis();
        if (now - lastWatch < 2 * 60_000L) return;
        ArrayList<String[]> items = new ArrayList<>();
        for (Friend f : friends.values()) {
            byte[] key = f.state == STATE_FRIEND ? outKey(f) : null;
            if (key == null) continue;
            String id = watchId(key, f.uid);
            String blob = encryptWatch(key, now + WATCH_MS, f.v == 2 ? id : null);
            if (blob != null) items.add(new String[]{id, blob});
        }
        if (items.isEmpty()) return;
        lastWatch = now;
        postItems(items);
    }

    // ---------------------------------------------------------------- people nearby

    /** Someone who turned on "Visible to people nearby" (position rounded to ~100 m by the server). */
    public static class NearbyPerson {
        public long uid;
        public String username;
        public double lat, lng;
        public int distance; // metres
        public long time;    // ms
    }

    private static final String NEARBY_URL = UMessagePremiumController.BASE_URL + "/api/loc?nearby=1";
    private final ArrayList<NearbyPerson> nearby = new ArrayList<>();
    private long lastNearby;
    private boolean nearbyBusy;

    /** Separate consent per Telegram account (keyed by user id), off until the user turns it on. */
    public static boolean isNearbyEnabled(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid == 0) return false;
        SharedPreferences p = globalPrefs();
        if (p.contains("nearby")) {
            // older builds kept one consent for every account: it now covers only the account that was selected
            boolean was = p.getBoolean("nearby", false);
            SharedPreferences.Editor e = p.edit().remove("nearby");
            long selected = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
            if (was && selected != 0) e.putBoolean("nearby_u" + selected, true);
            e.commit();
        }
        return p.getBoolean("nearby_u" + uid, false);
    }

    public static void setNearbyEnabled(int account, boolean enabled) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid == 0 || !UserConfig.getInstance(account).isClientActivated()) return;
        isNearbyEnabled(account); // runs the one-time migration first
        globalPrefs().edit().putBoolean("nearby_u" + uid, enabled).apply();
        UMessageFriendLocations c = getInstance(account);
        if (enabled) {
            c.lastNearby = 0;
            c.updateNearby(lastLocation);
        } else {
            c.nearbyOff();
        }
    }

    public ArrayList<NearbyPerson> getNearby() {
        return isNearbyEnabled(account) ? new ArrayList<>(nearby) : new ArrayList<>();
    }

    /** ~1 km grid: the relay never learns a finer position for people nearby. */
    private static double roundToGrid(double deg, double cellDeg) {
        return Math.floor(deg / cellDeg) * cellDeg + cellDeg / 2;
    }

    /** Publishes our rounded position and fetches who is around (every 20 s while the map is open, else 2 min). */
    public void updateNearby(Location location) {
        if (!isNearbyEnabled(account) || location == null || nearbyBusy || isSharingPaused()) return;
        long now = System.currentTimeMillis();
        if (now - lastNearby < (mapOpen ? 20_000 : 120_000)) return;
        long me = myUid();
        String token = UMessagePremiumController.getInstance().getToken(me);
        if (me == 0 || token == null) return;
        lastNearby = now;
        nearbyBusy = true;
        // rounded on the device to a ~1 km cell (0.009° of latitude; longitude scaled by latitude)
        double cellLat = 0.009;
        double cellLng = cellLat / Math.max(0.2, Math.cos(Math.toRadians(location.getLatitude())));
        double lat = roundToGrid(location.getLatitude(), cellLat), lng = roundToGrid(location.getLongitude(), cellLng);
        network.execute(() -> {
            ArrayList<NearbyPerson> result = null;
            try {
                JSONObject body = new JSONObject().put("userId", String.valueOf(me)).put("token", token)
                        .put("action", "set").put("lat", lat).put("lng", lng);
                JSONObject r = new JSONObject(http("POST", NEARBY_URL, body.toString()));
                JSONArray arr = r.optJSONArray("people");
                if (arr != null) {
                    result = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        NearbyPerson p = new NearbyPerson();
                        p.uid = Long.parseLong(o.getString("userId"));
                        p.username = o.isNull("username") ? null : o.optString("username", null);
                        p.lat = o.getDouble("lat");
                        p.lng = o.getDouble("lng");
                        p.distance = o.optInt("distance");
                        p.time = o.optLong("t");
                        result.add(p);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                lastNearby = 0; // offline: try again with the next fix
            }
            ArrayList<NearbyPerson> people = result;
            AndroidUtilities.runOnUIThread(() -> {
                nearbyBusy = false;
                if (people == null || !isNearbyEnabled(account)) return;
                nearby.clear();
                MessagesController mc = MessagesController.getInstance(account);
                for (NearbyPerson p : people) {
                    if (p.uid == me) continue;
                    Friend f = friends.get(p.uid);
                    if (f != null && f.state == STATE_FRIEND) continue; // friends are already on the map, precisely
                    nearby.add(p);
                    // strangers are usually unknown to our Telegram cache: their public username brings them in
                    if (mc.getUser(p.uid) == null && !TextUtils.isEmpty(p.username)) {
                        mc.getUserNameResolver().resolve(p.username, id -> notifyListeners());
                    }
                }
                notifyListeners();
            });
        });
    }

    private void nearbyOff() {
        nearby.clear();
        lastNearby = 0;
        notifyListeners();
        long me = myUid();
        String token = UMessagePremiumController.getInstance().getToken(me);
        if (me == 0 || token == null) return;
        network.execute(() -> {
            try {
                http("POST", NEARBY_URL, new JSONObject().put("userId", String.valueOf(me)).put("token", token).put("action", "off").toString());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onFriendsChanged();
        }
    }

    // ---------------------------------------------------------------- foreground tracking

    private static boolean appForeground, mapOpen, hadPermission;
    private static final UMessageLocationFilter filter = new UMessageLocationFilter();
    private static long liveUntil;
    private static final Runnable liveEnd = UMessageFriendLocations::updateTracking;
    private static com.google.android.gms.location.FusedLocationProviderClient fused;
    private static final com.google.android.gms.location.LocationCallback fusedCallback = new com.google.android.gms.location.LocationCallback() {
        @Override
        public void onLocationResult(@androidx.annotation.NonNull com.google.android.gms.location.LocationResult result) {
            for (Location l : result.getLocations()) dispatchLocation(l);
        }
    };

    /** A friend's watch may keep precise GPS on for at most this long in a row, then rests as long. */
    private static final long LIVE_MAX_CONTINUOUS = 15 * 60_000L, LIVE_COOLDOWN = 15 * 60_000L;
    private static final int LIVE_MAX_PER_DAY = 8;
    private static long liveSessionStart, liveCooldownUntil;
    private static int liveDay, liveCount;

    /** A friend is watching us: precise tracking while the app is open, capped in length and per day. */
    private static void setLiveUntil(long until) {
        long now = System.currentTimeMillis();
        if (isSharingPaused() || now < liveCooldownUntil) return;
        if (!isLive()) {
            int day = (int) (now / 86_400_000L);
            if (day != liveDay) {
                liveDay = day;
                liveCount = 0;
            }
            if (liveCount >= LIVE_MAX_PER_DAY) return;
            liveCount++;
            liveSessionStart = now;
        }
        long cap = liveSessionStart + LIVE_MAX_CONTINUOUS;
        if (until > cap) {
            until = cap;
            liveCooldownUntil = cap + LIVE_COOLDOWN;
        }
        if (until <= liveUntil) return;
        boolean was = isLive();
        liveUntil = until;
        AndroidUtilities.cancelRunOnUIThread(liveEnd);
        AndroidUtilities.runOnUIThread(liveEnd, Math.max(1000, until - System.currentTimeMillis() + 1000));
        if (!was) updateTracking();
    }

    public static boolean isLive() {
        return liveUntil > System.currentTimeMillis();
    }
    private static boolean launchPermissionAsked;
    private static int trackingMode; // 0 off, 1 slow, 2 fast
    private static Location lastLocation;
    private static final LocationListener tracker = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            dispatchLocation(location);
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

    /** Uploads a fresh fix for every logged-in account (also used by the background service). */
    public static void dispatchLocation(Location location) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.FRIEND_MAP)) return;
        if (location == null) return;
        location = filter.apply(location);
        if (location == null) return;
        if (lastLocation != null && location.getAccuracy() > lastLocation.getAccuracy() * 3
                && location.getTime() - lastLocation.getTime() < 15_000) {
            return; // ignore a much worse fix right after a good one
        }
        lastLocation = location;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                UMessageFriendLocations c = getInstance(a);
                c.upload(location, false);
                c.updateNearby(location);
                for (Friend f : c.friends.values()) c.checkNear(f);
            }
        }
    }

    // ---------------------------------------------------------------- background mode

    private static boolean serviceRunning;

    private static SharedPreferences globalPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umfriends_global", Context.MODE_PRIVATE);
    }

    public static final String[] IDLE_STYLES = {"calm", "wave", "dance", "jump", "phone", "sit", "jog", "stretch", "clap", "music", "sleep", "ball", "spin", "wait"};
    public static final int[] IDLE_NAMES = {R.string.UMessageIdleCalm, R.string.UMessageIdleWave, R.string.UMessageIdleDance, R.string.UMessageIdleJump,
            R.string.UMessageIdlePhone, R.string.UMessageIdleSit, R.string.UMessageIdleJog, R.string.UMessageIdleStretch, R.string.UMessageIdleClap,
            R.string.UMessageIdleMusic, R.string.UMessageIdleSleep, R.string.UMessageIdleBall, R.string.UMessageIdleSpin, R.string.UMessageIdleWait};

    /** What our walker does on friends' maps while we stand still. */
    public static String getMyIdle() {
        return globalPrefs().getString("idle", "calm");
    }

    public static void setMyIdle(String id) {
        globalPrefs().edit().putString("idle", id).apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null) instances[a].uploadNow();
        }
    }

    /** Once a day per account: lets the admin dashboard estimate Mapbox monthly active users. */
    public static void reportMapOpen(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid <= 0) return;
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        String key = "mapopen_" + account;
        if (day.equals(globalPrefs().getString(key, ""))) return;
        globalPrefs().edit().putString(key, day).apply();
        network.execute(() -> {
            try {
                http("POST", UMessagePremiumController.BASE_URL + "/api/usage", new JSONObject().put("userId", String.valueOf(uid)).put("event", "map").toString());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static boolean isBackgroundEnabled() {
        return globalPrefs().getBoolean("background", false); // off until the user turns it on
    }

    /** One switch that stops every upload of our position (friends, people nearby, live mode) until turned back on. */
    public static boolean isSharingPaused() {
        return globalPrefs().getBoolean("paused", false);
    }

    public static void setSharingPaused(boolean paused) {
        globalPrefs().edit().putBoolean("paused", paused).apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UMessageFriendLocations c = instances[a];
            if (c == null) continue;
            if (paused) {
                // friends stop seeing the last position too, not just updates
                ArrayList<String[]> items = new ArrayList<>();
                for (Friend f : c.friends.values()) {
                    byte[] key = f.state == STATE_FRIEND ? c.outKey(f) : null;
                    if (key != null) items.add(new String[]{locId(key, c.myUid()), null});
                }
                if (!items.isEmpty()) c.postItems(items);
                if (isNearbyEnabled(a)) c.nearbyOff();
            } else {
                c.lastNearby = 0;
                c.uploadNow();
            }
        }
        if (paused) {
            liveUntil = 0;
            AndroidUtilities.cancelRunOnUIThread(liveEnd);
        }
        restartTracking();
    }

    public static void setBackgroundEnabled(boolean enabled) {
        globalPrefs().edit().putBoolean("background", enabled).apply();
        updateService();
    }

    /** Runs the background service only while it is wanted, allowed and useful. */
    private static void updateService() {
        Context context = ApplicationLoader.applicationContext;
        boolean anyFriends = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null && instances[a].hasFriends()) anyFriends = true;
        }
        boolean want = isBackgroundEnabled() && !isSharingPaused() && anyFriends && hasPermission(context)
                && UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.FRIEND_MAP);
        if (want && !serviceRunning && appForeground) {
            serviceRunning = true;
            UMessageFriendLocationService.start(context);
        } else if (!want) {
            serviceRunning = false;
            UMessageFriendLocationService.stop(context);
        }
    }

    /** The service stopped on its own (refused by the system or the notification's Stop): start it again next time. */
    static void onServiceStopped() {
        serviceRunning = false;
    }

    public static Location getLastLocation() {
        return lastLocation;
    }

    public static void onAppResumed() {
        appForeground = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT && UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.FRIEND_MAP); a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                UMessageFriendLocations c = getInstance(a);
                c.scan(false);
                if (c.hasFriends()) c.refreshLocations();
            }
        }
        updateTracking();
    }

    public static void onAppPaused() {
        appForeground = false;
        updateTracking();
    }

    public static void setMapOpen(boolean open) {
        mapOpen = open;
        updateTracking();
    }

    @SuppressLint("MissingPermission")
    private static void updateTracking() {
        updateService();
        boolean anyFriends = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null && instances[a].hasFriends()) anyFriends = true;
        }
        boolean sharing = anyFriends && !isSharingPaused();
        // 3 = a friend is watching (only while the app is open: a watcher never keeps the background service on
        // high accuracy), 2 = our map is open (our own dot, even when sharing is paused)
        int mode = !appForeground ? 0 : sharing && isLive() ? 3 : mapOpen ? 2 : sharing ? 1 : 0;
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.FRIEND_MAP)) mode = 0; // switched off from the admin panel
        Context context = ApplicationLoader.applicationContext;
        boolean permission = hasPermission(context);
        // a grant from a permission dialog arrives as a plain resume, so a permission change also restarts tracking
        if (mode == trackingMode && permission == hadPermission) return;
        trackingMode = mode;
        hadPermission = permission;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.removeUpdates(tracker);
            if (fused != null) fused.removeLocationUpdates(fusedCallback);
        } catch (Exception ignore) {
        }
        if (mode == 0 || !hasPermission(context)) return;
        if (mode >= 2) {
            // live: Google's fused provider blends GNSS with Wi-Fi, cell and motion sensors, the same kind of source navigators use
            try {
                if (fused == null) fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context);
                com.google.android.gms.location.LocationRequest request = new com.google.android.gms.location.LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000)
                        .setMinUpdateIntervalMillis(500)
                        .setWaitForAccurateLocation(true)
                        .build();
                fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper());
                return;
            } catch (Throwable e) {
                FileLog.e(e); // no Play services: fall back to raw GPS below
            }
        }
        long interval = mode >= 2 ? 1000 : 30_000;
        float distance = mode >= 2 ? 0 : 25;
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, interval, distance, tracker, Looper.getMainLooper());
                    Location last = lm.getLastKnownLocation(provider);
                    if (last != null && (lastLocation == null || last.getTime() > lastLocation.getTime())) {
                        tracker.onLocationChanged(last);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for location when it matters and is still missing: once per launch on app entry while we have friends,
     * or always when {@code force} (map opened, request accepted).
     */
    public static void askPermission(android.app.Activity activity, boolean force) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.FRIEND_MAP)) return;
        if (activity == null || Build.VERSION.SDK_INT < 23 || hasPermission(activity)) return;
        if (!force) {
            if (launchPermissionAsked) return;
            boolean anyFriends = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated() && getInstance(a).hasFriends()) anyFriends = true;
            }
            if (!anyFriends) return;
            launchPermissionAsked = true;
        }
        withDisclosure(activity, false, () -> {
            try {
                activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, null);
    }

    public static final int PERMISSION_REQUEST = 0x7f1;

    /**
     * Google Play prominent disclosure before any location permission request (and before background sharing):
     * what is collected, who receives it and how to stop it. Shown until the user continues once per kind.
     */
    public static void withDisclosure(android.app.Activity activity, boolean background, Runnable onAccept, Runnable onDecline) {
        final String key = background ? "umfriend_disclosure_bg" : "umfriend_disclosure_fg";
        if (MessagesController.getGlobalMainSettings().getBoolean(key, false)) {
            onAccept.run();
            return;
        }
        if (activity == null || activity.isFinishing()) return;
        AndroidUtilities.runOnUIThread(() -> new org.telegram.ui.ActionBar.AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(background ? R.string.UMessageFriendMapDisclosureBgTitle : R.string.UMessageFriendMapDisclosureTitle))
                .setMessage(LocaleController.getString(background ? R.string.UMessageFriendMapDisclosureBgText : R.string.UMessageFriendMapDisclosureText))
                .setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapDisclosureContinue), (d, w) -> {
                    MessagesController.getGlobalMainSettings().edit().putBoolean(key, true).apply();
                    onAccept.run();
                })
                .setNegativeButton(LocaleController.getString(R.string.UMessageFriendMapDisclosureDecline), (d, w) -> {
                    if (onDecline != null) onDecline.run();
                })
                .show());
    }

    /** Re-evaluates tracking after a permission grant or a new friend. */
    public static void restartTracking() {
        trackingMode = -1;
        updateTracking();
    }

    // ---------------------------------------------------------------- handshake

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || acc != account) return;
        if (args.length > 2 && (boolean) args[2]) return; // scheduled
        long did = (long) args[0];
        if (did <= 0) return;
        ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
        boolean any = false;
        ArrayList<TLRPC.Message> requests = new ArrayList<>();
        for (MessageObject obj : objects) {
            if (process(obj.messageOwner)) {
                any = true;
                requests.add(obj.messageOwner);
            }
        }
        if (any) changed();
        redraw(did, requests);
    }

    /**
     * The chat built this message before we knew it was a request, so it has no buttons yet; hand the
     * chat a fresh copy (decorated with the current state) so Accept / Decline show up right away.
     */
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
        String cmd = command[0], arg = command[1];
        Friend f = friends.get(uid);
        switch (cmd) {
            case "req2": {
                byte[] pub = unb64(arg);
                if (pub == null || pub.length != 32) return false;
                String ref = ref(pub);
                if (deadRefs.contains(ref) || (f != null && ref.equals(f.ref))) {
                    if (f != null && !f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                    return false;
                }
                // our own request seen from another install: its private key is not in the message, nothing to restore
                if (m.out) return false;
                if (f != null && f.state == STATE_FRIEND) return false;
                if (f != null && f.state == STATE_OUTGOING) {
                    // both asked at once: the smaller user id's request wins
                    if (uid > myUid()) return false;
                    adoptRequest2(f, pub, ref, m.id);
                    accept(uid);
                    return true;
                }
                if (f == null) {
                    f = new Friend();
                    f.uid = uid;
                    friends.put(uid, f);
                }
                adoptRequest2(f, pub, ref, m.id);
                return true;
            }
            case "ok2": {
                if (arg.length() <= 16) return false;
                byte[] pub = unb64(arg.substring(16));
                if (f == null || pub == null || pub.length != 32 || !arg.substring(0, 16).equals(f.ref)) return false;
                if (!f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                if (f.state != STATE_OUTGOING || m.out || f.v != 2 || f.priv == null) return false;
                byte[] master = UMessageKeyAgreement.derive(KDF_PAIR, f.priv, pub, f.pubI, pub);
                if (master == null) return false;
                f.pubR = pub;
                setKey(f, master);
                f.state = STATE_FRIEND;
                deleteHandshake(f); // we asked and they agreed: clean the handshake out of the chat
                uploadNow();
                return true;
            }
            case "up2": {
                if (arg.length() <= 16) return false;
                byte[] pub = unb64(arg.substring(16));
                if (f == null || pub == null || pub.length != 32 || f.v != 1 || f.state != STATE_FRIEND || !arg.substring(0, 16).equals(f.ref)) return false;
                if (m.out) {
                    if (!f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                    return false;
                }
                // both proposed at once: the smaller user id's proposal wins
                if (f.priv != null && uid > myUid()) return false;
                byte[] priv = UMessageKeyAgreement.newPrivateKey();
                byte[] pubR = UMessageKeyAgreement.publicKey(priv);
                byte[] master = pubR == null ? null : UMessageKeyAgreement.derive(KDF_UPGRADE, priv, pub, pub, pubR);
                java.util.Arrays.fill(priv, (byte) 0);
                if (master == null) return false;
                send2(uid, LocaleController.getString(R.string.UMessageFriendUpgradeText), "upok", f.ref + b64(pubR));
                deleteMessages(uid, null, m.id);
                finishUpgrade(f, master, pub, pubR);
                return true;
            }
            case "upok2": {
                if (arg.length() <= 16) return false;
                byte[] pub = unb64(arg.substring(16));
                if (f == null || pub == null || pub.length != 32 || m.out || f.v != 1 || f.priv == null || !arg.substring(0, 16).equals(f.ref)) return false;
                byte[] master = UMessageKeyAgreement.derive(KDF_UPGRADE, f.priv, pub, f.pubI, pub);
                if (master == null) return false;
                deleteMessages(uid, f.handshakeIds, m.id);
                f.handshakeIds.clear();
                finishUpgrade(f, master, f.pubI, pub);
                return true;
            }
            case "req": {
                byte[] key = unb64(arg);
                if (key == null || key.length != 32) return false;
                String ref = ref(key);
                if (deadRefs.contains(ref) || (f != null && ref.equals(f.ref))) {
                    if (f != null && !f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                    return false;
                }
                if (f != null && f.state == STATE_FRIEND) return false;
                if (f != null && f.state == STATE_OUTGOING && !m.out) {
                    // both asked at once: the smaller user id's key wins
                    if (uid > myUid()) return false;
                    legacy(f);
                    f.key = key;
                    f.ref = ref;
                    f.state = STATE_INCOMING;
                    f.handshakeIds.add(m.id);
                    accept(uid);
                    return true;
                }
                if (f == null) {
                    f = new Friend();
                    f.uid = uid;
                    friends.put(uid, f);
                }
                legacy(f);
                f.key = key;
                f.ref = ref;
                f.state = m.out ? STATE_OUTGOING : STATE_INCOMING;
                f.handshakeIds.add(m.id);
                return true;
            }
            case "ok": {
                if (f == null || !arg.equals(f.ref)) return false;
                if (!f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                if (f.state == STATE_FRIEND || f.v != 1) return false;
                // an "ok" only answers a request: theirs for ours, or ours for theirs
                if (!((!m.out && f.state == STATE_OUTGOING) || (m.out && f.state == STATE_INCOMING))) return false;
                f.state = STATE_FRIEND;
                if (!m.out) {
                    // we asked and they agreed: clean the handshake out of the chat
                    deleteHandshake(f);
                }
                uploadNow();
                return true;
            }
            case "zone": {
                if (f == null || f.state != STATE_FRIEND) return false;
                Zone z = decryptZone(f.key, arg);
                if (z == null) return false;
                if (deadRefs.contains("z" + z.zid)) return false;
                if (f.zone != null && z.zid.equals(f.zone.zid)) {
                    if (!f.zone.messageIds.contains(m.id)) f.zone.messageIds.add(m.id);
                    return false;
                }
                z.state = m.out ? ZONE_OUTGOING : ZONE_INCOMING;
                z.messageIds.add(m.id);
                f.zone = z;
                return true;
            }
            case "zok": {
                if (f == null || f.zone == null || !arg.equals(f.zone.zid)) return false;
                if (!f.zone.messageIds.contains(m.id)) f.zone.messageIds.add(m.id);
                if (f.zone.state == ZONE_ACTIVE) return false;
                // the proposer cannot activate its own zone
                if (!((!m.out && f.zone.state == ZONE_OUTGOING) || (m.out && f.zone.state == ZONE_INCOMING))) return false;
                f.zone.state = ZONE_ACTIVE;
                f.zone.inside = null;
                if (!m.out) {
                    deleteMessages(uid, f.zone.messageIds, 0); // agreed: tidy the chat
                    f.zone.messageIds.clear();
                }
                return true;
            }
            case "zno":
            case "zdel": {
                deadRefs.add("z" + arg);
                if (!m.out) deleteMessages(uid, f != null && f.zone != null && arg.equals(f.zone.zid) ? f.zone.messageIds : null, m.id);
                if (f == null || f.zone == null || !arg.equals(f.zone.zid)) return false;
                f.zone = null;
                return true;
            }
            case "no":
            case "del": {
                if (!m.out) {
                    deleteMessages(uid, f != null && arg.equals(f.ref) ? f.handshakeIds : null, m.id);
                }
                deadRefs.add(arg);
                if (f == null || !arg.equals(f.ref)) return false;
                friends.remove(uid);
                return true;
            }
        }
        return false;
    }

    /** A pair made from a v1 request (an older build asked us). */
    private static void legacy(Friend f) {
        f.v = 1;
        f.priv = null;
        f.pubI = f.pubR = null;
        f.outKey = f.inKey = null;
    }

    private static void adoptRequest2(Friend f, byte[] pub, String ref, int messageId) {
        f.v = 2;
        f.key = null;
        f.outKey = f.inKey = null;
        f.priv = null;
        f.pubI = pub;
        f.pubR = null;
        f.ref = ref;
        f.state = STATE_INCOMING;
        f.handshakeIds.add(messageId);
    }

    private void deleteHandshake(Friend f) {
        deleteMessages(f.uid, f.handshakeIds, 0);
        f.handshakeIds.clear();
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
        sendLink(uid, text, LINK + cmd + "." + arg);
    }

    /** A v2 handshake line (X25519 public keys only). */
    private void send2(long uid, String text, String cmd, String arg) {
        sendLink(uid, text, LINK2 + cmd + "." + arg);
    }

    private void sendLink(long uid, String text, String url) {
        int space = text.indexOf(' ');
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.offset = 0;
        link.length = space > 0 ? space : text.length();
        link.url = url;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(link);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, uid);
        params.entities = entities;
        SendMessagesHelper.getInstance(account).sendMessage(params);
    }

    /** {command, argument} of a Friend Map handshake message, or null. */
    private static String[] parse(TLRPC.Message m) {
        if (m == null || !(m.peer_id instanceof TLRPC.TL_peerUser)) return null;
        if (m.entities != null) {
            for (int i = 0; i < m.entities.size(); i++) {
                TLRPC.MessageEntity e = m.entities.get(i);
                if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && e.url.startsWith(LINK)) {
                    Matcher matcher = LINK_COMMAND.matcher(e.url);
                    if (matcher.find()) return new String[]{matcher.group(1), matcher.group(2)};
                }
                if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && e.url.startsWith(LINK2)) {
                    Matcher matcher = LINK2_COMMAND.matcher(e.url);
                    if (matcher.find()) return new String[]{matcher.group(1) + "2", matcher.group(2)};
                }
            }
        }
        if (!TextUtils.isEmpty(m.message)) {
            Matcher matcher = COMMAND.matcher(m.message);
            if (matcher.find()) return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    // ---------------------------------------------------------------- in-chat buttons

    /** Gives a pending request message its Accept / Decline (or Cancel) buttons, drawn locally. */
    public static void decorate(int account, TLRPC.Message m) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.FRIEND_MAP)) return;
        if (m == null || (m.reply_markup != null && !isOurMarkup(m.reply_markup))) return;
        String[] command = parse(m);
        if (command != null && "zone".equals(command[0])) {
            decorateZone(account, m, command[1]);
            return;
        }
        // v1 carries the key, v2 the initiator's public key: either way the ref is its hash
        if (command == null || !("req".equals(command[0]) || "req2".equals(command[0]))) return;
        byte[] key = unb64(command[1]);
        if (key == null) return;
        String ref = ref(key);
        Friend f = getInstance(account).friends.get(m.peer_id.user_id);
        TLRPC.TL_replyInlineMarkup markup = null;
        if (f != null && ref.equals(f.ref)) {
            if (!m.out && f.state == STATE_INCOMING) {
                markup = markup(button(R.string.UMessageFriendMapAccept, "a", ref, true), button(R.string.UMessageFriendMapDecline, "d", ref, false));
            } else if (m.out && f.state == STATE_OUTGOING) {
                markup = markup(button(R.string.UMessageFriendMapCancelRequest, "c", ref, false));
            }
        }
        m.reply_markup = markup;
    }

    private static void decorateZone(int account, TLRPC.Message m, String arg) {
        Friend f = getInstance(account).friends.get(m.peer_id.user_id);
        TLRPC.TL_replyInlineMarkup markup = null;
        if (f != null && f.zone != null) {
            Zone z = decryptZone(f.key, arg);
            if (z != null && z.zid.equals(f.zone.zid)) {
                if (!m.out && f.zone.state == ZONE_INCOMING) {
                    markup = markup(button(R.string.UMessageFriendMapAccept, "za", z.zid, true), button(R.string.UMessageFriendMapDecline, "zd", z.zid, false));
                } else if (m.out && f.zone.state == ZONE_OUTGOING) {
                    markup = markup(button(R.string.UMessageFriendMapCancelRequest, "zc", z.zid, false));
                }
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

    private static TLRPC.TL_replyInlineMarkup markup(org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton... buttons) {
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow row = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow();
        Collections.addAll(row.buttons, buttons);
        markup.rows.add(row);
        return markup;
    }

    private static org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton button(int textRes, String action, String ref, boolean positive) {
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton b = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton();
        b.text = LocaleController.getString(textRes);
        org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback type = new org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback();
        type.data = (BUTTON + action + ":" + ref).getBytes(StandardCharsets.UTF_8);
        b.type = type;
        b.style = new org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonStyle();
        if (positive) b.style.bg_success = true; else b.style.bg_danger = true;
        return b;
    }

    /** Handles a press on one of our request buttons; returns the action ("a", "d", "c") or null. */
    public static String handleButton(int account, MessageObject messageObject, org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonProto button) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.FRIEND_MAP)) return null;
        if (messageObject == null || !(button instanceof org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton)) return null;
        org.telegram.tgnet.tl.TL_keyboard.InlineButtonType type = ((org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton) button).type;
        if (!(type instanceof org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback)) return null;
        String data = new String(((org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback) type).data, StandardCharsets.UTF_8);
        if (!data.startsWith(BUTTON)) return null;
        String[] parts = data.split(":");
        if (parts.length < 3) return null;
        UMessageFriendLocations c = getInstance(account);
        long uid = messageObject.getDialogId();
        Friend f = c.friends.get(uid);
        if (parts[1].startsWith("z")) {
            if (f != null && f.zone != null && parts[2].equals(f.zone.zid)) {
                if ("za".equals(parts[1])) c.acceptZone(uid); else c.removeZone(uid);
            }
        } else if (f != null && parts[2].equals(f.ref)) {
            if ("a".equals(parts[1])) {
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
        updateTracking();
        for (Listener l : new ArrayList<>(listeners)) {
            l.onFriendsChanged();
        }
    }

    private long myUid() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    // ---------------------------------------------------------------- crypto

    private static String ref(byte[] key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
            return hex(h).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String locId(byte[] key, long uid) {
        try {
            return hex(hmac(key, "loc:" + uid)).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private static String watchId(byte[] key, long watchedUid) {
        try {
            return hex(hmac(key, "watch:" + watchedUid)).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private static String encryptWatch(byte[] key, long until, String aad) {
        try {
            JSONObject o = new JSONObject().put("u", until);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(key, "watch"), "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Until when the friend watches us (ms), capped so a skewed clock cannot keep GPS on for long. */
    private static long decryptWatch(byte[] key, String blob, String aad) {
        try {
            byte[] raw = unb64(blob);
            if (key == null || raw == null || raw.length < 29) return 0;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(key, "watch"), "AES"), new GCMParameterSpec(128, raw, 0, 12));
            if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            long until = new JSONObject(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8)).getLong("u");
            return Math.min(until, System.currentTimeMillis() + WATCH_MS);
        } catch (Exception e) {
            return 0;
        }
    }

    /** When the fix was taken, by the monotonic clock (GPS wall-clock times are off on some phones). */
    private static long fixTime(Location l) {
        long now = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0) {
            long age = (android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
            if (age >= 0) return now - age;
        }
        return l.getTime() > 0 ? Math.min(l.getTime(), now) : now;
    }

    private static String encrypt(byte[] key, Location l, String aad) {
        try {
            JSONObject o = new JSONObject();
            o.put("a", l.getLatitude());
            o.put("o", l.getLongitude());
            o.put("c", (double) l.getAccuracy());
            // the fix's own time, so a re-sent old fix never looks live
            o.put("t", fixTime(l));
            o.put("s", l.hasSpeed() ? l.getSpeed() * 3.6 : -1);
            o.put("m", getMyIdle());
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(key, "enc"), "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String encryptZone(byte[] key, Zone z) {
        try {
            JSONObject o = new JSONObject().put("a", z.lat).put("o", z.lng).put("r", z.radius).put("z", z.zid);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(key, "zone"), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Zone decryptZone(byte[] key, String blob) {
        try {
            byte[] raw = unb64(blob);
            if (key == null || raw == null || raw.length < 29) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(key, "zone"), "AES"), new GCMParameterSpec(128, raw, 0, 12));
            JSONObject o = new JSONObject(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            Zone z = new Zone();
            z.lat = o.getDouble("a");
            z.lng = o.getDouble("o");
            z.radius = Math.max(100, Math.min(100_000, o.getInt("r")));
            z.zid = o.getString("z");
            return z;
        } catch (Exception e) {
            return null;
        }
    }

    private static Loc decrypt(byte[] key, String blob, String aad) {
        try {
            byte[] raw = unb64(blob);
            if (key == null || raw == null || raw.length < 29) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(key, "enc"), "AES"), new GCMParameterSpec(128, raw, 0, 12));
            if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            JSONObject o = new JSONObject(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            Loc loc = new Loc();
            loc.lat = o.getDouble("a");
            loc.lng = o.getDouble("o");
            loc.accuracy = (float) o.optDouble("c", 0);
            loc.time = o.getLong("t");
            loc.speedKmh = (float) o.optDouble("s", -1);
            loc.idle = o.optString("m", "calm");
            return loc;
        } catch (Exception e) {
            return null;
        }
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

    // ---------------------------------------------------------------- network

    private void postItems(java.util.List<String[]> items) {
        uploading = true;
        network.execute(() -> {
            boolean failed = false;
            try {
                // the relay takes at most 100 items per request
                for (int i = 0; i < items.size(); i += 100) {
                    JSONArray arr = new JSONArray();
                    for (String[] item : items.subList(i, Math.min(items.size(), i + 100))) {
                        JSONObject o = new JSONObject();
                        o.put("id", item[0]);
                        o.put("data", item[1] == null ? JSONObject.NULL : item[1]);
                        arr.put(o);
                    }
                    http("POST", URL, new JSONObject().put("items", arr).toString());
                }
            } catch (Exception e) {
                FileLog.e(e);
                failed = true;
            } finally {
                boolean retry = failed;
                AndroidUtilities.runOnUIThread(() -> {
                    uploading = false;
                    if (retry) {
                        // offline: the next fix goes out without waiting for the throttle
                        lastSent = null;
                        lastUpload = 0;
                    }
                    if (pendingForce) {
                        pendingForce = false;
                        uploadNow();
                    }
                });
            }
        });
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try (InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- storage

    private SharedPreferences prefs() {
        return UMessageLocalData.userPrefs(UMessageLocalData.FILE_FRIENDS, account, ownerUid);
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs().getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Friend f = new Friend();
                f.uid = o.getLong("u");
                f.state = o.getInt("s");
                f.v = o.optInt("v", 1);
                String k = o.optString("k", "");
                f.key = TextUtils.isEmpty(k) ? null : unb64(k);
                f.ref = o.has("r") ? o.getString("r") : ref(f.key);
                if (o.has("p")) f.priv = unb64(o.getString("p"));
                if (o.has("pi")) f.pubI = unb64(o.getString("pi"));
                if (o.has("pr")) f.pubR = unb64(o.getString("pr"));
                f.upAt = o.optLong("ua");
                if (f.key == null && f.state == STATE_FRIEND) continue; // nothing to decrypt with
                JSONArray ids = o.optJSONArray("h");
                for (int j = 0; ids != null && j < ids.length(); j++) f.handshakeIds.add(ids.getInt(j));
                JSONObject zo = o.optJSONObject("z");
                if (zo != null) {
                    Zone z = new Zone();
                    z.lat = zo.getDouble("a");
                    z.lng = zo.getDouble("o");
                    z.radius = zo.getInt("r");
                    z.state = zo.getInt("s");
                    z.zid = zo.getString("z");
                    if (zo.has("in")) z.inside = zo.getBoolean("in");
                    JSONArray zm = zo.optJSONArray("m");
                    for (int j = 0; zm != null && j < zm.length(); j++) z.messageIds.add(zm.getInt(j));
                    f.zone = z;
                }
                friends.put(f.uid, f);
            }
            JSONArray dead = new JSONArray(prefs().getString("dead", "[]"));
            for (int i = 0; i < dead.length(); i++) deadRefs.add(dead.getString(i));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Friend f : friends.values()) {
                JSONArray ids = new JSONArray();
                for (int id : f.handshakeIds) ids.put(id);
                JSONObject fo = new JSONObject().put("u", f.uid).put("s", f.state).put("k", f.key != null ? b64(f.key) : "").put("h", ids)
                        .put("v", f.v).put("r", f.ref).put("ua", f.upAt);
                if (f.priv != null) fo.put("p", b64(f.priv));
                if (f.pubI != null) fo.put("pi", b64(f.pubI));
                if (f.pubR != null) fo.put("pr", b64(f.pubR));
                if (f.zone != null) {
                    JSONArray zm = new JSONArray();
                    for (int id : f.zone.messageIds) zm.put(id);
                    JSONObject zo = new JSONObject().put("a", f.zone.lat).put("o", f.zone.lng).put("r", f.zone.radius)
                            .put("s", f.zone.state).put("z", f.zone.zid).put("m", zm);
                    if (f.zone.inside != null) zo.put("in", f.zone.inside.booleanValue());
                    fo.put("z", zo);
                }
                arr.put(fo);
            }
            prefs().edit().putString("friends", arr.toString()).putString("dead", new JSONArray(deadRefs).toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
