package org.telegram.messenger.privacyguard;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Low resolution front camera stream for local analysis. Frames are copied into memory and handed to the
 * listener; nothing is recorded, saved or sent. All camera work happens on the given handler's thread.
 * The camera is opened only when no other client uses any camera, and is given up whenever someone else needs it.
 */
@TargetApi(Build.VERSION_CODES.N)
final class PrivacyGuardCamera {

    interface Listener {
        /** Called on the camera thread; return false to drop the frame (analysis busy or throttled). */
        boolean wantsFrame();

        /** Called on the camera thread with a filled frame; call {@link #recycle} when done. */
        void onFrame(YuvFrame frame);

        /** Camera is streaming. */
        void onStarted(int sensorOrientation, Size size);

        /** Camera is closed; {@code interrupted} when it was taken away or failed rather than stopped by us. */
        void onStopped(boolean interrupted);
    }

    private final CameraManager manager;
    private final Handler handler;
    private final Listener listener;
    private final int maxFps;

    private String cameraId;
    private int sensorOrientation;
    private Size size;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;
    private boolean opening;
    private boolean wanted;
    private CountDownLatch closeLatch;

    private final HashSet<String> busyCameras = new HashSet<>();
    private long availabilityChangedAt;
    private final ArrayList<YuvFrame> framePool = new ArrayList<>();

    private final CameraManager.AvailabilityCallback availabilityCallback = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            busyCameras.remove(id);
            availabilityChangedAt = SystemClock.elapsedRealtime();
        }

        @Override
        public void onCameraUnavailable(String id) {
            busyCameras.add(id);
            availabilityChangedAt = SystemClock.elapsedRealtime();
        }
    };

    PrivacyGuardCamera(Context context, Handler handler, int maxFps, Listener listener) {
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.handler = handler;
        this.listener = listener;
        this.maxFps = maxFps;
        framePool.add(new YuvFrame());
        framePool.add(new YuvFrame());
    }

    /** Starts tracking camera availability; call on the camera thread. */
    void attach() {
        manager.registerAvailabilityCallback(availabilityCallback, handler);
        PrivacyGuardCameraArbiter.register(this);
    }

    /** Stops everything; call on the camera thread. */
    void detach() {
        PrivacyGuardCameraArbiter.unregister(this);
        manager.unregisterAvailabilityCallback(availabilityCallback);
        closeInternal(false);
        synchronized (framePool) {
            for (YuvFrame f : framePool) {
                f.wipe();
            }
        }
    }

    boolean isOpen() {
        return device != null || opening;
    }

    /** Whether nothing else uses a camera now and has not asked for one recently. Camera thread. */
    boolean canOpen() {
        final long now = SystemClock.elapsedRealtime();
        if (now - PrivacyGuardCameraArbiter.getLastYieldTime() < PrivacyGuardTuning.CAMERA_YIELD_MS) {
            return false;
        }
        if (now - availabilityChangedAt < PrivacyGuardTuning.CAMERA_AVAILABLE_SETTLE_MS) {
            return false;
        }
        for (String id : busyCameras) {
            if (!id.equals(cameraId) || device == null && !opening) {
                return false;
            }
        }
        return true;
    }

    /** Opens the front camera, optionally also streaming into a preview texture. Camera thread. */
    @SuppressLint("MissingPermission")
    boolean open(SurfaceTexture preview) {
        if (device != null || opening) {
            return true;
        }
        wanted = true;
        previewTexture = preview;
        try {
            if (cameraId == null && !selectCamera()) {
                return false;
            }
            opening = true;
            manager.openCamera(cameraId, stateCallback, handler);
            return true;
        } catch (Exception e) {
            opening = false;
            log("open failed " + e.getClass().getSimpleName());
            return false;
        }
    }

    void close() {
        closeInternal(false);
    }

    /** Another camera user needs the camera: close now and wait (bounded) until the device is released. Any thread. */
    void closeForOtherUser(long timeoutMs) {
        if (Looper.myLooper() == handler.getLooper()) {
            closeInternal(true);
            return;
        }
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        final CountDownLatch posted = new CountDownLatch(1);
        final CountDownLatch[] closed = new CountDownLatch[1];
        handler.postAtFrontOfQueue(() -> {
            closed[0] = closeInternal(true);
            posted.countDown();
        });
        if (await(posted, deadline) && closed[0] != null) {
            await(closed[0], deadline);
        }
    }

    private static boolean await(CountDownLatch latch, long deadline) {
        try {
            return latch.await(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** Returns a latch released when the device reports closed, or null if nothing was open. */
    private CountDownLatch closeInternal(boolean interrupted) {
        // a pending open must not start streaming once it completes
        wanted = false;
        if (device == null && !opening && session == null) {
            return null;
        }
        final CountDownLatch latch = closeLatch = new CountDownLatch(1);
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        final boolean hadDevice = device != null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (Exception ignore) {
        }
        device = null;
        opening = false;
        if (!hadDevice) {
            // still opening: onOpened will close it; nothing to wait for
            latch.countDown();
            releaseSurfaces();
        }
        listener.onStopped(interrupted);
        return latch;
    }

    private void releaseSurfaces() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private boolean selectCamera() throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) {
                continue;
            }
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                continue;
            }
            Size best = null;
            float bestScore = Float.MAX_VALUE;
            final int targetArea = PrivacyGuardTuning.ANALYSIS_WIDTH * PrivacyGuardTuning.ANALYSIS_HEIGHT;
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                final float aspect = Math.max(s.getWidth(), s.getHeight()) / (float) Math.min(s.getWidth(), s.getHeight());
                final float score = Math.abs((float) Math.log(s.getWidth() * s.getHeight() / (float) targetArea)) + Math.abs(aspect - 4f / 3f) * 2f;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best == null) {
                continue;
            }
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            cameraId = id;
            size = best;
            sensorOrientation = orientation != null ? orientation : 270;
            return true;
        }
        return false;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            opening = false;
            if (!wanted) {
                camera.close();
                return;
            }
            device = camera;
            startSession();
        }

        @Override
        public void onClosed(CameraDevice camera) {
            releaseSurfaces();
            if (closeLatch != null) {
                closeLatch.countDown();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            // a higher priority client took the camera
            log("disconnected");
            camera.close();
            if (device == camera) {
                device = null;
                session = null;
                listener.onStopped(true);
            }
            opening = false;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            log("error " + error);
            camera.close();
            if (device == camera || opening) {
                device = null;
                session = null;
                opening = false;
                listener.onStopped(true);
            }
        }
    };

    private void startSession() {
        try {
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(reader.getSurface());
            if (previewTexture != null) {
                previewTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
                previewSurface = new Surface(previewTexture);
                surfaces.add(previewSurface);
            }
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    if (device == null) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    try {
                        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        for (Surface s : surfaces) {
                            builder.addTarget(s);
                        }
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        Range<Integer> fps = chooseFpsRange();
                        if (fps != null) {
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                        }
                        if (supportsFacePriority()) {
                            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                        }
                        session.setRepeatingRequest(builder.build(), null, handler);
                        listener.onStarted(sensorOrientation, size);
                    } catch (Exception e) {
                        log("request failed " + e.getClass().getSimpleName());
                        closeInternal(true);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession failed) {
                    log("configure failed");
                    closeInternal(true);
                }
            }, handler);
        } catch (Exception e) {
            log("session failed " + e.getClass().getSimpleName());
            closeInternal(true);
        }
    }

    private Range<Integer> chooseFpsRange() {
        try {
            Range<Integer>[] ranges = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null) {
                return null;
            }
            // lowest frame rate that still covers the analysis rate; a low minimum lets exposure grow in low light
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() < PrivacyGuardTuning.FPS_ACTIVE || r.getUpper() > maxFps) {
                    continue;
                }
                if (best == null || r.getUpper() < best.getUpper() || r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower()) {
                    best = r;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean supportsFacePriority() {
        try {
            int[] modes = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            if (modes == null) {
                return false;
            }
            for (int m : modes) {
                if (m == CameraCharacteristics.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private void onImageAvailable(ImageReader r) {
        Image image;
        try {
            image = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (image == null) {
            return;
        }
        YuvFrame frame = null;
        try {
            if (listener.wantsFrame()) {
                synchronized (framePool) {
                    if (!framePool.isEmpty()) {
                        frame = framePool.remove(framePool.size() - 1);
                    }
                }
                if (frame != null) {
                    frame.copyFrom(image);
                }
            }
        } finally {
            image.close();
        }
        if (frame != null) {
            listener.onFrame(frame);
        }
    }

    void recycle(YuvFrame frame) {
        synchronized (framePool) {
            framePool.add(frame);
        }
    }

    /** Current display rotation in degrees. */
    static int displayRotation(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            switch (wm.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("PrivacyGuard: camera " + message);
        }
    }
}
