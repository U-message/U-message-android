package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mapbox.bindgen.Value;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.CameraState;
import com.mapbox.maps.EdgeInsets;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapboxMap;
import com.mapbox.maps.ScreenCoordinate;
import com.mapbox.maps.Style;
import com.mapbox.maps.plugin.animation.CameraAnimationsUtils;
import com.mapbox.maps.plugin.animation.MapAnimationOptions;
import com.mapbox.maps.plugin.attribution.AttributionUtils;
import com.mapbox.maps.plugin.compass.CompassUtils;
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin;
import com.mapbox.maps.plugin.locationcomponent.LocationComponentUtils;
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener;
import com.mapbox.maps.plugin.logo.LogoUtils;
import com.mapbox.maps.plugin.scalebar.ScaleBarUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UMessageFriendLocations;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageFriendMapOverlay;

import java.util.ArrayList;

/**
 * Friend Map: a globe with every accepted friend at their current position. Friendship is a
 * request/accept handshake ({@link UMessageFriendLocations}); positions are relayed end-to-end
 * encrypted and refreshed every few seconds while this screen is open.
 */
public class UMessageFriendMapActivity extends BaseFragment implements UMessageFriendLocations.Listener {

    private static final int PERMISSION_CODE = 2;
    private static final String HI = "👋";

    private UMessageFriendLocations controller;
    private final LongSparseArray<ImageReceiver> avatars = new LongSparseArray<>();

    private MapView mapView;
    private MapboxMap mapboxMap;
    private UMessageFriendMapOverlay overlay;
    private boolean styleLoaded;
    private boolean introDone;
    private Location myLocation;
    private final OnIndicatorPositionChangedListener positionListener = this::onMyPosition;

    private boolean dark;
    private int cBg, cText, cSecondary, cSeparator, cButton, cAccent;

    private long focusUserId;
    private long selectedUid;
    private FrameLayout root;
    private SheetLayout sheet;
    private LinearLayout listLayout;
    private TextView countView;
    private ImageView characterButton;
    private FrameLayout selectedCard;
    private boolean permissionAsked;
    private Runnable pendingAction;
    private final java.util.HashSet<Long> nearbyShown = new java.util.HashSet<>();
    private boolean destroyed;
    private final Runnable focusSelect = () -> select(focusUserId);
    // the list is rebuilt only when its rows change; otherwise these texts are refreshed in place
    private String listSignature;
    private final java.util.HashMap<Long, TextView[]> rowTexts = new java.util.HashMap<>();
    private TextView selectedSub;
    private String selectedSignature;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            controller.watchFriends(); // friends' phones switch to precise live GPS while we look
            controller.refreshLocations();
            controller.updateNearby(myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation());
            updateSelectedStatus();
            AndroidUtilities.runOnUIThread(this, 3000);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            rebuild();
            AndroidUtilities.runOnUIThread(this, 20_000);
        }
    };

    public static UMessageFriendMapActivity forUser(long userId) {
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        return new UMessageFriendMapActivity(args);
    }

    public UMessageFriendMapActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        focusUserId = arguments != null ? arguments.getLong("user_id", 0) : 0;
        controller = UMessageFriendLocations.getInstance(currentAccount);
        controller.addListener(this);
        controller.scan(true);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(focusSelect);
        AndroidUtilities.cancelRunOnUIThread(zoneFollow);
        if (overlay != null) {
            for (Long uid : nearbyShown) overlay.remove(uid);
        }
        nearbyShown.clear();
        controller.removeListener(this);
        AndroidUtilities.cancelRunOnUIThread(poller);
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.cancelRunOnUIThread(styleRetry);
        if (styleErrors != null) {
            styleErrors.cancel();
            styleErrors = null;
        }
        if (cameraWatch != null) {
            cameraWatch.cancel();
            cameraWatch = null;
        }
        for (int i = 0; i < avatars.size(); i++) {
            avatars.valueAt(i).onDetachedFromWindow();
        }
        if (mapView != null) {
            try {
                LocationComponentUtils.getLocationComponent(mapView).removeOnIndicatorPositionChangedListener(positionListener);
                mapView.onDestroy();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            mapView = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        UMessageFriendLocations.setMapOpen(true);
        UMessageFriendLocations.reportMapOpen(currentAccount);
        AndroidUtilities.cancelRunOnUIThread(poller);
        poller.run();
        enableMyLocation();
        if (zoneEditor != null) {
            AndroidUtilities.cancelRunOnUIThread(zoneFollow);
            zoneFollow.run();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        UMessageFriendLocations.setMapOpen(false);
        AndroidUtilities.cancelRunOnUIThread(poller);
        AndroidUtilities.cancelRunOnUIThread(zoneFollow); // restarted in onResume while the zone editor is open
    }

    @Override
    public boolean isLightStatusBar() {
        return !dark;
    }

    @Override
    public void onFriendsChanged() {
        rebuild();
    }

    @Override
    public View createView(Context context) {
        dark = AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.721f;
        cBg = getThemedColor(Theme.key_windowBackgroundWhite);
        cText = getThemedColor(Theme.key_chats_name);
        cSecondary = getThemedColor(Theme.key_chats_message);
        cSeparator = dark ? 0x40545458 : 0x2e3c3c43;
        cButton = dark ? 0xf02c2c2e : 0xf7ffffff;
        cAccent = dark ? 0xff0a84ff : 0xff007aff;

        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(0xff000000);
        fragmentView = root;

        createMap(context);

        int top = AndroidUtilities.statusBarHeight + dp(8);
        ImageView back = roundButton(context, R.drawable.ic_ab_back);
        back.setOnClickListener(v -> finishFragment());
        root.addView(back, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 16, 0, 0, 0));
        ((FrameLayout.LayoutParams) back.getLayoutParams()).topMargin = top;

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackground(rounded(cButton, dp(14)));
        controls.setElevation(dp(3));
        controls.addView(controlButton(context, R.drawable.msg_current_location, v -> focusMe()), LayoutHelper.createLinear(46, 46));
        View sep = new View(context);
        sep.setBackgroundColor(cSeparator);
        controls.addView(sep, LayoutHelper.createLinear(30, 1f / AndroidUtilities.density, Gravity.CENTER_HORIZONTAL));
        controls.addView(controlButton(context, R.drawable.msg_groups, v -> showGlobe(true)), LayoutHelper.createLinear(46, 46));
        View sep2 = new View(context);
        sep2.setBackgroundColor(cSeparator);
        controls.addView(sep2, LayoutHelper.createLinear(30, 1f / AndroidUtilities.density, Gravity.CENTER_HORIZONTAL));
        controls.addView(controlButton(context, R.drawable.msg_map_type, v -> showMapTypePicker()), LayoutHelper.createLinear(46, 46));
        root.addView(controls, LayoutHelper.createFrame(46, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 0, 16, 0));
        ((FrameLayout.LayoutParams) controls.getLayoutParams()).topMargin = top;

        selectedCard = new FrameLayout(context);
        selectedCard.setVisibility(View.GONE);
        root.addView(selectedCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 0, 12, 0));

        sheet = new SheetLayout(context);
        root.addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.BOTTOM));
        ((FrameLayout.LayoutParams) sheet.getLayoutParams()).topMargin = AndroidUtilities.statusBarHeight + dp(64);

        rebuild();
        AndroidUtilities.runOnUIThread(ticker, 20_000);
        return fragmentView;
    }

    // ---------------------------------------------------------------- map

    private void createMap(Context context) {
        mapView = new MapView(context);
        root.addView(mapView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        mapboxMap = mapView.getMapboxMap();

        overlay = new UMessageFriendMapOverlay(context);
        overlay.setSpeedFormat(LocaleController.getString(R.string.UMessageFriendMapSpeed));
        overlay.setOnPinClick(uid -> {
            if (nearbyShown.contains(uid)) showNearbyActions(uid);
            else select(uid);
        });
        root.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        try {
            com.mapbox.maps.plugin.gestures.GesturesUtils.getGestures(mapView).addOnMoveListener(new com.mapbox.maps.plugin.gestures.OnMoveListener() {
                @Override
                public void onMoveBegin(@androidx.annotation.NonNull com.mapbox.android.gestures.MoveGestureDetector detector) {
                    userMovedAt = System.currentTimeMillis();
                }

                @Override
                public boolean onMove(@androidx.annotation.NonNull com.mapbox.android.gestures.MoveGestureDetector detector) {
                    userMovedAt = System.currentTimeMillis();
                    return false;
                }

                @Override
                public void onMoveEnd(@androidx.annotation.NonNull com.mapbox.android.gestures.MoveGestureDetector detector) {
                    userMovedAt = System.currentTimeMillis();
                }
            });
            ScaleBarUtils.getScaleBar(mapView).setEnabled(false);
            CompassUtils.getCompass(mapView).setMarginTop(AndroidUtilities.statusBarHeight + dp(118));
        } catch (Throwable e) {
            FileLog.e(e);
        }

        myLocation = UMessageFriendLocations.getLastLocation();
        Point center = globeCenter();
        mapboxMap.setCamera(new CameraOptions.Builder().center(center).zoom(0.6).pitch(0.0).build());
        loadMapStyle();
    }

    private static final int MAP_STANDARD = 0, MAP_SATELLITE = 1, MAP_HYBRID = 2;

    private int mapType() {
        return MessagesController.getGlobalMainSettings().getInt("umfriend_map_type", MAP_STANDARD);
    }

    /** Plain map with labels, satellite imagery, or satellite with roads and names on top. */
    private int styleRetries;
    private com.mapbox.common.Cancelable styleErrors;
    private com.mapbox.common.Cancelable cameraWatch; // redraws the overlay only when the camera moves
    private final Runnable styleRetry = this::loadMapStyle;

    private void loadMapStyle() {
        if (mapboxMap == null) return;
        if (styleErrors == null) {
            // no connection to Mapbox leaves a black screen: say so and keep retrying instead of waiting forever
            styleErrors = mapboxMap.subscribeMapLoadingError(error -> {
                if (error.getType() != com.mapbox.maps.MapLoadingErrorType.STYLE) return;
                FileLog.e("friend map style failed: " + error.getMessage());
                AndroidUtilities.runOnUIThread(() -> {
                    if (mapboxMap == null) return;
                    if (styleRetries++ == 0 && getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.UMessageFriendMapNoConnection), android.widget.Toast.LENGTH_LONG).show();
                    }
                    AndroidUtilities.cancelRunOnUIThread(styleRetry);
                    AndroidUtilities.runOnUIThread(styleRetry, Math.min(30_000, 3000L * styleRetries));
                });
            });
        }
        int type = mapType();
        String uri = type == MAP_SATELLITE ? Style.SATELLITE : type == MAP_HYBRID ? "mapbox://styles/mapbox/standard-satellite" : Style.STANDARD;
        mapboxMap.loadStyle(uri, style -> {
            try {
                if (type != MAP_SATELLITE) {
                    style.setStyleImportConfigProperty("basemap", "lightPreset", Value.valueOf(dark ? "night" : "day"));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            styleLoaded = true;
            styleRetries = 0;
            overlay.setProjector(this::toScreen);
            if (cameraWatch == null && mapboxMap != null) {
                cameraWatch = mapboxMap.subscribeCameraChanged(changed -> {
                    if (overlay != null) overlay.invalidateForCamera();
                });
            }
            enableMyLocation();
            intro();
        });
    }

    private void showMapTypePicker() {
        if (getParentActivity() == null) return;
        CharSequence[] items = {LocaleController.getString(R.string.Map), LocaleController.getString(R.string.Satellite), LocaleController.getString(R.string.Hybrid)};
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setItems(items, (dialog, which) -> {
            if (which == mapType()) return;
            MessagesController.getGlobalMainSettings().edit().putInt("umfriend_map_type", which).apply();
            loadMapStyle();
        });
        showDialog(builder.create());
    }

    /** Opening shot: the globe turns to friends, then dives to the profile's user if they are a friend. */
    private void intro() {
        if (introDone || !styleLoaded) return;
        introDone = true;
        CameraOptions globe = new CameraOptions.Builder().center(globeCenter()).zoom(1.7).pitch(0.0).bearing(0.0).build();
        CameraAnimationsUtils.easeTo(mapboxMap, globe, new MapAnimationOptions.Builder().duration(1400).build(), null);
        UMessageFriendLocations.Friend focused = controller.get(focusUserId);
        if (focused != null && focused.location != null) {
            AndroidUtilities.cancelRunOnUIThread(focusSelect);
            AndroidUtilities.runOnUIThread(focusSelect, 1600);
        }
    }

    private Point globeCenter() {
        double lat = 0, lng = 0;
        int n = 0;
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            if (f.location != null) {
                lat += f.location.lat;
                lng += f.location.lng;
                n++;
            }
        }
        if (myLocation != null) {
            lat += myLocation.getLatitude();
            lng += myLocation.getLongitude();
            n++;
        }
        return n == 0 ? Point.fromLngLat(64.5, 41.3) : Point.fromLngLat(lng / n, lat / n);
    }

    private PointF toScreen(double lat, double lng) {
        if (mapboxMap == null || !styleLoaded) return null;
        CameraState camera = mapboxMap.getCameraState();
        if (camera.getZoom() < 5) {
            // hide pins on the far side of the globe
            Point c = camera.getCenter();
            float[] d = new float[1];
            Location.distanceBetween(c.latitude(), c.longitude(), lat, lng, d);
            if (d[0] > 9_000_000) return null;
        }
        ScreenCoordinate s = mapboxMap.pixelForCoordinate(Point.fromLngLat(lng, lat));
        // Mapbox clamps off-screen points onto the edge (and behind the horizon when pitched): those are not real positions
        float w = mapView != null ? mapView.getWidth() : 0, h = mapView != null ? mapView.getHeight() : 0;
        if (s.getX() <= 0.5 || s.getY() <= 0.5 || w > 0 && s.getX() >= w - 0.5 || h > 0 && s.getY() >= h - 0.5) return null;
        return new PointF((float) s.getX(), (float) s.getY());
    }

    private EdgeInsets insets() {
        int bottom = sheet != null ? sheet.visibleHeight() : dp(260);
        int selected = selectedCard != null && selectedCard.getVisibility() == View.VISIBLE ? selectedCard.getHeight() + dp(12) : 0;
        return new EdgeInsets(AndroidUtilities.statusBarHeight + dp(80), dp(40), bottom + selected + dp(30), dp(40));
    }

    private void onSheetMoved() {
        int visible = sheet.visibleHeight();
        if (selectedCard.getVisibility() == View.VISIBLE) {
            selectedCard.setTranslationY(root.getHeight() - visible - selectedCard.getHeight() - dp(12));
        }
        if (mapView != null) {
            try {
                LogoUtils.getLogo(mapView).setMarginBottom(visible + dp(6));
                AttributionUtils.getAttribution(mapView).setMarginBottom(visible + dp(6));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private void onMyPosition(Point point) {
        boolean first = myLocation == null;
        Location location = new Location("mapbox");
        location.setLatitude(point.latitude());
        location.setLongitude(point.longitude());
        myLocation = location;
        updateMe();
        if (first) rebuild();
    }

    /** With a character chosen we draw ourselves like a friend (the Mapbox puck is hidden). */
    private void updateMe() {
        if (overlay == null) return;
        long me = getUserConfig().getClientUserId();
        if (myLocation == null) {
            overlay.remove(me);
            return;
        }
        // we look to ourselves exactly as friends see us: the walker or car with our idle style
        Location fix = UMessageFriendLocations.getLastLocation();
        float speed = fix != null && fix.hasSpeed() ? fix.getSpeed() * 3.6f : -1;
        overlay.update(me, getUserConfig().getCurrentUser(), avatar(me), myLocation.getLatitude(), myLocation.getLongitude(),
                (int) (System.currentTimeMillis() / 1000), speed, fix != null ? fix.getAccuracy() : 0);
        overlay.setIdle(me, UMessageFriendLocations.getMyIdle());
    }

    private void applyPuck() {
        if (mapView == null || !styleLoaded) return;
        try {
            LocationComponentPlugin lc = LocationComponentUtils.getLocationComponent(mapView);
            // the overlay draws us; Mapbox only tracks the position
            lc.setLocationPuck(new com.mapbox.maps.plugin.LocationPuck2D());
            lc.setPulsingEnabled(false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        updateMe();
    }

    private void requestPermission() {
        if (!permissionAsked && Build.VERSION.SDK_INT >= 23 && getParentActivity() != null) {
            permissionAsked = true;
            // Google Play prominent disclosure: what is collected and where it goes, before the system dialog
            withLocationDisclosure(() -> {
                if (getParentActivity() != null) {
                    getParentActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_CODE);
                }
            });
        }
    }

    private void withLocationDisclosure(Runnable onAccept) {
        UMessageFriendLocations.withDisclosure(getParentActivity(), false, onAccept, () -> permissionAsked = false);
    }

    private void enableMyLocation() {
        if (mapView == null || !styleLoaded) return;
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            requestPermission();
            return;
        }
        try {
            LocationComponentPlugin lc = LocationComponentUtils.getLocationComponent(mapView);
            lc.setPulsingColor(0xff0a84ff);
            applyPuck();
            lc.removeOnIndicatorPositionChangedListener(positionListener);
            lc.addOnIndicatorPositionChangedListener(positionListener);
            lc.setEnabled(true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_CODE && UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            UMessageFriendLocations.restartTracking();
            enableMyLocation();
            if (pendingAction != null) {
                Runnable r = pendingAction;
                pendingAction = null;
                r.run();
            }
        }
    }

    private void flyTo(double lat, double lng, double zoom) {
        if (destroyed || mapboxMap == null) return;
        CameraOptions camera = new CameraOptions.Builder()
                .center(Point.fromLngLat(lng, lat))
                .zoom(zoom)
                .pitch(50.0)
                .padding(insets())
                .build();
        CameraAnimationsUtils.flyTo(mapboxMap, camera, new MapAnimationOptions.Builder().duration(2200).build(), null);
    }

    private void showGlobe(boolean animated) {
        if (mapboxMap == null) return;
        select(0);
        CameraOptions camera = new CameraOptions.Builder().center(globeCenter()).zoom(1.7).pitch(0.0).bearing(0.0)
                .padding(new EdgeInsets(0, 0, 0, 0)).build();
        if (animated) {
            CameraAnimationsUtils.flyTo(mapboxMap, camera, new MapAnimationOptions.Builder().duration(1800).build(), null);
        } else {
            mapboxMap.setCamera(camera);
        }
    }

    private void focusMe() {
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            permissionAsked = false;
            requestPermission();
            return;
        }
        Location l = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        if (l != null) {
            select(0);
            flyTo(l.getLatitude(), l.getLongitude(), 15.5);
        }
    }

    private ImageReceiver avatar(long uid) {
        ImageReceiver r = avatars.get(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        if (r == null && user != null && !destroyed) {
            r = new ImageReceiver();
            r.setCurrentAccount(currentAccount);
            r.onAttachedToWindow();
            AvatarDrawable d = new AvatarDrawable();
            d.setInfo(currentAccount, user);
            r.setForUserOrChat(user, d);
            avatars.put(uid, r);
        }
        return r;
    }

    private void updateMarkers() {
        if (destroyed || overlay == null) return;
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            TLRPC.User user = getMessagesController().getUser(f.uid);
            if (f.state != UMessageFriendLocations.STATE_FRIEND || f.location == null || user == null) {
                overlay.remove(f.uid);
                continue;
            }
            // drawn where the friend's phone put it: decrypted positions never go to a map-matching service
            double[] at = {f.location.lat, f.location.lng};
            overlay.update(f.uid, user, avatar(f.uid), at[0], at[1], (int) (f.location.time / 1000), f.location.speedKmh, f.location.accuracy);
            overlay.setIdle(f.uid, f.location.idle);
            if (f.uid == selectedUid && f.location.speedKmh >= 2) followCamera(f.uid, at[0], at[1], f.location.speedKmh, 1500);
        }
        // people nearby who chose to be visible: plain pins at their rounded position
        java.util.HashSet<Long> nowShown = new java.util.HashSet<>();
        for (UMessageFriendLocations.NearbyPerson p : controller.getNearby()) {
            TLRPC.User user = getMessagesController().getUser(p.uid);
            if (user == null || isFriendPin(p.uid)) continue; // a friend's live pin wins over the rounded one
            nowShown.add(p.uid);
            overlay.update(p.uid, user, avatar(p.uid), p.lat, p.lng, (int) (p.time / 1000), -1, 0);
        }
        for (Long uid : nearbyShown) {
            if (!nowShown.contains(uid) && !isFriendPin(uid)) overlay.remove(uid);
        }
        nearbyShown.clear();
        nearbyShown.addAll(nowShown);
        ArrayList<double[]> zones = new ArrayList<>();
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            if (f.zone != null) zones.add(new double[]{f.zone.lat, f.zone.lng, f.zone.radius, f.zone.state == UMessageFriendLocations.ZONE_ACTIVE ? 1 : 0});
        }
        overlay.setZones(zones);
    }

    private boolean isFriendPin(long uid) {
        UMessageFriendLocations.Friend f = controller.get(uid);
        return f != null && f.state == UMessageFriendLocations.STATE_FRIEND && f.location != null;
    }

    // ---------------------------------------------------------------- zone editor

    private static final int[] RADII = {200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 20000};
    private FrameLayout zoneEditor;
    private long zoneUid;
    private int zoneRadius = 1000;
    private final Runnable zoneFollow = new Runnable() {
        @Override
        public void run() {
            if (zoneEditor == null || mapboxMap == null) return;
            Point c = mapboxMap.getCameraState().getCenter();
            overlay.setEditZone(new double[]{c.latitude(), c.longitude(), zoneRadius});
            AndroidUtilities.runOnUIThread(this, 32);
        }
    };

    private void onZoneButton(UMessageFriendLocations.Friend f) {
        if (getParentActivity() == null) return;
        TLRPC.User user = getMessagesController().getUser(f.uid);
        String name = UserObject.getFirstName(user);
        UMessageFriendLocations.Zone z = f.zone;
        if (z == null) {
            startZoneEditor(f);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.UMessageZoneEditorTitle));
        String radius = UMessageFriendLocations.formatRadius(z.radius);
        if (z.state == UMessageFriendLocations.ZONE_INCOMING) {
            b.setMessage(LocaleController.formatString(R.string.UMessageZonePendingIn, name, radius));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapAccept), (d, w) -> controller.acceptZone(f.uid));
            b.setNegativeButton(LocaleController.getString(R.string.UMessageFriendMapDecline), (d, w) -> controller.removeZone(f.uid));
        } else {
            b.setMessage(z.state == UMessageFriendLocations.ZONE_ACTIVE
                    ? LocaleController.formatString(R.string.UMessageZoneActiveInfo, radius)
                    : LocaleController.getString(R.string.UMessageZonePendingOut));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageZoneNew), (d, w) -> startZoneEditor(f));
            b.setNegativeButton(LocaleController.getString(R.string.UMessageZoneRemove), (d, w) -> controller.removeZone(f.uid));
        }
        b.setNeutralButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void startZoneEditor(UMessageFriendLocations.Friend f) {
        Context context = getParentActivity();
        if (context == null || mapboxMap == null) return;
        zoneUid = f.uid;
        select(0);
        sheet.setVisibility(View.GONE);
        if (f.location != null) flyTo(f.location.lat, f.location.lng, 13);

        zoneEditor = new FrameLayout(context);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16) + AndroidUtilities.navigationBarHeight);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cBg);
        bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        card.setBackground(bg);
        card.setElevation(dp(16));
        card.setClickable(true);
        TextView title = text(context, 20, cText, true);
        title.setText(LocaleController.getString(R.string.UMessageZoneEditorTitle) + " · " + UserObject.getFirstName(getMessagesController().getUser(f.uid)));
        card.addView(title);
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTextColor(cSecondary);
        hint.setText(LocaleController.getString(R.string.UMessageZoneEditorHint));
        card.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 12));
        TextView radiusLabel = text(context, 16, cText, true);
        radiusLabel.setText(LocaleController.formatString(R.string.UMessageZoneRadius, UMessageFriendLocations.formatRadius(zoneRadius)));
        card.addView(radiusLabel);
        android.widget.SeekBar seek = new android.widget.SeekBar(context);
        seek.setMax(RADII.length - 1);
        int start = 4;
        for (int i = 0; i < RADII.length; i++) if (RADII[i] == zoneRadius) start = i;
        seek.setProgress(start);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                zoneRadius = RADII[progress];
                radiusLabel.setText(LocaleController.formatString(R.string.UMessageZoneRadius, UMessageFriendLocations.formatRadius(zoneRadius)));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        card.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 6, 0, 10));
        LinearLayout buttons = new LinearLayout(context);
        TextView cancel = pill(context, LocaleController.getString(R.string.Cancel), dark ? 0xff2c2c2e : 0xfff2f2f7, cText, 16);
        cancel.setOnClickListener(v -> closeZoneEditor());
        buttons.addView(cancel, LayoutHelper.createLinear(0, 50, 1f, 0, 0, 10, 0));
        TextView send = pill(context, LocaleController.getString(R.string.UMessageZoneSend), 0, 0xffffffff, 16);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff64d2ff, 0xff0a84ff});
        g.setCornerRadius(dp(14));
        send.setBackground(g);
        send.setOnClickListener(v -> {
            Point c = mapboxMap.getCameraState().getCenter();
            controller.proposeZone(zoneUid, c.latitude(), c.longitude(), zoneRadius);
            TLRPC.User user = getMessagesController().getUser(zoneUid);
            closeZoneEditor();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageZoneSent, UserObject.getFirstName(user))).show();
        });
        buttons.addView(send, LayoutHelper.createLinear(0, 50, 1.4f));
        card.addView(buttons);
        zoneEditor.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(zoneEditor, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        zoneEditor.setClickable(false);
        zoneFollow.run();
    }

    private void closeZoneEditor() {
        AndroidUtilities.cancelRunOnUIThread(zoneFollow);
        if (zoneEditor != null) {
            root.removeView(zoneEditor);
            zoneEditor = null;
        }
        overlay.setEditZone(null);
        sheet.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- selection card

    // ---------------------------------------------------------------- navigator-style follow camera

    private long userMovedAt;
    private float followSpeed = -1;
    private final java.util.HashMap<Long, double[]> followLast = new java.util.HashMap<>();

    /**
     * Follows a moving friend like a car navigator: the map turns to their heading, tilts, keeps them in the
     * lower part of the screen with the road ahead visible, and zooms out as they speed up and back in as they
     * slow down (walking ~z17.4, 45 km/h ~z16.2, 90 km/h ~z14.9). A pan by the user pauses it for 8 s.
     */
    private void followCamera(long uid, double lat, double lng, float speedKmh, long durationMs) {
        if (mapboxMap == null || !styleLoaded || System.currentTimeMillis() - userMovedAt < 8000) return;
        double[] last = followLast.get(uid);
        Double bearing = null;
        if (last != null) {
            float[] d = new float[2];
            Location.distanceBetween(last[0], last[1], lat, lng, d);
            if (d[0] > 3) bearing = (double) d[1];
        }
        if (last == null || bearing != null) followLast.put(uid, new double[]{lat, lng});
        followSpeed = followSpeed < 0 ? speedKmh : followSpeed * 0.7f + speedKmh * 0.3f; // no zoom jumps on one odd fix
        double zoom = 17.5 - Math.max(0, Math.min(110, followSpeed)) / 110.0 * 3.2;
        EdgeInsets base = insets();
        int h = mapView != null ? mapView.getHeight() : 0;
        // push the friend down so more of the road ahead is on screen
        EdgeInsets padding = new EdgeInsets(base.getTop() + h * 0.28, base.getLeft(), base.getBottom(), base.getRight());
        CameraOptions.Builder camera = new CameraOptions.Builder().center(Point.fromLngLat(lng, lat)).zoom(zoom).pitch(55.0).padding(padding);
        if (bearing != null && speedKmh >= 2) camera.bearing(bearing);
        CameraAnimationsUtils.easeTo(mapboxMap, camera.build(), new MapAnimationOptions.Builder().duration(durationMs).build(), null);
    }

    private void select(long uid) {
        if (destroyed || selectedCard == null) return;
        selectedUid = uid;
        followSpeed = -1;
        UMessageFriendLocations.Friend f = uid != 0 ? controller.get(uid) : null;
        selectedCard.removeAllViews();
        if (f == null || f.location == null) {
            selectedCard.setVisibility(View.GONE);
            selectedUid = 0;
            return;
        }
        selectedCard.addView(createSelectedCard(selectedCard.getContext(), f));
        selectedCard.setVisibility(View.VISIBLE);
        selectedCard.setAlpha(0f);
        sheet.collapse();
        selectedCard.post(() -> {
            onSheetMoved();
            selectedCard.animate().alpha(1f).setDuration(200).start();
            flyTo(f.location.lat, f.location.lng, 16.5);
        });
    }

    private TextView selectedStatus;
    private long selectedStatusUid;

    /** "● Live now" while their position is fresh, otherwise when it was last updated. */
    private void updateSelectedStatus() {
        if (selectedStatus == null) return;
        UMessageFriendLocations.Friend f = controller.get(selectedStatusUid);
        if (f == null || f.location == null) return;
        long age = System.currentTimeMillis() - f.location.time;
        if (age < 90_000) {
            selectedStatus.setText("● " + LocaleController.getString(R.string.UMessageFriendLocationLive));
            selectedStatus.setTextColor(0xff30d158);
        } else {
            selectedStatus.setText(LocaleController.formatLocationUpdateDate(f.location.time / 1000));
            selectedStatus.setTextColor(0x99ebebf5);
        }
    }

    private View createSelectedCard(Context context, UMessageFriendLocations.Friend f) {
        TLRPC.User user = getMessagesController().getUser(f.uid);
        LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff1d2330, 0xff11141c});
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setElevation(dp(8));

        BackupImageView avatar = avatarView(context, user, 46);
        card.addView(avatar, LayoutHelper.createLinear(46, 46, 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, 17, 0xffffffff, true);
        name.setText(UserObject.getUserName(user));
        texts.addView(name);
        TextView sub = text(context, 13, 0xb3ebebf5, false);
        sub.setText(details(f, true));
        selectedSub = sub;
        selectedSignature = selectedSignature(f);
        texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        selectedStatus = text(context, 12, 0xffffffff, true);
        selectedStatusUid = f.uid;
        updateSelectedStatus();
        texts.addView(selectedStatus, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        String safety = f.getSafetyCode();
        if (safety != null) {
            // compared face to face (or on a call): equal codes mean nobody sat between the two key exchanges
            TextView code = text(context, 12, 0x99ebebf5, false);
            code.setText(LocaleController.formatString(R.string.UMessageFriendSafetyCode, safety));
            code.setOnClickListener(v -> {
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                b.setTitle(safety);
                b.setMessage(LocaleController.getString(R.string.UMessageFriendSafetyCodeInfo));
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                showDialog(b.create());
            });
            texts.addView(code, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView zone = pill(context, "🎯", f.zone != null && f.zone.state == UMessageFriendLocations.ZONE_ACTIVE ? 0x4030d158 : 0x1fffffff, 0xffffffff, 20);
        zone.setPadding(0, 0, 0, 0);
        zone.setOnClickListener(v -> onZoneButton(f));
        card.addView(zone, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        TextView hi = pill(context, HI, 0x1fffffff, 0xffffffff, 20);
        hi.setPadding(0, 0, 0, 0);
        hi.setOnClickListener(v -> sayHi(f.uid));
        card.addView(hi, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        ImageView chat = new ImageView(context);
        chat.setScaleType(ImageView.ScaleType.CENTER);
        chat.setImageResource(R.drawable.filled_profile_message_24);
        chat.setColorFilter(0xffffffff);
        chat.setBackground(rounded(0x1fffffff, dp(22)));
        chat.setOnClickListener(v -> openChat(f.uid));
        pressScale(chat);
        card.addView(chat, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setAlpha(0.6f);
        close.setOnClickListener(v -> select(0));
        card.addView(close, LayoutHelper.createLinear(36, 44, 2, 0, 0, 0));
        mirror(card);
        return card;
    }

    private static String selectedSignature(UMessageFriendLocations.Friend f) {
        return f.uid + ":" + System.identityHashCode(f) + ":" + (f.zone == null ? -1 : f.zone.state) + ":" + f.getSafetyCode();
    }

    // ---------------------------------------------------------------- sheet

    /** Bottom sheet that can be dragged between a peek and an almost full-height list. */
    private class SheetLayout extends LinearLayout {
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final ScrollView scroll;
        private float downY, startTranslation;
        private boolean dragging;
        private VelocityTracker velocity;
        private ValueAnimator animator;

        SheetLayout(Context context) {
            super(context);
            setOrientation(VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(cBg);
            bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
            setBackground(bg);
            setElevation(dp(16));
            setClickable(true);

            View grabber = new View(context);
            grabber.setBackground(rounded(dark ? 0x66ebebf5 : 0x4d3c3c43, dp(2.5f)));
            addView(grabber, LayoutHelper.createLinear(36, 5, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 8));

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(context, 22, cText, true);
            title.setText(LocaleController.getString(R.string.UMessageFriendMapFriends));
            header.addView(title);
            countView = text(context, 15, cSecondary, false);
            header.addView(countView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 3, 0, 0));
            characterButton = new ImageView(context);
            characterButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
            characterButton.setPadding(dp(4), dp(4), dp(4), dp(4));
            characterButton.setBackground(rounded(dark ? 0xff2c2c2e : 0xfff2f2f7, dp(18)));
            characterButton.setOnClickListener(v -> showCharacterPicker());
            pressScale(characterButton);
            updateCharacterButton();
            header.addView(characterButton, LayoutHelper.createLinear(36, 36, 0, 0, 10, 0));
            ImageView add = new ImageView(context);
            add.setScaleType(ImageView.ScaleType.CENTER);
            add.setImageResource(R.drawable.msg_add);
            add.setColorFilter(0xffffffff);
            GradientDrawable addBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff64d2ff, 0xff0a84ff});
            addBg.setShape(GradientDrawable.OVAL);
            add.setBackground(addBg);
            add.setOnClickListener(v -> openPicker());
            pressScale(add);
            header.addView(add, LayoutHelper.createLinear(36, 36));
            mirror(header);
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? 16 : 18, 0, LocaleController.isRTL ? 18 : 16, 8));

            scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            listLayout = new LinearLayout(context);
            listLayout.setOrientation(VERTICAL);
            listLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(12));
            scroll.addView(listLayout);
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }

        int peek() {
            return dp(268) + AndroidUtilities.navigationBarHeight;
        }

        int collapsedTranslation() {
            return Math.max(0, getHeight() - peek());
        }

        /** Pushed down to just the grabber and the title, leaving the map almost free. */
        int minimizedTranslation() {
            return Math.max(collapsedTranslation(), getHeight() - dp(74) - AndroidUtilities.navigationBarHeight);
        }

        int visibleHeight() {
            return getHeight() == 0 ? peek() : (int) (getHeight() - getTranslationY());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            boolean first = getHeight() > 0 && getTag() == null;
            super.onLayout(changed, l, t, r, b);
            if (first || (changed && getTag() == null)) {
                setTag(1);
                setTranslationY(collapsedTranslation());
                onSheetMoved();
            }
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            onSheetMoved();
        }

        void collapse() {
            animateTo(collapsedTranslation());
        }

        void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(getTranslationY(), target);
            animator.addUpdateListener(a -> setTranslationY((float) a.getAnimatedValue()));
            animator.setDuration(320);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    startTranslation = getTranslationY();
                    dragging = false;
                    if (animator != null) animator.cancel();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dy) > touchSlop) {
                        boolean expanded = getTranslationY() <= 1;
                        boolean listAtTop = !scroll.canScrollVertically(-1);
                        boolean inList = ev.getY() > scroll.getTop();
                        if (!expanded || !inList || (dy > 0 && listAtTop)) {
                            dragging = true;
                            downY = ev.getRawY();
                            startTranslation = getTranslationY();
                            return true;
                        }
                    }
                    break;
                }
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (velocity == null) velocity = VelocityTracker.obtain();
            velocity.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    startTranslation = getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float t = Math.max(0, Math.min(minimizedTranslation(), startTranslation + ev.getRawY() - downY));
                    setTranslationY(t);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                    // three stops: full list, peek, minimized; a fling moves one stop in its direction
                    float[] stops = {0, collapsedTranslation(), minimizedTranslation()};
                    float cur = getTranslationY();
                    float target;
                    if (Math.abs(vy) > dp(400)) {
                        target = vy < 0 ? (cur > stops[1] + 1 ? stops[1] : 0) : (startTranslation < stops[1] - 1 && cur < stops[1] ? stops[1] : stops[2]);
                    } else {
                        target = stops[0];
                        for (float st : stops) if (Math.abs(st - cur) < Math.abs(target - cur)) target = st;
                    }
                    animateTo(target);
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private void rebuild() {
        if (destroyed || listLayout == null) return;
        Context context = listLayout.getContext();
        ArrayList<UMessageFriendLocations.Friend> all = controller.getAll();
        ArrayList<UMessageFriendLocations.Friend> incoming = new ArrayList<>(), friendsList = new ArrayList<>(), outgoing = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (UMessageFriendLocations.Friend f : all) {
            if (getMessagesController().getUser(f.uid) == null) continue;
            (f.state == UMessageFriendLocations.STATE_INCOMING ? incoming : f.state == UMessageFriendLocations.STATE_FRIEND ? friendsList : outgoing).add(f);
            sig.append(f.uid).append(':').append(System.identityHashCode(f)).append(':').append(f.state).append(':').append(f.location != null).append(',');
        }
        ArrayList<UMessageFriendLocations.NearbyPerson> people = new ArrayList<>();
        for (UMessageFriendLocations.NearbyPerson p : controller.getNearby()) {
            if (getMessagesController().getUser(p.uid) != null) {
                people.add(p);
                sig.append('n').append(p.uid).append(':').append(p.distance).append(',');
            }
        }
        UMessageFriendLocations.Friend focused = controller.get(focusUserId);
        sig.append(focused == null ? -1 : focused.state).append(UMessageFriendLocations.isNearbyEnabled(currentAccount)).append(UMessageFriendLocations.isSharingPaused()).append(all.isEmpty());
        String signature = sig.toString();
        if (signature.equals(listSignature)) {
            // same rows: refresh distances and times in place instead of recreating the list (no flicker)
            for (UMessageFriendLocations.Friend f : friendsList) {
                TextView[] texts = rowTexts.get(f.uid);
                if (texts == null) continue;
                texts[0].setText(details(f, false));
                if (texts[1] != null && f.location != null) texts[1].setText(LocaleController.stringForMessageListDate(f.location.time / 1000));
            }
            updateMarkers();
            updateSelectedCard();
            if (!introDone) intro();
            return;
        }
        listSignature = signature;
        rowTexts.clear();
        listLayout.removeAllViews();
        countView.setText(friendsList.isEmpty() ? "" : String.valueOf(friendsList.size()));

        if (focusUserId != 0 && (focused == null || focused.state != UMessageFriendLocations.STATE_FRIEND)) {
            listLayout.addView(createInviteCard(context, focusUserId, focused), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 10));
        }
        listLayout.addView(createPauseRow(context));
        listLayout.addView(createNearbyRow(context));
        if (!incoming.isEmpty()) {
            listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapRequests));
            for (int i = 0; i < incoming.size(); i++) {
                listLayout.addView(createRow(context, incoming.get(i), i != incoming.size() - 1));
            }
        }
        if (!friendsList.isEmpty()) {
            if (!incoming.isEmpty()) listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapFriends));
            for (int i = 0; i < friendsList.size(); i++) {
                listLayout.addView(createRow(context, friendsList.get(i), i != friendsList.size() - 1));
            }
        }
        if (!outgoing.isEmpty()) {
            listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapSent));
            for (int i = 0; i < outgoing.size(); i++) {
                listLayout.addView(createRow(context, outgoing.get(i), i != outgoing.size() - 1));
            }
        }
        if (!people.isEmpty()) {
            listLayout.addView(sectionHeader(context, R.string.UMessageNearbyPeople));
            for (int i = 0; i < people.size(); i++) {
                listLayout.addView(createNearbyPersonRow(context, people.get(i), i != people.size() - 1));
            }
        } else if (UMessageFriendLocations.isNearbyEnabled(currentAccount)) {
            TextView none = text(context, 14, cSecondary, false);
            none.setText(LocaleController.getString(R.string.UMessageNearbyNone));
            none.setPadding(dp(18), dp(10), dp(18), dp(6));
            listLayout.addView(none);
        }
        if (all.isEmpty() && focusUserId == 0) {
            TextView empty = new TextView(context);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(cSecondary);
            empty.setGravity(Gravity.CENTER);
            empty.setText(LocaleController.getString(R.string.UMessageFriendMapEmpty));
            listLayout.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 36, 24, 36, 24));
        }
        updateMarkers();
        updateSelectedCard();
        if (!introDone) intro();
    }

    /** Keeps the selected friend's card current; it is recreated only when its buttons would change. */
    private void updateSelectedCard() {
        if (selectedUid == 0 || selectedCard == null) return;
        UMessageFriendLocations.Friend f = controller.get(selectedUid);
        if (f == null || f.location == null) {
            select(0);
        } else if (selectedCard.getChildCount() > 0) {
            if (!selectedSignature(f).equals(selectedSignature) || selectedSub == null) {
                selectedCard.removeAllViews();
                selectedCard.addView(createSelectedCard(selectedCard.getContext(), f));
            } else {
                selectedSub.setText(details(f, true));
                updateSelectedStatus();
            }
        }
    }

    /** "Visible to people nearby": a separate consent; others nearby who turned it on appear on the map. */
    private View createNearbyRow(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_groups);
        icon.setColorFilter(0xffffffff);
        GradientDrawable iconBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff5ee07a, 0xff30b04a});
        iconBg.setCornerRadius(dp(9));
        icon.setBackground(iconBg);
        row.addView(icon, frame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
        TextView title = text(context, 16, cText, false);
        title.setText(LocaleController.getString(R.string.UMessageNearbyVisible));
        row.addView(title, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 11, 70, 0));
        TextView sub = text(context, 13, cSecondary, false);
        sub.setText(LocaleController.getString(R.string.UMessageNearbyVisibleInfo));
        row.addView(sub, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 33, 70, 0));
        org.telegram.ui.Components.Switch toggle = new org.telegram.ui.Components.Switch(context);
        toggle.setChecked(UMessageFriendLocations.isNearbyEnabled(currentAccount), false);
        row.addView(toggle, frame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        row.setOnClickListener(v -> {
            if (UMessageFriendLocations.isNearbyEnabled(currentAccount)) {
                UMessageFriendLocations.setNearbyEnabled(currentAccount, false);
                toggle.setChecked(false, true);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.UMessageNearbyOff)).show();
                return;
            }
            if (getParentActivity() == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            b.setTitle(LocaleController.getString(R.string.UMessageNearbyConsentTitle));
            b.setMessage(LocaleController.getString(R.string.UMessageNearbyConsentInfo));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageNearbyConsentAllow), (d, w) -> {
                Runnable enable = () -> {
                    UMessageFriendLocations.setNearbyEnabled(currentAccount, true);
                    toggle.setChecked(true, true);
                    controller.updateNearby(myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation());
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.UMessageNearbyOn)).show();
                };
                if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
                    pendingAction = enable;
                    permissionAsked = false;
                    requestPermission();
                } else {
                    enable.run();
                }
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        });
        View line = new View(context);
        line.setBackgroundColor(cSeparator);
        row.addView(line, frame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 64, 0, 0, 0));
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return row;
    }

    private View createNearbyPersonRow(Context context, UMessageFriendLocations.NearbyPerson p, boolean divider) {
        TLRPC.User user = getMessagesController().getUser(p.uid);
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.addView(avatarView(context, user, 54), frame(54, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        TextView name = text(context, 16, cText, true);
        name.setText(UserObject.getUserName(user));
        row.addView(name, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 13, 16, 0));
        TextView sub = text(context, 15, cSecondary, false);
        sub.setText(LocaleController.formatString(R.string.UMessageFriendMapAway, LocaleController.formatDistance(p.distance, 2)));
        row.addView(sub, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 37, 16, 0));
        row.setOnClickListener(v -> showNearbyActions(p.uid));
        if (divider) {
            View line = new View(context);
            line.setBackgroundColor(cSeparator);
            row.addView(line, frame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 76, 0, 0, 0));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
        return row;
    }

    /** A nearby stranger: show them on the map, write to them, or ask to become friends. */
    private void showNearbyActions(long uid) {
        if (getParentActivity() == null) return;
        TLRPC.User user = getMessagesController().getUser(uid);
        if (user == null) return;
        for (UMessageFriendLocations.NearbyPerson p : controller.getNearby()) {
            if (p.uid == uid) {
                select(0);
                flyTo(p.lat, p.lng, 15.5);
                break;
            }
        }
        UMessageFriendLocations.Friend f = controller.get(uid);
        boolean canAdd = f == null;
        CharSequence[] items = canAdd
                ? new CharSequence[]{LocaleController.getString(R.string.SendMessage), LocaleController.getString(R.string.UMessageNearbyAddFriend)}
                : new CharSequence[]{LocaleController.getString(R.string.SendMessage)};
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(UserObject.getUserName(user));
        b.setItems(items, (d, which) -> {
            if (which == 0) openChat(uid);
            else sendRequest(uid);
        });
        showDialog(b.create());
    }

    /** Global pause: no position leaves the phone (friends, people nearby, live mode) until switched back. */
    private View createPauseRow(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.live_loc);
        icon.setColorFilter(0xffffffff);
        GradientDrawable iconBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xffff9f0a, 0xffff453a});
        iconBg.setCornerRadius(dp(9));
        icon.setBackground(iconBg);
        row.addView(icon, frame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
        TextView title = text(context, 16, cText, false);
        title.setText(LocaleController.getString(R.string.UMessageFriendMapPause));
        row.addView(title, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 11, 70, 0));
        TextView sub = text(context, 13, cSecondary, false);
        sub.setText(LocaleController.getString(R.string.UMessageFriendMapPauseInfo));
        row.addView(sub, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 33, 70, 0));
        org.telegram.ui.Components.Switch toggle = new org.telegram.ui.Components.Switch(context);
        toggle.setChecked(UMessageFriendLocations.isSharingPaused(), false);
        row.addView(toggle, frame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        row.setOnClickListener(v -> {
            boolean pause = !UMessageFriendLocations.isSharingPaused();
            UMessageFriendLocations.setSharingPaused(pause);
            toggle.setChecked(pause, true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(pause ? R.string.UMessageFriendMapPausedOn : R.string.UMessageFriendMapPausedOff)).show();
            rebuild();
        });
        View line = new View(context);
        line.setBackgroundColor(cSeparator);
        row.addView(line, frame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 64, 0, 0, 0));
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return row;
    }

    /** Background mode switch: keep sending our position while the app is closed. */
    private void updateCharacterButton() {
        if (characterButton == null) return;
        characterButton.clearColorFilter();
        characterButton.setImageDrawable(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rf = new android.graphics.RectF();

            @Override
            public void draw(android.graphics.Canvas canvas) {
                // a tiny top-down walker, like the figure on the map
                android.graphics.Rect b = getBounds();
                float u = b.width() / 34f, x = b.left, y = b.top;
                p.setColor(0xff4a5263);
                rf.set(x + 10 * u, y + 14 * u, x + 16 * u, y + 26 * u);
                canvas.drawRoundRect(rf, 3 * u, 3 * u, p);
                rf.set(x + 18 * u, y + 14 * u, x + 24 * u, y + 26 * u);
                canvas.drawRoundRect(rf, 3 * u, 3 * u, p);
                p.setColor(cAccent);
                rf.set(x + 7 * u, y + 9 * u, x + 27 * u, y + 23 * u);
                canvas.drawRoundRect(rf, 7 * u, 7 * u, p);
                p.setColor(0xffffffff);
                canvas.drawCircle(x + 17 * u, y + 10 * u, 9 * u, p);
                p.setColor(0xffe8a33d);
                canvas.drawCircle(x + 17 * u, y + 10 * u, 7 * u, p);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return dp(28); }
            @Override public int getIntrinsicHeight() { return dp(28); }
        });
    }

    private void showCharacterPicker() {
        Context context = getParentActivity();
        if (context == null) return;
        org.telegram.ui.ActionBar.BottomSheet bottomSheet = new org.telegram.ui.ActionBar.BottomSheet(context, false, getResourceProvider());
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(12));
        TextView title = text(context, 20, cText, true);
        title.setText(LocaleController.getString(R.string.UMessageFriendMapCharacterTitle));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(cSecondary);
        info.setText(LocaleController.getString(R.string.UMessageFriendMapCharacterInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 14));

        // every style as a living tile, like the old character grid: the figure plays it in place
        android.widget.GridLayout grid = new android.widget.GridLayout(context);
        int columns = 3;
        grid.setColumnCount(columns);
        int cell = (AndroidUtilities.displaySize.x - dp(32)) / columns;
        long me = getUserConfig().getClientUserId();
        ArrayList<GradientDrawable> backgrounds = new ArrayList<>();
        for (int i = 0; i < UMessageFriendLocations.IDLE_STYLES.length; i++) {
            String id = UMessageFriendLocations.IDLE_STYLES[i];
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(dark ? 0xff2c2c2e : 0xfff2f2f7);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(2.5f), id.equals(UMessageFriendLocations.getMyIdle()) ? cAccent : 0);
            tile.setBackground(bg);
            backgrounds.add(bg);

            UMessageFriendMapOverlay preview = new UMessageFriendMapOverlay(context);
            preview.setProjector((lat, lng) -> new android.graphics.PointF(preview.getWidth() / 2f, preview.getHeight() * 0.56f));
            preview.update(me, getUserConfig().getCurrentUser(), avatar(me), 0, 0, 0, 0, 0);
            preview.setIdle(me, id);
            preview.setOnPinClick(uid -> tile.performClick());
            tile.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
            TextView label = text(context, 13, cText, true);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText(LocaleController.getString(UMessageFriendLocations.IDLE_NAMES[i]));
            tile.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 0, 6, 10));

            tile.setOnClickListener(v -> {
                UMessageFriendLocations.setMyIdle(id);
                for (int k = 0; k < backgrounds.size(); k++) {
                    backgrounds.get(k).setStroke(dp(2.5f), UMessageFriendLocations.IDLE_STYLES[k].equals(id) ? cAccent : 0);
                }
                updateMe();
            });
            pressScale(tile);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = cell - dp(8);
            lp.height = cell - dp(8) + dp(20);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile, lp);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(grid);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        bottomSheet.setCustomView(content);
        showDialog(bottomSheet);
    }

    private View sectionHeader(Context context, int res) {
        TextView view = text(context, 13, cSecondary, true);
        view.setText(LocaleController.getString(res).toUpperCase());
        view.setPadding(dp(18), dp(14), dp(18), dp(6));
        return view;
    }

    /** Opened from a profile of someone who is not a friend yet. */
    private View createInviteCard(Context context, long uid, UMessageFriendLocations.Friend f) {
        TLRPC.User user = getMessagesController().getUser(uid);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff1d2330, 0xff11141c});
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(avatarView(context, user, 52), LayoutHelper.createLinear(52, 52, 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, 18, 0xffffffff, true);
        name.setText(UserObject.getUserName(user));
        texts.addView(name);
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(0xb3ebebf5);
        int hintRes = f == null ? R.string.UMessageFriendMapInviteHint : f.state == UMessageFriendLocations.STATE_INCOMING ? R.string.UMessageFriendMapRequestHint : R.string.UMessageFriendMapWaitingHint;
        hint.setText(LocaleController.formatString(hintRes, UserObject.getFirstName(user)));
        texts.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        top.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        mirror(top);
        card.addView(top);

        TextView button;
        if (f == null) {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapSendRequest), 0, 0xffffffff, 16);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff64d2ff, 0xff0a84ff});
            g.setCornerRadius(dp(14));
            button.setBackground(g);
            button.setOnClickListener(v -> sendRequest(uid));
        } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapAccept), 0, 0xffffffff, 16);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff30d158, 0xff34c759});
            g.setCornerRadius(dp(14));
            button.setBackground(g);
            button.setOnClickListener(v -> accept(uid));
        } else {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapCancelRequest), 0x1fffffff, 0xffffffff, 16);
            button.setOnClickListener(v -> controller.remove(uid));
        }
        card.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));
        return card;
    }

    /** Looks like a chat list row; the distance takes the place of the last message. */
    private View createRow(Context context, UMessageFriendLocations.Friend f, boolean divider) {
        TLRPC.User user = getMessagesController().getUser(f.uid);
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.addView(avatarView(context, user, 54), frame(54, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        TextView name = text(context, 16, cText, true);
        name.setText(UserObject.getUserName(user));
        row.addView(name, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 13, f.state == UMessageFriendLocations.STATE_FRIEND ? 64 : 170, 0));
        TextView sub = text(context, 15, cSecondary, false);
        row.addView(sub, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 37, f.state == UMessageFriendLocations.STATE_FRIEND ? 16 : 170, 0));

        if (f.state == UMessageFriendLocations.STATE_FRIEND) {
            sub.setText(details(f, false));
            TextView time = null;
            if (f.location != null) {
                time = text(context, 13, getThemedColor(Theme.key_chats_date), false);
                time.setText(LocaleController.stringForMessageListDate(f.location.time / 1000));
                row.addView(time, frame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 15, 16, 0));
            }
            rowTexts.put(f.uid, new TextView[]{sub, time});
            row.setOnClickListener(v -> {
                if (f.location != null) {
                    select(f.uid);
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatString(R.string.UMessageFriendMapNoLocationYet, UserObject.getFirstName(user))).show();
                }
            });
            row.setOnLongClickListener(v -> {
                confirmRemove(f.uid);
                return true;
            });
        } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
            sub.setText(LocaleController.getString(R.string.UMessageFriendMapWantsToAdd));
            sub.setTextColor(cAccent);
            LinearLayout actions = new LinearLayout(context);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView accept = pill(context, LocaleController.getString(R.string.UMessageFriendMapAccept), cAccent, 0xffffffff, 14);
            accept.setOnClickListener(v -> accept(f.uid));
            actions.addView(accept, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
            ImageView decline = new ImageView(context);
            decline.setScaleType(ImageView.ScaleType.CENTER);
            decline.setImageResource(R.drawable.ic_close_white);
            decline.setColorFilter(cSecondary);
            decline.setBackground(rounded(dark ? 0xff2c2c2e : 0xfff2f2f7, dp(16)));
            decline.setOnClickListener(v -> controller.remove(f.uid));
            pressScale(decline);
            actions.addView(decline, LayoutHelper.createLinear(32, 32, 8, 0, 0, 0));
            mirror(actions);
            row.addView(actions, frame(LayoutHelper.WRAP_CONTENT, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        } else {
            sub.setText(LocaleController.getString(R.string.UMessageFriendMapRequestSent));
            TextView cancel = pill(context, LocaleController.getString(R.string.Cancel), dark ? 0xff2c2c2e : 0xfff2f2f7, cSecondary, 14);
            cancel.setOnClickListener(v -> controller.remove(f.uid));
            row.addView(cancel, frame(LayoutHelper.WRAP_CONTENT, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        }

        if (divider) {
            View line = new View(context);
            line.setBackgroundColor(cSeparator);
            row.addView(line, frame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 76, 0, 0, 0));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
        return row;
    }

    private String details(UMessageFriendLocations.Friend f, boolean withTime) {
        String base = detailsBase(f, withTime);
        if (f.zone == null) return base;
        String zone = f.zone.state == UMessageFriendLocations.ZONE_ACTIVE
                ? LocaleController.formatString(R.string.UMessageZoneShort, UMessageFriendLocations.formatRadius(f.zone.radius))
                : LocaleController.getString(R.string.UMessageZoneWaiting);
        return base + " · 🎯 " + zone;
    }

    private String detailsBase(UMessageFriendLocations.Friend f, boolean withTime) {
        if (f.location == null) {
            return LocaleController.getString(R.string.UMessageFriendMapNoLocationShort);
        }
        StringBuilder sb = new StringBuilder();
        Location me = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        if (me != null) {
            float[] d = new float[1];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), f.location.lat, f.location.lng, d);
            sb.append(LocaleController.formatString(R.string.UMessageFriendMapAway, LocaleController.formatDistance(d[0], 2)));
        }
        float speed = overlay != null ? overlay.getSpeed(f.uid) : f.location.speedKmh;
        if (speed >= 1f) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(LocaleController.getString(R.string.UMessageFriendMapSpeed), Math.round(speed)));
        }
        if (withTime || sb.length() == 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(LocaleController.formatLocationUpdateDate(f.location.time / 1000));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- actions

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("needForwardCount", false);
        ContactsActivity activity = new ContactsActivity(args);
        activity.setDelegate((user, param, a) -> AndroidUtilities.runOnUIThread(() -> {
            if (user == null) return;
            UMessageFriendLocations.Friend f = controller.get(user.id);
            if (f == null) {
                sendRequest(user.id);
            } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
                accept(user.id);
            } else if (f.state == UMessageFriendLocations.STATE_FRIEND) {
                select(user.id);
            }
        }, 300));
        presentFragment(activity);
    }

    private void sendRequest(long uid) {
        controller.sendRequest(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageFriendMapRequestSentTo, UserObject.getFirstName(user))).show();
    }

    private void accept(long uid) {
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            pendingAction = () -> accept(uid);
            permissionAsked = false;
            requestPermission();
            return;
        }
        controller.accept(uid);
        UMessageFriendLocations.restartTracking();
    }

    private void confirmRemove(long uid) {
        if (getParentActivity() == null) return;
        TLRPC.User user = getMessagesController().getUser(uid);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageFriendMapRemoveTitle));
        builder.setMessage(LocaleController.formatString(R.string.UMessageFriendMapRemoveInfo, UserObject.getFirstName(user)));
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapRemove), (d, w) -> {
            if (selectedUid == uid) select(0);
            controller.remove(uid);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) button.setTextColor(getThemedColor(Theme.key_text_RedBold));
    }

    private void openChat(long uid) {
        Bundle args = new Bundle();
        args.putLong("user_id", uid);
        presentFragment(new ChatActivity(args));
    }

    /** Waves at a friend: a hi message plus where we are right now. */
    private void sayHi(long uid) {
        Location l = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(LocaleController.getString(R.string.UMessageFriendMapHiText), uid));
        if (l != null) {
            TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
            media.geo = new TLRPC.TL_geoPoint();
            media.geo.lat = AndroidUtilities.fixLocationCoord(l.getLatitude());
            media.geo._long = AndroidUtilities.fixLocationCoord(l.getLongitude());
            getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(media, uid, null, null, null, null, true, 0, 0));
        }
        if (overlay != null) overlay.sayHi(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageFriendMapHiSent, UserObject.getFirstName(user))).show();
    }

    // ---------------------------------------------------------------- view helpers

    private BackupImageView avatarView(Context context, TLRPC.User user, int size) {
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(size / 2f));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, user);
        avatar.setForUserOrChat(user, avatarDrawable);
        return avatar;
    }

    private ImageView roundButton(Context context, int icon) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(dark ? 0xffffffff : 0xff000000);
        view.setBackground(rounded(cButton, dp(22)));
        view.setElevation(dp(3));
        pressScale(view);
        return view;
    }

    private ImageView controlButton(Context context, int icon, View.OnClickListener listener) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(cAccent);
        view.setOnClickListener(listener);
        pressScale(view);
        return view;
    }

    private TextView pill(Context context, String label, int bgColor, int textColor, float textSize) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(rounded(bgColor, dp(textSize >= 16 ? 14 : 16)));
        pressScale(view);
        return view;
    }

    private static TextView text(Context context, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    /** LayoutHelper.createFrame mirrored for right-to-left languages (the app has no system RTL layout). */
    private static FrameLayout.LayoutParams frame(int width, float height, int gravity, float left, float top, float right, float bottom) {
        if (LocaleController.isRTL) {
            int h = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (h == Gravity.LEFT || h == Gravity.RIGHT) {
                gravity = (gravity & ~Gravity.HORIZONTAL_GRAVITY_MASK) | (h == Gravity.LEFT ? Gravity.RIGHT : Gravity.LEFT);
            }
            float t = left;
            left = right;
            right = t;
        }
        return LayoutHelper.createFrame(width, height, gravity, left, top, right, bottom);
    }

    /** Reverses a horizontal row (children and side margins) for right-to-left languages. */
    private static void mirror(LinearLayout layout) {
        if (!LocaleController.isRTL) return;
        ArrayList<View> children = new ArrayList<>();
        for (int i = 0; i < layout.getChildCount(); i++) children.add(layout.getChildAt(i));
        layout.removeAllViews();
        for (int i = children.size() - 1; i >= 0; i--) {
            View child = children.get(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            int l = lp.leftMargin;
            lp.leftMargin = lp.rightMargin;
            lp.rightMargin = l;
            layout.addView(child, lp);
        }
        layout.setPadding(layout.getPaddingRight(), layout.getPaddingTop(), layout.getPaddingLeft(), layout.getPaddingBottom());
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void pressScale(View view) {
        view.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f).setDuration(120).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            return false;
        });
    }
}
