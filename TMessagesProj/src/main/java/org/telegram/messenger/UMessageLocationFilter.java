package org.telegram.messenger;

import android.location.Location;

/**
 * Cleans raw fixes before they leave the phone, the way navigators do:
 * a Kalman filter weighs each fix by its accuracy (a 30 m fix barely moves a 5 m estimate),
 * impossible jumps are dropped, and while standing still the position is locked so GPS wander
 * never "walks" the pin away on its own.
 */
public class UMessageLocationFilter {

    private static final float MAX_SPEED_MS = 70f;       // ~250 km/h, anything faster is a bad fix
    private static final float WALK_NOISE_MS = 3f;       // how fast the true position may drift between fixes
    private static final float STILL_SPEED_MS = 0.6f;    // below this the phone is treated as standing
    private static final float LOCK_MIN_M = 6f;

    private double lat, lng;
    private double variance = -1; // metres², < 0 = no estimate yet
    private long timeMs;
    private double lockLat, lockLng;
    private boolean locked;

    public synchronized Location apply(Location fix) {
        if (fix == null) return null;
        float accuracy = Math.max(1f, fix.hasAccuracy() ? fix.getAccuracy() : 30f);
        long t = fix.getTime();
        if (variance < 0 || t - timeMs > 120_000) {
            lat = fix.getLatitude();
            lng = fix.getLongitude();
            variance = accuracy * accuracy;
            timeMs = t;
            lockLat = lat;
            lockLng = lng;
            locked = true;
            return out(fix, lat, lng);
        }
        double dt = Math.max(0, t - timeMs) / 1000.0;
        float[] d = new float[1];
        Location.distanceBetween(lat, lng, fix.getLatitude(), fix.getLongitude(), d);
        if (dt > 0 && d[0] / dt > MAX_SPEED_MS && d[0] > accuracy * 2) {
            return null; // a jump no person or car makes: multipath reflection, drop it
        }
        float speed = fix.hasSpeed() ? fix.getSpeed() : -1f;
        float q = Math.max(WALK_NOISE_MS, speed > 0 ? speed : 0);
        variance += dt * q * q;
        double k = variance / (variance + accuracy * accuracy);
        lat += k * (fix.getLatitude() - lat);
        lng += k * (fix.getLongitude() - lng);
        variance = (1 - k) * variance;
        timeMs = t;

        Location.distanceBetween(lockLat, lockLng, lat, lng, d);
        boolean still = speed >= 0 ? speed < STILL_SPEED_MS : d[0] < accuracy;
        if (locked && still && d[0] < Math.max(LOCK_MIN_M, accuracy * 0.8f)) {
            return out(fix, lockLat, lockLng);
        }
        lockLat = lat;
        lockLng = lng;
        locked = true;
        return out(fix, lat, lng);
    }

    private Location out(Location fix, double la, double lo) {
        Location l = new Location(fix);
        l.setLatitude(la);
        l.setLongitude(lo);
        l.setAccuracy((float) Math.min(fix.hasAccuracy() ? fix.getAccuracy() : 30f, Math.sqrt(variance)));
        return l;
    }
}
