package org.telegram.messenger.privacyguard;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Size;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Runs the Privacy Guard pipeline while a chat is visible:
 * front camera (low resolution, 4-10 fps) -> face mesh -> owner recognition -> gaze -> temporal tracking
 * -> state machine. Everything stays on the device and in memory; results reach the UI as plain states.
 */
@TargetApi(Build.VERSION_CODES.N)
final class PrivacyGuardEngine implements PrivacyGuardCamera.Listener {

    interface Listener {
        /** UI thread. */
        void onStatus(PrivacyGuardStatus status);
    }

    private final Context context;
    private final Listener listener;
    private final float[][] ownerTemplates;
    private volatile PrivacyGuardSettings.Snapshot config;

    private final HandlerThread cameraThread = new HandlerThread("PGCamera");
    private final HandlerThread analysisThread = new HandlerThread("PGAnalysis", Process.THREAD_PRIORITY_BACKGROUND);
    private Handler cameraHandler;
    private Handler analysisHandler;
    private PrivacyGuardCamera camera;

    private volatile boolean running;
    private volatile boolean modelsReady;
    private volatile boolean analysisBusy;
    private volatile long lastFrameTime;
    private volatile long frameIntervalMs = 1000 / PrivacyGuardTuning.FPS_IDLE;
    private volatile int sensorOrientation = 270;

    // analysis thread only
    private FaceMeshPipeline pipeline;
    private FaceEmbedder embedder;
    private final YuvFrame.Converter converter = new YuvFrame.Converter();
    private final FaceTracker tracker = new FaceTracker();
    private final PrivacyGuardStateMachine machine = new PrivacyGuardStateMachine();
    private final PrivacyGuardStateMachine.Observation observation = new PrivacyGuardStateMachine.Observation();
    private final ArrayList<FaceTracker.Observation> observations = new ArrayList<>();
    private float lumaAverage = -1;
    private long unstableUntil;
    private boolean paused = true;
    private PrivacyGuardStatus lastPosted;
    private FaceTracker.Summary lastSummary;
    private long lastDiagnostics;
    private int framesSinceDiagnostics;
    private long frameTimeSinceDiagnostics;

    PrivacyGuardEngine(Context context, float[][] ownerTemplates, PrivacyGuardSettings.Snapshot config, Listener listener) {
        this.context = context.getApplicationContext();
        this.ownerTemplates = ownerTemplates;
        this.config = config;
        this.listener = listener;
    }

    void start() {
        running = true;
        cameraThread.start();
        analysisThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        analysisHandler = new Handler(analysisThread.getLooper());
        analysisHandler.post(() -> {
            try {
                pipeline = new FaceMeshPipeline();
                embedder = new FaceEmbedder();
                modelsReady = true;
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: models unavailable (" + e.getClass().getSimpleName() + ")");
                }
                post(true);
            }
        });
        cameraHandler.post(() -> {
            camera = new PrivacyGuardCamera(context, cameraHandler, 15, this);
            camera.attach();
            ensureCamera.run();
        });
    }

    void stop() {
        running = false;
        modelsReady = false;
        cameraHandler.post(() -> {
            cameraHandler.removeCallbacks(ensureCamera);
            if (camera != null) {
                camera.detach();
            }
            // let the device report closed before the thread goes away
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
            tracker.reset();
            machine.reset();
            analysisThread.quitSafely();
        });
    }

    void setConfig(PrivacyGuardSettings.Snapshot config) {
        this.config = config;
    }

    /** The user chose to show the chat despite the current detection. */
    void dismiss() {
        if (analysisHandler != null) {
            analysisHandler.post(() -> {
                machine.dismiss();
                tracker.dismissCurrent();
                post(paused);
            });
        }
    }

    private final Runnable ensureCamera = new Runnable() {
        @Override
        public void run() {
            if (!running || camera == null) {
                return;
            }
            if (!camera.isOpen() && camera.canOpen()) {
                if (!camera.open(null) && BuildVars.LOGS_ENABLED) {
                    FileLog.d("PrivacyGuard: PAUSED (no front camera)");
                }
            }
            cameraHandler.postDelayed(this, PrivacyGuardTuning.CAMERA_RETRY_MS);
        }
    };

    /* Camera thread */

    @Override
    public boolean wantsFrame() {
        return running && modelsReady && !analysisBusy && SystemClock.elapsedRealtime() - lastFrameTime >= frameIntervalMs;
    }

    @Override
    public void onFrame(YuvFrame frame) {
        analysisBusy = true;
        lastFrameTime = SystemClock.elapsedRealtime();
        final PrivacyGuardCamera source = camera;
        analysisHandler.post(() -> {
            try {
                if (running && modelsReady) {
                    analyze(frame);
                }
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: analysis failed (" + e.getClass().getSimpleName() + ")");
                }
            } finally {
                source.recycle(frame);
                analysisBusy = false;
            }
        });
    }

    @Override
    public void onStarted(int sensorOrientation, Size size) {
        this.sensorOrientation = sensorOrientation;
        analysisHandler.post(() -> {
            paused = false;
            lumaAverage = -1;
            post(false);
        });
    }

    @Override
    public void onStopped(boolean interrupted) {
        if (!running) {
            return;
        }
        analysisHandler.post(() -> {
            paused = true;
            // cannot see anything now: never keep the chat hidden on stale data (video messages, calls need the screen)
            tracker.reset();
            machine.reset();
            post(true);
        });
    }

    /* Analysis thread */

    private void analyze(YuvFrame frame) {
        final long start = SystemClock.elapsedRealtime();
        final int rotation = (sensorOrientation + PrivacyGuardCamera.displayRotation(context)) % 360;
        final Bitmap bitmap = converter.convert(frame, rotation);

        // auto exposure adapting (lights switched, phone turned toward a window): freeze judgments briefly
        final float luma = converter.meanLuma;
        if (lumaAverage >= 0 && Math.abs(luma - lumaAverage) > PrivacyGuardTuning.EXPOSURE_JUMP) {
            unstableUntil = start + PrivacyGuardTuning.EXPOSURE_SETTLE_MS;
        }
        lumaAverage = lumaAverage < 0 ? luma : lumaAverage + 0.3f * (luma - lumaAverage);

        // the recognized owner needs no mesh between identity checks unless their own gaze matters
        final PrivacyGuardSettings.Snapshot cfg = config;
        final FaceTracker.Track owner = tracker.getOwnerTrack();
        final boolean skipOwnerMesh = owner != null && !cfg.hideWhenOwnerAway
                && start - owner.lastResolved < PrivacyGuardTuning.REVERIFY_OWNER_MS;
        final ArrayList<float[]> tracked = new ArrayList<>();
        for (FaceTracker.Track t : tracker.getTracks()) {
            if (t.confirmed && t.seenThisFrame) {
                tracked.add(new float[]{t.left, t.top, t.right, t.bottom});
            }
        }
        final ArrayList<FaceMeshPipeline.Face> faces = pipeline.process(bitmap, PrivacyGuardTuning.MAX_FACES,
                skipOwnerMesh ? (l, t, r, b) -> FaceTracker.overlap(owner, l, t, r, b) < 0.5f : null, tracked);
        while (observations.size() < faces.size()) {
            observations.add(new FaceTracker.Observation());
        }
        ArrayList<FaceTracker.Observation> current = new ArrayList<>(faces.size());
        for (int i = 0; i < faces.size(); i++) {
            FaceMeshPipeline.Face face = faces.get(i);
            FaceTracker.Observation o = observations.get(i);
            o.left = face.box.left;
            o.top = face.box.top;
            o.right = face.box.right;
            o.bottom = face.box.bottom;
            o.index = i;
            o.hasPose = face.meshValid;
            if (face.meshValid) {
                FaceGeometry.compute(face, o.pose);
            }
            current.add(o);
        }
        tracker.update(current, start, bitmap.getWidth(), cfg, o -> {
            FaceMeshPipeline.Face face = faces.get(o.index);
            if (!face.meshValid || !FaceEmbedder.isUsable(face)) {
                return Float.NaN;
            }
            float[] embedding = embedder.embed(bitmap, face);
            float similarity = FaceEmbedder.similarity(embedding, ownerTemplates);
            java.util.Arrays.fill(embedding, 0);
            return similarity;
        });
        FaceTracker.Summary summary = tracker.summarize(start, cfg);
        lastSummary = summary;

        observation.now = start;
        observation.unstable = start < unstableUntil;
        observation.ownerVisible = summary.ownerVisible;
        observation.ownerLooking = summary.ownerLooking;
        observation.unknownPresent = summary.unknownPresent;
        observation.unknownLooking = summary.unknownLooking;
        machine.update(observation, cfg);
        post(false);

        // spend frames only where they matter: faster while someone else is around
        final boolean active = summary.unknownPresent || machine.getState() == PrivacyGuardStateMachine.State.SUSPICIOUS_GAZE || machine.isProtected();
        long interval = 1000 / (active ? PrivacyGuardTuning.FPS_ACTIVE : PrivacyGuardTuning.FPS_IDLE);
        final long spent = SystemClock.elapsedRealtime() - start;
        if (spent > interval) {
            interval = Math.min(1000 / PrivacyGuardTuning.FPS_MIN, spent + spent / 2);
        }
        frameIntervalMs = interval;

        if (BuildVars.LOGS_ENABLED) {
            framesSinceDiagnostics++;
            frameTimeSinceDiagnostics += spent;
            if (start - lastDiagnostics >= 5000) {
                FileLog.d("PrivacyGuard: stats " + diagnostics(summary) + " fps=" + (framesSinceDiagnostics * 1000 / Math.max(1, start - lastDiagnostics))
                        + " frame=" + (frameTimeSinceDiagnostics / framesSinceDiagnostics) + "ms luma=" + (int) luma);
                lastDiagnostics = start;
                framesSinceDiagnostics = 0;
                frameTimeSinceDiagnostics = 0;
            }
        }
    }

    /** Generic counts for debugging; no biometric data. */
    private static String diagnostics(FaceTracker.Summary s) {
        return "faces=" + s.faces + " owner=" + s.owners + " unknown=" + s.unknowns + " unresolved=" + s.unresolved;
    }

    private void post(boolean paused) {
        final PrivacyGuardStatus status = new PrivacyGuardStatus(
                paused ? PrivacyGuardStateMachine.State.PAUSED : machine.getState(),
                paused ? PrivacyGuardStateMachine.REASON_NONE : machine.getReason());
        if (status.equals(lastPosted)) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("PrivacyGuard: " + status + (paused || lastSummary == null ? "" : " " + diagnostics(lastSummary)));
        }
        lastPosted = status;
        AndroidUtilities.runOnUIThread(() -> listener.onStatus(status));
    }
}
