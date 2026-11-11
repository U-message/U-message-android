package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float currentZoom = 1f;

    private final Size previewSize;

    private ImageReader imageReader;

    /*
     * U message: story video like the procam camera — 1080p at 60 fps through MediaTek's high-frame-rate mode
     * (the public fps ranges stop at 30), the phone's own EIS, and an encoder-usage stream so the HAL enters its
     * video scenario. The camera then only feeds the GL preview; the recorder encodes what the preview draws.
     */
    private boolean proVideo;
    private boolean hfps;
    private int stabilizationMode = -1;
    private Surface encoderSurface;
    private boolean useSessionParams = true;

    public boolean isProVideo() {
        return proVideo;
    }

    private int videoFps = 30;
    private int requestedFps = 60;

    public int getVideoFps() {
        return proVideo ? videoFps : 30;
    }

    /** Story video formats: {height, fps, native}; native 0 marks our "4K" (1080p60 with the 4K colour). */
    public static java.util.ArrayList<int[]> availableFormats() {
        final java.util.ArrayList<int[]> formats = new java.util.ArrayList<>();
        formats.add(new int[]{1080, 30, 1});
        if (Build.VERSION.SDK_INT < 29) {
            return formats;
        }
        try {
            final CameraManager cameraManager = (CameraManager) ApplicationLoader.applicationContext.getSystemService(Context.CAMERA_SERVICE);
            final String id = backCameraId(cameraManager);
            if (id == null) {
                return formats;
            }
            final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return formats;
            }
            final boolean hd60 = supportsFormat(characteristics, map, 1920, 1080, 60);
            if (hd60) {
                formats.add(new int[]{1080, 60, 1});
            }
            if (supportsFormat(characteristics, map, 3840, 2160, 30)) {
                formats.add(new int[]{2160, 30, 1});
            }
            if (supportsFormat(characteristics, map, 3840, 2160, 60)) {
                formats.add(new int[]{2160, 60, 1});
            } else if (hd60) {
                formats.add(new int[]{2160, 60, 0});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return formats;
    }

    private static String backCameraId(CameraManager cameraManager) throws Exception {
        for (String id : cameraManager.getCameraIdList()) {
            final Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    /** The camera streams this size at fps and a hardware encoder takes it. */
    private static boolean supportsFormat(CameraCharacteristics characteristics, StreamConfigurationMap map, int width, int height, int fps) {
        boolean size = false;
        for (android.util.Size s : map.getOutputSizes(SurfaceTexture.class)) {
            if (s.getWidth() == width && s.getHeight() == height) {
                size = true;
                break;
            }
        }
        return size && supportsFps(characteristics, map, width, height, fps)
                && (CameraView.findEncoderCached("video/hevc", width, height, fps) != null || CameraView.findEncoderCached("video/avc", width, height, fps) != null);
    }

    /**
     * The back main camera for story video in the format picked (height 1080 or 2160, fps 30 or 60), falling back
     * to the closest the phone can do; null when Camera2 can't open it.
     */
    public static Camera2Session createProVideo(int height, int fps) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            final String id = backCameraId(cameraManager);
            if (id == null) {
                return null;
            }
            final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Size size = new Size(1920, 1080);
            if (height >= 2160 && supportsFormat(characteristics, map, 3840, 2160, fps)) {
                size = new Size(3840, 2160);
            } else if (!supportsFormat(characteristics, map, 1920, 1080, 30)) {
                return null;
            }
            return new Camera2Session(context, false, id, size, true, fps);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /** The camera streams width x height at fps: a public fps range with its min frame duration, or MediaTek HFPS. */
    private static boolean supportsFps(CameraCharacteristics characteristics, StreamConfigurationMap map, int width, int height, int fps) {
        if (publicFps(characteristics, map, width, height, fps)) {
            return true;
        }
        final int[] limits = hfpsMaxResolutions(characteristics);
        for (int i = 0; limits != null && i + 2 < limits.length; i += 3) {
            if (limits[i] >= width && limits[i + 1] >= height && limits[i + 2] >= fps) {
                return true;
            }
        }
        return false;
    }

    private static boolean publicFps(CameraCharacteristics characteristics, StreamConfigurationMap map, int width, int height, int fps) {
        final Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean publicRange = false;
        for (int i = 0; ranges != null && i < ranges.length; i++) {
            if (ranges[i].getUpper() >= fps) {
                publicRange = true;
            }
        }
        if (publicRange) {
            try {
                final long minFrame = map.getOutputMinFrameDuration(SurfaceTexture.class, new android.util.Size(width, height));
                if (minFrame > 0 && minFrame <= 1_000_000_000L / fps + 100_000L) {
                    return true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    /** Recording at the phone's own 4K (no "4K" colour on top: its own processing stays). */
    public boolean isNativeUhd() {
        return proVideo && previewSize.getWidth() >= 3840;
    }

    private static int[] hfpsMaxResolutions(CameraCharacteristics characteristics) {
        try {
            return characteristics.get(new CameraCharacteristics.Key<>("com.mediatek.streamingfeature.availableHfpsMaxResolutions", int[].class));
        } catch (Throwable e) {
            return null;
        }
    }

    private void setupProVideo() {
        // 60 fps from a public range when the camera has one, else through MediaTek HFPS (whose public ranges stop at 30)
        final int width = previewSize.getWidth(), height = previewSize.getHeight();
        final StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (requestedFps >= 60 && map != null && supportsFps(cameraCharacteristics, map, width, height, 60)) {
            videoFps = 60;
            hfps = !publicFps(cameraCharacteristics, map, width, height, 60);
        }
        final int[] modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (modes != null) {
            for (int mode : modes) {
                // PREVIEW_STABILIZATION (2) also steadies the preview stream, which is what gets recorded here
                if (mode == 2 || mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON && stabilizationMode != 2) {
                    stabilizationMode = mode;
                }
            }
        }
    }

    /**
     * A persistent encoder input surface sized for the video (as procam does): a throwaway MediaRecorder is prepared
     * on it so it gets real buffers of the right size and the video-encoder usage; without that the HAL left it out
     * of the session, stayed in its preview sensor mode and ran at 30 fps.
     */
    private Surface createEncoderSurface() {
        final Surface surface = android.media.MediaCodec.createPersistentInputSurface();
        final android.media.MediaRecorder recorder = new android.media.MediaRecorder();
        final java.io.File warmupFile = new java.io.File(ApplicationLoader.applicationContext.getCacheDir(), "camera_warmup.mp4");
        try {
            recorder.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC);
            recorder.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
            recorder.setVideoFrameRate(videoFps);
            recorder.setVideoEncodingBitRate(isNativeUhd() ? 48_000_000 : 16_000_000);
            recorder.setInputSurface(surface);
            recorder.setOutputFile(warmupFile.getAbsolutePath());
            recorder.prepare();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            recorder.release();
            try {
                warmupFile.delete();
            } catch (Throwable ignore) {
            }
        }
        return surface;
    }

    private void applyProVideo(CaptureRequest.Builder builder) {
        if (hfps && Build.VERSION.SDK_INT >= 29) {
            try {
                builder.set(new CaptureRequest.Key<>("com.mediatek.streamingfeature.hfpsMode", Integer.class), 1);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(videoFps, videoFps));
        if (stabilizationMode >= 0) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode);
        }
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        // optical stabilization under the EIS, when the lens has it
        final int[] ois = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null) {
            for (int mode : ois) {
                if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode);
                }
            }
        }
        // the phone's own real-time video denoise (MediaTek AI video NR + temporal 3D NR), like the system camera
        if (Build.VERSION.SDK_INT >= 29) {
            setVendorKey(builder, "com.mediatek.videoainrfeature.videoAinrModes", 1);
            setVendorKey(builder, "com.mediatek.nrfeature.3dnrmode", 1);
        }
    }

    /** Sets a vendor tag the HAL lists as a request or session key; others are left out. */
    private void setVendorKey(CaptureRequest.Builder builder, String name, int value) {
        try {
            final java.util.ArrayList<CaptureRequest.Key<?>> keys = new java.util.ArrayList<>(cameraCharacteristics.getAvailableCaptureRequestKeys());
            if (Build.VERSION.SDK_INT >= 28) {
                keys.addAll(cameraCharacteristics.getAvailableSessionKeys());
            }
            for (CaptureRequest.Key<?> key : keys) {
                if (name.equals(key.getName())) {
                    builder.set(new CaptureRequest.Key<>(name, Integer.class), value);
                    return;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size size = chooseOptimalSize(confMap.getOutputSizes(SurfaceTexture.class), viewWidth, viewHeight, false);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                } else {

                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Camera2Session(context, front, cameraId, bestSize);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size) {
        this(context, isFront, cameraId, size, false, 30);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size, boolean proVideo, int fps) {
        this.proVideo = proVideo;
        this.requestedFps = fps;
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    updateCaptureRequest();
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                if (Camera2Session.this.proVideo && useSessionParams) {
                    // some HALs reject the stabilization / fps session parameters: retry plain
                    FileLog.e("Camera2Session pro video session failed, retrying without session parameters");
                    useSessionParams = false;
                    // the plain session can't hold 60 fps / HFPS: record what it really delivers
                    hfps = false;
                    videoFps = 30;
                    opened = false;
                    handler.post(Camera2Session.this::checkOpen);
                    return;
                }
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        if (!proVideo) {
            this.imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        }
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            if (proVideo) {
                setupProVideo();
            }
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            org.telegram.messenger.privacyguard.PrivacyGuardCameraArbiter.yieldCamera(); // U message: Privacy Guard hands over the camera
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        if (surface == null) {
            surface = new Surface(surfaceTexture);
        }

        try {
            if (proVideo && Build.VERSION.SDK_INT >= 28) {
                final ArrayList<android.hardware.camera2.params.OutputConfiguration> outputs = new ArrayList<>();
                outputs.add(new android.hardware.camera2.params.OutputConfiguration(surface));
                if (useSessionParams) {
                    // configured but never targeted: an encoder-usage stream puts the HAL in its video mode (record EIS)
                    if (encoderSurface == null) {
                        encoderSurface = createEncoderSurface();
                    }
                    outputs.add(new android.hardware.camera2.params.OutputConfiguration(encoderSurface));
                }
                final android.hardware.camera2.params.SessionConfiguration config = new android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR, outputs, handler::post, captureStateCallback);
                if (useSessionParams) {
                    final CaptureRequest.Builder params = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    applyProVideo(params);
                    config.setSessionParameters(params.build());
                }
                cameraDevice.createCaptureSession(config);
                return;
            }
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();

        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        // TODO: support wide zoom camera switching
        return 1f;
    }

    /**
     * U message: view angle in degrees across the short side of the preview at the current zoom, 0 when the camera
     * does not report its optics. Round videos crop to that side, so it is also the angle of their square frame.
     */
    public float getShortSideFieldOfView() {
        if (cameraCharacteristics == null || previewSize == null) {
            return 0;
        }
        final float[] focalLengths = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        final SizeF physical = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        final Size pixels = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        final Rect active = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (focalLengths == null || focalLengths.length == 0 || focalLengths[0] <= 0 || physical == null
                || pixels == null || active == null || pixels.getWidth() <= 0 || pixels.getHeight() <= 0) {
            return 0;
        }
        final float activeWidth = physical.getWidth() * active.width() / pixels.getWidth();
        final float activeHeight = physical.getHeight() * active.height() / pixels.getHeight();
        final float sensorLong = Math.max(activeWidth, activeHeight), sensorShort = Math.min(activeWidth, activeHeight);
        final int previewLong = Math.max(previewSize.getWidth(), previewSize.getHeight());
        final int previewShort = Math.min(previewSize.getWidth(), previewSize.getHeight());
        if (sensorShort <= 0 || previewShort <= 0) {
            return 0;
        }
        // a preview wider than the sensor is cut across the sensor's short side
        final float aspect = previewLong / (float) previewShort;
        final float shortSide = aspect > sensorLong / sensorShort ? sensorLong / aspect : sensorShort;
        return (float) Math.toDegrees(2 * Math.atan(shortSide / (2 * focalLengths[0] * Math.max(1f, currentZoom))));
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                if (encoderSurface != null) {
                    encoderSurface.release();
                    encoderSurface = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            // pro video already streams the record request: re-sending it stalls the HAL for a moment and
            // the first frames of the recording were lost; only the torch needs it
            if (!proVideo || flashing) {
                updateCaptureRequest();
            }
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private void updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return;
        try {
            int template;
            if (proVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (proVideo) {
                applyProVideo(captureRequestBuilder);
            } else if (recordingVideo) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, 60));
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
            }

            if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (cameraDevice == null || captureSession == null || imageReader == null) return false;
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, null);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}