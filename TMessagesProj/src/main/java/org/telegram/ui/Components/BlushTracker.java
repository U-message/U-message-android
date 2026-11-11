package org.telegram.ui.Components;

import android.opengl.GLES20;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws a soft cheek mask from the same MediaPipe face mesh used by lipstick. This removes the second detector and its
 * extra framebuffer readback, so blush and lipstick stay attached to one tracked face at the same timestamp.
 */
public class BlushTracker {

    private static final int MASK_SIZE = 128;
    private static final long LOST_HOLD_MS = 200;
    private static final long FADE_OUT_MS = 250;
    private static final long MAX_PREDICTION_MS = 80;
    private static final float PREDICTION = 0.8f;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform vec2 leftCheek;\n" +
            "uniform vec2 rightCheek;\n" +
            "uniform float radius;\n" +
            "uniform float opacity;\n" +
            "uniform vec2 faceCenter;\n" +
            "uniform vec2 faceAxisX;\n" +
            "uniform vec2 faceAxisY;\n" +
            "uniform vec2 faceRadius;\n" +
            "uniform vec2 leftEye;\n" +
            "uniform vec2 rightEye;\n" +
            "uniform vec2 eyeRadius;\n" +
            "vec2 localPoint(vec2 p, vec2 center) {\n" +
            "  vec2 d = p - center;\n" +
            "  return vec2(dot(d, faceAxisX), dot(d, faceAxisY));\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 uv = vec2(gl_FragCoord.x / 128.0, 1.0 - gl_FragCoord.y / 128.0);\n" +
            "  float l = length(localPoint(uv, leftCheek) / vec2(radius, radius * 0.72));\n" +
            "  float r = length(localPoint(uv, rightCheek) / vec2(radius, radius * 0.72));\n" +
            "  float cheek = max(1.0 - smoothstep(0.10, 1.0, l), 1.0 - smoothstep(0.10, 1.0, r));\n" +
            "  vec2 eyeOffset = faceAxisY * eyeRadius.y * 0.72;\n" +
            "  float le = length(localPoint(uv, leftEye - eyeOffset) / eyeRadius);\n" +
            "  float re = length(localPoint(uv, rightEye - eyeOffset) / eyeRadius);\n" +
            "  float eyes = max(1.0 - smoothstep(0.32, 1.0, le), 1.0 - smoothstep(0.32, 1.0, re));\n" +
            "  float face = 1.0 - smoothstep(0.76, 1.0, length(localPoint(uv, faceCenter) / faceRadius));\n" +
            "  gl_FragColor = vec4(cheek, eyes, face, 1.0) * vec4(opacity, opacity, opacity, 1.0);\n" +
            "}\n";

    private final int[] maskTexture = new int[1];
    private final int[] maskFbo = new int[1];
    private final FloatBuffer vertices = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private int program;
    private int positionHandle;
    private int leftHandle;
    private int rightHandle;
    private int radiusHandle;
    private int opacityHandle;
    private int faceCenterHandle;
    private int faceAxisXHandle;
    private int faceAxisYHandle;
    private int faceRadiusHandle;
    private int leftEyeHandle;
    private int rightEyeHandle;
    private int eyeRadiusHandle;
    private boolean visible;
    private boolean failed;

    public int getTexture() {
        return visible ? maskTexture[0] : 0;
    }

    /** Renders the cheek mask on the current GL context from the latest shared face-mesh state. */
    public void render(LipstickTracker tracker) {
        visible = false;
        LipstickTracker.State state = tracker != null ? tracker.state : null;
        if (state == null || failed || !init()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        float opacity = 1f - clamp((now - state.time - LOST_HOLD_MS) / (float) FADE_OUT_MS, 0f, 1f);
        if (opacity <= 0.01f) {
            return;
        }
        float predict = Math.min(MAX_PREDICTION_MS, Math.max(0, now - state.time)) * PREDICTION;
        float dx = state.velocityX * predict;
        float dy = state.velocityY * predict;
        float radius = clamp(state.faceWidth * 0.145f, 0.035f, 0.13f);

        int[] previousFbo = new int[1];
        int[] previousViewport = new int[4];
        int[] previousProgram = new int[1];
        boolean blendEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND);
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFbo, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, previousProgram, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glViewport(0, 0, MASK_SIZE, MASK_SIZE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(leftHandle, state.leftCheekX + dx, state.leftCheekY + dy);
        GLES20.glUniform2f(rightHandle, state.rightCheekX + dx, state.rightCheekY + dy);
        GLES20.glUniform1f(radiusHandle, radius);
        GLES20.glUniform1f(opacityHandle, opacity);
        GLES20.glUniform2f(faceCenterHandle, state.faceCenterX + dx, state.faceCenterY + dy);
        GLES20.glUniform2f(faceAxisXHandle, state.faceAxisXX, state.faceAxisXY);
        GLES20.glUniform2f(faceAxisYHandle, state.faceAxisYX, state.faceAxisYY);
        GLES20.glUniform2f(faceRadiusHandle, state.faceRadiusX, state.faceRadiusY);
        GLES20.glUniform2f(leftEyeHandle, state.leftEyeX + dx, state.leftEyeY + dy);
        GLES20.glUniform2f(rightEyeHandle, state.rightEyeX + dx, state.rightEyeY + dy);
        GLES20.glUniform2f(eyeRadiusHandle, state.eyeRadiusX * 1.25f, state.eyeRadiusY * 1.65f);
        vertices.position(0);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertices);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFbo[0]);
        GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        GLES20.glUseProgram(previousProgram[0]);
        if (blendEnabled) {
            GLES20.glEnable(GLES20.GL_BLEND);
        }
        visible = true;
    }

    public void release() {
        visible = false;
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (maskFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, maskFbo, 0);
            maskFbo[0] = 0;
        }
        if (maskTexture[0] != 0) {
            GLES20.glDeleteTextures(1, maskTexture, 0);
            maskTexture[0] = 0;
        }
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
        leftHandle = GLES20.glGetUniformLocation(program, "leftCheek");
        rightHandle = GLES20.glGetUniformLocation(program, "rightCheek");
        radiusHandle = GLES20.glGetUniformLocation(program, "radius");
        opacityHandle = GLES20.glGetUniformLocation(program, "opacity");
        faceCenterHandle = GLES20.glGetUniformLocation(program, "faceCenter");
        faceAxisXHandle = GLES20.glGetUniformLocation(program, "faceAxisX");
        faceAxisYHandle = GLES20.glGetUniformLocation(program, "faceAxisY");
        faceRadiusHandle = GLES20.glGetUniformLocation(program, "faceRadius");
        leftEyeHandle = GLES20.glGetUniformLocation(program, "leftEye");
        rightEyeHandle = GLES20.glGetUniformLocation(program, "rightEye");
        eyeRadiusHandle = GLES20.glGetUniformLocation(program, "eyeRadius");

        int[] previousFbo = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFbo, 0);
        GLES20.glGenTextures(1, maskTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, MASK_SIZE, MASK_SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glGenFramebuffers(1, maskFbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, maskTexture[0], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFbo[0]);
        return true;
    }

    private static int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertexShader);
        GLES20.glAttachShader(result, fragmentShader);
        GLES20.glLinkProgram(result);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        int[] status = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("BlushTracker link failed " + GLES20.glGetProgramInfoLog(result));
            GLES20.glDeleteProgram(result);
            return 0;
        }
        return result;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("BlushTracker shader failed " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
    }
}
