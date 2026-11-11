package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.location.Location;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.PathParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Draws friends (and us) on top of the map as top-down "videogame" figures: a walker with the avatar as
 * its head and the friend's chosen idle animation while standing, a small car above walking speed,
 * footprints behind the walker, a marker where they got into a car, the speed, and a "👋" bubble after a hi.
 */
public class UMessageFriendMapOverlay extends View {

    public interface Projector {
        /** Screen position of a coordinate in this view's space, or null while the map is not ready. */
        PointF toScreen(double lat, double lng);
    }

    public interface OnPinClick {
        void onClick(long uid);
    }

    public static final float WALK_KMH = 7f;
    private static final float MIN_SPEED_KMH = 2f;   // below this a person is standing
    private static final float MIN_MOVE_M = 20f;     // smallest step that is not GPS wobble
    private static final float MAX_SPEED_KMH = 250f; // faster than this is a bad fix (teleport)
    private static final int TRAIL_MAX = 40;
    private static final int TRAIL_SECONDS = 30 * 60;

    private static final String FOOT = "M0,-7C3.2,-7 4.2,-2.5 3.6,1.8C3.1,5 1.7,7 0,7C-1.7,7 -3.1,5 -3.6,1.8C-4.2,-2.5 -3.2,-7 0,-7Z";

    public static class Pin {
        public long uid;
        public TLRPC.User user;
        public ImageReceiver avatar;
        double lat, lng, fromLat, fromLng, toLat, toLng;
        long moveStart;
        long appearStart;
        float speedKmh;
        long hiUntil;
        final ArrayList<double[]> trail = new ArrayList<>(); // lat, lng, unix seconds
        boolean alive = true;
        boolean faceRight;
        int lastFixDate;
        float accuracy;
        long lastMoveAt;
        long playStart;
        long moveDuration = 1200; // the gap between fixes: the pin glides the whole way instead of jumping
        float angle;              // screen heading of the game sprite, degrees, 0 = right
        boolean angleSet;
        boolean inCar;
        double[] carAt; // lat, lng, unix seconds where the walk ended and the drive began
        String idle;    // what the walker does while standing, chosen by the friend (UMessageFriendLocations.IDLE_STYLES)
        float walkPhase;
        long lastFrame;
    }

    // walker below CAR_ON, car above it; the gap keeps a car at a red light from turning into a walker
    private static final float CAR_ON_KMH = 9f, CAR_OFF_KMH = 5f;
    private static final long EXTRAPOLATE_MS = 3000;
    private static final int[] SPRITE_COLORS = {0xff0a84ff, 0xffff453a, 0xff30d158, 0xffff9f0a, 0xffbf5af2, 0xff40c8e0};

    private final ArrayList<Pin> pins = new ArrayList<>();
    private Projector projector;
    private OnPinClick onPinClick;
    private String speedFormat = "%d km/h";

    private final Path footPath;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Path tmpPath = new Path();
    private Pin pressed;

    public UMessageFriendMapOverlay(Context context) {
        super(context);
        footPath = PathParser.createPathFromPathData(FOOT);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(AndroidUtilities.bold());
    }

    public void setProjector(Projector projector) {
        this.projector = projector;
        invalidate();
    }

    public void setOnPinClick(OnPinClick onPinClick) {
        this.onPinClick = onPinClick;
    }

    public void setSpeedFormat(String format) {
        speedFormat = format;
    }

    public Pin find(long uid) {
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).uid == uid) return pins.get(i);
        }
        return null;
    }

    /** Adds or moves a friend's pin; consecutive positions build the trail and the speed. */
    /**
     * Adds or moves a friend's pin. GPS wanders by tens of metres while standing still, so a fix only
     * counts as movement when it lands outside the accuracy circle (at least 20 m); jitter never extends
     * the trail or produces a speed, and speed fades back to zero once real movement stops.
     */
    public void update(long uid, TLRPC.User user, ImageReceiver avatar, double lat, double lng, int date, float knownSpeedKmh, float accuracyM) {
        Pin pin = find(uid);
        long now = SystemClock.elapsedRealtime();
        if (pin == null) {
            pin = new Pin();
            pin.uid = uid;
            pin.lat = pin.fromLat = pin.toLat = lat;
            pin.lng = pin.fromLng = pin.toLng = lng;
            pin.appearStart = now;
            pin.lastFixDate = date;
            pin.accuracy = accuracyM;
            pins.add(pin);
            pin.trail.add(new double[]{lat, lng, date});
        } else if (date > pin.lastFixDate) {
            double[] anchor = pin.trail.get(pin.trail.size() - 1); // last confirmed position
            float[] dist = new float[1];
            Location.distanceBetween(anchor[0], anchor[1], lat, lng, dist);
            float noise = Math.max(MIN_MOVE_M, Math.max(accuracyM, pin.accuracy) * 1.2f);
            double seconds = date - anchor[2];
            float kmh = seconds > 0 ? (float) (dist[0] / seconds * 3.6) : 0f;
            boolean gpsSaysMoving = knownSpeedKmh >= MIN_SPEED_KMH;
            // with GNSS reporting motion even a 2 m step is real (the sender already locks standing fixes)
            if ((dist[0] > noise || (gpsSaysMoving && dist[0] > 2f)) && kmh < MAX_SPEED_KMH) {
                float measured = knownSpeedKmh >= 0 ? knownSpeedKmh : kmh;
                pin.speedKmh = pin.speedKmh == 0 ? measured : pin.speedKmh * 0.4f + measured * 0.6f;
                pin.lastMoveAt = now;
                updateCarState(pin, lat, lng, date);
                if (!pin.inCar) pin.trail.add(new double[]{lat, lng, date}); // a drive leaves no trail
                while (pin.trail.size() > TRAIL_MAX || (pin.trail.size() > 1 && date - pin.trail.get(0)[2] > TRAIL_SECONDS)) {
                    pin.trail.remove(0);
                }
                if (Math.abs(lng - pin.toLng) > 1e-6) {
                    pin.faceRight = lng > pin.toLng; // the artwork faces left
                }
                pin.fromLat = pin.lat;
                pin.fromLng = pin.lng;
                pin.toLat = lat;
                pin.toLng = lng;
                pin.moveDuration = pin.moveStart == 0 ? 1200 : Math.max(800, Math.min(5000, now - pin.moveStart));
                pin.moveStart = now;
            } else {
                // standing still: ignore the wobble, let the speed fade out
                pin.speedKmh = gpsSaysMoving ? knownSpeedKmh : pin.speedKmh * 0.5f;
                if (pin.speedKmh < MIN_SPEED_KMH) pin.speedKmh = 0;
                updateCarState(pin, pin.toLat, pin.toLng, date);
            }
            pin.lastFixDate = date;
            pin.accuracy = accuracyM > 0 ? accuracyM : pin.accuracy;
        }
        pin.user = user;
        pin.avatar = avatar;
        pin.alive = true;
        invalidate();
    }

    /**
     * Walker or car, with a gap between the thresholds so a car at a red light stays a car. Getting in keeps
     * the footprints up to that spot and marks it; getting out starts a fresh set of footprints.
     */
    private static void updateCarState(Pin pin, double lat, double lng, int date) {
        if (!pin.inCar && pin.speedKmh > CAR_ON_KMH) {
            pin.inCar = true;
            double[] last = pin.trail.isEmpty() ? new double[]{pin.fromLat, pin.fromLng, date} : pin.trail.get(pin.trail.size() - 1);
            pin.carAt = new double[]{last[0], last[1], last[2]};
        } else if (pin.inCar && pin.speedKmh < CAR_OFF_KMH) {
            pin.inCar = false;
            pin.carAt = null;
            pin.trail.clear();
            pin.trail.add(new double[]{lat, lng, date});
        }
    }

    public void setIdle(long uid, String idle) {
        Pin pin = find(uid);
        if (pin != null) pin.idle = idle;
    }

    public void remove(long uid) {
        Pin pin = find(uid);
        if (pin != null) pins.remove(pin);
        invalidate();
    }

    public void sayHi(long uid) {
        Pin pin = find(uid);
        if (pin != null) {
            pin.hiUntil = SystemClock.elapsedRealtime() + 4500;
            invalidate();
        }
    }

    public float getSpeed(long uid) {
        Pin pin = find(uid);
        return pin == null ? 0 : pin.speedKmh;
    }

    // ---------------------------------------------------------------- drawing

    // ---------------------------------------------------------------- zones

    /** lat, lng, radius metres, active (1) or pending (0). */
    private final ArrayList<double[]> zones = new ArrayList<>();
    private double[] editZone;
    private final android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0);

    public void setZones(ArrayList<double[]> list) {
        zones.clear();
        zones.addAll(list);
        invalidate();
    }

    /** The circle being drawn in the zone editor (null when not editing). */
    public void setEditZone(double[] zone) {
        editZone = zone;
        invalidate();
    }

    private void drawZone(Canvas canvas, double lat, double lng, double radius, boolean active, boolean editing) {
        PointF c = projector.toScreen(lat, lng);
        PointF n = projector.toScreen(lat + radius / 111320.0, lng);
        if (c == null || n == null) return;
        float r = (float) Math.hypot(n.x - c.x, n.y - c.y);
        if (r < 2) return;
        int color = editing ? 0xff0a84ff : active ? 0xff30d158 : 0xffff9f0a;
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha(editing ? 0x30 : 0x22);
        canvas.drawCircle(c.x, c.y, r, paint);
        strokePaint.setColor(color);
        strokePaint.setAlpha(0xdd);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setPathEffect(active && !editing ? null : dash);
        canvas.drawCircle(c.x, c.y, r, strokePaint);
        strokePaint.setPathEffect(null);
        if (editing) {
            paint.setColor(color);
            paint.setAlpha(255);
            canvas.drawCircle(c.x, c.y, dp(5), paint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (projector == null) {
            return;
        }
        // camera moves redraw through invalidateForCamera(); the zone editor through setEditZone()
        for (double[] z : zones) drawZone(canvas, z[0], z[1], z[2], z[3] > 0, false);
        if (editZone != null) {
            drawZone(canvas, editZone[0], editZone[1], editZone[2], true, true);
        }
        if (pins.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        float t = (now % 100_000) / 1000f;
        boolean animating = false;
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            if (pin.speedKmh > 0 && now - pin.lastMoveAt > 90_000) pin.speedKmh = 0; // no real movement lately
            move(pin, now);
            drawTrail(canvas, pin);
            if (now - pin.moveStart < pin.moveDuration + EXTRAPOLATE_MS || pin.speedKmh >= MIN_SPEED_KMH
                    || now - pin.appearStart < 650 || now < pin.hiUntil || pin.inCar) {
                animating = true;
            }
        }
        for (int i = 0; i < pins.size(); i++) {
            drawPin(canvas, pins.get(i), now, t);
        }
        if (animating) {
            postInvalidateOnAnimation();
        } else {
            // only standing walkers (idle poses): a low frame rate is plenty and spares the battery
            postInvalidateDelayed(IDLE_FRAME_MS);
        }
    }

    private static final long IDLE_FRAME_MS = 100;

    /** The map camera moved: pins and zones follow it on the next frame. */
    public void invalidateForCamera() {
        postInvalidateOnAnimation();
    }

    /**
     * Glides at constant speed from the previous fix to the new one over the time the fix took to arrive,
     * so the next fix lands just as the glide ends: no jump, no stop-and-go. When a fix is late the pin keeps
     * going along its heading at its speed for a few seconds instead of freezing.
     */
    private void move(Pin pin, long now) {
        float p = (now - pin.moveStart) / (float) pin.moveDuration;
        if (p <= 1f) {
            pin.lat = pin.fromLat + (pin.toLat - pin.fromLat) * p;
            pin.lng = pin.fromLng + (pin.toLng - pin.fromLng) * p;
            return;
        }
        pin.lat = pin.toLat;
        pin.lng = pin.toLng;
        long extra = now - pin.moveStart - pin.moveDuration;
        if (pin.speedKmh < MIN_SPEED_KMH || extra > EXTRAPOLATE_MS) return;
        float[] d = new float[1];
        Location.distanceBetween(pin.fromLat, pin.fromLng, pin.toLat, pin.toLng, d);
        if (d[0] < 1f) return;
        float metres = Math.min(d[0], pin.speedKmh / 3.6f * extra / 1000f); // never further than one step
        float k = metres / d[0];
        pin.lat = pin.toLat + (pin.toLat - pin.fromLat) * k;
        pin.lng = pin.toLng + (pin.toLng - pin.fromLng) * k;
    }

    private void drawTrail(Canvas canvas, Pin pin) {
        int n = pin.trail.size();
        if (n < 2 && !pin.inCar) {
            return;
        }
        ArrayList<PointF> pts = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            PointF s = projector.toScreen(pin.trail.get(i)[0], pin.trail.get(i)[1]);
            if (s != null) pts.add(s);
        }
        if (pin.inCar) {
            // the footprints stop where they got in; the car itself leaves nothing behind
            if (pin.carAt == null || System.currentTimeMillis() / 1000 - pin.carAt[2] > TRAIL_SECONDS) return;
            PointF at = projector.toScreen(pin.carAt[0], pin.carAt[1]);
            if (at == null) return;
            if (pts.isEmpty()) pts.add(at);
            else pts.set(pts.size() - 1, at);
            if (pts.size() >= 2) drawFootprints(canvas, pin, pts, 0);
            drawGotInCar(canvas, pin, at);
            return;
        }
        PointF head = projector.toScreen(pin.lat, pin.lng);
        if (head == null || pts.size() < 1) return;
        pts.set(pts.size() - 1, head);
        if (pts.size() < 2) return;
        drawFootprints(canvas, pin, pts, dp(22));
    }

    private int spriteColor(Pin pin) {
        return SPRITE_COLORS[(int) (Math.abs(pin.uid) % SPRITE_COLORS.length)];
    }

    /** Where the walk ended: a soft pulsing ring with "14:32 · got in a car". */
    private void drawGotInCar(Canvas canvas, Pin pin, PointF at) {
        int color = spriteColor(pin);
        float pulse = (SystemClock.elapsedRealtime() % 1800) / 1800f;
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setAlpha((int) (200 * (1f - pulse)));
        canvas.drawCircle(at.x, at.y, dp(6) + dp(10) * pulse, strokePaint);
        paint.setShader(null);
        paint.setColor(0xffffffff);
        canvas.drawCircle(at.x, at.y, dp(6), paint);
        paint.setColor(color);
        canvas.drawCircle(at.x, at.y, dp(4), paint);
        String time = org.telegram.messenger.LocaleController.getInstance().getFormatterDay().format((long) pin.carAt[2] * 1000);
        String label = org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.UMessageFriendGotInCar, time);
        textPaint.setTextSize(dp(11));
        float w = textPaint.measureText(label) + dp(14);
        rect.set(at.x - w / 2, at.y + dp(10), at.x + w / 2, at.y + dp(30));
        paint.setColor(0xe61c1c1e);
        canvas.drawRoundRect(rect, dp(10), dp(10), paint);
        textPaint.setColor(0xffffffff);
        canvas.drawText(label, rect.left + dp(7), rect.top + dp(14), textPaint);
    }

    /** Alternating footprints spaced along the path, the oldest faintest, starting {@code gap} behind the end. */
    private void drawFootprints(Canvas canvas, Pin pin, ArrayList<PointF> pts, float gap) {
        // walking: alternating footprints spaced along the path
        float total = 0;
        for (int i = 1; i < pts.size(); i++) {
            total += dist(pts.get(i - 1), pts.get(i));
        }
        if (total < dp(6)) return;
        float step = dp(15);
        int count = (int) (total / step);
        float acc = 0;
        int seg = 1;
        float segStart = 0;
        for (int k = 0; k <= count; k++) {
            float target = total - gap - k * step; // start a bit behind the pin, walk back in time
            if (target < 0) break;
            acc = 0;
            seg = 1;
            segStart = 0;
            for (; seg < pts.size(); seg++) {
                float d = dist(pts.get(seg - 1), pts.get(seg));
                if (acc + d >= target) {
                    segStart = acc;
                    break;
                }
                acc += d;
            }
            if (seg >= pts.size()) break;
            PointF a = pts.get(seg - 1), b = pts.get(seg);
            float d = Math.max(1, dist(a, b));
            float f = (target - segStart) / d;
            float x = a.x + (b.x - a.x) * f, y = a.y + (b.y - a.y) * f;
            float angle = (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)) + 90;
            float side = (k % 2 == 0 ? 1 : -1) * dp(4);
            float nx = -(b.y - a.y) / d, ny = (b.x - a.x) / d;
            float age = 1f - Math.min(1f, k * step / Math.max(total, 1));
            canvas.save();
            canvas.translate(x + nx * side, y + ny * side);
            canvas.rotate(angle);
            canvas.scale(dp(1) * 0.8f, dp(1) * 0.8f);
            paint.setShader(null);
            paint.setColor(spriteColor(pin));
            paint.setAlpha((int) (60 + 170 * age));
            canvas.drawPath(footPath, paint);
            canvas.drawCircle(-2.6f, -10.2f, 1.5f, paint);
            canvas.drawCircle(0f, -11f, 1.4f, paint);
            canvas.drawCircle(2.4f, -10.3f, 1.3f, paint);
            canvas.restore();
        }
    }

    private void drawPin(Canvas canvas, Pin pin, long now, float t) {
        PointF s = projector.toScreen(pin.lat, pin.lng);
        if (s == null) return;
        float appear = Math.min(1f, (now - pin.appearStart) / 650f);
        float pinTop = drawGameSprite(canvas, pin, s, now, appear);
        if (pin.speedKmh >= MIN_SPEED_KMH) {
            drawSpeed(canvas, s.x, s.y + dp(22), Math.round(pin.speedKmh), appear);
        }
        if (now < pin.hiUntil) {
            drawHi(canvas, s.x, pinTop - dp(6), (pin.hiUntil - now) / 4500f, t);
        }
    }

    private static final int SKIN = 0xffe0b48c, PANTS = 0xff2b3242;

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static int shade(int color, float k) {
        int r = Math.min(255, (int) (((color >> 16) & 0xff) * k));
        int g = Math.min(255, (int) (((color >> 8) & 0xff) * k));
        int b = Math.min(255, (int) ((color & 0xff) * k));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /** One arm or leg: a rounded bar from a pivot along an angle, optionally ending in a hand. */
    private void limb(Canvas canvas, float px, float py, float angle, float from, float to, float w, int color, int hand, int alpha) {
        canvas.save();
        canvas.translate(px, py);
        canvas.rotate(angle);
        paint.setColor(color);
        paint.setAlpha(alpha);
        rect.set(from, -w / 2, to, w / 2);
        canvas.drawRoundRect(rect, w / 2, w / 2, paint);
        if (hand != 0) {
            paint.setColor(hand);
            paint.setAlpha(alpha);
            canvas.drawCircle(to, 0, w * 0.58f, paint);
        }
        canvas.restore();
    }

    /**
     * Top-down "videogame" figure, drawn 1:1 from the Claude Design artboard ("Odamcha va mashina"): a 34-unit walker
     * whose head leads, shoulders and arms behind it, legs at the back; legs and arms swing in step with the real
     * speed and the friend's chosen idle animation plays while standing. Above walking speed it becomes the 48×26
     * car (no wheels visible from above, headlights, avatar on the roof). The body turns smoothly to the direction
     * of travel; the avatar itself always stays upright.
     */
    private float drawGameSprite(Canvas canvas, Pin pin, PointF s, long now, float appear) {
        float dt = pin.lastFrame == 0 ? 0 : Math.min(0.1f, (now - pin.lastFrame) / 1000f);
        pin.lastFrame = now;
        boolean moving = pin.speedKmh >= MIN_SPEED_KMH;

        PointF a = projector.toScreen(pin.fromLat, pin.fromLng), b = projector.toScreen(pin.toLat, pin.toLng);
        if (a != null && b != null && dist(a, b) > dp(1)) {
            float target = (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
            if (!pin.angleSet) {
                pin.angle = target;
                pin.angleSet = true;
            } else {
                float diff = ((target - pin.angle) % 360 + 540) % 360 - 180;
                pin.angle += diff * Math.min(1f, dt * 6f); // turn like a body, not a needle
            }
        }
        if (!pin.angleSet) {
            pin.angle = -90; // facing up until the first step
        }
        // the design's 0.5 s stride at walking pace, faster or slower with the real speed
        if (moving && !pin.inCar) pin.walkPhase += dt * (float) (Math.PI * 2) * (pin.speedKmh / 5f * 2f);
        float swing = moving && !pin.inCar ? (float) Math.sin(pin.walkPhase) : 0f;
        int color = spriteColor(pin);
        float u = dp(1.25f);
        int alpha = (int) (255 * appear);

        // pose in design units: legs ±5, arms ±4 along the body, shoulders sway ±3°
        float legL = -swing * 5, legR = swing * 5, armL = swing * 4, armR = -swing * 4;
        float armAngleL = 0, armAngleR = 0; // rotation from the hanging pose, positive = out to the side
        boolean armsRotated = false;
        float bodyRot = swing * 3f, hop = 0, breathe = 1, headNod = 1, headSide = 0;
        String idle = !moving && !pin.inCar ? (pin.idle == null ? "calm" : pin.idle) : null;
        float t = now / 1000f + Math.abs(pin.uid % 97);
        if (idle != null) {
            switch (idle) {
                case "wave": {
                    float c = t % 6f; // raises a hand and waves for 2 s every 6 s
                    float k = c < 2f ? Math.min(1f, Math.min(c, 2f - c) * 4f) : 0f;
                    armsRotated = k > 0;
                    armAngleR = (90f + (float) Math.sin(t * 12f) * 25f) * k;
                    breathe = 1f + 0.03f * (float) Math.sin(t * 2.2f);
                    break;
                }
                case "dance":
                    legL = (float) Math.sin(t * Math.PI / 0.225f) * 6;
                    legR = -legL;
                    armL = -legL * 7f / 6f;
                    armR = -armL;
                    bodyRot = (float) Math.sin(t * Math.PI / 0.45f) * 16f;
                    hop = Math.abs((float) Math.sin(t * Math.PI / 0.45f)) * 2f * u;
                    break;
                case "jump":
                    hop = Math.abs((float) Math.sin(t * Math.PI)) * 8f * u;
                    break;
                case "phone":
                    armsRotated = true;
                    armAngleL = -25;
                    armAngleR = -25;
                    headNod = 1f - 0.07f * (0.5f + 0.5f * (float) Math.sin(t * Math.PI / 1.5f));
                    break;
                case "sit":
                    breathe = 1f + 0.05f * (float) Math.sin(t * Math.PI / 2.25f);
                    break;
                case "jog":
                    legL = (float) Math.sin(t * Math.PI / 0.15f) * 6;
                    legR = -legL;
                    armL = -legL;
                    armR = legL;
                    hop = Math.abs((float) Math.sin(t * Math.PI / 0.15f)) * 2f * u;
                    break;
                case "stretch": {
                    float c = (t % 4f) / 4f;
                    float k = smooth(c < 0.35f ? (c - 0.15f) / 0.2f : c > 0.65f ? (0.85f - c) / 0.2f : 1f);
                    armsRotated = true;
                    armAngleL = 90 * k;
                    armAngleR = 90 * k;
                    breathe = 1f + 0.05f * k;
                    break;
                }
                case "clap": {
                    float k = Math.abs((float) Math.sin(t * Math.PI / 0.6f));
                    armsRotated = true;
                    armAngleL = -30 * k;
                    armAngleR = -30 * k;
                    break;
                }
                case "music":
                    headSide = (float) Math.sin(t * Math.PI / 0.25f) * 1.5f * u;
                    break;
                case "sleep":
                    breathe = 1f + 0.05f * (float) Math.sin(t * Math.PI / 2.25f);
                    break;
                case "ball": {
                    float c = (t % 0.8f) / 0.8f;
                    legR = c > 0.4f && c < 0.6f ? 6 : 0;
                    break;
                }
                case "spin": {
                    float c = (t % 2.4f) / 2.4f;
                    bodyRot = c < 0.7f ? 360f * smooth(c / 0.7f) : 0f;
                    armsRotated = true;
                    armAngleL = 90;
                    armAngleR = 90;
                    break;
                }
                case "wait":
                    break;
                default: // calm: breathes and looks around
                    bodyRot = (float) Math.sin(t * Math.PI / 1.95f) * 8f;
                    breathe = 1f + 0.05f * (float) Math.sin(t * Math.PI / 1.4f);
                    break;
            }
        }
        boolean sitting = "sit".equals(idle), sleeping = "sleep".equals(idle);

        canvas.save();
        canvas.translate(s.x, s.y);
        canvas.rotate(pin.angle);
        float headX = 6 * u, headY = 0; // the head leads the body
        float headR = 9 * u;
        if (pin.inCar) {
            float sway = (float) Math.sin(now / 1000.0 * Math.PI / 0.45) * 0.6f * u * (moving ? 1 : 0);
            canvas.translate(0, sway);
            // soft shadow and the headlight glow ahead
            paint.setShader(new RadialGradient(0, 0, 26 * u, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
            paint.setAlpha(alpha);
            rect.set(-23 * u, -9 * u, 23 * u, 15 * u);
            canvas.drawOval(rect, paint);
            float beam = 0.55f + 0.25f * (0.5f + 0.5f * (float) Math.sin(now / 1000.0 * Math.PI / 0.8));
            paint.setShader(new RadialGradient(20 * u, 0, 46 * u, 0x59fff0c8, 0x00fff0c8, Shader.TileMode.CLAMP));
            paint.setAlpha((int) (255 * beam * appear));
            rect.set(20 * u, -21 * u, 66 * u, 21 * u);
            canvas.drawOval(rect, paint);
            // body: lighter on the near side, darker on the far side, with a faint white rim
            paint.setShader(new LinearGradient(0, -13 * u, 0, 13 * u, shade(color, 1.12f), shade(color, 0.88f), Shader.TileMode.CLAMP));
            paint.setAlpha(alpha);
            rect.set(-24 * u, -13 * u, 24 * u, 13 * u);
            canvas.drawRoundRect(rect, 10.5f * u, 12 * u, paint);
            paint.setShader(null);
            strokePaint.setColor(0x40ffffff);
            strokePaint.setAlpha((int) (0x40 * appear));
            strokePaint.setStrokeWidth(1.5f * u);
            rect.set(-23.25f * u, -12.25f * u, 23.25f * u, 12.25f * u);
            canvas.drawRoundRect(rect, 10 * u, 11.5f * u, strokePaint);
            paint.setColor(0xff1b2230);
            paint.setAlpha(alpha);
            rect.set(7 * u, -10 * u, 16 * u, 10 * u);
            canvas.drawRoundRect(rect, 4 * u, 4 * u, paint); // windscreen
            rect.set(-18 * u, -9 * u, -12 * u, 9 * u);
            canvas.drawRoundRect(rect, 3 * u, 3 * u, paint); // rear window
            paint.setColor(0xfffff3c4);
            paint.setAlpha(alpha);
            rect.set(20 * u, -10 * u, 23 * u, -5 * u);
            canvas.drawRoundRect(rect, 2 * u, 2 * u, paint);
            rect.set(20 * u, 5 * u, 23 * u, 10 * u);
            canvas.drawRoundRect(rect, 2 * u, 2 * u, paint);
            paint.setColor(0xffff2d2d);
            paint.setAlpha(alpha);
            rect.set(-24 * u, -10 * u, -22 * u, -5 * u);
            canvas.drawRoundRect(rect, u, u, paint);
            rect.set(-24 * u, 5 * u, -22 * u, 10 * u);
            canvas.drawRoundRect(rect, u, u, paint);
            headX = -u;
            headY = sway;
        } else {
            // contact shadow under the feet, smaller while in the air
            float shrink = 1f - Math.min(0.4f, hop / (20 * u));
            paint.setShader(new RadialGradient(-8.5f * u, 0, 8 * u * shrink, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
            paint.setAlpha(alpha);
            rect.set(-12 * u * shrink - 8.5f * u, -8 * u * shrink, 12 * u * shrink - 8.5f * u, 8 * u * shrink);
            canvas.drawOval(rect, paint);
            paint.setShader(null);
            // everything above the ground lifts with a hop, toward the top of the screen
            canvas.rotate(-pin.angle);
            canvas.translate(0, -hop);
            canvas.rotate(pin.angle);
            if (sleeping) {
                paint.setColor(0xff1f2a3a);
                paint.setAlpha(alpha);
                rect.set(-21 * u, -11 * u, 21 * u, 11 * u);
                canvas.drawRoundRect(rect, 6 * u, 6 * u, paint); // mattress
                paint.setColor(0xffe9edf3);
                paint.setAlpha(alpha);
                rect.set(10 * u, -7 * u, 19 * u, 7 * u);
                canvas.drawRoundRect(rect, 4 * u, 4 * u, paint); // pillow
                paint.setColor(color);
                paint.setAlpha((int) (alpha * 0.85f));
                rect.set(-19 * u, -9 * u * breathe, 9 * u, 9 * u * breathe);
                canvas.drawRoundRect(rect, 6 * u, 6 * u, paint); // blanket
                headX = 13 * u;
                headR = 8 * u;
            } else {
                if (sitting) {
                    paint.setColor(0xff6b4a2b);
                    paint.setAlpha(alpha);
                    rect.set(-16 * u, -19 * u, -6 * u, 19 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint); // bench
                    paint.setColor(0xff4e3520);
                    paint.setAlpha(alpha);
                    rect.set(-12 * u, -19 * u, -11 * u, 19 * u);
                    canvas.drawRect(rect, paint);
                    limb(canvas, 0, -4 * u, 0, 0, 13 * u, 6 * u, PANTS, 0, alpha);
                    limb(canvas, 0, 4 * u, 0, 0, 13 * u, 6 * u, PANTS, 0, alpha);
                } else {
                    paint.setColor(PANTS);
                    paint.setAlpha(alpha);
                    rect.set((-9 + legL) * u, -7 * u, (3 + legL) * u, -u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                    float tap = "wait".equals(idle) ? Math.max(0, (float) Math.sin(t * Math.PI / 0.225f)) * 3f : 0f;
                    rect.set((-9 + legR + tap) * u, u, (3 + legR + tap) * u, 7 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                }
                // shoulders: arms and torso sway together around the chest
                canvas.save();
                canvas.translate(u, 0);
                canvas.rotate(bodyRot);
                canvas.translate(-u, 0);
                paint.setColor(SKIN);
                paint.setAlpha(alpha);
                if ("wait".equals(idle)) {
                    // arms folded across the chest are drawn over the torso below
                } else if (armsRotated) {
                    limb(canvas, u, -11.5f * u, -armAngleL, -4 * u, 6 * u, 5 * u, SKIN, 0, alpha);
                    limb(canvas, u, 11.5f * u, armAngleR, -4 * u, 6 * u, 5 * u, SKIN, 0, alpha);
                } else if (sitting) {
                    rect.set(-4 * u, -14 * u, 6 * u, -9 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                    rect.set(-4 * u, 9 * u, 6 * u, 14 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                } else {
                    rect.set((-4 - armL) * u, -14 * u, (6 - armL) * u, -9 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                    rect.set((-4 - armR) * u, 9 * u, (6 - armR) * u, 14 * u);
                    canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
                }
                paint.setColor(color);
                paint.setAlpha(alpha);
                rect.set(-6 * u, -10 * u * breathe, 8 * u * breathe, 10 * u * breathe);
                canvas.drawRoundRect(rect, 7 * u, 7 * u, paint);
                if ("wait".equals(idle)) {
                    paint.setColor(SKIN);
                    paint.setAlpha(alpha);
                    rect.set(4 * u, -11 * u, 9 * u, 11 * u);
                    canvas.drawRoundRect(rect, 2.5f * u, 2.5f * u, paint);
                    paint.setColor(0xff1b2230);
                    paint.setAlpha(alpha);
                    rect.set(4.5f * u, 6 * u, 8.5f * u, 9.5f * u);
                    canvas.drawRoundRect(rect, u, u, paint);
                }
                canvas.restore();
                if ("phone".equals(idle)) {
                    float glow = 0.5f + 0.5f * (float) Math.sin(t * Math.PI / 1.2f);
                    paint.setShader(new RadialGradient(17 * u, 0, 8 * u, 0xcc8cc8ff, 0x008cc8ff, Shader.TileMode.CLAMP));
                    paint.setAlpha((int) (alpha * (0.35f + 0.55f * glow)));
                    canvas.drawCircle(17 * u, 0, 8 * u, paint);
                    paint.setShader(null);
                    paint.setColor(0xff1b2230);
                    paint.setAlpha(alpha);
                    rect.set(12 * u, -4 * u, 17 * u, 4 * u);
                    canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, paint);
                    strokePaint.setColor(0xff9fd0ff);
                    strokePaint.setAlpha(alpha);
                    strokePaint.setStrokeWidth(u);
                    canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, strokePaint);
                }
                if ("clap".equals(idle)) {
                    float c = (t % 0.6f) / 0.6f;
                    float k = c < 0.4f ? 0 : c < 0.5f ? (c - 0.4f) * 10 : c < 0.7f ? 1 - (c - 0.5f) * 5 : 0;
                    if (k > 0) {
                        strokePaint.setColor(0xffffd166);
                        strokePaint.setAlpha((int) (alpha * k));
                        strokePaint.setStrokeWidth(1.6f * u);
                        canvas.drawLine(18 * u, 0, 21 * u, 0, strokePaint);
                        canvas.drawLine(17 * u, -5 * u, 19 * u, -7 * u, strokePaint);
                        canvas.drawLine(17 * u, 5 * u, 19 * u, 7 * u, strokePaint);
                    }
                }
                if ("ball".equals(idle)) {
                    float c = (t % 0.8f) / 0.8f;
                    float h = (float) Math.sin(c * Math.PI); // height of the bounce
                    paint.setColor(0x80000000);
                    paint.setAlpha((int) (0x80 * appear * (1 - 0.6f * h)));
                    rect.set(19 * u - 4 * u * (1 - 0.4f * h), -1.5f * u, 19 * u + 4 * u * (1 - 0.4f * h), 1.5f * u);
                    canvas.drawOval(rect, paint);
                    paint.setShader(new RadialGradient(22 * u - h * 3 * u, -1.5f * u, 5 * u, new int[]{0xffffffff, 0xffd7dbe2, 0xff8a909b}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                    paint.setAlpha(alpha);
                    canvas.drawCircle(23 * u - h * 3 * u, 0, 4 * u * (1 + 0.35f * h), paint);
                    paint.setShader(null);
                }
            }
        }
        canvas.restore();

        // the avatar head, upright, with a 2-unit white rim
        double rad = Math.toRadians(pin.angle);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float hx = s.x + headX * cos - headY * sin - headSide * sin;
        float hy = s.y + headX * sin + headY * cos + headSide * cos - (pin.inCar ? 0 : hop);
        float r = headR * headNod;
        paint.setShader(null);
        paint.setColor(0xffffffff);
        paint.setAlpha(alpha);
        canvas.drawCircle(hx, hy, r + 2 * u, paint);
        drawAvatar(canvas, pin, hx, hy, r);
        if ("music".equals(idle)) {
            strokePaint.setColor(0xff1b2230);
            strokePaint.setAlpha(alpha);
            strokePaint.setStrokeWidth(2 * u);
            rect.set(hx - r - 3 * u, hy - r - 3 * u, hx + r + 3 * u, hy + r + 3 * u);
            canvas.drawArc(rect, 200, 140, false, strokePaint);
            paint.setColor(0xff1b2230);
            paint.setAlpha(alpha);
            rect.set(hx - r - 4 * u, hy - 3 * u, hx - r + u, hy + 4 * u);
            canvas.drawRoundRect(rect, 2 * u, 2 * u, paint);
            rect.set(hx + r - u, hy - 3 * u, hx + r + 4 * u, hy + 4 * u);
            canvas.drawRoundRect(rect, 2 * u, 2 * u, paint);
            for (int i = 0; i < 2; i++) {
                float c = ((t + i * 0.8f) % 1.6f) / 1.6f;
                float side = i == 0 ? 1 : -1;
                drawNote(canvas, hx + side * (r + 4 * u + c * 10 * u), hy - r - c * 26 * u, u, i == 0 ? 0xff64d2ff : 0xffbf5af2, (int) (alpha * Math.min(1f, c * 5f) * (1 - c)));
            }
        }
        if (sleeping) {
            textPaint.setColor(0xff9fd0ff);
            for (int i = 0; i < 2; i++) {
                float c = ((t + i * 1.2f) % 2.4f) / 2.4f;
                textPaint.setTextSize((7 + 3 * c + i * 2) * u);
                textPaint.setAlpha((int) (alpha * Math.min(1f, c * 4f) * (1 - c)));
                canvas.drawText(i == 0 ? "z" : "Z", hx + r + c * 14 * u, hy - r - c * 30 * u, textPaint);
            }
        }
        return hy - r - 2 * u;
    }

    private void drawNote(Canvas canvas, float x, float y, float u, int color, int alpha) {
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawCircle(x, y, 2 * u, paint);
        strokePaint.setColor(color);
        strokePaint.setAlpha(alpha);
        strokePaint.setStrokeWidth(1.3f * u);
        canvas.drawLine(x + 1.6f * u, y, x + 1.6f * u, y - 7 * u, strokePaint);
        canvas.drawLine(x + 1.6f * u, y - 7 * u, x + 5 * u, y - 5.5f * u, strokePaint);
    }

    private void drawAvatar(Canvas canvas, Pin pin, float cx, float cy, float r) {
        Bitmap bitmap = pin.avatar != null && pin.avatar.hasImageLoaded() ? pin.avatar.getBitmap() : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.reset();
            float k = r * 2 / Math.min(bitmap.getWidth(), bitmap.getHeight());
            matrix.postScale(k, k);
            matrix.postTranslate(cx - bitmap.getWidth() * k / 2, cy - bitmap.getHeight() * k / 2);
            shader.setLocalMatrix(matrix);
            avatarPaint.setShader(shader);
            canvas.drawCircle(cx, cy, r, avatarPaint);
        } else if (pin.user != null) {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(pin.user);
            avatarDrawable.setBounds((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
            avatarDrawable.draw(canvas);
        }
    }

    private void drawSpeed(Canvas canvas, float cx, float y, int kmh, float alpha) {
        String label = String.format(speedFormat, kmh);
        textPaint.setTextSize(dp(12));
        float w = textPaint.measureText(label) + dp(16);
        rect.set(cx - w / 2, y, cx + w / 2, y + dp(22));
        paint.setShader(null);
        paint.setColor(kmh > WALK_KMH ? 0xf2ff3b30 : 0xe61c1c1e);
        paint.setAlpha((int) (paint.getAlpha() * alpha));
        canvas.drawRoundRect(rect, dp(11), dp(11), paint);
        textPaint.setColor(0xffffffff);
        textPaint.setAlpha((int) (255 * alpha));
        canvas.drawText(label, cx - w / 2 + dp(8), y + dp(15.5f), textPaint);
    }

    private void drawHi(Canvas canvas, float cx, float bottom, float left, float t) {
        float a = Math.min(1f, Math.min(left * 4f, (1f - left) * 6f));
        float size = dp(38);
        rect.set(cx - size / 2, bottom - size - dp(6), cx + size / 2, bottom - dp(6));
        paint.setShader(null);
        paint.setColor(0xffffffff);
        paint.setAlpha((int) (255 * a));
        canvas.drawRoundRect(rect, size / 2, size / 2, paint);
        tmpPath.reset();
        tmpPath.moveTo(cx - dp(6), bottom - dp(7));
        tmpPath.lineTo(cx, bottom);
        tmpPath.lineTo(cx + dp(6), bottom - dp(7));
        tmpPath.close();
        canvas.drawPath(tmpPath, paint);
        textPaint.setTextSize(dp(20));
        textPaint.setColor(0xff000000);
        textPaint.setAlpha((int) (255 * a));
        canvas.save();
        canvas.rotate((float) Math.sin(t * 14f) * 18f, rect.centerX() + dp(4), rect.bottom - dp(8));
        float tw = textPaint.measureText("👋");
        canvas.drawText("👋", rect.centerX() - tw / 2, rect.centerY() + dp(7), textPaint);
        canvas.restore();
    }

    private static float dist(PointF a, PointF b) {
        return (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    // ---------------------------------------------------------------- touch

    private Pin hit(float x, float y) {
        if (projector == null) return null;
        for (int i = pins.size() - 1; i >= 0; i--) {
            Pin pin = pins.get(i);
            PointF s = projector.toScreen(pin.lat, pin.lng);
            if (s == null) continue;
            if (Math.hypot(x - s.x, y - s.y) < dp(40)) {
                return pin;
            }
        }
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressed = hit(event.getX(), event.getY());
            return pressed != null;
        }
        if (event.getAction() == MotionEvent.ACTION_UP && pressed != null) {
            if (hit(event.getX(), event.getY()) == pressed && onPinClick != null) {
                onPinClick.onClick(pressed.uid);
            }
            pressed = null;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            pressed = null;
        }
        return pressed != null;
    }
}
