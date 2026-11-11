package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;

/**
 * Local storage of the offline chat, separate from Telegram's database:
 * packets (store-and-forward cache, ciphertext only), seen packet ids (replay/loop guard),
 * conversations (decrypted messages, per account) and pinned contact keys (TOFU).
 * All methods are synchronous; UMessageMesh calls them on its own worker thread.
 */
public final class UMessageMeshStore extends SQLiteOpenHelper {

    public static final int MAX_PACKETS = 1000;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long SEEN_KEEP_MS = 48L * 3600_000;
    private static final int MAX_SEEN = 20000;

    public static final int STATUS_QUEUED = 0, STATUS_SENT = 1, STATUS_DELIVERED = 2, STATUS_RECEIVED = 3;
    public static final int KIND_TEXT = 0, KIND_PHOTO = 1, KIND_VOICE = 2;
    public static final int SOURCE_DIRECTORY = 1, SOURCE_MESH = 2;
    public static final int PEER_NONE = 0, PEER_ACCEPTED = 1, PEER_BLOCKED = 2;
    public static final int MAX_REQUESTS_PER_PEER = 20, MAX_REQUESTS = 200;

    private static UMessageMeshStore instance;

    public static synchronized UMessageMeshStore get() {
        if (instance == null) {
            instance = new UMessageMeshStore(ApplicationLoader.applicationContext);
        }
        return instance;
    }

    private UMessageMeshStore(Context context) {
        super(context, "ummesh.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE packets(id BLOB PRIMARY KEY, hops INTEGER, expiry INTEGER, received INTEGER, size INTEGER, own INTEGER, data BLOB)");
        db.execSQL("CREATE INDEX packets_received ON packets(received)");
        db.execSQL("CREATE TABLE seen(id BLOB PRIMARY KEY, ts INTEGER)");
        db.execSQL("CREATE INDEX seen_ts ON seen(ts)");
        db.execSQL("CREATE TABLE messages(_id INTEGER PRIMARY KEY AUTOINCREMENT, account_uid INTEGER, peer_uid INTEGER, msg_id BLOB, out INTEGER, kind INTEGER, text TEXT, media BLOB, duration INTEGER, ts INTEGER, status INTEGER, unverified INTEGER, sender_ed BLOB, packet_id BLOB, unread INTEGER, UNIQUE(account_uid, peer_uid, msg_id, out))");
        db.execSQL("CREATE INDEX messages_peer ON messages(account_uid, peer_uid, ts)");
        db.execSQL("CREATE TABLE keys(uid INTEGER PRIMARY KEY, ed BLOB, x BLOB, source INTEGER, verified INTEGER, pending_ed BLOB, pending_x BLOB, name TEXT, updated INTEGER)");
        createV2(db);
        createV3(db);
    }

    /**
     * v3: pinned keys per local Telegram account (keys3), so one account's trust decisions never apply to another,
     * and the real source of a pending key. Legacy rows are copied to every account that already used the mesh.
     */
    private static void createV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE keys3(account_uid INTEGER, uid INTEGER, ed BLOB, x BLOB, source INTEGER, verified INTEGER, pending_ed BLOB, pending_x BLOB, pending_source INTEGER DEFAULT 0, name TEXT, updated INTEGER, PRIMARY KEY(account_uid, uid))");
        db.execSQL("INSERT OR IGNORE INTO keys3(account_uid, uid, ed, x, source, verified, pending_ed, pending_x, pending_source, name, updated) "
                + "SELECT a.account_uid, k.uid, k.ed, k.x, k.source, k.verified, k.pending_ed, k.pending_x, " + SOURCE_MESH + ", k.name, k.updated FROM keys k, "
                + "(SELECT account_uid FROM messages UNION SELECT account_uid FROM peers) a WHERE a.account_uid != 0");
        db.execSQL("DROP TABLE keys");
    }

    /** v2: message requests from strangers (messages.request) and per-account peer state (accepted / blocked). */
    private static void createV2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN request INTEGER DEFAULT 0");
        db.execSQL("CREATE TABLE peers(account_uid INTEGER, uid INTEGER, state INTEGER, PRIMARY KEY(account_uid, uid))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createV2(db);
        }
        if (oldVersion < 3) {
            createV3(db);
        }
    }

    /* Packets */

    public static final class Packet {
        public byte[] id;
        public int hops;
        public long expiry;
        public byte[] data;
    }

    public synchronized boolean hasPacket(byte[] id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM packets WHERE id = x'" + hex(id) + "'", null)) {
            return c.moveToFirst();
        }
    }

    /** True when the id was already seen (packet known, relayed or decrypted before); otherwise records it. */
    public synchronized boolean markSeen(byte[] id, long now) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("ts", now);
        return db.insertWithOnConflict("seen", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1;
    }

    public synchronized boolean isSeen(byte[] id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM seen WHERE id = x'" + hex(id) + "'", null)) {
            return c.moveToFirst();
        }
    }

    public synchronized void putPacket(Packet p, boolean own, long now) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("id", p.id);
        v.put("hops", p.hops);
        v.put("expiry", p.expiry);
        v.put("received", now);
        v.put("size", p.data.length);
        v.put("own", own ? 1 : 0);
        v.put("data", p.data);
        db.insertWithOnConflict("packets", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        trim(now);
    }

    public synchronized Packet getPacket(byte[] id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT hops, expiry, data FROM packets WHERE id = x'" + hex(id) + "'", null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            Packet p = new Packet();
            p.id = id;
            p.hops = c.getInt(0);
            p.expiry = c.getLong(1);
            p.data = c.getBlob(2);
            return p;
        }
    }

    /** Ids of every live packet, newest first. */
    public synchronized ArrayList<byte[]> packetIds(long now, int limit) {
        ArrayList<byte[]> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM packets WHERE expiry > " + now + " ORDER BY received DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                ids.add(c.getBlob(0));
            }
        }
        return ids;
    }

    public synchronized int packetCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM packets", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Expired packets go first, then the oldest relayed ones, our own last; old seen ids are forgotten. */
    public synchronized void trim(long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("packets", "expiry <= " + now, null);
        db.delete("seen", "ts < " + (now - SEEN_KEEP_MS), null);
        for (int guard = 0; guard < 50; guard++) {
            long count, bytes;
            try (Cursor c = db.rawQuery("SELECT COUNT(*), IFNULL(SUM(size), 0) FROM packets", null)) {
                c.moveToFirst();
                count = c.getLong(0);
                bytes = c.getLong(1);
            }
            if (count <= MAX_PACKETS && bytes <= MAX_BYTES) {
                break;
            }
            db.execSQL("DELETE FROM packets WHERE id IN (SELECT id FROM packets ORDER BY own ASC, received ASC LIMIT 20)");
        }
        db.execSQL("DELETE FROM seen WHERE id IN (SELECT id FROM seen ORDER BY ts DESC LIMIT -1 OFFSET " + MAX_SEEN + ")");
    }

    /* Messages */

    public static final class Message {
        public long rowId;
        public long accountUid, peerUid;
        public byte[] msgId;
        public boolean out;
        public int kind;
        public String text;
        public byte[] media;
        public int duration;
        public long ts;
        public int status;
        public boolean unverified;
        public byte[] senderEd;
        public byte[] packetId;
        public boolean unread;
        public boolean request;
    }

    /** False for a duplicate (same sender, same message id). */
    public synchronized boolean insertMessage(Message m) {
        ContentValues v = new ContentValues();
        v.put("account_uid", m.accountUid);
        v.put("peer_uid", m.peerUid);
        v.put("msg_id", m.msgId);
        v.put("out", m.out ? 1 : 0);
        v.put("kind", m.kind);
        v.put("text", m.text);
        v.put("media", m.media);
        v.put("duration", m.duration);
        v.put("ts", m.ts);
        v.put("status", m.status);
        v.put("unverified", m.unverified ? 1 : 0);
        v.put("sender_ed", m.senderEd);
        v.put("packet_id", m.packetId);
        v.put("unread", m.unread ? 1 : 0);
        v.put("request", m.request ? 1 : 0);
        long id = getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        m.rowId = id;
        return id != -1;
    }

    private static final String MSG_COLS = "_id, account_uid, peer_uid, msg_id, out, kind, text, media, duration, ts, status, unverified, sender_ed, packet_id, unread, request";

    private static Message readMessage(Cursor c, boolean withMedia) {
        Message m = new Message();
        m.rowId = c.getLong(0);
        m.accountUid = c.getLong(1);
        m.peerUid = c.getLong(2);
        m.msgId = c.getBlob(3);
        m.out = c.getInt(4) != 0;
        m.kind = c.getInt(5);
        m.text = c.getString(6);
        m.media = withMedia ? c.getBlob(7) : null;
        m.duration = c.getInt(8);
        m.ts = c.getLong(9);
        m.status = c.getInt(10);
        m.unverified = c.getInt(11) != 0;
        m.senderEd = c.getBlob(12);
        m.packetId = c.getBlob(13);
        m.unread = c.getInt(14) != 0;
        m.request = c.getInt(15) != 0;
        return m;
    }

    public synchronized ArrayList<Message> getMessages(long accountUid, long peerUid, int limit) {
        ArrayList<Message> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + MSG_COLS + " FROM (SELECT * FROM messages WHERE account_uid = " + accountUid + " AND peer_uid = " + peerUid + " ORDER BY ts DESC, _id DESC LIMIT " + limit + ") ORDER BY ts ASC, _id ASC", null)) {
            while (c.moveToNext()) {
                list.add(readMessage(c, true));
            }
        }
        return list;
    }

    /** The newest message of every conversation of an account, newest conversation first (no media). */
    public synchronized ArrayList<Message> getConversations(long accountUid) {
        return getConversations(accountUid, false);
    }

    /** request = true: one row per stranger whose messages wait in "So‘rovlar". */
    public synchronized ArrayList<Message> getConversations(long accountUid, boolean request) {
        ArrayList<Message> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + MSG_COLS + " FROM messages m WHERE account_uid = " + accountUid
                + " AND request = " + (request ? 1 : 0) + " AND _id = (SELECT _id FROM messages WHERE account_uid = m.account_uid AND peer_uid = m.peer_uid ORDER BY ts DESC, _id DESC LIMIT 1) ORDER BY ts DESC", null)) {
            while (c.moveToNext()) {
                list.add(readMessage(c, false));
            }
        }
        return list;
    }

    public synchronized int unreadCount(long accountUid, long peerUid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE account_uid = " + accountUid + " AND peer_uid = " + peerUid + " AND unread = 1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void markRead(long accountUid, long peerUid) {
        getWritableDatabase().execSQL("UPDATE messages SET unread = 0 WHERE account_uid = " + accountUid + " AND peer_uid = " + peerUid);
    }

    public synchronized int requestCount(long accountUid, long peerUid) {
        String where = "account_uid = " + accountUid + " AND request = 1" + (peerUid != 0 ? " AND peer_uid = " + peerUid : "");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE " + where, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** We already talk with this user outside "So‘rovlar" (a chat we started or accepted). */
    public synchronized boolean hasConversation(long accountUid, long peerUid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM messages WHERE account_uid = " + accountUid + " AND peer_uid = " + peerUid + " AND request = 0 LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    public synchronized int getPeerState(long accountUid, long uid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT state FROM peers WHERE account_uid = " + accountUid + " AND uid = " + uid, null)) {
            return c.moveToFirst() ? c.getInt(0) : PEER_NONE;
        }
    }

    public synchronized void setPeerState(long accountUid, long uid, int state) {
        ContentValues v = new ContentValues();
        v.put("account_uid", accountUid);
        v.put("uid", uid);
        v.put("state", state);
        getWritableDatabase().insertWithOnConflict("peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Accepting a request moves its messages into the normal chats. */
    public synchronized void acceptRequests(long accountUid, long peerUid) {
        getWritableDatabase().execSQL("UPDATE messages SET request = 0 WHERE account_uid = " + accountUid + " AND peer_uid = " + peerUid);
        setPeerState(accountUid, peerUid, PEER_ACCEPTED);
    }

    public synchronized void deleteConversation(long accountUid, long peerUid) {
        getWritableDatabase().delete("messages", "account_uid = " + accountUid + " AND peer_uid = " + peerUid, null);
    }

    /** Raises the status of our outgoing message (never lowers it); returns true when it changed. */
    public synchronized boolean raiseStatusByMsgId(long accountUid, long peerUid, byte[] msgId, int status) {
        return getWritableDatabase().compileStatement("UPDATE messages SET status = " + status + " WHERE out = 1 AND status < " + status
                + " AND account_uid = " + accountUid + " AND peer_uid = " + peerUid + " AND msg_id = x'" + hex(msgId) + "'").executeUpdateDelete() > 0;
    }

    public synchronized boolean raiseStatusByPacket(byte[] packetId, int status) {
        return getWritableDatabase().compileStatement("UPDATE messages SET status = " + status + " WHERE out = 1 AND status < " + status
                + " AND packet_id = x'" + hex(packetId) + "'").executeUpdateDelete() > 0;
    }

    public synchronized void setVerifiedForKey(long accountUid, long peerUid, byte[] ed) {
        getWritableDatabase().execSQL("UPDATE messages SET unverified = 0 WHERE out = 0 AND account_uid = " + accountUid + " AND peer_uid = " + peerUid + " AND sender_ed = x'" + hex(ed) + "'");
    }

    /* Keys */

    public static final class PeerKey {
        public long uid;
        public byte[] ed, x;
        public int source;
        public boolean verified;
        public byte[] pendingEd, pendingX;
        /** Where the pending key came from (SOURCE_DIRECTORY or SOURCE_MESH); accepting it keeps this source. */
        public int pendingSource;
        public String name;
        public long updated;

        public boolean hasPending() {
            return pendingEd != null && pendingX != null;
        }

        /** Trusted: first key from the directory (TOFU) or compared in person. */
        public boolean trusted() {
            return source == SOURCE_DIRECTORY || verified;
        }
    }

    private static final String KEY_COLUMNS = "uid, ed, x, source, verified, pending_ed, pending_x, name, updated, pending_source";

    public synchronized PeerKey getKey(long accountUid, long uid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + KEY_COLUMNS + " FROM keys3 WHERE account_uid = " + accountUid + " AND uid = " + uid, null)) {
            return c.moveToFirst() ? readKey(c) : null;
        }
    }

    public synchronized ArrayList<PeerKey> getKeys(long accountUid) {
        ArrayList<PeerKey> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + KEY_COLUMNS + " FROM keys3 WHERE account_uid = " + accountUid, null)) {
            while (c.moveToNext()) {
                list.add(readKey(c));
            }
        }
        return list;
    }

    private static PeerKey readKey(Cursor c) {
        PeerKey k = new PeerKey();
        k.uid = c.getLong(0);
        k.ed = c.getBlob(1);
        k.x = c.getBlob(2);
        k.source = c.getInt(3);
        k.verified = c.getInt(4) != 0;
        k.pendingEd = c.getBlob(5);
        k.pendingX = c.getBlob(6);
        k.name = c.getString(7);
        k.updated = c.getLong(8);
        k.pendingSource = c.getInt(9);
        return k;
    }

    public synchronized void putKey(long accountUid, PeerKey k) {
        ContentValues v = new ContentValues();
        v.put("account_uid", accountUid);
        v.put("uid", k.uid);
        v.put("ed", k.ed);
        v.put("x", k.x);
        v.put("source", k.source);
        v.put("verified", k.verified ? 1 : 0);
        v.put("pending_ed", k.pendingEd);
        v.put("pending_x", k.pendingX);
        v.put("pending_source", k.pendingSource);
        v.put("name", k.name);
        v.put("updated", k.updated);
        getWritableDatabase().insertWithOnConflict("keys3", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
