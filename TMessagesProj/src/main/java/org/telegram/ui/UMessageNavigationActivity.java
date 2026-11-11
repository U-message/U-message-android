package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.ui.Components.UMessageRouteUi.rounded;
import static org.telegram.ui.Components.UMessageRouteUi.text;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.ScreenRect;
import com.yandex.mapkit.directions.DirectionsFactory;
import com.yandex.mapkit.directions.driving.Action;
import com.yandex.mapkit.directions.driving.DrivingOptions;
import com.yandex.mapkit.directions.driving.DrivingRoute;
import com.yandex.mapkit.directions.driving.DrivingRouterType;
import com.yandex.mapkit.directions.driving.DrivingSection;
import com.yandex.mapkit.directions.driving.DrivingSession;
import com.yandex.mapkit.directions.driving.Event;
import com.yandex.mapkit.directions.driving.VehicleOptions;
import com.yandex.mapkit.geometry.Geo;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polyline;
import com.yandex.mapkit.geometry.PolylinePosition;
import com.yandex.mapkit.geometry.Subpolyline;
import com.yandex.mapkit.geometry.geo.PolylineIndex;
import com.yandex.mapkit.geometry.geo.PolylineUtils;
import com.yandex.mapkit.location.LocationListener;
import com.yandex.mapkit.location.LocationManager;
import com.yandex.mapkit.location.LocationStatus;
import com.yandex.mapkit.location.Purpose;
import com.yandex.mapkit.location.SubscriptionSettings;
import com.yandex.mapkit.location.UseInBackground;
import com.yandex.mapkit.map.CameraListener;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.CameraUpdateReason;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.Map;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolylineMapObject;
import com.yandex.mapkit.map.RotationType;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.navigation.JamSegment;
import com.yandex.mapkit.transport.TransportFactory;
import com.yandex.mapkit.transport.masstransit.Route;
import com.yandex.mapkit.transport.masstransit.Session;
import com.yandex.mapkit.transport.masstransit.TimeOptions;
import com.yandex.mapkit.transport.masstransit.TransitOptions;
import com.yandex.runtime.Error;
import com.yandex.runtime.image.ImageProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageNavCapsule;
import org.telegram.ui.Components.UMessageRouteUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * In-app turn-by-turn on top of a MapKit route: follows the user, snaps to the line, shows the next
 * maneuver, speed and speed limit, the current street, a traffic progress bar, speaks maneuvers,
 * hides the driven part, re-routes when off the line and announces arrival.
 */
public class UMessageNavigationActivity extends BaseFragment {

    private static final int PANEL = 0xff17181b;
    private static final int PANEL_BUTTON = 0xff2e2f34;

    private static class Maneuver {
        PolylinePosition position;
        double key;
        Action action;
        String text;
        String street;
    }

    private final Point destination;
    private final String destinationTitle;
    private final int mode;
    private DrivingRoute drivingRoute;
    private Route transportRoute;

    private Polyline geometry;
    private PolylineIndex index;
    private PolylinePosition endPosition;
    private double totalDistance, totalSeconds;
    private double[] cumulative;
    private final ArrayList<Maneuver> maneuvers = new ArrayList<>();
    private final ArrayList<double[]> sectionStreets = new ArrayList<>(); // [key] parallel to streetNames
    private final ArrayList<String> streetNames = new ArrayList<>();

    private MapView mapView;
    private Map map;
    private MapObjectCollection layer;
    private PolylineMapObject routeLine;
    private PlacemarkMapObject puck;
    private MapObjectCollection reportsLayer;
    private final ArrayList<org.telegram.messenger.UMessageRoadReports.Report> reports = new ArrayList<>();
    private final HashSet<String> reportsAsked = new HashSet<>();
    private final HashSet<String> reportsAlerted = new HashSet<>();
    private long reportsFetched;
    private Point reportsFetchPoint;
    private boolean reportsBusy;
    private FrameLayout reportPicker;
    private LinearLayout quickMenu;
    private final ArrayList<View> quickItems = new ArrayList<>();
    private int quickSelected = -1;
    private boolean quickShown;
    private float quickDownY;
    private LinearLayout reportPrompt;
    private final Runnable hidePrompt = () -> showPrompt(null);
    private boolean gotFix;
    private long startTime;
    private android.animation.ValueAnimator puckAnimator;
    private Point puckPoint;
    private float puckDirection;
    private LocationManager locationManager;
    /** The navigation that keeps running while its screen is closed (capsule on top of the app). */
    private static UMessageNavigationActivity active;
    private boolean started;
    private boolean minimized;
    private boolean stopping;
    private Action capsuleAction = Action.STRAIGHT;
    private String capsuleDistance = "";
    private String capsuleStreet = "";
    private long lastNotificationUpdate;
    private org.telegram.messenger.UMessageRoadReports.Report capsuleReport;
    private boolean simulating;
    private double simDistance;
    private long simStarted;
    private final Runnable simTick = this::simStep;
    private android.speech.SpeechRecognizer recognizer;
    private org.telegram.messenger.UMessageRoadReports.Report listeningFor;
    private DrivingSession drivingSession;
    private Session transportSession;
    private TextToSpeech tts;
    private boolean ttsReady;
    // Yandex SpeechKit voice first, Android TTS as fallback (no key / offline)
    private org.telegram.messenger.UMessageYandexVoice yandexVoice;
    private boolean muted;

    private boolean following = true;
    private boolean arrived;
    private boolean rerouting;
    private int offRouteCount;
    private Point lastPoint;
    private PolylinePosition lastPos = new PolylinePosition(0, 0);
    private float heading;
    private final HashSet<String> spoken = new HashSet<>();
    private boolean destroyed;
    private boolean dark;

    private FrameLayout root;
    private LinearLayout maneuverCard;
    private UMessageRouteUi.ManeuverView maneuverView;
    private TextView maneuverDistance;
    private TextView maneuverStreet;
    private TextView speedText;
    private GradientDrawable speedBg;
    private int currentLimit, currentSpeed;
    private boolean overSpeed;
    private final HashSet<String> radarAnnounced = new HashSet<>();
    private final java.util.HashMap<String, Long> radarWarnedAt = new java.util.HashMap<>();
    private final java.util.HashMap<String, Integer> radarWarnCount = new java.util.HashMap<>();
    private FrameLayout limitView;
    private TextView limitText;
    private TextView streetPill;
    private TextView distText, clockText, timeText;
    private ProgressBarView progressBar;
    private ImageView recenter;
    private ImageView soundButton;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationUpdated(com.yandex.mapkit.location.Location location) {
            if (!destroyed && location != null) onLocation(location);
        }

        @Override
        public void onLocationStatusUpdated(LocationStatus status) {
        }
    };

    private final CameraListener cameraListener = (m, position, reason, finished) -> {
        if (reason == CameraUpdateReason.GESTURES && following) {
            following = false;
            updateRecenter();
        }
    };

    /** Whole-route traffic strip with the driven part dimmed and a marker at the current spot. */
    private class ProgressBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path marker = new Path();
        private final RectF rect = new RectF();
        private float progress;

        ProgressBarView(Context context) {
            super(context);
        }

        void setProgress(float p) {
            progress = Math.max(0, Math.min(1, p));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = dp(6), top = (getHeight() - h) / 2f, w = getWidth() - dp(8), x0 = dp(4);
            rect.set(x0, top, x0 + w, top + h);
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(UMessageRouteUi.JAM_COLORS[1]);
            canvas.drawRect(rect, paint);
            List<JamSegment> jams = drivingRoute != null ? drivingRoute.getJamSegments() : null;
            if (jams != null && cumulative != null && totalDistance > 0) {
                int n = Math.min(jams.size(), cumulative.length - 1);
                for (int i = 0; i < n; i++) {
                    float a = (float) (cumulative[i] / totalDistance), b = (float) (cumulative[i + 1] / totalDistance);
                    paint.setColor(UMessageRouteUi.JAM_COLORS[UMessageRouteUi.jamIndex(jams.get(i))]);
                    canvas.drawRect(x0 + w * a, top, x0 + w * b + 1, top + h, paint);
                }
            } else if (drivingRoute == null) {
                paint.setColor(UMessageRouteUi.BLUE);
                canvas.drawRect(rect, paint);
            }
            paint.setColor(0xb3000000);
            canvas.drawRect(x0, top, x0 + w * progress, top + h, paint);
            canvas.restore();

            float mx = x0 + w * progress, my = getHeight() / 2f;
            marker.reset();
            marker.moveTo(mx + dp(9), my);
            marker.lineTo(mx - dp(7), my - dp(7));
            marker.lineTo(mx - dp(3), my);
            marker.lineTo(mx - dp(7), my + dp(7));
            marker.close();
            paint.setColor(0xff000000);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(marker, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(UMessageRouteUi.LIME);
            canvas.drawPath(marker, paint);
        }
    }

    public UMessageNavigationActivity(Point destination, String title, DrivingRoute route) {
        this.destination = destination;
        this.destinationTitle = title;
        this.mode = UMessageRouteUi.MODE_CAR;
        this.drivingRoute = route;
    }

    public UMessageNavigationActivity(Point destination, String title, int mode, Route route) {
        this.destination = destination;
        this.destinationTitle = title;
        this.mode = mode;
        this.transportRoute = route;
    }

    @Override
    public boolean isLightStatusBar() {
        return !dark;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return false;
    }

    @Override
    public View createView(Context context) {
        UMessageRouteActivity.ensureInit(context);
        dark = AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.721f;

        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(0xff000000);
        root.setKeepScreenOn(true);
        fragmentView = root;

        mapView = new MapView(context);
        root.addView(mapView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        map = mapView.getMapWindow().getMap();
        map.setNightModeEnabled(dark);
        map.addCameraListener(new WeakReference<>(cameraListener));
        layer = map.getMapObjects().addCollection();
        map.getMapObjects().addPlacemark(destination, ImageProvider.fromBitmap(UMessageRouteActivity.pinBitmap(0xffff3b30)),
                new IconStyle().setAnchor(new PointF(0.5f, 1f)).setZIndex(50f));

        createTopWidgets(context);
        createBottomBar(context);
        createReportUi(context);
        try {
            map.getLogo().setAlignment(new com.yandex.mapkit.logo.Alignment(com.yandex.mapkit.logo.HorizontalAlignment.LEFT, com.yandex.mapkit.logo.VerticalAlignment.BOTTOM));
            map.getLogo().setPadding(new com.yandex.mapkit.logo.Padding(dp(18), AndroidUtilities.navigationBarHeight + dp(10 + 62 + 12 + 52 + 10)));
        } catch (Throwable ignore) {
        }

        if (started) {
            // reopened from the capsule: navigation kept running, just rebuild the screen around it
            minimized = false;
            UMessageNavCapsule.hide();
            setRoute(drivingRoute, transportRoute, false);
            drawReports();
            Point at = puckPoint != null ? puckPoint : geometry.getPoints().get(0);
            map.move(new CameraPosition(at, 17.5f, heading, 55f));
            following = true;
            updateRecenter();
            mapView.post(this::updateFocusPoint);
            return root;
        }
        setRoute(drivingRoute, transportRoute, true);
        Point start = geometry.getPoints().get(0);
        startTime = android.os.SystemClock.elapsedRealtime();
        map.move(new CameraPosition(start, 17.5f, 0f, 55f));
        mapView.post(this::updateFocusPoint);

        locationManager = MapKitFactory.getInstance().createLocationManager();
        Purpose purpose = mode == UMessageRouteUi.MODE_CAR ? Purpose.AUTOMOTIVE_NAVIGATION : mode == UMessageRouteUi.MODE_BIKE ? Purpose.BICYCLE_NAVIGATION : Purpose.PEDESTRIAN_NAVIGATION;
        // ALLOW: keeps fixes coming while the app is in the background (the foreground service holds the process)
        locationManager.subscribeForLocationUpdates(new SubscriptionSettings(UseInBackground.ALLOW, purpose), new WeakReference<>(locationListener));

        initTts(context);
        if (active != null && active != this) active.stopNavigation();
        active = this;
        started = true;
        org.telegram.messenger.UMessageNavigationService.start(destinationTitle != null ? destinationTitle : "");
        return root;
    }

    private void updateFocusPoint() {
        if (mapView == null || destroyed) return;
        int w = mapView.getWidth(), h = mapView.getHeight();
        if (w > 0 && h > 0) {
            mapView.getMapWindow().setFocusPoint(new ScreenPoint(w / 2f, h * 0.72f));
        }
    }

    private void createTopWidgets(Context context) {
        int top = AndroidUtilities.statusBarHeight + dp(10);

        // maneuver card, top-left
        final int cardMaxWidth = (int) (AndroidUtilities.displaySize.x * 0.6f);
        maneuverCard = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int avail = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? cardMaxWidth : Math.min(cardMaxWidth, MeasureSpec.getSize(widthMeasureSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST), heightMeasureSpec);
            }
        };
        maneuverCard.setOrientation(LinearLayout.VERTICAL);
        maneuverCard.setMinimumWidth(dp(120));
        maneuverCard.setPadding(dp(12), dp(10), dp(16), dp(11));
        maneuverCard.setBackground(rounded(UMessageRouteUi.BLUE, dp(18)));
        maneuverCard.setElevation(dp(6));
        root.addView(maneuverCard, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 14, 0, 0, 0));
        ((FrameLayout.LayoutParams) maneuverCard.getLayoutParams()).topMargin = top;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // WRAP_CONTENT: a MATCH_PARENT row in a WRAP_CONTENT vertical LinearLayout does not count
        // toward the card width, so the card shrank to the street line and clipped the distance
        maneuverCard.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        maneuverView = new UMessageRouteUi.ManeuverView(context);
        row.addView(maneuverView, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        maneuverDistance = new TextView(context);
        maneuverDistance.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 27);
        maneuverDistance.setTypeface(AndroidUtilities.bold());
        maneuverDistance.setTextColor(0xffffffff);
        maneuverDistance.setSingleLine(true);
        maneuverDistance.setIncludeFontPadding(false);
        maneuverDistance.setEllipsize(null);
        row.addView(maneuverDistance, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        maneuverStreet = text(context, 15, 0xffffffff, false);
        maneuverStreet.setSingleLine(false);
        maneuverStreet.setMaxLines(2);
        maneuverStreet.setEllipsize(TextUtils.TruncateAt.END);
        maneuverCard.addView(maneuverStreet, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        // speed + speed limit, top-right
        FrameLayout speed = new FrameLayout(context);
        root.addView(speed, LayoutHelper.createFrame(112, 60, Gravity.TOP | Gravity.RIGHT, 0, 0, 12, 0));
        ((FrameLayout.LayoutParams) speed.getLayoutParams()).topMargin = top;
        speedText = new TextView(context);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        speedText.setTypeface(AndroidUtilities.bold());
        speedText.setTextColor(0xffffffff);
        speedText.setGravity(Gravity.CENTER);
        speedText.setText("0");
        // hidden test drive: long-press the speed dial to simulate driving along the route
        speedText.setOnLongClickListener(v -> {
            toggleSimulation();
            return true;
        });
        speedBg = new GradientDrawable();
        speedBg.setShape(GradientDrawable.OVAL);
        speedBg.setColor(0xff141518);
        speedText.setBackground(speedBg);
        speedText.setElevation(dp(4));
        speed.addView(speedText, LayoutHelper.createFrame(60, 60, Gravity.RIGHT, 0, 0, 52, 0));
        limitView = new FrameLayout(context);
        GradientDrawable limitBg = new GradientDrawable();
        limitBg.setShape(GradientDrawable.OVAL);
        limitBg.setColor(0xffffffff);
        limitBg.setStroke(dp(5), 0xffe8312f);
        limitView.setBackground(limitBg);
        limitView.setElevation(dp(5));
        limitText = new TextView(context);
        limitText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
        limitText.setTypeface(AndroidUtilities.bold());
        limitText.setTextColor(0xff111111);
        limitText.setGravity(Gravity.CENTER);
        limitView.addView(limitText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        limitView.setVisibility(View.GONE);
        speed.addView(limitView, LayoutHelper.createFrame(60, 60, Gravity.RIGHT));

        // sound + U mark under the speed widgets
        LinearLayout side = new LinearLayout(context);
        side.setOrientation(LinearLayout.VERTICAL);
        root.addView(side, LayoutHelper.createFrame(48, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 0, 12, 0));
        ((FrameLayout.LayoutParams) side.getLayoutParams()).topMargin = top + dp(60 + 12);
        soundButton = new ImageView(context);
        soundButton.setImageResource(R.drawable.umsg_volume_on);
        soundButton.setColorFilter(0xffffffff);
        soundButton.setScaleType(ImageView.ScaleType.CENTER);
        soundButton.setBackground(rounded(0xf2141518, dp(24)));
        soundButton.setElevation(dp(4));
        soundButton.setOnClickListener(v -> {
            muted = !muted;
            soundButton.setImageResource(muted ? R.drawable.umsg_volume_off : R.drawable.umsg_volume_on);
            if (muted && tts != null) tts.stop();
            if (muted && yandexVoice != null) yandexVoice.stop();
        });
        UMessageRouteUi.pressScale(soundButton);
        side.addView(soundButton, LayoutHelper.createLinear(48, 48));

        // recenter / follow-me button, always visible; blue while the camera is detached
        recenter = new ImageView(context);
        recenter.setImageResource(R.drawable.umsg_locate);
        recenter.setColorFilter(0xffffffff);
        recenter.setScaleType(ImageView.ScaleType.CENTER);
        recenter.setElevation(dp(4));
        recenter.setContentDescription(LocaleController.getString(R.string.UMessageNavContinue));
        recenter.setOnClickListener(v -> {
            following = true;
            updateRecenter();
            updateFocusPoint();
            if (lastPoint != null) followCamera(lastPoint, true);
        });
        UMessageRouteUi.pressScale(recenter);
        // bottom-right, just above the bottom bar
        root.addView(recenter, LayoutHelper.createFrame(52, 52, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 16, 0));
        ((FrameLayout.LayoutParams) recenter.getLayoutParams()).bottomMargin = AndroidUtilities.navigationBarHeight + dp(10 + 62 + 12);
        updateRecenter();

    }

    private void createBottomBar(Context context) {
        int navBottom = AndroidUtilities.navigationBarHeight + dp(10);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = rounded(PANEL, dp(24));
        bg.setStroke(dp(1), 0x1fffffff);
        bar.setBackground(bg);
        bar.setElevation(dp(10));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 62, Gravity.BOTTOM, 12, 0, 12, 0));
        ((FrameLayout.LayoutParams) bar.getLayoutParams()).bottomMargin = navBottom;

        ImageView overview = new ImageView(context);
        overview.setImageResource(R.drawable.umsg_fork);
        overview.setColorFilter(0xffffffff);
        overview.setScaleType(ImageView.ScaleType.CENTER);
        overview.setBackground(rounded(PANEL_BUTTON, dp(18)));
        overview.setOnClickListener(v -> showOverview());
        UMessageRouteUi.pressScale(overview);
        bar.addView(overview, LayoutHelper.createLinear(50, 50));

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setPadding(dp(6), dp(2), dp(6), 0);
        bar.addView(center, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        LinearLayout values = new LinearLayout(context);
        values.setOrientation(LinearLayout.HORIZONTAL);
        center.addView(values, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        distText = valueText(context);
        clockText = valueText(context);
        timeText = valueText(context);
        values.addView(distText, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        values.addView(clockText, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        values.addView(timeText, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        progressBar = new ProgressBarView(context);
        center.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16));

        ImageView end = new ImageView(context);
        end.setImageResource(R.drawable.umsg_close);
        end.setColorFilter(0xffffffff);
        end.setScaleType(ImageView.ScaleType.CENTER);
        end.setBackground(rounded(PANEL_BUTTON, dp(18)));
        end.setOnClickListener(v -> stopNavigation());
        UMessageRouteUi.pressScale(end);
        bar.addView(end, LayoutHelper.createLinear(50, 50));

        streetPill = new TextView(context);
        streetPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        streetPill.setTextColor(0xffffffff);
        streetPill.setSingleLine(true);
        streetPill.setEllipsize(TextUtils.TruncateAt.END);
        streetPill.setMaxWidth((int) (AndroidUtilities.displaySize.x * 0.62f));
        streetPill.setPadding(dp(16), dp(7), dp(16), dp(8));
        streetPill.setBackground(rounded(0xf0141518, dp(15)));
        streetPill.setVisibility(View.GONE);
        root.addView(streetPill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) streetPill.getLayoutParams()).bottomMargin = navBottom + dp(62 + 10);
    }

    private TextView valueText(Context context) {
        TextView v = new TextView(context);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        v.setTextColor(0xffffffff);
        v.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setEllipsize(null);
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, TypedValue.COMPLEX_UNIT_DIP);
        return v;
    }

    private void updateRecenter() {
        if (recenter == null) return;
        recenter.setBackground(rounded(following ? 0xf2141518 : UMessageRouteUi.BLUE, dp(26)));
    }

    private void showOverview() {
        if (mapView == null) return;
        following = false;
        updateRecenter();
        try {
            ArrayList<Point> pts = new ArrayList<>(geometry.getPoints());
            if (lastPoint != null) pts.add(lastPoint);
            float w = mapView.getWidth(), h = mapView.getHeight();
            ScreenRect rect = new ScreenRect(new ScreenPoint(dp(30), AndroidUtilities.statusBarHeight + dp(130)), new ScreenPoint(w - dp(30), h - AndroidUtilities.navigationBarHeight - dp(170)));
            mapView.getMapWindow().setFocusPoint(new ScreenPoint(w / 2f, h / 2f));
            CameraPosition pos = map.cameraPosition(Geometry.fromPolyline(new Polyline(pts)), rect);
            map.move(pos, new Animation(Animation.Type.SMOOTH, 0.6f), null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void setRoute(DrivingRoute dr, Route tr) {
        setRoute(dr, tr, true);
    }

    private void setRoute(DrivingRoute dr, Route tr, boolean fresh) {
        drivingRoute = dr;
        transportRoute = tr;
        geometry = dr != null ? dr.getGeometry() : tr.getGeometry();
        index = PolylineUtils.createPolylineIndex(geometry);
        List<Point> pts = geometry.getPoints();
        int n = pts.size();
        endPosition = new PolylinePosition(Math.max(0, n - 2), 1.0);
        cumulative = new double[n];
        for (int i = 1; i < n; i++) cumulative[i] = cumulative[i - 1] + Geo.distance(pts.get(i - 1), pts.get(i));
        totalDistance = cumulative[n - 1];
        totalSeconds = dr != null ? dr.getMetadata().getWeight().getTimeWithTraffic().getValue() : tr.getMetadata().getWeight().getTime().getValue();

        maneuvers.clear();
        spoken.clear();
        sectionStreets.clear();
        streetNames.clear();
        if (dr != null) {
            List<DrivingSection> sections = dr.getSections();
            for (int i = 0; i < sections.size(); i++) {
                DrivingSection s = sections.get(i);
                com.yandex.mapkit.directions.driving.Annotation a = s.getMetadata() != null ? s.getMetadata().getAnnotation() : null;
                String street = a != null ? a.getToponym() : null;
                sectionStreets.add(new double[]{key(s.getGeometry().getBegin())});
                streetNames.add(street);
                if (i == 0 || a == null) continue;
                if (a.getAction() == Action.STRAIGHT) continue;
                Maneuver m = new Maneuver();
                m.position = s.getGeometry().getBegin();
                m.key = key(m.position);
                m.action = a.getAction();
                m.text = !TextUtils.isEmpty(a.getDescriptionText()) ? a.getDescriptionText() : street;
                m.street = !TextUtils.isEmpty(street) ? street : m.text;
                maneuvers.add(m);
            }
        }
        Maneuver finish = new Maneuver();
        finish.position = endPosition;
        finish.key = key(endPosition);
        finish.action = Action.FINISH;
        finish.text = finish.street = !TextUtils.isEmpty(destinationTitle) ? destinationTitle : LocaleController.getString(R.string.UMessageNavDestination);
        maneuvers.add(finish);

        layer.clear();
        routeLine = layer.addPolyline(geometry);
        routeLine.setStrokeWidth(10f);
        routeLine.setOutlineColor(0xff101114);
        routeLine.setOutlineWidth(1.5f);
        routeLine.setTurnRadius(16f);
        if (dr != null && dr.getJamSegments() != null && !dr.getJamSegments().isEmpty()) {
            for (int c = 0; c < UMessageRouteUi.JAM_COLORS.length; c++) routeLine.setPaletteColor(c, UMessageRouteUi.JAM_COLORS[c]);
            ArrayList<Integer> colors = new ArrayList<>();
            for (JamSegment s : dr.getJamSegments()) colors.add(UMessageRouteUi.jamIndex(s));
            routeLine.setStrokeColors(colors);
        } else {
            routeLine.setStrokeColor(UMessageRouteUi.BLUE);
            if (mode == UMessageRouteUi.MODE_WALK) {
                routeLine.setDashLength(7f);
                routeLine.setGapLength(4f);
            }
        }
        if (dr != null && dr.getEvents() != null) {
            for (Event e : dr.getEvents()) {
                int kind = UMessageRouteUi.eventKind(e.getTags());
                if (kind < 0 || e.getLocation() == null) continue;
                layer.addPlacemark(e.getLocation(), ImageProvider.fromBitmap(UMessageRouteUi.eventIcon(kind)), new IconStyle().setZIndex(15f));
            }
        }
        puck = layer.addPlacemark(puckPoint != null ? puckPoint : pts.get(0), ImageProvider.fromBitmap(UMessageRouteUi.puckBitmap()),
                new IconStyle().setRotationType(RotationType.ROTATE).setFlat(true).setZIndex(100f).setAnchor(new PointF(0.5f, 0.5f)));
        puck.setZIndex(100f);
        puck.setDirection(puckDirection);
        puck.setVisible(gotFix);
        if (fresh) {
            lastPos = new PolylinePosition(0, 0);
            simDistance = 0;
        }
        updatePanel(lastPos);
    }

    private static double key(PolylinePosition p) {
        return p.getSegmentIndex() + p.getSegmentPosition();
    }

    private double distanceAt(PolylinePosition p) {
        int i = Math.min(p.getSegmentIndex(), cumulative.length - 2);
        if (i < 0) return 0;
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * p.getSegmentPosition();
    }

    private void onLocation(com.yandex.mapkit.location.Location location) {
        if (location.getSpeed() != null) {
            currentSpeed = (int) Math.max(0, Math.round(location.getSpeed() * 3.6));
            speedText.setText(String.valueOf(currentSpeed));
            updateSpeedStyle();
            pushCapsule();
        }
        if (arrived) return;
        // the first fixes are often coarse network positions: wait (up to 6 s) for an accurate one
        Double accuracy = location.getAccuracy();
        if (!gotFix && accuracy != null && accuracy > 40 && android.os.SystemClock.elapsedRealtime() - startTime < 6000) return;
        boolean firstFix = !gotFix;
        if (firstFix) {
            gotFix = true;
            puck.setVisible(true);
        }
        Point raw = location.getPosition();
        onReportsLocation(raw);
        double tolerance = mode == UMessageRouteUi.MODE_CAR ? 45 : 30;
        PolylinePosition pos = index.closestPolylinePosition(raw, PolylineIndex.Priority.CLOSEST_TO_RAW_POINT, tolerance);
        if (pos == null) {
            lastPoint = raw;
            if (location.getHeading() != null) heading = location.getHeading().floatValue();
            movePuck(raw, heading, firstFix);
            if (following) followCamera(raw, false);
            if ((firstFix || ++offRouteCount >= 3) && !rerouting) reroute(raw);
            return;
        }
        offRouteCount = 0;
        Point snapped = PolylineUtils.pointByPolylinePosition(geometry, pos);
        PolylinePosition ahead = PolylineUtils.advancePolylinePosition(geometry, pos, 25);
        Point aheadPoint = PolylineUtils.pointByPolylinePosition(geometry, ahead);
        if (Geo.distance(snapped, aheadPoint) > 2) heading = (float) Geo.course(snapped, aheadPoint);
        lastPoint = snapped;
        lastPos = pos;
        movePuck(snapped, heading, firstFix);
        try {
            routeLine.hide(new Subpolyline(new PolylinePosition(0, 0), pos));
        } catch (Throwable ignore) {
        }
        if (following) followCamera(snapped, false);
        updatePanel(pos);

        double left = totalDistance - distanceAt(pos);
        if (left < 25 || Geo.distance(raw, destination) < 30) onArrived();
    }

    /** Glides the arrow to the new fix instead of jumping (matches the camera animation). */
    private void movePuck(Point to, float direction, boolean instant) {
        if (puckAnimator != null) puckAnimator.cancel();
        if (instant || puckPoint == null || Geo.distance(puckPoint, to) > 300) {
            puckPoint = to;
            puckDirection = direction;
            puck.setGeometry(to);
            puck.setDirection(direction);
            return;
        }
        final Point from = puckPoint;
        final float fromDir = puckDirection;
        float delta = ((direction - fromDir) % 360 + 540) % 360 - 180;
        puckAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        puckAnimator.setDuration(900);
        puckAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        puckAnimator.addUpdateListener(a -> {
            if (destroyed || puck == null || !puck.isValid()) return;
            float t = (float) a.getAnimatedValue();
            puckPoint = new Point(from.getLatitude() + (to.getLatitude() - from.getLatitude()) * t, from.getLongitude() + (to.getLongitude() - from.getLongitude()) * t);
            puckDirection = fromDir + delta * t;
            puck.setGeometry(puckPoint);
            puck.setDirection(puckDirection);
        });
        puckAnimator.start();
    }

    private void followCamera(Point p, boolean animatedLong) {
        if (animatedLong) updateFocusPoint();
        float zoom = mode == UMessageRouteUi.MODE_CAR ? 17.5f : 18.2f;
        map.move(new CameraPosition(p, zoom, heading, 55f), new Animation(Animation.Type.SMOOTH, animatedLong ? 0.6f : 0.9f), null);
    }

    private void updatePanel(PolylinePosition pos) {
        double posKey = key(pos);
        double done = distanceAt(pos);
        double left = Math.max(0, totalDistance - done);
        double secondsLeft = totalDistance > 0 ? totalSeconds * (left / totalDistance) : 0;
        distText.setText(UMessageRouteUi.formatDistance(left));
        timeText.setText(UMessageRouteUi.formatTime(secondsLeft));
        Calendar eta = Calendar.getInstance();
        eta.add(Calendar.SECOND, (int) secondsLeft);
        clockText.setText(String.format(Locale.US, "%d:%02d", eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE)));
        progressBar.setProgress(totalDistance > 0 ? (float) (done / totalDistance) : 0);

        // speed limit of the current segment (MapKit gives m/s)
        if (drivingRoute != null && drivingRoute.getSpeedLimits() != null && pos.getSegmentIndex() < drivingRoute.getSpeedLimits().size()) {
            Float v = drivingRoute.getSpeedLimits().get(pos.getSegmentIndex());
            if (v != null && v > 0) {
                int kmh = v > 45 ? Math.round(v) : (int) (Math.round(v * 3.6 / 5.0) * 5);
                limitText.setText(String.valueOf(kmh));
                limitView.setVisibility(View.VISIBLE);
                currentLimit = kmh;
            } else {
                limitView.setVisibility(View.GONE);
                currentLimit = 0;
            }
            updateSpeedStyle();
        }

        // current street
        String street = null;
        for (int i = 0; i < sectionStreets.size(); i++) {
            if (sectionStreets.get(i)[0] <= posKey + 1e-6) street = streetNames.get(i);
            else break;
        }
        if (!TextUtils.isEmpty(street)) {
            streetPill.setText(street);
            streetPill.setVisibility(View.VISIBLE);
        } else {
            streetPill.setVisibility(View.GONE);
        }

        if (rerouting) return;
        Maneuver next = null;
        int nextIndex = -1;
        for (int i = 0; i < maneuvers.size(); i++) {
            if (maneuvers.get(i).key > posKey + 1e-6) {
                next = maneuvers.get(i);
                nextIndex = i;
                break;
            }
        }
        if (next == null) next = maneuvers.get(maneuvers.size() - 1);
        double toNext = Math.max(0, distanceAt(next.position) - done);
        maneuverView.setAction(next.action);
        maneuverDistance.setText(UMessageRouteUi.formatDistance(toNext));
        if (mode != UMessageRouteUi.MODE_CAR && next.action == Action.FINISH) {
            maneuverStreet.setText(LocaleController.getString(R.string.UMessageNavFollowRoute));
        } else {
            String st = next.street;
            if (!TextUtils.isEmpty(st)) st = Character.toUpperCase(st.charAt(0)) + st.substring(1);
            maneuverStreet.setText(st);
            maneuverStreet.setVisibility(TextUtils.isEmpty(st) ? View.GONE : View.VISIBLE);
        }
        capsuleAction = next.action;
        capsuleDistance = UMessageRouteUi.formatDistance(toNext);
        capsuleStreet = mode != UMessageRouteUi.MODE_CAR && next.action == Action.FINISH ? LocaleController.getString(R.string.UMessageNavFollowRoute) : next.street;
        pushCapsule();
        if (mode == UMessageRouteUi.MODE_CAR && next.action != Action.FINISH) {
            announce(nextIndex, next, toNext);
        }
    }

    private void announce(int i, Maneuver m, double distance) {
        String level = distance < 70 ? "now" : distance < 280 ? "near" : distance < 800 ? "far" : null;
        if (level == null || TextUtils.isEmpty(m.text)) return;
        if (!spoken.add(i + ":" + level)) return;
        if ("far".equals(level) && spoken.contains(i + ":near")) return;
        if ("now".equals(level)) {
            speak(m.text);
        } else {
            String t = m.text.length() > 1 ? Character.toLowerCase(m.text.charAt(0)) + m.text.substring(1) : m.text;
            speak(LocaleController.formatString(R.string.UMessageNavInDistance, UMessageRouteUi.formatDistance(Math.round(distance / 50.0) * 50), t));
        }
    }

    private void onArrived() {
        if (arrived) return;
        arrived = true;
        maneuverView.setAction(Action.FINISH);
        maneuverDistance.setText(LocaleController.getString(R.string.UMessageNavArrived));
        maneuverStreet.setText(!TextUtils.isEmpty(destinationTitle) ? destinationTitle : "");
        distText.setText(UMessageRouteUi.formatDistance(0));
        progressBar.setProgress(1f);
        speak(LocaleController.getString(R.string.UMessageNavArrived));
        try {
            routeLine.setVisible(false);
        } catch (Throwable ignore) {
        }
        capsuleAction = Action.FINISH;
        capsuleDistance = LocaleController.getString(R.string.UMessageNavArrived);
        capsuleStreet = destinationTitle;
        pushCapsule();
        AndroidUtilities.runOnUIThread(() -> {
            if (started && !stopping) stopNavigation();
        }, 20_000);
    }

    private void reroute(Point from) {
        rerouting = true;
        maneuverView.setAction(Action.STRAIGHT);
        maneuverDistance.setText("···");
        maneuverStreet.setText(LocaleController.getString(R.string.UMessageNavRerouting));
        try {
            if (mode == UMessageRouteUi.MODE_CAR) {
                DrivingOptions o = new DrivingOptions().setRoutesCount(1).setAnnotationLanguage(UMessageRouteUi.annotationLanguage()).setInitialAzimuth((double) heading);
                drivingSession = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
                        .requestRoutes(UMessageRouteActivity.points(from, destination), o, new VehicleOptions(), new DrivingSession.DrivingRouteListener() {
                            @Override
                            public void onDrivingRoutes(List<DrivingRoute> list) {
                                if (destroyed) return;
                                rerouting = false;
                                if (!list.isEmpty()) setRoute(list.get(0), null);
                            }

                            @Override
                            public void onDrivingRoutesError(Error error) {
                                rerouting = false;
                            }
                        });
            } else {
                Session.RouteListener l = new Session.RouteListener() {
                    @Override
                    public void onMasstransitRoutes(List<Route> list) {
                        if (destroyed) return;
                        rerouting = false;
                        if (!list.isEmpty()) setRoute(null, list.get(0));
                    }

                    @Override
                    public void onMasstransitRoutesError(Error error) {
                        rerouting = false;
                    }
                };
                if (mode == UMessageRouteUi.MODE_TRANSIT) {
                    transportSession = TransportFactory.getInstance().createMasstransitRouter().requestRoutes(UMessageRouteActivity.points(from, destination), new TransitOptions(0, new TimeOptions()), UMessageRouteActivity.routeOptions(), l);
                } else if (mode == UMessageRouteUi.MODE_BIKE) {
                    transportSession = TransportFactory.getInstance().createBicycleRouterV2().requestRoutes(UMessageRouteActivity.points(from, destination), new TimeOptions(), UMessageRouteActivity.routeOptions(), l);
                } else {
                    transportSession = TransportFactory.getInstance().createPedestrianRouter().requestRoutes(UMessageRouteActivity.points(from, destination), new TimeOptions(), UMessageRouteActivity.routeOptions(), l);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            rerouting = false;
        }
    }

    private void initTts(Context context) {
        String voiceLang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "uz";
        yandexVoice = new org.telegram.messenger.UMessageYandexVoice(context, currentAccount, voiceLang);
        try {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "en";
                Locale wanted = lang != null && (lang.startsWith("uz") || lang.startsWith("kaa")) ? new Locale("uz", "UZ") : lang != null ? new Locale(lang) : Locale.getDefault();
                int r = tts.setLanguage(wanted);
                if (r < 0) r = tts.setLanguage(new Locale("ru", "RU"));
                if (r < 0) tts.setLanguage(Locale.getDefault());
                ttsReady = true;
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void speak(String phrase) {
        if (muted || TextUtils.isEmpty(phrase)) return;
        if (yandexVoice != null) {
            yandexVoice.speak(phrase, null, () -> systemSpeak(phrase));
            return;
        }
        systemSpeak(phrase);
    }

    private void systemSpeak(String phrase) {
        if (muted || !ttsReady || tts == null || TextUtils.isEmpty(phrase)) return;
        try {
            if (BuildVars.LOGS_ENABLED) FileLog.d("UMNav: speak: " + phrase);
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, "umsg_nav"); // queued, so a camera alert isn't cut by a turn
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- road reports (police, radar, ...)

    private void createReportUi(Context context) {
        reportsLayer = map.getMapObjects().addCollection();

        ImageView button = new ImageView(context);
        button.setImageResource(R.drawable.umsg_report);
        button.setColorFilter(0xffffffff);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(rounded(0xffff8a00, dp(26)));
        button.setElevation(dp(4));
        button.setContentDescription(LocaleController.getString(R.string.UMessageReportTitle));
        attachQuickMenu(button);
        root.addView(button, LayoutHelper.createFrame(52, 52, Gravity.BOTTOM | Gravity.LEFT, 16, 0, 0, 0));
        ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin = AndroidUtilities.navigationBarHeight + dp(10 + 62 + 12);

        // long-press + slide menu: options stacked above the button, nearest = most common
        quickMenu = new LinearLayout(context);
        quickMenu.setOrientation(LinearLayout.VERTICAL);
        quickMenu.setVisibility(View.GONE);
        quickMenu.setElevation(dp(35));
        root.addView(quickMenu, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 12, 0, 0, 0));
        ((FrameLayout.LayoutParams) quickMenu.getLayoutParams()).bottomMargin = AndroidUtilities.navigationBarHeight + dp(10 + 62 + 12 + 52 + 10);
        String[] types = org.telegram.messenger.UMessageRoadReports.TYPES;
        for (int i = types.length - 1; i >= 0; i--) {
            String type = types[i];
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setTag(type);
            FrameLayout disc = new FrameLayout(context);
            disc.setBackground(rounded(PANEL, dp(30)));
            disc.setElevation(dp(6));
            ImageView icon = new ImageView(context);
            icon.setImageBitmap(UMessageRouteUi.reportImage(type, 46));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            disc.addView(icon, LayoutHelper.createFrame(50, 46, Gravity.CENTER));
            item.addView(disc, LayoutHelper.createLinear(60, 60));
            TextView label = text(context, 15, 0xffffffff, true);
            label.setText(LocaleController.getString(UMessageRouteUi.reportName(type)));
            label.setPadding(dp(12), dp(6), dp(12), dp(7));
            label.setBackground(rounded(0xe6141518, dp(14)));
            item.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            quickMenu.addView(item, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            quickItems.add(0, item); // quickItems[0] = nearest to the button
        }

        // quick picker: dimmed backdrop + a card with one big tile per type
        reportPicker = new FrameLayout(context);
        reportPicker.setBackgroundColor(0x99000000);
        reportPicker.setVisibility(View.GONE);
        reportPicker.setElevation(dp(40));
        reportPicker.setOnClickListener(v -> showReportPicker(false));
        root.addView(reportPicker, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(16), dp(16), dp(16), dp(18));
        card.setBackground(rounded(PANEL, dp(26)));
        reportPicker.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 0));
        ((FrameLayout.LayoutParams) card.getLayoutParams()).bottomMargin = AndroidUtilities.navigationBarHeight + dp(10);
        TextView title = text(context, 18, 0xffffffff, true);
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.UMessageReportTitle));
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 112));
        for (int i = 0; i < org.telegram.messenger.UMessageRoadReports.TYPES.length; i++) {
            String type = org.telegram.messenger.UMessageRoadReports.TYPES[i];
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(rounded(PANEL_BUTTON, dp(20)));
            ImageView icon = new ImageView(context);
            icon.setImageBitmap(UMessageRouteUi.reportImage(type, 60));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60, 4, 0, 4, 0));
            TextView label = text(context, 13, 0xffffffff, true);
            label.setGravity(Gravity.CENTER);
            label.setText(LocaleController.getString(UMessageRouteUi.reportName(type)));
            tile.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 0));
            tile.setOnClickListener(v -> submitReport(type));
            UMessageRouteUi.pressScale(tile);
            row.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, i == 0 ? 0 : 4, 0, i == 3 ? 0 : 4, 0));
        }

        // "still there?" prompt shown while passing someone else's report
        reportPrompt = new LinearLayout(context);
        reportPrompt.setOrientation(LinearLayout.HORIZONTAL);
        reportPrompt.setGravity(Gravity.CENTER_VERTICAL);
        reportPrompt.setPadding(dp(10), dp(10), dp(10), dp(10));
        reportPrompt.setBackground(rounded(PANEL, dp(22)));
        reportPrompt.setElevation(dp(8));
        reportPrompt.setVisibility(View.GONE);
        reportPrompt.setElevation(dp(30));
        reportPrompt.setClickable(true);
        root.addView(reportPrompt, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 0));
        ((FrameLayout.LayoutParams) reportPrompt.getLayoutParams()).bottomMargin = AndroidUtilities.navigationBarHeight + dp(10 + 62 + 76);
    }

    /** Hidden test drive: feeds fake fixes along the route every second (~72 km/h), as if driving. */
    private void toggleSimulation() {
        if (simulating) {
            simulating = false;
            AndroidUtilities.cancelRunOnUIThread(simTick);
            Purpose purpose = mode == UMessageRouteUi.MODE_CAR ? Purpose.AUTOMOTIVE_NAVIGATION : Purpose.PEDESTRIAN_NAVIGATION;
            locationManager.subscribeForLocationUpdates(new SubscriptionSettings(UseInBackground.DISALLOW, purpose), new WeakReference<>(locationListener));
            android.widget.Toast.makeText(ctx(), "Simulation off", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            locationManager.unsubscribe(new WeakReference<>(locationListener));
        } catch (Throwable ignore) {
        }
        simulating = true;
        simDistance = 0;
        simStarted = android.os.SystemClock.elapsedRealtime();
        radarAnnounced.clear();
        radarWarnedAt.clear();
        radarWarnCount.clear();
        reportsAlerted.clear();
        reportsAsked.clear();
        lastSlowDown = 0;
        following = true;
        updateRecenter();
        simStep();
        android.widget.Toast.makeText(ctx(), "Simulation on", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void simStep() {
        if (!simulating || destroyed || geometry == null) return;
        double speed = mode == UMessageRouteUi.MODE_CAR ? 20 : 2;
        PolylinePosition start = new PolylinePosition(0, 0);
        PolylinePosition at = PolylineUtils.advancePolylinePosition(geometry, start, simDistance);
        PolylinePosition next = PolylineUtils.advancePolylinePosition(geometry, start, simDistance + 15);
        Point p = PolylineUtils.pointByPolylinePosition(geometry, at);
        Point q = PolylineUtils.pointByPolylinePosition(geometry, next);
        double course = Geo.distance(p, q) > 1 ? Geo.course(p, q) : heading;
        long now = System.currentTimeMillis();
        onLocation(new com.yandex.mapkit.location.Location(p, 4.0, null, null, course, speed, null, now, android.os.SystemClock.elapsedRealtime() - simStarted));
        simDistance += speed;
        if (simDistance > totalDistance + 30) {
            toggleSimulation();
            return;
        }
        AndroidUtilities.runOnUIThread(simTick, 1000);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachQuickMenu(View button) {
        final Runnable longPress = () -> showQuickMenu(true);
        button.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    quickDownY = ev.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start();
                    AndroidUtilities.runOnUIThread(longPress, 280);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!quickShown && quickDownY - ev.getRawY() > dp(18)) {
                        AndroidUtilities.cancelRunOnUIThread(longPress);
                        showQuickMenu(true);
                    }
                    if (quickShown) selectQuick(ev.getRawX(), ev.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    AndroidUtilities.cancelRunOnUIThread(longPress);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                    if (quickShown) {
                        int sel = quickSelected;
                        showQuickMenu(false);
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && sel >= 0) {
                            submitReport((String) quickItems.get(sel).getTag());
                        }
                    } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        showReportPicker(true);
                    }
                    return true;
            }
            return false;
        });
    }

    private void showQuickMenu(boolean show) {
        quickShown = show;
        quickSelected = -1;
        if (show) {
            try {
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            } catch (Throwable ignore) {
            }
            quickMenu.setVisibility(View.VISIBLE);
            for (int i = 0; i < quickItems.size(); i++) {
                View item = quickItems.get(i);
                item.setAlpha(0f);
                item.setTranslationY(dp(24));
                item.setScaleX(1f);
                item.setScaleY(1f);
                item.animate().alpha(1f).translationY(0).setStartDelay(i * 35L).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
            }
        } else {
            quickMenu.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                quickMenu.setVisibility(View.GONE);
                quickMenu.setAlpha(1f);
            }).start();
        }
    }

    private void selectQuick(float rawX, float rawY) {
        int found = -1;
        int[] loc = new int[2];
        for (int i = 0; i < quickItems.size(); i++) {
            View item = quickItems.get(i);
            item.getLocationOnScreen(loc);
            // generous hit area: the whole row height, from the screen edge to well past the label
            if (rawY >= loc[1] - dp(5) && rawY <= loc[1] + item.getHeight() + dp(5) && rawX <= loc[0] + item.getWidth() + dp(80)) {
                found = i;
                break;
            }
        }
        if (found == quickSelected) return;
        quickSelected = found;
        if (found >= 0) {
            try {
                root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            } catch (Throwable ignore) {
            }
        }
        for (int i = 0; i < quickItems.size(); i++) {
            float sc = i == found ? 1.18f : (found >= 0 ? 0.92f : 1f);
            View item = quickItems.get(i);
            item.setPivotX(dp(30));
            item.setPivotY(item.getHeight() / 2f);
            item.animate().scaleX(sc).scaleY(sc).alpha(found < 0 || i == found ? 1f : 0.6f).setStartDelay(0).setDuration(120).start();
        }
    }

    private void showReportPicker(boolean show) {
        if (show) {
            reportPicker.setVisibility(View.VISIBLE);
            reportPicker.setAlpha(0f);
            reportPicker.animate().alpha(1f).setDuration(180).start();
        } else {
            reportPicker.animate().alpha(0f).setDuration(150).withEndAction(() -> reportPicker.setVisibility(View.GONE)).start();
        }
    }

    private void toast(int res) {
        android.widget.Toast.makeText(ctx(), LocaleController.getString(res), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void submitReport(String type) {
        showReportPicker(false);
        Point at = puckPoint != null ? puckPoint : lastPoint;
        if (at == null) {
            toast(R.string.UMessageRouteNoLocation);
            return;
        }
        org.telegram.messenger.UMessageRoadReports.add(currentAccount, type, at.getLatitude(), at.getLongitude(), r -> {
            if (destroyed) return;
            if (r == null) {
                toast(R.string.UMessageReportError);
                return;
            }
            toast(R.string.UMessageReportSent);
            reportsAsked.add(r.id);
            for (int i = 0; i < reports.size(); i++) {
                if (reports.get(i).id.equals(r.id)) {
                    reports.remove(i);
                    break;
                }
            }
            reports.add(r);
            drawReports();
        });
    }

    private void onReportsLocation(Point p) {
        long now = System.currentTimeMillis();
        if (!reportsBusy && (now - reportsFetched > 60_000 || reportsFetchPoint == null || Geo.distance(reportsFetchPoint, p) > 3000)) {
            reportsBusy = true;
            reportsFetched = now;
            reportsFetchPoint = p;
            org.telegram.messenger.UMessageRoadReports.list(currentAccount, p.getLatitude(), p.getLongitude(), list -> {
                reportsBusy = false;
                if (destroyed || list == null) return;
                reports.clear();
                reports.addAll(list);
                drawReports();
            });
        }
        org.telegram.messenger.UMessageRoadReports.Report near = null;
        for (org.telegram.messenger.UMessageRoadReports.Report r : reports) {
            double d = Geo.distance(p, new Point(r.lat, r.lng));
            // "still there?" — also for your own report once it is a few minutes old (e.g. the way back)
            // (the reporter's own report already carries their "yes" vote, so only skip it when they said "gone")
            boolean askable = r.mine ? (r.voted != -1 && System.currentTimeMillis() - r.time > 5 * 60_000) : r.voted == 0;
            if (d < 120 && askable && !reportsAsked.contains(r.id) && near == null) near = r;
            if (UMessageRoadReports_RADAR.equals(r.type)) continue; // radars get their own speed-aware alerts
            if (d < 500 && isAhead(p, new Point(r.lat, r.lng), d) && reportsAlerted.add(r.id)) {
                speak(LocaleController.formatString(R.string.UMessageReportAhead, LocaleController.getString(UMessageRouteUi.reportName(r.type))));
            }
        }
        if (near != null) {
            reportsAsked.add(near.id);
            showPrompt(near);
        }
        checkRadars(p);
    }

    private Context ctx() {
        Context c = getParentActivity();
        return c != null ? c : org.telegram.messenger.ApplicationLoader.applicationContext;
    }

    /** Capsule + ongoing notification while the navigation screen is closed. */
    private void pushCapsule() {
        if (!started || stopping) return;
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate > 4000) {
            lastNotificationUpdate = now;
            org.telegram.messenger.UMessageNavigationService.update(capsuleDistance + (TextUtils.isEmpty(capsuleStreet) ? "" : " \u00b7 " + capsuleStreet));
        }
        if (!minimized) return;
        UMessageNavCapsule capsule = UMessageNavCapsule.get();
        if (capsule != null) capsule.update(capsuleAction, capsuleDistance, capsuleStreet, currentSpeed, currentLimit);
    }

    private void showCapsule() {
        android.app.Activity activity = org.telegram.ui.LaunchActivity.instance;
        UMessageNavCapsule capsule = UMessageNavCapsule.show(activity, new UMessageNavCapsule.Delegate() {
            @Override
            public void onOpen() {
                reopen();
            }

            @Override
            public void onAnswer(int vote) {
                org.telegram.messenger.UMessageRoadReports.Report r = capsuleReport;
                capsuleReport = null;
                if (r != null) answer(r, vote);
            }
        });
        if (capsule != null) capsule.update(capsuleAction, capsuleDistance, capsuleStreet, currentSpeed, currentLimit);
    }

    private void reopen() {
        BaseFragment last = org.telegram.ui.LaunchActivity.getLastFragment();
        if (last == null || !minimized) return;
        setFinishing(false);
        last.presentFragment(this);
    }

    /** Real stop (the X button, or a while after arriving): releases GPS, voice, service and capsule. */
    public void stopNavigation() {
        stopping = true;
        if (minimized) {
            minimized = false;
            UMessageNavCapsule.hide();
            release();
        } else {
            finishFragment();
        }
    }

    private void release() {
        AndroidUtilities.cancelRunOnUIThread(hidePrompt);
        AndroidUtilities.cancelRunOnUIThread(simTick);
        simulating = false;
        stopListening();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
        destroyed = true;
        if (puckAnimator != null) puckAnimator.cancel();
        if (drivingSession != null) drivingSession.cancel();
        if (transportSession != null) transportSession.cancel();
        try {
            if (locationManager != null) locationManager.unsubscribe(new WeakReference<>(locationListener));
        } catch (Throwable ignore) {
        }
        if (yandexVoice != null) {
            yandexVoice.release();
            yandexVoice = null;
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignore) {
            }
            tts = null;
        }
        try {
            MapKitFactory.getInstance().onStop();
        } catch (Throwable ignore) {
        }
        org.telegram.messenger.UMessageNavigationService.stop();
        UMessageNavCapsule.hide();
        if (active == this) active = null;
        started = false;
    }

    private static final String UMessageRoadReports_RADAR = org.telegram.messenger.UMessageRoadReports.RADAR;

    private static int toKmh(Float v) {
        if (v == null || v <= 0) return 0;
        return v > 45 ? Math.round(v) : (int) (Math.round(v * 3.6 / 5.0) * 5);
    }

    private boolean isAhead(Point from, Point at, double d) {
        if (d <= 40) return true;
        float bearing = (float) Geo.course(from, at);
        return Math.abs(((bearing - heading) % 360 + 540) % 360 - 180) <= 75;
    }

    private long lastSlowDown;

    /** Over the limit: red speed dial. */
    private void updateSpeedStyle() {
        boolean over = currentLimit > 0 && currentSpeed > currentLimit + 3;
        if (over == overSpeed || speedBg == null) return;
        overSpeed = over;
        speedBg.setColor(over ? 0xffe8262b : 0xff141518);
    }

    private void flashSpeed() {
        speedText.animate().cancel();
        speedText.setScaleX(1.25f);
        speedText.setScaleY(1.25f);
        speedText.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
    }

    /**
     * Speed cameras ahead, from drivers' radar reports and from Yandex route events: announce the camera and
     * its limit at ~500 m, then keep telling the driver to slow down while over the limit within ~300 m.
     */
    private void checkRadars(Point p) {
        ArrayList<Object[]> radars = new ArrayList<>(); // {key, Point, limitKmh}
        for (org.telegram.messenger.UMessageRoadReports.Report r : reports) {
            if (UMessageRoadReports_RADAR.equals(r.type)) radars.add(new Object[]{"r:" + r.id, new Point(r.lat, r.lng), 0});
        }
        if (drivingRoute != null && drivingRoute.getEvents() != null) {
            List<Event> events = drivingRoute.getEvents();
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                if (e.getLocation() != null && UMessageRouteUi.eventKind(e.getTags()) == 0) {
                    radars.add(new Object[]{"e:" + (e.getEventId() != null ? e.getEventId() : String.valueOf(i)), e.getLocation(), toKmh(e.getSpeedLimit())});
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Object[] radar : radars) {
            String key = (String) radar[0];
            Point at = (Point) radar[1];
            double d = Geo.distance(p, at);
            if (d > 600) continue;
            if (!isAhead(p, at, d)) continue; // only cameras in front of us
            int limit = (Integer) radar[2] > 0 ? (Integer) radar[2] : currentLimit;
            if (d <= 520 && !radarAnnounced.contains(key)) {
                // cameras within 80 m of an announced one are the same spot
                boolean dup = false;
                for (Object[] o : radars) {
                    if (o != radar && radarAnnounced.contains((String) o[0]) && Geo.distance((Point) o[1], at) < 80) {
                        dup = true;
                        break;
                    }
                }
                radarAnnounced.add(key);
                if (dup) continue;
                speak(limit > 0 ? LocaleController.formatString(R.string.UMessageNavRadarAhead, limit) : LocaleController.getString(R.string.UMessageNavRadarAheadNoLimit));
                flashSpeed();
                radarWarnedAt.put(key, now); // the announcement counts as the first nudge
                continue;
            }
            if (d <= 300 && limit > 0 && currentSpeed > limit + 3) {
                Long last = radarWarnedAt.get(key);
                int count = radarWarnCount.containsKey(key) ? radarWarnCount.get(key) : 0;
                // several cameras often sit at one spot: one shared cooldown keeps it to a single voice
                if ((last == null || now - last > 8000) && count < 3 && now - lastSlowDown > 8000) {
                    lastSlowDown = now;
                    radarWarnedAt.put(key, now);
                    radarWarnCount.put(key, count + 1);
                    speak(LocaleController.getString(R.string.UMessageNavSlowDown));
                    flashSpeed();
                }
            }
        }
    }

    private void drawReports() {
        if (reportsLayer == null) return;
        reportsLayer.clear();
        for (org.telegram.messenger.UMessageRoadReports.Report r : reports) {
            boolean is3d = UMessageRouteUi.report3dRes(r.type) != 0;
            PlacemarkMapObject pm = reportsLayer.addPlacemark(new Point(r.lat, r.lng), ImageProvider.fromBitmap(UMessageRouteUi.reportImage(r.type, is3d ? 56 : 40)),
                    new IconStyle().setZIndex(40f).setAnchor(new PointF(0.5f, is3d ? 0.9f : 0.5f)));
            // disputed reports stay visible (so more drivers can vote) but look faded
            pm.setOpacity(r.confidence < 0.5 ? 0.55f : 1f);
        }
    }

    private void showPrompt(org.telegram.messenger.UMessageRoadReports.Report r) {
        AndroidUtilities.cancelRunOnUIThread(hidePrompt);
        stopListening();
        if (minimized) {
            UMessageNavCapsule capsule = UMessageNavCapsule.get();
            capsuleReport = r;
            if (capsule == null) return;
            if (r == null) {
                capsule.showPrompt(null, null);
                return;
            }
            String q = LocaleController.formatString(R.string.UMessageReportStillThere, LocaleController.getString(UMessageRouteUi.reportName(r.type)));
            capsule.showPrompt(q, UMessageRouteUi.reportImage(r.type, 40));
            if (canListen()) speakThen(q, () -> startListening(r));
            AndroidUtilities.runOnUIThread(hidePrompt, 15_000);
            return;
        }
        if (r == null) {
            if (reportPrompt.getVisibility() == View.VISIBLE) {
                reportPrompt.animate().alpha(0f).translationY(dp(16)).setDuration(160).withEndAction(() -> reportPrompt.setVisibility(View.GONE)).start();
            }
            return;
        }
        Context context = reportPrompt.getContext();
        reportPrompt.removeAllViews();
        reportPrompt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        reportPrompt.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        ImageView icon = new ImageView(context);
        icon.setImageBitmap(UMessageRouteUi.reportImage(r.type, 52));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        top.addView(icon, LayoutHelper.createLinear(72, 52, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        top.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        TextView question = text(context, 18, 0xffffffff, true);
        question.setSingleLine(false);
        question.setMaxLines(2);
        String q = LocaleController.formatString(R.string.UMessageReportStillThere, LocaleController.getString(UMessageRouteUi.reportName(r.type)));
        question.setText(q);
        texts.addView(question);
        TextView hint = text(context, 13, 0xff9a9aa1, false);
        hint.setText("\uD83C\uDFA4  " + LocaleController.getString(R.string.UMessageReportVoiceHint));
        hint.setVisibility(canListen() ? View.VISIBLE : View.GONE);
        texts.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        reportPrompt.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68));
        buttons.addView(voteButton(context, "\uD83D\uDC4D", R.string.UMessageReportYes, UMessageRouteUi.GREEN, r, 1), LayoutHelper.createLinear(0, 68, 1f, 0, 0, 5, 0));
        buttons.addView(voteButton(context, "\uD83D\uDC4E", R.string.UMessageReportNo, 0xffe8262b, r, -1), LayoutHelper.createLinear(0, 68, 1f, 5, 0, 0, 0));
        // ask out loud, then listen for yes/no in the app language
        if (canListen()) {
            speakThen(q, () -> startListening(r));
        }
        reportPrompt.setVisibility(View.VISIBLE);
        reportPrompt.setAlpha(0f);
        reportPrompt.setTranslationY(dp(16));
        reportPrompt.animate().alpha(1f).translationY(0).setDuration(200).start();
        AndroidUtilities.runOnUIThread(hidePrompt, 15_000);
    }

    private TextView voteButton(Context context, String emoji, int label, int color, org.telegram.messenger.UMessageRoadReports.Report r, int vote) {
        TextView b = new TextView(context);
        b.setText(emoji + "  " + LocaleController.getString(label));
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(0xffffffff);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(color, dp(20)));
        b.setOnClickListener(v -> answer(r, vote));
        UMessageRouteUi.pressScale(b);
        return b;
    }

    private void answer(org.telegram.messenger.UMessageRoadReports.Report r, int vote) {
        {
            r.voted = vote;
            showPrompt(null);
            org.telegram.messenger.UMessageRoadReports.vote(currentAccount, r, vote, updated -> {
                if (destroyed) return;
                if (updated == null && vote < 0) {
                    reports.remove(r);
                } else if (updated != null) {
                    r.up = updated.up;
                    r.down = updated.down;
                    r.confidence = updated.confidence;
                }
                drawReports();
            });
        }
    }

    // ---------------------------------------------------------------- voice answers for "still there?"

    private static final String[] YES_WORDS = {"ha", "xa", "haa", "ҳа", "ха", "албатта", "albatta", "bor", "turibdi", "awa", "ава", "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "да", "ага", "есть", "стоит", "так", "evet", "si", "sì", "sim", "ja", "oui", "نعم"};
    private static final String[] NO_WORDS = {"yo'q", "yoq", "yuq", "йўқ", "йук", "йок", "ketgan", "ketdi", "yaq", "joq", "жоқ", "no", "nope", "nah", "gone", "нет", "неа", "не", "уехал", "уехали", "hayır", "hayir", "yok", "nein", "non", "não", "لا"};

    private boolean canListen() {
        Context c = ctx();
        return !muted
                && c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && android.speech.SpeechRecognizer.isRecognitionAvailable(c);
    }

    private void speakThen(String phrase, Runnable after) {
        if (yandexVoice != null && !muted) {
            boolean[] fellBack = {false};
            yandexVoice.speak(phrase, () -> {
                if (!fellBack[0]) after.run();
            }, () -> {
                fellBack[0] = true;
                systemSpeakThen(phrase, after);
            });
            return;
        }
        systemSpeakThen(phrase, after);
    }

    private void systemSpeakThen(String phrase, Runnable after) {
        if (muted || !ttsReady || tts == null) {
            after.run();
            return;
        }
        try {
            String id = "ask" + System.nanoTime();
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if (id.equals(utteranceId)) AndroidUtilities.runOnUIThread(after);
                }

                @Override
                public void onError(String utteranceId) {
                    if (id.equals(utteranceId)) AndroidUtilities.runOnUIThread(after);
                }
            });
            if (BuildVars.LOGS_ENABLED) FileLog.d("UMNav: speak: " + phrase);
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, id);
        } catch (Throwable e) {
            after.run();
        }
    }

    private void startListening(org.telegram.messenger.UMessageRoadReports.Report r) {
        if (destroyed || (!minimized && reportPrompt.getVisibility() != View.VISIBLE) || (minimized && capsuleReport == null) || !canListen()) return;
        try {
            if (recognizer == null) recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx());
            listeningFor = r;
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override
                public void onReadyForSpeech(android.os.Bundle params) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("UMNav: listening");
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("UMNav: recognizer error " + error);
                }

                @Override
                public void onResults(android.os.Bundle results) {
                    handleSpeech(results);
                }

                @Override
                public void onPartialResults(android.os.Bundle partial) {
                    handleSpeech(partial);
                }

                @Override
                public void onEvent(int eventType, android.os.Bundle params) {
                }
            });
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "en";
            String tag;
            if (lang != null && (lang.startsWith("uz") || lang.startsWith("kaa"))) tag = "uz-UZ";
            else if (lang != null && lang.startsWith("ru")) tag = "ru-RU";
            else if (lang != null && lang.length() >= 2) tag = lang.substring(0, 2);
            else tag = "en-US";
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            recognizer.startListening(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void stopListening() {
        listeningFor = null;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Throwable ignore) {
            }
        }
    }

    private void handleSpeech(android.os.Bundle bundle) {
        org.telegram.messenger.UMessageRoadReports.Report r = listeningFor;
        if (r == null || bundle == null) return;
        ArrayList<String> heard = bundle.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null) return;
        for (String phrase : heard) {
            int v = matchAnswer(phrase);
            if (BuildVars.LOGS_ENABLED) FileLog.d("UMNav: heard: " + phrase + " -> " + v);
            if (v != 0) {
                listeningFor = null;
                answer(r, v);
                return;
            }
        }
    }

    /** 1 = yes / still there, -1 = no / gone, 0 = not understood. "No" wins when both appear. */
    static int matchAnswer(String phrase) {
        if (phrase == null) return 0;
        String t = phrase.toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'').replace('ʻ', '\'').replace('ʼ', '\'').replace('`', '\'');
        t = " " + t.replaceAll("[.,!?;:«»\"]", " ") + " ";
        for (String w : NO_WORDS) {
            if (t.contains(" " + w + " ")) return -1;
        }
        for (String w : YES_WORDS) {
            if (t.contains(" " + w + " ")) return 1;
        }
        return 0;
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            MapKitFactory.getInstance().onStart();
            if (mapView != null) mapView.onStart();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (quickShown) showQuickMenu(false); // a lost ACTION_UP must not leave the slide menu stuck open
        try {
            if (mapView != null) mapView.onStop();
            if (!started || stopping) MapKitFactory.getInstance().onStop(); // navigating: keep GPS running in background
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (started && !stopping && !destroyed) {
            // closed with back: keep navigating in the background and show the capsule over the app
            minimized = true;
            if (quickShown) showQuickMenu(false);
            if (reportPrompt != null) reportPrompt.setVisibility(View.GONE);
            showCapsule();
            mapView = null;
            super.onFragmentDestroy();
            return;
        }
        if (started) release();
        mapView = null;
        super.onFragmentDestroy();
    }
}
