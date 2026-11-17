package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UMessageHolidays;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * U message: holiday decorations of the chats screen. Drawn on one view above the screen that never takes touches.
 * <ul>
 * <li>Winter: snow falls, piles up on the tops of the components (following their round corners), comes off in
 * lumps and powder when a component is tapped or moved fast, and a gust blows some of it away now and then.</li>
 * <li>Navro'z: wheat sprouts and tulips grow on the dock and sway; an apricot blossom branch hangs in the free part
 * of the header and drops a petal now and then.</li>
 * <li>Hayit: string lights are draped across the two top corners; a crescent, a star and a lantern hang on threads
 * in the free part of the header and swing like pendulums.</li>
 * </ul>
 * All sizes below are in dp: the canvas is scaled by the display density.
 */
public class UMessageHolidayView extends View {

    public interface Host {
        /** 0..1: how much of the chats page is on screen. */
        float visibility();

        /** Free horizontal span of the header in window pixels: {left, right, top}. False while there is none. */
        boolean headerGap(float[] out);
    }

    private static class Ledge {
        WeakReference<View> ref;
        float inset, rad, x, y, w, hgt, lastY, sway, sv;
        boolean dock, valid, planted;
        float[] h;
        int cols;
        float born;
        final ArrayList<float[]> blades = new ArrayList<>(), flowers = new ArrayList<>();
    }

    private static class P {
        float x, y, vx, vy, s, a = 1, ph, life = 1, rot, vr;
        boolean near, free, lump;
        Ledge from;
        int c;
    }

    private static final float COL = 2f, MAX_SNOW = 8f;
    private static final int[] GREENS = {0xff5fb357, 0xff7ccb6a, 0xff93d97f, 0xff4c9e48};
    private static final int[] BLOOMS = {0xffe9485d, 0xfff59ab8, 0xfff6c445, 0xffffffff, 0xffe9485d};
    private static final int[] PINKS = {0xfff7bfd2, 0xfffde3ec, 0xffffffff, 0xfff19db9};
    private static final float[][] BLOSSOMS = {{10, 14, 5}, {22, 22, 4.2f}, {30, 34, 5.2f}, {16, 30, 3.6f}, {36, 22, 3.8f}, {4, 4, 3.4f}};

    private final Host host;
    private final ArrayList<Ledge> ledges = new ArrayList<>();
    private final ArrayList<P> parts = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), petalPath = new Path(), star4 = new Path(), star5 = new Path(), moonPath = new Path();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private final float[] gap = new float[3];
    private final float[] ornTh = new float[3], ornOm = new float[3];
    private int mode;
    private long lastTime, modeCheck;
    private float t, nextPetal = 60, branchKick, branchVel, flash, wob;
    private boolean dark;

    public UMessageHolidayView(Context context, Host host) {
        super(context);
        this.host = host;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setShader(new RadialGradient(0, 0, 1, 0xffffd66e, 0x00ffd66e, Shader.TileMode.CLAMP));
        petalPath.moveTo(0, -1);
        petalPath.cubicTo(0.95f, -0.55f, 0.7f, 0.75f, 0, 1);
        petalPath.cubicTo(-0.7f, 0.75f, -0.95f, -0.55f, 0, -1);
        star4.moveTo(0, -1);
        star4.quadTo(0.12f, -0.12f, 1, 0);
        star4.quadTo(0.12f, 0.12f, 0, 1);
        star4.quadTo(-0.12f, 0.12f, -1, 0);
        star4.quadTo(-0.12f, -0.12f, 0, -1);
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 1 ? 0.45f : 1f;
            if (i == 0) star5.moveTo((float) Math.cos(a) * r, (float) Math.sin(a) * r);
            else star5.lineTo((float) Math.cos(a) * r, (float) Math.sin(a) * r);
        }
        star5.close();
        final float a = 0.95f, d = 0.5f;
        final float ex = (float) Math.cos(a) - d, ey = (float) Math.sin(a), r2 = (float) Math.hypot(ex, ey);
        final float phi = (float) Math.toDegrees(Math.atan2(ey, ex)), deg = (float) Math.toDegrees(a);
        moonPath.arcTo(new RectF(-1, -1, 1, 1), deg, 360 - 2 * deg, true);
        moonPath.arcTo(new RectF(d - r2, -r2, d + r2, r2), 360 - phi, -(360 - 2 * phi), false);
        moonPath.close();
        for (int i = 0; i < 3; i++) ornTh[i] = (float) (Math.random() - 0.5) * 0.3f;
    }

    /** A component things can lie or grow on: its visible body is the view inset by {@code insetDp}. */
    public void addLedge(View view, float insetDp, float radiusDp, boolean dock) {
        if (view == null) return;
        for (Ledge l : ledges) if (l.ref.get() == view) return;
        Ledge l = new Ledge();
        l.ref = new WeakReference<>(view);
        l.inset = insetDp;
        l.rad = radiusDp;
        l.dock = dock;
        ledges.add(l);
        invalidate();
    }

    private static float rnd() {
        return (float) Math.random();
    }

    private float drop(Ledge l, float x) {
        float d = Math.min(x, l.w - x), r = l.rad;
        if (d >= r || r <= 0) return 0;
        return r - (float) Math.sqrt(Math.max(0, r * r - (r - d) * (r - d)));
    }

    private float hold(Ledge l, int i) {
        float cx = (i + 0.5f) * COL, d = Math.min(cx, l.w - cx);
        if (l.rad <= 0) return 1;
        // nothing stays on the steep part of a round end; it builds up only where the surface is close to level
        return (float) Math.pow(Math.max(0, Math.min(1, (d / l.rad - 0.38f) / 0.45f)), 1.3);
    }

    private static float smooth(float[] h, int i, int n) {
        float sum = 0;
        int count = 0;
        for (int k = -3; k <= 3; k++) {
            int j = i + k;
            if (j < 0 || j >= n) continue;
            sum += h[j];
            count++;
        }
        return sum / count;
    }

    private static float ease(float v) {
        v = Math.max(0, Math.min(1, v));
        return 1 - (1 - v) * (1 - v) * (1 - v);
    }

    private void readLedges(float density) {
        getLocationInWindow(loc);
        final int ox = loc[0], oy = loc[1];
        for (int i = ledges.size() - 1; i >= 0; i--) {
            Ledge l = ledges.get(i);
            View v = l.ref.get();
            if (v == null) {
                ledges.remove(i);
                continue;
            }
            boolean ok = v.isShown() && v.getAlpha() > 0.6f && v.getWidth() > 0;
            if (ok) {
                v.getLocationInWindow(loc);
                float w = v.getWidth() * v.getScaleX() / density - l.inset * 2;
                float y = (loc[1] - oy) / density + l.inset + 1.5f; // sits on the edge, not above it
                l.x = (loc[0] - ox) / density + l.inset;
                l.hgt = v.getHeight() * v.getScaleY() / density - l.inset * 2;
                int cols = Math.max(2, (int) Math.ceil(w / COL));
                if (l.h == null || l.cols != cols) {
                    l.h = new float[cols];
                    l.cols = cols;
                    l.planted = false;
                } else if (l.valid && Math.abs(y - l.lastY) > 7) {
                    shed(l, 0.45f, false); // moved fast: part of the snow slips off
                }
                l.w = w;
                l.y = l.lastY = y;
                ok = w > 20;
            }
            if (!ok && l.valid && l.h != null) {
                java.util.Arrays.fill(l.h, 0);
                l.planted = false;
            }
            l.valid = ok;
        }
    }

    private void spawnSnow(float w, float y) {
        P p = new P();
        p.near = rnd() < 0.55f;
        p.x = rnd() * (w + 60) - 30;
        p.y = y;
        p.ph = rnd() * 6.28f;
        p.s = p.near ? 1.6f + rnd() * 2.2f : 0.8f + rnd() * 1.1f;
        p.vy = (p.near ? 0.55f : 0.3f) + rnd() * 0.45f;
        p.a = p.near ? 0.9f : 0.5f;
        parts.add(p);
    }

    private boolean settle(P p, Ledge l, int i) {
        float cap = MAX_SNOW * hold(l, i);
        if (l.h[i] >= cap) return false;
        float add = p.s * 0.5f;
        final float[] k = {0.15f, 0.5f, 1, 0.5f, 0.15f};
        for (int n = -2; n <= 2; n++) {
            int j = i + n;
            if (j < 0 || j >= l.cols) continue;
            l.h[j] = Math.min(MAX_SNOW * hold(l, j), l.h[j] + add * k[n + 2]);
        }
        return true;
    }

    private void powder(float x, float y, float vx, float vy, float s, Ledge from) {
        P p = new P();
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.s = s;
        p.free = p.near = true;
        p.from = from;
        parts.add(p);
    }

    /** What lies on the component hops up a little and falls: powder, and lumps where the snow was deep. */
    private void shed(Ledge l, float amount, boolean wiggle) {
        if (mode != UMessageHolidays.SNOW || l.h == null) return;
        boolean any = false;
        for (int i = 0; i < l.cols; i++) {
            float h = l.h[i];
            if (h < 0.25f) continue;
            any = true;
            float take = h * amount;
            int n = Math.max(1, Math.round(take / 1.3f));
            for (int k = 0; k < n; k++) {
                powder(l.x + (i + rnd()) * COL, l.y + drop(l, (i + 0.5f) * COL) - h * rnd(), (rnd() - 0.5f) * 1.6f, -0.4f - rnd() * 1.4f, 1 + rnd() * 2, l);
            }
            if (take > 3 && i % 4 == 0) {
                powder(l.x + (i + 0.5f) * COL, l.y + drop(l, (i + 0.5f) * COL) - h * 0.5f, (rnd() - 0.5f) * 1.2f, -0.3f - rnd(), 2.6f + take * 0.3f, l);
                parts.get(parts.size() - 1).lump = true;
            }
            l.h[i] = h - take;
        }
        if (any && wiggle) wiggle(l.ref.get());
    }

    private void wiggle(View v) {
        if (v == null) return;
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(340);
        a.addUpdateListener(an -> {
            float p = (float) an.getAnimatedValue();
            v.setTranslationX((float) Math.sin(p * Math.PI * 4) * AndroidUtilities.dp(2.5f) * (1 - p));
        });
        a.start();
    }

    private void plant(Ledge l) {
        float inset = l.rad * 0.55f, flat = l.w - inset * 2;
        l.blades.clear();
        l.flowers.clear();
        l.born = t;
        l.planted = true;
        int n = (int) (flat / 6);
        for (int i = 0; i < n; i++) {
            l.blades.add(new float[]{inset + rnd() * flat, (5 + rnd() * 9) * 1.35f, rnd() - 0.5f, rnd() * 220, (int) (rnd() * 4), 1.1f + rnd() * 0.9f});
        }
        int f = Math.max(1, (int) (flat / 60));
        for (int k = 0; k < f; k++) {
            l.flowers.add(new float[]{inset + (k + 0.25f + rnd() * 0.5f) * flat / f, (15 + rnd() * 8) * 1.35f, (rnd() - 0.5f) * 0.6f, 120 + rnd() * 260, (int) (rnd() * 5)});
        }
    }

    private void addPetal(float x, float y, int c, float vx, float vy) {
        P p = new P();
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.c = c;
        p.s = 2.6f + rnd() * 1.8f;
        p.rot = rnd() * 6.28f;
        p.vr = (rnd() - 0.5f) * 0.08f;
        p.ph = rnd() * 6.28f;
        parts.add(p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // only looks at where a touch starts, never consumes it
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && mode != UMessageHolidays.NONE) {
            final float d = AndroidUtilities.density, x = e.getX() / d, y = e.getY() / d;
            if (mode == UMessageHolidays.HAYIT) {
                if (host.headerGap(gap) && y < gap[2] / d + 64) {
                    for (int i = 0; i < 3; i++) ornOm[i] += (rnd() < 0.5f ? -1 : 1) * 0.03f + (rnd() - 0.5f) * 0.02f;
                    flash = wob = 1;
                }
            } else {
                for (Ledge l : ledges) {
                    if (!l.valid || x < l.x || x > l.x + l.w || y < l.y - 6 || y > l.y + l.hgt) continue;
                    if (mode == UMessageHolidays.SNOW) {
                        shed(l, 1, true);
                    } else if (l.dock && l.planted) {
                        l.sv += (rnd() < 0.5f ? -1 : 1) * 0.22f;
                        for (float[] f : l.flowers) {
                            for (int k = 0; k < 2; k++) addPetal(l.x + f[0], l.y + drop(l, f[0]) - f[1], BLOOMS[(int) f[4]], (rnd() - 0.5f) * 2.4f, -0.6f - rnd() * 1.2f);
                        }
                        branchVel += 0.02f;
                    }
                }
            }
        }
        return false;
    }

    private void glow(Canvas c, float x, float y, float r, float alpha) {
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        glowPaint.setAlpha((int) (255 * Math.max(0, Math.min(1, alpha))));
        c.drawCircle(0, 0, 1, glowPaint);
        c.restore();
    }

    private void shape(Canvas c, Path p, float x, float y, float s, float rotDeg, float sy) {
        c.save();
        c.translate(x, y);
        if (rotDeg != 0) c.rotate(rotDeg);
        c.scale(s, s * sy);
        c.drawPath(p, paint);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        if (now - modeCheck > 2000) {
            modeCheck = now;
            int m = UMessageHolidays.current();
            if (m != mode) {
                mode = m;
                parts.clear();
                for (Ledge l : ledges) {
                    l.h = null;
                    l.planted = false;
                }
            }
        }
        final float vis = mode == UMessageHolidays.NONE ? 0 : host.visibility();
        if (vis <= 0.01f || getWidth() == 0) {
            lastTime = 0;
            if (mode != UMessageHolidays.NONE) postInvalidateDelayed(400);
            else postInvalidateDelayed(5000);
            return;
        }
        final float dt = lastTime == 0 ? 1 : Math.min(2.5f, (now - lastTime) / 16.67f);
        lastTime = now;
        t += dt;
        dark = Theme.isCurrentThemeDark();
        final float d = AndroidUtilities.density, W = getWidth() / d, H = getHeight() / d;
        readLedges(d);

        final int layer = vis < 0.99f ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * vis)) : canvas.save();
        canvas.scale(d, d);
        if (mode == UMessageHolidays.SNOW) drawSnow(canvas, W, H, dt);
        else if (mode == UMessageHolidays.NAVRUZ) drawNavruz(canvas, W, H, dt);
        else drawHayit(canvas, W, dt);
        canvas.restoreToCount(layer);
        postInvalidateOnAnimation();
    }

    private void drawSnow(Canvas c, float W, float H, float dt) {
        final float wind = (float) (Math.sin(t * 0.004) * 0.35 + Math.sin(t * 0.0113 + 1.7) * 0.25);
        final float gust = (float) Math.max(0, Math.sin(t * 0.0021) - 0.93) * 14;
        int falling = 0;
        for (P p : parts) if (!p.free) falling++;
        if (falling == 0 && parts.isEmpty()) for (int i = 0; i < 40; i++) spawnSnow(W, rnd() * H);
        if (falling < 70 && rnd() < 0.5f * dt) spawnSnow(W, -12);

        paint.setStyle(Paint.Style.FILL);
        final int snowColor = dark ? 0xffffffff : 0xffb9cbe2;
        for (int n = parts.size() - 1; n >= 0; n--) {
            P p = parts.get(n);
            float py = p.y;
            if (p.free) {
                p.vy += 0.09f * dt;
                p.vx *= 0.985f;
                p.x += (p.vx + wind * 0.3f) * dt;
                p.y += p.vy * dt;
                if (!p.lump && p.y > H * 0.62f) p.life -= 0.006f * dt;
            } else {
                p.ph += 0.03f * dt;
                p.x += ((float) Math.sin(p.ph) * 0.3f + (wind + gust) * (p.near ? 1 : 0.6f)) * dt;
                p.y += p.vy * dt;
            }
            boolean gone = p.y > H + 14 || p.x < -40 || p.x > W + 40 || p.life <= 0;
            if (!gone && p.near) {
                for (Ledge l : ledges) {
                    if (!l.valid || p.x < l.x || p.x > l.x + l.w) continue;
                    int i = Math.min(l.cols - 1, (int) ((p.x - l.x) / COL));
                    float top = l.y + drop(l, (i + 0.5f) * COL) - l.h[i];
                    if (py > top || p.y < top) continue;
                    if (!p.free) {
                        if (settle(p, l, i)) {
                            gone = true;
                            break;
                        }
                    } else if (p.from != l && p.vy > 0.5f) {
                        if (p.lump) {
                            for (int u = 0; u < 7; u++) powder(p.x + (rnd() - 0.5f) * 6, top - 1, (rnd() - 0.5f) * 3, -0.6f - rnd() * 1.6f, 0.8f + rnd() * 1.2f, null);
                        }
                        settle(p, l, i);
                        gone = true;
                        break;
                    }
                }
            }
            if (gone) {
                parts.remove(n);
                continue;
            }
            paint.setColor(snowColor);
            paint.setAlpha((int) (255 * p.a * p.life));
            c.drawCircle(p.x, p.y, p.s, paint);
        }

        for (Ledge l : ledges) {
            if (!l.valid) continue;
            final float[] h = l.h;
            boolean any = false;
            for (int a = 0; a < l.cols - 1; a++) {
                float df = h[a] - h[a + 1];
                if (Math.abs(df) > 1.1f) {
                    h[a] -= df * 0.2f;
                    h[a + 1] += df * 0.2f;
                }
                if (h[a] > 0.2f) any = true;
            }
            if (!any) continue;
            if (gust > 0.2f && rnd() < 0.3f) {
                int gi = (int) (rnd() * l.cols);
                if (h[gi] > 1) {
                    h[gi] -= 0.5f;
                    powder(l.x + gi * COL, l.y + drop(l, (gi + 0.5f) * COL) - h[gi], 1.5f + rnd(), -0.3f, 1 + rnd(), l);
                }
            }
            // a soft mound: heights are averaged over neighbours, and a dusting too thin to read as snow is not drawn
            path.rewind();
            boolean started = false;
            float lx = 0, ly = 0;
            for (int b = 0; b < l.cols; b++) {
                float hb = smooth(h, b, l.cols);
                if (hb < 0.7f) continue;
                float x = l.x + (b + 0.5f) * COL, base = l.y + drop(l, (b + 0.5f) * COL);
                if (!started) {
                    path.moveTo(x - COL * 1.5f, base + 0.6f);
                    started = true;
                }
                path.lineTo(x, base - hb);
                lx = x;
                ly = base;
            }
            if (!started) continue;
            path.lineTo(lx + COL * 1.5f, ly + 0.6f);
            for (int e = l.cols - 1; e >= 0; e--) {
                if (smooth(h, e, l.cols) < 0.7f) continue;
                path.lineTo(l.x + (e + 0.5f) * COL, l.y + drop(l, (e + 0.5f) * COL) + 0.8f);
            }
            path.close();
            paint.setColor(dark ? 0xffffffff : 0xfff4f8fd);
            paint.setShadowLayer(1.5f, 0, 0.6f, dark ? 0x59000000 : 0x73466496);
            c.drawPath(path, paint);
            paint.clearShadowLayer();
        }
    }

    private void drawNavruz(Canvas c, float W, float H, float dt) {
        final float wind = (float) (Math.sin(t * 0.011) * 0.5 + Math.sin(t * 0.027 + 2) * 0.3 + Math.max(0, Math.sin(t * 0.004) - 0.8) * 4);
        for (Ledge l : ledges) {
            if (!l.valid || !l.dock) continue;
            if (!l.planted) plant(l);
            final float age = t - l.born;
            l.sv += (-l.sway * 0.05f - l.sv * 0.06f) * dt;
            l.sway += l.sv * dt;
            for (float[] b : l.blades) {
                float g = ease((age - b[3]) / 150);
                if (g <= 0) continue;
                float bx = l.x + b[0], by = l.y + drop(l, b[0]) + 0.5f, h = b[1] * g;
                float bend = l.sway * 2.2f + wind * 0.22f + (float) Math.sin(t * 0.03 + b[0]) * 0.06f + b[2] * 0.5f;
                stroke.setColor(GREENS[(int) b[4]]);
                stroke.setStrokeWidth(b[5]);
                path.rewind();
                path.moveTo(bx, by);
                path.quadTo(bx + bend * h * 0.25f, by - h * 0.6f, bx + bend * h * 0.9f, by - h);
                c.drawPath(path, stroke);
            }
            paint.setStyle(Paint.Style.FILL);
            for (float[] f : l.flowers) {
                float fg = ease((age - f[3]) / 200);
                if (fg <= 0) continue;
                float fx = l.x + f[0], fy = l.y + drop(l, f[0]) + 0.5f, fh = f[1] * fg;
                float fb = l.sway * 1.8f + wind * 0.16f + f[2] * 0.4f, tx = fx + fb * fh * 0.8f, ty = fy - fh;
                stroke.setColor(0xff4c9e48);
                stroke.setStrokeWidth(1.5f);
                path.rewind();
                path.moveTo(fx, fy);
                path.quadTo(fx + fb * fh * 0.2f, fy - fh * 0.6f, tx, ty);
                c.drawPath(path, stroke);
                float bloom = ease((fg - 0.75f) / 0.25f), sz = 2 + 2.4f * ease((fg - 0.4f) / 0.6f);
                int color = BLOOMS[(int) f[4]];
                paint.setColor(!dark && color == 0xffffffff ? 0xfff59ab8 : color);
                c.save();
                c.translate(tx, ty);
                c.rotate((float) Math.toDegrees(fb * 0.9f));
                for (int p = -1; p <= 1; p++) {
                    c.save();
                    c.rotate((float) Math.toDegrees(p * 0.42f * bloom));
                    rect.set(p * sz * 0.35f - sz * 0.5f, -sz * 1.95f, p * sz * 0.35f + sz * 0.5f, sz * 0.15f);
                    c.drawOval(rect, paint);
                    c.restore();
                }
                c.restore();
            }
        }

        // apricot blossom branch in the free part of the header
        final float d = AndroidUtilities.density;
        boolean branch = host.headerGap(gap) && (gap[1] - gap[0]) / d >= 46;
        float bx = 0, by = 0, bs = 1;
        if (branch) {
            getLocationInWindow(loc);
            float gl = (gap[0] - loc[0]) / d, gr = (gap[1] - loc[0]) / d;
            bs = Math.min(1.15f, (gr - gl - 6) / 42f);
            bx = (gl + gr) / 2f - 20 * bs;
            by = (gap[2] - loc[1]) / d - 2;
            branchVel += -branchKick * 0.02f + wind * 0.0009f;
            branchVel *= 0.96f;
            branchKick += branchVel;
            c.save();
            c.translate(bx, by);
            c.scale(bs, bs);
            c.rotate((float) Math.toDegrees(branchKick + wind * 0.05f));
            stroke.setColor(dark ? 0xff8a6248 : 0xff6f4a33);
            stroke.setStrokeWidth(2.6f);
            path.rewind();
            path.moveTo(0, 0);
            path.quadTo(10, 20, 31, 36);
            c.drawPath(path, stroke);
            stroke.setStrokeWidth(1.6f);
            path.rewind();
            path.moveTo(12, 16);
            path.quadTo(26, 16, 37, 21);
            path.moveTo(8, 11);
            path.quadTo(10, 22, 16, 30);
            c.drawPath(path, stroke);
            for (int i = 0; i < BLOSSOMS.length; i++) {
                float[] b = BLOSSOMS[i];
                c.save();
                c.translate(b[0], b[1]);
                c.rotate((float) Math.toDegrees(i * 1.3f + Math.sin(t * 0.02 + i) * 0.08));
                paint.setColor(i % 2 == 1 ? 0xfffde3ec : 0xfff7bfd2);
                for (int p = 0; p < 5; p++) {
                    c.rotate(72);
                    rect.set(-b[2] * 0.46f, -b[2] * 1.24f, b[2] * 0.46f, 0);
                    c.drawOval(rect, paint);
                }
                paint.setColor(0xfff2b642);
                c.drawCircle(0, 0, b[2] * 0.24f, paint);
                c.restore();
            }
            c.restore();
            nextPetal -= dt;
            if (nextPetal <= 0 && parts.size() < 6) {
                nextPetal = 150 + rnd() * 200;
                float[] s = BLOSSOMS[(int) (rnd() * BLOSSOMS.length)];
                addPetal(bx + s[0] * bs, by + s[1] * bs, PINKS[(int) (rnd() * 4)], 0, 0.25f + rnd() * 0.3f);
            }
        }
        for (int n = parts.size() - 1; n >= 0; n--) {
            P p = parts.get(n);
            p.ph += 0.035f * dt;
            p.rot += p.vr * dt;
            p.vx += (wind * 0.5f + (float) Math.sin(p.ph) * 0.7f - p.vx) * 0.04f * dt;
            p.vy += (0.55f - p.vy) * 0.03f * dt;
            p.x += p.vx * dt;
            p.y += p.vy * (0.7f + 0.4f * (float) Math.cos(p.ph * 2)) * dt;
            if (p.y > H * 0.82f) p.life -= 0.012f * dt;
            if (p.y > H + 10 || p.x < -20 || p.x > W + 20 || p.life <= 0) {
                parts.remove(n);
                continue;
            }
            paint.setColor(!dark && (p.c == 0xffffffff || p.c == 0xfffde3ec) ? 0xfff19db9 : p.c);
            paint.setAlpha((int) (235 * p.life));
            shape(c, petalPath, p.x, p.y, p.s, (float) Math.toDegrees(p.rot), 0.35f + 0.65f * (float) Math.abs(Math.cos(p.ph * 1.7)));
        }
    }

    private void drawHayit(Canvas c, float W, float dt) {
        final float d = AndroidUtilities.density;
        final int gold = dark ? 0xfff2c94c : 0xffc98a00, pale = dark ? 0xffffe9a8 : 0xffe0a81e, wire = dark ? 0x8cf2c94c : 0xb38c640a;
        if (!host.headerGap(gap)) return;
        getLocationInWindow(loc);
        final float top = (gap[2] - loc[1]) / d, gl = (gap[0] - loc[0]) / d, gr = (gap[1] - loc[0]) / d;
        flash *= (float) Math.pow(0.975, dt);
        wob *= (float) Math.pow(0.97, dt);
        paint.setStyle(Paint.Style.FILL);

        // string lights draped across the two top corners, above the logo and the header buttons
        for (int i = 0; i < 2; i++) {
            final float x0 = i == 0 ? 0 : W - 118, step = 59;
            stroke.setColor(wire);
            stroke.setStrokeWidth(1);
            path.rewind();
            for (int k = 0; k < 2; k++) {
                float x1 = x0 + k * step, x2 = x1 + step, sag = 6.5f + (float) Math.sin(t * 0.25 + k + i) * 1.6f * wob;
                path.moveTo(x1, top);
                path.quadTo((x1 + x2) / 2, top + sag * 2, x2, top);
            }
            c.drawPath(path, stroke);
            for (int b = 0; b < 6; b++) {
                int k = b / 3, j = b % 3;
                float sag = 6.5f + (float) Math.sin(t * 0.25 + k + i) * 1.6f * wob;
                float x = x0 + k * step + step * (0.25f + 0.25f * j), y = top + sag * (j == 1 ? 1 : 0.75f);
                float chase = (float) Math.max(0, Math.sin(t * 0.035 - b * 0.7 + i * 2));
                float lit = Math.min(1, 0.3f + 0.7f * chase * chase + flash);
                glow(c, x, y + 1.5f, 5 + 5 * lit, (dark ? 0.5f : 0.35f) * lit);
                paint.setColor(b % 3 == 0 ? pale : gold);
                paint.setAlpha((int) (255 * (0.55f + 0.45f * lit)));
                rect.set(x - 1.7f, y - 0.7f, x + 1.7f, y + 3.9f);
                c.drawOval(rect, paint);
            }
        }

        // crescent, star and lantern on threads in the free part of the header: damped pendulums in a light breeze
        final float span = gr - gl;
        if (span < 30) return;
        final int count = span >= 78 ? 3 : 1;
        final float[] lens = {26, 13, 32}, sizes = {9, 4.2f, 7.5f}, pos = {0.2f, 0.52f, 0.82f};
        for (int o = 0; o < count; o++) {
            final float ox = count == 1 ? (gl + gr) / 2 : gl + span * pos[o], len = lens[o], s = sizes[o];
            ornOm[o] += (-0.0038f * (float) Math.sin(ornTh[o]) * (30 / len) + (float) Math.sin(t * 0.017 + ox) * 0.00022f) * dt;
            ornOm[o] *= (float) Math.pow(0.993, dt);
            ornTh[o] += ornOm[o] * dt;
            final float ex = ox + (float) Math.sin(ornTh[o]) * len, ey = top + (float) Math.cos(ornTh[o]) * len;
            stroke.setColor(wire);
            stroke.setStrokeWidth(0.9f);
            c.drawLine(ox, top, ex, ey, stroke);
            c.save();
            c.translate(ex, ey);
            c.rotate((float) -Math.toDegrees(ornTh[o]));
            if (o == 0) {
                glow(c, 0, s * 0.8f, s * 2.4f, dark ? 0.18f : 0.1f);
                paint.setColor(gold);
                shape(c, moonPath, 0, s * 0.9f, s, -28, 1);
            } else if (o == 1) {
                paint.setColor(pale);
                paint.setAlpha((int) (255 * (0.75f + 0.25f * Math.sin(t * 0.06 + 2))));
                shape(c, star5, 0, s * 0.8f, s, 0, 1);
            } else {
                drawLantern(c, s, 0.75f + 0.25f * (float) (Math.sin(t * 0.09) * Math.sin(t * 0.023)), gold);
            }
            c.restore();
        }
    }

    private void drawLantern(Canvas c, float s, float pulse, int gold) {
        glow(c, 0, s * 0.9f, s * 3.2f, (dark ? 0.32f : 0.22f) * pulse);
        paint.setColor(gold);
        c.drawRect(-s * 0.32f, -s * 0.15f, s * 0.32f, s * 0.15f, paint);
        path.rewind();
        path.moveTo(-s * 0.55f, s * 0.15f);
        path.lineTo(s * 0.55f, s * 0.15f);
        path.quadTo(s * 0.85f, s * 0.9f, s * 0.5f, s * 1.65f);
        path.lineTo(-s * 0.5f, s * 1.65f);
        path.quadTo(-s * 0.85f, s * 0.9f, -s * 0.55f, s * 0.15f);
        path.close();
        paint.setColor(dark ? 0xffffc454 : 0xfff0aa28);
        paint.setAlpha((int) (255 * (dark ? 0.55f + 0.3f * pulse : 0.7f + 0.2f * pulse)));
        c.drawPath(path, paint);
        stroke.setColor(gold);
        stroke.setStrokeWidth(1.1f);
        c.drawPath(path, stroke);
        path.rewind();
        path.moveTo(0, s * 0.15f);
        path.lineTo(0, s * 1.65f);
        path.moveTo(-s * 0.32f, s * 0.2f);
        path.quadTo(-s * 0.5f, s * 0.9f, -s * 0.28f, s * 1.62f);
        path.moveTo(s * 0.32f, s * 0.2f);
        path.quadTo(s * 0.5f, s * 0.9f, s * 0.28f, s * 1.62f);
        path.moveTo(0, s * 1.87f);
        path.lineTo(0, s * 2.5f);
        c.drawPath(path, stroke);
        paint.setColor(gold);
        c.drawRect(-s * 0.36f, s * 1.65f, s * 0.36f, s * 1.87f, paint);
    }
}
