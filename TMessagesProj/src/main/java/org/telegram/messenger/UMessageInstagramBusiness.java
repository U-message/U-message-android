package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Instagram Business/Creator tools of U message: every non-profile call of {@code POST /api/ig}, the
 * chunked publish pipeline and the channel auto-post. The server holds the Instagram token; this class
 * only ever sees the U message owner token. Nothing here logs tokens or message content.
 *
 * There is no push here: the unread count and auto-post are polled only while the app is open, and only
 * for accounts known to be connected, so ordinary users never call this endpoint.
 */
public final class UMessageInstagramBusiness {

    public static final String ERROR_NOT_CONFIGURED = "not_configured";
    public static final String ERROR_WINDOW_CLOSED = "window_closed";
    public static final String ERROR_NETWORK = "network";
    /** Auto-post was switched off by itself because the channel can no longer be posted to. */
    public static final String ERROR_NO_RIGHTS = "no_rights";

    public static final String KIND_IMAGE = "image";
    public static final String KIND_VIDEO = "video";
    public static final String KIND_REELS = "reels";
    public static final String KIND_STORY = "story";
    public static final String KIND_CAROUSEL = "carousel";

    public static final int CAROUSEL_MIN = 2;
    public static final int CAROUSEL_MAX = 10;
    public static final int CAPTION_MAX = 2200;

    private static final String ENDPOINT = UMessagePremiumController.BASE_URL + "/api/ig";
    private static final String PREFS = "umessage_instagram_business";

    // the contract caps the base64 "data" at 3.5 MB (and Vercel bodies at 4.5 MB): 2.6 MB raw is 3.47 MB encoded
    private static final int RAW_PART_BYTES = 2_600_000;
    private static final int PART_ATTEMPTS = 3;
    private static final long IMAGE_MAX_BYTES = 8L * 1024 * 1024; // Instagram's limit for a JPEG
    private static final int IMAGE_MAX_SIDE = 2880;
    private static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024; // the server's limit, known before uploading it all

    private static final long POLL_INTERVAL_MS = 2 * 60 * 1000;
    private static final long RESUME_THROTTLE_MS = 60 * 1000;
    private static final long PUBLISH_POLL_MS = 10000;
    private static final long PUBLISH_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int PUBLISH_MAX_NETWORK_ERRORS = 10;

    private static final int SEEN_IDS_MAX = 200;
    private static final int AUTO_POST_MAX_PER_RUN = 10;
    private static final int AUTO_POST_CAPTION_CHARS = 500;

    private static final UMessageInstagramBusiness[] instances = new UMessageInstagramBusiness[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageInstagramBusiness getInstance(int account) {
        synchronized (instances) {
            if (instances[account] == null) {
                instances[account] = new UMessageInstagramBusiness(account);
            }
            return instances[account];
        }
    }

    private final int account;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long lastPoll;
    private boolean pollInFlight;
    private boolean autoPostInFlight;
    private int autoPostGeneration; // a reply from before the last on/off switch is ignored
    private PublishTask publishTask;

    private UMessageInstagramBusiness(int account) {
        this.account = account;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private long selfId() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    /** Preference key of the Telegram user on this account (account slots can change owner). */
    private String key(String name) {
        return name + "_" + selfId();
    }

    // ---------- Requests ----------

    /** Runs one action; the callback is on the UI thread and {@code error} is a server code or "network". */
    public void call(String action, JSONObject params, UMessagePremiumController.Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            final Reply reply = callBlocking(action, params);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(reply.result, reply.error));
            }
        });
    }

    private static final class Reply {
        JSONObject result;
        String error;
    }

    private Reply callBlocking(String action, JSONObject params) {
        final Reply reply = new Reply();
        try {
            final long self = selfId();
            final String token = UMessagePremiumController.getInstance().getToken(self);
            if (self == 0 || token == null) {
                reply.error = "bad_token";
                AndroidUtilities.runOnUIThread(() -> UMessagePremiumController.getInstance().register(account));
                return reply;
            }
            final JSONObject body = new JSONObject();
            if (params != null) {
                final java.util.Iterator<String> keys = params.keys();
                while (keys.hasNext()) {
                    final String k = keys.next();
                    body.put(k, params.get(k));
                }
            }
            body.put("userId", String.valueOf(self));
            body.put("token", token);
            body.put("action", action);
            final JSONObject r = new JSONObject(post(body.toString()));
            reply.result = r;
            if (r.has("error")) {
                reply.error = r.optString("error", "error");
                if ("bad_token".equals(reply.error)) {
                    AndroidUtilities.runOnUIThread(() -> UMessagePremiumController.getInstance().register(account));
                }
            }
        } catch (Throwable t) {
            reply.error = ERROR_NETWORK;
        }
        return reply;
    }

    private static String post(String json) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000); // a media part or a publish call is much slower than the premium calls
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            final byte[] bytes = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            final InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) {
                throw new java.io.IOException("empty");
            }
            try (InputStream in = is) {
                final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject params(Object... pairs) {
        final JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (pairs[i + 1] != null) {
                    o.put((String) pairs[i], pairs[i + 1]);
                }
            }
        } catch (Exception ignore) {
        }
        return o;
    }

    public void connectUrl(UMessagePremiumController.Callback cb) {
        connectingAccount = account;
        call("connect_url", null, cb);
    }

    private static volatile int connectingAccount = -1;

    /** The last step of connecting: umessage://instagram/connect?code=..., opened from the page Instagram's login ends on. */
    public static boolean handleConnectIntent(android.content.Intent intent) {
        final android.net.Uri data = intent == null ? null : intent.getData();
        if (data == null || !"umessage".equals(data.getScheme()) || !"instagram".equals(data.getHost())) {
            return false;
        }
        final String code = data.getQueryParameter("code");
        if (!android.text.TextUtils.isEmpty(code)) {
            final int a = connectingAccount >= 0 ? connectingAccount : UserConfig.selectedAccount;
            final UMessageInstagramBusiness api = getInstance(a);
            api.call("connect_finish", params("code", code), (r, e) -> {
                if (e != null) {
                    android.widget.Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("wrong_account".equals(e) ? R.string.UMessageInstagramConnectWrongAccount : R.string.UMessageInstagramConnectExpired), android.widget.Toast.LENGTH_LONG).show();
                }
                api.poll(0);
            });
        }
        return true;
    }

    public void conversations(String cursor, UMessagePremiumController.Callback cb) {
        call("conversations", params("cursor", cursor), cb);
    }

    public void messages(String conversationId, String cursor, UMessagePremiumController.Callback cb) {
        call("messages", params("conversationId", conversationId, "cursor", cursor), cb);
    }

    public void send(String recipientId, String text, UMessagePremiumController.Callback cb) {
        call("send", params("recipientId", recipientId, "text", text), cb);
    }

    public void media(String cursor, UMessagePremiumController.Callback cb) {
        call("media", params("cursor", cursor), cb);
    }

    public void comments(String mediaId, String cursor, UMessagePremiumController.Callback cb) {
        call("comments", params("mediaId", mediaId, "cursor", cursor), cb);
    }

    public void commentReply(String commentId, String text, UMessagePremiumController.Callback cb) {
        call("comment_reply", params("commentId", commentId, "text", text), cb);
    }

    public void commentHide(String commentId, boolean hidden, UMessagePremiumController.Callback cb) {
        call("comment_hide", params("commentId", commentId, "hidden", hidden), cb);
    }

    public void commentDelete(String commentId, UMessagePremiumController.Callback cb) {
        call("comment_delete", params("commentId", commentId), cb);
    }

    public void insights(String period, UMessagePremiumController.Callback cb) {
        call("insights", params("period", period), cb);
    }

    public void mediaInsights(String mediaId, UMessagePremiumController.Callback cb) {
        call("media_insights", params("mediaId", mediaId), cb);
    }

    // ---------- Status and unread count ----------

    public boolean isConnectedCached() {
        return prefs().getBoolean(key("connected"), false);
    }

    public String getUsernameCached() {
        return prefs().getString(key("username"), null);
    }

    public int getUnread() {
        return prefs().getInt(key("unread"), 0);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Asks the server for the account state; connection and unread caches follow the reply. */
    public void status(UMessagePremiumController.Callback callback) {
        call("status", null, (r, error) -> {
            if (error == null && r != null) {
                applyStatus(r);
            } else if ("not_connected".equals(error)) {
                applyStatus(new JSONObject()); // the token died: same as a disconnected account
            }
            if (callback != null) {
                callback.run(r, error);
            }
        });
    }

    private void applyStatus(JSONObject r) {
        final boolean connected = r.optBoolean("connected", false);
        final JSONObject acc = r.optJSONObject("account");
        final String igId = acc == null ? null : acc.optString("id", null);
        final SharedPreferences p = prefs();
        final SharedPreferences.Editor e = p.edit();
        e.putBoolean(key("connected"), connected);
        e.putInt(key("unread"), connected ? r.optInt("unread", 0) : 0);
        e.putString(key("username"), connected && acc != null ? acc.optString("username", null) : null);
        // after any gap or account change the next run takes a new baseline: what was published meanwhile is history
        final String autoPostAccount = p.getString(key("auto_ig_id"), null);
        if (!connected || (igId != null && autoPostAccount != null && !igId.equals(autoPostAccount))) {
            e.putBoolean(key("auto_baselined"), false);
        }
        if (connected && igId != null) {
            e.putString(key("auto_ig_id"), igId);
        }
        e.commit();
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    /** Called when the app comes to the foreground: every connected account is refreshed, then polled. */
    public static void onAppResumed() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_BUSINESS)) { onAppPaused(); return; }
        AndroidUtilities.cancelRunOnUIThread(pollLoop);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                getInstance(a).poll(RESUME_THROTTLE_MS);
            }
        }
        AndroidUtilities.runOnUIThread(pollLoop, POLL_INTERVAL_MS);
    }

    public static void onAppPaused() {
        AndroidUtilities.cancelRunOnUIThread(pollLoop);
    }

    private static final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    getInstance(a).poll(POLL_INTERVAL_MS / 2);
                }
            }
            AndroidUtilities.runOnUIThread(this, POLL_INTERVAL_MS);
        }
    };

    private void poll(long minInterval) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (!isConnectedCached() || pollInFlight || (lastPoll != 0 && now - lastPoll < minInterval)) {
            return;
        }
        lastPoll = now;
        pollInFlight = true;
        status((r, error) -> {
            pollInFlight = false;
            if (error == null && isConnectedCached()) {
                runAutoPost();
            }
        });
    }

    /** The user disconnected: nothing keeps running for this account. */
    public void disconnect(UMessagePremiumController.Callback callback) {
        call("disconnect", null, (r, error) -> {
            if (error == null) {
                prefs().edit()
                    .putBoolean(key("connected"), false)
                    .putInt(key("unread"), 0)
                    .remove(key("username"))
                    .putBoolean(key("auto_enabled"), false)
                    .putBoolean(key("auto_baselined"), false)
                    .remove(key("auto_last_id"))
                    .remove(key("auto_seen"))
                    .remove(key("auto_ig_id"))
                    .commit();
                notifyListeners();
            }
            if (callback != null) {
                callback.run(r, error);
            }
        });
    }

    // ---------- Auto-post ----------

    public boolean isAutoPostEnabled() {
        return prefs().getBoolean(key("auto_enabled"), false);
    }

    /** The chosen channel's dialog id (negative), or 0. */
    public long getAutoPostDialog() {
        return prefs().getLong(key("auto_dialog"), 0);
    }

    public int getAutoPostCount() {
        return prefs().getInt(key("auto_count"), 0);
    }

    /** Reason auto-post stopped by itself ({@link #ERROR_NO_RIGHTS}), or null. */
    public String getAutoPostProblem() {
        return prefs().getString(key("auto_problem"), null);
    }

    public void setAutoPostDialog(long dialogId) {
        prefs().edit().putLong(key("auto_dialog"), dialogId).remove(key("auto_problem")).commit();
    }

    /**
     * Turning on always starts from a new baseline, so neither the existing history nor what was
     * published while it was off reaches the channel.
     */
    public void setAutoPostEnabled(boolean enabled, Runnable done) {
        final int generation = ++autoPostGeneration;
        final SharedPreferences.Editor e = prefs().edit()
            .putBoolean(key("auto_enabled"), enabled)
            .putBoolean(key("auto_baselined"), false)
            .remove(key("auto_last_id"))
            .remove(key("auto_seen"))
            .remove(key("auto_problem"));
        e.commit();
        if (enabled) {
            call("new_media", null, (r, error) -> {
                if (error == null && r != null && generation == autoPostGeneration && isAutoPostEnabled() && !prefs().getBoolean(key("auto_baselined"), false)) {
                    takeBaseline(r);
                }
                // on failure the first poll takes the baseline: an un-baselined account never posts
                if (done != null) {
                    done.run();
                }
            });
        } else if (done != null) {
            done.run();
        }
    }

    private void takeBaseline(JSONObject r) {
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final JSONArray items = r.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item != null && !TextUtils.isEmpty(item.optString("id"))) {
                    seen.add(item.optString("id"));
                }
            }
        }
        final String lastId = jsonString(r, "lastId");
        if (lastId != null) {
            seen.add(lastId);
        }
        prefs().edit()
            .putBoolean(key("auto_baselined"), true)
            .putString(key("auto_last_id"), lastId)
            .putString(key("auto_seen"), TextUtils.join(",", seen))
            .putLong(key("auto_since"), System.currentTimeMillis() / 1000)
            .commit();
    }

    private void runAutoPost() {
        if (!isAutoPostEnabled() || autoPostInFlight || getAutoPostDialog() == 0) {
            return;
        }
        autoPostInFlight = true;
        final int generation = autoPostGeneration;
        final boolean baselined = prefs().getBoolean(key("auto_baselined"), false);
        final String since = baselined ? prefs().getString(key("auto_last_id"), null) : null;
        call("new_media", params("sinceId", since), (r, error) -> {
            autoPostInFlight = false;
            if (error != null || r == null || generation != autoPostGeneration || !isAutoPostEnabled()) {
                return;
            }
            if (!baselined) {
                takeBaseline(r);
            } else {
                postNewItems(r);
            }
        });
    }

    private void postNewItems(JSONObject r) {
        final long dialogId = getAutoPostDialog();
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null) {
            return; // chats are not loaded yet right after the start: nothing is marked, the next poll retries
        }
        if (!ChatObject.canPost(chat)) {
            prefs().edit().putBoolean(key("auto_enabled"), false).putString(key("auto_problem"), ERROR_NO_RIGHTS).commit();
            notifyListeners();
            return;
        }
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final String stored = prefs().getString(key("auto_seen"), "");
        if (!TextUtils.isEmpty(stored)) {
            seen.addAll(Arrays.asList(stored.split(",")));
        }
        final long since = prefs().getLong(key("auto_since"), 0);
        final ArrayList<JSONObject> fresh = new ArrayList<>();
        final JSONArray items = r.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                final String id = item.optString("id");
                if (TextUtils.isEmpty(id) || seen.contains(id)) continue;
                seen.add(id);
                // a deleted sinceId can make the server answer with old posts: older than the baseline is history
                final long time = timeSeconds(item, "time");
                if (time != 0 && time < since - 300) continue;
                if (TextUtils.isEmpty(item.optString("permalink"))) continue;
                fresh.add(item);
            }
        }
        Collections.sort(fresh, (a, b) -> Long.compare(timeSeconds(a, "time"), timeSeconds(b, "time")));
        final List<JSONObject> toPost = fresh.size() > AUTO_POST_MAX_PER_RUN ? fresh.subList(fresh.size() - AUTO_POST_MAX_PER_RUN, fresh.size()) : fresh;

        final ArrayList<String> kept = new ArrayList<>(seen);
        final List<String> tail = kept.size() > SEEN_IDS_MAX ? kept.subList(kept.size() - SEEN_IDS_MAX, kept.size()) : kept;
        final String lastId = jsonString(r, "lastId");
        final SharedPreferences.Editor e = prefs().edit().putString(key("auto_seen"), TextUtils.join(",", tail));
        if (lastId != null) {
            e.putString(key("auto_last_id"), lastId);
        }
        e.putInt(key("auto_count"), getAutoPostCount() + toPost.size());
        // written before anything is sent: a crash can lose a post but never send it twice
        e.commit();

        for (JSONObject item : toPost) {
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(postText(item), dialogId));
        }
        if (!toPost.isEmpty()) {
            notifyListeners();
        }
    }

    private static String postText(JSONObject item) {
        String caption = item.optString("caption", "").trim();
        if (caption.length() > AUTO_POST_CAPTION_CHARS) {
            caption = caption.substring(0, AUTO_POST_CAPTION_CHARS).trim() + "…";
        }
        final String link = item.optString("permalink");
        return caption.isEmpty() ? link : caption + "\n\n" + link;
    }

    private static String jsonString(JSONObject o, String name) {
        if (o.isNull(name)) {
            return null;
        }
        final String v = o.optString(name, null);
        return TextUtils.isEmpty(v) ? null : v;
    }

    // ---------- Publish ----------

    /** The publish running on this account, kept alive when its screen is closed; or null. */
    public PublishTask getPublishTask() {
        return publishTask;
    }

    public PublishTask startPublish(String kind, ArrayList<Uri> uris, String caption, Runnable onChange) {
        final PublishTask task = new PublishTask(kind, uris, caption);
        task.listener = onChange;
        publishTask = task;
        // the upload blocks for minutes: the shared queues must not wait for it
        new Thread(task, "umessage-ig-publish").start();
        return task;
    }

    public void clearPublishTask(PublishTask task) {
        if (publishTask == task) {
            publishTask = null;
        }
    }

    public final class PublishTask implements Runnable {
        public static final int STATE_UPLOADING = 0;
        public static final int STATE_PROCESSING = 1;
        public static final int STATE_PUBLISHED = 2;
        public static final int STATE_ERROR = 3;

        public volatile int state = STATE_UPLOADING;
        public volatile float progress;
        public volatile String error;
        public volatile String permalink;
        public volatile Runnable listener;
        private volatile boolean cancelled;

        private final String kind;
        private final ArrayList<Uri> uris;
        private final String caption;
        private final ArrayList<File> tempFiles = new ArrayList<>();

        PublishTask(String kind, ArrayList<Uri> uris, String caption) {
            this.kind = kind;
            this.uris = new ArrayList<>(uris);
            this.caption = caption;
        }

        public boolean isRunning() {
            return state == STATE_UPLOADING || state == STATE_PROCESSING;
        }

        /** Only stops the upload: once publish has been requested, Instagram finishes on its own. */
        public void cancel() {
            cancelled = true;
        }

        private void changed() {
            AndroidUtilities.runOnUIThread(() -> {
                final Runnable l = listener;
                if (l != null) {
                    l.run();
                }
            });
        }

        private void fail(String code) {
            error = code;
            state = STATE_ERROR;
            changed();
        }

        @Override
        public void run() {
            try {
                final JSONArray urls = new JSONArray();
                final int count = uris.size();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    final String url = uploadFile(uris.get(i), p -> {
                        progress = (index + p) / count;
                        changed();
                    });
                    if (url == null) {
                        return; // fail() was called
                    }
                    urls.put(url);
                }
                state = STATE_PROCESSING;
                changed();
                final Reply reply = callBlocking("publish", params("kind", kind, "urls", urls, "caption", TextUtils.isEmpty(caption) ? null : caption));
                if (reply.error != null) {
                    // never retried by itself: a lost reply could mean it is already on its way to Instagram
                    fail(reply.error);
                    return;
                }
                pollStatus(reply.result.optString("jobId"));
            } catch (Throwable t) {
                FileLog.e(t);
                fail("error");
            } finally {
                for (File f : tempFiles) {
                    f.delete();
                }
            }
        }

        private void pollStatus(String jobId) throws InterruptedException {
            final long deadline = android.os.SystemClock.elapsedRealtime() + PUBLISH_TIMEOUT_MS;
            int networkErrors = 0;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(PUBLISH_POLL_MS);
                final Reply reply = callBlocking("publish_status", params("jobId", jobId));
                if (reply.error != null) {
                    if (ERROR_NETWORK.equals(reply.error) && ++networkErrors < PUBLISH_MAX_NETWORK_ERRORS) {
                        continue;
                    }
                    fail(reply.error);
                    return;
                }
                networkErrors = 0;
                final String s = reply.result.optString("state");
                if ("published".equals(s)) {
                    permalink = jsonString(reply.result, "permalink");
                    state = STATE_PUBLISHED;
                    changed();
                    return;
                }
                if ("error".equals(s)) {
                    final String code = jsonString(reply.result, "error");
                    fail(code == null ? "error" : code);
                    return;
                }
            }
            fail("timeout");
        }

        /** Returns the public URL, or null after calling {@link #fail}. */
        private String uploadFile(Uri uri, Utilities.Callback<Float> onProgress) throws Exception {
            final Context context = ApplicationLoader.applicationContext;
            final ContentResolver resolver = context.getContentResolver();
            String mime = resolver.getType(uri);
            if (mime == null) {
                mime = "";
            }
            File file = null;
            if (mime.startsWith("video/")) {
                if (!"video/mp4".equals(mime) && !"video/quicktime".equals(mime)) {
                    fail("bad_format");
                    return null;
                }
            } else {
                final long size = querySize(resolver, uri);
                if (!"image/jpeg".equals(mime) || size <= 0 || size > IMAGE_MAX_BYTES) {
                    // Instagram accepts only JPEG: anything else, or a too large one, is converted here
                    file = toJpeg(resolver, uri);
                    if (file == null) {
                        fail("bad_format");
                        return null;
                    }
                    tempFiles.add(file);
                    mime = "image/jpeg";
                }
            }
            long size = file != null ? file.length() : querySize(resolver, uri);
            if (size <= 0 && file == null) {
                // the provider does not know the size, and "total" needs it
                file = copyToCache(resolver, uri);
                tempFiles.add(file);
                size = file.length();
            }
            if (size <= 0) {
                fail("bad_format");
                return null;
            }
            if (mime.startsWith("video/") && size > VIDEO_MAX_BYTES) {
                fail("too_big");
                return null;
            }
            final int total = (int) ((size + RAW_PART_BYTES - 1) / RAW_PART_BYTES);
            try (InputStream in = file != null ? new FileInputStream(file) : resolver.openInputStream(uri)) {
                if (in == null) {
                    fail("bad_format");
                    return null;
                }
                final byte[] buffer = new byte[RAW_PART_BYTES];
                String uploadId = null;
                for (int index = 0; index < total; index++) {
                    if (cancelled) {
                        fail("cancelled");
                        return null;
                    }
                    int read = 0;
                    while (read < RAW_PART_BYTES) {
                        final int n = in.read(buffer, read, RAW_PART_BYTES - read);
                        if (n < 0) break;
                        read += n;
                    }
                    if (read == 0) {
                        fail("bad_format");
                        return null;
                    }
                    final String data = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                    Reply reply = null;
                    for (int attempt = 0; attempt < PART_ATTEMPTS; attempt++) {
                        if (attempt > 0) {
                            Thread.sleep(1500L * attempt);
                        }
                        reply = callBlocking("upload_part", params("uploadId", uploadId, "index", index, "total", total, "mime", mime, "data", data));
                        if (!ERROR_NETWORK.equals(reply.error)) {
                            break;
                        }
                    }
                    if (reply.error != null) {
                        fail(reply.error);
                        return null;
                    }
                    uploadId = jsonString(reply.result, "uploadId");
                    onProgress.run((index + 1f) / total);
                    final String url = jsonString(reply.result, "url");
                    if (reply.result.optBoolean("done") || index == total - 1) {
                        if (url == null) {
                            fail("error");
                        }
                        return url;
                    }
                }
            }
            fail("error");
            return null;
        }

        private File copyToCache(ContentResolver resolver, Uri uri) throws Exception {
            final File out = File.createTempFile("ig_upload", ".tmp", ApplicationLoader.applicationContext.getCacheDir());
            try (InputStream in = resolver.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
                final byte[] buf = new byte[65536];
                int n;
                while (in != null && (n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out;
        }
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignore) {
        }
        return -1;
    }

    /** Decodes any picked image (rotation applied, transparency on white) into a JPEG under Instagram's limit. */
    private static File toJpeg(ContentResolver resolver, Uri uri) {
        Bitmap bitmap = null;
        try {
            bitmap = decode(resolver, uri);
            if (bitmap == null) {
                return null;
            }
            final File out = File.createTempFile("ig_upload", ".jpg", ApplicationLoader.applicationContext.getCacheDir());
            for (int quality = 92; quality >= 60; quality -= 12) {
                try (OutputStream os = new FileOutputStream(out)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os);
                }
                if (out.length() <= IMAGE_MAX_BYTES) {
                    return out;
                }
            }
            out.delete();
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
        return null;
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri) throws Exception {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_SIDE) {
            sample *= 2;
        }
        Bitmap decoded;
        if (Build.VERSION.SDK_INT >= 28) {
            final int finalSample = sample;
            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(finalSample);
            });
        } else {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = resolver.openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, options);
            }
            if (decoded != null && Build.VERSION.SDK_INT >= 24) {
                try (InputStream in = resolver.openInputStream(uri)) {
                    final int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    final int rotation = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                    if (rotation != 0) {
                        final Matrix m = new Matrix();
                        m.postRotate(rotation);
                        final Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                        if (rotated != decoded) {
                            decoded.recycle();
                        }
                        decoded = rotated;
                    }
                } catch (Exception ignore) {
                }
            }
        }
        if (decoded == null || !decoded.hasAlpha()) {
            return decoded;
        }
        final Bitmap flat = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(decoded, 0, 0, null);
        decoded.recycle();
        return flat;
    }

    // ---------- Helpers ----------

    /** A server time as unix seconds: accepts seconds, milliseconds or Meta's ISO-8601 text; 0 when absent. */
    public static long timeSeconds(JSONObject o, String name) {
        final Object v = o.opt(name);
        if (v instanceof Number) {
            return normalizeSeconds(((Number) v).longValue());
        }
        if (v instanceof String) {
            final String s = (String) v;
            try {
                return normalizeSeconds(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
            try {
                final String iso = s.replace("Z", "+0000").replaceAll("\\.\\d+", "").replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");
                final Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(iso);
                return d == null ? 0 : d.getTime() / 1000;
            } catch (Exception ignore) {
            }
        }
        return 0;
    }

    private static long normalizeSeconds(long value) {
        return value > 100_000_000_000L ? value / 1000 : value;
    }
}
