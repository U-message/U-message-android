package org.telegram.messenger.privacyguard;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Registers the owner's face: guides through five head orientations with the front camera and stores only
 * identity embeddings (encrypted, on device). No photo is taken or kept.
 */
@TargetApi(Build.VERSION_CODES.N)
public final class PrivacyGuardEnrollment implements PrivacyGuardCamera.Listener {

    public static final int STEP_STRAIGHT = 0;
    public static final int STEP_SIDE = 1;
    public static final int STEP_OTHER_SIDE = 2;
    public static final int STEP_UP = 3;
    public static final int STEP_DOWN = 4;
    public static final int STEP_COUNT = 5;

    public static final int HINT_NONE = 0;
    public static final int HINT_NO_FACE = 1;
    public static final int HINT_MULTIPLE_FACES = 2;
    public static final int HINT_MOVE_CLOSER = 3;
    public static final int HINT_TOO_DARK = 4;
    public static final int HINT_OPEN_EYES = 5;
    public static final int HINT_TURN_MORE = 6;
    public static final int HINT_TURN_LESS = 7;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_CAMERA = 1;
    public static final int ERROR_MODELS = 2;
    public static final int ERROR_INCONSISTENT = 3;
    public static final int ERROR_SAVE = 4;

    private static final int SAMPLES_PER_STEP = 3;
    private static final long SAMPLE_INTERVAL_MS = 220;
    private static final long FRAME_INTERVAL_MS = 110;
    private static final float MIN_FACE_FRACTION = 0.2f;
    private static final float MIN_LUMA = 20f;
    private static final float MIN_CONSISTENCY = 0.5f;

    public interface Callback {
        /** UI thread. Preview buffer size as delivered by the camera, before rotation. */
        void onCameraStarted(int width, int height);

        /**
         * UI thread. fill[0] = straight progress, fill[1..] = ring sectors (0 = top, clockwise) already covered,
         * in whatever order the user turns their head.
         */
        void onCoverage(float[] fill, int hint);

        /** UI thread. */
        void onFinished(boolean success, int error);
    }

    private final Context context;
    private final Callback callback;
    private final HandlerThread cameraThread = new HandlerThread("PGEnrollCamera");
    private final HandlerThread analysisThread = new HandlerThread("PGEnrollAnalysis");
    private Handler cameraHandler, analysisHandler;
    private PrivacyGuardCamera camera;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile boolean ready;
    private volatile long lastFrame;
    private volatile int sensorOrientation = 270;

    // analysis thread
    private FaceMeshPipeline pipeline;
    private FaceEmbedder embedder;
    private final YuvFrame.Converter converter = new YuvFrame.Converter();
    private final FacePose pose = new FacePose();
    private int step;
    private final ArrayList<ArrayList<float[]>> samples = new ArrayList<>();
    private long lastSample;
    private float baselinePitch;
    private volatile float sideSign;
    private boolean finished;
    private int lastStepPosted = -1;
    private float lastProgressPosted = -1;
    private int lastHintPosted = -1;

    public PrivacyGuardEnrollment(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        for (int i = 0; i < STEP_COUNT; i++) {
            samples.add(new ArrayList<>());
        }
    }

    public void start(SurfaceTexture preview) {
        running = true;
        PrivacyGuardController.getInstance().setEnrollmentActive(true);
        cameraThread.start();
        analysisThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        analysisHandler = new Handler(analysisThread.getLooper());
        analysisHandler.post(() -> {
            try {
                pipeline = new FaceMeshPipeline();
                embedder = new FaceEmbedder();
                ready = true;
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: enrollment models unavailable (" + e.getClass().getSimpleName() + ")");
                }
                finish(false, ERROR_MODELS);
            }
        });
        cameraHandler.post(() -> {
            camera = new PrivacyGuardCamera(context, cameraHandler, 30, this);
            camera.attach();
            if (!camera.open(preview)) {
                finish(false, ERROR_CAMERA);
            }
        });
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        ready = false;
        cameraHandler.post(() -> {
            if (camera != null) {
                camera.detach();
            }
            cameraHandler.postDelayed(cameraThread::quitSafely, 700);
        });
        analysisHandler.post(() -> {
            if (pipeline != null) {
                pipeline.close();
                pipeline = null;
            }
            if (embedder != null) {
                embedder.close();
                embedder = null;
            }
            converter.release();
            for (ArrayList<float[]> list : samples) {
                for (float[] s : list) {
                    java.util.Arrays.fill(s, 0);
                }
                list.clear();
            }
            analysisThread.quitSafely();
        });
        PrivacyGuardController.getInstance().setEnrollmentActive(false);
    }

    /** Sign of the yaw (upright frame) the first side step was taken with; 0 until known. Read-only, for the UI. */
    public float getSideSign() {
        return sideSign;
    }

    /** Start over after a failed consistency check. */
    public void restart() {
        analysisHandler.post(() -> {
            for (ArrayList<float[]> list : samples) {
                list.clear();
            }
            step = STEP_STRAIGHT;
            baselinePitch = 0;
            sideSign = 0;
            java.util.Arrays.fill(covered, false);
            smoothYaw = smoothPitch = Float.NaN;
            baselineYaw = 0;
            lastSector = -1;
            finished = false;
            lastStepPosted = -1;
            progress(HINT_NONE, 0);
        });
    }

    @Override
    public boolean wantsFrame() {
        return running && ready && !busy && SystemClock.elapsedRealtime() - lastFrame >= FRAME_INTERVAL_MS;
    }

    @Override
    public void onFrame(YuvFrame frame) {
        busy = true;
        lastFrame = SystemClock.elapsedRealtime();
        final PrivacyGuardCamera source = camera;
        analysisHandler.post(() -> {
            try {
                if (running && ready && !finished) {
                    analyze(frame);
                }
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: enrollment analysis failed (" + e.getClass().getSimpleName() + ")");
                }
            } finally {
                source.recycle(frame);
                busy = false;
            }
        });
    }

    @Override
    public void onStarted(int sensorOrientation, Size size) {
        this.sensorOrientation = sensorOrientation;
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onCameraStarted(size.getWidth(), size.getHeight());
            }
        });
    }

    @Override
    public void onStopped(boolean interrupted) {
        if (interrupted && running) {
            finish(false, ERROR_CAMERA);
        }
    }

    private void analyze(YuvFrame frame) {
        final long now = SystemClock.elapsedRealtime();
        final Bitmap bitmap = converter.convert(frame, (sensorOrientation + PrivacyGuardCamera.displayRotation(context)) % 360);
        if (converter.meanLuma < MIN_LUMA) {
            progress(HINT_TOO_DARK, currentProgress());
            return;
        }
        final ArrayList<FaceMeshPipeline.Face> faces = pipeline.process(bitmap, 3, null);
        if (faces.isEmpty()) {
            progress(HINT_NO_FACE, currentProgress());
            return;
        }
        final FaceMeshPipeline.Face face = faces.get(0);
        if (faces.size() > 1 && faces.get(1).box.width() > face.box.width() * 0.4f) {
            progress(HINT_MULTIPLE_FACES, currentProgress());
            return;
        }
        FaceGeometry.compute(face, pose);
        if (pose.faceWidth / bitmap.getWidth() < MIN_FACE_FRACTION) {
            progress(HINT_MOVE_CLOSER, currentProgress());
            return;
        }
        if (pose.blink > 0.5f) {
            progress(HINT_OPEN_EYES, currentProgress());
            return;
        }
        // Face ID-like: the head direction lights the matching part of the ring, all the way around, in any order
        // smooth the pose so one noisy frame can't light a wrong part of the ring
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = pose.yaw;
            smoothPitch = pose.pitch;
        } else {
            smoothYaw += (pose.yaw - smoothYaw) * 0.5f;
            smoothPitch += (pose.pitch - smoothPitch) * 0.5f;
        }
        // the ring only counts once the straight look is known: directions are measured from it
        final boolean calibrated = samples.get(STEP_STRAIGHT).size() >= SAMPLES_PER_STEP;
        final int rawSector = calibrated ? sectorOf() : -1;
        // a sector is covered only after the head stayed there for two analysed frames in a row
        final int sector = rawSector >= 0 && rawSector == lastSector ? rawSector : -1;
        lastSector = rawSector;
        boolean changed = false;
        if (sector >= 0 && !covered[sector]) {
            covered[sector] = true;
            changed = true;
        }
        final int bucket = sector >= 0 ? quadrantOf(sector) : (!calibrated ? (Math.abs(smoothYaw) < 12 && Math.abs(smoothPitch) < 20 ? STEP_STRAIGHT : -1) : (isStraight() ? STEP_STRAIGHT : -1));
        final int limit = bucket == STEP_STRAIGHT ? SAMPLES_PER_STEP : MAX_SIDE_SAMPLES;
        if (bucket >= 0 && samples.get(bucket).size() < limit && now - lastSample >= SAMPLE_INTERVAL_MS) {
            lastSample = now;
            final ArrayList<float[]> list = samples.get(bucket);
            list.add(embedder.embed(bitmap, face));
            if (bucket == STEP_STRAIGHT) {
                // the user's natural straight look (phone held below the face is common) becomes the centre
                baselinePitch += (smoothPitch - baselinePitch) / list.size();
                baselineYaw += (smoothYaw - baselineYaw) / list.size();
            }
            changed = true;
        }
        if (changed && isComplete()) {
            complete();
            return;
        }
        progress(HINT_NONE, 0);
    }

    private static final int SECTORS = 24;
    private static final int MAX_SIDE_SAMPLES = 6;
    private final boolean[] covered = new boolean[SECTORS];

    private float smoothYaw = Float.NaN, smoothPitch = Float.NaN;
    private float baselineYaw;
    private int lastSector = -1;

    private float relYaw() {
        return smoothYaw - baselineYaw;
    }

    private float relPitch() {
        return samples.get(STEP_STRAIGHT).isEmpty() ? smoothPitch : smoothPitch - baselinePitch;
    }

    private boolean isStraight() {
        return Math.abs(relYaw()) < 9 && Math.abs(relPitch()) < 11;
    }

    /** Ring sector (0 = top, clockwise on the preview) the head points to, or -1 when facing straight or too far. */
    private int sectorOf() {
        final float x = -relYaw() / 28f;      // checked on a phone: +yaw lights the opposite side, so it is negated      // mirrored preview: a negative yaw is a turn toward screen right
        final float y = relPitch() / 20f;     // up is positive
        final double m = Math.sqrt(x * x + y * y);
        if (m < 0.45 || m > 1.7) return -1;
        double deg = Math.toDegrees(Math.atan2(x, y));
        if (deg < 0) deg += 360;
        return Math.min(SECTORS - 1, (int) (deg / (360.0 / SECTORS)));
    }

    /** Which embedding bucket a sector belongs to (up, right = negative yaw, down, left = positive yaw). */
    private static int quadrantOf(int sector) {
        final double deg = (sector + 0.5) * 360.0 / SECTORS;
        if (deg >= 315 || deg < 45) return STEP_UP;
        if (deg < 135) return STEP_SIDE;
        if (deg < 225) return STEP_DOWN;
        return STEP_OTHER_SIDE;
    }

    private boolean isComplete() {
        for (boolean c : covered) if (!c) return false;
        if (samples.get(STEP_STRAIGHT).size() < SAMPLES_PER_STEP) return false;
        for (int i = 1; i < STEP_COUNT; i++) if (samples.get(i).isEmpty()) return false;
        return true;
    }

    private float currentProgress() {
        return 0;
    }

    private void complete() {
        // every orientation must belong to the same person as the straight one
        final float[] reference = mean(samples.get(STEP_STRAIGHT));
        ArrayList<float[]> templates = new ArrayList<>();
        for (int i = 0; i < STEP_COUNT; i++) {
            if (samples.get(i).isEmpty()) continue;
            float[] m = mean(samples.get(i));
            if (FaceEmbedder.cosine(m, reference) < MIN_CONSISTENCY) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("PrivacyGuard: ENROLL_INCONSISTENT");
                }
                finish(false, ERROR_INCONSISTENT);
                return;
            }
            templates.add(m);
            templates.addAll(samples.get(i));
        }
        final boolean saved = OwnerFaceStore.save(templates.toArray(new float[0][]));
        finish(saved, saved ? ERROR_NONE : ERROR_SAVE);
    }

    private static float[] mean(ArrayList<float[]> list) {
        float[] m = new float[list.get(0).length];
        for (float[] v : list) {
            for (int i = 0; i < m.length; i++) {
                m[i] += v[i];
            }
        }
        FaceEmbedder.normalize(m);
        return m;
    }

    private void progress(int hint, float ignored) {
        final float[] fill = new float[1 + SECTORS];
        float total = fill[0] = Math.min(1f, samples.get(STEP_STRAIGHT).size() / (float) SAMPLES_PER_STEP);
        for (int i = 0; i < SECTORS; i++) {
            fill[1 + i] = covered[i] ? 1f : 0f;
            total += fill[1 + i] * (i + 2) * 3;
        }
        if (hint == lastHintPosted && total == lastProgressPosted) {
            return;
        }
        lastHintPosted = hint;
        lastProgressPosted = total;
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onCoverage(fill, hint);
            }
        });
    }

    private void finish(boolean success, int error) {
        finished = true;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("PrivacyGuard: ENROLL " + (success ? "DONE" : "FAILED error=" + error));
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onFinished(success, error);
            }
        });
    }
}
