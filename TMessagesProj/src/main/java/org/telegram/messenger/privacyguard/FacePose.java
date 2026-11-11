package org.telegram.messenger.privacyguard;

/** Per frame head pose and gaze of one face, see {@link FaceGeometry}. Degrees; plain data. */
final class FacePose {
    float yaw, pitch;
    float eyeYaw, eyePitch;
    boolean irisReliable;
    float blink;
    float gazeYaw, gazePitch;
    float faceWidth;
}
