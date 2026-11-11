package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Internetsiz chat" — offline, end-to-end encrypted chat between U message contacts over a phone-to-phone mesh.
 *
 * DESIGN NOTE
 * - Transport (UMessageMeshService): Google Nearby Connections (P2P_CLUSTER, own service id), foreground service
 *   while the user keeps "Internetsiz rejim" on. Endpoint names carry only a random id rotated every 15 minutes.
 *   Up to 4 neighbours; epidemic gossip: on connect each side sends an inventory of packet ids, the other asks for
 *   what it lacks, new packets are announced to all neighbours. Store-and-forward cache: 1000 packets / 20 MB,
 *   24 h expiry, at most 6 hops, per-neighbour rate limits, 280 KB packet cap; frames over 30 KB are chunked.
 * - Wire packet (all a relay sees): version, random 16-byte packet id, hop count, expiry, ciphertext.
 *   No sender/recipient ids. Recipients find their packets by trial decryption with their own HPKE key.
 * - Crypto (UMessageMeshCrypto, Google Tink): per-account long-term HPKE X25519/HKDF-SHA256/AES-256-GCM key pair +
 *   Ed25519 key pair, private keysets encrypted by an Android Keystore master key. The plaintext carries sender uid,
 *   recipient uid, timestamp, message id, sender public keys, body and an Ed25519 signature over
 *   (type ‖ recipient ‖ sender ‖ msg id ‖ timestamp ‖ sender X25519 key ‖ SHA-256(body)). HPKE info binds the outer
 *   packet id and expiry. Delivery receipts are sealed and signed the same way and flood back.
 * - Replay/dedupe: persisted seen-id cache (48 h, 20k), timestamps outside ±24 h rejected, per-sender msg id unique.
 * - Keys (online): bundles self-signed with Ed25519 are published to /api/loc?keys=1 with the U message token, and
 *   contacts' bundles are fetched in batches. Trust on first use; a changed key is held as "pending", its messages
 *   are shown "tasdiqlanmagan" until the user accepts it; a key first learnt over the mesh is unverified until the
 *   directory confirms it or the safety number is compared in person.
 * - Limits: no forward secrecy (long-term keys; a stolen private key decrypts recorded traffic), timing/size and
 *   radio-presence metadata are visible to neighbours, the mesh can be flooded or jammed, Android only.
 * Offline messages are kept in their own database (UMessageMeshStore) and never forwarded to Telegram.
 */
public final class UMessageMesh {

    public static final int MAX_HOPS = 6;
    public static final long TTL_MS = 24L * 3600_000;
    private static final long SKEW_MS = 10 * 60_000;
    public static final int MAX_PACKET = 280 * 1024;
    public static final int MAX_TEXT = 4096;
    public static final int MAX_PHOTO = 150 * 1024;
    public static final int MAX_VOICE = 200 * 1024;
    public static final int MAX_VOICE_SEC = 60;

    private static final byte PACKET_VERSION = 1;
    private static final int HEADER = 1 + 16 + 1 + 8 + 4;
    private static final byte TYPE_MESSAGE = 1, TYPE_RECEIPT = 2;
    private static final byte[] CONTEXT = "ummesh-pkt-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIG_PREFIX = "ummesh-sig-v1".getBytes(StandardCharsets.UTF_8);
    private static final String CHANNEL = "ummesh_messages";
    private static final String KEYS_URL = UMessagePremiumController.BASE_URL + "/api/loc?keys=1";

    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ExecutorService network = Executors.newSingleThreadExecutor();
    private static final SecureRandom random = new SecureRandom();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();
    private static final HashSet<Integer> syncing = new HashSet<>();

    /** Live counters shown on the main screen. */
    public static volatile int neighbours;
    public static volatile boolean scanning;

    private UMessageMesh() {
    }

    /* Settings */

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("ummesh", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
        Context context = ApplicationLoader.applicationContext;
        if (enabled) {
            UMessageMeshService.start(context);
            syncAllKeys(true); // turning the mode on is when the user expects it to "just work": keys right now
        } else {
            UMessageMeshService.stop(context);
            neighbours = 0;
            scanning = false;
        }
        notifyListeners();
    }

    /** Stop scanning when battery saver is on and the battery is low (default on). */
    public static boolean isBatteryGuard() {
        return prefs().getBoolean("battery_guard", true);
    }

    public static void setBatteryGuard(boolean value) {
        prefs().edit().putBoolean("battery_guard", value).apply();
        notifyListeners();
    }

    public static long getRelayedCount() {
        return prefs().getLong("relayed", 0);
    }

    private static void addRelayed() {
        prefs().edit().putLong("relayed", getRelayedCount() + 1).apply();
    }

    /** Called when the app opens: brings the service back if the mode is on (it stops on reboot / kill). */
    public static void onAppResumed() {
        if (isEnabled() && !UMessageMeshService.isRunning() && hasPermissions(ApplicationLoader.applicationContext)) {
            UMessageMeshService.start(ApplicationLoader.applicationContext);
        }
        // every user publishes their key and caches contacts' keys in the background, so offline chat works without
        // anyone having to open it first; delayed so the contact list is loaded, throttled inside syncKeys
        AndroidUtilities.cancelRunOnUIThread(resumeSync);
        AndroidUtilities.runOnUIThread(resumeSync, 8000);
    }

    private static final Runnable resumeSync = () -> syncAllKeys(false);

    private static void syncAllKeys(boolean force) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                syncKeys(a, force);
            }
        }
    }

    public static String[] requiredPermissions() {
        ArrayList<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(android.Manifest.permission.BLUETOOTH_SCAN);
            list.add(android.Manifest.permission.BLUETOOTH_ADVERTISE);
            list.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            list.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(android.Manifest.permission.NEARBY_WIFI_DEVICES);
            list.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    /** Radio permissions only (notifications are optional). */
    public static boolean hasPermissions(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        for (String p : requiredPermissions()) {
            if (android.Manifest.permission.POST_NOTIFICATIONS.equals(p)) {
                continue;
            }
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /* Listeners (UI thread) */

    public static void addListener(Runnable r) {
        if (!listeners.contains(r)) {
            listeners.add(r);
        }
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    static void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable r : new ArrayList<>(listeners)) {
                r.run();
            }
        });
    }

    /* Accounts */

    static long uidOf(int account) {
        return UserConfig.getInstance(account).isClientActivated() ? UserConfig.getInstance(account).getClientUserId() : 0;
    }

    /** Identities of every logged-in account, for trial decryption. */
    private static ArrayList<UMessageMeshCrypto.Identity> identities() {
        ArrayList<UMessageMeshCrypto.Identity> list = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            long uid = uidOf(a);
            if (uid == 0) {
                continue;
            }
            try {
                list.add(UMessageMeshCrypto.identity(uid));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return list;
    }

    private static int accountOf(long uid) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (uidOf(a) == uid) {
                return a;
            }
        }
        return -1;
    }

    public static String getName(int account, long uid) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(uid);
        if (user != null) {
            return UserObject.getUserName(user);
        }
        UMessageMeshStore.PeerKey k = UMessageMeshStore.get().getKey(uidOf(account), uid);
        return k != null && !TextUtils.isEmpty(k.name) ? k.name : "U message";
    }

    /* Packets */

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] context(byte[] id, long expiry) {
        return ByteBuffer.allocate(CONTEXT.length + 16 + 8).put(CONTEXT).put(id).putLong(expiry).array();
    }

    private static byte[] encodePacket(byte[] id, int hops, long expiry, byte[] ct) {
        return ByteBuffer.allocate(HEADER + ct.length).put(PACKET_VERSION).put(id).put((byte) hops).putLong(expiry).putInt(ct.length).put(ct).array();
    }

    /** Header check only (relays cannot read anything else); null when malformed, too big, expired or too far. */
    static UMessageMeshStore.Packet parsePacket(byte[] data, long now) {
        if (data == null || data.length < HEADER + 32 || data.length > MAX_PACKET || data[0] != PACKET_VERSION) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        b.get();
        UMessageMeshStore.Packet p = new UMessageMeshStore.Packet();
        p.id = new byte[16];
        b.get(p.id);
        p.hops = b.get() & 0xff;
        p.expiry = b.getLong();
        int len = b.getInt();
        if (len != data.length - HEADER || p.hops >= MAX_HOPS || p.expiry <= now || p.expiry > now + TTL_MS + SKEW_MS) {
            return null;
        }
        p.data = data;
        return p;
    }

    /**
     * A packet from a neighbour (worker thread). Returns the stored packet when it is new (to announce further),
     * null when it was malformed or already seen.
     */
    static UMessageMeshStore.Packet onPacket(byte[] data) {
        long now = System.currentTimeMillis();
        UMessageMeshStore.Packet p = parsePacket(data, now);
        UMessageMeshStore store = UMessageMeshStore.get();
        if (p == null || store.markSeen(p.id, now)) {
            return null;
        }
        p.hops++;
        p.data = data.clone();
        p.data[17] = (byte) p.hops;
        store.putPacket(p, false, now);
        addRelayed();
        byte[] ct = new byte[data.length - HEADER];
        System.arraycopy(data, HEADER, ct, 0, ct.length);
        byte[] ctx = context(p.id, p.expiry);
        for (UMessageMeshCrypto.Identity identity : identities()) {
            byte[] plain = identity.open(ct, ctx);
            if (plain != null) {
                try {
                    onPlaintext(identity, plain, now);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                break;
            }
        }
        notifyListeners();
        return p.hops < MAX_HOPS ? p : null;
    }

    /** Our own packet was handed to a neighbour: the message is "uzatildi". */
    static void onPacketSent(byte[] id) {
        if (UMessageMeshStore.get().raiseStatusByPacket(id, UMessageMeshStore.STATUS_SENT)) {
            notifyListeners();
        }
    }

    /* Inner message */

    private static final class Inner {
        byte type;
        long sender, recipient, ts;
        byte[] msgId, senderEd, senderX;
        int kind, duration;
        String text;
        byte[] blob;
        byte[] sig;
    }

    private static byte[] bodyHash(int kind, int duration, String text, byte[] blob) {
        byte[] t = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        byte[] m = blob == null ? new byte[0] : blob;
        return UMessageMeshCrypto.sha256(ByteBuffer.allocate(1 + 2 + 4 + 4).put((byte) kind).putShort((short) duration).putInt(t.length).putInt(m.length).array(), t, m);
    }

    private static byte[] signedPart(Inner in) {
        return ByteBuffer.allocate(SIG_PREFIX.length + 1 + 8 + 8 + 16 + 8 + 32 + 32)
                .put(SIG_PREFIX).put(in.type).putLong(in.recipient).putLong(in.sender).put(in.msgId).putLong(in.ts).put(in.senderX)
                .put(bodyHash(in.kind, in.duration, in.text, in.blob)).array();
    }

    private static byte[] encodeInner(Inner in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte('U');
        out.writeByte('M');
        out.writeByte(1);
        out.writeByte(in.type);
        out.writeLong(in.sender);
        out.writeLong(in.recipient);
        out.writeLong(in.ts);
        out.write(in.msgId);
        out.write(in.senderEd);
        out.write(in.senderX);
        out.writeByte(in.kind);
        out.writeShort(in.duration);
        byte[] t = in.text == null ? new byte[0] : in.text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(t.length);
        out.write(t);
        byte[] m = in.blob == null ? new byte[0] : in.blob;
        out.writeInt(m.length);
        out.write(m);
        out.write(in.sig);
        out.flush();
        return bo.toByteArray();
    }

    private static Inner decodeInner(byte[] data) throws Exception {
        DataInputStream inp = new DataInputStream(new ByteArrayInputStream(data));
        if (inp.readByte() != 'U' || inp.readByte() != 'M' || inp.readByte() != 1) {
            return null;
        }
        Inner in = new Inner();
        in.type = inp.readByte();
        in.sender = inp.readLong();
        in.recipient = inp.readLong();
        in.ts = inp.readLong();
        in.msgId = new byte[16];
        inp.readFully(in.msgId);
        in.senderEd = new byte[32];
        inp.readFully(in.senderEd);
        in.senderX = new byte[32];
        inp.readFully(in.senderX);
        in.kind = inp.readByte();
        in.duration = inp.readShort() & 0xffff;
        int tl = inp.readInt();
        if (tl < 0 || tl > MAX_TEXT * 4) {
            return null;
        }
        byte[] t = new byte[tl];
        inp.readFully(t);
        in.text = new String(t, StandardCharsets.UTF_8);
        int ml = inp.readInt();
        if (ml < 0 || ml > MAX_VOICE) {
            return null;
        }
        in.blob = new byte[ml];
        inp.readFully(in.blob);
        in.sig = new byte[64];
        inp.readFully(in.sig);
        return in;
    }

    private static void onPlaintext(UMessageMeshCrypto.Identity me, byte[] plain, long now) throws Exception {
        Inner in = decodeInner(plain);
        if (in == null || in.recipient != me.uid || in.sender == me.uid || Math.abs(now - in.ts) > TTL_MS + SKEW_MS) {
            return;
        }
        if (!UMessageMeshCrypto.verify(in.senderEd, in.sig, signedPart(in))) {
            return;
        }
        UMessageMeshStore store = UMessageMeshStore.get();
        UMessageMeshStore.PeerKey k = store.getKey(me.uid, in.sender);
        if (in.type == TYPE_RECEIPT) {
            // only the pinned (or pending) key of a contact we wrote to may confirm delivery
            boolean known = k != null && (UMessageMeshCrypto.same(k.ed, in.senderEd) || UMessageMeshCrypto.same(k.pendingEd, in.senderEd));
            if (known && in.blob.length == 16 && store.raiseStatusByMsgId(me.uid, in.sender, in.blob, UMessageMeshStore.STATUS_DELIVERED)) {
                notifyListeners();
            }
            return;
        }
        if (in.type != TYPE_MESSAGE || in.kind < UMessageMeshStore.KIND_TEXT || in.kind > UMessageMeshStore.KIND_VOICE) {
            return;
        }
        // receiver-side gate (relays stay blind): blocked senders are dropped without a receipt, strangers wait in requests
        boolean[] contact = new boolean[1];
        int gate = classifySender(me.uid, in.sender, contact);
        if (gate == GATE_DROP) {
            return;
        }
        boolean request = gate == GATE_REQUEST;
        if (request && (store.requestCount(me.uid, in.sender) >= UMessageMeshStore.MAX_REQUESTS_PER_PEER
                || store.requestCount(me.uid, 0) >= UMessageMeshStore.MAX_REQUESTS)) {
            return;
        }
        boolean unverified;
        if (k == null) {
            // a Telegram contact's key comes from the directory: a key heard only over the mesh is never pinned for
            // them (anyone nearby could claim to be them); the message is shown, marked unverified
            if (!contact[0]) {
                k = new UMessageMeshStore.PeerKey();
                k.uid = in.sender;
                k.ed = in.senderEd;
                k.x = in.senderX;
                k.source = UMessageMeshStore.SOURCE_MESH;
                k.updated = now;
                store.putKey(me.uid, k);
            }
            unverified = true;
        } else if (UMessageMeshCrypto.same(k.ed, in.senderEd) && UMessageMeshCrypto.same(k.x, in.senderX)) {
            unverified = !k.trusted();
        } else {
            if (!(UMessageMeshCrypto.same(k.pendingEd, in.senderEd) && UMessageMeshCrypto.same(k.pendingX, in.senderX))) {
                k.pendingEd = in.senderEd;
                k.pendingX = in.senderX;
                k.pendingSource = UMessageMeshStore.SOURCE_MESH;
                k.updated = now;
                store.putKey(me.uid, k);
            }
            unverified = true;
        }
        UMessageMeshStore.Message m = new UMessageMeshStore.Message();
        m.accountUid = me.uid;
        m.peerUid = in.sender;
        m.msgId = in.msgId;
        m.out = false;
        m.kind = in.kind;
        m.text = in.text.length() > MAX_TEXT ? in.text.substring(0, MAX_TEXT) : in.text;
        m.media = in.blob.length > 0 ? in.blob : null;
        m.duration = Math.min(in.duration, MAX_VOICE_SEC);
        m.ts = in.ts;
        m.status = UMessageMeshStore.STATUS_RECEIVED;
        m.unverified = unverified;
        m.senderEd = in.senderEd;
        m.unread = true;
        m.request = request;
        boolean fresh = store.insertMessage(m);
        if (request) {
            // no receipt and no notification: a stranger learns nothing until the request is accepted
            if (fresh) {
                notifyListeners();
            }
            return;
        }
        // the receipt is sent for duplicates too: the first one may have been lost
        sendSealed(me, in.sender, in.senderX, TYPE_RECEIPT, 0, null, in.msgId, 0, randomBytes(16));
        if (fresh) {
            showNotification(me.uid, m);
            notifyListeners();
        }
    }

    private static final int GATE_CHAT = 0, GATE_REQUEST = 1, GATE_DROP = 2;

    /**
     * Who may write to us offline: Telegram contacts and users we already chat with go to chats; anyone blocked in
     * Telegram or in the offline blocklist is dropped; other people go to "So‘rovlar" (or are dropped when the user
     * turned strangers off). Worker thread; Telegram's contact/block state is read on the UI thread.
     */
    private static int classifySender(long me, long sender, boolean[] contactOut) {
        UMessageMeshStore store = UMessageMeshStore.get();
        int state = store.getPeerState(me, sender);
        if (state == UMessageMeshStore.PEER_BLOCKED) {
            return GATE_DROP;
        }
        final int account = accountOf(me);
        if (account < 0) {
            return GATE_DROP;
        }
        final boolean[] tg = new boolean[2]; // blocked, contact
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController mc = MessagesController.getInstance(account);
                tg[0] = mc.blockePeers.indexOfKey(sender) >= 0;
                tg[1] = ContactsController.getInstance(account).contactsDict.containsKey(sender);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                return GATE_DROP; // could not check: fail closed, the sender's packet keeps circulating anyway
            }
        } catch (InterruptedException e) {
            return GATE_DROP;
        }
        if (tg[0]) {
            return GATE_DROP;
        }
        contactOut[0] = tg[1];
        if (tg[1] || state == UMessageMeshStore.PEER_ACCEPTED || store.hasConversation(me, sender)) {
            return GATE_CHAT;
        }
        return isAcceptStrangers() ? GATE_REQUEST : GATE_DROP;
    }

    /** "Notanishlardan xabar qabul qilish": on = strangers land in requests, off = dropped silently. */
    public static boolean isAcceptStrangers() {
        return prefs().getBoolean("accept_strangers", true);
    }

    public static void setAcceptStrangers(boolean value) {
        prefs().edit().putBoolean("accept_strangers", value).apply();
        notifyListeners();
    }

    /** Moves a stranger's messages into chats; later messages from them go straight to the chat. */
    public static void acceptRequest(int account, long uid) {
        final long me = uidOf(account);
        worker.execute(() -> {
            UMessageMeshStore.get().acceptRequests(me, uid);
            notifyListeners();
        });
    }

    /** Blocks a user for offline chat and deletes what they sent. */
    public static void blockPeer(int account, long uid) {
        final long me = uidOf(account);
        worker.execute(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            store.setPeerState(me, uid, UMessageMeshStore.PEER_BLOCKED);
            store.deleteConversation(me, uid);
            notifyListeners();
        });
    }

    public static void unblockPeer(int account, long uid) {
        final long me = uidOf(account);
        worker.execute(() -> {
            UMessageMeshStore.get().setPeerState(me, uid, UMessageMeshStore.PEER_NONE);
            notifyListeners();
        });
    }

    /** Builds, signs, seals and stores one packet; returns its packet id. Worker thread. */
    private static byte[] sendSealed(UMessageMeshCrypto.Identity me, long peer, byte[] peerX, byte type, int kind, String text, byte[] blob, int duration, byte[] msgId) throws Exception {
        long now = System.currentTimeMillis();
        Inner in = new Inner();
        in.type = type;
        in.sender = me.uid;
        in.recipient = peer;
        in.ts = now;
        in.msgId = msgId;
        in.senderEd = me.ed;
        in.senderX = me.x;
        in.kind = kind;
        in.duration = duration;
        in.text = text == null ? "" : text;
        in.blob = blob == null ? new byte[0] : blob;
        in.sig = me.sign(signedPart(in));
        byte[] id = randomBytes(16);
        long expiry = now + TTL_MS;
        byte[] ct = UMessageMeshCrypto.seal(peerX, encodeInner(in), context(id, expiry));
        UMessageMeshStore.Packet p = new UMessageMeshStore.Packet();
        p.id = id;
        p.hops = 0;
        p.expiry = expiry;
        p.data = encodePacket(id, 0, expiry, ct);
        if (p.data.length > MAX_PACKET) {
            throw new IllegalArgumentException("too big");
        }
        UMessageMeshStore store = UMessageMeshStore.get();
        store.markSeen(id, now);
        store.putPacket(p, true, now);
        UMessageMeshService.announce(id);
        return id;
    }

    public interface SendCallback {
        void run(boolean ok);
    }

    /** Sends a text / photo (JPEG ≤150 KB) / voice (AAC ≤200 KB, ≤60 s) to a contact with a known key. */
    public static void send(int account, long peerUid, int kind, String text, byte[] media, int duration, SendCallback callback) {
        final long me = uidOf(account);
        worker.execute(() -> {
            boolean ok = false;
            try {
                if (me == 0 || (text != null && text.length() > MAX_TEXT)
                        || (kind == UMessageMeshStore.KIND_PHOTO && (media == null || media.length > MAX_PHOTO))
                        || (kind == UMessageMeshStore.KIND_VOICE && (media == null || media.length > MAX_VOICE || duration > MAX_VOICE_SEC))) {
                    throw new IllegalArgumentException("bad message");
                }
                UMessageMeshStore store = UMessageMeshStore.get();
                UMessageMeshStore.PeerKey k = store.getKey(me, peerUid);
                if (k == null) {
                    throw new IllegalStateException("no key");
                }
                if (!k.trusted() && !isUntrustedSendConfirmed(me, peerUid)) {
                    // a key nobody vouched for (heard over the mesh, not compared): the user has to say "send anyway"
                    throw new IllegalStateException("untrusted key");
                }
                UMessageMeshCrypto.Identity identity = UMessageMeshCrypto.identity(me);
                byte[] msgId = randomBytes(16);
                UMessageMeshStore.Message m = new UMessageMeshStore.Message();
                m.accountUid = me;
                m.peerUid = peerUid;
                m.msgId = msgId;
                m.out = true;
                m.kind = kind;
                m.text = text;
                m.media = media;
                m.duration = duration;
                m.ts = System.currentTimeMillis();
                m.status = UMessageMeshStore.STATUS_QUEUED;
                m.senderEd = identity.ed;
                m.packetId = sendSealed(identity, peerUid, k.x, TYPE_MESSAGE, kind, text, media, duration, msgId);
                store.insertMessage(m);
                if (store.getPeerState(me, peerUid) != UMessageMeshStore.PEER_ACCEPTED) {
                    store.acceptRequests(me, peerUid); // writing to someone accepts them (and their pending requests)
                }
                ok = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean result = ok;
            notifyListeners();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(result));
            }
        });
    }

    /* Trust */

    /** "Send anyway" to an unverified key, confirmed by the user in this session ("accountUid_peerUid"). */
    private static final java.util.Set<String> untrustedSendConfirmed = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static boolean isUntrustedSendConfirmed(long accountUid, long peerUid) {
        return untrustedSendConfirmed.contains(accountUid + "_" + peerUid);
    }

    public static void confirmUntrustedSend(int account, long peerUid) {
        untrustedSendConfirmed.add(uidOf(account) + "_" + peerUid);
    }

    /**
     * The user accepted a contact's new key: it becomes the pinned one. It keeps its real source, so a key heard
     * only over the mesh stays unverified until compared in person; a directory key counts as trusted.
     */
    public static void acceptPendingKey(int account, long uid) {
        final long me = uidOf(account);
        worker.execute(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            UMessageMeshStore.PeerKey k = store.getKey(me, uid);
            if (k == null || !k.hasPending()) {
                return;
            }
            k.ed = k.pendingEd;
            k.x = k.pendingX;
            k.pendingEd = null;
            k.pendingX = null;
            k.source = k.pendingSource == UMessageMeshStore.SOURCE_DIRECTORY ? UMessageMeshStore.SOURCE_DIRECTORY : UMessageMeshStore.SOURCE_MESH;
            k.pendingSource = 0;
            k.verified = false;
            k.updated = System.currentTimeMillis();
            store.putKey(me, k);
            if (k.trusted()) {
                store.setVerifiedForKey(me, uid, k.ed);
            }
            notifyListeners();
        });
    }

    /** Safety numbers matched in person. */
    public static void markVerified(int account, long uid, boolean verified) {
        final long me = uidOf(account);
        worker.execute(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            UMessageMeshStore.PeerKey k = store.getKey(me, uid);
            if (k == null) {
                return;
            }
            k.verified = verified;
            store.putKey(me, k);
            if (verified) {
                store.setVerifiedForKey(me, uid, k.ed);
            }
            notifyListeners();
        });
    }

    /* Key directory (online) */

    private static final long REPUBLISH_MS = 3L * 24 * 3600_000;
    private static final long REFETCH_MS = 12L * 3600_000;

    /** Publishes our keys and refreshes contacts' keys. Call on the UI thread; silently does nothing offline. */
    public static void syncKeys(int account, boolean force) {
        syncKeys(account, force, null);
    }

    /**
     * Same, reporting what happened (UI thread): our key published or why not, and how many contacts have keys.
     * A silent failure here (no U message token, server error) left both phones with an empty contact picker.
     */
    public static void syncKeys(int account, boolean force, Utilities.Callback<String> onResult) {
        final long me = uidOf(account);
        if (me == 0 || syncing.contains(account)) {
            if (onResult != null) onResult.run(null);
            return;
        }
        final ArrayList<Long> ids = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();
        // while no contact had a key yet, look again after 30 minutes
        final long refetch = prefs().getInt("found_" + me, 0) == 0 ? 30 * 60_000L : REFETCH_MS;
        boolean fetch = force || System.currentTimeMillis() - prefs().getLong("fetched_" + me, 0) > refetch;
        if (fetch) {
            MessagesController mc = MessagesController.getInstance(account);
            if (mc.blockePeers.size() == 0 && !mc.loadingBlockedPeers && !mc.blockedEndReached) {
                mc.getBlockedPeers(false); // Telegram loads its block list lazily; the offline gate reads it
            }
            for (TLRPC.TL_contact c : new ArrayList<>(ContactsController.getInstance(account).contacts)) {
                if (c.user_id == me) {
                    continue;
                }
                TLRPC.User u = mc.getUser(c.user_id);
                ids.add(c.user_id);
                names.add(u == null ? null : UserObject.getUserName(u));
            }
        }
        syncing.add(account);
        network.execute(() -> {
            String published, fetched = null;
            try {
                published = publishOwn(me, force);
            } catch (Throwable e) {
                FileLog.e(e);
                published = LocaleController.getString(R.string.UMessageMeshPublishNetwork);
            }
            if (fetch) {
                try {
                    int found = fetchKeys(me, ids, names);
                    prefs().edit().putLong("fetched_" + me, System.currentTimeMillis()).putInt("found_" + me, found).apply();
                    fetched = LocaleController.formatString(R.string.UMessageMeshFetched, found, ids.size());
                } catch (Throwable e) {
                    FileLog.e(e);
                    fetched = LocaleController.getString(R.string.UMessageMeshPublishNetwork);
                }
            }
            final String result = fetched == null ? published : published + "\n" + fetched;
            AndroidUtilities.runOnUIThread(() -> {
                syncing.remove(account);
                if (onResult != null) onResult.run(result);
            });
            notifyListeners();
        });
    }

    /** Returns a short user-facing status line. */
    private static String publishOwn(long me, boolean force) throws Exception {
        String token = UMessagePremiumController.getInstance().getToken(me);
        if (token == null) {
            return LocaleController.getString(R.string.UMessageMeshPublishNoToken);
        }
        UMessageMeshCrypto.Identity identity = UMessageMeshCrypto.identity(me);
        String x = UMessageMeshCrypto.b64(identity.x), e = UMessageMeshCrypto.b64(identity.ed);
        String fp = x + e;
        SharedPreferences p = prefs();
        if (!force && fp.equals(p.getString("pub_" + me, null)) && System.currentTimeMillis() - p.getLong("pubt_" + me, 0) < REPUBLISH_MS) {
            return LocaleController.getString(R.string.UMessageMeshPublishOk);
        }
        long ts = System.currentTimeMillis();
        JSONObject bundle = new JSONObject().put("v", 1).put("uid", String.valueOf(me)).put("x", x).put("e", e).put("ts", ts)
                .put("sig", UMessageMeshCrypto.b64(identity.sign(UMessageMeshCrypto.bundleMessage(me, x, e, ts))));
        JSONObject body = new JSONObject().put("userId", String.valueOf(me)).put("token", token).put("bundle", bundle);
        JSONObject r = new JSONObject(UMessagePremiumController.httpPost(KEYS_URL, body.toString()));
        if (r.optBoolean("ok", false)) {
            p.edit().putString("pub_" + me, fp).putLong("pubt_" + me, ts).apply();
            return LocaleController.getString(R.string.UMessageMeshPublishOk);
        }
        FileLog.e("mesh key publish: " + r);
        return LocaleController.formatString(R.string.UMessageMeshPublishError, r.optString("error", "?"));
    }

    private static int fetchKeys(long me, ArrayList<Long> ids, ArrayList<String> names) throws Exception {
        int found = 0;
        for (int start = 0; start < ids.size(); start += 100) {
            StringBuilder sb = new StringBuilder();
            int end = Math.min(ids.size(), start + 100);
            for (int i = start; i < end; i++) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(i));
            }
            JSONObject keys = UMessagePremiumController.httpGet(KEYS_URL + "&ids=" + sb).optJSONObject("keys");
            if (keys == null) {
                continue;
            }
            for (int i = start; i < end; i++) {
                long uid = ids.get(i);
                JSONObject b = keys.optJSONObject(String.valueOf(uid));
                if (b == null || b.optInt("v") != 1 || !String.valueOf(uid).equals(b.optString("uid"))) {
                    continue;
                }
                String xs = b.optString("x"), es = b.optString("e");
                byte[] x = UMessageMeshCrypto.unb64(xs), ed = UMessageMeshCrypto.unb64(es), sig = UMessageMeshCrypto.unb64(b.optString("sig"));
                long ts = b.optLong("ts");
                if (x == null || x.length != 32 || ed == null || ed.length != 32
                        || !UMessageMeshCrypto.verify(ed, sig, UMessageMeshCrypto.bundleMessage(uid, xs, es, ts))) {
                    continue;
                }
                applyDirectoryKey(me, uid, ed, x, names.get(i));
                found++;
            }
        }
        return found;
    }

    private static void applyDirectoryKey(long me, long uid, byte[] ed, byte[] x, String name) {
        worker.execute(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            UMessageMeshStore.PeerKey k = store.getKey(me, uid);
            long now = System.currentTimeMillis();
            if (k == null) {
                k = new UMessageMeshStore.PeerKey();
                k.uid = uid;
                k.ed = ed;
                k.x = x;
                k.source = UMessageMeshStore.SOURCE_DIRECTORY;
            } else if (UMessageMeshCrypto.same(k.ed, ed) && UMessageMeshCrypto.same(k.x, x)) {
                if (k.source != UMessageMeshStore.SOURCE_DIRECTORY) {
                    k.source = UMessageMeshStore.SOURCE_DIRECTORY;
                    store.setVerifiedForKey(me, uid, ed);
                }
                if (UMessageMeshCrypto.same(k.pendingEd, ed)) {
                    k.pendingEd = k.pendingX = null;
                    k.pendingSource = 0;
                }
            } else if (k.source == UMessageMeshStore.SOURCE_MESH && !k.verified) {
                // a key only ever heard over the mesh (and never compared) gives way to the directory's key
                k.ed = ed;
                k.x = x;
                k.source = UMessageMeshStore.SOURCE_DIRECTORY;
                k.pendingEd = k.pendingX = null;
                k.pendingSource = 0;
                store.setVerifiedForKey(me, uid, ed);
            } else if (!(UMessageMeshCrypto.same(k.pendingEd, ed) && UMessageMeshCrypto.same(k.pendingX, x))) {
                k.pendingEd = ed;
                k.pendingX = x;
                k.pendingSource = UMessageMeshStore.SOURCE_DIRECTORY;
            }
            if (name != null) {
                k.name = name;
            }
            k.updated = now;
            store.putKey(me, k);
        });
    }

    /* Notifications */

    private static void showNotification(long accountUid, UMessageMeshStore.Message m) {
        Context context = ApplicationLoader.applicationContext;
        int account = accountOf(accountUid);
        if (account < 0) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL, LocaleController.getString(R.string.UMessageMeshTitle), NotificationManager.IMPORTANCE_HIGH));
                }
            }
            String text = m.kind == UMessageMeshStore.KIND_PHOTO ? LocaleController.getString(R.string.UMessageMeshPhoto)
                    : m.kind == UMessageMeshStore.KIND_VOICE ? LocaleController.getString(R.string.UMessageMeshVoice) : m.text;
            Intent open = new Intent(context, LaunchActivity.class);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String[] title = new String[1];
            // the user cache lives on the UI thread
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    title[0] = getName(account, m.peerUid) + (m.unverified ? " (" + LocaleController.getString(R.string.UMessageMeshUnverified) + ")" : "");
                    NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL)
                            .setSmallIcon(R.drawable.notification)
                            .setContentTitle(title[0])
                            .setContentText(text)
                            // the sender's key is not vouched for: say so before anyone trusts the name
                            .setSubText(m.unverified ? LocaleController.getString(R.string.UMessageMeshTitle) + " · " + LocaleController.getString(R.string.UMessageMeshUnverified)
                                    : LocaleController.getString(R.string.UMessageMeshTitle))
                            .setAutoCancel(true)
                            .setContentIntent(pi)
                            .setPriority(NotificationCompat.PRIORITY_HIGH);
                    NotificationManagerCompat.from(context).notify("ummesh", (int) (m.peerUid ^ (m.peerUid >>> 32)), b.build());
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancelNotification(long peerUid) {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel("ummesh", (int) (peerUid ^ (peerUid >>> 32)));
        } catch (Throwable ignore) {
        }
    }
}
