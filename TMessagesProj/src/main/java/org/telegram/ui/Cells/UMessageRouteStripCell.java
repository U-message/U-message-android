package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.location.Location;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.RequestPoint;
import com.yandex.mapkit.RequestPointType;
import com.yandex.mapkit.directions.DirectionsFactory;
import com.yandex.mapkit.directions.driving.DrivingOptions;
import com.yandex.mapkit.directions.driving.DrivingRouterType;
import com.yandex.mapkit.directions.driving.DrivingSummarySession;
import com.yandex.mapkit.directions.driving.VehicleOptions;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.location.LocationListener;
import com.yandex.mapkit.location.LocationManager;
import com.yandex.mapkit.location.LocationStatus;
import com.yandex.mapkit.transport.TransportFactory;
import com.yandex.mapkit.transport.masstransit.FitnessOptions;
import com.yandex.mapkit.transport.masstransit.RouteOptions;
import com.yandex.mapkit.transport.masstransit.SummarySession;
import com.yandex.mapkit.transport.masstransit.TimeOptions;
import com.yandex.mapkit.transport.masstransit.TransitOptions;
import com.yandex.runtime.Error;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageRouteUi;
import org.telegram.ui.UMessageRouteActivity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * "Route" block on the location screen: distance, a Start button and one tile per travel mode with
 * a live ETA from Yandex MapKit; the fastest mode is marked.
 */
public class UMessageRouteStripCell extends LinearLayout {

    public interface Delegate {
        void onRoute(int mode, boolean start);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView subtitle;
    private final LinearLayout[] tiles = new LinearLayout[4];
    private final double[] seconds = new double[4];
    private final ValueAnimator pulse;
    private final boolean dark;
    private final int cText, cSecondary, cFill;

    private Delegate delegate;
    private Point destination;
    private Point origin;
    private LocationManager locationManager;
    private DrivingSummarySession drivingSession;
    private final ArrayList<SummarySession> sessions = new ArrayList<>();
    private boolean requested;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationUpdated(com.yandex.mapkit.location.Location location) {
            if (origin == null && location != null) {
                origin = location.getPosition();
                updateSubtitle();
                request();
            }
        }

        @Override
        public void onLocationStatusUpdated(LocationStatus status) {
        }
    };

    public UMessageRouteStripCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        dark = AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.721f;
        cText = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        cSecondary = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        cFill = dark ? 0x14ffffff : 0xfff2f3f5;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 12));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        TextView title = UMessageRouteUi.text(context, 18, cText, true);
        title.setText(LocaleController.getString(R.string.UMessageRouteTitle));
        titles.addView(title);
        subtitle = UMessageRouteUi.text(context, 13, cSecondary, false);
        titles.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        TextView start = new TextView(context);
        start.setText(LocaleController.getString(R.string.UMessageRouteStart));
        start.setTextSize(15);
        start.setTypeface(AndroidUtilities.bold());
        start.setTextColor(0xffffffff);
        start.setGravity(Gravity.CENTER);
        start.setPadding(dp(20), 0, dp(20), 0);
        start.setBackground(UMessageRouteUi.rounded(UMessageRouteUi.ACCENT, dp(22)));
        start.setOnClickListener(v -> {
            if (delegate != null) delegate.onRoute(UMessageRouteUi.MODE_CAR, true);
        });
        UMessageRouteUi.pressScale(start);
        header.addView(start, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76));
        for (int i = 0; i < UMessageRouteUi.ORDER.length; i++) {
            int m = UMessageRouteUi.ORDER[i];
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(VERTICAL);
            tile.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(context);
            icon.setImageResource(UMessageRouteUi.icon(m));
            icon.setColorFilter(cSecondary);
            tile.addView(icon, LayoutHelper.createLinear(24, 24));
            TextView eta = UMessageRouteUi.text(context, 15, cText, true);
            eta.setGravity(Gravity.CENTER);
            eta.setText("···");
            tile.addView(eta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 5, 4, 0));
            tile.setOnClickListener(v -> {
                if (delegate != null) delegate.onRoute(m, false);
            });
            UMessageRouteUi.pressScale(tile);
            row.addView(tile, LayoutHelper.createLinear(0, 76, 1f, i == 0 ? 0 : 4, 0, i == UMessageRouteUi.ORDER.length - 1 ? 0 : 4, 0));
            tiles[m] = tile;
            styleTile(m, false);
        }

        pulse = ValueAnimator.ofFloat(0.3f, 1f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            for (int m = 0; m < tiles.length; m++) {
                if (seconds[m] == 0) tiles[m].getChildAt(1).setAlpha(v);
            }
        });
    }

    private void styleTile(int m, boolean fastest) {
        LinearLayout tile = tiles[m];
        tile.setBackground(fastest ? UMessageRouteUi.stroked(dark ? 0x2918b663 : 0x1418b663, UMessageRouteUi.GREEN, dp(1.5f), dp(18))
                : UMessageRouteUi.rounded(cFill, dp(18)));
        ((ImageView) tile.getChildAt(0)).setColorFilter(fastest ? UMessageRouteUi.GREEN : cSecondary);
        ((TextView) tile.getChildAt(1)).setTextColor(fastest ? UMessageRouteUi.GREEN : cText);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setPoints(double lat, double lon, Location myLocation) {
        boolean destChanged = destination == null || destination.getLatitude() != lat || destination.getLongitude() != lon;
        destination = new Point(lat, lon);
        if (origin == null && myLocation != null) {
            origin = new Point(myLocation.getLatitude(), myLocation.getLongitude());
            requested = false;
        }
        if (destChanged) requested = false;
        updateSubtitle();
        request();
    }

    private void updateSubtitle() {
        if (origin == null || destination == null) {
            subtitle.setText(LocaleController.getString(R.string.UMessageRouteNoLocation));
            return;
        }
        float[] d = new float[1];
        Location.distanceBetween(origin.getLatitude(), origin.getLongitude(), destination.getLatitude(), destination.getLongitude(), d);
        subtitle.setText(LocaleController.formatDistance(d[0], 1));
    }

    private void request() {
        if (destination == null || requested || !isAttachedToWindow()) return;
        try {
            UMessageRouteActivity.ensureInit(getContext());
            if (origin == null) {
                if (locationManager == null) {
                    locationManager = MapKitFactory.getInstance().createLocationManager();
                    locationManager.requestSingleUpdate(new WeakReference<>(locationListener));
                }
                return;
            }
            requested = true;
            cancel();
            for (int i = 0; i < seconds.length; i++) seconds[i] = 0;
            ArrayList<RequestPoint> points = new ArrayList<>();
            points.add(new RequestPoint(origin, RequestPointType.WAYPOINT, null, null, null));
            points.add(new RequestPoint(destination, RequestPointType.WAYPOINT, null, null, null));
            RouteOptions routeOptions = new RouteOptions(new FitnessOptions(false, false));
            drivingSession = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
                    .requestRoutesSummary(points, new DrivingOptions().setRoutesCount(1), new VehicleOptions(), new DrivingSummarySession.DrivingSummaryListener() {
                        @Override
                        public void onDrivingSummaries(List<com.yandex.mapkit.directions.driving.Summary> list) {
                            setEta(UMessageRouteUi.MODE_CAR, list.isEmpty() ? -1 : list.get(0).getWeight().getTimeWithTraffic().getValue());
                        }

                        @Override
                        public void onDrivingSummariesError(Error error) {
                            setEta(UMessageRouteUi.MODE_CAR, -1);
                        }
                    });
            sessions.add(TransportFactory.getInstance().createMasstransitRouter()
                    .requestRoutesSummary(points, new TransitOptions(0, new TimeOptions()), routeOptions, transportListener(UMessageRouteUi.MODE_TRANSIT)));
            sessions.add(TransportFactory.getInstance().createPedestrianRouter()
                    .requestRoutesSummary(points, new TimeOptions(), routeOptions, transportListener(UMessageRouteUi.MODE_WALK)));
            sessions.add(TransportFactory.getInstance().createBicycleRouterV2()
                    .requestRoutesSummary(points, new TimeOptions(), routeOptions, transportListener(UMessageRouteUi.MODE_BIKE)));
            if (!pulse.isStarted()) pulse.start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private SummarySession.SummaryListener transportListener(int m) {
        return new SummarySession.SummaryListener() {
            @Override
            public void onMasstransitSummaries(List<com.yandex.mapkit.transport.masstransit.Summary> list) {
                setEta(m, list.isEmpty() ? -1 : list.get(0).getWeight().getTime().getValue());
            }

            @Override
            public void onMasstransitSummariesError(Error error) {
                setEta(m, -1);
            }
        };
    }

    private void setEta(int m, double value) {
        seconds[m] = value > 0 ? value : -1;
        TextView eta = (TextView) tiles[m].getChildAt(1);
        eta.setText(value > 0 ? UMessageRouteUi.formatTime(value) : "—");
        eta.animate().alpha(1f).setDuration(200).start();
        int best = -1;
        boolean pending = false;
        for (int i = 0; i < seconds.length; i++) {
            if (seconds[i] == 0) pending = true;
            if (seconds[i] > 0 && (best < 0 || seconds[i] < seconds[best])) best = i;
        }
        if (!pending) {
            pulse.cancel();
            for (int i = 0; i < tiles.length; i++) styleTile(i, i == best);
        }
    }

    private void cancel() {
        if (drivingSession != null) {
            drivingSession.cancel();
            drivingSession = null;
        }
        for (SummarySession s : sessions) s.cancel();
        sessions.clear();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        request();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pulse.cancel();
        boolean incomplete = false;
        for (double s : seconds) if (s == 0) incomplete = true;
        if (incomplete) {
            cancel();
            requested = false;
        }
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
