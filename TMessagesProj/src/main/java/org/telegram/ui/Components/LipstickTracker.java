package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.SystemClock;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.privacyguard.LipLandmarker;
import org.telegram.messenger.privacyguard.OneEuroFilter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * U message: follows the lips in the round video camera for the lipstick effect.
 * <p>
 * {@link #capture} (camera GL thread) draws the raw camera square, the same crop the round video shows but without
 * effects, into a small framebuffer and reads it back whenever no detection is running. A background thread runs the
 * MediaPipe Face Landmarker in video-tracking mode on it, smooths the lip contours with One Euro filters and measures
 * the lip and skin colors. The old local mesh is kept only as a fallback when MediaPipe cannot initialize.
 * <p>
 * Every GL context that draws the effect (camera preview, video encoder, settings preview) renders its own feathered
 * lip mask from the latest contours with a {@link Mask}, in the gl_FragCoord space of the round frame. Preview and
 * recording therefore get the same lipstick, and no context samples a texture another one is writing.
 */
public class LipstickTracker {

    static final int CONTOUR = 11;
    /** Contours in {@link State#points}: upper outer, upper inner, lower outer, lower inner. */
    private static final int[][] CONTOURS = {LipLandmarker.UPPER_OUTER, LipLandmarker.UPPER_INNER, LipLandmarker.LOWER_OUTER, LipLandmarker.LOWER_INNER};
    private static final int POINT_COUNT = CONTOUR * 4;

    private static final int CAPTURE_SIZE = 256;
    private static final long MIN_CAPTURE_INTERVAL_MS = 16;

    // One Euro filter in frame units (0..1): steady while still, little lag while talking or turning the head
    private static final float FILTER_MIN_CUTOFF = 3.2f;
    private static final float FILTER_BETA = 22f;
    private static final float FILTER_DERIVATIVE_CUTOFF = 1f;

    private static final long FADE_IN_MS = 150;
    private static final long LOST_HOLD_MS = 200;
    private static final long FADE_OUT_MS = 250;
    /** Contours are from a frame captured a little earlier: move them along with the mouth for at most this long. */
    private static final long MAX_PREDICTION_MS = 80;
    private static final float PREDICTION = 0.8f;

    /** Immutable once published. */
    static final class State {
        /** x, y in 0..1 of the round frame, y down. */
        final float[] points = new float[POINT_COUNT * 2];
        /** U message: every face mesh landmark ({@link FaceMeshData}), x, y in 0..1 of the round frame, y down. */
        final float[] face = new float[FaceMeshData.LANDMARKS * 2];
        float centerX, centerY;
        float leftCheekX, leftCheekY, rightCheekX, rightCheekY, faceWidth;
        float faceCenterX, faceCenterY, faceAxisXX, faceAxisXY, faceAxisYX, faceAxisYY, faceRadiusX, faceRadiusY;
        float leftEyeX, leftEyeY, rightEyeX, rightEyeY, eyeRadiusX, eyeRadiusY;
        /** Mouth motion in frame units per ms. */
        float velocityX, velocityY;
        /** Capture time of the frame, {@link SystemClock#elapsedRealtime}. */
        long time;
        long firstSeen;
        float mouthWidth;
        /** 0 closed .. 1 open far enough to show teeth. */
        float openness;
        float skinCb, skinCr, lipCb, lipCr;
        /** How well lip and skin colors can be told apart, 0..1. */
        float colorConfidence;
    }

    volatile State state;
    private volatile boolean busy;
    private volatile boolean released;
    private long lastCaptureTime;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FaceLandmarker mediaPipeLandmarker; // created and used on executor thread
    private LipLandmarker fallbackLandmarker; // executor thread
    private boolean landmarkerFailed;
    private final float[] landmarks = new float[LipLandmarker.LANDMARK_COUNT * 2];
    private final OneEuroFilter[] filters = new OneEuroFilter[POINT_COUNT * 2];
    private final OneEuroFilter[] faceFilters = new OneEuroFilter[FaceMeshData.LANDMARKS * 2];

    private final Bitmap captureBitmap = Bitmap.createBitmap(CAPTURE_SIZE, CAPTURE_SIZE, Bitmap.Config.ARGB_8888);
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(CAPTURE_SIZE * CAPTURE_SIZE * 4).order(ByteOrder.nativeOrder());
    private int captureProgram, capturePositionHandle, captureTextureHandle, captureMatrixHandle, captureMvpHandle;
    private final float[] identity = new float[16];
    private final int[] captureFbo = new int[1];
    private final int[] captureTexture = new int[1];
    private final FloatBuffer captureVertices = floatBuffer(new float[]{
            -1, -1,
            1, -1,
            -1, 1,
            1, 1
    });

    private static final String CAPTURE_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            // the frame as drawn, upside down: image top lands in framebuffer row 0, so read back rows are top down
            "   gl_Position = uMVPMatrix * aPosition;\n" +
            "   gl_Position.y = -gl_Position.y;\n" +
            "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String CAPTURE_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "   gl_FragColor = vec4(texture2D(sTexture, vTextureCoord).rgb, 1.0);\n" +
            "}\n";

    /**
     * Call on the camera GL thread after the frame is drawn. Uses the same texture coordinates and transforms as the
     * round frame, so the contours line up with what is drawn.
     *
     * @param mvpMatrix vertex transform of the frame's full screen quad, null for none
     * @param textureCoords 4 x (s, t), triangle strip order bottom left, bottom right, top left, top right
     */
    public void capture(int cameraTexture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords) {
        final long now = SystemClock.elapsedRealtime();
        if (busy || released || captureBitmap.isRecycled() || cameraTexture == 0 || textureCoords == null || now - lastCaptureTime < MIN_CAPTURE_INTERVAL_MS) {
            return;
        }
        if (!initCapture()) {
            return;
        }
        lastCaptureTime = now;

        GlState saved = GlState.save();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo[0]);
        GLES20.glViewport(0, 0, CAPTURE_SIZE, CAPTURE_SIZE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(captureProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glUniformMatrix4fv(captureMatrixHandle, 1, false, stMatrix, 0);
        GLES20.glUniformMatrix4fv(captureMvpHandle, 1, false, mvpMatrix != null ? mvpMatrix : identity, 0);
        captureVertices.position(0);
        GLES20.glVertexAttribPointer(capturePositionHandle, 2, GLES20.GL_FLOAT, false, 8, captureVertices);
        GLES20.glEnableVertexAttribArray(capturePositionHandle);
        textureCoords.position(0);
        GLES20.glVertexAttribPointer(captureTextureHandle, 2, GLES20.GL_FLOAT, false, 8, textureCoords);
        GLES20.glEnableVertexAttribArray(captureTextureHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(capturePositionHandle);
        GLES20.glDisableVertexAttribArray(captureTextureHandle);
        readBuffer.rewind();
        GLES20.glReadPixels(0, 0, CAPTURE_SIZE, CAPTURE_SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        saved.restore();

        readBuffer.rewind();
        captureBitmap.copyPixelsFromBuffer(readBuffer);
        busy = true;
        executor.execute(() -> {
            try {
                process(now);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                busy = false;
            }
        });
    }

    /** Call on the GL thread that called {@link #capture}. */
    public void release() {
        released = true;
        if (captureProgram != 0) {
            GLES20.glDeleteProgram(captureProgram);
            captureProgram = 0;
        }
        if (captureFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, captureFbo, 0);
            captureFbo[0] = 0;
        }
        if (captureTexture[0] != 0) {
            GLES20.glDeleteTextures(1, captureTexture, 0);
            captureTexture[0] = 0;
        }
        executor.execute(() -> {
            if (mediaPipeLandmarker != null) {
                mediaPipeLandmarker.close();
                mediaPipeLandmarker = null;
            }
            if (fallbackLandmarker != null) {
                fallbackLandmarker.close();
                fallbackLandmarker = null;
            }
            captureBitmap.recycle();
            executor.shutdown();
        });
    }

    private boolean initCapture() {
        if (captureProgram != 0) {
            return true;
        }
        captureProgram = createProgram(CAPTURE_VERTEX_SHADER, CAPTURE_FRAGMENT_SHADER);
        if (captureProgram == 0) {
            released = true;
            return false;
        }
        capturePositionHandle = GLES20.glGetAttribLocation(captureProgram, "aPosition");
        captureTextureHandle = GLES20.glGetAttribLocation(captureProgram, "aTextureCoord");
        captureMatrixHandle = GLES20.glGetUniformLocation(captureProgram, "uSTMatrix");
        captureMvpHandle = GLES20.glGetUniformLocation(captureProgram, "uMVPMatrix");
        android.opengl.Matrix.setIdentityM(identity, 0);
        createTarget(captureFbo, captureTexture, CAPTURE_SIZE);
        return true;
    }

    // executor thread
    private void process(long time) {
        if (released) {
            return;
        }
        if (mediaPipeLandmarker == null && fallbackLandmarker == null) {
            if (landmarkerFailed) {
                return;
            }
            initializeLandmarker();
        }
        boolean detected = false;
        if (mediaPipeLandmarker != null) {
            // not closed: MPImage.close() recycles the wrapped bitmap, and captureBitmap is reused for every frame
            MPImage image;
            try {
                image = new BitmapImageBuilder(captureBitmap).build();
                FaceLandmarkerResult result = mediaPipeLandmarker.detectForVideo(image, time);
                if (!result.faceLandmarks().isEmpty()) {
                    java.util.List<NormalizedLandmark> face = result.faceLandmarks().get(0);
                    final int count = Math.min(LipLandmarker.LANDMARK_COUNT, face.size());
                    for (int i = 0; i < count; i++) {
                        NormalizedLandmark point = face.get(i);
                        landmarks[i * 2] = point.x() * CAPTURE_SIZE;
                        landmarks[i * 2 + 1] = point.y() * CAPTURE_SIZE;
                    }
                    detected = count >= LipLandmarker.LANDMARK_COUNT;
                }
            } catch (Throwable e) {
                FileLog.e(e);
                try {
                    mediaPipeLandmarker.close();
                } catch (Throwable ignore) {
                }
                mediaPipeLandmarker = null;
                initializeFallback();
            }
        }
        if (!detected && fallbackLandmarker != null) {
            detected = fallbackLandmarker.detect(captureBitmap, landmarks);
        }
        if (!detected) {
            return; // the last state fades out by itself
        }

        final State previous = state;
        final boolean restart = previous == null || time - previous.time > LOST_HOLD_MS + FADE_OUT_MS;
        if (restart) {
            for (int i = 0; i < filters.length; i++) {
                filters[i] = new OneEuroFilter(FILTER_MIN_CUTOFF, FILTER_BETA, FILTER_DERIVATIVE_CUTOFF);
            }
            for (int i = 0; i < faceFilters.length; i++) {
                faceFilters[i] = new OneEuroFilter(FILTER_MIN_CUTOFF, FILTER_BETA, FILTER_DERIVATIVE_CUTOFF);
            }
        }
        final float scale = 1f / CAPTURE_SIZE;
        final State s = new State();
        s.time = time;
        s.firstSeen = restart ? time : previous.firstSeen;
        float cx = 0, cy = 0;
        for (int c = 0, p = 0; c < CONTOURS.length; c++) {
            for (int k = 0; k < CONTOUR; k++, p++) {
                final int index = CONTOURS[c][k];
                s.points[p * 2] = filters[p * 2].filter(landmarks[index * 2] * scale, time);
                s.points[p * 2 + 1] = filters[p * 2 + 1].filter(landmarks[index * 2 + 1] * scale, time);
                cx += s.points[p * 2];
                cy += s.points[p * 2 + 1];
            }
        }
        s.centerX = cx / POINT_COUNT;
        s.centerY = cy / POINT_COUNT;
        for (int i = 0; i < s.face.length; i++) {
            s.face[i] = faceFilters[i].filter(landmarks[i] * scale, time);
        }
        s.leftCheekX = landmarks[205 * 2] * scale;
        s.leftCheekY = landmarks[205 * 2 + 1] * scale;
        s.rightCheekX = landmarks[425 * 2] * scale;
        s.rightCheekY = landmarks[425 * 2 + 1] * scale;
        s.faceWidth = (float) Math.hypot(
                (landmarks[454 * 2] - landmarks[234 * 2]) * scale,
                (landmarks[454 * 2 + 1] - landmarks[234 * 2 + 1]) * scale);
        final float sideX = (landmarks[454 * 2] - landmarks[234 * 2]) * scale;
        final float sideY = (landmarks[454 * 2 + 1] - landmarks[234 * 2 + 1]) * scale;
        final float sideLength = Math.max(1e-4f, (float) Math.hypot(sideX, sideY));
        s.faceAxisXX = sideX / sideLength;
        s.faceAxisXY = sideY / sideLength;
        s.faceAxisYX = -s.faceAxisXY;
        s.faceAxisYY = s.faceAxisXX;
        final float foreheadX = landmarks[10 * 2] * scale;
        final float foreheadY = landmarks[10 * 2 + 1] * scale;
        final float chinX = landmarks[152 * 2] * scale;
        final float chinY = landmarks[152 * 2 + 1] * scale;
        if ((chinX - foreheadX) * s.faceAxisYX + (chinY - foreheadY) * s.faceAxisYY < 0) {
            s.faceAxisYX = -s.faceAxisYX;
            s.faceAxisYY = -s.faceAxisYY;
        }
        s.faceCenterX = (foreheadX + chinX) * 0.5f;
        s.faceCenterY = (foreheadY + chinY) * 0.5f;
        s.faceRadiusX = s.faceWidth * 0.51f;
        s.faceRadiusY = (float) Math.hypot(chinX - foreheadX, chinY - foreheadY) * 0.54f;
        s.leftEyeX = (landmarks[33 * 2] + landmarks[133 * 2]) * 0.5f * scale;
        s.leftEyeY = (landmarks[33 * 2 + 1] + landmarks[133 * 2 + 1]) * 0.5f * scale;
        s.rightEyeX = (landmarks[362 * 2] + landmarks[263 * 2]) * 0.5f * scale;
        s.rightEyeY = (landmarks[362 * 2 + 1] + landmarks[263 * 2 + 1]) * 0.5f * scale;
        s.eyeRadiusX = ((float) Math.hypot(
                landmarks[33 * 2] - landmarks[133 * 2],
                landmarks[33 * 2 + 1] - landmarks[133 * 2 + 1]) * scale) * 0.72f;
        s.eyeRadiusY = s.faceRadiusY * 0.12f;
        if (!restart) {
            final float dt = Math.max(1, time - previous.time);
            final float max = 0.004f;
            s.velocityX = previous.velocityX * 0.5f + clamp((s.centerX - previous.centerX) / dt, -max, max) * 0.5f;
            s.velocityY = previous.velocityY * 0.5f + clamp((s.centerY - previous.centerY) / dt, -max, max) * 0.5f;
        }

        // upper outer 0 and 10 are the corners, inner 5 the middle of the upper and lower lip
        s.mouthWidth = distance(s.points, index(0, 0), index(0, CONTOUR - 1));
        final float gap = distance(s.points, index(1, CONTOUR / 2), index(3, CONTOUR / 2));
        s.openness = smoothstep(0.03f, 0.10f, gap / Math.max(s.mouthWidth, 1e-3f));

        measureColors(s, restart ? null : previous);
        state = s;
    }

    private void initializeLandmarker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            initializeFallback();
            return;
        }
        try {
            mediaPipeLandmarker = createMediaPipeLandmarker(Delegate.GPU);
            return;
        } catch (Throwable gpuError) {
            FileLog.e(gpuError);
        }
        try {
            mediaPipeLandmarker = createMediaPipeLandmarker(Delegate.CPU);
            return;
        } catch (Throwable cpuError) {
            FileLog.e(cpuError);
        }
        initializeFallback();
    }

    private FaceLandmarker createMediaPipeLandmarker(Delegate delegate) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setDelegate(delegate)
                .setModelAssetPath("face_landmarker.task")
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.45f)
                .setMinFacePresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .build();
        return FaceLandmarker.createFromOptions(ApplicationLoader.applicationContext, options);
    }

    private void initializeFallback() {
        if (fallbackLandmarker != null || landmarkerFailed) {
            return;
        }
        try {
            fallbackLandmarker = new LipLandmarker();
        } catch (Throwable e) {
            FileLog.e(e);
            landmarkerFailed = true;
        }
    }

    /** Mean chroma of the lip body and of the cheeks next to the mouth, from the unfiltered landmarks of this frame. */
    private void measureColors(State s, State previous) {
        float lipCb = 0, lipCr = 0, skinCb = 0, skinCr = 0;
        int lipCount = 0, skinCount = 0;
        final float[] chroma = new float[2];
        for (int lip = 0; lip < 2; lip++) {
            final int[] outer = lip == 0 ? LipLandmarker.UPPER_OUTER : LipLandmarker.LOWER_OUTER;
            final int[] inner = lip == 0 ? LipLandmarker.UPPER_INNER : LipLandmarker.LOWER_INNER;
            for (int k = 2; k <= CONTOUR - 3; k++) {
                // 40% into the lip: away from the skin border and the shadow of the inner line
                final float x = landmarks[outer[k] * 2] * 0.6f + landmarks[inner[k] * 2] * 0.4f;
                final float y = landmarks[outer[k] * 2 + 1] * 0.6f + landmarks[inner[k] * 2 + 1] * 0.4f;
                if (sampleChroma(x, y, chroma)) {
                    lipCb += chroma[0];
                    lipCr += chroma[1];
                    lipCount++;
                }
            }
        }
        for (int index : LipLandmarker.SKIN_SAMPLES) {
            if (sampleChroma(landmarks[index * 2], landmarks[index * 2 + 1], chroma)) {
                skinCb += chroma[0];
                skinCr += chroma[1];
                skinCount++;
            }
        }
        if (lipCount == 0 || skinCount == 0) {
            if (previous != null) {
                s.lipCb = previous.lipCb;
                s.lipCr = previous.lipCr;
                s.skinCb = previous.skinCb;
                s.skinCr = previous.skinCr;
                s.colorConfidence = previous.colorConfidence;
            }
            return;
        }
        lipCb /= lipCount;
        lipCr /= lipCount;
        skinCb /= skinCount;
        skinCr /= skinCount;
        if (previous != null) {
            // colors change slowly: keeps the edge refinement from flickering
            final float k = 0.25f;
            lipCb = previous.lipCb + (lipCb - previous.lipCb) * k;
            lipCr = previous.lipCr + (lipCr - previous.lipCr) * k;
            skinCb = previous.skinCb + (skinCb - previous.skinCb) * k;
            skinCr = previous.skinCr + (skinCr - previous.skinCr) * k;
        }
        s.lipCb = lipCb;
        s.lipCr = lipCr;
        s.skinCb = skinCb;
        s.skinCr = skinCr;
        s.colorConfidence = smoothstep(0.012f, 0.035f, (float) Math.hypot(lipCb - skinCb, lipCr - skinCr));
    }

    /** 3x3 mean around a capture pixel as YCbCr chroma. */
    private boolean sampleChroma(float x, float y, float[] out) {
        final int px = Math.round(x), py = Math.round(y);
        if (px < 1 || py < 1 || px >= CAPTURE_SIZE - 1 || py >= CAPTURE_SIZE - 1) {
            return false;
        }
        float r = 0, g = 0, b = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int c = captureBitmap.getPixel(px + dx, py + dy);
                r += (c >> 16) & 0xff;
                g += (c >> 8) & 0xff;
                b += c & 0xff;
            }
        }
        r /= 9f * 255f;
        g /= 9f * 255f;
        b /= 9f * 255f;
        out[0] = -0.1687f * r - 0.3313f * g + 0.5f * b;
        out[1] = 0.5f * r - 0.4187f * g - 0.0813f * b;
        return true;
    }

    private static int index(int contour, int k) {
        return contour * CONTOUR + k;
    }

    private static float distance(float[] points, int a, int b) {
        return (float) Math.hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1]);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        final float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Soft lip mask of one GL context: red = coverage, green = where the lip color refines it (near the borders). */
    public static class Mask {

        private static final int SIZE = 256;
        private static final int RINGS = 5;
        private static final int FLOATS_PER_VERTEX = 4; // x, y, coverage, refinement
        private static final int MAX_VERTICES = 2 * (RINGS - 1) * (CONTOUR - 1) * 6;

        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;\n" +
                "attribute vec2 aValue;\n" +
                "varying vec2 vValue;\n" +
                "void main() {\n" +
                "   gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "   vValue = aValue;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n" +
                "varying vec2 vValue;\n" +
                "void main() {\n" +
                "   float a = clamp(vValue.x, 0.0, 1.0);\n" +
                "   gl_FragColor = vec4(a * a * (3.0 - 2.0 * a), clamp(vValue.y, 0.0, 1.0), 0.0, 1.0);\n" +
                "}\n";

        private int program, positionHandle, valueHandle;
        private final int[] fbo = new int[1];
        private final int[] texture = new int[1];
        private final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
        private final FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] rings = new float[RINGS * CONTOUR * FLOATS_PER_VERTEX];
        private boolean visible;
        private boolean failed;

        // lip / skin color model of the rendered state, for the effect uniforms
        float skinCb, skinCr, lipCb, lipCr, colorConfidence;

        /** Mask of the last {@link #render}, 0 when there are no lips to color. */
        public int getTexture() {
            return visible ? texture[0] : 0;
        }

        /**
         * Renders the mask for the current lip position; call on this Mask's GL thread before drawing the frame.
         * Keeps the bound framebuffer, viewport, blending and clear color.
         *
         * @param softness 0..1, width of the feathered edge
         */
        public boolean render(LipstickTracker tracker, float softness) {
            visible = false;
            final State s = tracker != null ? tracker.state : null;
            if (s == null || failed) {
                return false;
            }
            final long now = SystemClock.elapsedRealtime();
            final float fadeIn = clamp((now - s.firstSeen) / (float) FADE_IN_MS, 0f, 1f);
            final float fadeOut = 1f - clamp((now - s.time - LOST_HOLD_MS) / (float) FADE_OUT_MS, 0f, 1f);
            final float visibility = fadeIn * fadeOut;
            if (visibility <= 0.01f || !init()) {
                return false;
            }
            final float predict = Math.min(MAX_PREDICTION_MS, Math.max(0, now - s.time)) * PREDICTION;
            final float dx = s.velocityX * predict, dy = s.velocityY * predict;

            int count = buildLip(s, 0, 1, dx, dy, softness, visibility, 0);
            count = buildLip(s, 2, 3, dx, dy, softness, visibility, count);

            GlState saved = GlState.save();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glViewport(0, 0, SIZE, SIZE);
            GLES20.glClearColor(0, 0, 0, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // upper and lower lip meet at the corners: add up there instead of cutting one off
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glUseProgram(program);
            vertexBuffer.clear();
            vertexBuffer.put(vertices, 0, count * FLOATS_PER_VERTEX).position(0);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionHandle);
            vertexBuffer.position(2);
            GLES20.glVertexAttribPointer(valueHandle, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, vertexBuffer);
            GLES20.glEnableVertexAttribArray(valueHandle);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(valueHandle);
            saved.restore();

            skinCb = s.skinCb;
            skinCr = s.skinCr;
            lipCb = s.lipCb;
            lipCr = s.lipCr;
            colorConfidence = s.colorConfidence;
            visible = true;
            return true;
        }

        /**
         * One lip as rings from outside in, each ring one point per contour column:
         * feather edge (0) -> lip border from the mesh (half) -> lip body (full) -> inner line (full when the mouth is
         * closed, fading to nothing when it opens, so teeth and the inside of the mouth stay untouched).
         */
        private int buildLip(State s, int outerContour, int innerContour, float dx, float dy, float softness, float visibility, int vertex) {
            final float feather = s.mouthWidth * (0.02f + 0.06f * softness);
            final float open = s.openness;
            final float cx = s.centerX + dx, cy = s.centerY + dy;
            for (int k = 0; k < CONTOUR; k++) {
                final int o = index(outerContour, k), i = index(innerContour, k);
                final float ox = s.points[o * 2] + dx, oy = s.points[o * 2 + 1] + dy;
                final float ix = s.points[i * 2] + dx, iy = s.points[i * 2 + 1] + dy;
                float nx = ox - cx, ny = oy - cy;
                final float length = (float) Math.hypot(nx, ny);
                if (length > 1e-5f) {
                    nx /= length;
                    ny /= length;
                }
                setRing(0, k, ox + nx * feather, oy + ny * feather, 0f, 1f);
                setRing(1, k, ox, oy, 0.5f * visibility, 1f);
                setRing(2, k, ox + (ix - ox) * 0.3f, oy + (iy - oy) * 0.3f, visibility, 0.3f);
                setRing(3, k, ox + (ix - ox) * 0.75f, oy + (iy - oy) * 0.75f, visibility, 0.3f * open);
                setRing(4, k, ix, iy, (1f - open) * visibility, open);
            }
            for (int r = 0; r < RINGS - 1; r++) {
                for (int k = 0; k < CONTOUR - 1; k++) {
                    vertex = putVertex(vertex, r, k);
                    vertex = putVertex(vertex, r, k + 1);
                    vertex = putVertex(vertex, r + 1, k);
                    vertex = putVertex(vertex, r + 1, k);
                    vertex = putVertex(vertex, r, k + 1);
                    vertex = putVertex(vertex, r + 1, k + 1);
                }
            }
            return vertex;
        }

        // frame space (0..1, y down) -> clip space of the mask, which matches gl_FragCoord / viewport of the frame
        private void setRing(int ring, int k, float x, float y, float coverage, float refine) {
            final int o = (ring * CONTOUR + k) * FLOATS_PER_VERTEX;
            rings[o] = x * 2f - 1f;
            rings[o + 1] = 1f - y * 2f;
            rings[o + 2] = coverage;
            rings[o + 3] = refine;
        }

        private int putVertex(int vertex, int ring, int k) {
            System.arraycopy(rings, (ring * CONTOUR + k) * FLOATS_PER_VERTEX, vertices, vertex * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            return vertex + 1;
        }

        private boolean init() {
            if (program != 0) {
                return true;
            }
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                failed = true;
                return false;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            valueHandle = GLES20.glGetAttribLocation(program, "aValue");
            createTarget(fbo, texture, SIZE);
            return true;
        }

        /** Call on this Mask's GL thread. */
        public void release() {
            visible = false;
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (fbo[0] != 0) {
                GLES20.glDeleteFramebuffers(1, fbo, 0);
                fbo[0] = 0;
            }
            if (texture[0] != 0) {
                GLES20.glDeleteTextures(1, texture, 0);
                texture[0] = 0;
            }
        }
    }

    /** GL state the offscreen passes change, restored so the frame drawing around them is unaffected. */
    private static final class GlState {
        private final int[] framebuffer = new int[1];
        private final int[] viewport = new int[4];
        private final int[] blendFunc = new int[4];
        private final float[] clearColor = new float[4];
        private final int[] program = new int[1];
        private boolean blend;

        static GlState save() {
            GlState s = new GlState();
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, s.framebuffer, 0);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, s.viewport, 0);
            GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, s.blendFunc, 0);
            GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, s.blendFunc, 1);
            GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, s.blendFunc, 2);
            GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, s.blendFunc, 3);
            GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, s.clearColor, 0);
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, s.program, 0);
            s.blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
            return s;
        }

        void restore() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glBlendFuncSeparate(blendFunc[0], blendFunc[1], blendFunc[2], blendFunc[3]);
            GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            GLES20.glUseProgram(program[0]);
            if (blend) {
                GLES20.glEnable(GLES20.GL_BLEND);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
        }
    }

    private static void createTarget(int[] fbo, int[] texture, int size) {
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        final int[] previous = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previous, 0);
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture[0], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous[0]);
    }

    static int createProgramForMask(String vertexSource, String fragmentSource) {
        return createProgram(vertexSource, fragmentSource);
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        final int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        final int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        final int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("LipstickTracker link failed " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int loadShader(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("LipstickTracker shader failed " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
}
