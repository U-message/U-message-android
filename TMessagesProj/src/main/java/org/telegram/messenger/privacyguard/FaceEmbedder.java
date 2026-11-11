package org.telegram.messenger.privacyguard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Face identity embedding (MobileFaceNet) on a face aligned to the standard 5 point 112x112 template.
 * The face and its mirror image are embedded together and averaged, which is more robust and mirror invariant.
 * Embeddings never leave the process except encrypted through {@link OwnerFaceStore}; they are never logged.
 */
final class FaceEmbedder {

    private static final int SIZE = 112;
    /** Reference positions of the eyes, nose tip and mouth corners (left to right) in the 112x112 crop. */
    private static final float[] TEMPLATE = {
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f
    };

    private final Interpreter interpreter;
    private final int batch;
    private final int dimension;
    private final ByteBuffer input;
    private final ByteBuffer output;
    private final Bitmap crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(crop);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] pixels = new int[SIZE * SIZE];
    private final float[] points = new float[10];

    FaceEmbedder() throws IOException {
        PrivacyGuardModels.acquire();
        try {
            interpreter = PrivacyGuardModels.create(PrivacyGuardModels.EMBEDDING, 2);
        } catch (IOException | RuntimeException e) {
            PrivacyGuardModels.release();
            throw e;
        }
        int[] inShape = interpreter.getInputTensor(0).shape();
        batch = Math.max(1, inShape[0]);
        int[] outShape = interpreter.getOutputTensor(0).shape();
        dimension = outShape[outShape.length - 1];
        input = PrivacyGuardModels.allocate(batch * SIZE * SIZE * 3 * 4);
        output = PrivacyGuardModels.allocate(batch * dimension * 4);
    }

    void close() {
        interpreter.close();
        crop.recycle();
        PrivacyGuardModels.release();
    }

    /** Minimum face width in pixels for a usable embedding. */
    static boolean isUsable(FaceMeshPipeline.Face face) {
        return FaceGeometry.faceWidth(face) >= PrivacyGuardTuning.EMBED_MIN_FACE_PX;
    }

    /** L2 normalized embedding. */
    float[] embed(Bitmap frame, FaceMeshPipeline.Face face) {
        fillPoints(face);
        // least squares similarity transform (rotation, uniform scale, translation) from the face to the template
        float msx = 0, msy = 0, mdx = 0, mdy = 0;
        for (int i = 0; i < 5; i++) {
            msx += points[i * 2];
            msy += points[i * 2 + 1];
            mdx += TEMPLATE[i * 2];
            mdy += TEMPLATE[i * 2 + 1];
        }
        msx /= 5; msy /= 5; mdx /= 5; mdy /= 5;
        float dot = 0, cross = 0, norm = 0;
        for (int i = 0; i < 5; i++) {
            final float px = points[i * 2] - msx, py = points[i * 2 + 1] - msy;
            final float qx = TEMPLATE[i * 2] - mdx, qy = TEMPLATE[i * 2 + 1] - mdy;
            dot += px * qx + py * qy;
            cross += px * qy - py * qx;
            norm += px * px + py * py;
        }
        final float a = dot / norm, b = cross / norm;
        final float tx = mdx - (a * msx - b * msy);
        final float ty = mdy - (b * msx + a * msy);
        matrix.setValues(new float[]{a, -b, tx, b, a, ty, 0, 0, 1});
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, matrix, paint);
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);

        input.rewind();
        FloatBuffer in = input.asFloatBuffer();
        for (int n = 0; n < batch; n++) {
            final boolean mirror = (n & 1) == 1;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    final int c = pixels[y * SIZE + (mirror ? SIZE - 1 - x : x)];
                    in.put((((c >> 16) & 0xff) - 127.5f) / 128f);
                    in.put((((c >> 8) & 0xff) - 127.5f) / 128f);
                    in.put(((c & 0xff) - 127.5f) / 128f);
                }
            }
        }
        input.rewind();
        output.rewind();
        interpreter.run(input, output);
        output.rewind();
        FloatBuffer out = output.asFloatBuffer();

        final float[] result = new float[dimension];
        final float[] row = new float[dimension];
        final int used = Math.min(batch, 2);
        for (int n = 0; n < used; n++) {
            out.position(n * dimension);
            out.get(row);
            normalize(row);
            for (int i = 0; i < dimension; i++) {
                result[i] += row[i];
            }
        }
        normalize(result);
        return result;
    }

    private void fillPoints(FaceMeshPipeline.Face face) {
        // eye centers from the corners, nose tip, mouth corners; ordered left to right in the frame
        float e1x = (face.x(33) + face.x(133)) / 2, e1y = (face.y(33) + face.y(133)) / 2;
        float e2x = (face.x(362) + face.x(263)) / 2, e2y = (face.y(362) + face.y(263)) / 2;
        if (e1x > e2x) {
            float t = e1x; e1x = e2x; e2x = t;
            t = e1y; e1y = e2y; e2y = t;
        }
        float m1x = face.x(61), m1y = face.y(61), m2x = face.x(291), m2y = face.y(291);
        if (m1x > m2x) {
            float t = m1x; m1x = m2x; m2x = t;
            t = m1y; m1y = m2y; m2y = t;
        }
        points[0] = e1x; points[1] = e1y;
        points[2] = e2x; points[3] = e2y;
        points[4] = face.x(1); points[5] = face.y(1);
        points[6] = m1x; points[7] = m1y;
        points[8] = m2x; points[9] = m2y;
    }

    static void normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += f * f;
        }
        final float inv = (float) (1.0 / Math.sqrt(Math.max(sum, 1e-12)));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    static float cosine(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0, n = Math.min(a.length, b.length); i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** Best match of an embedding against the owner's enrolled templates. */
    static float similarity(float[] embedding, float[][] templates) {
        float best = -1f;
        if (templates != null) {
            for (float[] t : templates) {
                best = Math.max(best, cosine(embedding, t));
            }
        }
        return best;
    }
}
