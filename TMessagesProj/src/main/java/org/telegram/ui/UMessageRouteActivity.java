package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.ui.Components.UMessageRouteUi.rounded;
import static org.telegram.ui.Components.UMessageRouteUi.text;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.RequestPoint;
import com.yandex.mapkit.RequestPointType;
import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.ScreenRect;
import com.yandex.mapkit.directions.DirectionsFactory;
import com.yandex.mapkit.directions.driving.DrivingOptions;
import com.yandex.mapkit.directions.driving.DrivingRoute;
import com.yandex.mapkit.directions.driving.DrivingRouter;
import com.yandex.mapkit.directions.driving.DrivingRouterType;
import com.yandex.mapkit.directions.driving.DrivingSession;
import com.yandex.mapkit.directions.driving.DrivingSummarySession;
import com.yandex.mapkit.directions.driving.Event;
import com.yandex.mapkit.directions.driving.VehicleOptions;
import com.yandex.mapkit.geometry.Geo;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polyline;
import com.yandex.mapkit.geometry.PolylinePosition;
import com.yandex.mapkit.geometry.geo.PolylineUtils;
import com.yandex.mapkit.location.LocationListener;
import com.yandex.mapkit.location.LocationManager;
import com.yandex.mapkit.location.LocationStatus;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.Map;
import com.yandex.mapkit.map.MapObject;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.MapObjectTapListener;
import com.yandex.mapkit.map.MapType;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolylineMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.navigation.JamSegment;
import com.yandex.mapkit.traffic.TrafficLayer;
import com.yandex.mapkit.transport.TransportFactory;
import com.yandex.mapkit.transport.masstransit.FitnessOptions;
import com.yandex.mapkit.transport.masstransit.Route;
import com.yandex.mapkit.transport.masstransit.RouteOptions;
import com.yandex.mapkit.transport.masstransit.Session;
import com.yandex.mapkit.transport.masstransit.SummarySession;
import com.yandex.mapkit.transport.masstransit.TimeOptions;
import com.yandex.mapkit.transport.masstransit.TransitOptions;
import com.yandex.mapkit.user_location.UserLocationLayer;
import com.yandex.runtime.Error;
import com.yandex.runtime.image.ImageProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.UMessageRouteUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Route preview on Yandex MapKit: car (live traffic, alternatives with time-delta bubbles, road
 * events), transit, walking and bicycle. "Start" offers Yandex Navigator when it is installed (car
 * only); otherwise, or on "no", it opens {@link UMessageNavigationActivity}.
 */
public class UMessageRouteActivity extends BaseFragment {

    public static final int MODE_CAR = UMessageRouteUi.MODE_CAR, MODE_WALK = UMessageRouteUi.MODE_WALK,
            MODE_TRANSIT = UMessageRouteUi.MODE_TRANSIT, MODE_BIKE = UMessageRouteUi.MODE_BIKE;

    private static boolean initialized;

    /**
     * Hidden for now: Yandex MapKit/NaviKit is a paid license. The code stays; flip to true to show the route
     * screen again. While false every caller keeps the old external-app flow.
     */
    public static final boolean ENABLED = false;

    /** MapKit needs API 26+ and a key; otherwise callers keep the old external-app flow. */
    public static boolean isAvailable() {
        return ENABLED && android.os.Build.VERSION.SDK_INT >= 26 && !TextUtils.isEmpty(BuildConfig.YANDEX_MAPKIT_KEY);
    }

    public static void ensureInit(Context context) {
        if (initialized) return;
        MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_KEY);
        String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "en";
        String locale;
        if (lang != null && lang.startsWith("ru")) locale = "ru_RU";
        else if (lang != null && lang.startsWith("uk")) locale = "uk_UA";
        else if (lang != null && lang.startsWith("tr")) locale = "tr_TR";
        else if (lang != null && (lang.startsWith("uz") || lang.startsWith("kaa"))) locale = "uz_UZ";
        else locale = "en_US";
        MapKitFactory.setLocale(locale);
        MapKitFactory.initialize(context.getApplicationContext());
        initialized = true;
    }

    static RouteOptions routeOptions() {
        return new RouteOptions(new FitnessOptions(false, false));
    }

    static ArrayList<RequestPoint> points(Point from, Point to) {
        ArrayList<RequestPoint> points = new ArrayList<>();
        points.add(new RequestPoint(from, RequestPointType.WAYPOINT, null, null, null));
        points.add(new RequestPoint(to, RequestPointType.WAYPOINT, null, null, null));
        return points;
    }

    static Point pointAt(Polyline line, double fraction) {
        try {
            int n = line.getPoints().size();
            PolylinePosition start = new PolylinePosition(0, 0);
            double total = PolylineUtils.distanceBetweenPolylinePositions(line, start, new PolylinePosition(Math.max(0, n - 2), 1.0));
            return PolylineUtils.pointByPolylinePosition(line, PolylineUtils.advancePolylinePosition(line, start, total * fraction));
        } catch (Throwable e) {
            return line.getPoints().get(line.getPoints().size() / 2);
        }
    }

    private final Point destination;
    private final String destinationTitle;
    private String destinationSubtitle;
    private TextView subtitleView;
    private LinearLayout routeArea;
    private final boolean autoStart;
    private Point origin;
    private double originAccuracy = 10000;
    private int originRefines;

    private MapView mapView;
    private Map map;
    private MapObjectCollection routesLayer;
    private TrafficLayer trafficLayer;
    private UserLocationLayer userLocationLayer;
    private LocationManager locationManager;
    private DrivingRouter drivingRouter;
    private DrivingSession drivingSession;
    private Session transportSession;
    private final ArrayList<Object> summarySessions = new ArrayList<>();
    private final ArrayList<MapObjectTapListener> tapListeners = new ArrayList<>();

    private int mode;
    private int selected;
    private final ArrayList<DrivingRoute> drivingRoutes = new ArrayList<>();
    private final ArrayList<Route> transportRoutes = new ArrayList<>();
    private final double[] modeEta = new double[4];

    private UMessageRouteUi.Palette pal;
    private FrameLayout root;
    private LinearLayout sheet;
    private LinearLayout rightControls;
    private final LinearLayout[] modeTabs = new LinearLayout[4];
    private LinearLayout cards;
    private LinearLayout stateRow;
    private RadialProgressView progress;
    private TextView stateText;
    private TextView startButton;
    private TextView chooseCaption;
    private TextView buildButton;
    private boolean pulsed;
    private UMessageRouteUi.TrafficIcon trafficIcon;
    private boolean destroyed;
    private boolean pendingStart;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationUpdated(com.yandex.mapkit.location.Location location) {
            if (destroyed || location == null) return;
            double acc = location.getAccuracy() != null ? location.getAccuracy() : 500;
            if (origin == null) {
                origin = location.getPosition();
                originAccuracy = acc;
                updateSubtitle();
                requestSummaries();
                if (mode >= 0) requestRoutes();
            } else if (acc < originAccuracy && acc <= 40 && originRefines < 2 && Geo.distance(origin, location.getPosition()) > 35) {
                // a coarse / stale first fix was replaced by GPS: rebuild from the real spot
                originRefines++;
                origin = location.getPosition();
                originAccuracy = acc;
                updateSubtitle();
                requestSummaries();
                if (mode >= 0) requestRoutes();
            } else if (acc < originAccuracy) {
                originAccuracy = acc;
            }
            if (originAccuracy <= 25) stopLocationUpdates();
        }

        @Override
        public void onLocationStatusUpdated(LocationStatus locationStatus) {
        }
    };

    private final DrivingSession.DrivingRouteListener drivingListener = new DrivingSession.DrivingRouteListener() {
        @Override
        public void onDrivingRoutes(List<DrivingRoute> routes) {
            if (destroyed) return;
            drivingRoutes.clear();
            drivingRoutes.addAll(routes);
            onRoutesReady();
        }

        @Override
        public void onDrivingRoutesError(Error error) {
            onRouteError();
        }
    };

    private final Session.RouteListener transportListener = new Session.RouteListener() {
        @Override
        public void onMasstransitRoutes(List<Route> routes) {
            if (destroyed) return;
            transportRoutes.clear();
            transportRoutes.addAll(routes);
            onRoutesReady();
        }

        @Override
        public void onMasstransitRoutesError(Error error) {
            onRouteError();
        }
    };

    public UMessageRouteActivity(double lat, double lon, String title, android.location.Location myLocation) {
        this(lat, lon, title, myLocation, MODE_CAR, false);
    }

    /** Plain place view: pin + name, no route until a travel mode is picked at the bottom. */
    public static UMessageRouteActivity forPlace(double lat, double lon, String title, String subtitle) {
        UMessageRouteActivity f = new UMessageRouteActivity(lat, lon, title, null, -1, false);
        f.destinationSubtitle = subtitle;
        return f;
    }

    public UMessageRouteActivity(double lat, double lon, String title, android.location.Location myLocation, int initialMode) {
        this(lat, lon, title, myLocation, initialMode, false);
    }

    public UMessageRouteActivity(double lat, double lon, String title, android.location.Location myLocation, int initialMode, boolean autoStart) {
        destination = new Point(lat, lon);
        destinationTitle = title;
        mode = initialMode;
        this.autoStart = autoStart;
        if (myLocation != null) {
            origin = new Point(myLocation.getLatitude(), myLocation.getLongitude());
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !pal.dark;
    }

    @Override
    public View createView(Context context) {
        ensureInit(context);
        pal = new UMessageRouteUi.Palette(AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.721f);
        pendingStart = autoStart;

        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(pal.sheet);
        fragmentView = root;

        mapView = new MapView(context);
        root.addView(mapView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        map = mapView.getMapWindow().getMap();
        map.setNightModeEnabled(pal.dark);
        map.move(new CameraPosition(destination, mode < 0 ? 16f : 15f, 0f, 0f));
        routesLayer = map.getMapObjects().addCollection();
        map.getMapObjects().addPlacemark(destination, ImageProvider.fromBitmap(pinBitmap(0xffff3b30)), new IconStyle().setAnchor(new PointF(0.5f, 1f)).setZIndex(50f));

        trafficLayer = MapKitFactory.getInstance().createTrafficLayer(mapView.getMapWindow());
        trafficLayer.setTrafficVisible(false);
        userLocationLayer = MapKitFactory.getInstance().createUserLocationLayer(mapView.getMapWindow());
        userLocationLayer.setVisible(true);

        createMapControls(context);
        createSheet(context);

        if (origin != null) {
            requestSummaries();
            if (mode >= 0) requestRoutes();
            updateSubtitle();
        } else {
            showState(true, LocaleController.getString(R.string.UMessageRouteNoLocation));
        }
        // keep listening briefly so a coarse or stale first position gets corrected by GPS
        locationManager = MapKitFactory.getInstance().createLocationManager();
        locationManager.subscribeForLocationUpdates(new com.yandex.mapkit.location.SubscriptionSettings(com.yandex.mapkit.location.UseInBackground.DISALLOW, com.yandex.mapkit.location.Purpose.GENERAL), new WeakReference<>(locationListener));
        AndroidUtilities.runOnUIThread(this::stopLocationUpdates, 20000);
        return root;
    }

    private void createMapControls(Context context) {
        rightControls = new LinearLayout(context);
        rightControls.setOrientation(LinearLayout.VERTICAL);
        rightControls.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(rightControls, LayoutHelper.createFrame(56, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 12, 0));
        rightControls.addView(UMessageRouteUi.mapButton(context, R.drawable.umsg_plus, pal, 16), LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));
        rightControls.getChildAt(0).setOnClickListener(v -> zoom(1));
        rightControls.addView(UMessageRouteUi.mapButton(context, R.drawable.umsg_minus, pal, 16), LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));
        rightControls.getChildAt(1).setOnClickListener(v -> zoom(-1));
        ImageView locate = UMessageRouteUi.mapButton(context, R.drawable.umsg_locate, pal, 26);
        locate.setOnClickListener(v -> focusMe());
        rightControls.addView(locate, LayoutHelper.createLinear(52, 52, Gravity.CENTER_HORIZONTAL));
    }

    private void createSheet(Context context) {
        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(UMessageRouteUi.roundedTop(pal.sheet, dp(28)));
        sheet.setElevation(dp(12));
        sheet.setPadding(0, dp(10), 0, dp(12) + AndroidUtilities.navigationBarHeight);
        sheet.setClickable(true);
        root.addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        sheet.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rightControls.getLayoutParams();
            int margin = (b - t) + dp(16);
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin;
                rightControls.setLayoutParams(lp);
            }
        });

        View grip = new View(context);
        grip.setBackground(rounded(pal.dark ? 0x40ffffff : 0x26000000, dp(2)));
        sheet.addView(grip, LayoutHelper.createLinear(40, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        // header: back · from/to · spacer
        FrameLayout header = new FrameLayout(context);
        sheet.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 0, 0, 0, 12));
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.umsg_back);
        back.setColorFilter(pal.icon);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(rounded(pal.pill, dp(20)));
        back.setOnClickListener(v -> finishFragment());
        UMessageRouteUi.pressScale(back);
        header.addView(back, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 72, 0, 72, 0));
        TextView to = text(context, 17, pal.text, true);
        to.setGravity(Gravity.CENTER);
        to.setText(!TextUtils.isEmpty(destinationTitle) ? destinationTitle : String.format(Locale.US, "%.5f, %.5f", destination.getLatitude(), destination.getLongitude()));
        titles.addView(to, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleView = text(context, 14, pal.secondary, false);
        subtitleView.setGravity(Gravity.CENTER);
        titles.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        updateSubtitle();

        // plain mode: hint caption above the travel-mode buttons
        chooseCaption = text(context, 13, pal.secondary, false);
        chooseCaption.setGravity(Gravity.CENTER);
        chooseCaption.setText(LocaleController.getString(R.string.UMessageRouteChooseMode));
        chooseCaption.setVisibility(mode < 0 ? View.VISIBLE : View.GONE);
        sheet.addView(chooseCaption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, -4, 16, 8));

        // modes: equal-width buttons, icon above time; selected one on a pill
        LinearLayout modes = new LinearLayout(context);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(dp(13), 0, dp(13), 0);
        sheet.addView(modes, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 0, 0, 12));

        routeArea = new LinearLayout(context);
        routeArea.setOrientation(LinearLayout.VERTICAL);
        routeArea.setVisibility(mode >= 0 ? View.VISIBLE : View.GONE);
        for (int m : UMessageRouteUi.ORDER) {
            LinearLayout tab = new LinearLayout(context);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(4), dp(4), dp(4));
            ImageView icon = new ImageView(context);
            icon.setImageResource(UMessageRouteUi.icon(m));
            tab.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 3));
            TextView eta = text(context, 14, pal.text, false);
            eta.setSingleLine(false);
            eta.setMaxLines(1);
            eta.setEllipsize(TextUtils.TruncateAt.END);
            eta.setGravity(Gravity.CENTER);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(eta, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            eta.setText("···");
            tab.addView(eta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 18));
            final int mm = m;
            tab.setOnClickListener(v -> setMode(mm));
            UMessageRouteUi.pressScale(tab);
            modes.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 3, 0, 3, 0));
            modeTabs[m] = tab;
        }

        // plain mode: primary call-to-action that picks the fastest mode
        buildButton = new TextView(context);
        buildButton.setText(LocaleController.getString(R.string.UMessageRouteTitle));
        buildButton.setTextSize(17);
        buildButton.setTypeface(AndroidUtilities.bold());
        buildButton.setTextColor(0xffffffff);
        buildButton.setGravity(Gravity.CENTER);
        buildButton.setBackground(rounded(UMessageRouteUi.BLUE, dp(26)));
        buildButton.setOnClickListener(v -> setMode(fastestMode()));
        UMessageRouteUi.pressScale(buildButton);
        buildButton.setVisibility(mode < 0 ? View.VISIBLE : View.GONE);
        sheet.addView(buildButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 16, 0, 16, 0));

        sheet.addView(routeArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (mode < 0) modes.postDelayed(this::pulseTabs, 450);
        updateModeTabs();

        // route alternatives
        FrameLayout routesBox = new FrameLayout(context);
        routeArea.addView(routesBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 74, 0, 0, 0, 14));
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(18), 0, dp(14), 0);
        cards = new LinearLayout(context);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(cards, new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        routesBox.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        stateRow = new LinearLayout(context);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER);
        progress = new RadialProgressView(context);
        progress.setSize(dp(22));
        progress.setProgressColor(UMessageRouteUi.BLUE);
        stateRow.addView(progress, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        stateText = text(context, 16, pal.secondary, false);
        stateRow.addView(stateText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        routesBox.addView(stateRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // bottom row: traffic · Start · map type
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        routeArea.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 16, 0, 16, 0));

        trafficIcon = new UMessageRouteUi.TrafficIcon(context);
        trafficIcon.setOffColor(pal.secondary);
        trafficIcon.setOn(false);
        trafficIcon.setOnClickListener(v -> {
            boolean on = !trafficLayer.isTrafficVisible();
            trafficLayer.setTrafficVisible(on);
            trafficIcon.setOn(on);
        });
        UMessageRouteUi.pressScale(trafficIcon);
        bottom.addView(trafficIcon, LayoutHelper.createLinear(48, 48));

        startButton = new TextView(context);
        startButton.setText(LocaleController.getString(R.string.UMessageRouteStart));
        startButton.setTextSize(17);
        startButton.setTypeface(AndroidUtilities.bold());
        startButton.setTextColor(0xffffffff);
        startButton.setGravity(Gravity.CENTER);
        startButton.setBackground(rounded(UMessageRouteUi.BLUE, dp(27)));
        startButton.setOnClickListener(v -> onStartClick());
        UMessageRouteUi.pressScale(startButton);
        bottom.addView(startButton, LayoutHelper.createLinear(0, 54, 1f, 12, 0, 12, 0));

        ImageView layers = new ImageView(context);
        layers.setImageResource(R.drawable.umsg_layers);
        layers.setColorFilter(pal.icon);
        layers.setScaleType(ImageView.ScaleType.CENTER);
        layers.setOnClickListener(v -> cycleMapType());
        UMessageRouteUi.pressScale(layers);
        bottom.addView(layers, LayoutHelper.createLinear(48, 48));
        setStartEnabled(false);
    }

    private void updateSubtitle() {
        if (subtitleView == null) return;
        if (origin != null) {
            float[] d = new float[1];
            android.location.Location.distanceBetween(origin.getLatitude(), origin.getLongitude(), destination.getLatitude(), destination.getLongitude(), d);
            String dist = LocaleController.formatDistance(d[0], 1);
            subtitleView.setText(!TextUtils.isEmpty(destinationSubtitle) ? destinationSubtitle + " · " + dist : dist);
        } else {
            subtitleView.setText(!TextUtils.isEmpty(destinationSubtitle) ? destinationSubtitle : LocaleController.getString(R.string.UMessageRouteNoLocation));
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            try {
                locationManager.unsubscribe(new WeakReference<>(locationListener));
            } catch (Throwable ignore) {
            }
        }
    }

    private void setStartEnabled(boolean enabled) {
        startButton.setEnabled(enabled);
        startButton.animate().alpha(enabled ? 1f : 0.45f).setDuration(180).start();
    }

    private void showState(boolean loading, String message) {
        stateRow.setVisibility(View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        stateText.setText(message);
        cards.removeAllViews();
    }

    private void setMode(int m) {
        if (mode == m) return;
        if (mode < 0) {
            routeArea.setVisibility(View.VISIBLE);
            routeArea.setAlpha(0f);
            routeArea.animate().alpha(1f).setDuration(220).start();
        }
        mode = m;
        if (chooseCaption != null) chooseCaption.setVisibility(View.GONE);
        if (buildButton != null) buildButton.setVisibility(View.GONE);
        updateModeTabs();
        requestRoutes();
    }

    /** Fastest mode by summary ETA; car until summaries arrive. */
    private int fastestMode() {
        int best = MODE_CAR;
        double bestEta = Double.MAX_VALUE;
        for (int m : UMessageRouteUi.ORDER) {
            if (modeEta[m] > 0 && modeEta[m] < bestEta) {
                bestEta = modeEta[m];
                best = m;
            }
        }
        return best;
    }

    /** One gentle staggered bounce so the plain-mode tabs read as tappable buttons. */
    private void pulseTabs() {
        if (pulsed || destroyed || mode >= 0) return;
        pulsed = true;
        int i = 0;
        for (int m : UMessageRouteUi.ORDER) {
            View tab = modeTabs[m];
            if (tab == null) continue;
            long delay = i++ * 70L;
            tab.animate().scaleX(1.07f).scaleY(1.07f).setStartDelay(delay).setDuration(160)
                    .withEndAction(() -> tab.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).start()).start();
        }
    }

    private void updateModeTabs() {
        for (int m : UMessageRouteUi.ORDER) {
            LinearLayout tab = modeTabs[m];
            if (tab == null) continue;
            TextView eta = (TextView) tab.getChildAt(1);
            if (mode < 0) {
                // plain mode: every option is an outlined button; the fastest gets a blue outline
                boolean fastest = modeEta[m] > 0 && m == fastestMode();
                tab.setBackground(UMessageRouteUi.stroked(pal.pill, fastest ? UMessageRouteUi.BLUE : pal.divider, dp(fastest ? 1.5f : 1f), dp(16)));
                ((ImageView) tab.getChildAt(0)).setColorFilter(fastest ? UMessageRouteUi.BLUE : pal.text);
                eta.setTextColor(pal.text);
            } else {
                boolean on = m == mode;
                tab.setBackground(on ? rounded(pal.pill, dp(16)) : null);
                ((ImageView) tab.getChildAt(0)).setColorFilter(on ? pal.text : pal.secondary);
                eta.setTextColor(on ? pal.text : pal.secondary);
            }
            eta.setText(modeEta[m] > 0 ? UMessageRouteUi.formatTime(modeEta[m]) : modeEta[m] < 0 ? "—" : "···");
        }
    }

    private void requestSummaries() {
        if (origin == null) return;
        ArrayList<RequestPoint> pts = points(origin, destination);
        try {
            summarySessions.add(DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
                    .requestRoutesSummary(pts, new DrivingOptions().setRoutesCount(1), new VehicleOptions(), new DrivingSummarySession.DrivingSummaryListener() {
                        @Override
                        public void onDrivingSummaries(List<com.yandex.mapkit.directions.driving.Summary> list) {
                            setModeEta(MODE_CAR, list.isEmpty() ? -1 : list.get(0).getWeight().getTimeWithTraffic().getValue());
                        }

                        @Override
                        public void onDrivingSummariesError(Error error) {
                            setModeEta(MODE_CAR, -1);
                        }
                    }));
            summarySessions.add(TransportFactory.getInstance().createMasstransitRouter().requestRoutesSummary(pts, new TransitOptions(0, new TimeOptions()), routeOptions(), summaryListener(MODE_TRANSIT)));
            summarySessions.add(TransportFactory.getInstance().createPedestrianRouter().requestRoutesSummary(pts, new TimeOptions(), routeOptions(), summaryListener(MODE_WALK)));
            summarySessions.add(TransportFactory.getInstance().createBicycleRouterV2().requestRoutesSummary(pts, new TimeOptions(), routeOptions(), summaryListener(MODE_BIKE)));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private SummarySession.SummaryListener summaryListener(int m) {
        return new SummarySession.SummaryListener() {
            @Override
            public void onMasstransitSummaries(List<com.yandex.mapkit.transport.masstransit.Summary> list) {
                setModeEta(m, list.isEmpty() ? -1 : list.get(0).getWeight().getTime().getValue());
            }

            @Override
            public void onMasstransitSummariesError(Error error) {
                setModeEta(m, -1);
            }
        };
    }

    private void setModeEta(int m, double value) {
        if (destroyed) return;
        modeEta[m] = value > 0 ? value : -1;
        updateModeTabs();
    }

    private void requestRoutes() {
        cancelSessions();
        drivingRoutes.clear();
        transportRoutes.clear();
        selected = 0;
        if (routesLayer != null) routesLayer.clear();
        tapListeners.clear();
        setStartEnabled(false);
        if (origin == null) {
            showState(true, LocaleController.getString(R.string.UMessageRouteNoLocation));
            return;
        }
        showState(true, LocaleController.getString(R.string.UMessageRouteSearching));
        ArrayList<RequestPoint> pts = points(origin, destination);
        try {
            if (mode == MODE_CAR) {
                if (drivingRouter == null) drivingRouter = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED);
                DrivingOptions options = new DrivingOptions().setRoutesCount(3).setAnnotationLanguage(UMessageRouteUi.annotationLanguage());
                drivingSession = drivingRouter.requestRoutes(pts, options, new VehicleOptions(), drivingListener);
            } else if (mode == MODE_TRANSIT) {
                transportSession = TransportFactory.getInstance().createMasstransitRouter().requestRoutes(pts, new TransitOptions(0, new TimeOptions()), routeOptions(), transportListener);
            } else if (mode == MODE_WALK) {
                transportSession = TransportFactory.getInstance().createPedestrianRouter().requestRoutes(pts, new TimeOptions(), routeOptions(), transportListener);
            } else {
                transportSession = TransportFactory.getInstance().createBicycleRouterV2().requestRoutes(pts, new TimeOptions(), routeOptions(), transportListener);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            onRouteError();
        }
    }

    private void onRouteError() {
        if (destroyed) return;
        showState(false, LocaleController.getString(R.string.UMessageRouteError));
    }

    private void onRoutesReady() {
        if (routeCount() == 0) {
            onRouteError();
            return;
        }
        stateRow.setVisibility(View.GONE);
        drawRoutes();
        buildCards();
        fitRoute();
        setStartEnabled(true);
        if (pendingStart) {
            pendingStart = false;
            onStartClick();
        }
    }

    private int routeCount() {
        return mode == MODE_CAR ? drivingRoutes.size() : transportRoutes.size();
    }

    private Polyline routeGeometry(int i) {
        return mode == MODE_CAR ? drivingRoutes.get(i).getGeometry() : transportRoutes.get(i).getGeometry();
    }

    private double routeSeconds(int i) {
        if (mode == MODE_CAR) return drivingRoutes.get(i).getMetadata().getWeight().getTimeWithTraffic().getValue();
        return transportRoutes.get(i).getMetadata().getWeight().getTime().getValue();
    }

    private String routeDistance(int i) {
        if (mode == MODE_CAR) return drivingRoutes.get(i).getMetadata().getWeight().getDistance().getText();
        Polyline g = routeGeometry(i);
        int n = g.getPoints().size();
        double d = PolylineUtils.distanceBetweenPolylinePositions(g, new PolylinePosition(0, 0), new PolylinePosition(Math.max(0, n - 2), 1.0));
        return UMessageRouteUi.formatDistance(d);
    }

    private void drawRoutes() {
        routesLayer.clear();
        tapListeners.clear();
        int count = routeCount();
        double selSeconds = routeSeconds(selected);
        for (int i = 0; i < count; i++) {
            if (i == selected) continue;
            PolylineMapObject alt = routesLayer.addPolyline(routeGeometry(i));
            alt.setStrokeColor(UMessageRouteUi.ALT_ROUTE);
            alt.setStrokeWidth(5.5f);
            alt.setOutlineWidth(0f);
            alt.setZIndex(1f);
            alt.setTurnRadius(12f);
            final int index = i;
            MapObjectTapListener tap = (MapObject o, Point p) -> {
                selectRoute(index);
                return true;
            };
            tapListeners.add(tap);
            alt.addTapListener(new WeakReference<>(tap));
            int delta = (int) Math.round((routeSeconds(i) - selSeconds) / 60.0);
            String label = (delta >= 0 ? "+" : "−") + LocaleController.formatString(R.string.UMessageRouteMinutes, Math.abs(delta));
            PlacemarkMapObject bubble = routesLayer.addPlacemark(pointAt(routeGeometry(i), 0.5),
                    ImageProvider.fromBitmap(UMessageRouteUi.bubble(label, null, pal.dark ? 0xff2c2d31 : 0xffffffff, delta > 0 ? 0xffff5a5f : UMessageRouteUi.GREEN, 0)),
                    new IconStyle().setAnchor(new PointF(0f, 0f)).setZIndex(20f));
            bubble.addTapListener(new WeakReference<>(tap));
        }

        PolylineMapObject line = routesLayer.addPolyline(routeGeometry(selected));
        line.setStrokeWidth(7f);
        line.setOutlineColor(pal.dark ? 0xff16181c : 0xffffffff);
        line.setOutlineWidth(1.5f);
        line.setZIndex(10f);
        line.setTurnRadius(12f);
        List<JamSegment> jams = mode == MODE_CAR ? drivingRoutes.get(selected).getJamSegments() : null;
        if (jams != null && !jams.isEmpty()) {
            for (int c = 0; c < UMessageRouteUi.JAM_COLORS.length; c++) line.setPaletteColor(c, UMessageRouteUi.JAM_COLORS[c]);
            ArrayList<Integer> colors = new ArrayList<>(jams.size());
            for (JamSegment s : jams) colors.add(UMessageRouteUi.jamIndex(s));
            line.setStrokeColors(colors);
        } else {
            line.setStrokeColor(UMessageRouteUi.BLUE);
            if (mode == MODE_WALK) {
                line.setDashLength(6f);
                line.setGapLength(4f);
            }
        }
        routesLayer.addPlacemark(pointAt(routeGeometry(selected), 0.12),
                ImageProvider.fromBitmap(UMessageRouteUi.bubble(UMessageRouteUi.formatTime(selSeconds), routeDistance(selected), UMessageRouteUi.BLUE, 0xffffffff, 0xd9ffffff)),
                new IconStyle().setAnchor(new PointF(0f, 0f)).setZIndex(30f));

        if (mode == MODE_CAR) {
            List<Event> events = drivingRoutes.get(selected).getEvents();
            if (events != null) {
                for (Event e : events) {
                    int kind = UMessageRouteUi.eventKind(e.getTags());
                    if (kind < 0 || e.getLocation() == null) continue;
                    routesLayer.addPlacemark(e.getLocation(), ImageProvider.fromBitmap(UMessageRouteUi.eventIcon(kind)), new IconStyle().setZIndex(15f));
                }
            }
        }
    }

    private void selectRoute(int index) {
        if (index == selected || index >= routeCount()) return;
        selected = index;
        drawRoutes();
        buildCards();
    }

    private void buildCards() {
        cards.removeAllViews();
        Context context = cards.getContext();
        for (int i = 0; i < routeCount(); i++) {
            final int index = i;
            boolean sel = i == selected;
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), 0, dp(18), 0);
            card.setBackground(sel ? rounded(pal.pill, dp(18)) : null);

            TextView time = text(context, 18, sel ? pal.text : pal.secondary, true);
            time.setText(UMessageRouteUi.formatTime(routeSeconds(i)));
            card.addView(time);

            CharSequence line2;
            if (mode == MODE_TRANSIT) {
                int transfers = transportRoutes.get(i).getMetadata().getWeight().getTransfersCount();
                line2 = transfers > 0 ? LocaleController.formatString(R.string.UMessageRouteTransfers, transfers) : LocaleController.getString(R.string.UMessageRouteNoTransfers);
            } else if (mode == MODE_CAR) {
                com.yandex.mapkit.directions.driving.Weight w = drivingRoutes.get(i).getMetadata().getWeight();
                double extra = w.getTimeWithTraffic().getValue() - w.getTime().getValue();
                if (extra > 180) {
                    SpannableStringBuilder sb = new SpannableStringBuilder(routeDistance(i));
                    String delay = "  " + LocaleController.formatString(R.string.UMessageRouteTrafficDelay, UMessageRouteUi.formatTime(extra));
                    int s = sb.length();
                    sb.append(delay);
                    sb.setSpan(new ForegroundColorSpan(0xffff8a1a), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    line2 = sb;
                } else {
                    line2 = routeDistance(i);
                }
            } else {
                line2 = routeDistance(i);
            }
            TextView d = text(context, 15, sel ? pal.text : pal.secondary, false);
            d.setAlpha(sel ? 0.85f : 1f);
            d.setText(line2);
            card.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            card.setOnClickListener(v -> selectRoute(index));
            UMessageRouteUi.pressScale(card);
            cards.addView(card, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, 0, 0, 6, 0));
        }
    }

    private void fitRoute() {
        if (mapView == null || routeCount() == 0) return;
        mapView.post(() -> {
            if (destroyed || mapView == null || selected >= routeCount()) return;
            try {
                float w = mapView.getWidth(), h = mapView.getHeight();
                float topPad = AndroidUtilities.statusBarHeight + dp(40);
                float bottom = h - sheet.getHeight() - dp(40);
                if (w <= 0 || bottom <= topPad) return;
                ScreenRect rect = new ScreenRect(new ScreenPoint(dp(40), topPad), new ScreenPoint(w - dp(96), bottom));
                ArrayList<Point> all = new ArrayList<>();
                for (int i = 0; i < routeCount(); i++) all.addAll(routeGeometry(i).getPoints());
                CameraPosition pos = map.cameraPosition(Geometry.fromPolyline(new Polyline(all)), rect);
                map.move(pos, new Animation(Animation.Type.SMOOTH, 0.5f), null);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private void zoom(int delta) {
        CameraPosition c = map.getCameraPosition();
        map.move(new CameraPosition(c.getTarget(), c.getZoom() + delta, c.getAzimuth(), c.getTilt()), new Animation(Animation.Type.SMOOTH, 0.25f), null);
    }

    private void focusMe() {
        Point p = origin;
        if (userLocationLayer != null && userLocationLayer.cameraPosition() != null) {
            p = userLocationLayer.cameraPosition().getTarget();
        }
        if (p != null) map.move(new CameraPosition(p, 16f, 0f, 0f), new Animation(Animation.Type.SMOOTH, 0.4f), null);
    }

    private void cycleMapType() {
        MapType t = map.getMapType();
        map.setMapType(t == MapType.SATELLITE ? MapType.HYBRID : t == MapType.HYBRID ? MapType.VECTOR_MAP : MapType.SATELLITE);
    }

    private void onStartClick() {
        if (routeCount() == 0 || getParentActivity() == null) return;
        if (mode == MODE_CAR && UMessageRouteUi.isNavigatorInstalled(getParentActivity())) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            b.setTitle(LocaleController.getString(R.string.UMessageRouteNaviAskTitle));
            b.setMessage(LocaleController.getString(R.string.UMessageRouteNaviAskText));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageRouteNaviOpen), (d, w) -> openNavigator());
            b.setNegativeButton(LocaleController.getString(R.string.UMessageRouteNaviStay), (d, w) -> startInApp());
            showDialog(b.create());
        } else {
            startInApp();
        }
    }

    private void openNavigator() {
        String uri = String.format(Locale.US, "yandexnavi://build_route_on_map?lat_to=%f&lon_to=%f", destination.getLatitude(), destination.getLongitude());
        if (origin != null) uri += String.format(Locale.US, "&lat_from=%f&lon_from=%f", origin.getLatitude(), origin.getLongitude());
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setPackage("ru.yandex.yandexnavi");
            getParentActivity().startActivity(intent);
        } catch (Exception e) {
            startInApp();
        }
    }

    private void startInApp() {
        if (routeCount() == 0) return;
        if (mode == MODE_CAR) {
            presentFragment(new UMessageNavigationActivity(destination, destinationTitle, drivingRoutes.get(selected)));
        } else {
            presentFragment(new UMessageNavigationActivity(destination, destinationTitle, mode, transportRoutes.get(selected)));
        }
    }

    private void cancelSessions() {
        if (drivingSession != null) {
            drivingSession.cancel();
            drivingSession = null;
        }
        if (transportSession != null) {
            transportSession.cancel();
            transportSession = null;
        }
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
        try {
            if (mapView != null) mapView.onStop();
            MapKitFactory.getInstance().onStop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        cancelSessions();
        for (Object s : summarySessions) {
            try {
                if (s instanceof DrivingSummarySession) ((DrivingSummarySession) s).cancel();
                else if (s instanceof SummarySession) ((SummarySession) s).cancel();
            } catch (Throwable ignore) {
            }
        }
        summarySessions.clear();
        if (locationManager != null) {
            try {
                locationManager.unsubscribe(new WeakReference<>(locationListener));
            } catch (Throwable ignore) {
            }
        }
        mapView = null;
        super.onFragmentDestroy();
    }

    static Bitmap pinBitmap(int color) {
        int w = dp(34), h = dp(44);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x40000000);
        c.drawOval(w / 2f - dp(6), h - dp(5), w / 2f + dp(6), h - dp(1), p);
        android.graphics.Path path = new android.graphics.Path();
        float r = w / 2f - dp(2);
        path.addCircle(w / 2f, r + dp(2), r, android.graphics.Path.Direction.CW);
        path.moveTo(w / 2f - r * 0.55f, r + dp(2) + r * 0.8f);
        path.lineTo(w / 2f, h - dp(3));
        path.lineTo(w / 2f + r * 0.55f, r + dp(2) + r * 0.8f);
        path.close();
        p.setColor(0xffffffff);
        c.drawPath(path, p);
        p.setColor(color);
        c.drawCircle(w / 2f, r + dp(2), r - dp(3), p);
        p.setColor(0xffffffff);
        c.drawCircle(w / 2f, r + dp(2), dp(4.5f), p);
        return b;
    }
}
