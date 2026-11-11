package org.telegram.messenger;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation prompts spoken with a Yandex SpeechKit voice (nigora for Uzbek), synthesized by
 * umessage-premium-server (/api/loc?tts=1) so the API key never ships in the APK. Phrases play one after
 * another; audio is cached in files so repeated prompts work offline. When the server has no key, is
 * unreachable or returns an error, the phrase goes to {@code fallback} (Android TTS).
 */
public class UMessageYandexVoice {

    private static final String URL = UMessagePremiumController.BASE_URL + "/api/loc?tts=1";
    private static final int MAX_CACHE_FILES = 400;

    private static class Item {
        final String text;
        final Runnable done, fallback;

        Item(String text, Runnable done, Runnable fallback) {
            this.text = text;
            this.done = done;
            this.fallback = fallback;
        }
    }

    private final Context context;
    private final int account;
    private final String lang;
    private final File dir;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private boolean busy, released;
    private int generation;
    // server said "not_configured": stop asking for this session
    private static volatile boolean unavailable;

    public UMessageYandexVoice(Context context, int account, String lang) {
        this.context = context.getApplicationContext();
        this.account = account;
        this.lang = lang == null ? "uz" : lang;
        this.dir = new File(this.context.getCacheDir(), "nav_voice");
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public static boolean isUnavailable() {
        return unavailable;
    }

    /** Queues a phrase; {@code done} runs after it was spoken (or handed to fallback), both on the UI thread. */
    public void speak(String text, Runnable done, Runnable fallback) {
        if (released || text == null || text.trim().isEmpty()) {
            if (done != null) done.run();
            return;
        }
        if (unavailable) {
            if (fallback != null) fallback.run();
            if (done != null) done.run();
            return;
        }
        queue.add(new Item(text.trim(), done, fallback));
        next();
    }

    /** Drops queued phrases and stops the current one. */
    public void stop() {
        generation++;
        queue.clear();
        stopPlayer();
        busy = false;
    }

    public void release() {
        stop();
        released = true;
        network.shutdownNow();
    }

    private void next() {
        if (busy || released || queue.isEmpty()) return;
        busy = true;
        Item item = queue.poll();
        int gen = generation;
        network.execute(() -> {
            File file = null;
            try {
                file = fetch(item.text);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            File f = file;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || released) return;
                if (f == null) {
                    if (item.fallback != null) item.fallback.run();
                    finish(item);
                } else {
                    play(f, item, gen);
                }
            });
        });
    }

    private void finish(Item item) {
        busy = false;
        if (item.done != null) item.done.run();
        next();
    }

    private void play(File file, Item item, int gen) {
        try {
            stopPlayer();
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                if (gen != generation) return;
                stopPlayer();
                finish(item);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (gen != generation) return true;
                stopPlayer();
                file.delete();
                if (item.fallback != null) item.fallback.run();
                finish(item);
                return true;
            });
            requestFocus(attrs);
            mp.prepare();
            mp.start();
        } catch (Throwable e) {
            FileLog.e(e);
            stopPlayer();
            if (item.fallback != null) item.fallback.run();
            finish(item);
        }
    }

    private void requestFocus(AudioAttributes attrs) {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Throwable ignore) {
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignore) {
            }
            player = null;
        }
        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            } catch (Throwable ignore) {
            }
            focusRequest = null;
        }
    }

    /** Cached file for the phrase, synthesizing it on the server when missing; null on failure. */
    private File fetch(String text) throws Exception {
        File file = new File(dir, hash(lang + "|" + text) + ".mp3");
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        long me = UserConfig.getInstance(account).getClientUserId();
        String token = UMessagePremiumController.getInstance().getToken(me);
        if (me == 0 || token == null) return null;
        String body = new JSONObject().put("userId", String.valueOf(me)).put("token", token)
                .put("text", text).put("lang", lang).toString();
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(5_000);
            c.setReadTimeout(8_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 503) {
                unavailable = true;
                return null;
            }
            if (code != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(UMessageNet.readCapped(c.getInputStream(), MAX_AUDIO_BYTES)); // a voice clip, never megabytes
            if (out.size() == 0) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            trimCache();
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                out.writeTo(fos);
            }
            return tmp.renameTo(file) ? file : null;
        } finally {
            c.disconnect();
        }
    }

    private static final long MAX_AUDIO_BYTES = 5L << 20;

    private void trimCache() {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_CACHE_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_CACHE_FILES / 2; i++) files[i].delete();
    }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
