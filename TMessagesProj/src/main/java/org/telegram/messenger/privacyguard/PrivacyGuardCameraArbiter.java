package org.telegram.messenger.privacyguard;

import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared front camera strategy. Every other camera user in the app (video messages, the camera screen, calls)
 * calls {@link #yieldCamera()} right before it opens a camera: Privacy Guard closes its camera synchronously,
 * so the other user never finds the camera busy, and stays paused until the camera is free again.
 * Privacy Guard itself only opens the camera when no camera is in use by anyone (see {@link PrivacyGuardCamera}).
 */
public final class PrivacyGuardCameraArbiter {

    private static final long CLOSE_TIMEOUT_MS = 700;

    private static final CopyOnWriteArrayList<PrivacyGuardCamera> active = new CopyOnWriteArrayList<>();
    private static volatile long lastYieldTime;

    private PrivacyGuardCameraArbiter() {
    }

    /** Call before opening any camera. Cheap when Privacy Guard is not running. Safe from any thread. */
    public static void yieldCamera() {
        lastYieldTime = SystemClock.elapsedRealtime();
        for (PrivacyGuardCamera camera : active) {
            camera.closeForOtherUser(CLOSE_TIMEOUT_MS);
        }
    }

    static long getLastYieldTime() {
        return lastYieldTime;
    }

    static void register(PrivacyGuardCamera camera) {
        active.addIfAbsent(camera);
    }

    static void unregister(PrivacyGuardCamera camera) {
        active.remove(camera);
    }
}
