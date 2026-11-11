package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Music for stories: trending lists and search from Deezer's public API. Every track comes with a
 * 30 s preview, which is the song's hook (the part that trends), so playback and recording start
 * right there. Tracks Deezer has no preview for here fall back to the iTunes preview of the same song.
 */
public final class UMessageMusic {

    /** dedicated queues: network lookups/downloads must not hold up the shared globalQueue */
    public static final DispatchQueue queue = new DispatchQueue("umMusic");
    public static final DispatchQueue downloadQueue = new DispatchQueue("umMusicDl");

    public static final int CATEGORY_TREND = 0;
    public static final int CATEGORY_TIKTOK = 1;
    public static final int CATEGORY_RUSSIAN = 2;
    public static final int CATEGORY_WORLD = 3;
    public static final int CATEGORY_COUNT = 4;

    /**
     * Lists prepared by our server (umessage-premium-server /api/music): this week's Shazam charts (Uzbekistan,
     * Russia, world) and the newest TikTok hits, each song already with its cover and hook, cached at the edge.
     */
    private static final String[] SOURCES = {"trend", "tiktok", "russia", "world"};

    private static final long CACHE_TIME = 30 * 60 * 1000L;
    /** A saved list older than this is refreshed in the background while it is shown. */
    private static final long REFRESH_TIME = 60 * 60 * 1000L;

    public static final class Track {
        public long id;
        public String title;
        public String artist;
        public String cover;
        /** Hook preview url, resolved lazily when Deezer has none. */
        public volatile String preview;
        /** Full song length in seconds. */
        public int duration;
        volatile boolean resolved;
    }

    public interface Callback {
        void onLoaded(ArrayList<Track> tracks, boolean error);
    }

    private static final ArrayList<Track>[] cache = new ArrayList[CATEGORY_COUNT];
    private static final long[] cacheTime = new long[CATEGORY_COUNT];

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_music", android.content.Context.MODE_PRIVATE);
    }

    /**
     * Shows the list saved from last time right away, then (when it is older than an hour or missing)
     * the fresh one; the callback can run twice.
     */
    public static void load(int category, Callback callback) {
        final ArrayList<Track> cached = cache[category];
        if (cached != null && System.currentTimeMillis() - cacheTime[category] < CACHE_TIME) {
            callback.onLoaded(cached, false);
            return;
        }
        final String saved = prefs().getString("list_" + SOURCES[category], null);
        final long savedTime = prefs().getLong("time_" + SOURCES[category], 0);
        boolean shown = false;
        if (saved != null) {
            try {
                final ArrayList<Track> tracks = parseServer(new JSONObject(saved));
                if (!tracks.isEmpty()) {
                    cache[category] = tracks;
                    cacheTime[category] = savedTime;
                    callback.onLoaded(tracks, false);
                    shown = true;
                    if (System.currentTimeMillis() - savedTime < REFRESH_TIME) {
                        return;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        final boolean alreadyShown = shown;
        queue.postRunnable(() -> {
            ArrayList<Track> result = null;
            String body = null;
            try {
                body = get(UMessagePremiumController.BASE_URL + "/api/music?c=" + SOURCES[category]);
                result = parseServer(new JSONObject(body));
            } catch (Exception e) {
                FileLog.e(e);
            }
            final ArrayList<Track> tracks = result;
            final String json = body;
            AndroidUtilities.runOnUIThread(() -> {
                if (tracks != null && !tracks.isEmpty()) {
                    cache[category] = tracks;
                    cacheTime[category] = System.currentTimeMillis();
                    prefs().edit().putString("list_" + SOURCES[category], json).putLong("time_" + SOURCES[category], System.currentTimeMillis()).apply();
                    callback.onLoaded(tracks, false);
                } else if (!alreadyShown) {
                    callback.onLoaded(new ArrayList<>(), true);
                }
            });
        });
    }

    private static ArrayList<Track> parseServer(JSONObject json) throws Exception {
        final JSONArray items = json.optJSONArray("tracks");
        final ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            final JSONObject item = items.getJSONObject(i);
            final Track track = new Track();
            final String id = item.optString("id");
            track.id = id.hashCode() & 0x7fffffffL;
            track.title = item.optString("title");
            track.artist = item.optString("artist");
            track.cover = item.optString("cover", null);
            track.duration = item.optInt("duration");
            track.preview = item.optString("preview", null);
            // the server already picked the hook (Apple's preview when there is one)
            track.resolved = !TextUtils.isEmpty(track.preview);
            if (!TextUtils.isEmpty(track.title)) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    public static void search(String query, Callback callback) {
        queue.postRunnable(() -> {
            ArrayList<Track> result;
            boolean error = false;
            try {
                result = parseTracks(get("https://api.deezer.com/search?limit=60&q=" + Uri.encode(query)));
            } catch (Exception e) {
                FileLog.e(e);
                result = new ArrayList<>();
                error = true;
            }
            final ArrayList<Track> tracks = result;
            final boolean failed = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(tracks, failed));
        });
    }

    /**
     * Finds the hook; blocking, network. Apple's preview is the part editors picked as the song's hook
     * (what trends), Deezer's is often just the opening, so Apple comes first and Deezer is the fallback.
     */
    public static String resolvePreview(Track track) {
        if (track.resolved) {
            return track.preview;
        }
        try {
            final JSONObject json = new JSONObject(get("https://itunes.apple.com/search?entity=song&limit=8&term=" + Uri.encode(track.artist + " " + track.title)));
            final JSONArray results = json.optJSONArray("results");
            final String title = normalize(track.title);
            final String artist = normalize(track.artist);
            String fallback = null;
            for (int i = 0; results != null && i < results.length(); i++) {
                final JSONObject song = results.getJSONObject(i);
                final String url = song.optString("previewUrl", null);
                if (url == null) {
                    continue;
                }
                final boolean titleMatch = normalize(song.optString("trackName", "")).startsWith(title);
                final boolean artistMatch = normalize(song.optString("artistName", "")).contains(artist) || artist.contains(normalize(song.optString("artistName", "")));
                if (titleMatch && artistMatch) {
                    track.preview = url;
                    track.resolved = true;
                    return url;
                }
                if (titleMatch && fallback == null) {
                    fallback = url;
                }
            }
            if (TextUtils.isEmpty(track.preview) && fallback != null) {
                track.preview = fallback;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        track.resolved = true;
        return track.preview;
    }

    /**
     * The song's music video highlight: Apple's 30 s preview of the official clip (iTunes Search API),
     * or null when the song has none. Blocking, network.
     */
    public static String findVideo(String title, String artist) {
        final String wantTitle = normalize(title);
        final String wantArtist = normalize(artist);
        if (wantTitle.isEmpty()) {
            return null;
        }
        // Shazam and Apple often spell the artist differently (featured artists, "&", translit),
        // so the title alone is tried too, still checking that the artist matches loosely
        final String[] queries = TextUtils.isEmpty(artist) ? new String[]{title} : new String[]{artist + " " + title, title};
        for (String query : queries) {
            try {
                final JSONArray results = new JSONObject(get("https://itunes.apple.com/search?entity=musicVideo&limit=25&term=" + Uri.encode(query))).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    final JSONObject video = results.getJSONObject(i);
                    final String url = video.optString("previewUrl", null);
                    final String gotTitle = normalize(video.optString("trackName", ""));
                    final String gotArtist = normalize(video.optString("artistName", ""));
                    final boolean titleMatch = !gotTitle.isEmpty() && (gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle));
                    final boolean artistMatch = wantArtist.isEmpty() || gotArtist.isEmpty() || gotArtist.contains(wantArtist) || wantArtist.contains(gotArtist)
                            || sharesWord(video.optString("artistName", ""), artist);
                    if (!TextUtils.isEmpty(url) && titleMatch && artistMatch) {
                        return url;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return null;
    }

    /**
     * Downloads a clip into the cache, so it plays smoothly from the phone instead of stalling on a
     * slow connection (the clips are 1080p, about 20 MB); kept for next time. Blocking, network.
     */
    public static File downloadVideo(String url) throws Exception {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "umessage_clips");
        dir.mkdirs();
        final File file = new File(dir, Integer.toHexString(url.hashCode()) + ".m4v");
        if (file.exists() && file.length() > 0) {
            return file;
        }
        final File temp = new File(dir, Integer.toHexString(url.hashCode()) + ".part");
        final HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            copyCapped(connection, in, out, MAX_VIDEO_BYTES);
        } catch (Exception e) {
            temp.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        if (!temp.renameTo(file)) {
            throw new IllegalStateException("rename failed");
        }
        // only the newest few clips are kept
        final File[] clips = dir.listFiles((d, name) -> name.endsWith(".m4v"));
        if (clips != null && clips.length > 5) {
            java.util.Arrays.sort(clips, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < clips.length - 5; i++) {
                clips[i].delete();
            }
        }
        return file;
    }

    /** Whether two artist credits share a name of 3+ letters ("Mirjalol Nematov" and "Nematov, Mirjalol feat. X"). */
    private static boolean sharesWord(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        final HashSet<String> words = new HashSet<>();
        for (String w : a.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3) {
                words.add(w);
            }
        }
        for (String w : b.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && words.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        s = s.toLowerCase();
        final int cut = s.indexOf(" (");
        if (cut > 0) {
            s = s.substring(0, cut);
        }
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Audio files (bot links, previews) are capped at this size, clips at MAX_VIDEO_BYTES, JSON at 1 MB. */
    private static final long MAX_AUDIO_BYTES = 20L << 20;
    private static final long MAX_VIDEO_BYTES = 60L << 20;

    private static void copyCapped(HttpURLConnection connection, InputStream in, java.io.OutputStream out, long max) throws Exception {
        if (connection.getContentLengthLong() > max) {
            throw new IllegalStateException("too large");
        }
        final byte[] buffer = new byte[65536];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > max) {
                throw new IllegalStateException("too large");
            }
            out.write(buffer, 0, read);
        }
    }

    /** The file really is audio (a container MediaExtractor can open, with an audio track), not whatever a link served. */
    public static boolean isAudioFile(File file) {
        final android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        } finally {
            extractor.release();
        }
    }

    /**
     * Downloads the hook (or a music bot's full-song link) into the cache; blocking, network. Only https,
     * at most MAX_AUDIO_BYTES, and only kept when it parses as audio: it is sent to chats as the user's audio.
     */
    public static File download(Track track) throws Exception {
        final String url = resolvePreview(track);
        if (TextUtils.isEmpty(url)) {
            throw new IllegalStateException("no preview");
        }
        if (!url.startsWith("https://")) {
            throw new IllegalStateException("not https");
        }
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "umessage_music");
        dir.mkdirs();
        final File file = new File(dir, track.id + "_" + Integer.toHexString(url.hashCode()) + (url.contains(".m4a") ? ".m4a" : ".mp3"));
        if (file.exists() && file.length() > 0) {
            return file;
        }
        final File temp = new File(dir, track.id + ".part");
        final HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            copyCapped(connection, in, out, MAX_AUDIO_BYTES);
        } catch (Exception e) {
            temp.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        if (!isAudioFile(temp)) {
            temp.delete();
            throw new IllegalStateException("not audio");
        }
        if (!temp.renameTo(file)) {
            throw new IllegalStateException("rename failed");
        }
        return file;
    }

    private static final android.util.LruCache<String, android.graphics.Bitmap> covers = new android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, android.graphics.Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final java.util.concurrent.ExecutorService coverLoader = java.util.concurrent.Executors.newFixedThreadPool(3);

    public static android.graphics.Bitmap getCachedCover(String url) {
        return url == null ? null : covers.get(url);
    }

    /** Loads a cover (album art) from the web; the callback runs on the UI thread, with null on failure. */
    public static void loadCover(String url, Utilities.Callback<android.graphics.Bitmap> callback) {
        if (TextUtils.isEmpty(url)) {
            callback.run(null);
            return;
        }
        final android.graphics.Bitmap cached = covers.get(url);
        if (cached != null) {
            callback.run(cached);
            return;
        }
        coverLoader.execute(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                final HttpURLConnection connection = open(url);
                try (InputStream in = connection.getInputStream()) {
                    bitmap = android.graphics.BitmapFactory.decodeStream(in);
                } finally {
                    connection.disconnect();
                }
                if (bitmap != null) {
                    covers.put(url, bitmap);
                }
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) FileLog.e("UMusic: cover " + url, e);
                FileLog.e(e);
            }
            final android.graphics.Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    /** Length of a downloaded hook in ms. */
    public static long readDuration(File file) {
        final android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Long.parseLong(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            FileLog.e(e);
            return 30000;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
    }

    private static ArrayList<Track> parseTracks(String response) throws Exception {
        final JSONArray data = new JSONObject(response).optJSONArray("data");
        final ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            final JSONObject item = data.getJSONObject(i);
            if (!"track".equals(item.optString("type", "track"))) {
                continue;
            }
            final Track track = new Track();
            track.id = item.optLong("id");
            track.title = item.optString("title_short", item.optString("title"));
            track.duration = item.optInt("duration");
            track.preview = item.optString("preview", null);
            if (TextUtils.isEmpty(track.preview)) {
                track.preview = null;
            }
            final JSONObject artist = item.optJSONObject("artist");
            track.artist = artist != null ? artist.optString("name") : "";
            final JSONObject album = item.optJSONObject("album");
            if (album != null) {
                track.cover = album.optString("cover_medium", null);
            }
            if (track.id != 0 && !TextUtils.isEmpty(track.title)) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    private static HttpURLConnection open(String url) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept-Language", "uz,ru;q=0.8,en;q=0.6");
        if (connection.getResponseCode() != 200) {
            connection.disconnect();
            throw new IllegalStateException("http " + connection.getResponseCode() + " " + url);
        }
        return connection;
    }

    private static String get(String url) throws Exception {
        final HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            return UMessageNet.readCappedString(in, UMessageNet.MAX_JSON_BYTES);
        } finally {
            connection.disconnect();
        }
    }
}
