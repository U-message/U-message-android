package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.Stories.StoriesController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * U message stories: when the Telegram story limit is used up, a story is posted to our own server
 * (umessage-premium-server /api/stories, media in Cloudflare R2) and shown only to U message users,
 * inside the normal Telegram story bar and viewer. They live 24h (48h at most), are never pinned to the
 * profile, and their author can always see who viewed them.
 * A U story item has an id >= {@link #BASE_ID} and its media in {@code attachPath} (a local file).
 */
public class UMessageStories implements NotificationCenter.NotificationCenterDelegate {

    public static final int BASE_ID = 1000000000;
    /**
     * Local-only peer of the official "U message" account that admin stories are shown under, like
     * Telegram's own service stories. Never a real Telegram user; treated as a service user (no replies).
     */
    public static final long OFFICIAL_ID = 9000777000000L;
    private static final int VIEWED_TTL = 24 * 3600;
    private static final String URL = UMessagePremiumController.BASE_URL + "/api/stories";
    private static final long REFRESH_MS = 3 * 60 * 1000;
    private static final int MAX_PERIOD = 48 * 3600;
    /** Story media size limits (server: photo 10 MB, video 60 MB), with a little room. */
    private static final long MAX_PHOTO_BYTES = 11L << 20, MAX_VIDEO_BYTES = 61L << 20;
    private static final long PART_SIZE_MIN = 1L << 20;
    /** Server deletes that did not go through yet, per Telegram user id; survive restarts and logout. */
    private static final String PENDING_PREFS = "umessage_stories_pending";

    private static final UMessageStories[] instances = new UMessageStories[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageStories getInstance(int account) {
        UMessageStories s = instances[account];
        if (s == null) {
            synchronized (UMessageStories.class) {
                s = instances[account];
                if (s == null) instances[account] = s = new UMessageStories(account);
            }
        }
        return s;
    }

    /** Logout / slot reuse: the feed prefs, media cache and instance are only a cache, so they are wiped. */
    static void resetAccount(int account) {
        final UMessageStories old;
        synchronized (UMessageStories.class) {
            old = instances[account];
            instances[account] = null;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (old != null) {
                AndroidUtilities.cancelRunOnUIThread(old.refreshRunnable);
                NotificationCenter.getInstance(account).removeObserver(old, NotificationCenter.storiesUpdated);
            }
        });
        try {
            ApplicationLoader.applicationContext.getSharedPreferences("umessage_stories_" + account, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        } catch (Exception e) {
            FileLog.e(e);
        }
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "umstories" + account);
        Utilities.globalQueue.postRunnable(() -> {
            final File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        });
    }

    public static boolean isU(TL_stories.StoryItem item) {
        return item != null && item.id >= BASE_ID;
    }

    /** Only registered U message users (they have a server token) can post U stories; reading is open. */
    public static boolean available(int account) {
        final UserConfig uc = UserConfig.getInstance(account);
        return uc.isClientActivated() && UMessagePremiumController.getInstance().getToken(uc.getClientUserId()) != null;
    }

    /**
     * Posting U stories is only for accounts without real Telegram Premium: Premium has Telegram's own
     * higher story limit, so past it the normal Telegram limit sheet stays. Viewing works for everyone.
     */
    public static boolean canPost(int account) {
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.U_STORIES)) return false;
        // the real Telegram flag: the local "Telegram Premium" option does not raise Telegram's own story limit
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        return available(account) && (self == null || !self.premium);
    }

    public interface ResultCallback {
        void run(JSONObject result, String error);
    }

    private final int account;
    /** owner -> active U stories with downloaded media */
    private final LongSparseArrayCompat stories = new LongSparseArrayCompat();
    private final HashSet<Long> injected = new HashSet<>();
    private final HashSet<String> downloading = new HashSet<>();
    private Set<String> read;
    /** network work off the shared globalQueue: uploads can take minutes, feed/view calls must not wait behind them */
    private static final DispatchQueue uploadQueue = new DispatchQueue("umStoriesUp");
    private static final DispatchQueue netQueue = new DispatchQueue("umStories");
    private boolean loading;
    private long lastLoad;
    private boolean observing;
    private final Runnable refreshRunnable = () -> refresh(false);
    private boolean feedRestored;
    /** Several stories download at once instead of one after another on the global queue. */
    private static final java.util.concurrent.ExecutorService downloads = java.util.concurrent.Executors.newFixedThreadPool(3);

    /** False once {@link #resetAccount} dropped this instance (logout): late network callbacks must not persist anything. */
    private boolean alive() {
        return instances[account] == this;
    }

    private UMessageStories(int account) {
        this.account = account;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_stories_" + account, android.content.Context.MODE_PRIVATE);
    }

    private File dir() {
        final File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "umstories" + account);
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ feed

    /** Loads U stories of contacts and chat partners; called with the Telegram story list and on a timer. */
    public void refresh(boolean force) {
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        AndroidUtilities.runOnUIThread(refreshRunnable, REFRESH_MS);
        if (!observing) {
            observing = true;
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.storiesUpdated);
        }
        // Reading is open to every activated account: without the key the server serves the official stories
        final boolean activated = UserConfig.getInstance(account).isClientActivated();
        if (!feedRestored && activated) {
            feedRestored = true;
            restoreFeed();
        }
        if (loading || !activated || !force && System.currentTimeMillis() - lastLoad < 30000) return;
        loading = true;
        retryPendingDeletes();
        // No contact / chat list goes to the server: it answers from the audiences owners chose at publish,
        // matched against this (token-authenticated) viewer.
        call("feed", new JSONObject(), (r, error) -> {
            loading = false;
            if (error != null || r == null || !alive()) return;
            lastLoad = System.currentTimeMillis();
            saveFeed(r);
            onFeed(r.optJSONArray("stories"), r.optJSONArray("official"));
        });
    }

    /** The last feed is kept on disk, so after a restart the story row shows at once from cached media. */
    private void saveFeed(JSONObject r) {
        if (!alive()) return;
        try {
            final JSONObject o = new JSONObject();
            o.put("stories", r.optJSONArray("stories"));
            o.put("official", r.optJSONArray("official"));
            prefs().edit().putString("feed", o.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private void restoreFeed() {
        final String raw = prefs().getString("feed", null);
        if (raw == null) return;
        try {
            final JSONObject o = new JSONObject(raw);
            onFeed(o.optJSONArray("stories"), o.optJSONArray("official"));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The official account exists only in memory, so it is put back before every use. */
    private void ensureOfficialUser() {
        final MessagesController mc = MessagesController.getInstance(account);
        if (mc.getUser(OFFICIAL_ID) != null) return;
        final TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = OFFICIAL_ID;
        user.first_name = "U message";
        user.verified = true;
        user.flags |= 2;
        mc.putUser(user, false);
    }

    /** A remote feature switch (U stories / official stories) changed: the cached feed is applied again. */
    public void onRemoteFlagsChanged() {
        if (alive() && UserConfig.getInstance(account).isClientActivated()) restoreFeed();
    }

    private void onFeed(JSONArray list, JSONArray official) {
        // switched off from the admin panel: those stories are not shown (cached media is kept for re-enabling)
        final boolean filtered = !UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.U_STORIES) || !UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.OFFICIAL_STORIES);
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.U_STORIES)) list = null;
        if (!UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.OFFICIAL_STORIES)) official = null;
        final long self = UserConfig.getInstance(account).getClientUserId();
        final LongSparseArrayCompat next = new LongSparseArrayCompat();
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        if (official != null && official.length() > 0) ensureOfficialUser();
        // official stories first, so their media starts downloading before the others
        final int officialCount = official == null ? 0 : official.length();
        final int total = officialCount + (list == null ? 0 : list.length());
        final HashSet<String> keep = new HashSet<>();
        final java.util.Set<String> deleting = pendingPrefs().getStringSet("del_" + self, new HashSet<>());
        for (int i = 0; i < total; i++) {
            final boolean isOfficial = i < officialCount;
            final JSONObject o = isOfficial ? official.optJSONObject(i) : list.optJSONObject(i - officialCount);
            if (o == null || o.optInt("expire") <= now) continue;
            long owner;
            try {
                owner = isOfficial ? OFFICIAL_ID : Long.parseLong(o.optString("owner", "0"));
            } catch (NumberFormatException e) {
                continue; // one malformed item must not drop the whole feed
            }
            final int id = o.optInt("id");
            if (owner <= 0 || id < BASE_ID) continue;
            if (owner == self && deleting.contains(String.valueOf(id))) continue; // deleted here, server not confirmed yet
            final boolean video = "video".equals(o.optString("type"));
            final File file = new File(dir(), owner + "_" + id + (video ? ".mp4" : ".jpg"));
            keep.add(file.getName());
            if (!file.exists()) {
                download(o.optJSONArray("urls"), file);
                continue;
            }
            final TL_stories.StoryItem item = findItem(owner, id);
            final TL_stories.StoryItem story = item != null ? item : makeItem(owner, id, o, file);
            if (isOfficial) story.expire_date = Math.min(story.expire_date, o.optInt("expire")); // per-user: first view + 24h
            if (owner != self && o.optBoolean("read")) markReadLocal(owner, id);
            next.add(owner, story);
        }
        stories.replaceWith(next);
        if (!filtered) cleanupFiles(keep);
        if (stories.size() > 0 || !injected.isEmpty()) loadSelfViews();
        inject();
    }

    private TL_stories.StoryItem makeItem(long owner, int id, JSONObject o, File file) {
        final TL_stories.TL_storyItem item = new TL_stories.TL_storyItem();
        item.id = id;
        item.dialogId = owner;
        item.date = o.optInt("date");
        item.expire_date = o.optInt("expire");
        item.caption = o.optString("caption", "");
        item.attachPath = file.getAbsolutePath();
        item.pinned = false;
        return item;
    }

    private TL_stories.StoryItem findItem(long owner, int id) {
        final ArrayList<TL_stories.StoryItem> list = stories.get(owner);
        if (list != null) for (TL_stories.StoryItem s : list) if (s.id == id) return s;
        return null;
    }

    private void download(JSONArray urls, File file) {
        if (urls == null || urls.length() == 0 || !downloading.add(file.getName())) return;
        downloads.execute(() -> {
            final File tmp = new File(file.getPath() + ".part");
            boolean ok = false;
            try (OutputStream out = new FileOutputStream(tmp)) {
                final byte[] buf = new byte[64 * 1024];
                // the server caps a story at 60 MB: never write more than that from whatever the URLs return
                final long max = file.getName().endsWith(".mp4") ? MAX_VIDEO_BYTES : MAX_PHOTO_BYTES;
                long total = 0;
                if (urls.length() > (int) (max / PART_SIZE_MIN) + 1) throw new IllegalStateException("too_many_parts");
                for (int i = 0; i < urls.length(); i++) {
                    final String u = urls.getString(i);
                    if (!u.startsWith("https://")) throw new IllegalStateException("bad_url");
                    final HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(false);
                    try (InputStream in = c.getInputStream()) {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            total += n;
                            if (total > max) throw new IllegalStateException("too_big");
                            out.write(buf, 0, n);
                        }
                    } finally {
                        c.disconnect();
                    }
                }
                ok = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean done = ok && tmp.renameTo(file);
            if (!done) tmp.delete();
            AndroidUtilities.runOnUIThread(() -> {
                downloading.remove(file.getName());
                if (done && !alive()) {
                    file.delete();
                } else if (done) {
                    restoreFeed(); // the saved feed already lists it: show it without asking the server again
                }
            });
        });
    }

    /**
     * Deletes cached media no longer in the feed (expired, deleted or no longer shared with us). Files written in
     * the last few minutes stay (an own story just posted, a download finishing), and so does anything still
     * downloading; nothing outlives MAX_PERIOD.
     */
    private void cleanupFiles(HashSet<String> keep) {
        final File[] files = dir().listFiles();
        if (files == null) return;
        final long nowMs = System.currentTimeMillis();
        final long cutoff = nowMs - (MAX_PERIOD + 3600) * 1000L;
        final long grace = nowMs - 10 * 60 * 1000L;
        for (File f : files) {
            final String name = f.getName();
            final String base = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
            if (downloading.contains(base)) continue;
            if (f.lastModified() < cutoff || !keep.contains(base) && f.lastModified() < grace) f.delete();
        }
    }

    /** Puts the U stories into the Telegram story lists (again after every Telegram reload). */
    public void inject() {
        if (hasStories(OFFICIAL_ID)) ensureOfficialUser();
        final StoriesController sc = MessagesController.getInstance(account).getStoriesController();
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        final HashSet<Long> owners = new HashSet<>(injected);
        for (int i = 0; i < stories.size(); i++) owners.add(stories.keyAt(i));
        boolean changed = false;
        for (long owner : owners) {
            final ArrayList<TL_stories.StoryItem> active = new ArrayList<>();
            final ArrayList<TL_stories.StoryItem> list = stories.get(owner);
            if (list != null) for (TL_stories.StoryItem s : list) if (s.expire_date > now) active.add(s);
            if (sc.applyUMessageStories(owner, active)) changed = true;
            if (active.isEmpty()) injected.remove(owner);
            else injected.add(owner);
        }
        if (changed) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.storiesUpdated);
        }
    }

    public boolean hasStories(long owner) {
        final ArrayList<TL_stories.StoryItem> list = stories.get(owner);
        return list != null && !list.isEmpty();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.storiesUpdated) {
            inject();
        }
    }

    // ------------------------------------------------------------------ read state, reactions, views

    private Set<String> readSet() {
        if (read == null) {
            // insertion-ordered so trimming drops the oldest entries
            read = new LinkedHashSet<>();
            final String ordered = prefs().getString("read_order", null);
            if (ordered != null) {
                for (String k : TextUtils.split(ordered, ",")) {
                    if (!TextUtils.isEmpty(k)) read.add(k);
                }
            } else {
                read.addAll(prefs().getStringSet("read", new HashSet<>()));
            }
        }
        return read;
    }

    public boolean isRead(TL_stories.StoryItem item) {
        return item.dialogId == UserConfig.getInstance(account).getClientUserId() || readSet().contains(item.dialogId + "_" + item.id);
    }

    private boolean markReadLocal(long owner, int id) {
        if (!readSet().add(owner + "_" + id)) return false;
        // bound the set: drop the oldest entries, keep recent ones so they don't show as unread again
        if (read.size() > 2000) {
            final Iterator<String> it = read.iterator();
            while (read.size() > 1500 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        prefs().edit().putString("read_order", TextUtils.join(",", read)).remove("read").apply();
        return true;
    }

    /** Viewer opened a U story: record the view (once) for its author. */
    public boolean markRead(long owner, TL_stories.StoryItem item) {
        if (owner == UserConfig.getInstance(account).getClientUserId() || !markReadLocal(owner, item.id)) return false;
        if (owner == OFFICIAL_ID) {
            // an official story stays 24h after this user's first view
            item.expire_date = Math.min(item.expire_date, ConnectionsManager.getInstance(account).getCurrentTime() + VIEWED_TTL);
        }
        sendView(owner, item.id, null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.storiesReadUpdated);
        return true;
    }

    public void setReaction(long owner, TL_stories.StoryItem item, String emoji) {
        sendView(owner, item.id, emoji == null ? "" : emoji);
    }

    private void sendView(long owner, int id, String reaction) {
        final JSONObject body = new JSONObject();
        try {
            body.put("owner", owner == OFFICIAL_ID ? "official" : String.valueOf(owner));
            body.put("id", id);
            if (reaction != null) body.put("reaction", reaction);
        } catch (Exception ignore) {
        }
        call("view", body, (r, e) -> {});
    }

    /** View counters of own U stories, shown under the story like Telegram's. */
    private void loadSelfViews() {
        final long self = UserConfig.getInstance(account).getClientUserId();
        if (!hasStories(self)) return;
        call("mine", new JSONObject(), (r, error) -> {
            final JSONArray list = r == null ? null : r.optJSONArray("stories");
            boolean changed = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                final JSONObject o = list.optJSONObject(i);
                final TL_stories.StoryItem item = findItem(self, o.optInt("id"));
                if (item == null) continue;
                if (item.views == null) item.views = new TL_stories.TL_storyViews();
                final int views = o.optInt("views"), reactions = o.optInt("reactions");
                if (item.views.views_count != views || item.views.reactions_count != reactions) {
                    item.views.views_count = views;
                    item.views.reactions_count = reactions;
                    changed = true;
                }
            }
            if (changed) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.storiesUpdated);
        });
    }

    /** Who viewed an own U story — available without Premium and after the story expired. */
    public void loadViewers(TL_stories.StoryItem item, ResultCallback callback) {
        final JSONObject body = new JSONObject();
        try {
            body.put("id", item.id);
        } catch (Exception ignore) {
        }
        call("viewers", body, callback);
    }

    public void delete(TL_stories.StoryItem item) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final ArrayList<TL_stories.StoryItem> list = stories.get(self);
        if (list != null) list.remove(item);
        if (item.attachPath != null) new File(item.attachPath).delete();
        // the story only leaves its audience once the server confirms: queued on disk until then
        addPendingDelete(self, item.id);
        sendDelete(self, item.id);
        inject();
    }

    private static SharedPreferences pendingPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PENDING_PREFS, android.content.Context.MODE_PRIVATE);
    }

    private static synchronized void addPendingDelete(long self, int id) {
        final HashSet<String> set = new HashSet<>(pendingPrefs().getStringSet("del_" + self, new HashSet<>()));
        set.add(String.valueOf(id));
        pendingPrefs().edit().putStringSet("del_" + self, set).apply();
    }

    private static synchronized void removePendingDelete(long self, String id) {
        final HashSet<String> set = new HashSet<>(pendingPrefs().getStringSet("del_" + self, new HashSet<>()));
        if (!set.remove(id)) return;
        if (set.isEmpty()) pendingPrefs().edit().remove("del_" + self).apply();
        else pendingPrefs().edit().putStringSet("del_" + self, set).apply();
    }

    private void sendDelete(long self, int id) {
        final String token = UMessagePremiumController.getInstance().getToken(self);
        if (token == null) return; // retried once a key is back
        netQueue.postRunnable(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("userId", String.valueOf(self));
                body.put("token", token);
                body.put("action", "delete");
                body.put("id", id);
                final JSONObject r = new JSONObject(UMessagePremiumController.httpPost(URL, body.toString()));
                if (r.optBoolean("ok", false)) removePendingDelete(self, String.valueOf(id));
            } catch (Throwable e) {
                FileLog.e(e); // stays queued: retried on the next feed refresh
            }
        });
    }

    /** Re-sends deletes the server has not confirmed yet (called with every feed refresh). */
    private void retryPendingDeletes() {
        final long self = UserConfig.getInstance(account).getClientUserId();
        if (self <= 0) return;
        for (String s : new HashSet<>(pendingPrefs().getStringSet("del_" + self, new HashSet<>()))) {
            try {
                sendDelete(self, Integer.parseInt(s));
            } catch (NumberFormatException e) {
                removePendingDelete(self, s);
            }
        }
    }

    // ------------------------------------------------------------------ posting

    public interface PublishCallback {
        void onProgress(float progress);

        /** error: null on success, "limit" when the daily U story limit is used up */
        void onDone(String error);
    }

    /** Returned by {@link #publish}: cancel() stops the upload, and deletes the story if it was already published. */
    public static final class PublishHandle {
        private final java.util.concurrent.atomic.AtomicBoolean canceled = new java.util.concurrent.atomic.AtomicBoolean();

        public void cancel() {
            canceled.set(true);
        }

        public boolean isCanceled() {
            return canceled.get();
        }

        void check() {
            if (canceled.get()) throw new IllegalStateException("canceled");
        }
    }

    private static final int MAX_AUDIENCE = 5000;

    private static HashSet<Long> ruleUsers(ArrayList<TLRPC.InputPrivacyRule> rules, Class<?> clazz) {
        final HashSet<Long> ids = new HashSet<>();
        if (rules == null) return ids;
        for (TLRPC.InputPrivacyRule r : rules) {
            ArrayList<TLRPC.InputUser> users = null;
            if (clazz.isInstance(r) && r instanceof TLRPC.TL_inputPrivacyValueAllowUsers) users = ((TLRPC.TL_inputPrivacyValueAllowUsers) r).users;
            else if (clazz.isInstance(r) && r instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) users = ((TLRPC.TL_inputPrivacyValueDisallowUsers) r).users;
            if (users != null) for (TLRPC.InputUser u : users) if (u != null && u.user_id != 0) ids.add(u.user_id);
        }
        return ids;
    }

    private static boolean hasRule(ArrayList<TLRPC.InputPrivacyRule> rules, Class<?> clazz) {
        if (rules != null) for (TLRPC.InputPrivacyRule r : rules) if (clazz.isInstance(r)) return true;
        return false;
    }

    /**
     * Who may see the story on the server, from the story privacy chosen in the recorder (the same rules sent to
     * Telegram): "everyone" -> audience "all" + excluded ids (+ reach: own contacts and chat partners, whose story
     * bar shows it); contacts / close friends / selected -> the id list. Fail-closed: no rules, "nobody"
     * (DisallowAll) or any rule we can not map (chat participants, …) adds nobody, so the story is visible to
     * the owner only rather than to more people than chosen.
     */
    private void putAudience(JSONObject pub, ArrayList<TLRPC.InputPrivacyRule> rules) throws Exception {
        final JSONArray ids = new JSONArray();
        final HashSet<Long> deny = ruleUsers(rules, TLRPC.TL_inputPrivacyValueDisallowUsers.class);
        final boolean denyContacts = hasRule(rules, TLRPC.TL_inputPrivacyValueDisallowContacts.class);
        final ContactsController cc = ContactsController.getInstance(account);
        if (hasRule(rules, TLRPC.TL_inputPrivacyValueAllowAll.class) && !hasRule(rules, TLRPC.TL_inputPrivacyValueDisallowAll.class)) {
            pub.put("audience", "all");
            final HashSet<Long> excluded = new HashSet<>(deny);
            if (denyContacts) for (TLRPC.TL_contact c : new ArrayList<>(cc.contacts)) excluded.add(c.user_id);
            for (Long id : excluded) {
                if (ids.length() >= MAX_AUDIENCE) break;
                ids.put(String.valueOf(id));
            }
            pub.put("exclude", ids);
            final JSONArray reach = new JSONArray();
            final LinkedHashSet<Long> seen = new LinkedHashSet<>();
            for (TLRPC.TL_contact c : new ArrayList<>(cc.contacts)) seen.add(c.user_id);
            final MessagesController mc = MessagesController.getInstance(account);
            for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
                if (d.id <= 0 || d.id == UserConfig.getInstance(account).getClientUserId()) continue;
                final TLRPC.User u = mc.getUser(d.id);
                if (u != null && !u.bot && !u.deleted) seen.add(d.id);
            }
            for (Long id : seen) {
                if (reach.length() >= MAX_AUDIENCE) break;
                if (!excluded.contains(id)) reach.put(String.valueOf(id));
            }
            pub.put("reach", reach);
            return;
        }
        final LinkedHashSet<Long> allow = new LinkedHashSet<>();
        if (!hasRule(rules, TLRPC.TL_inputPrivacyValueDisallowAll.class) || hasRule(rules, TLRPC.TL_inputPrivacyValueAllowUsers.class)) {
            final boolean contacts = hasRule(rules, TLRPC.TL_inputPrivacyValueAllowContacts.class) && !denyContacts;
            final boolean closeFriends = hasRule(rules, TLRPC.TL_inputPrivacyValueAllowCloseFriends.class);
            allow.addAll(ruleUsers(rules, TLRPC.TL_inputPrivacyValueAllowUsers.class));
            if (contacts || closeFriends) {
                final MessagesController mc = MessagesController.getInstance(account);
                for (TLRPC.TL_contact c : new ArrayList<>(cc.contacts)) {
                    if (!contacts) {
                        final TLRPC.User u = mc.getUser(c.user_id);
                        if (u == null || !u.close_friend) continue;
                    }
                    allow.add(c.user_id);
                }
            }
            allow.removeAll(deny);
        }
        for (Long id : allow) {
            if (ids.length() >= MAX_AUDIENCE) break;
            ids.put(String.valueOf(id));
        }
        pub.put("audience", ids);
    }

    /** Uploads an exported story file (photo .jpg or video .mp4) as a U message story. */
    public PublishHandle publish(String path, boolean video, CharSequence caption, int period, long durationMs, ArrayList<TLRPC.InputPrivacyRule> privacyRules, PublishCallback callback) {
        final PublishHandle handle = new PublishHandle();
        final long self = UserConfig.getInstance(account).getClientUserId();
        final String token = UMessagePremiumController.getInstance().getToken(self);
        // build the audience now, on the calling (UI) thread, where the contacts list is safe to read
        final JSONObject audience = new JSONObject();
        try {
            putAudience(audience, privacyRules == null ? null : new ArrayList<>(privacyRules));
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!audience.has("audience")) {
            try {
                audience.put("audience", new JSONArray()); // fail-closed: owner only
                audience.remove("exclude");
                audience.remove("reach");
            } catch (Exception ignore) {
            }
        }
        uploadQueue.postRunnable(() -> {
            String error = null;
            try {
                if (token == null) throw new IllegalStateException("bad_token");
                handle.check();
                final File file = new File(path);
                final JSONObject up = new JSONObject();
                up.put("userId", String.valueOf(self));
                up.put("token", token);
                up.put("action", "upload");
                up.put("v", 2); // this app always sends an audience at publish
                up.put("type", video ? "video" : "photo");
                up.put("size", file.length());
                final JSONObject r = new JSONObject(UMessagePremiumController.httpPost(URL, up.toString()));
                if (r.has("error")) throw new IllegalStateException(r.optString("error"));
                final int id = r.getInt("id");
                final int parts = r.getInt("parts");
                final int partSize = r.getInt("partSize");
                if (partSize <= 0 || parts <= 0 || (long) partSize * parts < file.length()) throw new IllegalStateException("bad_reply");
                try (InputStream in = new FileInputStream(file)) {
                    for (int n = 0; n < parts; n++) {
                        handle.check(); // canceled: stop before the next part (the server deletes unpublished parts)
                        final byte[] buf = new byte[(int) Math.min(partSize, file.length() - (long) partSize * n)];
                        int off = 0;
                        while (off < buf.length) {
                            final int k = in.read(buf, off, buf.length - off);
                            if (k < 0) throw new IllegalStateException("short_read");
                            off += k;
                        }
                        postPart(self, token, id, n, buf);
                        final float p = (n + 1) / (float) parts;
                        AndroidUtilities.runOnUIThread(() -> callback.onProgress(p * 0.95f));
                    }
                }
                handle.check();
                final JSONObject pub = new JSONObject();
                pub.put("userId", String.valueOf(self));
                pub.put("token", token);
                pub.put("action", "publish");
                pub.put("id", id);
                pub.put("caption", caption == null ? "" : caption.toString());
                pub.put("period", Math.min(MAX_PERIOD, period <= 0 ? 86400 : period));
                pub.put("duration", durationMs / 1000.0);
                pub.put("audience", audience.opt("audience"));
                if (audience.has("exclude")) pub.put("exclude", audience.get("exclude"));
                if (audience.has("reach")) pub.put("reach", audience.get("reach"));
                final JSONObject p = new JSONObject(UMessagePremiumController.httpPost(URL, pub.toString()));
                if (p.has("error")) throw new IllegalStateException(p.optString("error"));
                if (handle.isCanceled()) {
                    // canceled while the publish call was in flight: take it down again
                    addPendingDelete(self, id);
                    sendDelete(self, id);
                    throw new IllegalStateException("canceled");
                }
                final JSONObject story = p.getJSONObject("story");
                // keep our own copy so the story shows right away, without downloading it back
                final File own = new File(dir(), self + "_" + id + (video ? ".mp4" : ".jpg"));
                copy(file, own);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!alive()) {
                        own.delete();
                        return;
                    }
                    if (handle.isCanceled()) {
                        addPendingDelete(self, id);
                        sendDelete(self, id);
                        own.delete();
                        return;
                    }
                    final TL_stories.StoryItem item = makeItem(self, id, story, own);
                    item.views = new TL_stories.TL_storyViews();
                    stories.add(self, item);
                    inject();
                });
            } catch (Throwable t) {
                if (!handle.isCanceled()) FileLog.e(t);
                error = t instanceof IllegalStateException ? t.getMessage() : "network";
            }
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.onDone(fe));
        });
        return handle;
    }

    private static void postPart(long self, String token, int id, int part, byte[] data) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(URL + "/part?id=" + id + "&part=" + part).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("x-user-id", String.valueOf(self));
        c.setRequestProperty("x-token", token);
        try {
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            if (c.getResponseCode() != 200) throw new IllegalStateException("upload_failed");
        } finally {
            c.disconnect();
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** Telegram refused a story because of its limit: post it as a U story instead. */
    public static boolean isLimitError(TLRPC.TL_error error) {
        return error != null && error.text != null && (error.text.startsWith("STORY_SEND_FLOOD") || "STORIES_TOO_MUCH".equals(error.text));
    }

    private void call(String action, JSONObject params, ResultCallback callback) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final String token = UMessagePremiumController.getInstance().getToken(self);
        // Without the key the server serves the official stories only (feed, and viewing an official story);
        // everything else needs it. So an app that lost its key still shows the admin's stories.
        final boolean openAction = "feed".equals(action) || ("view".equals(action) && "official".equals(params.optString("owner")));
        if (token == null && !openAction) {
            callback.run(null, "bad_token");
            return;
        }
        netQueue.postRunnable(() -> {
            JSONObject r = null;
            String error = null;
            try {
                final JSONObject body = new JSONObject(params.toString());
                body.put("userId", String.valueOf(self));
                if (token != null) {
                    body.put("token", token);
                }
                body.put("action", action);
                r = new JSONObject(UMessagePremiumController.httpPost(URL, body.toString()));
                if (r.has("error")) error = r.optString("error");
            } catch (Throwable t) {
                error = "network";
            }
            final JSONObject fr = r;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (alive()) callback.run(fr, fe);
            });
        });
    }

    /** owner -> story list, insertion ordered by story id. */
    private static class LongSparseArrayCompat {
        private final androidx.collection.LongSparseArray<ArrayList<TL_stories.StoryItem>> map = new androidx.collection.LongSparseArray<>();

        ArrayList<TL_stories.StoryItem> get(long key) {
            return map.get(key);
        }

        void add(long key, TL_stories.StoryItem item) {
            ArrayList<TL_stories.StoryItem> list = map.get(key);
            if (list == null) map.put(key, list = new ArrayList<>());
            for (TL_stories.StoryItem s : list) if (s.id == item.id) return;
            list.add(item);
            java.util.Collections.sort(list, (a, b) -> Integer.compare(a.id, b.id));
        }

        void replaceWith(LongSparseArrayCompat other) {
            map.clear();
            for (int i = 0; i < other.map.size(); i++) map.put(other.map.keyAt(i), other.map.valueAt(i));
        }

        int size() {
            return map.size();
        }

        long keyAt(int i) {
            return map.keyAt(i);
        }
    }
}
