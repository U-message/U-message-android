package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Waze-style road reports (police, mobile radar, accident, road works) shared between U message
 * drivers through umessage-premium-server (/api/loc?road=1). Drivers add one with a tap; drivers
 * passing later confirm or deny it, and the server keeps or drops it from those votes.
 */
public class UMessageRoadReports {

    public static final String POLICE = "police", RADAR = "radar", ACCIDENT = "accident", WORKS = "works";
    public static final String[] TYPES = {POLICE, RADAR, ACCIDENT, WORKS};

    private static final String URL = UMessagePremiumController.BASE_URL + "/api/loc?road=1";
    private static final ExecutorService network = Executors.newSingleThreadExecutor();

    public static class Report {
        public String id, type;
        public double lat, lng;
        public int up, down, voted;
        public long time, expires;
        public boolean mine;
        /** share of confirmations among votes, 0..1; low = disputed, still collecting votes */
        public double confidence = 1;

        static Report from(JSONObject o) {
            Report r = new Report();
            r.id = o.optString("id");
            r.type = o.optString("type");
            r.lat = o.optDouble("lat");
            r.lng = o.optDouble("lng");
            r.up = o.optInt("up");
            r.down = o.optInt("down");
            r.voted = o.optInt("voted");
            r.time = o.optLong("t");
            r.expires = o.optLong("exp");
            r.mine = o.optBoolean("mine");
            r.confidence = o.optDouble("confidence", 1);
            return r;
        }
    }

    public interface Callback<T> {
        void run(T result);
    }

    private static String http(String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            if (code >= 400) throw new Exception("HTTP " + code + " " + out);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject base(int account, String action) throws Exception {
        long me = UserConfig.getInstance(account).getClientUserId();
        String token = UMessagePremiumController.getInstance().getToken(me);
        if (me == 0 || token == null) throw new IllegalStateException("not registered");
        return new JSONObject().put("userId", String.valueOf(me)).put("token", token).put("action", action);
    }

    /** Reports within ~15 km; result is null on failure. */
    public static void list(int account, double lat, double lng, Callback<ArrayList<Report>> callback) {
        network.execute(() -> {
            ArrayList<Report> result = null;
            try {
                // the list only needs the area: a ~1 km cell, not where we are
                double qLat = Math.round(lat / 0.01) * 0.01, qLng = Math.round(lng / 0.01) * 0.01;
                JSONObject r = new JSONObject(http(base(account, "list").put("lat", qLat).put("lng", qLng).toString()));
                JSONArray arr = r.optJSONArray("reports");
                result = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) result.add(Report.from(arr.getJSONObject(i)));
            } catch (Exception e) {
                FileLog.e(e);
            }
            ArrayList<Report> out = result;
            AndroidUtilities.runOnUIThread(() -> callback.run(out));
        });
    }

    /** Adds (or confirms an identical nearby) report; result is null on failure. */
    public static void add(int account, String type, double lat, double lng, Callback<Report> callback) {
        network.execute(() -> {
            Report result = null;
            try {
                JSONObject r = new JSONObject(http(base(account, "add").put("type", type).put("lat", lat).put("lng", lng).toString()));
                JSONObject o = r.optJSONObject("report");
                if (o != null) result = Report.from(o);
            } catch (Exception e) {
                FileLog.e(e);
            }
            Report out = result;
            AndroidUtilities.runOnUIThread(() -> callback.run(out));
        });
    }

    /** vote = 1 (still there) or -1 (gone). Callback gets the updated report, or null when it was removed / failed. */
    public static void vote(int account, Report report, int vote, Callback<Report> callback) {
        network.execute(() -> {
            Report result = null;
            try {
                JSONObject r = new JSONObject(http(base(account, "vote").put("id", report.id).put("vote", vote)
                        .put("lat", report.lat).put("lng", report.lng).toString()));
                JSONObject o = r.optJSONObject("report");
                if (o != null) result = Report.from(o);
            } catch (Exception e) {
                FileLog.e(e);
            }
            Report out = result;
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.run(out));
        });
    }
}
