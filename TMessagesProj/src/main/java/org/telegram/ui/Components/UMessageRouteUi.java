package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.yandex.mapkit.annotations.AnnotationLanguage;
import com.yandex.mapkit.directions.driving.Action;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/** Shared look for the route preview, the location-screen route strip and in-app navigation. */
public final class UMessageRouteUi {

    public static final int MODE_CAR = 0, MODE_WALK = 1, MODE_TRANSIT = 2, MODE_BIKE = 3;
    /** Display order of the modes. */
    public static final int[] ORDER = {MODE_CAR, MODE_TRANSIT, MODE_WALK, MODE_BIKE};

    public static final int ACCENT = 0xff2f6bff;
    public static final int GREEN = 0xff18b663;

    public static final int BLUE = 0xff2b6cff;
    public static final int LIME = 0xffd4ff4a;
    public static final int ALT_ROUTE = 0xffb35ce6;
    /** Traffic palette indexed by jamIndex(): unknown, free, light, hard, very hard, blocked. */
    public static final int[] JAM_COLORS = {0xff7ad61f, 0xff7ad61f, 0xffffd400, 0xffff6a1a, 0xffe8262b, 0xff8c1515};

    /** Colors of the floating controls and sheets, per theme. */
    public static final class Palette {
        public final boolean dark;
        public final int sheet, pill, text, secondary, button, icon, divider;

        public Palette(boolean dark) {
            this.dark = dark;
            sheet = dark ? 0xff1f2023 : 0xffffffff;
            pill = dark ? 0xff35363b : 0xffeef0f3;
            text = dark ? 0xffffffff : 0xff111114;
            secondary = dark ? 0xff8e8e93 : 0xff8a8a8f;
            button = dark ? 0xf22a2b2f : 0xf7ffffff;
            icon = dark ? 0xffffffff : 0xff1c1c1e;
            divider = dark ? 0x1fffffff : 0x14000000;
        }
    }

    public static int jamIndex(com.yandex.mapkit.navigation.JamSegment s) {
        switch (s.getJamType()) {
            case FREE: return 1;
            case LIGHT: return 2;
            case HARD: return 3;
            case VERY_HARD: return 4;
            case BLOCKED: return 5;
            default: return 0;
        }
    }

    private UMessageRouteUi() {
    }

    private static Paint textPaint(float sizeDp, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(dp(sizeDp));
        p.setColor(color);
        p.setTypeface(bold ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
        return p;
    }

    /**
     * Speech-bubble label for a route on the map. The tail sits at the top-left corner, so the icon
     * anchor is (0, 0) and the tail tip lands exactly on the route point.
     */
    public static android.graphics.Bitmap bubble(String line1, String line2, int bg, int color1, int color2) {
        Paint p1 = textPaint(17, color1, true);
        Paint p2 = textPaint(15, color2, false);
        float padH = dp(14), padV = dp(9), tail = dp(9);
        float w1 = p1.measureText(line1), w2 = line2 != null ? p2.measureText(line2) : 0;
        float bw = Math.max(w1, w2) + padH * 2;
        float bh = padV * 2 + dp(20) + (line2 != null ? dp(19) : 0);
        int W = (int) (bw + tail), H = (int) (bh + tail);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bg);
        p.setShadowLayer(dp(3), 0, dp(1), 0x40000000);
        RectF r = new RectF(tail, tail, W - dp(1), H - dp(2));
        Path path = new Path();
        path.addRoundRect(r, dp(14), dp(14), Path.Direction.CW);
        Path t = new Path();
        t.moveTo(0, 0);
        t.lineTo(tail + dp(16), tail);
        t.lineTo(tail, tail + dp(16));
        t.close();
        path.op(t, Path.Op.UNION);
        c.drawPath(path, p);
        float x = tail + padH;
        float y = tail + padV + dp(16);
        c.drawText(line1, x, y, p1);
        if (line2 != null) c.drawText(line2, x, y + dp(20), p2);
        return b;
    }

    /** Round road-event marker: speed camera, works/danger triangle or closed road. */
    public static android.graphics.Bitmap eventIcon(int kind) {
        int s = dp(30);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f, cy = s / 2f;
        if (kind == 2) { // closed
            p.setColor(0xffffffff);
            c.drawCircle(cx, cy, s / 2f, p);
            p.setColor(0xffe8262b);
            c.drawCircle(cx, cy, s / 2f - dp(2), p);
            p.setColor(0xffffffff);
            c.drawRoundRect(new RectF(cx - dp(7), cy - dp(2), cx + dp(7), cy + dp(2)), dp(1), dp(1), p);
            return b;
        }
        if (kind == 1) { // danger / works triangle
            Path tri = new Path();
            tri.moveTo(cx, dp(2));
            tri.lineTo(s - dp(1), s - dp(3));
            tri.lineTo(dp(1), s - dp(3));
            tri.close();
            p.setColor(0xffe8262b);
            c.drawPath(tri, p);
            Path in = new Path();
            in.moveTo(cx, dp(8));
            in.lineTo(s - dp(6), s - dp(6));
            in.lineTo(dp(6), s - dp(6));
            in.close();
            p.setColor(0xffffffff);
            c.drawPath(in, p);
            p.setColor(0xff111111);
            c.drawRoundRect(new RectF(cx - dp(1.2f), dp(13), cx + dp(1.2f), dp(20)), dp(1), dp(1), p);
            c.drawCircle(cx, dp(22.5f), dp(1.3f), p);
            return b;
        }
        // speed camera: white disc, red ring, camera glyph
        p.setColor(0xffe8262b);
        c.drawCircle(cx, cy, s / 2f, p);
        p.setColor(0xffffffff);
        c.drawCircle(cx, cy, s / 2f - dp(2.5f), p);
        p.setColor(0xff111111);
        c.drawRoundRect(new RectF(cx - dp(7.5f), cy - dp(4.5f), cx + dp(7.5f), cy + dp(5.5f)), dp(2), dp(2), p);
        c.drawRect(cx - dp(3), cy - dp(6.5f), cx + dp(1), cy - dp(4), p);
        p.setColor(0xffffffff);
        c.drawCircle(cx, cy + dp(0.5f), dp(3), p);
        p.setColor(0xff111111);
        c.drawCircle(cx, cy + dp(0.5f), dp(1.6f), p);
        return b;
    }

    /** Kind for eventIcon(), or -1 when the event is not worth a marker. */
    public static int eventKind(java.util.List<com.yandex.mapkit.road_events.EventTag> tags) {
        if (tags == null) return -1;
        int kind = -1;
        for (com.yandex.mapkit.road_events.EventTag t : tags) {
            switch (t) {
                case CLOSED: case DRAWBRIDGE: return 2;
                case SPEED_CONTROL: case LANE_CONTROL: case MOBILE_CONTROL: case CROSS_ROAD_CONTROL:
                case ROAD_MARKING_CONTROL: case NO_STOPPING_CONTROL: case TRAFFIC_CONTROL:
                    kind = 0;
                    break;
                case RECONSTRUCTION: case ACCIDENT: case DANGER: case SCHOOL: case PEDESTRIAN_DANGER:
                case OVERTAKING_DANGER: case CROSS_ROAD_DANGER:
                    if (kind < 0) kind = 1;
                    break;
                default:
                    break;
            }
        }
        return kind;
    }

    public static int reportColor(String type) {
        switch (type) {
            case "police": return 0xff2f6bff;
            case "radar": return 0xffff8a00;
            case "accident": return 0xffe8262b;
            default: return 0xfff2b600;
        }
    }

    public static int reportName(String type) {
        switch (type) {
            case "police": return R.string.UMessageReportPolice;
            case "radar": return R.string.UMessageReportRadar;
            case "accident": return R.string.UMessageReportAccident;
            default: return R.string.UMessageReportWorks;
        }
    }

    /** 3D illustration for police (officer + YPX car) and radar (tripod camera); null for other types. */
    public static int report3dRes(String type) {
        if ("police".equals(type)) return R.drawable.umsg_report_police3d;
        if ("radar".equals(type)) return R.drawable.umsg_report_radar3d;
        return 0;
    }

    /** Map/tile image for a report: the 3D illustration scaled to heightDp, or the round badge. */
    public static android.graphics.Bitmap reportImage(String type, float heightDp) {
        int res = report3dRes(type);
        if (res != 0) {
            try {
                android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeResource(org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), res);
                int h = dp(heightDp);
                int w = Math.round(src.getWidth() * (h / (float) src.getHeight()));
                return android.graphics.Bitmap.createScaledBitmap(src, w, h, true);
            } catch (Throwable ignore) {
            }
        }
        return reportIcon(type, heightDp * 0.8f);
    }

    /** Round road-report badge: colored disc with a white rim and a white glyph for the type. */
    public static android.graphics.Bitmap reportIcon(String type, float sizeDp) {
        int s = dp(sizeDp);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f, cy = s / 2f, u = s / 40f;
        p.setColor(0xffffffff);
        p.setShadowLayer(u * 2, 0, u, 0x55000000);
        c.drawCircle(cx, cy, s / 2f - u * 2, p);
        p.clearShadowLayer();
        p.setColor(reportColor(type));
        c.drawCircle(cx, cy, s / 2f - u * 4.5f, p);
        p.setColor(0xffffffff);
        Path g = new Path();
        switch (type) {
            case "police": { // shield with a star cut-out feel
                g.moveTo(cx, cy - u * 10);
                g.lineTo(cx + u * 8, cy - u * 7);
                g.lineTo(cx + u * 8, cy + u * 1);
                g.cubicTo(cx + u * 8, cy + u * 6, cx + u * 4, cy + u * 9, cx, cy + u * 11);
                g.cubicTo(cx - u * 4, cy + u * 9, cx - u * 8, cy + u * 6, cx - u * 8, cy + u * 1);
                g.lineTo(cx - u * 8, cy - u * 7);
                g.close();
                c.drawPath(g, p);
                p.setColor(reportColor(type));
                c.drawCircle(cx, cy, u * 3.2f, p);
                break;
            }
            case "radar": { // camera on a tripod
                c.drawRoundRect(new RectF(cx - u * 8, cy - u * 9, cx + u * 6, cy - u * 1), u * 2, u * 2, p);
                g.moveTo(cx + u * 6, cy - u * 7);
                g.lineTo(cx + u * 10, cy - u * 9);
                g.lineTo(cx + u * 10, cy - u * 1);
                g.lineTo(cx + u * 6, cy - u * 3);
                g.close();
                c.drawPath(g, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u * 2.2f);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx - u, cy - u, cx - u, cy + u * 4, p);
                c.drawLine(cx - u, cy + u * 4, cx - u * 7, cy + u * 10, p);
                c.drawLine(cx - u, cy + u * 4, cx + u * 5, cy + u * 10, p);
                c.drawLine(cx - u, cy + u * 4, cx - u, cy + u * 10, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case "accident": { // exclamation in a triangle
                g.moveTo(cx, cy - u * 10);
                g.lineTo(cx + u * 11, cy + u * 9);
                g.lineTo(cx - u * 11, cy + u * 9);
                g.close();
                c.drawPath(g, p);
                p.setColor(reportColor(type));
                c.drawRoundRect(new RectF(cx - u * 1.4f, cy - u * 3.5f, cx + u * 1.4f, cy + u * 3.5f), u, u, p);
                c.drawCircle(cx, cy + u * 6.2f, u * 1.6f, p);
                break;
            }
            default: { // traffic cone
                g.moveTo(cx - u * 2.5f, cy - u * 10);
                g.lineTo(cx + u * 2.5f, cy - u * 10);
                g.lineTo(cx + u * 7, cy + u * 7);
                g.lineTo(cx - u * 7, cy + u * 7);
                g.close();
                c.drawPath(g, p);
                c.drawRoundRect(new RectF(cx - u * 10, cy + u * 7, cx + u * 10, cy + u * 10), u, u, p);
                p.setColor(reportColor(type));
                c.drawRect(cx - u * 5, cy - u * 2, cx + u * 5, cy + u * 1, p);
                break;
            }
        }
        return b;
    }

    /** U message navigation arrow: two-tone lime arrowhead with a white rim and soft shadow. */
    public static android.graphics.Bitmap puckBitmap() {
        int s = dp(56);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = s / 2f, cy = s / 2f;
        Path arrow = new Path();
        arrow.moveTo(cx, cy - dp(20));
        arrow.lineTo(cx + dp(15), cy + dp(18));
        arrow.lineTo(cx, cy + dp(10));
        arrow.lineTo(cx - dp(15), cy + dp(18));
        arrow.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(dp(5));
        p.setShadowLayer(dp(4), 0, dp(2), 0x66000000);
        c.drawPath(arrow, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        Path left = new Path();
        left.moveTo(cx, cy - dp(20));
        left.lineTo(cx, cy + dp(10));
        left.lineTo(cx - dp(15), cy + dp(18));
        left.close();
        p.setColor(LIME);
        c.drawPath(left, p);
        Path right = new Path();
        right.moveTo(cx, cy - dp(20));
        right.lineTo(cx + dp(15), cy + dp(18));
        right.lineTo(cx, cy + dp(10));
        right.close();
        p.setColor(0xff9fcc1a);
        c.drawPath(right, p);
        return b;
    }

    /** Rounded-square floating map button. */
    public static android.widget.ImageView mapButton(Context context, int icon, Palette pal, float radiusDp) {
        android.widget.ImageView v = new android.widget.ImageView(context);
        v.setImageResource(icon);
        v.setColorFilter(pal.icon);
        v.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        v.setBackground(rounded(pal.button, dp(radiusDp)));
        v.setElevation(dp(4));
        pressScale(v);
        return v;
    }

    public static int icon(int mode) {
        switch (mode) {
            case MODE_WALK: return R.drawable.umsg_route_walk;
            case MODE_TRANSIT: return R.drawable.umsg_route_bus;
            case MODE_BIKE: return R.drawable.umsg_route_bike;
            default: return R.drawable.umsg_route_car;
        }
    }

    public static int name(int mode) {
        switch (mode) {
            case MODE_WALK: return R.string.UMessageRouteWalk;
            case MODE_TRANSIT: return R.string.UMessageRouteTransit;
            case MODE_BIKE: return R.string.UMessageRouteBike;
            default: return R.string.UMessageRouteCar;
        }
    }

    public static String formatTime(double secs) {
        int minutes = Math.max(1, (int) Math.round(secs / 60.0));
        if (minutes < 60) return LocaleController.formatString(R.string.UMessageRouteMinutes, minutes);
        return LocaleController.formatString(R.string.UMessageRouteHours, minutes / 60, minutes % 60);
    }

    public static String formatDistance(double meters) {
        return LocaleController.formatDistance((float) meters, 2);
    }

    public static boolean isNavigatorInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("ru.yandex.yandexnavi", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static AnnotationLanguage annotationLanguage() {
        String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "en";
        if (lang == null) return AnnotationLanguage.ENGLISH;
        if (lang.startsWith("uz") || lang.startsWith("kaa")) return AnnotationLanguage.UZBEK;
        if (lang.startsWith("ru")) return AnnotationLanguage.RUSSIAN;
        if (lang.startsWith("uk")) return AnnotationLanguage.UKRAINIAN;
        if (lang.startsWith("tr")) return AnnotationLanguage.TURKISH;
        if (lang.startsWith("kk")) return AnnotationLanguage.KAZAKH;
        if (lang.startsWith("ar")) return AnnotationLanguage.ARABIC;
        if (lang.startsWith("it")) return AnnotationLanguage.ITALIAN;
        if (lang.startsWith("pt")) return AnnotationLanguage.PORTUGUESE;
        if (lang.startsWith("es")) return AnnotationLanguage.LATIN_AMERICAN_SPANISH;
        return AnnotationLanguage.ENGLISH;
    }

    public static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable stroked(int color, int strokeColor, int strokeWidth, float radius) {
        GradientDrawable d = rounded(color, radius);
        d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    public static GradientDrawable roundedTop(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return d;
    }

    public static TextView text(Context context, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void pressScale(View view) {
        view.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            return false;
        });
    }

    /** Turn arrow drawn from the maneuver angle, so every action has a crisp glyph at any size. */
    public static class ManeuverView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private Action action = Action.STRAIGHT;

        public ManeuverView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xffffffff);
            dim.set(paint);
            dim.setColor(0x59ffffff);
        }

        public void setColor(int color) {
            paint.setColor(color);
            dim.setColor((color & 0x00ffffff) | 0x59000000);
            invalidate();
        }

        public void setAction(Action action) {
            this.action = action == null ? Action.STRAIGHT : action;
            invalidate();
        }

        private static float angle(Action a) {
            switch (a) {
                case SLIGHT_LEFT: case FORK_LEFT: case EXIT_LEFT: return -45;
                case SLIGHT_RIGHT: case FORK_RIGHT: case EXIT_RIGHT: return 45;
                case LEFT: return -90;
                case RIGHT: return 90;
                case HARD_LEFT: return -135;
                case HARD_RIGHT: return 135;
                default: return 0;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float w = s * 0.11f;
            paint.setStrokeWidth(w);
            dim.setStrokeWidth(w);
            paint.setStyle(Paint.Style.STROKE);
            if (action == Action.FINISH || action == Action.WAYPOINT) {
                // destination flag: pole + waving flag
                canvas.drawLine(cx - s * 0.18f, cy + s * 0.36f, cx - s * 0.18f, cy - s * 0.34f, paint);
                path.reset();
                path.moveTo(cx - s * 0.18f, cy - s * 0.34f);
                path.lineTo(cx + s * 0.28f, cy - s * 0.2f);
                path.lineTo(cx - s * 0.18f, cy - s * 0.04f);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawPath(path, paint);
                return;
            }
            if (action == Action.ENTER_ROUNDABOUT || action == Action.LEAVE_ROUNDABOUT) {
                float r = s * 0.17f;
                canvas.drawCircle(cx, cy - s * 0.02f, r, dim);
                canvas.drawLine(cx, cy + s * 0.4f, cx, cy + r, paint);
                RectF oval = new RectF(cx - r, cy - s * 0.02f - r, cx + r, cy - s * 0.02f + r);
                canvas.drawArc(oval, 90, 220, false, paint);
                float ex = (float) (cx + r * Math.cos(Math.toRadians(310))), ey = (float) (cy - s * 0.02f + r * Math.sin(Math.toRadians(310)));
                drawHead(canvas, ex, ey, ex + s * 0.2f, ey - s * 0.2f, s);
                canvas.drawLine(ex, ey, ex + s * 0.2f, ey - s * 0.2f, paint);
                return;
            }
            if (action == Action.UTURN_LEFT || action == Action.UTURN_RIGHT) {
                float dir = action == Action.UTURN_LEFT ? -1 : 1;
                float r = s * 0.16f;
                float x0 = cx - dir * r;
                path.reset();
                path.moveTo(x0, cy + s * 0.38f);
                path.lineTo(x0, cy - s * 0.1f);
                RectF oval = new RectF(Math.min(x0, x0 + 2 * dir * r), cy - s * 0.1f - r, Math.max(x0, x0 + 2 * dir * r), cy - s * 0.1f + r);
                path.arcTo(oval, 180, dir * 180);
                path.lineTo(x0 + 2 * dir * r, cy + s * 0.12f);
                canvas.drawPath(path, paint);
                drawHead(canvas, x0 + 2 * dir * r, cy - s * 0.1f, x0 + 2 * dir * r, cy + s * 0.14f, s);
                return;
            }
            float a = angle(action);
            float stemTop = cy - (a == 0 ? s * 0.3f : s * 0.02f);
            float baseX = cx - (float) Math.sin(Math.toRadians(a)) * s * 0.12f;
            path.reset();
            path.moveTo(baseX, cy + s * 0.4f);
            path.lineTo(baseX, stemTop);
            float len = a == 0 ? 0 : s * 0.32f;
            float ex = baseX + (float) Math.sin(Math.toRadians(a)) * len;
            float ey = stemTop - (float) Math.cos(Math.toRadians(a)) * len;
            if (a != 0) path.lineTo(ex, ey);
            canvas.drawPath(path, paint);
            if (a == 0) {
                drawHead(canvas, baseX, cy + s * 0.1f, baseX, stemTop - s * 0.06f, s);
            } else {
                drawHead(canvas, baseX, stemTop, ex, ey, s);
            }
        }

        private void drawHead(Canvas canvas, float fromX, float fromY, float toX, float toY, float s) {
            double ang = Math.atan2(toY - fromY, toX - fromX);
            float h = s * 0.2f;
            path.reset();
            path.moveTo(toX + (float) Math.cos(ang) * h * 0.35f, toY + (float) Math.sin(ang) * h * 0.35f);
            path.lineTo(toX + (float) Math.cos(ang + 2.4) * h, toY + (float) Math.sin(ang + 2.4) * h);
            path.lineTo(toX + (float) Math.cos(ang - 2.4) * h, toY + (float) Math.sin(ang - 2.4) * h);
            path.close();
            Paint.Style old = paint.getStyle();
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            float sw = paint.getStrokeWidth();
            paint.setStrokeWidth(s * 0.03f);
            canvas.drawPath(path, paint);
            paint.setStrokeWidth(sw);
            paint.setStyle(old);
        }
    }

    /** Small traffic-light glyph for the traffic toggle. */
    public static class TrafficIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on = true;
        private int offColor = 0xff8e8e93;

        public TrafficIcon(Context context) {
            super(context);
        }

        public void setOn(boolean on) {
            this.on = on;
            invalidate();
        }

        public void setOffColor(int color) {
            offColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = dp(3.2f), gap = dp(8);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(offColor);
            canvas.drawRoundRect(new RectF(cx - dp(6.5f), cy - dp(13), cx + dp(6.5f), cy + dp(13)), dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.FILL);
            int[] colors = {0xffff3b30, 0xffffcc00, 0xff34c759};
            for (int i = 0; i < 3; i++) {
                paint.setColor(on ? colors[i] : offColor);
                canvas.drawCircle(cx, cy + (i - 1) * gap, r, paint);
            }
        }
    }
}
