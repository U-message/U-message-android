package org.telegram.messenger;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Snaps a moving friend onto the street network, like a navigator does: the last few fixes go to the
 * Mapbox Map Matching API (walking profile on foot, so OSM sidewalks and footpaths win over the roadway
 * when the fixes are closer to them; driving profile at car speed). Standing friends are never matched.
 * A fix is held back for at most {@link #WAIT_MS} while its match is in flight, then shown raw.
 */
public class UMessageMapMatcher {

    public interface Callback {
        void onMatched();
    }

    private static final int HISTORY = 8;
    private static final long HISTORY_MS = 90_000;
    private static final long WAIT_MS = 1500;
    private static final float MOVING_KMH = 1.5f;
    private static final float DRIVING_KMH = 12f;
    private static final long MATCH_EVERY_MS = 3000; // keeps a watched walker at ~1200 API calls per hour
    private static final long DELTA_TTL_MS = 10_000;

    private static final ExecutorService network = Executors.newSingleThreadExecutor();

    private final String token;
    private final Callback callback;
    private final HashMap<Long, ArrayList<double[]>> history = new HashMap<>(); // lat, lng, time ms, accuracy
    private final HashMap<Long, double[]> matched = new HashMap<>();             // fix time ms, lat, lng
    private final HashMap<Long, long[]> pending = new HashMap<>();               // fix time ms, started at
    private final HashMap<Long, double[]> delta = new HashMap<>();               // snapped - raw lat, lng, at ms
    private final HashMap<Long, Long> lastRequest = new HashMap<>();

    public UMessageMapMatcher(String token, Callback callback) {
        this.token = token;
        this.callback = callback;
    }

    /** Where to draw this fix: {lat, lng}, or null while its match is still on the way. */
    public double[] position(long uid, UMessageFriendLocations.Loc loc) {
        ArrayList<double[]> h = history.get(uid);
        if (h == null) history.put(uid, h = new ArrayList<>());
        if (h.isEmpty() || h.get(h.size() - 1)[2] != loc.time) {
            h.add(new double[]{loc.lat, loc.lng, loc.time, loc.accuracy});
            while (h.size() > HISTORY || (h.size() > 1 && loc.time - h.get(0)[2] > HISTORY_MS)) h.remove(0);
        }
        double[] m = matched.get(uid);
        if (m != null && m[0] == loc.time) return new double[]{m[1], m[2]};

        float kmh = speedKmh(loc, h);
        if (h.size() < 2 || kmh < MOVING_KMH || android.text.TextUtils.isEmpty(token)) {
            return new double[]{loc.lat, loc.lng};
        }
        long now = System.currentTimeMillis();
        long[] p = pending.get(uid);
        if (p != null && p[0] == loc.time) {
            return now - p[1] < WAIT_MS ? null : new double[]{loc.lat, loc.lng};
        }
        Long last = lastRequest.get(uid);
        if (last != null && now - last < MATCH_EVERY_MS) {
            // between API calls keep the fix on the same side of the street by reusing the last correction
            double[] dl = delta.get(uid);
            return dl != null && now - dl[2] < DELTA_TTL_MS ? new double[]{loc.lat + dl[0], loc.lng + dl[1]} : new double[]{loc.lat, loc.lng};
        }
        lastRequest.put(uid, now);
        pending.put(uid, new long[]{loc.time, now});
        request(uid, loc, new ArrayList<>(h), kmh >= DRIVING_KMH ? "driving" : "walking");
        return null;
    }

    private static float speedKmh(UMessageFriendLocations.Loc loc, ArrayList<double[]> h) {
        if (loc.speedKmh >= 0) return loc.speedKmh;
        if (h.size() < 2) return 0;
        double[] a = h.get(h.size() - 2), b = h.get(h.size() - 1);
        float[] d = new float[1];
        Location.distanceBetween(a[0], a[1], b[0], b[1], d);
        double s = (b[2] - a[2]) / 1000.0;
        return s > 0 ? (float) (d[0] / s * 3.6) : 0;
    }

    private void request(long uid, UMessageFriendLocations.Loc loc, ArrayList<double[]> points, String profile) {
        StringBuilder coords = new StringBuilder(), radiuses = new StringBuilder(), timestamps = new StringBuilder();
        long lastSec = -1;
        for (double[] pt : points) {
            long sec = (long) (pt[2] / 1000);
            if (sec <= lastSec) continue; // the API wants strictly increasing timestamps
            lastSec = sec;
            if (coords.length() > 0) {
                coords.append(';');
                radiuses.append(';');
                timestamps.append(';');
            }
            coords.append(String.format(Locale.US, "%.6f,%.6f", pt[1], pt[0]));
            radiuses.append(Math.round(Math.max(5, Math.min(50, pt[3] > 0 ? pt[3] : 20))));
            timestamps.append(sec);
        }
        String url = "https://api.mapbox.com/matching/v5/mapbox/" + profile + "/" + coords
                + "?radiuses=" + radiuses + "&timestamps=" + timestamps + "&tidy=false&access_token=" + token;
        network.execute(() -> {
            double[] result = null;
            try {
                JSONArray tracepoints = new JSONObject(get(url)).optJSONArray("tracepoints");
                if (tracepoints != null && tracepoints.length() > 0) {
                    JSONObject last = tracepoints.optJSONObject(tracepoints.length() - 1);
                    if (last != null) {
                        JSONArray xy = last.getJSONArray("location");
                        double lat = xy.getDouble(1), lng = xy.getDouble(0);
                        float[] d = new float[1];
                        Location.distanceBetween(loc.lat, loc.lng, lat, lng, d);
                        // never pull further than the fix itself could be wrong
                        if (d[0] <= Math.max(25, loc.accuracy * 1.5f)) result = new double[]{lat, lng};
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            boolean found = result != null;
            double[] snapped = found ? result : new double[]{loc.lat, loc.lng};
            AndroidUtilities.runOnUIThread(() -> {
                matched.put(uid, new double[]{loc.time, snapped[0], snapped[1]});
                if (found) delta.put(uid, new double[]{snapped[0] - loc.lat, snapped[1] - loc.lng, System.currentTimeMillis()});
                long[] p = pending.get(uid);
                if (p != null && p[0] == loc.time) pending.remove(uid);
                callback.onMatched();
            });
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
