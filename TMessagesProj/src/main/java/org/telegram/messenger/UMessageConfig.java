package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;

import org.telegram.tgnet.TLRPC;

/** U message specific settings (stored apart from Telegram's own config). */
public class UMessageConfig {

    private static final String PREFS = "umessage";
    /** Facebook App ID for Instagram's "Sharing to Stories"; story sharing stays hidden while empty. */
    public static final String INSTAGRAM_FB_APP_ID = "1446263944380234";
    public static final String KEY_BLOCK_ADS = "block_ads";

    private static volatile Boolean adsBlocked;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Sponsored messages, sponsored search results and video ads are not requested. Off by default: not a safety feature. */
    public static boolean isAdsBlocked() {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.BLOCK_ADS)) return false;
        Boolean value = adsBlocked;
        if (value == null) {
            value = adsBlocked = prefs().getBoolean(KEY_BLOCK_ADS, false);
        }
        return value;
    }

    public static void setAdsBlocked(boolean blocked) {
        adsBlocked = blocked;
        prefs().edit().putBoolean(KEY_BLOCK_ADS, blocked).apply();
    }

    /* "Use U Message Proxy": fall back to built-in proxies when direct connection fails (off by default) */

    /** Preview/page key of the U message Premium switch (value lives in UMessagePremiumController). */
    public static final String KEY_UM_PREMIUM = "um_premium";
    public static final String KEY_PROXY_FALLBACK = "proxy_fallback";

    private static volatile Boolean proxyFallback;

    public static boolean isProxyFallbackEnabled() {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.PROXY_FALLBACK)) return false;
        Boolean value = proxyFallback;
        if (value == null) {
            value = proxyFallback = prefs().getBoolean(KEY_PROXY_FALLBACK, false);
        }
        return value;
    }

    public static void setProxyFallbackEnabled(boolean enabled) {
        proxyFallback = enabled;
        prefs().edit().putBoolean(KEY_PROXY_FALLBACK, enabled).apply();
        UMessageProxyManager.onSettingChanged();
    }

    /* Quick reply templates: typing "/shortcut " in a chat expands to the template text */

    private static final String KEY_TEMPLATES = "templates";

    public static class Template {
        public String shortcut;
        public String text;

        public Template(String shortcut, String text) {
            this.shortcut = shortcut;
            this.text = text;
        }
    }

    private static ArrayList<Template> templates;

    public static ArrayList<Template> getTemplates() {
        if (templates == null) {
            templates = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(prefs().getString(KEY_TEMPLATES, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    templates.add(new Template(o.getString("s"), o.getString("t")));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return templates;
    }

    public static void saveTemplates() {
        JSONArray array = new JSONArray();
        try {
            for (Template t : getTemplates()) {
                array.put(new JSONObject().put("s", t.shortcut).put("t", t.text));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString(KEY_TEMPLATES, array.toString()).apply();
    }

    public static Template findTemplate(String shortcut) {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.TEMPLATES)) return null;
        for (Template t : getTemplates()) {
            if (t.shortcut.equalsIgnoreCase(shortcut)) {
                return t;
            }
        }
        return null;
    }

    /* Nearby share: exchange contacts with U message users around via Nearby Connections */

    public static final String KEY_NEARBY_SHARE = "nearby_share";

    public static boolean isNearbyShareEnabled() {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.NEARBY_SHARE)) return false;
        return prefs().getBoolean(KEY_NEARBY_SHARE, false);
    }

    public static void setNearbyShareEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_NEARBY_SHARE, enabled).apply();
    }

    /* Focus mode: during the chosen hours only chats from the allowed folders notify */

    private static final String KEY_FOCUS_ENABLED = "focus_enabled";
    private static final String KEY_FOCUS_START = "focus_start";
    private static final String KEY_FOCUS_END = "focus_end";
    private static final String KEY_FOCUS_FOLDERS = "focus_folders";

    public static boolean isFocusEnabled() {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.FOCUS_MODE)) return false;
        return prefs().getBoolean(KEY_FOCUS_ENABLED, false);
    }

    public static void setFocusEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_FOCUS_ENABLED, enabled).apply();
    }

    /** Minutes since midnight. */
    public static int getFocusStart() {
        return prefs().getInt(KEY_FOCUS_START, 9 * 60);
    }

    public static int getFocusEnd() {
        return prefs().getInt(KEY_FOCUS_END, 18 * 60);
    }

    public static void setFocusHours(int startMinutes, int endMinutes) {
        prefs().edit().putInt(KEY_FOCUS_START, startMinutes).putInt(KEY_FOCUS_END, endMinutes).apply();
    }

    public static HashSet<Integer> getFocusFolders() {
        HashSet<Integer> result = new HashSet<>();
        for (String id : prefs().getString(KEY_FOCUS_FOLDERS, "").split(",")) {
            try {
                if (!id.isEmpty()) result.add(Integer.parseInt(id));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    public static void setFocusFolders(HashSet<Integer> folderIds) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : folderIds) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs().edit().putString(KEY_FOCUS_FOLDERS, sb.toString()).apply();
    }

    /** True while focus mode is on and the current time is inside the focus hours (may wrap midnight). */
    public static boolean isFocusActiveNow() {
        if (!isFocusEnabled()) {
            return false;
        }
        final Calendar calendar = Calendar.getInstance();
        final int now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        final int start = getFocusStart(), end = getFocusEnd();
        if (start == end) {
            return true;
        }
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** Whether focus mode silences this dialog now. Mentions and replies to you always come through. */
    public static boolean isSilencedByFocus(int account, long dialogId, boolean mentioned) {
        if (mentioned || !isFocusActiveNow()) {
            return false;
        }
        final HashSet<Integer> allowed = getFocusFolders();
        final MessagesController controller = MessagesController.getInstance(account);
        for (MessagesController.DialogFilter filter : controller.getDialogFilters()) {
            if (allowed.contains(filter.id) && (filter.isDefault() || filter.includesDialog(AccountInstance.getInstance(account), dialogId))) {
                return false;
            }
        }
        return true;
    }

    /* Toggles from the U message settings screen */

    public static final String KEY_STORIES_ANONYMOUS = "stories_anonymous";
    public static final String KEY_STORIES_HIDDEN = "stories_hidden";
    public static final String KEY_STORIES_DOWNLOAD = "stories_download";
    public static final String KEY_SAVE_EDITED = "save_edited";
    public static final String KEY_SAVE_DELETED = "save_deleted";
    public static final String KEY_STOP_AUTODOWNLOAD = "stop_autodownload";
    public static final String KEY_AUTO_REPLY = "auto_reply";
    public static final String KEY_GHOST_MODE = "ghost_mode";
    public static final String KEY_GHOST_BUTTON = "ghost_button";
    public static final String KEY_HIDDEN_CHATS = "hidden_chats";
    public static final String KEY_CONFIRM_STICKER = "confirm_sticker";
    public static final String KEY_CONFIRM_VOICE = "confirm_voice";
    public static final String KEY_CONFIRM_GIF = "confirm_gif";
    public static final String KEY_ROUND_FRONT_CAMERA = "round_front_camera";
    public static final String KEY_VOICE_INPUT = "voice_input";
    public static final String KEY_FOLDER_ICONS = "folder_icons";
    public static final String KEY_HIDE_FOLDER_TABS = "hide_folder_tabs";
    public static final String KEY_ADMIN_FOLDERS = "admin_folders";
    public static final String KEY_AUTO_APPROVE = "auto_approve_requests";
    public static final String KEY_UNREAD_REMINDER = "unread_reminder";
    public static final String KEY_STRANGER_PROTECTION = "stranger_protection";
    public static final String KEY_GROUP_SPAM_GUARD = "group_spam_guard";
    public static final String KEY_BLOCK_APK = "block_apk";
    public static final String KEY_SAVE_VIEW_ONCE = "save_view_once";

    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> flags = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int version;

    /** Changes every time a setting changes, so screens can tell when to refresh. */
    public static int getVersion() {
        return version;
    }

    public static boolean isDefaultOn(String key) {
        switch (key) {
            case KEY_ROUND_FRONT_CAMERA:
            case KEY_VOICE_INPUT:
            // user safety (Settings → Security): on unless the user turns them off; all checks run on the device
            case KEY_BLOCK_APK:
            case KEY_GROUP_SPAM_GUARD:
            case KEY_STRANGER_PROTECTION:
                return true;
        }
        return false;
    }

    /** Local retention options are off while Safe Mode (admin, for everyone) is on, or while local data is being cleared. */
    public static boolean isConfigurationAvailable(String key) {
        if (KEY_SAVE_VIEW_ONCE.equals(key)) return !UMessageLocalData.isClearing();
        final boolean extendedCache = KEY_SAVE_EDITED.equals(key)
                || KEY_SAVE_DELETED.equals(key);
        return !extendedCache || !UMessageLocalData.isClearing()
                && !org.telegram.configuration.ConfigurationManager.isSafeModeActive();
    }

    public static boolean get(String key) {
        if (!isConfigurationAvailable(key)) {
            return false;
        }
        // switched off from the admin panel: Telegram's own behaviour, the user's saved choice is kept.
        // Hidden chats stay hidden (only their entry points close, see isHiddenChatsManageable).
        if (!KEY_HIDDEN_CHATS.equals(key) && !UMessageRemoteFlags.isKeyEnabled(key)) {
            return KEY_ROUND_FRONT_CAMERA.equals(key); // Telegram opens the round camera on the front side
        }
        Boolean value = flags.get(key);
        if (value == null) {
            value = prefs().getBoolean(key, isDefaultOn(key));
            flags.put(key, value);
        }
        return value;
    }

    public static void set(String key, boolean value) {
        if (value && !isConfigurationAvailable(key)) {
            return;
        }
        flags.put(key, value);
        prefs().edit().putBoolean(key, value).apply();
        version++;
        switch (key) {
            case KEY_HIDDEN_CHATS:
            case KEY_STRANGER_PROTECTION:
                refreshDialogs();
                break;
            case KEY_GHOST_MODE:
                UMessageGhostScope.onChanged();
                break;
            case KEY_AUTO_REPLY:
                if (value) {
                    UMessageAutomation.resetAutoReplies();
                }
                break;
        }
    }

    /** UMessageRemoteFlags changed: screens comparing getVersion() rebuild. */
    static void onRemoteFlagsChanged() {
        version++;
    }

    /** Hidden chats can be opened, hidden or shown (local switch on and not switched off remotely). */
    public static boolean isHiddenChatsManageable() {
        return isHiddenChatsEnabled() && UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.HIDDEN_CHATS);
    }

    public static boolean isAnonymousStories() { return get(KEY_STORIES_ANONYMOUS); }
    public static boolean isStoriesHidden() { return get(KEY_STORIES_HIDDEN); }
    public static boolean isStoriesDownload() { return get(KEY_STORIES_DOWNLOAD); }
    public static boolean isSaveEdited() { return get(KEY_SAVE_EDITED); }
    public static boolean isSaveDeleted() { return get(KEY_SAVE_DELETED); }
    public static boolean isAutoDownloadStopped() { return get(KEY_STOP_AUTODOWNLOAD); }
    public static boolean isAutoReply() { return get(KEY_AUTO_REPLY); }
    public static boolean isGhostMode() { return get(KEY_GHOST_MODE); }
    public static void setGhostMode(boolean value) { set(KEY_GHOST_MODE, value); }
    public static boolean isGhostButton() { return get(KEY_GHOST_BUTTON); }
    public static boolean isHiddenChatsEnabled() { return get(KEY_HIDDEN_CHATS); }
    public static boolean isConfirmSticker() { return get(KEY_CONFIRM_STICKER); }
    public static boolean isConfirmVoice() { return get(KEY_CONFIRM_VOICE); }
    public static boolean isConfirmGif() { return get(KEY_CONFIRM_GIF); }
    public static boolean isRoundFrontCamera() { return get(KEY_ROUND_FRONT_CAMERA); }
    public static boolean isSaveViewOnce() { return get(KEY_SAVE_VIEW_ONCE); }

    /* Round video effects: defaults applied when recording starts (see RoundVideoEffects) */

    private static final String KEY_ROUND_FILTER = "round_filter";
    private static final String KEY_ROUND_BEAUTY_LEVEL = "round_beauty_level";
    private static final String KEY_ROUND_BLUSH = "round_blush";
    private static final String KEY_ROUND_EFFECT_PRESET = "round_effect_preset";
    private static final String KEY_ROUND_EFFECT_LENS = "round_effect_lens";
    private static final String KEY_ROUND_EFFECT_INTENSITY = "round_effect_intensity";

    private static final String KEY_ROUND_MAKEUP = "round_makeup_manual";

    /** U message: the user's own round video makeup as JSON (see MakeupSettings), null for the defaults. */
    public static String getRoundMakeup() {
        return prefs().getString(KEY_ROUND_MAKEUP, null);
    }

    public static void setRoundMakeup(String json) {
        prefs().edit().putString(KEY_ROUND_MAKEUP, json).apply();
    }

    /** Selected all-in-one makeup look. -1 means that the legacy individual controls still need migration. */
    public static int getRoundEffectPreset() {
        return prefs().getInt(KEY_ROUND_EFFECT_PRESET, -1);
    }

    public static void setRoundEffectPreset(int preset) {
        prefs().edit().putInt(KEY_ROUND_EFFECT_PRESET, preset).apply();
    }

    /** Snap lens id of the selected look, null for none: lens positions change when the lens group changes. */
    public static String getRoundEffectLens() {
        return prefs().getString(KEY_ROUND_EFFECT_LENS, null);
    }

    public static void setRoundEffectLens(String lensId) {
        prefs().edit().putString(KEY_ROUND_EFFECT_LENS, lensId).apply();
    }

    /** Overall strength of the selected makeup look, 0 is off. */
    public static int getRoundEffectIntensity() {
        final SharedPreferences p = prefs();
        if (p.contains(KEY_ROUND_EFFECT_INTENSITY)) {
            return Math.max(0, Math.min(100, p.getInt(KEY_ROUND_EFFECT_INTENSITY, 70)));
        }
        final int legacy = Math.max(getRoundBeauty(), Math.max(getRoundBlush(), getRoundLipstick()));
        return legacy > 0 ? legacy : 70;
    }

    public static void setRoundEffectIntensity(int percent) {
        prefs().edit().putInt(KEY_ROUND_EFFECT_INTENSITY, Math.max(0, Math.min(100, percent))).apply();
    }

    public static int getRoundFilter() {
        return prefs().getInt(KEY_ROUND_FILTER, 0);
    }

    public static void setRoundFilter(int filter) {
        prefs().edit().putInt(KEY_ROUND_FILTER, filter).apply();
    }

    public static final int DEFAULT_ROUND_BEAUTY = 70;

    /** Skin smoothing strength in percent, 0 is off. */
    public static int getRoundBeauty() {
        final SharedPreferences p = prefs();
        if (p.contains(KEY_ROUND_BEAUTY_LEVEL)) {
            return p.getInt(KEY_ROUND_BEAUTY_LEVEL, 0);
        }
        // earlier versions stored a style or an on/off switch
        return p.getInt("round_beauty_style", p.getBoolean("round_beauty", false) ? 1 : 0) > 0 ? DEFAULT_ROUND_BEAUTY : 0;
    }

    public static void setRoundBeauty(int percent) {
        prefs().edit().putInt(KEY_ROUND_BEAUTY_LEVEL, percent).apply();
    }

    /** Blush strength in percent, 0 is off. */
    public static int getRoundBlush() {
        return prefs().getInt(KEY_ROUND_BLUSH, 0);
    }

    public static void setRoundBlush(int percent) {
        prefs().edit().putInt(KEY_ROUND_BLUSH, percent).apply();
    }

    private static final String KEY_ROUND_LIPSTICK = "round_lipstick";
    private static final String KEY_ROUND_LIPSTICK_SHADE = "round_lipstick_shade";
    private static final String KEY_ROUND_LIP_SATURATION = "round_lip_saturation";
    private static final String KEY_ROUND_LIP_BRIGHTNESS = "round_lip_brightness";
    private static final String KEY_ROUND_LIP_SOFTNESS = "round_lip_softness";

    /** Natural look: about half strength. */
    public static final int DEFAULT_ROUND_LIPSTICK = 55;

    /** Lipstick strength in percent, 0 is off. */
    public static int getRoundLipstick() {
        return prefs().getInt(KEY_ROUND_LIPSTICK, 0);
    }

    public static void setRoundLipstick(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIPSTICK, percent).apply();
    }

    /** Index into RoundVideoEffects.LIPSTICK_COLORS. */
    public static int getRoundLipstickShade() {
        return prefs().getInt(KEY_ROUND_LIPSTICK_SHADE, 0);
    }

    public static void setRoundLipstickShade(int shade) {
        prefs().edit().putInt(KEY_ROUND_LIPSTICK_SHADE, shade).apply();
    }

    /** 0..100, 50 is the shade's own saturation. */
    public static int getRoundLipSaturation() {
        return prefs().getInt(KEY_ROUND_LIP_SATURATION, 50);
    }

    public static void setRoundLipSaturation(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_SATURATION, percent).apply();
    }

    /** 0..100, 50 keeps the lips' own brightness. */
    public static int getRoundLipBrightness() {
        return prefs().getInt(KEY_ROUND_LIP_BRIGHTNESS, 50);
    }

    public static void setRoundLipBrightness(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_BRIGHTNESS, percent).apply();
    }

    /** 0..100, width of the soft lipstick edge. */
    public static int getRoundLipSoftness() {
        return prefs().getInt(KEY_ROUND_LIP_SOFTNESS, 50);
    }

    public static void setRoundLipSoftness(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_SOFTNESS, percent).apply();
    }

    public static boolean isVoiceInputButton() { return get(KEY_VOICE_INPUT); }
    public static boolean isFolderIcons() { return get(KEY_FOLDER_ICONS); }
    public static boolean isFolderTabsHidden() { return get(KEY_HIDE_FOLDER_TABS); }
    public static boolean isAdminFolders() { return get(KEY_ADMIN_FOLDERS); }
    public static boolean isAutoApproveRequests() { return get(KEY_AUTO_APPROVE); }
    public static boolean isUnreadReminder() { return get(KEY_UNREAD_REMINDER); }
    public static boolean isStrangerProtection() { return get(KEY_STRANGER_PROTECTION); }
    public static boolean isGroupSpamGuard() { return get(KEY_GROUP_SPAM_GUARD); }
    public static boolean isApkBlocked() { return get(KEY_BLOCK_APK); }

    /* Account auto-switch: two independent PINs mapped to two Telegram accounts. */

    private static final String ACCOUNT_SWITCH_ACCOUNT_PREFIX = "account_switch_account_";
    private static final String ACCOUNT_SWITCH_SALT_PREFIX = "account_switch_salt_";
    private static final String ACCOUNT_SWITCH_HASH_PREFIX = "account_switch_hash_";
    /** PBKDF2 iteration count of the switch PIN hash; missing for the legacy single SHA-256. */
    private static final String ACCOUNT_SWITCH_KDF_PREFIX = "account_switch_kdf_";
    /** App passcode hash the switch PINs were configured with; changing or removing the passcode invalidates them. */
    private static final String ACCOUNT_SWITCH_PASSCODE_KEY = "account_switch_passcode";

    /** Drops the switch PINs when the app passcode was changed or disabled after they were configured. */
    private static void validateAccountSwitchPins() {
        SharedPreferences p = prefs();
        if (!p.contains(ACCOUNT_SWITCH_HASH_PREFIX + 0) && !p.contains(ACCOUNT_SWITCH_HASH_PREFIX + 1)) {
            return;
        }
        String current = SharedConfig.passcodeHash;
        if (current == null || current.length() == 0) {
            clearAccountSwitchPins();
            return;
        }
        String bound = p.getString(ACCOUNT_SWITCH_PASSCODE_KEY, null);
        if (bound == null) {
            // configured by an older version: bind to the passcode that is active now
            p.edit().putString(ACCOUNT_SWITCH_PASSCODE_KEY, current).apply();
        } else if (!bound.equals(current)) {
            clearAccountSwitchPins();
        }
    }

    public static boolean hasAccountSwitchPins() {
        validateAccountSwitchPins();
        int first = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 0, -1);
        int second = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 1, -1);
        return first != second && UserConfig.isValidAccount(first) && UserConfig.isValidAccount(second)
                && prefs().contains(ACCOUNT_SWITCH_HASH_PREFIX + 0)
                && prefs().contains(ACCOUNT_SWITCH_HASH_PREFIX + 1);
    }

    public static void setAccountSwitchPins(int firstAccount, String firstPin, int secondAccount, String secondPin) {
        String firstSaltHex = newPinSalt();
        String secondSaltHex = newPinSalt();
        prefs().edit()
                .putInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 0, firstAccount)
                .putString(ACCOUNT_SWITCH_SALT_PREFIX + 0, firstSaltHex)
                .putString(ACCOUNT_SWITCH_HASH_PREFIX + 0, pinKdf(firstSaltHex, firstPin, PIN_KDF_ITERATIONS))
                .putInt(ACCOUNT_SWITCH_KDF_PREFIX + 0, PIN_KDF_ITERATIONS)
                .putInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 1, secondAccount)
                .putString(ACCOUNT_SWITCH_SALT_PREFIX + 1, secondSaltHex)
                .putString(ACCOUNT_SWITCH_HASH_PREFIX + 1, pinKdf(secondSaltHex, secondPin, PIN_KDF_ITERATIONS))
                .putInt(ACCOUNT_SWITCH_KDF_PREFIX + 1, PIN_KDF_ITERATIONS)
                .apply();

        // The first account PIN is also the app passcode, so configuring this feature
        // always enables Telegram's lock screen and keeps exactly the requested flow.
        try {
            SharedConfig.passcodeSalt = new byte[16];
            Utilities.random.nextBytes(SharedConfig.passcodeSalt);
            byte[] pinBytes = firstPin.getBytes("UTF-8");
            byte[] bytes = new byte[32 + pinBytes.length];
            System.arraycopy(SharedConfig.passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(pinBytes, 0, bytes, 16, pinBytes.length);
            System.arraycopy(SharedConfig.passcodeSalt, 0, bytes, pinBytes.length + 16, 16);
            SharedConfig.passcodeHash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
            SharedConfig.badPasscodeTries = 0;
            SharedConfig.passcodeRetryInMs = 0;
            SharedConfig.saveConfig();
            prefs().edit().putString(ACCOUNT_SWITCH_PASSCODE_KEY, SharedConfig.passcodeHash).apply();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetPasscode);
        } catch (Exception e) {
            FileLog.e(e);
        }
        version++;
    }

    public static void clearAccountSwitchPins() {
        SharedPreferences.Editor editor = prefs().edit();
        for (int i = 0; i < 2; i++) {
            editor.remove(ACCOUNT_SWITCH_ACCOUNT_PREFIX + i)
                    .remove(ACCOUNT_SWITCH_SALT_PREFIX + i)
                    .remove(ACCOUNT_SWITCH_HASH_PREFIX + i)
                    .remove(ACCOUNT_SWITCH_KDF_PREFIX + i);
        }
        editor.remove(ACCOUNT_SWITCH_PASSCODE_KEY);
        editor.apply();
        version++;
    }

    /** Returns the mapped active account, or -1 when this is not an account-switch PIN. */
    public static int findAccountForSwitchPin(String pin) {
        if (pin == null || pin.length() != 4 || !UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.ACCOUNT_SWITCH_PINS)) {
            return -1;
        }
        validateAccountSwitchPins();
        int found = -1;
        // both slots are always checked so the time taken does not tell which PIN matched
        for (int i = 0; i < 2; i++) {
            int account = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + i, -1);
            String salt = prefs().getString(ACCOUNT_SWITCH_SALT_PREFIX + i, null);
            String hash = prefs().getString(ACCOUNT_SWITCH_HASH_PREFIX + i, null);
            if (salt == null || hash == null) continue;
            int iterations = prefs().getInt(ACCOUNT_SWITCH_KDF_PREFIX + i, 0);
            boolean match = iterations > 0 ? hashEquals(hash, pinKdf(salt, pin, iterations)) : hashEquals(hash, hashPassword(salt, pin));
            if (match && iterations <= 0) {
                // legacy single SHA-256: re-hash with PBKDF2 now that the PIN is known
                String newSalt = newPinSalt();
                prefs().edit()
                        .putString(ACCOUNT_SWITCH_SALT_PREFIX + i, newSalt)
                        .putString(ACCOUNT_SWITCH_HASH_PREFIX + i, pinKdf(newSalt, pin, PIN_KDF_ITERATIONS))
                        .putInt(ACCOUNT_SWITCH_KDF_PREFIX + i, PIN_KDF_ITERATIONS)
                        .apply();
            }
            if (match && found < 0 && UserConfig.isValidAccount(account)) {
                found = account;
            }
        }
        return found;
    }

    /* Auto reply text */

    public static String getAutoReplyText() {
        return prefs().getString("auto_reply_text", LocaleController.getString(R.string.UMessageAutoReplyDefault));
    }

    public static void setAutoReplyText(String text) {
        prefs().edit().putString("auto_reply_text", text).apply();
        UMessageAutomation.resetAutoReplies();
    }

    /* Unread reminder */

    public static final int REMINDER_SOUND_NOTIFICATION = 0;
    public static final int REMINDER_SOUND_ALARM = 1;
    public static final int REMINDER_SOUND_RINGTONE = 2;
    public static final int REMINDER_SOUND_NONE = 3;
    public static final int[] REMINDER_DELAYS = {1, 5, 10, 15, 30, 60};

    public static int getReminderDelayMinutes() {
        return prefs().getInt("reminder_delay", 5);
    }

    public static void setReminderDelayMinutes(int minutes) {
        prefs().edit().putInt("reminder_delay", minutes).apply();
    }

    public static int getReminderSound() {
        return prefs().getInt("reminder_sound", REMINDER_SOUND_NOTIFICATION);
    }

    public static void setReminderSound(int sound) {
        prefs().edit().putInt("reminder_sound", sound).apply();
    }

    /* Hidden chats ("secret chat") and stranger protection */

    /** Keyed by Telegram user id: hidden chats belong to the user, not to the account slot. */
    private static final HashMap<Long, HashSet<Long>> hiddenChats = new HashMap<>();
    private static final HashMap<Long, HashSet<Integer>> hiddenFolders = new HashMap<>();

    /** Logout / slot reuse: drops the cached lists of the user that leaves the slot. */
    static void resetAccount(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        synchronized (hiddenChats) {
            hiddenChats.remove(uid);
            hiddenChats.remove(0L);
        }
        synchronized (hiddenFolders) {
            hiddenFolders.remove(uid);
            hiddenFolders.remove(0L);
        }
        version++;
    }

    public static HashSet<Long> getHiddenChats(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        synchronized (hiddenChats) {
            HashSet<Long> set = hiddenChats.get(uid);
            if (set == null) {
                set = new HashSet<>();
                for (String id : prefs().getString(UMessageLocalData.userKey(PREFS, "hidden_chats_", account, uid), "").split(",")) {
                    try {
                        if (!id.isEmpty()) set.add(Long.parseLong(id));
                    } catch (NumberFormatException ignore) {
                    }
                }
                hiddenChats.put(uid, set);
            }
            return set;
        }
    }

    public static void setChatsHidden(int account, ArrayList<Long> dialogIds, boolean hidden) {
        synchronized (hiddenChats) {
            HashSet<Long> set = getHiddenChats(account);
            if (hidden) {
                set.addAll(dialogIds);
            } else {
                set.removeAll(dialogIds);
            }
            StringBuilder sb = new StringBuilder();
            for (Long id : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            prefs().edit().putString(UMessageLocalData.userKey(PREFS, "hidden_chats_", account), sb.toString()).apply();
        }
        version++;
        refreshDialogs();
        // launcher / direct-share shortcuts must not keep pointing at a chat that is now hidden
        if (hidden) {
            try {
                ArrayList<String> ids = new ArrayList<>();
                for (Long id : dialogIds) ids.add("did3_" + id);
                androidx.core.content.pm.ShortcutManagerCompat.removeLongLivedShortcuts(ApplicationLoader.applicationContext, ids);
                for (Long id : dialogIds) MediaDataController.getInstance(account).uninstallShortcut(id, MediaDataController.SHORTCUT_TYPE_USER_OR_CHAT);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        AndroidUtilities.runOnUIThread(() -> MediaDataController.getInstance(account).buildShortcuts());
    }

    public static boolean isChatHidden(int account, long dialogId) {
        if (!isHiddenChatsEnabled()) {
            return false;
        }
        synchronized (hiddenChats) {
            return getHiddenChats(account).contains(dialogId);
        }
    }

    public static HashSet<Integer> getHiddenFolders(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        synchronized (hiddenFolders) {
            HashSet<Integer> set = hiddenFolders.get(uid);
            if (set == null) {
                set = new HashSet<>();
                for (String id : prefs().getString(UMessageLocalData.userKey(PREFS, "hidden_folders_", account, uid), "").split(",")) {
                    try {
                        if (!id.isEmpty()) set.add(Integer.parseInt(id));
                    } catch (NumberFormatException ignore) {
                    }
                }
                hiddenFolders.put(uid, set);
            }
            return set;
        }
    }

    public static void setFolderHidden(int account, int filterId, boolean hidden) {
        synchronized (hiddenFolders) {
            HashSet<Integer> set = getHiddenFolders(account);
            if (hidden) {
                set.add(filterId);
            } else {
                set.remove(filterId);
            }
            StringBuilder sb = new StringBuilder();
            for (Integer id : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            prefs().edit().putString(UMessageLocalData.userKey(PREFS, "hidden_folders_", account), sb.toString()).apply();
        }
        version++;
        refreshDialogs();
    }

    public static boolean isFolderHidden(int account, int filterId) {
        if (!isHiddenChatsEnabled()) {
            return false;
        }
        synchronized (hiddenFolders) {
            return getHiddenFolders(account).contains(filterId);
        }
    }

    /* Hidden chats password: only a salted SHA-256 hash is stored */

    /** A 4 digit PIN entered on the Telegram-style lock screen (older text passwords are not accepted any more). */
    public static boolean hasHiddenPassword() {
        return prefs().contains("hidden_password_hash") && prefs().getBoolean("hidden_password_pin", false);
    }

    /* How the hidden chats are opened */

    public static final int HIDDEN_ACCESS_HIDE_ICON = 0;
    public static final int HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS = 1;
    public static final int HIDDEN_ACCESS_EDIT_LONG_PRESS = 2;

    public static int getHiddenAccess() {
        return prefs().getInt("hidden_access", HIDDEN_ACCESS_HIDE_ICON);
    }

    public static void setHiddenAccess(int access) {
        prefs().edit().putInt("hidden_access", access).apply();
        version++;
    }

    public static boolean isHiddenAccess(int access) {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.HIDDEN_CHATS)) return false;
        return isHiddenChatsEnabled() && getHiddenAccess() == access;
    }

    public static void setHiddenPassword(String password) {
        final String saltHex = newPinSalt();
        prefs().edit()
                .putString("hidden_password_salt", saltHex)
                .putString("hidden_password_hash", pinKdf(saltHex, password, PIN_KDF_ITERATIONS))
                .putInt("hidden_password_kdf", PIN_KDF_ITERATIONS)
                .putBoolean("hidden_password_pin", true)
                .commit();
    }

    public static boolean checkHiddenPassword(String password) {
        if (password == null) return false;
        final String saltHex = prefs().getString("hidden_password_salt", null);
        final String hash = prefs().getString("hidden_password_hash", null);
        if (saltHex == null || hash == null) return false;
        final int iterations = prefs().getInt("hidden_password_kdf", 0);
        if (iterations > 0) {
            return hashEquals(hash, pinKdf(saltHex, password, iterations));
        }
        // stored by an older version as a single salted SHA-256: upgrade on the first correct entry
        final boolean ok = hashEquals(hash, hashPassword(saltHex, password));
        if (ok) setHiddenPassword(password);
        return ok;
    }

    /* Wrong-PIN lockout (persisted, growing), shared by the hidden chats and parental PINs */

    public static final String PIN_LOCK_HIDDEN = "hidden";
    public static final String PIN_LOCK_PARENTAL = "parental";
    private static final long PIN_LOCK_MAX_MS = 60 * 60 * 1000L;

    /** pin_lock_start_ value of a lockout that has been fully waited out. */
    private static final long PIN_LOCK_SERVED = -1;

    private static int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Milliseconds until the next attempt is allowed, 0 when not locked out. The lockout runs on
     * SystemClock.elapsedRealtime, so changing the wall clock never shortens it; after a reboot the
     * remaining wait restarts at the full duration.
     */
    public static long getPinLockRemainingMs(String key) {
        final SharedPreferences p = prefs();
        final long lock = getPinLockDurationMs(p.getInt("pin_lock_tries_" + key, 0));
        if (lock <= 0) {
            return 0;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        final int boot = bootCount();
        long start = p.getLong("pin_lock_start_" + key, Long.MIN_VALUE);
        if (start == PIN_LOCK_SERVED) {
            return 0;
        }
        if (start == Long.MIN_VALUE) {
            // lockout stored by an older version as a wall-clock deadline
            if (p.getLong("pin_lock_until_" + key, 0) <= System.currentTimeMillis()) {
                p.edit().putLong("pin_lock_start_" + key, PIN_LOCK_SERVED).remove("pin_lock_until_" + key).commit();
                return 0;
            }
            start = now;
            p.edit().putLong("pin_lock_start_" + key, start).putInt("pin_lock_boot_" + key, boot).remove("pin_lock_until_" + key).commit();
        } else if (start > now || p.getInt("pin_lock_boot_" + key, boot) != boot) {
            // rebooted since the lockout began: the full lockout starts again
            start = now;
            p.edit().putLong("pin_lock_start_" + key, start).putInt("pin_lock_boot_" + key, boot).commit();
        }
        final long remaining = start + lock - now;
        if (remaining <= 0) {
            p.edit().putLong("pin_lock_start_" + key, PIN_LOCK_SERVED).commit();
            return 0;
        }
        return Math.min(remaining, lock);
    }

    /** Counts a wrong PIN; returns the lockout that now applies (0 when none). */
    public static long onPinLockFailed(String key) {
        int tries = prefs().getInt("pin_lock_tries_" + key, 0) + 1;
        long lock = getPinLockDurationMs(tries);
        SharedPreferences.Editor editor = prefs().edit()
                .putInt("pin_lock_tries_" + key, tries)
                .putLong("pin_lock_start_" + key, lock > 0 ? android.os.SystemClock.elapsedRealtime() : PIN_LOCK_SERVED)
                .putInt("pin_lock_boot_" + key, bootCount())
                .remove("pin_lock_until_" + key);
        // committed synchronously: killing the app right after a wrong PIN must not lose the attempt
        editor.commit();
        return lock;
    }

    /** "Too many tries, try again in N seconds/minutes" for a lockout. */
    public static String formatPinLock(long ms) {
        int seconds = Math.max(1, (int) Math.ceil(ms / 1000.0));
        String time = seconds >= 120 ? LocaleController.formatPluralString("Minutes", (seconds + 59) / 60) : LocaleController.formatPluralString("Seconds", seconds);
        return LocaleController.formatString(R.string.TooManyTries, time);
    }

    public static void resetPinLock(String key) {
        prefs().edit().remove("pin_lock_tries_" + key).remove("pin_lock_until_" + key)
                .remove("pin_lock_start_" + key).remove("pin_lock_boot_" + key).apply();
    }

    private static long getPinLockDurationMs(int tries) {
        if (tries < 3) {
            return 0;
        }
        if (tries <= 7) {
            return (tries - 2) * 5000L;
        }
        return Math.min(PIN_LOCK_MAX_MS, 30000L << Math.min(tries - 8, 10));
    }

    /* PIN hashing: PBKDF2-HMAC-SHA256 with a random 16 byte salt */

    private static final int PIN_KDF_ITERATIONS = 120_000;

    private static String newPinSalt() {
        final byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return Utilities.bytesToHex(salt);
    }

    private static String pinKdf(String saltHex, String pin, int iterations) {
        javax.crypto.spec.PBEKeySpec spec = null;
        try {
            spec = new javax.crypto.spec.PBEKeySpec(pin.toCharArray(), saltHex.getBytes("UTF-8"), iterations, 256);
            return Utilities.bytesToHex(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }

    /** Constant-time comparison of two hex hashes; an empty (failed) hash never matches. */
    static boolean hashEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) return false;
        try {
            return java.security.MessageDigest.isEqual(expected.toLowerCase(java.util.Locale.ROOT).getBytes("UTF-8"), actual.toLowerCase(java.util.Locale.ROOT).getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Legacy single salted SHA-256, only to verify PINs stored by older versions. */
    private static String hashPassword(String saltHex, String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(saltHex.getBytes("UTF-8"));
            return Utilities.bytesToHex(digest.digest(password.getBytes("UTF-8")));
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    /** A private chat with someone who is not a contact and you never wrote to. */
    public static boolean isStranger(int account, TLRPC.Dialog dialog) {
        if (dialog == null || dialog.id <= 0 || dialog.read_outbox_max_id > 0) {
            return false;
        }
        try {
            return isStrangerInternal(account, dialog);
        } catch (Exception e) {
            // also called from the notifications queue while the lists may change
            return false;
        }
    }

    private static boolean isStrangerInternal(int account, TLRPC.Dialog dialog) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User user = controller.getUser(dialog.id);
        if (user == null || user.self || user.contact || user.bot || user.support || user.verified
                || UserObject.isService(user.id) || UserObject.isReplyUser(user) || UserObject.isDeleted(user)) {
            return false;
        }
        final java.util.ArrayList<MessageObject> last = controller.dialogMessage.get(dialog.id);
        if (last != null) {
            for (MessageObject message : last) {
                if (message != null && message.isOut()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isStranger(int account, long dialogId) {
        return isStranger(account, MessagesController.getInstance(account).dialogs_dict.get(dialogId));
    }

    /** Kept out of the chat list: hidden chats and, with stranger protection, chats with strangers. */
    public static boolean isHiddenFromChatList(int account, TLRPC.Dialog dialog) {
        return dialog != null && (isChatHidden(account, dialog.id) || UMessageChatTrash.isTrashed(account, dialog.id) || isStrangerProtection() && isStranger(account, dialog));
    }

    public static boolean isNotificationSuppressed(int account, long dialogId) {
        return isChatHidden(account, dialogId) || UMessageChatTrash.isTrashed(account, dialogId) || isStrangerProtection() && isStranger(account, dialogId);
    }

    public static void refreshDialogs() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    MessagesController.getInstance(a).sortDialogs(null);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.storiesUpdated);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.reloadHints);
                }
            }
        });
    }

    /** Top peers (search / share suggestions) without the hidden chats. */
    public static ArrayList<TLRPC.TL_topPeer> visibleHints(int account, ArrayList<TLRPC.TL_topPeer> hints) {
        if (hints == null || !isHiddenChatsEnabled() || getHiddenChats(account).isEmpty()) {
            return hints;
        }
        final ArrayList<TLRPC.TL_topPeer> result = new ArrayList<>(hints.size());
        for (TLRPC.TL_topPeer peer : hints) {
            if (peer == null || peer.peer == null || !isChatHidden(account, DialogObject.getPeerDialogId(peer.peer))) {
                result.add(peer);
            }
        }
        return result;
    }

    /* Folder icons: the server folder emoticon, otherwise one picked from the folder type */

    public static void setFolderEmoticon(int account, int filterId, String emoticon) {
        final String key = UMessageLocalData.userKey(PREFS, "folder_emoticon_", account) + "_" + filterId;
        if (android.text.TextUtils.isEmpty(emoticon)) {
            prefs().edit().remove(key).apply();
        } else {
            prefs().edit().putString(key, emoticon).apply();
        }
    }

    public static String getFolderIcon(int account, MessagesController.DialogFilter filter) {
        if (filter.isDefault()) {
            return "\uD83D\uDCAC"; // speech balloon
        }
        final String emoticon = prefs().getString(UMessageLocalData.userKey(PREFS, "folder_emoticon_", account) + "_" + filter.id, null);
        if (!android.text.TextUtils.isEmpty(emoticon)) {
            return emoticon;
        }
        final int f = filter.flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        if ((filter.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0) return "\u2705"; // check mark
        if (f == MessagesController.DIALOG_FILTER_FLAG_CONTACTS || f == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
                || f == (MessagesController.DIALOG_FILTER_FLAG_CONTACTS | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS)) return "\uD83D\uDC64"; // person
        if (f == MessagesController.DIALOG_FILTER_FLAG_GROUPS) return "\uD83D\uDC65"; // people
        if (f == MessagesController.DIALOG_FILTER_FLAG_CHANNELS) return "\uD83D\uDCE2"; // loudspeaker
        if (f == MessagesController.DIALOG_FILTER_FLAG_BOTS) return "\uD83E\uDD16"; // robot
        return "\uD83D\uDCC1"; // folder
    }

    /* Confirmation before sending */

    public static void showSendConfirm(org.telegram.ui.ActionBar.BaseFragment fragment, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, int questionRes, Runnable onConfirm) {
        if (fragment == null || fragment.getParentActivity() == null) {
            onConfirm.run();
            return;
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.UMessageConfirmTitle));
        builder.setMessage(LocaleController.getString(questionRes));
        builder.setPositiveButton(LocaleController.getString(R.string.Send), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Shortcut names: letters, digits and underscore. */
    public static String normalizeShortcut(String shortcut) {
        if (shortcut == null) return "";
        shortcut = shortcut.trim();
        if (shortcut.startsWith("/")) shortcut = shortcut.substring(1);
        return shortcut.replaceAll("[^\\p{L}\\p{N}_]", "");
    }
}
