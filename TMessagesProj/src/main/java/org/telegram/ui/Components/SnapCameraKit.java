package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.text.TextUtils;
import android.view.Surface;

import com.snap.camerakit.ImageProcessor;
import com.snap.camerakit.ImageProcessors;
import com.snap.camerakit.Session;
import com.snap.camerakit.Sessions;
import com.snap.camerakit.lenses.LensesComponent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.Closeable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message: Snapchat Lenses for round video messages, rendered by Snap Camera Kit.
 * <p>
 * One Camera Kit session for the app loads the lenses of the lens group (SNAP_LENS_GROUP_ID in local.properties).
 * A {@link Renderer} on the camera GL thread draws each camera frame, as the round preview shows it, into Camera Kit's
 * input surface and copies Camera Kit's latest output into a texture in gl_FragCoord space, which the preview and
 * encoder shaders use in place of the camera color (see {@link RoundVideoEffects#GLSL}). The camera itself, the dual
 * camera switch and the recording stay as they are.
 */
public class SnapCameraKit {

    private static final String TAG = "SnapCameraKit";

    /**
     * "Eyes and Lips": hides the face of someone who would not show it. While this lens is selected the camera frame
     * must never be shown or recorded, see {@link RoundVideoEffects.Look#covered}.
     */
    public static final String COVER_LENS_ID = "8d6cba29-19a7-4a87-854f-8d56ad511c8c";

    private static Session session; // UI thread
    private static Closeable lensesObserver; // UI thread
    private static volatile List<LensesComponent.Lens> lenses = Collections.emptyList();
    private static final ConcurrentHashMap<String, Bitmap> icons = new ConcurrentHashMap<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>(); // UI thread
    private static boolean loading, failed;

    /** Camera Kit can run here: a token in local.properties, a lens group and an OpenGL ES 3 capable device. */
    public static boolean isSupported() {
        if (!BuildConfig.SNAP_CAMERAKIT_ENABLED || TextUtils.isEmpty(BuildConfig.SNAP_LENS_GROUP_ID) || failed) {
            return false;
        }
        try {
            return Sessions.supported(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            return false;
        }
    }

    /** Starts the session and loads the lens list; call on the UI thread. */
    public static void prepare() {
        if (session != null || loading || !isSupported()) {
            return;
        }
        loading = true;
        try {
            session = Sessions.newBuilder(ApplicationLoader.applicationContext)
                    .handleErrorsWith(e -> FileLog.e(TAG + " error", e))
                    .build();
            lensesObserver = session.getLenses().getRepository().observe(
                    new LensesComponent.Repository.QueryCriteria.Available(BuildConfig.SNAP_LENS_GROUP_ID),
                    result -> {
                        if (!(result instanceof LensesComponent.Repository.Result.Some)) {
                            // no answer (offline, for one): the list we already show stays
                            AndroidUtilities.runOnUIThread(() -> {
                                loading = false;
                                notifyListeners();
                            });
                            return;
                        }
                        final List<LensesComponent.Lens> list = new ArrayList<>(((LensesComponent.Repository.Result.Some) result).getLenses());
                        for (LensesComponent.Lens lens : list) {
                            android.util.Log.i(TAG, "lens id=" + lens.getId() + " name=" + lens.getName());
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            lenses = list;
                            loading = false;
                            loadIcons(list);
                            notifyListeners();
                        });
                    });
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            loading = false;
            session = null;
        }
    }

    /**
     * Asks Snap for the lens group again, so lenses added or removed there show without restarting the app. The
     * repository answers for the lifetime of a session, so this starts a new one. Call on the UI thread while no camera
     * is rendering a lens; the current list stays until the answer arrives.
     */
    public static void refresh() {
        loading = false; // a request that never answered must not block this one
        if (lensesObserver != null) {
            try {
                lensesObserver.close();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            lensesObserver = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            session = null;
        }
        prepare();
    }

    /** Called on the UI thread whenever the lens list or a lens icon changes. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    public static int getLensCount() {
        return lenses.size();
    }

    private static LensesComponent.Lens getLens(int index) {
        final List<LensesComponent.Lens> list = lenses;
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public static String getLensId(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? lens.getId() : null;
    }

    public static String getLensName(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? lens.getName() : null;
    }

    /** Lens icon for the carousel, null until downloaded. */
    public static Bitmap getLensIcon(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? icons.get(lens.getId()) : null;
    }

    /** Position of a lens in the list, -1 when it is not there or the list has not loaded. */
    public static int indexOfLens(String id) {
        final List<LensesComponent.Lens> list = lenses;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static LensesComponent.Lens findLens(String id) {
        for (LensesComponent.Lens lens : lenses) {
            if (lens.getId().equals(id)) {
                return lens;
            }
        }
        return null;
    }

    private static void loadIcons(List<LensesComponent.Lens> list) {
        for (LensesComponent.Lens lens : list) {
            final String id = lens.getId(), uri = lens.getIconUri();
            if (icons.containsKey(id) || TextUtils.isEmpty(uri)) {
                continue;
            }
            Utilities.globalQueue.postRunnable(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(uri).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    try (InputStream in = connection.getInputStream()) {
                        final Bitmap bitmap = BitmapFactory.decodeStream(in);
                        if (bitmap != null) {
                            icons.put(id, bitmap);
                            AndroidUtilities.runOnUIThread(SnapCameraKit::notifyListeners);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        }
    }

    private static Renderer lensOwner; // UI thread

    /**
     * Applies the lens, null clears it; any thread. A lens that is not found is cleared and never reports applied.
     * A renderer that was released late cannot clear the lens of the renderer that came after it.
     */
    private static void applyLens(Renderer owner, String id, com.snap.camerakit.common.Consumer<Boolean> onApplied) {
        AndroidUtilities.runOnUIThread(() -> {
            if (session == null) {
                return;
            }
            try {
                if (id == null && lensOwner != owner) {
                    return;
                }
                lensOwner = owner;
                final LensesComponent.Lens lens = id != null ? findLens(id) : null;
                if (lens != null) {
                    session.getLenses().getProcessor().apply(lens, applied -> {
                        android.util.Log.i(TAG, "applied " + lens.getName() + " " + applied);
                        if (onApplied != null) {
                            onApplied.accept(applied);
                        }
                    });
                } else {
                    session.getLenses().getProcessor().clear(cleared -> {});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static Session getSession() {
        return session;
    }

    /**
     * Feeds one GL thread's camera frames through Camera Kit. All methods run on that thread, whose context must be
     * current; the frame is passed exactly as the round preview draws it, so Camera Kit gets it upright.
     */
    public static final class Renderer {

        private static final int SIZE = 720;
        /** View angle across the frame's short side when the camera does not report its own. */
        private static final float FIELD_OF_VIEW = 55f;

        /** Lens frame size: square for round videos, the camera's aspect for story videos. */
        private final int width, height;

        public Renderer() {
            this(SIZE, SIZE);
        }

        public Renderer(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "   gl_Position = uMVPMatrix * aPosition;\n" +
                "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "uniform sampler2D originalFrame;\n" +
                "uniform vec2 frameSize;\n" +
                "uniform float lensStrength;\n" +
                "void main() {\n" +
                "   vec3 color = texture2D(sTexture, vTextureCoord).rgb;\n" +
                "   if (lensStrength < 1.0) color = mix(texture2D(originalFrame, gl_FragCoord.xy / frameSize).rgb, color, lensStrength);\n" +
                "   gl_FragColor = vec4(color, 1.0);\n" +
                "}\n";

        private final FloatBuffer quad = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        private final FloatBuffer fullTexture = floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        private final float[] identity = new float[16];
        private final float[] outputMatrix = new float[16];
        private final int[] outputFbo = new int[1], outputTexture = new int[1], lensTexture = new int[1];
        private int program, positionHandle, textureHandle, stHandle, mvpHandle;
        private int originalHandle, strengthHandle, frameSizeHandle;
        // Keep bounded GPU copies of input frames; never mix a live camera frame with delayed lens output.
        private static final int HISTORY_SIZE = 8;
        private final int[] originalTextures = new int[HISTORY_SIZE];
        private final int[] originalFbo = new int[1];
        private final long[] originalTimestamps = new long[HISTORY_SIZE];
        private int nextOriginal;
        private volatile long processingTimestamp;
        private boolean syncReported;
        private int syncMisses;

        private SurfaceTexture inputTexture, lensOutput;
        private Surface inputSurface, lensSurface;
        private EGLSurface inputEglSurface = EGL14.EGL_NO_SURFACE;
        private Closeable inputConnection, outputConnection;
        private Session connectedSession;
        private boolean connectedFront;
        private volatile boolean lensFrameAvailable;
        private String appliedLens;
        /** When Camera Kit confirmed {@link #appliedLens}, 0 before that. Older frames pass the camera through unchanged. */
        private volatile long lensReadyAt;
        private volatile int lensRequest;
        private boolean hasOutput;
        private boolean swapIntervalSet;
        private boolean failed;
        private volatile float shortSideFieldOfView = FIELD_OF_VIEW;

        /**
         * The camera's real view angle across the frame's short side. The face tracker fits its 3D head with this
         * perspective: a wrong angle hardly shows on a frontal face, but misplaces the mesh on a turned head.
         */
        public void setShortSideFieldOfView(float degrees) {
            if (degrees > 0 && degrees < 180) {
                shortSideFieldOfView = degrees;
            }
        }

        private float fieldOfViewAcross(int size) {
            final double halfShort = Math.toRadians(shortSideFieldOfView / 2f);
            return (float) Math.toDegrees(2 * Math.atan(Math.tan(halfShort) * size / Math.max(1, Math.min(width, height))));
        }

        /**
         * Feeds the current camera frame to Camera Kit and returns its latest lens frame.
         *
         * @param front the frame shows the front camera
         * @return texture with the lens frame in gl_FragCoord space, 0 while not available
         */
        public int render(String lensId, int cameraTexture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords, boolean front) {
            return render(lensId, cameraTexture, stMatrix, mvpMatrix, textureCoords, front, 100);
        }

        public int render(String lensId, int cameraTexture, float[] stMatrix, float[] mvpMatrix,
                          FloatBuffer textureCoords, boolean front, int intensity) {
            final Session session = getSession();
            if (failed || lensId == null || cameraTexture == 0 || textureCoords == null || session == null) {
                return 0;
            }
            final GlState saved = GlState.save();
            try {
                if (program == 0 && !init()) {
                    return 0;
                }
                if (connectedSession != session) {
                    appliedLens = null; // a new session has no lens applied yet
                }
                if (connectedSession != session || connectedFront != front) {
                    connect(session, front);
                }
                if (!lensId.equals(appliedLens)) {
                    appliedLens = lensId;
                    hasOutput = false;
                    lensReadyAt = 0;
                    final int request = ++lensRequest;
                    applyLens(this, lensId, applied -> {
                        if (applied && request == lensRequest) {
                            lensReadyAt = System.nanoTime();
                        }
                    });
                }

                final long timestamp = System.nanoTime();
                // Snapshot exactly what is sent to Snap, with the same presentation timestamp.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, originalFbo[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, originalTextures[nextOriginal], 0);
                GLES20.glViewport(0, 0, width, height);
                drawExternal(cameraTexture, stMatrix, mvpMatrix != null ? mvpMatrix : identity, textureCoords);
                originalTimestamps[nextOriginal] = timestamp;
                nextOriginal = (nextOriginal + 1) % HISTORY_SIZE;

                // the frame as the round preview draws it, into Camera Kit's input
                final EGLDisplay display = EGL14.eglGetCurrentDisplay();
                final EGLContext context = EGL14.eglGetCurrentContext();
                final EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                final EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
                if (EGL14.eglMakeCurrent(display, inputEglSurface, inputEglSurface, context)) {
                    if (!swapIntervalSet) {
                        // a slow lens drops input frames instead of blocking the camera thread
                        EGL14.eglSwapInterval(display, 0);
                        swapIntervalSet = true;
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GLES20.glViewport(0, 0, width, height);
                    drawExternal(cameraTexture, stMatrix, mvpMatrix != null ? mvpMatrix : identity, textureCoords);
                    EGLExt.eglPresentationTimeANDROID(display, inputEglSurface, timestamp);
                    EGL14.eglSwapBuffers(display, inputEglSurface);
                    EGL14.eglMakeCurrent(display, draw, read, context);
                }

                // Camera Kit's latest output, a frame or two behind
                if (lensFrameAvailable) {
                    lensFrameAvailable = false;
                    lensOutput.updateTexImage();
                    lensOutput.getTransformMatrix(outputMatrix);
                    final long outputTimestamp = lensOutput.getTimestamp();
                    int paired = -1;
                    for (int i = 0; i < HISTORY_SIZE; i++) {
                        if (originalTimestamps[i] == outputTimestamp && outputTimestamp != 0) {
                            paired = i;
                            break;
                        }
                    }
                    final float strength = Math.max(0, Math.min(100, intensity)) / 100f;
                    final long readyAt = lensReadyAt;
                    if (readyAt == 0 || outputTimestamp <= readyAt) {
                        if (++syncMisses % 120 == 1) {
                            android.util.Log.i(TAG, "Lens not applied yet");
                        }
                    } else if (paired >= 0 || strength == 1f) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0]);
                        GLES20.glViewport(0, 0, width, height);
                        drawExternal(lensTexture[0], outputMatrix, identity, fullTexture,
                                paired >= 0 ? originalTextures[paired] : 0, strength);
                        hasOutput = true;
                        if (paired >= 0 && !syncReported) {
                            syncReported = true;
                            android.util.Log.i(TAG, "Lens strength: input/output frame timestamps matched");
                        }
                    } else if (++syncMisses % 120 == 1) {
                        android.util.Log.w(TAG, "Lens strength: waiting for matching input/output frame");
                    }
                }
                return hasOutput ? outputTexture[0] : 0;
            } catch (Throwable e) {
                FileLog.e(e);
                failed = true;
                return 0;
            } finally {
                saved.restore();
            }
        }

        private void drawExternal(int texture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords) {
            drawExternal(texture, stMatrix, mvpMatrix, textureCoords, 0, 1f);
        }

        private void drawExternal(int texture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords,
                                  int originalTexture, float strength) {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniform1i(originalHandle, 1);
            GLES20.glUniform1f(strengthHandle, strength);
            GLES20.glUniform2f(frameSizeHandle, width, height);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, originalTexture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniformMatrix4fv(stHandle, 1, false, stMatrix, 0);
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, quad);
            GLES20.glEnableVertexAttribArray(positionHandle);
            textureCoords.position(0);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureCoords);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        private boolean init() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                failed = true;
                return false;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            stHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
            mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            originalHandle = GLES20.glGetUniformLocation(program, "originalFrame");
            strengthHandle = GLES20.glGetUniformLocation(program, "lensStrength");
            frameSizeHandle = GLES20.glGetUniformLocation(program, "frameSize");
            android.opengl.Matrix.setIdentityM(identity, 0);

            GLES20.glGenTextures(1, outputTexture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, outputFbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTexture[0], 0);

            GLES20.glGenTextures(HISTORY_SIZE, originalTextures, 0);
            GLES20.glGenFramebuffers(1, originalFbo, 0);
            for (int texture : originalTextures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            }

            // Camera Kit renders the lens into this texture of ours
            GLES20.glGenTextures(1, lensTexture, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, lensTexture[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            lensOutput = new SurfaceTexture(lensTexture[0]);
            lensOutput.setDefaultBufferSize(width, height);
            lensOutput.setOnFrameAvailableListener(t -> lensFrameAvailable = true);
            lensSurface = new Surface(lensOutput);

            // Camera Kit attaches its input texture to its own context: it must start detached from ours
            if (Build.VERSION.SDK_INT >= 26) {
                inputTexture = new SurfaceTexture(false);
            } else {
                inputTexture = new SurfaceTexture(0);
                inputTexture.detachFromGLContext();
            }
            inputTexture.setDefaultBufferSize(width, height);
            inputSurface = new Surface(inputTexture);

            final EGLDisplay display = EGL14.eglGetCurrentDisplay();
            final EGLConfig config = currentConfig(display);
            if (config == null) {
                failed = true;
                return false;
            }
            inputEglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (inputEglSurface == null || inputEglSurface == EGL14.EGL_NO_SURFACE) {
                failed = true;
                return false;
            }
            return true;
        }

        private void connect(Session session, boolean front) {
            disconnect();
            final ImageProcessor processor = session.getProcessor();
            final Callable<Float> horizontalFov = () -> fieldOfViewAcross(width);
            final Callable<Float> verticalFov = () -> fieldOfViewAcross(height);
            final ImageProcessor.Input input = ImageProcessors.inputFrom(inputTexture, width, height, 0, front, horizontalFov, verticalFov);
            android.util.Log.i(TAG, "Lens input " + width + "x" + height + " front=" + front
                    + " fov=" + fieldOfViewAcross(width) + "x" + fieldOfViewAcross(height));
            inputConnection = processor.connectInput(new ImageProcessor.Input.BackedBySurfaceTexture(inputTexture) {
                @Override public int getWidth() { return input.getWidth(); }
                @Override public int getHeight() { return input.getHeight(); }
                @Override public int getRotationDegrees() { return input.getRotationDegrees(); }
                @Override public boolean getFacingFront() { return input.getFacingFront(); }
                @Override public void attachToGlContext(int texture) { input.attachToGlContext(texture); }
                @Override public void detachFromGlContext() { input.detachFromGlContext(); }
                @Override public Closeable subscribeTo(com.snap.camerakit.common.Consumer<ImageProcessor.Input> consumer) {
                    return input.subscribeTo(ignored -> consumer.accept(this));
                }
                @Override public ImageProcessor.Input.Frame readFrame() {
                    ImageProcessor.Input.Frame frame = input.readFrame();
                    processingTimestamp = frame.getTimestamp();
                    return frame;
                }
            });
            // The stock surface output timestamps frames with wall time. Preserve the consumed input's
            // timestamp instead, so the GL thread can retrieve the exact original frame for compositing.
            outputConnection = processor.connectOutput(new ImageProcessor.Output.BackedBySurface(
                    lensSurface, ImageProcessor.Output.Purpose.RECORDING) {
                @Override public int getRotationDegrees() { return 0; }
                @Override public ImageProcessor.Output.Frame writeFrame() {
                    final long timestamp = processingTimestamp;
                    return new ImageProcessor.Output.Frame() {
                        @Override public long getTimestamp() { return timestamp; }
                        @Override public void recycle() { }
                    };
                }
            });
            connectedSession = session;
            connectedFront = front;
            hasOutput = false;
            processingTimestamp = 0;
            java.util.Arrays.fill(originalTimestamps, 0L);
        }

        private void disconnect() {
            close(inputConnection);
            close(outputConnection);
            inputConnection = null;
            outputConnection = null;
            connectedSession = null;
        }

        private static void close(Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }

        private static EGLConfig currentConfig(EGLDisplay display) {
            final int[] id = new int[1];
            if (!EGL14.eglQueryContext(display, EGL14.eglGetCurrentContext(), EGL14.EGL_CONFIG_ID, id, 0)) {
                return null;
            }
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return null;
            }
            return configs[0];
        }

        /** Call on the GL thread that rendered. */
        public void release() {
            disconnect();
            if (appliedLens != null) {
                lensRequest++;
                applyLens(this, null, null);
                appliedLens = null;
            }
            lensReadyAt = 0;
            if (inputEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), inputEglSurface);
                inputEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (inputTexture != null) {
                inputTexture.release();
                inputTexture = null;
            }
            if (lensSurface != null) {
                lensSurface.release();
                lensSurface = null;
            }
            if (lensOutput != null) {
                lensOutput.release();
                lensOutput = null;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (originalFbo[0] != 0) {
                GLES20.glDeleteFramebuffers(1, originalFbo, 0);
                originalFbo[0] = 0;
            }
            GLES20.glDeleteTextures(HISTORY_SIZE, originalTextures, 0);
            java.util.Arrays.fill(originalTextures, 0);
            if (outputFbo[0] != 0) {
                GLES20.glDeleteFramebuffers(1, outputFbo, 0);
                outputFbo[0] = 0;
            }
            if (outputTexture[0] != 0) {
                GLES20.glDeleteTextures(1, outputTexture, 0);
                outputTexture[0] = 0;
            }
            if (lensTexture[0] != 0) {
                GLES20.glDeleteTextures(1, lensTexture, 0);
                lensTexture[0] = 0;
            }
            hasOutput = false;
        }
    }

    /** State the offscreen passes change, restored so the round frame drawing around them is unaffected. */
    private static final class GlState {
        private final int[] framebuffer = new int[1];
        private final int[] viewport = new int[4];
        private final int[] program = new int[1];
        private boolean blend;

        static GlState save() {
            GlState s = new GlState();
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, s.framebuffer, 0);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, s.viewport, 0);
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, s.program, 0);
            s.blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
            return s;
        }

        void restore() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glUseProgram(program[0]);
            if (blend) {
                GLES20.glEnable(GLES20.GL_BLEND);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
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
            FileLog.e(TAG + " link failed " + GLES20.glGetProgramInfoLog(program));
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
            FileLog.e(TAG + " shader failed " + GLES20.glGetShaderInfoLog(shader));
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
