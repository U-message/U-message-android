package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.telegram.ui.LaunchActivity;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Mesh transport of "Internetsiz chat" (see UMessageMesh for the design): a connectedDevice foreground service that
 * advertises and discovers over Nearby Connections, keeps up to 4 neighbours and gossips packets with them.
 * Frames: INV (packet ids we have), WANT (ids we lack), PKT (one packet), CHUNK (part of a frame over 30 KB).
 * Everything about neighbours runs on the main thread; database and crypto work on UMessageMesh.worker.
 */
public class UMessageMeshService extends Service {

    private static final String SERVICE_ID = "org.telegram.umessage.mesh.v1";
    private static final String ACTION_STOP = "org.telegram.messenger.UMMESH_STOP";
    private static final int NOTIFICATION_ID = 0x75f3;

    private static final int MAX_NEIGHBOURS = 4;
    private static final int CHUNK = 30 * 1024;
    private static final int MAX_FRAME = UMessageMesh.MAX_PACKET + 64;
    private static final int MAX_INV = 1200;
    private static final int MAX_WANT = 300;
    private static final long EID_ROTATE_MS = 15 * 60_000L;
    private static final long TICK_MS = 15_000;
    private static final long FULL_INV_MS = 120_000;
    private static final long IDLE_MS = 90_000;
    private static final long RATE_WINDOW_MS = 10_000;
    private static final int RATE_FRAMES = 80;
    private static final long RATE_BYTES = 1536 * 1024;

    private static final byte F_INV = 2, F_WANT = 3, F_PKT = 4, F_CHUNK = 0x10;

    private static UMessageMeshService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectionsClient client;
    private boolean started, advertising, discovering;
    private String eid;
    private long eidTime;
    private long lastTrim;
    private int chunkSeq;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static final class Reassembly {
        byte[][] parts;
        int got, size;
        long started;
    }

    private static final class Neighbour {
        String endpointId, eid;
        boolean connected;
        long connectedAt, lastUseful, lastInv;
        long windowStart;
        int frames, strikes;
        long bytes;
        final HashMap<Integer, Reassembly> chunks = new HashMap<>();
    }

    private final HashMap<String, Neighbour> neighbours = new HashMap<>();
    private final HashMap<String, String> discovered = new HashMap<>();
    private final HashMap<String, Long> cooldown = new HashMap<>();

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, UMessageMeshService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, UMessageMeshService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** A new packet in the store (ours or relayed): tell every neighbour. Any thread. */
    static void announce(byte[] id) {
        AndroidUtilities.runOnUIThread(() -> {
            UMessageMeshService s = instance;
            if (s == null) {
                return;
            }
            byte[] frame = invFrame(F_INV, singleList(id));
            for (Neighbour n : s.neighbours.values()) {
                if (n.connected) {
                    s.sendFrame(n.endpointId, frame);
                }
            }
        });
    }

    private static ArrayList<byte[]> singleList(byte[] id) {
        ArrayList<byte[]> l = new ArrayList<>(1);
        l.add(id);
        return l;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            UMessageMesh.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!UMessageMesh.isEnabled() || !UMessageMesh.hasPermissions(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable e) {
            // e.g. a restart from the background on Android 12+; the app brings it back when opened
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            instance = this;
            client = Nearby.getConnectionsClient(this);
            newEid();
            UMessageMesh.worker.execute(() -> {
                // warm up the Keystore-backed identities so the first packet is not slow
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    long uid = UMessageMesh.uidOf(a);
                    if (uid != 0) {
                        try {
                            UMessageMeshCrypto.identity(uid);
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                }
            });
            registerNetwork();
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        started = false;
        handler.removeCallbacks(tick);
        if (client != null) {
            try {
                client.stopAdvertising();
                client.stopDiscovery();
                // only our endpoints: stopAllEndpoints() would also cut "Yaqin kontakt almashish"
                for (String id : new ArrayList<>(neighbours.keySet())) {
                    client.disconnectFromEndpoint(id);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        neighbours.clear();
        discovered.clear();
        advertising = discovering = false;
        if (networkCallback != null && Build.VERSION.SDK_INT >= 24) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignore) {
            }
            networkCallback = null;
        }
        if (instance == this) {
            instance = null;
        }
        UMessageMesh.neighbours = 0;
        UMessageMesh.scanning = false;
        UMessageMesh.notifyListeners();
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, LaunchActivity.class);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, UMessageMeshService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationsController.checkOtherNotificationsChannel();
        return new NotificationCompat.Builder(this, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.UMessageMeshMode))
                .setContentText(LocaleController.formatString(R.string.UMessageMeshNotification, UMessageMesh.neighbours))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, LocaleController.getString(R.string.UMessageMeshTurnOff), stopIntent)
                .build();
    }

    private void updateState() {
        int count = 0;
        for (Neighbour n : neighbours.values()) {
            if (n.connected) {
                count++;
            }
        }
        if (count != UMessageMesh.neighbours || UMessageMesh.scanning != discovering) {
            UMessageMesh.neighbours = count;
            UMessageMesh.scanning = discovering;
            try {
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
            } catch (Throwable e) {
                FileLog.e(e);
            }
            UMessageMesh.notifyListeners();
        }
    }

    private void registerNetwork() {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    AndroidUtilities.runOnUIThread(UMessageMeshService::syncAll, 3000);
                }
            };
            ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void syncAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UMessageMesh.uidOf(a) != 0) {
                UMessageMesh.syncKeys(a, false);
            }
        }
    }

    /* Radio */

    /** Our endpoint name is "m1" + a random 64-bit id, new every 15 minutes; nothing else about us is on the air. */
    private void newEid() {
        eid = "m1" + UMessageMeshStore.hex(UMessageMesh.randomBytes(8));
        eidTime = SystemClock.elapsedRealtime();
    }

    private static boolean validName(String name) {
        return name != null && name.length() == 18 && name.startsWith("m1") && name.substring(2).matches("[0-9a-f]{16}");
    }

    private boolean batteryLow() {
        if (!UMessageMesh.isBatteryGuard()) {
            return false;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            return pm.isPowerSaveMode() && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) <= 20;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void startRadio() {
        if (client == null || !started) {
            return;
        }
        if (!advertising) {
            advertising = true;
            client.startAdvertising(eid, SERVICE_ID, lifecycle, new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> {
                        FileLog.e(e);
                        advertising = false; // retried on the next tick (also when "Yaqin kontakt almashish" holds the radio)
                    });
        }
        if (!discovering) {
            discovering = true;
            client.startDiscovery(SERVICE_ID, discovery, new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> {
                        FileLog.e(e);
                        discovering = false;
                        updateState();
                    });
        }
        updateState();
    }

    private void stopRadio() {
        if (client == null) {
            return;
        }
        if (advertising) {
            client.stopAdvertising();
            advertising = false;
        }
        if (discovering) {
            client.stopDiscovery();
            discovering = false;
        }
        discovered.clear();
        updateState();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (batteryLow()) {
                stopRadio(); // existing connections keep gossiping, no more scanning
            } else {
                if (now - eidTime > EID_ROTATE_MS) {
                    newEid();
                    if (advertising) {
                        client.stopAdvertising();
                        advertising = false;
                    }
                }
                startRadio();
            }
            for (Iterator<Map.Entry<String, Long>> it = cooldown.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue() < now) {
                    it.remove();
                }
            }
            boolean waiting = hasCandidates();
            for (Neighbour n : new ArrayList<>(neighbours.values())) {
                for (Iterator<Reassembly> it = n.chunks.values().iterator(); it.hasNext(); ) {
                    if (now - it.next().started > 60_000) {
                        it.remove();
                    }
                }
                if (!n.connected) {
                    if (now - n.connectedAt > 60_000) {
                        neighbours.remove(n.endpointId); // a request that never resolved
                    }
                    continue;
                }
                if (waiting && now - n.connectedAt > IDLE_MS && now - n.lastUseful > IDLE_MS) {
                    // rotate neighbours so packets reach more of the mesh
                    cooldown.put(n.eid, now + 5 * 60_000);
                    client.disconnectFromEndpoint(n.endpointId);
                    neighbours.remove(n.endpointId);
                } else if (now - n.lastInv > FULL_INV_MS) {
                    sendFullInventory(n);
                }
            }
            connectCandidates();
            if (now - lastTrim > 10 * 60_000) {
                lastTrim = now;
                UMessageMesh.worker.execute(() -> UMessageMeshStore.get().trim(System.currentTimeMillis()));
            }
            updateState();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private boolean hasCandidates() {
        for (Map.Entry<String, String> e : discovered.entrySet()) {
            if (!neighbours.containsKey(e.getKey()) && !cooldown.containsKey(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Only the side with the smaller id asks, so two phones don't request each other at the same moment. */
    private void connectCandidates() {
        if (client == null || !started) {
            return;
        }
        for (Map.Entry<String, String> e : new ArrayList<>(discovered.entrySet())) {
            if (neighbours.size() >= MAX_NEIGHBOURS) {
                return;
            }
            String endpointId = e.getKey(), peerEid = e.getValue();
            if (neighbours.containsKey(endpointId) || cooldown.containsKey(peerEid) || eid.compareTo(peerEid) >= 0) {
                continue;
            }
            Neighbour n = new Neighbour();
            n.endpointId = endpointId;
            n.eid = peerEid;
            n.connectedAt = SystemClock.elapsedRealtime();
            neighbours.put(endpointId, n);
            client.requestConnection(eid, endpointId, lifecycle).addOnFailureListener(ex -> {
                neighbours.remove(endpointId);
                cooldown.put(peerEid, SystemClock.elapsedRealtime() + 60_000);
            });
        }
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            if (!SERVICE_ID.equals(info.getServiceId()) || !validName(info.getEndpointName()) || info.getEndpointName().equals(eid)) {
                return;
            }
            if (discovered.size() < 64) {
                discovered.put(endpointId, info.getEndpointName());
            }
            connectCandidates();
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            discovered.remove(endpointId);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            if (client == null || !started) {
                return;
            }
            String name = info.getEndpointName();
            Neighbour n = neighbours.get(endpointId);
            if (n == null) {
                if (!validName(name) || neighbours.size() >= MAX_NEIGHBOURS || cooldown.containsKey(name)) {
                    client.rejectConnection(endpointId);
                    return;
                }
                n = new Neighbour();
                n.endpointId = endpointId;
                n.eid = name;
                n.connectedAt = SystemClock.elapsedRealtime();
                neighbours.put(endpointId, n);
            }
            // no authentication here on purpose: anyone may relay, only recipients can read (end-to-end)
            client.acceptConnection(endpointId, payloads).addOnFailureListener(e -> neighbours.remove(endpointId));
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            Neighbour n = neighbours.get(endpointId);
            if (n == null) {
                return;
            }
            if (!result.getStatus().isSuccess()) {
                neighbours.remove(endpointId);
                cooldown.put(n.eid, SystemClock.elapsedRealtime() + 60_000);
                updateState();
                return;
            }
            n.connected = true;
            n.connectedAt = n.lastUseful = SystemClock.elapsedRealtime();
            sendFullInventory(n);
            updateState();
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            neighbours.remove(endpointId);
            updateState();
        }
    };

    /* Frames */

    private static byte[] invFrame(byte type, ArrayList<byte[]> ids) {
        ByteBuffer b = ByteBuffer.allocate(3 + ids.size() * 16).put(type).putShort((short) ids.size());
        for (byte[] id : ids) {
            b.put(id);
        }
        return b.array();
    }

    private static ArrayList<byte[]> parseIds(byte[] frame, int max) {
        if (frame.length < 3) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(frame);
        b.get();
        int count = b.getShort() & 0xffff;
        if (count > max || frame.length != 3 + count * 16) {
            return null;
        }
        ArrayList<byte[]> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[16];
            b.get(id);
            ids.add(id);
        }
        return ids;
    }

    private void sendFullInventory(Neighbour n) {
        n.lastInv = SystemClock.elapsedRealtime();
        final String endpointId = n.endpointId;
        UMessageMesh.worker.execute(() -> {
            ArrayList<byte[]> ids = UMessageMeshStore.get().packetIds(System.currentTimeMillis(), MAX_INV);
            if (ids.isEmpty()) {
                return;
            }
            byte[] frame = invFrame(F_INV, ids);
            AndroidUtilities.runOnUIThread(() -> sendFrame(endpointId, frame));
        });
    }

    private void sendFrame(String endpointId, byte[] frame) {
        Neighbour n = neighbours.get(endpointId);
        if (client == null || n == null || !n.connected) {
            return;
        }
        if (frame.length <= CHUNK) {
            client.sendPayload(endpointId, Payload.fromBytes(frame)).addOnFailureListener(FileLog::e);
            return;
        }
        int total = (frame.length + CHUNK - 1) / CHUNK;
        int xfer = ++chunkSeq;
        for (int i = 0; i < total; i++) {
            int off = i * CHUNK, len = Math.min(CHUNK, frame.length - off);
            byte[] part = ByteBuffer.allocate(7 + len).put(F_CHUNK).putInt(xfer).put((byte) i).put((byte) total).put(frame, off, len).array();
            client.sendPayload(endpointId, Payload.fromBytes(part)).addOnFailureListener(FileLog::e);
        }
    }

    /** Per-neighbour budget; a neighbour that keeps flooding is dropped and ignored for 10 minutes. */
    private boolean allow(Neighbour n, int size) {
        long now = SystemClock.elapsedRealtime();
        if (now - n.windowStart > RATE_WINDOW_MS) {
            if (n.frames > RATE_FRAMES || n.bytes > RATE_BYTES) {
                n.strikes++;
            } else if (n.strikes > 0) {
                n.strikes--;
            }
            n.windowStart = now;
            n.frames = 0;
            n.bytes = 0;
        }
        n.frames++;
        n.bytes += size;
        if (n.strikes >= 3) {
            cooldown.put(n.eid, now + 10 * 60_000);
            client.disconnectFromEndpoint(n.endpointId);
            neighbours.remove(n.endpointId);
            updateState();
            return false;
        }
        return n.frames <= RATE_FRAMES && n.bytes <= RATE_BYTES;
    }

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            Neighbour n = neighbours.get(endpointId);
            byte[] bytes = payload.getType() == Payload.Type.BYTES ? payload.asBytes() : null;
            if (n == null || !n.connected || bytes == null || bytes.length == 0 || bytes.length > CHUNK + 16 || !allow(n, bytes.length)) {
                return;
            }
            handleFrame(n, bytes);
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
        }
    };

    private void handleFrame(Neighbour n, byte[] frame) {
        final String endpointId = n.endpointId;
        switch (frame[0]) {
            case F_CHUNK: {
                if (frame.length < 8) {
                    return;
                }
                ByteBuffer b = ByteBuffer.wrap(frame);
                b.get();
                int xfer = b.getInt();
                int idx = b.get() & 0xff, total = b.get() & 0xff;
                if (total < 2 || total * CHUNK > MAX_FRAME + CHUNK || idx >= total) {
                    return;
                }
                Reassembly r = n.chunks.get(xfer);
                if (r == null) {
                    if (n.chunks.size() >= 4) {
                        return;
                    }
                    r = new Reassembly();
                    r.parts = new byte[total][];
                    r.started = SystemClock.elapsedRealtime();
                    n.chunks.put(xfer, r);
                }
                if (r.parts.length != total || r.parts[idx] != null) {
                    return;
                }
                byte[] part = new byte[frame.length - 7];
                System.arraycopy(frame, 7, part, 0, part.length);
                r.parts[idx] = part;
                r.got++;
                r.size += part.length;
                if (r.size > MAX_FRAME) {
                    n.chunks.remove(xfer);
                    return;
                }
                if (r.got == total) {
                    n.chunks.remove(xfer);
                    ByteBuffer full = ByteBuffer.allocate(r.size);
                    for (byte[] p : r.parts) {
                        full.put(p);
                    }
                    byte[] whole = full.array();
                    if (whole.length > 0 && whole[0] != F_CHUNK) {
                        handleFrame(n, whole);
                    }
                }
                return;
            }
            case F_INV: {
                ArrayList<byte[]> ids = parseIds(frame, MAX_INV);
                if (ids == null) {
                    return;
                }
                UMessageMesh.worker.execute(() -> {
                    UMessageMeshStore store = UMessageMeshStore.get();
                    ArrayList<byte[]> want = new ArrayList<>();
                    for (byte[] id : ids) {
                        if (!store.isSeen(id) && !store.hasPacket(id)) {
                            want.add(id);
                            if (want.size() >= MAX_WANT) {
                                break;
                            }
                        }
                    }
                    if (!want.isEmpty()) {
                        byte[] out = invFrame(F_WANT, want);
                        AndroidUtilities.runOnUIThread(() -> sendFrame(endpointId, out));
                    }
                });
                return;
            }
            case F_WANT: {
                ArrayList<byte[]> ids = parseIds(frame, MAX_WANT);
                if (ids == null) {
                    return;
                }
                UMessageMesh.worker.execute(() -> {
                    UMessageMeshStore store = UMessageMeshStore.get();
                    long now = System.currentTimeMillis();
                    for (byte[] id : ids) {
                        UMessageMeshStore.Packet p = store.getPacket(id);
                        if (p == null || p.expiry <= now || p.hops >= UMessageMesh.MAX_HOPS) {
                            continue;
                        }
                        byte[] out = ByteBuffer.allocate(1 + p.data.length).put(F_PKT).put(p.data).array();
                        AndroidUtilities.runOnUIThread(() -> sendFrame(endpointId, out));
                        UMessageMesh.onPacketSent(id);
                    }
                });
                return;
            }
            case F_PKT: {
                if (frame.length > UMessageMesh.MAX_PACKET + 1) {
                    return;
                }
                byte[] data = new byte[frame.length - 1];
                System.arraycopy(frame, 1, data, 0, data.length);
                UMessageMesh.worker.execute(() -> {
                    UMessageMeshStore.Packet p = UMessageMesh.onPacket(data);
                    if (p == null) {
                        return;
                    }
                    byte[] inv = invFrame(F_INV, singleList(p.id));
                    AndroidUtilities.runOnUIThread(() -> {
                        Neighbour from = neighbours.get(endpointId);
                        if (from != null) {
                            from.lastUseful = SystemClock.elapsedRealtime();
                        }
                        for (Neighbour other : neighbours.values()) {
                            if (other.connected && !other.endpointId.equals(endpointId)) {
                                sendFrame(other.endpointId, inv);
                            }
                        }
                    });
                });
                return;
            }
            default:
        }
    }
}
