package org.telegram.messenger;

import android.text.TextUtils;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Who unfollowed: the follower list is read with the user's own instagram.com session (the cookies the in-app
 * Instagram page keeps), stored only on this phone and compared with the previous list. Nothing reaches our server.
 * Instagram has no official API for follower lists, so checks run only on a tap, at most twice a day, with pauses.
 */
public class UMessageInstagramFollowers {

    public static final String ORIGIN = "https://www.instagram.com";
    private static final String APP_ID = "936619743392459"; // instagram.com's own web app id
    private static final int PAGE = 50;
    private static final int MAX_EVENTS = 1000;
    private static final int CHECKS_PER_DAY = 2;
    public static final int BIG_ACCOUNT = 50000;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_LOGIN = 1;
    public static final int ERROR_LIMITED = 2;
    public static final int ERROR_FAILED = 3;

    public static class Person {
        public String id, username, name, photo;
        public long time; // event time, seconds
        public boolean lost;
    }

    public interface Progress {
        void onProgress(int loaded, int total);
    }

    public interface Done {
        /** baseline: first list saved, nothing to compare yet. */
        void onDone(int error, boolean baseline, int lost, int gained);
    }

    private static volatile String userAgent;

    public static void setUserAgent(String ua) {
        userAgent = ua;
    }

    public static String cookies() {
        try {
            return CookieManager.getInstance().getCookie(ORIGIN);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String cookie(String all, String name) {
        if (all == null) return null;
        for (String part : all.split(";")) {
            final String p = part.trim();
            if (p.startsWith(name + "=")) {
                final String v = p.substring(name.length() + 1);
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** The logged-in Instagram user id, or null when not logged in. */
    public static String userId() {
        final String id = cookie(cookies(), "ds_user_id");
        return id != null && id.matches("\\d{1,20}") ? id : null;
    }

    /** Ends the instagram.com session in the app's WebView only; other sites keep their cookies. */
    public static void logout() {
        try {
            final CookieManager cm = CookieManager.getInstance();
            final String all = cm.getCookie(ORIGIN);
            if (all != null) {
                for (String part : all.split(";")) {
                    final int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    final String name = part.substring(0, eq).trim();
                    cm.setCookie(ORIGIN, name + "=; Max-Age=0; Path=/; Domain=.instagram.com");
                    cm.setCookie(ORIGIN, name + "=; Max-Age=0; Path=/");
                }
            }
            cm.flush();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // --- storage (per Instagram account, app files dir) ---

    private static File file(String uid, String kind) {
        return new File(ApplicationLoader.getFilesDirFixed(), "um_ig_" + kind + "_" + uid + ".json");
    }

    private static JSONObject read(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return null;
        }
    }

    private static void write(File f, JSONObject o) throws Exception {
        final File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new Exception("rename");
    }

    /** Followers count and time (seconds) of the last full check; null before the first one. */
    public static long[] lastCheck(String uid) {
        final JSONObject o = uid == null ? null : read(file(uid, "list"));
        if (o == null) return null;
        final JSONObject users = o.optJSONObject("users");
        return new long[]{users != null ? users.length() : 0, o.optLong("t")};
    }

    public static ArrayList<Person> events(String uid) {
        final ArrayList<Person> out = new ArrayList<>();
        final JSONObject o = uid == null ? null : read(file(uid, "events"));
        final JSONArray arr = o != null ? o.optJSONArray("e") : null;
        if (arr == null) return out;
        for (int i = arr.length() - 1; i >= 0; i--) { // newest first
            final JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            final Person p = new Person();
            p.id = e.optString("id");
            p.username = e.optString("u");
            p.name = e.optString("n");
            p.photo = e.optString("p");
            p.time = e.optLong("t");
            p.lost = e.optBoolean("l");
            out.add(p);
        }
        return out;
    }

    /** True when another check is allowed now (at most {@link #CHECKS_PER_DAY} in 24 hours). */
    public static boolean canCheck(String uid) {
        final JSONObject o = uid == null ? null : read(file(uid, "list"));
        final JSONArray runs = o != null ? o.optJSONArray("runs") : null;
        if (runs == null) return true;
        final long dayAgo = System.currentTimeMillis() / 1000 - 86400;
        int recent = 0;
        for (int i = 0; i < runs.length(); i++) if (runs.optLong(i) > dayAgo) recent++;
        return recent < CHECKS_PER_DAY;
    }

    // --- network ---

    private static class HttpError extends Exception {
        final int code;
        HttpError(int code) { this.code = code; }
    }

    private static JSONObject get(String path, String cookies) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(ORIGIN + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Cookie", cookies);
        c.setRequestProperty("X-IG-App-ID", APP_ID);
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Referer", ORIGIN + "/");
        final String csrf = cookie(cookies, "csrftoken");
        if (csrf != null) c.setRequestProperty("X-CSRFToken", csrf);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        try {
            final int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            try (InputStream in = c.getInputStream()) {
                final JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                if ("fail".equals(o.optString("status"))) {
                    final String msg = o.optString("message");
                    throw new HttpError(msg.contains("login") || msg.contains("checkpoint") ? 401 : 429);
                }
                return o;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Total followers, or -1 when Instagram does not say. */
    public static int followerCount(String uid) {
        try {
            final JSONObject user = get("/api/v1/users/" + uid + "/info/", cookies()).optJSONObject("user");
            return user != null ? user.optInt("follower_count", -1) : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Loads the whole list on a background thread; callbacks arrive on the UI thread. A partial list is never compared. */
    public static void check(String uid, Progress progress, Done done) {
        new Thread(() -> { // own thread: the pauses would block a shared queue for minutes
            int error = ERROR_NONE;
            boolean baseline = false;
            int lost = 0, gained = 0;
            try {
                final String cookies = cookies();
                if (uid == null || cookies == null || !uid.equals(cookie(cookies, "ds_user_id"))) throw new HttpError(401);
                final int total = followerCount(uid);
                final Random random = new Random();
                final HashMap<String, JSONArray> now = new HashMap<>();
                String maxId = null;
                do {
                    final JSONObject page = get("/api/v1/friendships/" + uid + "/followers/?count=" + PAGE
                            + (maxId != null ? "&max_id=" + java.net.URLEncoder.encode(maxId, "UTF-8") : ""), cookies);
                    final JSONArray users = page.optJSONArray("users");
                    if (users != null) {
                        for (int i = 0; i < users.length(); i++) {
                            final JSONObject u = users.optJSONObject(i);
                            if (u == null) continue;
                            final String id = u.optString("pk_id", u.optString("pk"));
                            if (TextUtils.isEmpty(id)) continue;
                            now.put(id, new JSONArray().put(u.optString("username")).put(u.optString("full_name")).put(u.optString("profile_pic_url")));
                        }
                    }
                    final String next = page.optString("next_max_id", null);
                    maxId = TextUtils.isEmpty(next) || next.equals(maxId) ? null : next;
                    final int loaded = now.size();
                    AndroidUtilities.runOnUIThread(() -> progress.onProgress(loaded, total));
                    if (maxId != null) Thread.sleep(1200 + random.nextInt(1300)); // be gentle: like a person scrolling
                } while (maxId != null);

                final long t = System.currentTimeMillis() / 1000;
                final File listFile = file(uid, "list");
                final JSONObject old = read(listFile);
                final JSONObject oldUsers = old != null ? old.optJSONObject("users") : null;
                final JSONArray events = new JSONArray();
                if (oldUsers == null) {
                    baseline = true;
                } else {
                    for (Iterator<String> it = oldUsers.keys(); it.hasNext(); ) {
                        final String id = it.next();
                        if (!now.containsKey(id)) {
                            events.put(event(id, oldUsers.optJSONArray(id), t, true));
                            lost++;
                        }
                    }
                    for (Map.Entry<String, JSONArray> e : now.entrySet()) {
                        if (!oldUsers.has(e.getKey())) {
                            events.put(event(e.getKey(), e.getValue(), t, false));
                            gained++;
                        }
                    }
                }
                if (events.length() > 0) {
                    final File evFile = file(uid, "events");
                    final JSONObject evOld = read(evFile);
                    final JSONArray all = evOld != null && evOld.optJSONArray("e") != null ? evOld.optJSONArray("e") : new JSONArray();
                    for (int i = 0; i < events.length(); i++) all.put(events.get(i));
                    final JSONArray kept = new JSONArray();
                    for (int i = Math.max(0, all.length() - MAX_EVENTS); i < all.length(); i++) kept.put(all.get(i));
                    write(evFile, new JSONObject().put("e", kept));
                }
                final JSONObject users = new JSONObject();
                for (Map.Entry<String, JSONArray> e : now.entrySet()) users.put(e.getKey(), e.getValue());
                final JSONArray runs = new JSONArray();
                final JSONArray oldRuns = old != null ? old.optJSONArray("runs") : null;
                if (oldRuns != null) for (int i = Math.max(0, oldRuns.length() - 4); i < oldRuns.length(); i++) runs.put(oldRuns.optLong(i));
                runs.put(t);
                write(listFile, new JSONObject().put("t", t).put("users", users).put("runs", runs));
            } catch (HttpError e) {
                error = e.code == 401 || e.code == 302 || e.code == 403 ? ERROR_LOGIN : e.code == 429 ? ERROR_LIMITED : ERROR_FAILED;
            } catch (Throwable e) {
                FileLog.e(e);
                error = ERROR_FAILED;
            }
            final int fError = error, fLost = lost, fGained = gained;
            final boolean fBaseline = baseline;
            AndroidUtilities.runOnUIThread(() -> done.onDone(fError, fBaseline, fLost, fGained));
        }, "um-ig-followers").start();
    }

    private static JSONObject event(String id, JSONArray u, long t, boolean lost) throws Exception {
        return new JSONObject().put("id", id).put("u", u != null ? u.optString(0) : "").put("n", u != null ? u.optString(1) : "")
                .put("p", u != null ? u.optString(2) : "").put("t", t).put("l", lost);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
