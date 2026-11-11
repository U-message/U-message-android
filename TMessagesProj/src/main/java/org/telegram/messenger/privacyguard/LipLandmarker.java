package org.telegram.messenger.privacyguard;

import android.graphics.Bitmap;

import java.io.IOException;
import java.util.ArrayList;

/**
 * U message: dense face mesh for the round video lipstick. Finds the largest face with the Privacy Guard models
 * and follows it between frames. Not thread safe: create, use and close on one thread.
 */
public final class LipLandmarker {

    public static final int LANDMARK_COUNT = FaceMeshPipeline.LANDMARK_COUNT;

    /** Lip contours of the 478 point mesh, corner to corner (left corner first), 11 points each. */
    public static final int[] UPPER_OUTER = {61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291};
    public static final int[] UPPER_INNER = {78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308};
    public static final int[] LOWER_OUTER = {61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291};
    public static final int[] LOWER_INNER = {78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308};
    /** Bare skin next to the mouth (cheeks beside the lip corners), for the lip / skin color model. */
    public static final int[] SKIN_SAMPLES = {205, 425, 207, 427, 212, 432};

    private final FaceMeshPipeline pipeline;
    private final ArrayList<float[]> tracked = new ArrayList<>();
    private final float[] trackedBox = new float[4];

    public LipLandmarker() throws IOException {
        pipeline = new FaceMeshPipeline();
        pipeline.blendshapesEnabled = false;
    }

    /**
     * @param out x, y in frame pixels per landmark, {@link #LANDMARK_COUNT} * 2 values
     * @return false when there is no face
     */
    public boolean detect(Bitmap frame, float[] out) {
        ArrayList<FaceMeshPipeline.Face> faces = pipeline.process(frame, 1, null, tracked);
        FaceMeshPipeline.Face face = null;
        for (FaceMeshPipeline.Face f : faces) {
            if (f.meshValid) {
                face = f;
                break;
            }
        }
        tracked.clear();
        if (face == null) {
            return false;
        }
        trackedBox[0] = face.box.left;
        trackedBox[1] = face.box.top;
        trackedBox[2] = face.box.right;
        trackedBox[3] = face.box.bottom;
        tracked.add(trackedBox);
        for (int i = 0; i < LANDMARK_COUNT; i++) {
            out[i * 2] = face.x(i);
            out[i * 2 + 1] = face.y(i);
        }
        return true;
    }

    public void close() {
        pipeline.close();
    }
}
