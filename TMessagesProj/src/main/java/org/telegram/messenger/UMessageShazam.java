package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Song recognition through Shazam. The audio fingerprint is Shazam's own signature format, ported
 * from SongRec / shazamio (FFT peaks in four frequency bands); only that signature, never the
 * audio, is sent to amp.shazam.com.
 */
public final class UMessageShazam {

    public static final int SAMPLE_RATE = 16000;
    /** Shazam matches on at most ~12 s of audio; longer signatures are rejected. */
    public static final int MAX_SAMPLES = SAMPLE_RATE * 12;

    public static final class Track {
        public String title;
        public String artist;
        public String coverUrl;
        public String shazamUrl;
        public String appleMusicUrl;
        /** The song's 30 s hook (audio), when Shazam gives one. */
        public String previewUrl;

        public String query() {
            return TextUtils.isEmpty(artist) ? title : artist + " " + title;
        }

        public String spotifyUrl() {
            return "https://open.spotify.com/search/" + Uri.encode(query());
        }

        public String youtubeUrl() {
            return "https://music.youtube.com/search?q=" + Uri.encode(query());
        }

        public String shareText() {
            final StringBuilder sb = new StringBuilder("🎵 ").append(title);
            if (!TextUtils.isEmpty(artist)) {
                sb.append(" — ").append(artist);
            }
            final String link = !TextUtils.isEmpty(shazamUrl) ? shazamUrl : spotifyUrl();
            return sb.append('\n').append(link).toString();
        }
    }

    /** Recognizes 16 kHz mono PCM; returns null when Shazam knows no match. Blocking, network. */
    public static Track recognize(short[] samples, int count) throws Exception {
        final int from = Math.max(0, count - MAX_SAMPLES);
        final Generator generator = new Generator();
        generator.feed(samples, from, count - from);
        final byte[] signature = generator.encode();

        final JSONObject body = new JSONObject();
        final JSONObject sig = new JSONObject();
        final long now = System.currentTimeMillis();
        sig.put("uri", "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(signature, Base64.NO_WRAP));
        sig.put("samplems", generator.numberSamples * 1000L / SAMPLE_RATE);
        sig.put("timestamp", now);
        body.put("signature", sig);
        body.put("timestamp", now);
        body.put("timezone", TimeZone.getDefault().getID());
        body.put("context", new JSONObject());
        body.put("geolocation", new JSONObject());

        final String url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"
                + UUID.randomUUID().toString().toUpperCase() + "/" + UUID.randomUUID()
                + "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3";
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Language", "en_US");
            connection.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("shazam http " + code);
            }
            final ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    response.write(buffer, 0, read);
                }
            }
            return parse(new JSONObject(response.toString("UTF-8")));
        } finally {
            connection.disconnect();
        }
    }

    private static Track parse(JSONObject json) {
        final JSONArray matches = json.optJSONArray("matches");
        final JSONObject track = json.optJSONObject("track");
        if (matches == null || matches.length() == 0 || track == null) {
            return null;
        }
        final Track result = new Track();
        result.title = track.optString("title");
        result.artist = track.optString("subtitle");
        result.shazamUrl = track.optString("url", null);
        final JSONObject images = track.optJSONObject("images");
        if (images != null) {
            result.coverUrl = images.optString("coverarthq", images.optString("coverart", null));
        }
        final JSONObject hub = track.optJSONObject("hub");
        if (hub != null) {
            result.appleMusicUrl = findAppleMusic(hub.optJSONArray("actions"));
            result.previewUrl = findPreview(hub.optJSONArray("actions"));
            final JSONArray options = hub.optJSONArray("options");
            for (int i = 0; options != null && result.appleMusicUrl == null && i < options.length(); i++) {
                final JSONObject option = options.optJSONObject(i);
                if (option != null) {
                    result.appleMusicUrl = findAppleMusic(option.optJSONArray("actions"));
                }
            }
        }
        return result;
    }

    private static String findPreview(JSONArray actions) {
        for (int i = 0; actions != null && i < actions.length(); i++) {
            final JSONObject action = actions.optJSONObject(i);
            final String uri = action != null ? action.optString("uri", null) : null;
            if (uri != null && uri.startsWith("https://") && (uri.contains(".m4a") || uri.contains("audio-ssl") || uri.contains(".mp3"))) {
                return uri;
            }
        }
        return null;
    }

    private static String findAppleMusic(JSONArray actions) {
        for (int i = 0; actions != null && i < actions.length(); i++) {
            final JSONObject action = actions.optJSONObject(i);
            final String uri = action != null ? action.optString("uri", null) : null;
            if (uri != null && uri.startsWith("https://music.apple.com")) {
                return uri;
            }
        }
        return null;
    }

    /** Shazam signature generator (SongRec's algorithm.rs): 2048-point FFT every 128 samples, spread peaks, banded. */
    private static final class Generator {

        private static final float[] HANNING = new float[2048];
        private static final int[] FORMERS = {1, 3, 6};
        private static final int[] NEIGHBORS = {-10, -7, -4, -3, 1, 2, 5, 8};
        private static final int[] OTHERS = {-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249};

        static {
            for (int i = 0; i < 2048; i++) {
                HANNING[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * (i + 1) / 2049)));
            }
        }

        private final short[] ring = new short[2048];
        private int ringPos;
        private final float[][] fftOutputs = new float[256][1025];
        private int fftPos;
        private final float[][] spreadOutputs = new float[256][1025];
        private int spreadPos;
        private int spreadWritten;
        private final double[] re = new double[2048];
        private final double[] im = new double[2048];
        private final float[] spread = new float[1025];
        /** Per band (250-520, 520-1450, 1450-3500, 3500-5500 Hz): {fft pass, magnitude, corrected bin}. */
        @SuppressWarnings("unchecked")
        private final ArrayList<int[]>[] bands = new ArrayList[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
        int numberSamples;

        void feed(short[] samples, int offset, int length) {
            for (int i = 0; i + 128 <= length; i += 128) {
                numberSamples += 128;
                doFft(samples, offset + i);
                doPeakSpreading();
                if (spreadWritten >= 46) {
                    doPeakRecognition();
                }
            }
        }

        private void doFft(short[] samples, int offset) {
            System.arraycopy(samples, offset, ring, ringPos, 128);
            ringPos = (ringPos + 128) & 2047;
            for (int i = 0; i < 2048; i++) {
                re[i] = ring[(ringPos + i) & 2047] * HANNING[i];
                im[i] = 0;
            }
            fft(re, im);
            final float[] out = fftOutputs[fftPos];
            for (int i = 0; i <= 1024; i++) {
                out[i] = (float) Math.max((re[i] * re[i] + im[i] * im[i]) / (1 << 17), 0.0000000001);
            }
            fftPos = (fftPos + 1) & 255;
        }

        private void doPeakSpreading() {
            System.arraycopy(fftOutputs[(fftPos - 1) & 255], 0, spread, 0, 1025);
            for (int p = 0; p <= 1022; p++) {
                spread[p] = Math.max(spread[p], Math.max(spread[p + 1], spread[p + 2]));
            }
            for (int p = 0; p <= 1024; p++) {
                float max = spread[p];
                for (int former : FORMERS) {
                    final float[] formerOutput = spreadOutputs[(spreadPos - former) & 255];
                    max = Math.max(max, formerOutput[p]);
                    formerOutput[p] = max;
                }
            }
            System.arraycopy(spread, 0, spreadOutputs[spreadPos], 0, 1025);
            spreadPos = (spreadPos + 1) & 255;
            spreadWritten++;
        }

        private void doPeakRecognition() {
            final float[] fft46 = fftOutputs[(fftPos - 46) & 255];
            final float[] spread49 = spreadOutputs[(spreadPos - 49) & 255];
            for (int bin = 10; bin <= 1014; bin++) {
                final float value = fft46[bin];
                if (value < 1f / 64 || value < spread49[bin - 1]) {
                    continue;
                }
                float maxNeighbor = 0;
                for (int offset : NEIGHBORS) {
                    maxNeighbor = Math.max(maxNeighbor, spread49[bin + offset]);
                }
                if (value <= maxNeighbor) {
                    continue;
                }
                float maxOther = maxNeighbor;
                for (int offset : OTHERS) {
                    maxOther = Math.max(maxOther, spreadOutputs[(spreadPos + offset) & 255][bin - 1]);
                }
                if (value <= maxOther) {
                    continue;
                }
                final double magnitude = magnitude(value);
                final double before = magnitude(fft46[bin - 1]);
                final double after = magnitude(fft46[bin + 1]);
                final double variation1 = magnitude * 2 - before - after;
                final double variation2 = (after - before) * 32 / variation1;
                final int correctedBin = (int) (bin * 64 + variation2);
                final double hz = correctedBin * (16000.0 / 2 / 1024 / 64);
                final int band;
                if (hz < 250) {
                    continue;
                } else if (hz < 520) {
                    band = 0;
                } else if (hz < 1450) {
                    band = 1;
                } else if (hz < 3500) {
                    band = 2;
                } else if (hz <= 5500) {
                    band = 3;
                } else {
                    continue;
                }
                bands[band].add(new int[]{spreadWritten - 46, (int) magnitude, correctedBin});
            }
        }

        private static double magnitude(float value) {
            return Math.log(Math.max(1.0 / 64, value)) * 1477.3 + 6144;
        }

        /** In-place iterative radix-2 FFT. */
        private static void fft(double[] re, double[] im) {
            final int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    double t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                final double angle = -2 * Math.PI / len;
                final double wRe = Math.cos(angle), wIm = Math.sin(angle);
                for (int i = 0; i < n; i += len) {
                    double curRe = 1, curIm = 0;
                    for (int k = 0; k < len / 2; k++) {
                        final int a = i + k, b = a + len / 2;
                        final double tRe = re[b] * curRe - im[b] * curIm;
                        final double tIm = re[b] * curIm + im[b] * curRe;
                        re[b] = re[a] - tRe;
                        im[b] = im[a] - tIm;
                        re[a] += tRe;
                        im[a] += tIm;
                        final double next = curRe * wRe - curIm * wIm;
                        curIm = curRe * wIm + curIm * wRe;
                        curRe = next;
                    }
                }
            }
        }

        /** Shazam's binary signature: 48-byte header, then one TLV per frequency band, CRC32 over everything after byte 8. */
        byte[] encode() {
            final ByteArrayOutputStream contents = new ByteArrayOutputStream();
            for (int band = 0; band < 4; band++) {
                if (bands[band].isEmpty()) {
                    continue;
                }
                final ByteArrayOutputStream peaks = new ByteArrayOutputStream();
                int pass = 0;
                for (int[] peak : bands[band]) {
                    if (peak[0] - pass >= 255) {
                        peaks.write(0xff);
                        writeInt(peaks, peak[0]);
                        pass = peak[0];
                    }
                    peaks.write(peak[0] - pass);
                    writeShort(peaks, peak[1]);
                    writeShort(peaks, peak[2]);
                    pass = peak[0];
                }
                writeInt(contents, 0x60030040 + band);
                writeInt(contents, peaks.size());
                final byte[] data = peaks.toByteArray();
                contents.write(data, 0, data.length);
                for (int i = 0; i < (4 - data.length % 4) % 4; i++) {
                    contents.write(0);
                }
            }
            final byte[] body = contents.toByteArray();
            final ByteBuffer buffer = ByteBuffer.allocate(48 + 8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0xcafe2580);
            buffer.putInt(0); // crc32, filled below
            buffer.putInt(body.length + 8);
            buffer.putInt(0x94119c00);
            buffer.putInt(0).putInt(0).putInt(0);
            buffer.putInt(3 << 27); // sample rate id 3 = 16 kHz
            buffer.putInt(0).putInt(0);
            buffer.putInt(numberSamples + (int) (SAMPLE_RATE * 0.24));
            buffer.putInt((15 << 19) + 0x40000);
            buffer.putInt(0x40000000);
            buffer.putInt(body.length + 8);
            buffer.put(body);
            final byte[] result = buffer.array();
            final CRC32 crc = new CRC32();
            crc.update(result, 8, result.length - 8);
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (int) crc.getValue());
            return result;
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            out.write(value);
            out.write(value >> 8);
            out.write(value >> 16);
            out.write(value >> 24);
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write(value);
            out.write(value >> 8);
        }
    }
}
