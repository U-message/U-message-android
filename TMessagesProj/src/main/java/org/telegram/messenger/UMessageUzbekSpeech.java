package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline Uzbek speech recognition, ported from the 7OS keyboard's dictation: NVIDIA's Uzbek
 * FastConformer (stt_uz_fastconformer_hybrid_large_pc, CC-BY-4.0, CTC head, ONNX) run by
 * sherpa-onnx, with Silero VAD cutting long audio into utterances. The model (~458 MB) is downloaded
 * once into app storage; after that recognition never touches the network.
 */
public final class UMessageUzbekSpeech {

    public static final int SAMPLE_RATE = 16000;

    public interface DownloadListener {
        void onProgress(long done, long total);

        void onDone(boolean ok, String error);
    }

    /** Pinned to a commit, so the files (and sizes) never change under us. */
    private static final String BASE_URL =
            "https://huggingface.co/OpenVoiceOS/stt_uz_fastconformer_hybrid_large_pc_onnx/resolve/df756f83c28353c8dea2b23a8d01ae6393c91a0e/";
    private static final String TOKENS = "tokens.txt";
    private static final long TOKENS_SIZE = 9_759L;
    private static final String MODEL_RAW = "model.download.onnx";
    private static final long MODEL_RAW_SIZE = 458_160_971L;
    private static final String MODEL = "model.sherpa.onnx";
    public static final long TOTAL_BYTES = TOKENS_SIZE + MODEL_RAW_SIZE;

    /** sherpa-onnx's NeMo CTC loader keys, appended to the downloaded ONNX (protobuf merges repeated fields). */
    private static final byte[] SHERPA_METADATA = buildMetadata(new String[][]{
            {"vocab_size", "1025"}, {"subsampling_factor", "8"}, {"normalize_type", "per_feature"}});

    private static volatile long progressBytes;

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_speech", Context.MODE_PRIVATE);
    }

    /** User switch (U message settings): use the offline Uzbek model for voice-to-text. */
    public static boolean isEnabled() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.UZBEK_SPEECH)) return false;
        return prefs().getBoolean("enabled", true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
        if (!enabled) release();
    }

    /** Bytes downloaded so far in the running (or last) download, for progress rows. */
    public static long getProgressBytes() {
        return progressBytes;
    }

    private static volatile Thread downloadThread;
    private static volatile boolean cancelDownload;
    private static OfflineRecognizer recognizer;
    /** transcriptions currently using {@link #recognizer}; the native object is freed only when this is 0 */
    private static int recognizerUsers;
    private static boolean releasePending;
    private static final Runnable releaseTask = UMessageUzbekSpeech::release;

    private UMessageUzbekSpeech() {
    }

    // ---------------------------------------------------------------- availability

    /** False on devices without the native library (x86) or in builds that do not package it. */
    public static boolean isSupported() {
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "";
            return abi.startsWith("arm");
        } catch (Throwable t) {
            return false;
        }
    }

    private static File dir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "asr/uz-fastconformer-ctc");
    }

    private static File modelFile() {
        return new File(dir(), MODEL);
    }

    private static File tokensFile() {
        return new File(dir(), TOKENS);
    }

    public static boolean isReady() {
        return tokensFile().length() == TOKENS_SIZE && modelFile().length() == MODEL_RAW_SIZE + SHERPA_METADATA.length;
    }

    public static boolean isDownloading() {
        Thread t = downloadThread;
        return t != null && t.isAlive();
    }

    // ---------------------------------------------------------------- download

    /** Downloads (or resumes) the model; listener callbacks arrive on the UI thread. */
    public static void download(DownloadListener listener) {
        if (isDownloading()) return;
        cancelDownload = false;
        downloadThread = new Thread(() -> {
            String error = null;
            try {
                downloadAll(listener);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> listener.onDone(err == null && isReady(), err));
        }, "uz-speech-download");
        downloadThread.start();
    }

    public static void cancelDownload() {
        cancelDownload = true;
    }

    public static void delete() {
        cancelDownload = true;
        release();
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private static void downloadAll(DownloadListener listener) throws Exception {
        File dir = dir();
        dir.mkdirs();
        long doneBefore = 0;
        String[][] files = {{"vocab.txt", TOKENS, String.valueOf(TOKENS_SIZE)}, {"model.onnx", MODEL_RAW, String.valueOf(MODEL_RAW_SIZE)}};
        for (String[] f : files) {
            long size = Long.parseLong(f[2]);
            File target = new File(dir, f[1]);
            boolean finished = f[1].equals(MODEL_RAW) ? modelFile().length() == MODEL_RAW_SIZE + SHERPA_METADATA.length : target.length() == size;
            if (finished) {
                doneBefore += size;
                continue;
            }
            File part = new File(dir, f[1] + ".part");
            final long base = doneBefore;
            fetch(BASE_URL + f[0] + "?download=true", part, size, got -> {
                progressBytes = base + got;
                AndroidUtilities.runOnUIThread(() -> listener.onProgress(base + got, TOTAL_BYTES));
            });
            if (part.length() != size) throw new IllegalStateException("incomplete " + f[0]);
            target.delete();
            if (!part.renameTo(target)) throw new IllegalStateException("rename " + f[0]);
            doneBefore += size;
        }
        File raw = new File(dir, MODEL_RAW);
        if (raw.exists()) {
            try (FileOutputStream out = new FileOutputStream(raw, true)) {
                out.write(SHERPA_METADATA);
            }
            File model = modelFile();
            model.delete();
            if (!raw.renameTo(model)) throw new IllegalStateException("rename model");
        }
        if (!isReady()) throw new IllegalStateException("model file is invalid");
    }

    private interface Progress {
        void run(long got);
    }

    /** Downloads url into part, resuming; Hugging Face redirects to its CDN are followed by hand to keep Range. */
    private static void fetch(String url, File part, long size, Progress progress) throws Exception {
        URL current = new URL(url);
        int redirects = 0;
        while (true) {
            long have = Math.min(part.exists() ? part.length() : 0, size);
            if (have == size) return;
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null || ++redirects > 8) throw new IllegalStateException("redirect");
                    current = new URL(current, location);
                    // the model is only ever fetched over https (no downgrade through a redirect)
                    if (!"https".equalsIgnoreCase(current.getProtocol())) throw new IllegalStateException("redirect not https");
                    continue;
                }
                boolean append = code == HttpURLConnection.HTTP_PARTIAL;
                if (code != HttpURLConnection.HTTP_OK && !append) throw new IllegalStateException("HTTP " + code);
                long got = append ? have : 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[256 * 1024];
                    long last = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelDownload) throw new IllegalStateException("cancelled");
                        got += n;
                        if (got > size) throw new IllegalStateException("too large");
                        out.write(buf, 0, n);
                        long now = SystemClock.elapsedRealtime();
                        if (now - last > 250) {
                            last = now;
                            progress.run(got);
                        }
                    }
                }
                progress.run(got);
                return;
            } finally {
                c.disconnect();
            }
        }
    }

    private static byte[] varint(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.write(v);
                return out.toByteArray();
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    /** ModelProto.metadata_props (field 14) entries of StringStringEntryProto{key=1, value=2}. */
    private static byte[] buildMetadata(String[][] entries) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try {
            for (String[] e : entries) {
                byte[] k = e[0].getBytes("UTF-8"), v = e[1].getBytes("UTF-8");
                ByteArrayOutputStream entry = new ByteArrayOutputStream();
                entry.write(0x0A);
                entry.write(varint(k.length));
                entry.write(k);
                entry.write(0x12);
                entry.write(varint(v.length));
                entry.write(v);
                byte[] body = entry.toByteArray();
                all.write((14 << 3) | 2);
                all.write(varint(body.length));
                all.write(body);
            }
        } catch (Exception ignore) {
        }
        return all.toByteArray();
    }

    // ---------------------------------------------------------------- recognition

    private static synchronized OfflineRecognizer recognizer() {
        AndroidUtilities.cancelRunOnUIThread(releaseTask);
        if (recognizer != null) return recognizer;
        OfflineModelConfig model = new OfflineModelConfig();
        model.setNemo(new OfflineNemoEncDecCtcModelConfig(modelFile().getAbsolutePath()));
        model.setTokens(tokensFile().getAbsolutePath());
        model.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        model.setProvider("cpu");
        model.setDebug(false);
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        // files on disk, not assets: no AssetManager for the recognizer
        recognizer = new OfflineRecognizer(null, config);
        return recognizer;
    }

    private static synchronized OfflineRecognizer acquireRecognizer() {
        final OfflineRecognizer rec = recognizer();
        recognizerUsers++;
        releasePending = false;
        return rec;
    }

    private static synchronized void releaseRecognizer() {
        if (recognizerUsers > 0) recognizerUsers--;
        if (recognizerUsers == 0 && releasePending) {
            release();
        }
    }

    /** Frees the native recognizer, or defers it until the running transcription finishes (else SIGSEGV). */
    private static synchronized void release() {
        if (recognizerUsers > 0) {
            releasePending = true;
            return;
        }
        releasePending = false;
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
    }

    private static Vad newVad() {
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel("asr/silero_vad.onnx");
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.5f);
        silero.setMinSpeechDuration(0.25f);
        silero.setWindowSize(512);
        silero.setMaxSpeechDuration(20f);
        VadModelConfig config = new VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(SAMPLE_RATE);
        config.setNumThreads(1);
        config.setProvider("cpu");
        return new Vad(ApplicationLoader.applicationContext.getAssets(), config);
    }

    /** Recognizes 16 kHz mono audio; blocking, call off the UI thread. */
    public static String transcribe(float[] samples) {
        final OfflineRecognizer rec = acquireRecognizer();
        final Vad vad;
        try {
            vad = newVad();
        } catch (Throwable t) {
            releaseRecognizer();
            throw t;
        }
        StringBuilder text = new StringBuilder();
        try {
            final int window = 512;
            int i = 0;
            for (; i + window <= samples.length; i += window) {
                float[] chunk = new float[window];
                System.arraycopy(samples, i, chunk, 0, window);
                vad.acceptWaveform(chunk);
                drain(vad, rec, text);
            }
            vad.flush();
            drain(vad, rec, text);
            if (text.length() == 0 && samples.length > SAMPLE_RATE / 5) {
                // VAD heard nothing it trusted (very short or quiet message): decode it whole
                append(text, decode(rec, samples));
            }
        } finally {
            vad.release();
            releaseRecognizer();
            AndroidUtilities.runOnUIThread(releaseTask, 3 * 60_000L); // keep warm for the next message
        }
        return normalize(text.toString());
    }

    private static void drain(Vad vad, OfflineRecognizer rec, StringBuilder text) {
        while (!vad.empty()) {
            append(text, decode(rec, vad.front().getSamples()));
            vad.pop();
        }
    }

    private static void append(StringBuilder text, String part) {
        if (part == null) return;
        part = part.trim();
        if (part.isEmpty()) return;
        if (text.length() > 0) text.append(' ');
        text.append(part);
    }

    private static String decode(OfflineRecognizer rec, float[] samples) {
        if (samples.length < SAMPLE_RATE / 5) return "";
        OfflineStream stream = rec.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            rec.decode(stream);
            return rec.getResult(stream).getText();
        } finally {
            stream.release();
        }
    }

    // ---------------------------------------------------------------- text cleanup (from the 7OS keyboard)

    private static final char TURNED = 'ʻ'; // oʻ gʻ
    private static final char TUTUQ = 'ʼ';  // maʼno
    private static final String APOSTROPHES = "'`´‘’ʻʼ′‛ʹ";
    private static final Pattern TURNED_LETTER = Pattern.compile("([oOgG])[" + APOSTROPHES + "]");
    private static final Pattern TUTUQ_MARK = Pattern.compile("(?<=\\p{L})(?<![oOgG])[" + APOSTROPHES + "]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.!?;:])");

    static String normalize(String raw) {
        String text = transliterateCyrillic(raw);
        text = SPACES.matcher(text).replaceAll(" ").trim();
        Matcher m = TURNED_LETTER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, m.group(1) + TURNED);
        m.appendTail(sb);
        text = TUTUQ_MARK.matcher(sb.toString()).replaceAll(String.valueOf(TUTUQ));
        text = SPACE_BEFORE_PUNCT.matcher(text).replaceAll("$1");
        if (!text.isEmpty()) {
            int i = 0;
            while (i < text.length() && !Character.isLetter(text.charAt(i))) i++;
            if (i < text.length()) text = text.substring(0, i) + Character.toUpperCase(text.charAt(i)) + text.substring(i + 1);
        }
        return text;
    }

    private static final String CYRILLIC_VOWELS = "аеёиоуўэюя";
    private static final HashMap<Character, String> CYR = new HashMap<>();

    static {
        String[][] map = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"ё", "yo"}, {"ж", "j"}, {"з", "z"},
                {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"},
                {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "x"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "sh"},
                {"ъ", String.valueOf(TUTUQ)}, {"ы", "i"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"}, {"я", "ya"},
                {"ў", "o" + TURNED}, {"қ", "q"}, {"ғ", "g" + TURNED}, {"ҳ", "h"}};
        for (String[] p : map) CYR.put(p[0].charAt(0), p[1]);
    }

    private static String transliterateCyrillic(String text) {
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'Ѐ' && c <= 'ӿ') {
                any = true;
                break;
            }
        }
        if (!any) return text;
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), lower = Character.toLowerCase(c);
            boolean atWordStart = i == 0 || !Character.isLetter(text.charAt(i - 1));
            String latin = lower == 'е'
                    ? (atWordStart || CYRILLIC_VOWELS.indexOf(Character.toLowerCase(text.charAt(i - 1))) >= 0 ? "ye" : "e")
                    : CYR.get(lower);
            if (latin == null) {
                out.append(c);
            } else if (Character.isUpperCase(c) && !latin.isEmpty()) {
                out.append(Character.toUpperCase(latin.charAt(0))).append(latin.substring(1));
            } else {
                out.append(latin);
            }
        }
        return out.toString();
    }

    /** 16-bit little-endian PCM to floats in [-1, 1]. */
    public static float[] toFloats(byte[] pcm16) {
        float[] out = new float[pcm16.length / 2];
        for (int i = 0; i < out.length; i++) {
            short s = (short) ((pcm16[2 * i] & 0xff) | (pcm16[2 * i + 1] << 8));
            out[i] = s / 32768f;
        }
        return out;
    }
}
