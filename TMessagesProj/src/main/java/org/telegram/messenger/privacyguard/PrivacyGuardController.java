package org.telegram.messenger.privacyguard;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Decides when Privacy Guard may use the camera: only while a chat screen is visible in the foreground,
 * the feature is enabled and enrolled, the camera permission is granted and the screen is on.
 * Everything else (other screens, background, screen off, disabled) releases the camera immediately.
 * UI thread only.
 */
public final class PrivacyGuardController {

    public interface Host {
        void onPrivacyGuardStatus(PrivacyGuardStatus status);
    }

    private static volatile PrivacyGuardController instance;

    public static PrivacyGuardController getInstance() {
        PrivacyGuardController local = instance;
        if (local == null) {
            synchronized (PrivacyGuardController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PrivacyGuardController();
                }
            }
        }
        return local;
    }

    private Host host;
    private PrivacyGuardEngine engine;
    private PrivacyGuardStatus status = PrivacyGuardStatus.DISABLED;
    private boolean screenOn = true;
    private boolean enrollmentActive;
    private boolean appShieldActive;
    private BroadcastReceiver screenReceiver;
    // App-wide guard: the camera runs only for a short check after the app opens or the page changes,
    // and keeps running while the cover is up so the owner's face can still open it.
    private static final long WAKE_MS = 4000;
    private boolean sleeping;
    private boolean holdAwake;
    private final Runnable sleepRunnable = this::trySleep;

    private PrivacyGuardController() {
    }

    /** A chat became visible. */
    public void attach(Host host) {
        this.host = host;
        registerScreenReceiver();
        sleeping = false;
        scheduleSleep();
        update();
        host.onPrivacyGuardStatus(status);
    }

    /** The chat was paused, hidden or destroyed. */
    public void detach(Host host) {
        if (this.host != host) {
            return;
        }
        this.host = null;
        AndroidUtilities.cancelRunOnUIThread(sleepRunnable);
        sleeping = false;
        unregisterScreenReceiver();
        update();
    }

    public void onSettingsChanged() {
        AndroidUtilities.runOnUIThread(this::update);
    }

    /** The user chose to show the chat for the people currently in view. */
    public void dismiss() {
        if (engine != null) {
            engine.dismiss();
        }
    }

    public void setEnrollmentActive(boolean active) {
        enrollmentActive = active;
        update();
    }

    /** The app-wide shield covers every screen, so per-chat shields stand down while it is present. */
    public void setAppShieldActive(boolean active) {
        appShieldActive = active;
    }

    /** The page changed: let the camera take one more look, if it is not already running. */
    public void wake() {
        if (host == null || !appShieldActive) {
            return;
        }
        scheduleSleep();
        if (sleeping) {
            sleeping = false;
            update();
        }
    }

    /** The cover is up: keep the camera on until it is lifted. */
    public void setHoldAwake(boolean hold) {
        if (holdAwake == hold) {
            return;
        }
        holdAwake = hold;
        if (!hold) {
            scheduleSleep();
        } else if (sleeping) {
            sleeping = false;
            update();
        }
    }

    private void scheduleSleep() {
        AndroidUtilities.cancelRunOnUIThread(sleepRunnable);
        AndroidUtilities.runOnUIThread(sleepRunnable, WAKE_MS);
    }

    private void trySleep() {
        if (host == null || !appShieldActive || sleeping) {
            return;
        }
        if (holdAwake || status.isProtected() || status.state != PrivacyGuardStateMachine.State.SAFE_OWNER_ONLY && engine != null) {
            // no clear "owner only" verdict yet: look a little longer
            if (!holdAwake) {
                scheduleSleep();
            }
            return;
        }
        sleeping = true;
        update();
    }

    public boolean isAppShieldActive() {
        return appShieldActive;
    }

    public PrivacyGuardStatus getStatus() {
        return status;
    }

    public static boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 || ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void update() {
        final boolean shouldRun = host != null && screenOn && !enrollmentActive && !sleeping
                && PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner() && hasCameraPermission();
        if (shouldRun) {
            if (engine == null) {
                final float[][] templates = OwnerFaceStore.load();
                if (templates == null) {
                    setStatus(PrivacyGuardStatus.DISABLED);
                    return;
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("PrivacyGuard: START");
                }
                final PrivacyGuardEngine[] self = new PrivacyGuardEngine[1];
                final PrivacyGuardEngine created = self[0] = new PrivacyGuardEngine(ApplicationLoader.applicationContext, templates, PrivacyGuardSettings.snapshot(), s -> {
                    // late results of an engine that was already stopped are ignored
                    if (engine == self[0]) {
                        setStatus(s);
                    }
                });
                engine = created;
                setStatus(new PrivacyGuardStatus(PrivacyGuardStateMachine.State.PAUSED, PrivacyGuardStateMachine.REASON_NONE));
                created.start();
            } else {
                engine.setConfig(PrivacyGuardSettings.snapshot());
            }
        } else if (engine != null) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("PrivacyGuard: STOP");
            }
            engine.stop();
            engine = null;
            OwnerFaceStore.clearCache();
            // asleep between checks: keep the last verdict so the cover does not change
            if (!sleeping) {
                setStatus(PrivacyGuardStatus.DISABLED);
            }
        } else {
            setStatus(PrivacyGuardStatus.DISABLED);
        }
    }

    private void setStatus(PrivacyGuardStatus newStatus) {
        if (newStatus.equals(status)) {
            return;
        }
        status = newStatus;
        if (host != null) {
            host.onPrivacyGuardStatus(newStatus);
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                update();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }
        } catch (Exception e) {
            screenReceiver = null;
        }
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver == null) {
            return;
        }
        try {
            ApplicationLoader.applicationContext.unregisterReceiver(screenReceiver);
        } catch (Exception ignore) {
        }
        screenReceiver = null;
    }
}
