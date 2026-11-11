package org.telegram.messenger.privacyguard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Face detection (MediaPipe full range BlazeFace) -> 478 point face mesh with iris -> 52 blendshapes, all on LiteRT.
 * Not thread safe: use from one thread. Frames stay in memory only for the duration of {@link #process}.
 */
final class FaceMeshPipeline {

    static final int LANDMARK_COUNT = 478;
    static final int BLENDSHAPE_COUNT = 52;

    static final int BS_EYE_BLINK_LEFT = 9;
    static final int BS_EYE_BLINK_RIGHT = 10;
    static final int BS_EYE_LOOK_DOWN_LEFT = 11;
    static final int BS_EYE_LOOK_DOWN_RIGHT = 12;
    static final int BS_EYE_LOOK_UP_LEFT = 17;
    static final int BS_EYE_LOOK_UP_RIGHT = 18;

    /** Landmarks the blendshapes model takes, in its order. */
    private static final int[] BLENDSHAPE_SUBSET = {
            0, 1, 4, 5, 6, 7, 8, 10, 13, 14, 17, 21, 33, 37, 39,
            40, 46, 52, 53, 54, 55, 58, 61, 63, 65, 66, 67, 70, 78, 80,
            81, 82, 84, 87, 88, 91, 93, 95, 103, 105, 107, 109, 127, 132, 133,
            136, 144, 145, 146, 148, 149, 150, 152, 153, 154, 155, 157, 158, 159, 160,
            161, 162, 163, 168, 172, 173, 176, 178, 181, 185, 191, 195, 197, 234, 246,
            249, 251, 263, 267, 269, 270, 276, 282, 283, 284, 285, 288, 291, 293, 295,
            296, 297, 300, 308, 310, 311, 312, 314, 317, 318, 321, 323, 324, 332, 334,
            336, 338, 356, 361, 362, 365, 373, 374, 375, 377, 378, 379, 380, 381, 382,
            384, 385, 386, 387, 388, 389, 390, 397, 398, 400, 402, 405, 409, 415, 454,
            466, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477
    };

    private static final int DET_SIZE = 192;
    private static final int DET_GRID = 48;
    private static final int DET_ANCHORS = DET_GRID * DET_GRID;
    private static final int DET_VALUES = 16;
    private static final int LM_SIZE = 256;

    /** Lets the caller skip the (expensive) mesh for faces it already knows well enough. */
    interface MeshFilter {
        boolean needsMesh(float left, float top, float right, float bottom);
    }

    static final class Face {
        float detectionScore;
        float presence;
        /** False when the mesh was skipped: only {@link #box} is valid. */
        boolean meshValid;
        /** x, y, z per landmark in frame pixels; z uses the x scale, smaller is closer. */
        final float[] landmarks = new float[LANDMARK_COUNT * 3];
        final float[] blendshapes = new float[BLENDSHAPE_COUNT];
        /** Detector box in frame pixels; stable across frames whether or not the mesh ran. */
        final RectF box = new RectF();

        float x(int i) { return landmarks[i * 3]; }
        float y(int i) { return landmarks[i * 3 + 1]; }
        float z(int i) { return landmarks[i * 3 + 2]; }
    }

    private static final class Detection {
        float score, cx, cy, w, h;
        final float[] keypoints = new float[12];
    }

    private final Interpreter detector;
    private final Interpreter landmarker;
    private final Interpreter blendshaper;

    private final ByteBuffer detInput = PrivacyGuardModels.allocate(DET_SIZE * DET_SIZE * 3 * 4);
    private final ByteBuffer detRegressors = PrivacyGuardModels.allocate(DET_ANCHORS * DET_VALUES * 4);
    private final ByteBuffer detScores = PrivacyGuardModels.allocate(DET_ANCHORS * 4);
    private final int detRegressorsIndex, detScoresIndex;

    private final ByteBuffer lmInput = PrivacyGuardModels.allocate(LM_SIZE * LM_SIZE * 3 * 4);
    private final ByteBuffer[] lmOutputs;
    private final int lmLandmarksIndex, lmPresenceIndex;

    private final ByteBuffer bsInput = PrivacyGuardModels.allocate(BLENDSHAPE_SUBSET.length * 2 * 4);
    private final ByteBuffer bsOutput = PrivacyGuardModels.allocate(BLENDSHAPE_COUNT * 4);

    private final Bitmap detBitmap = Bitmap.createBitmap(DET_SIZE, DET_SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas detCanvas = new Canvas(detBitmap);
    private final Bitmap lmBitmap = Bitmap.createBitmap(LM_SIZE, LM_SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas lmCanvas = new Canvas(lmBitmap);
    private final int[] pixels = new int[LM_SIZE * LM_SIZE];
    private final Paint filterPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final float[] anchors = new float[DET_ANCHORS * 2];
    private final float[] regressors = new float[DET_ANCHORS * DET_VALUES];
    private final float[] scores = new float[DET_ANCHORS];
    private final float[] rawLandmarks = new float[LANDMARK_COUNT * 3];

    private final ArrayList<Face> facePool = new ArrayList<>();

    /** Off for users that only need the mesh (round video lipstick): saves the blendshapes run per face. */
    boolean blendshapesEnabled = true;

    FaceMeshPipeline() throws IOException {
        PrivacyGuardModels.acquire();
        try {
            detector = PrivacyGuardModels.create(PrivacyGuardModels.DETECTOR, 2);
            landmarker = PrivacyGuardModels.create(PrivacyGuardModels.LANDMARKS, 2);
            blendshaper = PrivacyGuardModels.create(PrivacyGuardModels.BLENDSHAPES, 1);
        } catch (IOException | RuntimeException e) {
            PrivacyGuardModels.release();
            throw e;
        }

        int regIndex = 0, scoreIndex = 1;
        for (int i = 0; i < detector.getOutputTensorCount(); i++) {
            int[] shape = detector.getOutputTensor(i).shape();
            if (shape[shape.length - 1] == DET_VALUES) {
                regIndex = i;
            } else {
                scoreIndex = i;
            }
        }
        detRegressorsIndex = regIndex;
        detScoresIndex = scoreIndex;

        final int count = landmarker.getOutputTensorCount();
        lmOutputs = new ByteBuffer[count];
        int landmarksIndex = 0, presenceIndex = -1;
        for (int i = 0; i < count; i++) {
            lmOutputs[i] = PrivacyGuardModels.allocate(landmarker.getOutputTensor(i).numBytes());
            final String name = landmarker.getOutputTensor(i).name();
            if (landmarker.getOutputTensor(i).numElements() == LANDMARK_COUNT * 3) {
                landmarksIndex = i;
            } else if ("Identity_1".equals(name)) {
                presenceIndex = i;
            }
        }
        lmLandmarksIndex = landmarksIndex;
        lmPresenceIndex = presenceIndex;

        for (int y = 0, i = 0; y < DET_GRID; y++) {
            for (int x = 0; x < DET_GRID; x++, i++) {
                anchors[i * 2] = (x + 0.5f) / DET_GRID;
                anchors[i * 2 + 1] = (y + 0.5f) / DET_GRID;
            }
        }
    }

    void close() {
        detector.close();
        landmarker.close();
        blendshaper.close();
        detBitmap.recycle();
        lmBitmap.recycle();
        PrivacyGuardModels.release();
    }

    /** Faces sorted by size, largest first. Returned objects are reused on the next call. */
    ArrayList<Face> process(Bitmap frame, int maxFaces, MeshFilter filter) {
        return process(frame, maxFaces, filter, null);
    }

    /**
     * @param tracked boxes of faces seen in the previous frame (left, top, right, bottom); when the detector misses
     *                one of them this frame (motion blur, partly out of view, dim light), the mesh is run on its last
     *                region instead, like MediaPipe's video mode, so a face does not blink in and out.
     */
    ArrayList<Face> process(Bitmap frame, int maxFaces, MeshFilter filter, List<float[]> tracked) {
        ArrayList<Detection> detections = detect(frame);
        if (tracked != null) {
            for (float[] box : tracked) {
                final Detection previous = new Detection();
                previous.cx = (box[0] + box[2]) / 2f;
                previous.cy = (box[1] + box[3]) / 2f;
                previous.w = box[2] - box[0];
                previous.h = box[3] - box[1];
                boolean detected = false;
                for (Detection d : detections) {
                    if (iou(d, previous) > PrivacyGuardTuning.DETECTION_NMS_IOU) {
                        detected = true;
                        break;
                    }
                }
                if (!detected) {
                    previous.score = 0;
                    // level eyes: keypoints only define the roll, which is small between frames
                    previous.keypoints[0] = previous.cx - 1;
                    previous.keypoints[1] = previous.cy;
                    previous.keypoints[2] = previous.cx + 1;
                    previous.keypoints[3] = previous.cy;
                    detections.add(previous);
                }
            }
        }
        ArrayList<Face> result = new ArrayList<>();
        for (int i = 0; i < detections.size() && result.size() < maxFaces; i++) {
            Face face = facePool.size() > result.size() ? facePool.get(result.size()) : null;
            if (face == null) {
                face = new Face();
                facePool.add(face);
            }
            final Detection d = detections.get(i);
            face.box.set(d.cx - d.w / 2, d.cy - d.h / 2, d.cx + d.w / 2, d.cy + d.h / 2);
            face.detectionScore = d.score;
            final boolean fromTracking = d.score == 0;
            if (!fromTracking && filter != null && !filter.needsMesh(face.box.left, face.box.top, face.box.right, face.box.bottom)) {
                face.meshValid = false;
                result.add(face);
            } else if (landmarks(frame, d, face)) {
                face.meshValid = true;
                if (fromTracking) {
                    // follow the face: re-center the box on the eyes and mouth found by the mesh
                    final float cx = (face.x(33) + face.x(263) + face.x(13)) / 3f;
                    final float cy = (face.y(33) + face.y(263) + face.y(13)) / 3f;
                    face.box.offset(cx - face.box.centerX(), cy - face.box.centerY());
                }
                if (blendshapesEnabled) {
                    blendshapes(face);
                }
                result.add(face);
            }
        }
        Collections.sort(result, (a, b) -> Float.compare(b.box.width(), a.box.width()));
        return result;
    }

    private ArrayList<Detection> detect(Bitmap frame) {
        final int w = frame.getWidth(), h = frame.getHeight();
        final float scale = DET_SIZE / (float) Math.max(w, h);
        final float padX = (DET_SIZE - w * scale) / 2f, padY = (DET_SIZE - h * scale) / 2f;
        detCanvas.drawColor(Color.BLACK);
        matrix.setScale(scale, scale);
        matrix.postTranslate(padX, padY);
        detCanvas.drawBitmap(frame, matrix, filterPaint);
        detBitmap.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE);
        detInput.rewind();
        FloatBuffer in = detInput.asFloatBuffer();
        for (int i = 0, n = DET_SIZE * DET_SIZE; i < n; i++) {
            final int c = pixels[i];
            in.put(((c >> 16) & 0xff) / 127.5f - 1f);
            in.put(((c >> 8) & 0xff) / 127.5f - 1f);
            in.put((c & 0xff) / 127.5f - 1f);
        }
        detInput.rewind();
        detRegressors.rewind();
        detScores.rewind();
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(detRegressorsIndex, detRegressors);
        outputs.put(detScoresIndex, detScores);
        detector.runForMultipleInputsOutputs(new Object[]{detInput}, outputs);
        detRegressors.rewind();
        detRegressors.asFloatBuffer().get(regressors);
        detScores.rewind();
        detScores.asFloatBuffer().get(scores);

        ArrayList<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < DET_ANCHORS; i++) {
            final float logit = Math.max(-100f, Math.min(100f, scores[i]));
            final float score = (float) (1.0 / (1.0 + Math.exp(-logit)));
            if (score < PrivacyGuardTuning.DETECTION_THRESHOLD) {
                continue;
            }
            final float ax = anchors[i * 2], ay = anchors[i * 2 + 1];
            final int o = i * DET_VALUES;
            Detection d = new Detection();
            d.score = score;
            // model space [0,1] of the letterboxed input -> frame pixels
            d.cx = ((regressors[o] / DET_SIZE + ax) * DET_SIZE - padX) / scale;
            d.cy = ((regressors[o + 1] / DET_SIZE + ay) * DET_SIZE - padY) / scale;
            d.w = regressors[o + 2] / scale;
            d.h = regressors[o + 3] / scale;
            for (int k = 0; k < 6; k++) {
                d.keypoints[k * 2] = ((regressors[o + 4 + k * 2] / DET_SIZE + ax) * DET_SIZE - padX) / scale;
                d.keypoints[k * 2 + 1] = ((regressors[o + 5 + k * 2] / DET_SIZE + ay) * DET_SIZE - padY) / scale;
            }
            candidates.add(d);
        }
        return weightedNms(candidates);
    }

    private static ArrayList<Detection> weightedNms(ArrayList<Detection> candidates) {
        Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
        ArrayList<Detection> result = new ArrayList<>();
        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            final Detection top = candidates.get(i);
            float total = 0, cx = 0, cy = 0, w = 0, h = 0;
            float[] kp = new float[12];
            for (int j = i; j < candidates.size(); j++) {
                if (used[j]) {
                    continue;
                }
                final Detection d = candidates.get(j);
                if (j != i && iou(top, d) <= PrivacyGuardTuning.DETECTION_NMS_IOU) {
                    continue;
                }
                used[j] = true;
                total += d.score;
                cx += d.cx * d.score;
                cy += d.cy * d.score;
                w += d.w * d.score;
                h += d.h * d.score;
                for (int k = 0; k < 12; k++) {
                    kp[k] += d.keypoints[k] * d.score;
                }
            }
            Detection merged = new Detection();
            merged.score = top.score;
            merged.cx = cx / total;
            merged.cy = cy / total;
            merged.w = w / total;
            merged.h = h / total;
            for (int k = 0; k < 12; k++) {
                merged.keypoints[k] = kp[k] / total;
            }
            result.add(merged);
        }
        return result;
    }

    private static float iou(Detection a, Detection b) {
        final float iw = Math.max(0, Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2));
        final float ih = Math.max(0, Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2));
        final float inter = iw * ih;
        return inter / (a.w * a.h + b.w * b.h - inter + 1e-6f);
    }

    private boolean landmarks(Bitmap frame, Detection d, Face face) {
        // region of interest: rotated so the eyes are level, 1.5x the detection, square
        final double angle = Math.atan2(d.keypoints[3] - d.keypoints[1], d.keypoints[2] - d.keypoints[0]);
        final float size = Math.max(d.w, d.h) * 1.5f;
        final float k = size / LM_SIZE;
        matrix.setTranslate(-d.cx, -d.cy);
        matrix.postRotate((float) -Math.toDegrees(angle));
        matrix.postScale(1f / k, 1f / k);
        matrix.postTranslate(LM_SIZE / 2f, LM_SIZE / 2f);
        lmCanvas.drawColor(Color.BLACK);
        lmCanvas.drawBitmap(frame, matrix, filterPaint);
        lmBitmap.getPixels(pixels, 0, LM_SIZE, 0, 0, LM_SIZE, LM_SIZE);
        lmInput.rewind();
        FloatBuffer in = lmInput.asFloatBuffer();
        for (int i = 0, n = LM_SIZE * LM_SIZE; i < n; i++) {
            final int c = pixels[i];
            in.put(((c >> 16) & 0xff) / 255f);
            in.put(((c >> 8) & 0xff) / 255f);
            in.put((c & 0xff) / 255f);
        }
        lmInput.rewind();
        Map<Integer, Object> outputs = new HashMap<>();
        for (int i = 0; i < lmOutputs.length; i++) {
            lmOutputs[i].rewind();
            outputs.put(i, lmOutputs[i]);
        }
        landmarker.runForMultipleInputsOutputs(new Object[]{lmInput}, outputs);

        float presence = Float.MAX_VALUE;
        if (lmPresenceIndex >= 0) {
            lmOutputs[lmPresenceIndex].rewind();
            presence = lmOutputs[lmPresenceIndex].asFloatBuffer().get(0);
            if (presence < PrivacyGuardTuning.LANDMARKS_PRESENCE_LOGIT) {
                return false;
            }
        }
        lmOutputs[lmLandmarksIndex].rewind();
        lmOutputs[lmLandmarksIndex].asFloatBuffer().get(rawLandmarks);

        final float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        for (int i = 0; i < LANDMARK_COUNT; i++) {
            final float rx = (rawLandmarks[i * 3] - LM_SIZE / 2f) * k;
            final float ry = (rawLandmarks[i * 3 + 1] - LM_SIZE / 2f) * k;
            final float x = cos * rx - sin * ry + d.cx;
            final float y = sin * rx + cos * ry + d.cy;
            face.landmarks[i * 3] = x;
            face.landmarks[i * 3 + 1] = y;
            face.landmarks[i * 3 + 2] = rawLandmarks[i * 3 + 2] * k;
        }
        face.presence = presence;
        return true;
    }

    private void blendshapes(Face face) {
        bsInput.rewind();
        FloatBuffer in = bsInput.asFloatBuffer();
        for (int index : BLENDSHAPE_SUBSET) {
            in.put(face.x(index));
            in.put(face.y(index));
        }
        bsInput.rewind();
        bsOutput.rewind();
        blendshaper.run(bsInput, bsOutput);
        bsOutput.rewind();
        bsOutput.asFloatBuffer().get(face.blendshapes);
    }
}
