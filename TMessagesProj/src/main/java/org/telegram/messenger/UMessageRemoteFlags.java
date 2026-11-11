package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.LaunchActivity;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * U message: features switched off remotely from the admin panel ("Funksiyalar").
 * The server registry (umessage-premium-server/lib/remote-flags.js) is the source of truth for the ids.
 *
 * Fetched on app start / every return to the foreground, then polled every {@link #POLL_MS} while the app is
 * in the foreground (GET /api/settings?route=flags&v=<last v>; an unchanged version answers {v} only).
 * Fail-open: never fetched = everything enabled; a network or server error keeps the last known state.
 * A remote "off" only overrides: the user's own settings are never rewritten, so re-enabling restores them.
 */
public final class UMessageRemoteFlags {

    // ---------------------------------------------------------------- ids (keep in sync with lib/remote-flags.js)
    public static final String GHOST_MODE = "ghost_mode";
    public static final String HIDDEN_CHATS = "hidden_chats";
    public static final String STRANGER_PROTECTION = "stranger_protection";
    public static final String CHAT_TRASH = "chat_trash";
    public static final String PRIVACY_GUARD = "privacy_guard";
    public static final String ACCOUNT_SWITCH_PINS = "account_switch_pins";
    public static final String APK_BLOCK = "apk_block";
    public static final String GROUP_SPAM_GUARD = "group_spam_guard";
    public static final String MASKED_LINK_GUARD = "masked_link_guard";
    public static final String NSFW_FILTER = "nsfw_filter";
    public static final String PARENTAL_CONTROL = "parental_control";
    public static final String BANNED_SOURCES = "banned_sources";
    public static final String SAVE_EDITED = "save_edited";
    public static final String SAVE_DELETED = "save_deleted";
    public static final String VIEW_ONCE_SAVE = "view_once_save";
    public static final String TEMPLATES = "templates";
    public static final String AUTO_REPLY = "auto_reply";
    public static final String CONFIRM_STICKER = "confirm_sticker";
    public static final String CONFIRM_VOICE = "confirm_voice";
    public static final String CONFIRM_GIF = "confirm_gif";
    public static final String VOICE_INPUT = "voice_input";
    public static final String UZBEK_SPEECH = "uzbek_speech";
    public static final String OFFLINE_TRANSLATE = "offline_translate";
    public static final String SWIPE_ACTIONS = "swipe_actions";
    public static final String BANK_CARDS = "bank_cards";
    public static final String CHAT_EXPORT = "chat_export";
    public static final String CLEAR_NOTE = "clear_note";
    public static final String UNREAD_REMINDER = "unread_reminder";
    public static final String AUTO_APPROVE = "auto_approve";
    public static final String OFFICIAL_CHAT = "official_chat";
    public static final String FOCUS_MODE = "focus_mode";
    public static final String FOLDER_ICONS = "folder_icons";
    public static final String HIDE_FOLDER_TABS = "hide_folder_tabs";
    public static final String ADMIN_FOLDERS = "admin_folders";
    public static final String MUTUAL_CONTACT = "mutual_contact";
    public static final String STORIES_ANONYMOUS = "stories_anonymous";
    public static final String STORIES_HIDDEN = "stories_hidden";
    public static final String STORIES_DOWNLOAD = "stories_download";
    public static final String U_STORIES = "u_stories";
    public static final String OFFICIAL_STORIES = "official_stories";
    public static final String STORY_PRIVACY_BATCH = "story_privacy_batch";
    public static final String STORY_CAMERA_PRO = "story_camera_pro";
    public static final String STORY_MUSIC = "story_music";
    public static final String ROUND_FRONT_CAMERA = "round_front_camera";
    public static final String ROUND_EFFECTS = "round_effects";
    public static final String SEND_AS_ROUND = "send_as_round";
    public static final String AI_ERASER = "ai_eraser";
    public static final String DOCUMENT_READER = "document_reader";
    public static final String SLEEP_TIMER = "sleep_timer";
    public static final String STOP_AUTODOWNLOAD = "stop_autodownload";
    public static final String FRIEND_MAP = "friend_map";
    public static final String NEARBY_SHARE = "nearby_share";
    public static final String SHAZAM = "shazam";
    public static final String QR_LOGIN = "qr_login";
    public static final String INSTAGRAM_LINK = "instagram_link";
    public static final String INSTAGRAM_BUSINESS = "instagram_business";
    public static final String U_USERNAMES = "u_usernames";
    public static final String BLOCK_ADS = "block_ads";
    public static final String PROXY_FALLBACK = "proxy_fallback";
    public static final String UM_PREMIUM = "um_premium";
    public static final String LOCAL_TG_PREMIUM = "local_tg_premium";
    public static final String U_ADS = "u_ads";

    private static final String PREFS = "umessage_remote_flags";
    private static final String URL_BASE = UMessagePremiumController.BASE_URL + "/api/settings?route=flags";
    private static final long POLL_MS = 15_000;
    private static final int TIMEOUT_MS = 7_000;

    private static volatile Set<String> disabled;
    private static volatile long version = -1; // -1: never fetched
    private static boolean foreground;
    private static boolean inFlight;

    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!foreground) return;
            fetch();
            AndroidUtilities.cancelRunOnUIThread(this);
            AndroidUtilities.runOnUIThread(this, POLL_MS);
        }
    };

    private UMessageRemoteFlags() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> state() {
        Set<String> s = disabled;
        if (s == null) {
            synchronized (UMessageRemoteFlags.class) {
                s = disabled;
                if (s == null) {
                    final HashSet<String> loaded = new HashSet<>();
                    long v = -1;
                    try {
                        final SharedPreferences p = prefs();
                        v = p.getLong("v", -1);
                        final String list = p.getString("disabled", "");
                        if (!TextUtils.isEmpty(list)) Collections.addAll(loaded, list.split(","));
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    version = v;
                    disabled = s = Collections.unmodifiableSet(loaded);
                }
            }
        }
        return s;
    }

    /** False only when the admin switched this feature off. Thread-safe, cheap (a set lookup). */
    public static boolean isEnabled(String id) {
        if (id == null) return true;
        if (state().contains(id)) return false;
        // Safe Mode (admin panel) turns off every feature Google Play / the App Store would not allow
        return !isSafeModeFeature(id) || !org.telegram.configuration.ConfigurationManager.isSafeModeActive();
    }

    private static boolean isSafeModeFeature(String id) {
        switch (id) {
            case GHOST_MODE:
            case VIEW_ONCE_SAVE:
            case SAVE_DELETED:
            case SAVE_EDITED:
            case STORIES_ANONYMOUS:
            case STORIES_DOWNLOAD:
            // store policies: Telegram API terms, copyright, user content without reporting, background location
            case BLOCK_ADS:
            case SHAZAM:
            case STORY_MUSIC:
            case U_STORIES:
            case FRIEND_MAP:
            case LOCAL_TG_PREMIUM:
                return true;
        }
        return false;
    }

    /** UMessageConfig switch key -> remote feature id (null when the key is not a remote feature). */
    public static String featureOfKey(String key) {
        if (key == null) return null;
        switch (key) {
            case UMessageConfig.KEY_GHOST_MODE:
            case UMessageConfig.KEY_GHOST_BUTTON: return GHOST_MODE;
            case UMessageConfig.KEY_HIDDEN_CHATS: return HIDDEN_CHATS;
            case UMessageConfig.KEY_SAVE_EDITED: return SAVE_EDITED;
            case UMessageConfig.KEY_SAVE_DELETED: return SAVE_DELETED;
            case UMessageConfig.KEY_SAVE_VIEW_ONCE: return VIEW_ONCE_SAVE;
            case UMessageConfig.KEY_STORIES_ANONYMOUS: return STORIES_ANONYMOUS;
            case UMessageConfig.KEY_STORIES_HIDDEN: return STORIES_HIDDEN;
            case UMessageConfig.KEY_STORIES_DOWNLOAD: return STORIES_DOWNLOAD;
            case UMessageConfig.KEY_STOP_AUTODOWNLOAD: return STOP_AUTODOWNLOAD;
            case UMessageConfig.KEY_AUTO_REPLY: return AUTO_REPLY;
            case UMessageConfig.KEY_CONFIRM_STICKER: return CONFIRM_STICKER;
            case UMessageConfig.KEY_CONFIRM_VOICE: return CONFIRM_VOICE;
            case UMessageConfig.KEY_CONFIRM_GIF: return CONFIRM_GIF;
            case UMessageConfig.KEY_ROUND_FRONT_CAMERA: return ROUND_FRONT_CAMERA;
            case UMessageConfig.KEY_VOICE_INPUT: return VOICE_INPUT;
            case UMessageConfig.KEY_FOLDER_ICONS: return FOLDER_ICONS;
            case UMessageConfig.KEY_HIDE_FOLDER_TABS: return HIDE_FOLDER_TABS;
            case UMessageConfig.KEY_ADMIN_FOLDERS: return ADMIN_FOLDERS;
            case UMessageConfig.KEY_AUTO_APPROVE: return AUTO_APPROVE;
            case UMessageConfig.KEY_UNREAD_REMINDER: return UNREAD_REMINDER;
            case UMessageConfig.KEY_STRANGER_PROTECTION: return STRANGER_PROTECTION;
            case UMessageConfig.KEY_GROUP_SPAM_GUARD: return GROUP_SPAM_GUARD;
            case UMessageConfig.KEY_BLOCK_APK: return APK_BLOCK;
            case UMessageConfig.KEY_BLOCK_ADS: return BLOCK_ADS;
            case UMessageConfig.KEY_PROXY_FALLBACK: return PROXY_FALLBACK;
            case UMessageConfig.KEY_NEARBY_SHARE: return NEARBY_SHARE;
            case UMessageConfig.KEY_UM_PREMIUM: return UM_PREMIUM;
        }
        return null;
    }

    public static boolean isKeyEnabled(String key) {
        return isEnabled(featureOfKey(key));
    }

    // ---------------------------------------------------------------- screens of a feature

    /** The remote feature a screen belongs to, or null for ordinary screens. */
    public static String featureOf(BaseFragment f) {
        if (f == null) return null;
        if (f instanceof org.telegram.ui.UMessageFriendMapActivity) return FRIEND_MAP;
        if (f instanceof org.telegram.ui.UMessageShazamActivity) return SHAZAM;
        // all Instagram Business screens (their base class is package-private)
        if (f.getClass().getName().startsWith("org.telegram.ui.UMessageInstagram")) return INSTAGRAM_BUSINESS;
        if (f instanceof org.telegram.ui.UMessageChatTrashActivity) return CHAT_TRASH;
        if (f instanceof org.telegram.ui.UMessageFocusActivity) return FOCUS_MODE;
        if (f instanceof org.telegram.ui.UMessageParentalActivity
                || f instanceof org.telegram.ui.UMessageParentalChildActivity) return PARENTAL_CONTROL;
        if (f instanceof org.telegram.ui.UMessageTemplatesActivity
                || f instanceof org.telegram.ui.UMessageTemplateEditActivity) return TEMPLATES;
        if (f instanceof org.telegram.ui.UMessageDocumentReaderActivity) return DOCUMENT_READER;
        if (f instanceof org.telegram.ui.UMessageExportViewerActivity) return CHAT_EXPORT;
        if (f instanceof org.telegram.ui.UMessageUsernameActivity) return U_USERNAMES;
        if (f instanceof org.telegram.ui.UMessageOfficialChatActivity
                || f instanceof org.telegram.ui.UMessageOfficialProfileActivity) return OFFICIAL_CHAT;
        if (f instanceof org.telegram.ui.UMessageKeptMediaActivity) return VIEW_ONCE_SAVE;
        if (f instanceof org.telegram.ui.UMessageRoundEffectsActivity) return ROUND_EFFECTS;
        if (f instanceof org.telegram.ui.PrivacyGuardSettingsActivity) return PRIVACY_GUARD;
        if (f instanceof org.telegram.ui.UMessageLockActivity) return HIDDEN_CHATS;
        if (f instanceof org.telegram.ui.UMessageChatsActivity) {
            return ((org.telegram.ui.UMessageChatsActivity) f).isHiddenMode() ? HIDDEN_CHATS : STRANGER_PROTECTION;
        }
        if (f instanceof org.telegram.ui.DialogsActivity && ((org.telegram.ui.DialogsActivity) f).isUMessageHiddenMode()) return HIDDEN_CHATS;
        if (f instanceof org.telegram.ui.UMessageFeatureActivity) return featureOfKey(((org.telegram.ui.UMessageFeatureActivity) f).getFeatureKey());
        return null;
    }

    /** ActionBarLayout.presentFragment: a switched-off feature's screen is not opened from any entry point. */
    public static boolean allowFragment(BaseFragment f) {
        return isEnabled(featureOf(f));
    }

    // ---------------------------------------------------------------- polling

    public static void onAppResumed() {
        foreground = true;
        AndroidUtilities.cancelRunOnUIThread(poll);
        poll.run();
    }

    public static void onAppPaused() {
        foreground = false;
        AndroidUtilities.cancelRunOnUIThread(poll);
    }

    private static boolean configListening;

    private static void fetch() {
        // Safe Mode (stable_release) is re-checked together with the switches
        try {
            if (!configListening) {
                configListening = true;
                org.telegram.configuration.ConfigurationManager.addListener(() ->
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.umessageRemoteFlagsChanged));
            }
            org.telegram.configuration.ConfigurationManager.refresh();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (inFlight) return;
        inFlight = true;
        state();
        final long known = version;
        UMessageNet.run(() -> {
            try {
                final JSONObject r = request(known >= 0 ? URL_BASE + "&v=" + known : URL_BASE);
                if (r != null && r.has("v")) {
                    final long v = r.getLong("v");
                    final JSONArray list = r.optJSONArray("disabled");
                    if (list != null) {
                        final HashSet<String> next = new HashSet<>();
                        for (int i = 0; i < list.length(); i++) {
                            final String id = list.optString(i, null);
                            if (!TextUtils.isEmpty(id) && id.length() <= 40 && id.indexOf(',') < 0) next.add(id);
                        }
                        AndroidUtilities.runOnUIThread(() -> apply(v, next));
                    } else if (v != known) {
                        // { v } alone for a version we do not have (should not happen): ask for the full list next time
                        version = -1;
                    }
                }
            } catch (Throwable e) {
                // offline / server error: keep the last known state (fail-open)
                if (BuildVars.LOGS_ENABLED) FileLog.e(e);
            } finally {
                AndroidUtilities.runOnUIThread(() -> inFlight = false);
            }
        });
    }

    private static JSONObject request(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(UMessageNet.readCappedString(c.getInputStream(), 64 * 1024));
        } finally {
            c.disconnect();
        }
    }

    private static void apply(long v, Set<String> next) {
        final Set<String> prev = state();
        final boolean changed = !prev.equals(next);
        version = v;
        if (changed) disabled = Collections.unmodifiableSet(new HashSet<>(next));
        try {
            prefs().edit().putLong("v", v).putString("disabled", TextUtils.join(",", next)).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (!changed) return;
        final HashSet<String> turnedOff = new HashSet<>(next);
        turnedOff.removeAll(prev);
        final HashSet<String> turnedOn = new HashSet<>(prev);
        turnedOn.removeAll(next);
        onChanged(turnedOff, turnedOn);
    }

    /** UI thread: makes every open screen and running module follow the new state right away. */
    private static void onChanged(Set<String> turnedOff, Set<String> turnedOn) {
        UMessageConfig.onRemoteFlagsChanged();
        final HashSet<String> touched = new HashSet<>(turnedOff);
        touched.addAll(turnedOn);
        try {
            if (touched.contains(FRIEND_MAP)) UMessageFriendLocations.restartTracking();
            if (touched.contains(NEARBY_SHARE)) {
                if (turnedOff.contains(NEARBY_SHARE)) org.telegram.ui.UMessageNearbyShareManager.onActivityPaused();
                else if (LaunchActivity.instance != null && foreground) org.telegram.ui.UMessageNearbyShareManager.onActivityResumed(LaunchActivity.instance);
            }
            if (turnedOff.contains(SLEEP_TIMER) && UMessageSleepTimer.isActive()) UMessageSleepTimer.cancel();
            if (touched.contains(SLEEP_TIMER) && UMessageSleepTimer.listener != null) UMessageSleepTimer.listener.run();
            if (touched.contains(PARENTAL_CONTROL) && UMessageParental.lockListener != null) UMessageParental.lockListener.run();
            if (touched.contains(U_STORIES) || touched.contains(OFFICIAL_STORIES)) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) UMessageStories.getInstance(a).onRemoteFlagsChanged();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        UMessageConfig.refreshDialogs();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, 0);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.umessageRemoteFlagsChanged);
        closeDisabledScreens();
    }

    /** Finishes open screens of switched-off features (the top one animated, the ones below silently). */
    private static void closeDisabledScreens() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) return;
        final ArrayList<INavigationLayout> layouts = new ArrayList<>();
        layouts.add(activity.getActionBarLayout());
        layouts.add(activity.getRightActionBarLayout());
        layouts.add(activity.getLayersActionBarLayout());
        layouts.addAll(activity.sheetFragmentsStack);
        for (INavigationLayout layout : layouts) {
            if (layout == null || layout.getFragmentStack() == null) continue;
            final List<BaseFragment> stack = new ArrayList<>(layout.getFragmentStack());
            BaseFragment top = null;
            for (int i = stack.size() - 1; i >= 0; i--) {
                final BaseFragment f = stack.get(i);
                if (allowFragment(f)) continue;
                if (i == stack.size() - 1 && stack.size() > 1) {
                    top = f;
                } else if (stack.size() > 1) {
                    try { f.removeSelfFromStack(); } catch (Throwable e) { FileLog.e(e); }
                }
            }
            if (top != null) {
                try { top.finishFragment(); } catch (Throwable e) { FileLog.e(e); }
            }
        }
    }
}
