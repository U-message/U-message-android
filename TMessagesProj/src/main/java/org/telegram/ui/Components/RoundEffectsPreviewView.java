package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.TextureView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.camera.Camera2Session;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** U message: live front camera preview with round video effects, used in settings. Expects a square size. */
public class RoundEffectsPreviewView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String VERTEX_SHADER =
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "   gl_Position = aPosition;\n" +
            "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            RoundVideoEffects.GLSL +
            "void main() {\n" +
            "   vec4 c = texture2D(sTexture, vTextureCoord);\n" +
            "   gl_FragColor = vec4(applyEffects(c.rgb, vTextureCoord), 1.0);\n" +
            "}\n";

    private volatile int filter;
    private volatile int filterIntensity;
    private volatile int beauty;
    private volatile int foundation;
    private volatile int blush;
    private volatile int eyes;
    private volatile int eyeTone;
    private volatile RoundVideoEffects.Lipstick lipstick;
    private Runnable onError;

    private HandlerThread glThread;
    private Handler glHandler;
    private int surfaceWidth, surfaceHeight;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int positionHandle, textureHandle, stMatrixHandle;
    private final RoundVideoEffects.Uniforms effectsUniforms = new RoundVideoEffects.Uniforms();
    private BlushTracker blushTracker;
    private LipstickTracker lipstickTracker;
    private LipstickTracker.Mask lipstickMask;
    private final int[] cameraTextureId = new int[1];
    private SurfaceTexture cameraTexture;
    private Camera2Session session;
    private volatile int lensIntensity = 100;
    private volatile boolean lensCovered;
    private volatile String makeup; // Snap lens id, null is off
    private SnapCameraKit.Renderer makeupRenderer;
    private boolean gles3; // Camera Kit needs an OpenGL ES 3 context
    private final float[] stMatrix = new float[16];
    private FloatBuffer vertexBuffer, textureBuffer;

    public RoundEffectsPreviewView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
    }

    /** @param lipstick null is off */

    public void setEffects(int filter, int filterIntensity, int beauty, int foundation, int blush,
                           int eyes, int eyeTone, RoundVideoEffects.Lipstick lipstick) {
        this.filter = filter;
        this.filterIntensity = filterIntensity;
        this.beauty = beauty;
        this.foundation = foundation;
        this.blush = blush;
        this.eyes = eyes;
        this.eyeTone = eyeTone;
        this.lipstick = lipstick;
    }

    /** Snap lens drawn under the other effects, null is off. */
    public void setLens(String lensId, int intensity, boolean covered) {
        lensIntensity = intensity;
        lensCovered = covered;
        makeup = lensId;
    }

    /** Called on the UI thread when the camera or GL could not be started. */
    public void setOnErrorListener(Runnable onError) {
        this.onError = onError;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        glThread = new HandlerThread("u_round_preview");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(() -> {
            if (!init(surface)) {
                releaseGL();
                AndroidUtilities.runOnUIThread(() -> {
                    if (onError != null) {
                        onError.run();
                    }
                });
            }
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        if (glHandler != null) {
            glHandler.post(() -> {
                surfaceWidth = width;
                surfaceHeight = height;
            });
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        if (glHandler != null) {
            glHandler.post(this::releaseGL);
            glThread.quitSafely();
            try {
                glThread.join();
            } catch (InterruptedException e) {
                FileLog.e(e);
            }
            glHandler = null;
            glThread = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }

    private boolean init(SurfaceTexture surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            return false;
        }
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR, ES 2 below when missing
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        gles3 = EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0;
        if (!gles3) {
            configAttribs[7] = EGL14.EGL_OPENGL_ES2_BIT;
        }
        if (!gles3 && (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0)) {
            return false;
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, gles3 ? 3 : 2, EGL14.EGL_NONE}, 0);
        if (gles3 && (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT)) {
            gles3 = false;
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        }
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            return false;
        }

        program = createProgram();
        if (program == 0) {
            return false;
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
        effectsUniforms.init(program);

        GLES20.glGenTextures(1, cameraTextureId, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        session = Camera2Session.create(true, 720, 720);
        if (session == null) {
            return false;
        }

        // crop the camera buffer to its centered square, the transform matrix then rotates it upright
        final int pw = session.getPreviewWidth(), ph = session.getPreviewHeight();
        final float tX = pw > ph ? 0.5f * ph / pw : 0.5f;
        final float tY = ph > pw ? 0.5f * pw / ph : 0.5f;
        vertexBuffer = floatBuffer(new float[]{
                -1, -1,
                1, -1,
                -1, 1,
                1, 1
        });
        textureBuffer = floatBuffer(new float[]{
                0.5f - tX, 0.5f - tY,
                0.5f + tX, 0.5f - tY,
                0.5f - tX, 0.5f + tY,
                0.5f + tX, 0.5f + tY
        });

        cameraTexture = new SurfaceTexture(cameraTextureId[0]);
        cameraTexture.setOnFrameAvailableListener(t -> draw(), glHandler);
        session.open(cameraTexture);
        return true;
    }

    private void draw() {
        if (cameraTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        cameraTexture.updateTexImage();
        cameraTexture.getTransformMatrix(stMatrix);

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        final String makeup = this.makeup;
        int makeupTexture = 0;
        if (makeup != null && gles3) {
            if (makeupRenderer == null) {
                makeupRenderer = new SnapCameraKit.Renderer();
            }
            if (session != null) {
                makeupRenderer.setShortSideFieldOfView(session.getShortSideFieldOfView());
            }
            makeupTexture = makeupRenderer.render(makeup, cameraTextureId[0], stMatrix, null, textureBuffer, true, lensIntensity);
        }
        final RoundVideoEffects.Lipstick lipstick = this.lipstick;
        final int blush = this.blush;
        final boolean makeupMask = blush > 0 || foundation > 0 || eyes > 0;
        if ((lipstick != null || makeupMask) && lipstickTracker == null) {
            lipstickTracker = new LipstickTracker();
        }
        if (lipstick != null) {
            if (lipstickMask == null) {
                lipstickMask = new LipstickTracker.Mask();
            }
            lipstickMask.render(lipstickTracker, lipstick.getSoftness());
        }
        // offscreen masks change vertex attributes and the framebuffer: draw them before setting up the frame
        if (makeupMask && blushTracker == null) {
            blushTracker = new BlushTracker();
        }
        if (blushTracker != null) {
            blushTracker.render(lipstickTracker);
        }
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0]);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
        GLES20.glEnableVertexAttribArray(textureHandle);
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0);
        effectsUniforms.apply(filter, filterIntensity, beauty, foundation, blush, eyes, eyeTone,
                blushTracker != null ? blushTracker.getTexture() : 0, surfaceWidth, surfaceHeight);
        effectsUniforms.applyLipstick(lipstick, lipstickMask);
        if (makeupTexture == 0 && lensCovered) {
            effectsUniforms.applyCover();
        } else {
            effectsUniforms.applyMakeup(makeupTexture);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureHandle);
        if (lipstick != null || makeupMask) {
            lipstickTracker.capture(cameraTextureId[0], stMatrix, null, textureBuffer);
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void releaseGL() {
        if (session != null) {
            session.destroy(false);
            session = null;
        }
        if (cameraTexture != null) {
            cameraTexture.release();
            cameraTexture = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                if (blushTracker != null) {
                    blushTracker.release();
                    blushTracker = null;
                }
                if (makeupRenderer != null) {
                    makeupRenderer.release();
                    makeupRenderer = null;
                }
                if (lipstickMask != null) {
                    lipstickMask.release();
                    lipstickMask = null;
                }
                if (lipstickTracker != null) {
                    lipstickTracker.release();
                    lipstickTracker = null;
                }
                if (cameraTextureId[0] != 0) {
                    GLES20.glDeleteTextures(1, cameraTextureId, 0);
                    cameraTextureId[0] = 0;
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    private static int createProgram() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("RoundEffectsPreview link failed " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("RoundEffectsPreview shader failed " + GLES20.glGetShaderInfoLog(shader));
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
