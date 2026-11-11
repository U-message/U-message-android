package org.telegram.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageNearbyCard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Contact exchange between U message users whose phones touch.
 * v2: the BLE beacon and the Nearby Connections endpoint name only carry a random id rotated every
 * 15 minutes. When the beacon says the phones are touching (RSSI_TOUCH), a connection is opened and only a
 * first name is exchanged ("s=h"). The card asks "Share contact?"; a tap sends consent ("s=ok"), and the
 * contact (uid, username, name, bio, import token) is sent only once both sides consented. RSSI alone can be
 * spoofed by a strong transmitter, so it never releases the contact by itself. Moving apart re-arms the peer.
 * v1 (old clients, receive only): beacon with the raw uid, contact in the endpoint name.
 */
public class UMessageNearbyShareManager {
    private static final String SERVICE_ID = "org.telegram.umessage.nearby.contact";
    /** v1 beacon (old clients): service data is the raw user id. Only scanned, never advertised. */
    private static final ParcelUuid BEACON_UUID = new ParcelUuid(UUID.fromString("7c1f3a52-94d1-4b8e-a6c2-5e0d9b1f4a37"));
    /** v2 beacon: service data is the rotating random id. */
    private static final ParcelUuid BEACON_UUID_V2 = new ParcelUuid(UUID.fromString("7c1f3a53-94d1-4b8e-a6c2-5e0d9b1f4a37"));
    private static final long EID_ROTATE_MS = 15 * 60 * 1000L;
    private static final int REQUEST_CODE = 7346;

    /** Smoothed RSSI at or above this means the phones are touching or about to. */
    private static final int RSSI_TOUCH = -60;
    /** Smoothed RSSI below this means they moved apart, so the next touch counts again. */
    private static final int RSSI_APART = -72;
    /** A peer not heard from for this long is treated as gone and re-armed. */
    private static final long LOST_MS = 4000;

    private static ConnectionsClient client;
    private static boolean started;
    private static boolean askedPermissions, askedBluetooth;
    private static View overlayView;
    private static UMessageNearbyCard overlayCard;

    private static BluetoothAdapter btAdapter;
    private static BluetoothLeAdvertiser advertiser;
    private static BluetoothLeScanner scanner;
    private static boolean beaconRunning;
    private static int tokenExpires;
    private static String localToken;

    private static final SecureRandom random = new SecureRandom();
    private static String localEid;
    private static long localEidValue;
    private static long localEidTime;

    /** Peers' contact info ("uid=..;username=..;name=..;bio=.."), by peer key ("u" + uid for v1, "e" + id for v2). */
    private static final HashMap<String, String> endpoints = new HashMap<>();
    private static final HashMap<String, String> endpointKeys = new HashMap<>();
    private static final HashMap<String, String> keyEndpoints = new HashMap<>();
    private static final HashMap<String, Peer> peers = new HashMap<>();
    /** v2 consent handshake, by peer key: first name from the hello, our / their "share" tap, contact sent. */
    private static final HashMap<String, String> helloNames = new HashMap<>();
    private static final java.util.HashSet<String> localConsent = new java.util.HashSet<>();
    private static final java.util.HashSet<String> remoteConsent = new java.util.HashSet<>();
    private static final java.util.HashSet<String> contactSent = new java.util.HashSet<>();
    private static final HashMap<String, String> connectedEndpoints = new HashMap<>();
    /** An undecided connection is dropped after this long. */
    private static final long DECIDE_MS = 30_000;

    private static class Peer {
        float rssi = Float.NaN;
        long lastSeen;
        boolean armed = true;
        boolean connecting;
    }

    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UNearby: " + message);
        }
    }

    /** The random id we advertise; changes every 15 minutes so the phone can't be tracked by it. */
    private static String getLocalEid() {
        long now = SystemClock.elapsedRealtime();
        if (localEid == null || now - localEidTime > EID_ROTATE_MS) {
            long id;
            do {
                id = random.nextLong();
            } while (id == 0);
            localEidValue = id;
            localEid = Long.toHexString(id);
            localEidTime = now;
        }
        return localEid;
    }

    private static long getLocalEidValue() {
        getLocalEid();
        return localEidValue;
    }

    private static final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            localEid = null;
            getLocalEid();
            if (beaconRunning) {
                stopBeacon();
                startBeacon(ApplicationLoader.applicationContext);
            }
            if (started && client != null) {
                client.stopAdvertising();
                advertise();
            }
            AndroidUtilities.runOnUIThread(this, EID_ROTATE_MS);
        }
    };

    private static void scheduleRotation() {
        AndroidUtilities.cancelRunOnUIThread(rotateRunnable);
        long left = EID_ROTATE_MS - (SystemClock.elapsedRealtime() - localEidTime);
        AndroidUtilities.runOnUIThread(rotateRunnable, Math.max(1000, left));
    }

    public static void onActivityResumed(LaunchActivity activity) {
        if (activity == null || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        if (!UMessageConfig.isNearbyShareEnabled()) {
            stop();
            return;
        }
        if (!hasPermissions(activity)) {
            log("missing permissions");
            if (!askedPermissions) {
                askedPermissions = true;
                activity.requestPermissions(requiredPermissions(), REQUEST_CODE);
            }
            return;
        }
        requestBluetooth(activity);
        getLocalEid();
        start(activity);
        startBeacon(activity.getApplicationContext());
        scheduleRotation();
    }

    /** Nothing runs in the background: beacon, advertising and discovery all stop until the next resume. */
    public static void onActivityPaused() {
        AndroidUtilities.cancelRunOnUIThread(rotateRunnable);
        stopBeacon();
        stopNearby();
        hideOverlay(false);
    }

    /** Turned on: the permissions are asked once, then the beacon runs whenever the app is open; no pairing. */
    public static boolean onRequestPermissionsResult(LaunchActivity activity, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        boolean granted = grantResults != null && grantResults.length > 0;
        if (granted) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }
        if (granted) {
            onActivityResumed(activity);
        } else {
            UMessageConfig.setNearbyShareEnabled(false);
            stop();
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                BulletinFactory.of(last).createErrorBulletin(LocaleController.getString(R.string.UMessageNearbySharePermission)).show();
            }
        }
        return true;
    }

    /** The beacon needs Bluetooth on: asked once after the feature is turned on. */
    @SuppressLint("MissingPermission")
    private static void requestBluetooth(LaunchActivity activity) {
        if (askedBluetooth) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || adapter.isEnabled()) {
            return;
        }
        askedBluetooth = true;
        try {
            activity.startActivity(new android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void setEnabled(LaunchActivity activity, boolean enabled) {
        UMessageConfig.setNearbyShareEnabled(enabled);
        if (enabled) {
            askedPermissions = false;
            askedBluetooth = false;
            onActivityResumed(activity);
        } else {
            stop();
        }
    }

    private static void stop() {
        AndroidUtilities.cancelRunOnUIThread(rotateRunnable);
        stopBeacon();
        stopNearby();
        tokenExpires = 0;
        localToken = null;
        hideOverlay();
    }

    private static void stopNearby() {
        if (!started || client == null) {
            return;
        }
        started = false;
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        endpoints.clear();
        endpointKeys.clear();
        keyEndpoints.clear();
        peers.clear();
        helloNames.clear();
        localConsent.clear();
        remoteConsent.clear();
        contactSent.clear();
        connectedEndpoints.clear();
    }

    private static void start(LaunchActivity activity) {
        if (started) {
            return;
        }
        started = true;
        client = Nearby.getConnectionsClient(activity.getApplicationContext());
        advertise();
        if (localToken == null || tokenExpires == 0 || System.currentTimeMillis() / 1000 > tokenExpires - 60) {
            refreshContactToken();
        }
        client.startDiscovery(SERVICE_ID, endpointCallback, new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> FileLog.e(e));
    }

    /** A contact token lets peers without a shared chat or username still open ours; it is only sent after the handshake. */
    private static void refreshContactToken() {
        tokenExpires = 0;
        MessagesController.getInstance(UserConfig.selectedAccount).requestContactToken(token -> {
            if (token == null || TextUtils.isEmpty(token.url)) {
                return;
            }
            tokenExpires = token.expires;
            localToken = token.url.substring(token.url.lastIndexOf('/') + 1);
        });
    }

    /** The endpoint name only carries the protocol version and our rotating id. */
    private static String makeEndpointName() {
        return "v=2;e=" + getLocalEid();
    }

    private static void advertise() {
        client.startAdvertising(makeEndpointName(), SERVICE_ID, lifecycleCallback, new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> FileLog.e(e));
    }

    /* Proximity beacon */

    @SuppressLint("MissingPermission")
    private static void startBeacon(Context context) {
        if (beaconRunning) {
            return;
        }
        long self = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        BluetoothManager manager = context == null ? null : (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (self == 0 || adapter == null || !adapter.isEnabled()) {
            log("beacon skipped, bluetooth off or no user");
            return;
        }
        btAdapter = adapter;
        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();
        if (advertiser == null || scanner == null) {
            log("beacon unsupported adv=" + advertiser + " scan=" + scanner);
            return;
        }
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(BEACON_UUID_V2, ByteBuffer.allocate(8).putLong(getLocalEidValue()).array())
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);

            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            // filtered by our service data: some phones (this MTK one too) drop or throttle unfiltered scans
            ArrayList<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder().setServiceData(BEACON_UUID_V2, new byte[0]).build());
            filters.add(new ScanFilter.Builder().setServiceData(BEACON_UUID, new byte[0]).build());
            scanner.startScan(filters, scanSettings, scanCallback);
            beaconRunning = true;
            log("beacon started");
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @SuppressLint("MissingPermission")
    private static void stopBeacon() {
        if (!beaconRunning) {
            return;
        }
        beaconRunning = false;
        if (btAdapter == null || !btAdapter.isEnabled()) {
            // bluetooth turned off: the system already dropped scan/advertise, stop calls would throw
            return;
        }
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            log("advertise failed " + errorCode);
        }
    };

    private static final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() == null) {
                return;
            }
            String key;
            byte[] data = result.getScanRecord().getServiceData(BEACON_UUID_V2);
            if (data != null && data.length >= 8) {
                long id = ByteBuffer.wrap(data).getLong();
                if (id == 0) {
                    return;
                }
                key = "e" + Long.toHexString(id);
            } else {
                // v1 beacon of an old client: the raw user id
                data = result.getScanRecord().getServiceData(BEACON_UUID);
                if (data == null || data.length < 8) {
                    return;
                }
                long uid = ByteBuffer.wrap(data).getLong();
                if (uid == 0) {
                    return;
                }
                key = "u" + uid;
            }
            int rssi = result.getRssi();
            AndroidUtilities.runOnUIThread(() -> onBeacon(key, rssi));
        }

        @Override
        public void onScanFailed(int errorCode) {
            beaconRunning = false;
            log("scan failed " + errorCode);
        }
    };

    private static void onBeacon(String key, int rssi) {
        if (key.equals("e" + localEid) || key.equals("u" + UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId())) {
            return;
        }
        Peer peer = peers.get(key);
        if (peer == null) {
            peers.put(key, peer = new Peer());
        }
        long now = SystemClock.elapsedRealtime();
        if (Float.isNaN(peer.rssi) || now - peer.lastSeen > LOST_MS) {
            peer.rssi = rssi;
            peer.armed = true;
        } else {
            peer.rssi = peer.rssi * 0.6f + rssi * 0.4f;
        }
        peer.lastSeen = now;
        log("beacon rssi=" + rssi + " avg=" + (int) peer.rssi + " armed=" + peer.armed + " known=" + endpoints.containsKey(key));

        if (peer.rssi < RSSI_APART) {
            peer.armed = true;
        } else {
            maybeShowCard(key, peer);
        }
    }

    private static void maybeShowCard(String key, Peer peer) {
        if (!peer.armed || peer.rssi < RSSI_TOUCH || overlayView != null) {
            return;
        }
        String info = endpoints.get(key);
        long uid = 0;
        if (info == null && key.startsWith("u")) {
            // v1: the Nearby Connections endpoint can come seconds after the beacon: a user we already know is enough
            uid = parseLong(key.substring(1));
            TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(uid);
            if (user != null) {
                info = "uid=" + uid + ";username=" + safe(user.username) + ";name=" + safe(user.first_name) + ";bio=";
            }
        } else if (info == null) {
            String hello = helloNames.get(key);
            if (hello == null) {
                // v2: a first name only comes after a handshake with the phone we touch
                requestContact(key, peer);
                return;
            }
            LaunchActivity activity = LaunchActivity.instance;
            if (activity != null) {
                peer.armed = false;
                log("touch -> ask to share");
                showNearbyOverlay(activity, hello, "", "", () -> onLocalConsent(key), () -> onLocalDecline(key));
            }
            return;
        }
        LaunchActivity activity = LaunchActivity.instance;
        if (info != null && activity != null) {
            peer.armed = false;
            log("touch -> show card");
            String username = parseField(info, "username");
            String token = parseField(info, "t");
            long peerUid = parseLong(parseField(info, "uid"));
            final boolean v2 = key.startsWith("e");
            showNearbyOverlay(activity, parseField(info, "name"), username, parseField(info, "bio"), () -> {
                if (v2) {
                    // an older v2 phone sent its contact right away: ours still goes only after this tap
                    localConsent.add(key);
                    trySendContact(key);
                }
                openChat(username, token, peerUid);
            }, () -> onLocalDecline(key));
        }
    }

    /** Our user tapped "Share contact" for a v2 peer: tell it, and send the contact once it agreed too. */
    private static void onLocalConsent(String key) {
        localConsent.add(key);
        hideOverlay();
        String endpointId = connectedEndpoints.get(key);
        if (endpointId != null && client != null && started) {
            client.sendPayload(endpointId, Payload.fromBytes("v=2;s=ok".getBytes(StandardCharsets.UTF_8)))
                    .addOnFailureListener(e -> FileLog.e(e));
        }
        trySendContact(key);
    }

    private static void onLocalDecline(String key) {
        hideOverlay();
        localConsent.remove(key);
        String endpointId = connectedEndpoints.remove(key);
        if (endpointId != null && client != null && started) {
            client.disconnectFromEndpoint(endpointId);
        }
    }

    /** Sends our contact only after both users tapped "Share contact" (or the peer is an older v2 app that sent its own). */
    private static void trySendContact(String key) {
        if (!localConsent.contains(key) || contactSent.contains(key)) {
            return;
        }
        if (!remoteConsent.contains(key) && !endpoints.containsKey(key)) {
            return;
        }
        String endpointId = connectedEndpoints.get(key);
        if (endpointId == null || client == null || !started || !isPeerClose(key)) {
            return;
        }
        contactSent.add(key);
        client.sendPayload(endpointId, Payload.fromBytes(makeLocalPayload().getBytes(StandardCharsets.UTF_8)))
                .addOnFailureListener(e -> FileLog.e(e));
        // give our payload time to arrive before closing the connection
        AndroidUtilities.runOnUIThread(() -> {
            if (client != null && started && endpointId.equals(connectedEndpoints.get(key)) && endpoints.containsKey(key)) {
                connectedEndpoints.remove(key);
                client.disconnectFromEndpoint(endpointId);
            }
        }, 3000);
    }

    private static void requestContact(String key, Peer peer) {
        String endpointId = keyEndpoints.get(key);
        if (peer.connecting || endpointId == null || !started || client == null) {
            return;
        }
        peer.connecting = true;
        client.requestConnection(makeEndpointName(), endpointId, lifecycleCallback)
                .addOnFailureListener(e -> {
                    // may fail only because the peer connected to us at the same moment: its payload still arrives
                    log("request connection failed " + e);
                    peer.connecting = false;
                });
    }

    /** The beacon says the phones are touching right now: the only state that opens a connection. */
    private static boolean isPeerTouching(String key) {
        Peer peer = key == null ? null : peers.get(key);
        return peer != null && !Float.isNaN(peer.rssi) && peer.rssi >= RSSI_TOUCH
                && SystemClock.elapsedRealtime() - peer.lastSeen < LOST_MS;
    }

    /** Still near (not moved apart) — enough to finish an exchange both users already confirmed. */
    private static boolean isPeerClose(String key) {
        Peer peer = key == null ? null : peers.get(key);
        return peer != null && !Float.isNaN(peer.rssi) && peer.rssi >= RSSI_APART
                && SystemClock.elapsedRealtime() - peer.lastSeen < LOST_MS;
    }

    private static String keyFromEndpointName(String name) {
        if ("2".equals(parseField(name, "v"))) {
            String eid = parseField(name, "e");
            return TextUtils.isEmpty(eid) ? null : "e" + eid;
        }
        long uid = parseLong(parseField(name, "uid"));
        return uid == 0 ? null : "u" + uid;
    }

    /* Nearby Connections */

    private static boolean hasPermissions(LaunchActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static String[] requiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return permissions.toArray(new String[0]);
    }

    private static final EndpointDiscoveryCallback endpointCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            String name = info.getEndpointName();
            String key = keyFromEndpointName(name);
            log("endpoint found " + endpointId + " v2=" + (key != null && key.startsWith("e")));
            if (key == null) {
                return;
            }
            endpointKeys.put(endpointId, key);
            keyEndpoints.put(key, endpointId);
            if (key.startsWith("u")) {
                // v1 client: the contact is in the endpoint name
                endpoints.put(key, name);
            }
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            String key = endpointKeys.remove(endpointId);
            if (key != null) {
                if (endpointId.equals(keyEndpoints.get(key))) {
                    keyEndpoints.remove(key);
                }
                if (key.startsWith("u")) {
                    endpoints.remove(key);
                }
            }
        }
    };

    /** v2 handshake: accepted only from a peer whose beacon we hear close by, then both sides send their contact. */
    private static final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            if (client == null || !started) {
                return;
            }
            String key = keyFromEndpointName(info.getEndpointName());
            if (key != null && key.startsWith("e") && isPeerTouching(key)) {
                endpointKeys.put(endpointId, key);
                client.acceptConnection(endpointId, payloadCallback).addOnFailureListener(e -> FileLog.e(e));
            } else {
                client.rejectConnection(endpointId);
            }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (client == null || !started) {
                return;
            }
            String key = endpointKeys.get(endpointId);
            if (!result.getStatus().isSuccess()) {
                Peer peer = key == null ? null : peers.get(key);
                if (peer != null) {
                    peer.connecting = false;
                }
                return;
            }
            if (key == null || !isPeerTouching(key)) {
                client.disconnectFromEndpoint(endpointId);
                return;
            }
            connectedEndpoints.put(key, endpointId);
            contactSent.remove(key);
            remoteConsent.remove(key);
            localConsent.remove(key);
            // only a first name until both users confirm on their screens
            client.sendPayload(endpointId, Payload.fromBytes(makeHelloPayload().getBytes(StandardCharsets.UTF_8)))
                    .addOnFailureListener(e -> FileLog.e(e));
            AndroidUtilities.runOnUIThread(() -> {
                if (client != null && started && endpointId.equals(connectedEndpoints.get(key)) && !contactSent.contains(key)) {
                    connectedEndpoints.remove(key);
                    client.disconnectFromEndpoint(endpointId);
                }
            }, DECIDE_MS);
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            String key = endpointKeys.get(endpointId);
            if (key != null && endpointId.equals(connectedEndpoints.get(key))) {
                connectedEndpoints.remove(key);
            }
            Peer peer = key == null ? null : peers.get(key);
            if (peer != null) {
                peer.connecting = false;
            }
        }
    };

    private static final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            String key = endpointKeys.get(endpointId);
            byte[] bytes = payload.getType() == Payload.Type.BYTES ? payload.asBytes() : null;
            if (key == null || bytes == null || bytes.length > 4096) {
                return;
            }
            String info = new String(bytes, StandardCharsets.UTF_8);
            String stage = parseField(info, "s");
            Peer peer = peers.get(key);
            if ("h".equals(stage)) {
                String name = parseField(info, "name");
                helloNames.put(key, TextUtils.isEmpty(name) ? "U message" : cut(name, 24));
                if (peer != null) {
                    peer.connecting = false;
                    if (SystemClock.elapsedRealtime() - peer.lastSeen < LOST_MS) {
                        maybeShowCard(key, peer);
                    }
                }
                return;
            }
            if ("ok".equals(stage)) {
                remoteConsent.add(key);
                trySendContact(key);
                return;
            }
            if (parseLong(parseField(info, "uid")) == 0) {
                return;
            }
            endpoints.put(key, info);
            log("contact received");
            if (localConsent.contains(key)) {
                // both confirmed: our contact went (or goes now), open theirs
                trySendContact(key);
                AndroidUtilities.runOnUIThread(() -> {
                    if (client != null && started) {
                        connectedEndpoints.remove(key);
                        client.disconnectFromEndpoint(endpointId);
                    }
                }, 3000);
                openChat(parseField(info, "username"), parseField(info, "t"), parseLong(parseField(info, "uid")));
                return;
            }
            // an older v2 phone sends its contact at once: still ask our user before sending ours
            if (peer != null) {
                peer.connecting = false;
                peer.armed = true;
                if (SystemClock.elapsedRealtime() - peer.lastSeen < LOST_MS) {
                    maybeShowCard(key, peer);
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
        }
    };

    private static final int MAX_BIO = 140;

    /** Before both users confirm: a first name only (no id, username, bio or token). */
    private static String makeHelloPayload() {
        TLRPC.User self = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        String name = self == null || TextUtils.isEmpty(self.first_name) ? "U message" : self.first_name;
        return "v=2;s=h;name=" + safe(cut(name, 24));
    }

    private static String makeLocalPayload() {
        int account = UserConfig.selectedAccount;
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        String name = self == null || TextUtils.isEmpty(self.first_name) ? "U message" : self.first_name;
        String username = self == null || TextUtils.isEmpty(self.username) ? "" : self.username;
        TLRPC.UserFull userFull = self == null ? null : MessagesController.getInstance(account).getUserFull(self.id);
        String bio = userFull == null || TextUtils.isEmpty(userFull.about) ? "" : safe(cut(userFull.about, MAX_BIO));
        long uid = self == null ? 0 : self.id;
        return "uid=" + uid + ";username=" + safe(username) + ";t=" + safe(localToken) + ";name=" + safe(cut(name, 24)) + ";bio=" + bio;
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static void openChat(String username, String token, long uid) {
        hideOverlay();
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (!TextUtils.isEmpty(username)) {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                MessagesController.getInstance(account).openByUserName(username, last, 1);
                return;
            }
        }
        if (!TextUtils.isEmpty(token)) {
            TLRPC.TL_contacts_importContactToken req = new TLRPC.TL_contacts_importContactToken();
            req.token = token;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.User) {
                    MessagesController.getInstance(account).putUser((TLRPC.User) response, false);
                    activity.presentFragment(ChatActivity.of(((TLRPC.User) response).id));
                } else {
                    log("import contact token failed " + (error == null ? null : error.text));
                    openKnown(activity, account, uid);
                }
            }));
            return;
        }
        openKnown(activity, account, uid);
    }

    private static void openKnown(LaunchActivity activity, int account, long uid) {
        if (uid > 0 && MessagesController.getInstance(account).getUser(uid) != null) {
            activity.presentFragment(ChatActivity.of(uid));
        } else {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                BulletinFactory.of(last).createErrorBulletin(LocaleController.getString(R.string.NoUsernameFound)).show();
            }
        }
    }

    /* Card */

    private static void showNearbyOverlay(LaunchActivity activity, String peerName, String username, String bio, Runnable share, Runnable cancel) {
        hideOverlay();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0xcc000000);

        UMessageNearbyCard card = new UMessageNearbyCard(activity, peerName, username, bio, share, cancel);
        overlay.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 18, 0, 18, 28));

        overlay.setAlpha(0f);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        card.setTranslationY(AndroidUtilities.dp(420));
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(750).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        card.play(true);
        AndroidUtilities.vibrateCursor(overlay);
        overlayView = overlay;
        overlayCard = card;
    }

    private static void hideOverlay() {
        hideOverlay(true);
    }

    /** Not animated when the activity pauses: the static references must not keep the old window alive. */
    private static void hideOverlay(boolean animated) {
        if (overlayView == null) {
            return;
        }
        View view = overlayView;
        UMessageNearbyCard card = overlayCard;
        overlayView = null;
        overlayCard = null;
        if (!animated) {
            if (card != null) {
                card.animate().cancel();
            }
            view.animate().cancel();
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            return;
        }
        if (card != null) {
            card.animate().translationY(AndroidUtilities.dp(420)).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_IN).start();
        }
        view.animate().alpha(0f).setDuration(420).withEndAction(() -> {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }).start();
    }

    private static String parseField(String data, String key) {
        if (data == null) {
            return "";
        }
        String prefix = key + "=";
        String[] parts = data.split(";");
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(";", " ").replace("=", " ");
    }
}
