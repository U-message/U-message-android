package org.telegram.messenger.privacyguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

/** U message Privacy Guard user settings. Biometric data is kept apart in {@link OwnerFaceStore}. */
public final class PrivacyGuardSettings {

    public static final int MODE_LOOKING = 0;
    public static final int MODE_ANY_FACE = 1;

    public static final int ACTION_BLUR = 0;
    public static final int ACTION_LOCK = 1;

    public static final int SENSITIVITY_LOW = 0;
    public static final int SENSITIVITY_BALANCED = 1;
    public static final int SENSITIVITY_HIGH = 2;

    private static final String PREFS = "umessage_privacy_guard";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PASSCODE_HASH = "passcode_hash";
    private static final String KEY_PASSCODE_SALT = "passcode_salt";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ACTION = "action";
    private static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_OWNER_AWAY = "hide_owner_away";
    public static final String KEY_PROTECT_MEDIA = "protect_media";
    public static final String KEY_PROTECT_COMPOSER = "protect_composer";
    public static final String KEY_PROTECT_SCREENSHOTS = "protect_screenshots";

    private PrivacyGuardSettings() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Needs Keystore AES (API 23) and the camera2 / RenderNode behavior this feature relies on (API 24). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean isEnabled() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.PRIVACY_GUARD)) return false;
        return isSupported() && prefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        PrivacyGuardController.getInstance().onSettingsChanged();
    }

    public static int getMode() {
        return prefs().getInt(KEY_MODE, MODE_LOOKING);
    }

    public static void setMode(int mode) {
        prefs().edit().putInt(KEY_MODE, mode).apply();
        PrivacyGuardController.getInstance().onSettingsChanged();
    }

    public static int getAction() {
        return prefs().getInt(KEY_ACTION, ACTION_BLUR);
    }

    public static void setAction(int action) {
        prefs().edit().putInt(KEY_ACTION, action).apply();
        PrivacyGuardController.getInstance().onSettingsChanged();
    }

    public static int getSensitivity() {
        return prefs().getInt(KEY_SENSITIVITY, SENSITIVITY_BALANCED);
    }

    public static void setSensitivity(int sensitivity) {
        prefs().edit().putInt(KEY_SENSITIVITY, sensitivity).apply();
        PrivacyGuardController.getInstance().onSettingsChanged();
    }

    public static boolean get(String key) {
        return prefs().getBoolean(key, getDefault(key));
    }

    public static void set(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        PrivacyGuardController.getInstance().onSettingsChanged();
    }

    private static boolean getDefault(String key) {
        return KEY_PROTECT_MEDIA.equals(key) || KEY_PROTECT_COMPOSER.equals(key) || KEY_OWNER_AWAY.equals(key);
    }

    /* Guard's own passcode — separate from the phone / app passcode, set under the face enrollment. */

    public static boolean hasGuardPasscode() {
        return !TextUtils.isEmpty(prefs().getString(KEY_PASSCODE_HASH, ""));
    }

    public static void setGuardPasscode(String passcode) {
        if (TextUtils.isEmpty(passcode)) {
            prefs().edit().remove(KEY_PASSCODE_HASH).remove(KEY_PASSCODE_SALT).apply();
            return;
        }
        try {
            final byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            final String hash = hash(passcode, salt);
            prefs().edit()
                    .putString(KEY_PASSCODE_HASH, hash)
                    .putString(KEY_PASSCODE_SALT, Utilities.bytesToHex(salt))
                    .apply();
        } catch (Exception ignore) {
        }
    }

    public static void clearGuardPasscode() {
        setGuardPasscode(null);
    }

    public static boolean checkGuardPasscode(String passcode) {
        final String stored = prefs().getString(KEY_PASSCODE_HASH, "");
        final String saltHex = prefs().getString(KEY_PASSCODE_SALT, "");
        if (TextUtils.isEmpty(stored) || TextUtils.isEmpty(saltHex) || TextUtils.isEmpty(passcode)) {
            return false;
        }
        try {
            return stored.equals(hash(passcode, Utilities.hexToBytes(saltHex)));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hash(String passcode, byte[] salt) throws Exception {
        final byte[] pass = passcode.getBytes("UTF-8");
        final byte[] bytes = new byte[32 + pass.length];
        System.arraycopy(salt, 0, bytes, 0, 16);
        System.arraycopy(pass, 0, bytes, 16, pass.length);
        System.arraycopy(salt, 0, bytes, pass.length + 16, 16);
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
    }

    public static boolean isHideWhenOwnerAway() { return get(KEY_OWNER_AWAY); }
    public static boolean isProtectMedia() { return get(KEY_PROTECT_MEDIA); }
    public static boolean isProtectComposer() { return get(KEY_PROTECT_COMPOSER); }
    public static boolean isProtectScreenshots() { return get(KEY_PROTECT_SCREENSHOTS); }

    /** Immutable copy handed to the analysis thread. */
    public static final class Snapshot {
        public final int mode;
        public final int action;
        public final int sensitivity;
        public final boolean hideWhenOwnerAway;

        public Snapshot(int mode, int action, int sensitivity, boolean hideWhenOwnerAway) {
            this.mode = mode;
            this.action = action;
            this.sensitivity = Math.max(SENSITIVITY_LOW, Math.min(SENSITIVITY_HIGH, sensitivity));
            this.hideWhenOwnerAway = hideWhenOwnerAway;
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(getMode(), getAction(), getSensitivity(), isHideWhenOwnerAway());
    }
}
