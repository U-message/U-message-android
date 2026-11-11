package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Consumer Instagram features: the profile link (umessage-premium-server /api/ig), official post/Reels
 * embed links, and Meta's "Sharing to Stories" intent. Only public, official Instagram surfaces are used.
 */
public final class UMessageInstagram {

    public static final String APP_PACKAGE = "com.instagram.android";

    private static final String API_URL = UMessagePremiumController.BASE_URL + "/api/ig";
    private static final long LINK_TTL = 10 * 60 * 1000;
    private static final long LINK_RETRY = 60 * 1000;
    private static final String SHARE_ACTION = "com.instagram.share.ADD_TO_STORY";
    private static final String SHARE_DIR = "umessage-instagram"; // cache-path in xml/provider_paths.xml
    private static final int SHARE_REQUEST = 0x4947;
    private static final long SHARE_PENDING_TTL = 10 * 60 * 1000;
    private static final long SHARE_FILE_TTL = 60 * 60 * 1000;
    private static final String KEY_SHARE_ENABLED = "ig_story_share";

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._]{1,30}$");
    private static final Pattern SHORTCODE = Pattern.compile("^[A-Za-z0-9_-]{5,64}$");

    private UMessageInstagram() {
    }

    public interface Callback {
        /** Runs on the UI thread; exactly one of the arguments is null. */
        void run(JSONObject result, String error);
    }

    // ------------------------------------------------------------------ API

    /** Authenticated POST /api/ig. Error is the server's code, "network" or "not_registered". */
    public static void call(int account, String action, JSONObject params, Callback callback) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final String token = UMessagePremiumController.getInstance().getToken(self);
        if (token == null) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null, "not_registered"));
            }
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject result = null;
            String error = null;
            try {
                final JSONObject body = params == null ? new JSONObject() : new JSONObject(params.toString());
                body.put("userId", String.valueOf(self));
                body.put("token", token);
                body.put("action", action);
                result = new JSONObject(UMessagePremiumController.httpPost(API_URL, body.toString()));
                if (result.has("error")) {
                    error = result.optString("error", "network");
                    result = null;
                }
            } catch (Throwable t) {
                result = null;
                error = "network";
            }
            if (callback != null) {
                final JSONObject r = result;
                final String e = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(r, e));
            }
        });
    }

    /** {connected, account:{...}, unread} */
    public static void status(int account, Callback callback) {
        call(account, "status", null, callback);
    }

    public static boolean canEditLink(int account) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_LINK)) return false;
        final UserConfig uc = UserConfig.getInstance(account);
        return uc.isClientActivated() && UMessagePremiumController.getInstance().getToken(uc.getClientUserId()) != null;
    }

    // ------------------------------------------------------------------ profile link

    public static final class Link {
        public final String username;
        public final boolean verified;

        Link(String username, boolean verified) {
            this.username = username;
            this.verified = verified;
        }
    }

    private static final class CacheEntry {
        Link link;
        long nextFetch;
        boolean loading;
        int version;
    }

    private static final ConcurrentHashMap<Long, CacheEntry> links = new ConcurrentHashMap<>();

    /** The cached link of a user, or null; refreshes it in the background when stale (updateInterfaces, 0 on change). */
    public static Link getLink(long userId) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_LINK)) return null;
        if (userId <= 0) {
            return null;
        }
        CacheEntry entry = links.get(userId);
        if (entry == null) {
            entry = new CacheEntry();
            links.put(userId, entry);
        }
        if (!entry.loading && SystemClock.elapsedRealtime() >= entry.nextFetch) {
            entry.loading = true;
            fetchLink(userId, entry, entry.version);
        }
        return entry.link;
    }

    private static void fetchLink(long userId, CacheEntry entry, int version) {
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            Link link = null;
            try {
                final JSONObject r = UMessagePremiumController.httpGet(API_URL + "?user=" + userId);
                if (!r.has("error")) {
                    ok = true;
                    final String name = r.isNull("username") ? null : r.optString("username", null);
                    if (isValidUsername(name)) {
                        link = new Link(name, r.optBoolean("verified"));
                    }
                }
            } catch (Throwable ignore) {
            }
            final boolean success = ok;
            final Link result = link;
            AndroidUtilities.runOnUIThread(() -> {
                entry.loading = false;
                entry.nextFetch = SystemClock.elapsedRealtime() + (success ? LINK_TTL : LINK_RETRY);
                if (success && version == entry.version) { // not older than our own set_link / clear_link
                    setCached(userId, entry, result);
                }
            });
        });
    }

    private static void setCached(long userId, CacheEntry entry, Link link) {
        final Link old = entry.link;
        entry.link = link;
        links.put(userId, entry);
        final boolean same = old == link || old != null && link != null && old.verified == link.verified && TextUtils.equals(old.username, link.username);
        if (!same) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, 0);
            }
        }
    }

    public static boolean isValidUsername(String name) {
        return name != null && USERNAME.matcher(name).matches() && !name.startsWith(".") && !name.endsWith(".") && !name.contains("..");
    }

    /** Accepts "name", "@name" or a pasted profile link; returns the plain username or null. */
    public static String normalizeUsername(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        final String lower = s.toLowerCase(Locale.ROOT);
        final int at = lower.indexOf("instagram.com/");
        if (at >= 0) {
            s = s.substring(at + "instagram.com/".length());
            final int end = indexOfAny(s, "/?#");
            if (end >= 0) {
                s = s.substring(0, end);
            }
        }
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        return isValidUsername(s) ? s : null;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    public static void setLink(int account, String username, Callback callback) {
        final JSONObject params = new JSONObject();
        try {
            params.put("username", username);
        } catch (Exception ignore) {
        }
        call(account, "set_link", params, (r, e) -> {
            if (r != null) {
                final String name = r.isNull("username") ? null : r.optString("username", username);
                updateOwn(account, isValidUsername(name) ? new Link(name, r.optBoolean("verified")) : null);
            }
            if (callback != null) {
                callback.run(r, e);
            }
        });
    }

    public static void clearLink(int account, Callback callback) {
        call(account, "clear_link", null, (r, e) -> {
            if (r != null) {
                updateOwn(account, null);
            }
            if (callback != null) {
                callback.run(r, e);
            }
        });
    }

    private static void updateOwn(int account, Link link) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        CacheEntry entry = links.get(self);
        if (entry == null) {
            entry = new CacheEntry();
        }
        entry.version++;
        entry.nextFetch = SystemClock.elapsedRealtime() + LINK_TTL;
        setCached(self, entry, link);
    }

    public static String profileUrl(String username) {
        return "https://www.instagram.com/" + username + "/";
    }

    /** Opens an instagram.com page in the Instagram app when it is installed, otherwise on the web. */
    public static void openExternally(Context context, String url) {
        if (context == null || url == null) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(APP_PACKAGE);
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable notInstalled) {
            Browser.openUrl(context, url);
        }
    }

    // ------------------------------------------------------------------ post / Reels embeds

    public static final class Embed {
        /** "p", "reel" or "tv" */
        public final String type;
        public final String shortcode;

        Embed(String type, String shortcode) {
            this.type = type;
            this.shortcode = shortcode;
        }

        public String pageUrl() {
            return "https://www.instagram.com/" + type + "/" + shortcode + "/";
        }

        public String embedPath() {
            return "/" + type + "/" + shortcode + "/embed/";
        }

        public String embedUrl() {
            return "https://www.instagram.com" + embedPath();
        }
    }

    /**
     * Strict parser: only https/http instagram.com /p/, /reel/, /reels/ and /tv/ links with a valid
     * shortcode give an Embed, and the URL that is loaded is always rebuilt from the two validated parts.
     */
    public static Embed parseEmbed(String url) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_LINK)) return null;
        if (url == null || url.length() > 512) {
            return null;
        }
        try {
            final String trimmed = url.trim();
            final URI uri = new URI(trimmed.contains("://") ? trimmed : "https://" + trimmed);
            final String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getHost() == null) {
                return null;
            }
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("instagram.com") && !host.equals("www.instagram.com") && !host.equals("m.instagram.com")) {
                return null;
            }
            final String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) {
                return null;
            }
            String[] parts = (path.endsWith("/") ? path.substring(1, path.length() - 1) : path.substring(1)).split("/", -1);
            if (parts.length != 2 || !SHORTCODE.matcher(parts[1]).matches()) {
                return null;
            }
            final String type = parts[0];
            final String code = parts[1];
            switch (type) {
                case "p":
                case "tv":
                    return new Embed(type, code);
                case "reel":
                    return new Embed("reel", code);
                case "reels":
                    // /reels/audio/ and /reels/videos/ are Instagram pages, not shortcodes
                    return "audio".equals(code) || "videos".equals(code) ? null : new Embed("reel", code);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ share to Instagram Stories

    private static final class PendingShare {
        final File file;
        final String mime;
        final int account;
        final long dialogId;
        final long time = SystemClock.elapsedRealtime();

        PendingShare(File file, String mime, int account, long dialogId) {
            this.file = file;
            this.mime = mime;
            this.account = account;
            this.dialogId = dialogId;
        }
    }

    private static PendingShare pendingShare;

    /** Instagram is installed, answers the official intent, and the Facebook App ID required by it is set. */
    public static boolean canShareStory() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_LINK)) return false;
        if (TextUtils.isEmpty(UMessageConfig.INSTAGRAM_FB_APP_ID)) {
            return false;
        }
        try {
            final Intent intent = new Intent(SHARE_ACTION);
            intent.setDataAndType(Uri.parse("content://" + ApplicationLoader.getApplicationId() + ".provider/check"), "image/jpeg");
            intent.setPackage(APP_PACKAGE);
            return ApplicationLoader.applicationContext.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage", Context.MODE_PRIVATE);
    }

    /** The story sheet remembers the last choice; off until the user turns it on. */
    public static boolean isStoryShareEnabled() {
        return prefs().getBoolean(KEY_SHARE_ENABLED, false);
    }

    public static void setStoryShareEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_SHARE_ENABLED, enabled).apply();
    }

    /**
     * Called on the UI thread once a story has been accepted by Telegram or the U message server: from
     * here on, leaving the app cannot interrupt that post. The final file is copied because the only
     * place the provider exposes for this is our own directory.
     */
    public static void onStoryPosted(int account, long dialogId, String path, boolean video) {
        if (path == null || !canShareStory()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final File source = new File(path);
                if (!source.isFile() || source.length() == 0) {
                    return;
                }
                final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), SHARE_DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return;
                }
                final File[] old = dir.listFiles();
                if (old != null) {
                    final long now = System.currentTimeMillis();
                    for (File f : old) {
                        if (now - f.lastModified() > SHARE_FILE_TTL) {
                            f.delete();
                        }
                    }
                }
                final File copy = new File(dir, "story_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
                if (!AndroidUtilities.copyFile(source, copy)) {
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    pendingShare = new PendingShare(copy, video ? "video/mp4" : "image/jpeg", account, dialogId);
                    launchPendingShare();
                });
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    /**
     * Opens Instagram for a posted story when that is possible: the app is visible and unlocked (Android
     * blocks background activity starts, so otherwise it waits for the next resume), and no other story
     * of the same batch is still uploading.
     */
    public static void launchPendingShare() {
        final PendingShare share = pendingShare;
        if (share == null) {
            return;
        }
        if (SystemClock.elapsedRealtime() - share.time > SHARE_PENDING_TTL) {
            pendingShare = null;
            share.file.delete();
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing() || ApplicationLoader.mainInterfacePaused || SharedConfig.isWaitingForPasscodeEnter) {
            return;
        }
        if (MessagesController.getInstance(share.account).getStoriesController().hasActiveUploads(share.dialogId)) {
            return;
        }
        pendingShare = null;
        try {
            final Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", share.file);
            final Intent intent = new Intent(SHARE_ACTION);
            intent.setDataAndType(uri, share.mime);
            intent.setPackage(APP_PACKAGE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra("source_application", UMessageConfig.INSTAGRAM_FB_APP_ID);
            // startActivityForResult as in Meta's sample: it gives Instagram the calling package
            activity.startActivityForResult(intent, SHARE_REQUEST);
        } catch (Throwable t) {
            FileLog.e(t);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, org.telegram.ui.Components.Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.UMessageInstagramShareFailed));
        }
    }
}
