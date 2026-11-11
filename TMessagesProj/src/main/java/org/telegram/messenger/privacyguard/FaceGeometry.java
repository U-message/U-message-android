package org.telegram.messenger.privacyguard;

/**
 * Head pose and gaze from the face mesh. Angles are in degrees in the upright frame; yaw is positive toward the
 * frame's +x, pitch positive when looking up. A face that looks at the camera appears frontal in the image, so the
 * appearance based pose already accounts for where the face sits relative to the camera.
 */
final class FaceGeometry {

    private FaceGeometry() {
    }

    static float faceWidth(FaceMeshPipeline.Face f) {
        return (float) Math.hypot(f.x(454) - f.x(234), f.y(454) - f.y(234));
    }

    static void compute(FaceMeshPipeline.Face f, FacePose out) {
        // face axes: across the eyes and cheeks, and forehead to chin
        final float xx = (f.x(263) - f.x(33)) + (f.x(454) - f.x(234));
        final float xy = (f.y(263) - f.y(33)) + (f.y(454) - f.y(234));
        final float xz = (f.z(263) - f.z(33)) + (f.z(454) - f.z(234));
        final float yx = f.x(152) - f.x(10);
        final float yy = f.y(152) - f.y(10);
        final float yz = f.z(152) - f.z(10);
        // forward (toward the viewer's gaze) = -(x cross y)
        float fx = -(xy * yz - xz * yy);
        float fy = -(xz * yx - xx * yz);
        float fz = -(xx * yy - xy * yx);
        final float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1e-6f) {
            fx /= len; fy /= len; fz /= len;
        }
        out.yaw = (float) Math.toDegrees(Math.atan2(fx, -fz));
        out.pitch = (float) Math.toDegrees(Math.atan2(-fy, -fz));

        // eyes: iris position between the eye corners; the average cancels the symmetric resting offset
        final float o1 = irisOffset(f, 468, 33, 133);
        final float o2 = irisOffset(f, 473, 362, 263);
        out.irisReliable = Math.abs(o1) < 0.6f && Math.abs(o2) < 0.6f;
        out.eyeYaw = out.irisReliable ? (o1 + o2) / 2f * PrivacyGuardTuning.EYE_YAW_GAIN : 0f;
        final float[] bs = f.blendshapes;
        final float up = (bs[FaceMeshPipeline.BS_EYE_LOOK_UP_LEFT] + bs[FaceMeshPipeline.BS_EYE_LOOK_UP_RIGHT]) / 2f;
        final float down = (bs[FaceMeshPipeline.BS_EYE_LOOK_DOWN_LEFT] + bs[FaceMeshPipeline.BS_EYE_LOOK_DOWN_RIGHT]) / 2f;
        out.eyePitch = (up - down) * PrivacyGuardTuning.EYE_PITCH_GAIN;
        out.blink = (bs[FaceMeshPipeline.BS_EYE_BLINK_LEFT] + bs[FaceMeshPipeline.BS_EYE_BLINK_RIGHT]) / 2f;

        out.gazeYaw = out.yaw + PrivacyGuardTuning.EYE_YAW_WEIGHT * out.eyeYaw;
        out.gazePitch = out.pitch + PrivacyGuardTuning.EYE_PITCH_WEIGHT * out.eyePitch;
        out.faceWidth = faceWidth(f);
    }

    /** Iris position along the eye, -0.5 .. 0.5, positive toward the frame's +x. */
    private static float irisOffset(FaceMeshPipeline.Face f, int iris, int c1, int c2) {
        float ax = f.x(c1), ay = f.y(c1), bx = f.x(c2), by = f.y(c2);
        if (ax > bx) {
            float t = ax; ax = bx; bx = t;
            t = ay; ay = by; by = t;
        }
        final float vx = bx - ax, vy = by - ay;
        final float len2 = vx * vx + vy * vy;
        if (len2 < 1e-3f) {
            return 1f;
        }
        return ((f.x(iris) - ax) * vx + (f.y(iris) - ay) * vy) / len2 - 0.5f;
    }
}
