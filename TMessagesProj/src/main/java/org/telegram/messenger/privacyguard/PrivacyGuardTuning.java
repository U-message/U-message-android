package org.telegram.messenger.privacyguard;

/**
 * Every threshold and timing Privacy Guard uses, in one place.
 * Arrays indexed by sensitivity are {LOW, BALANCED, HIGH}.
 * Gaze and identity values were calibrated on the bundled models (see assets/privacy_guard).
 */
public final class PrivacyGuardTuning {

    private PrivacyGuardTuning() {
    }

    /* Timing */

    /** A suspicious gaze must last this long before the chat is hidden. */
    public static final long[] ACTIVATE_DWELL_MS = {900, 350, 120}; // clearly different reaction speed per level
    /** Once nothing suspicious remains, wait this long before restoring. */
    public static final long RELEASE_DELAY_MS = 450;
    /** Short dropouts (a blink, a missed frame) inside a suspicious gaze do not restart the dwell. */
    public static final long SUSPICIOUS_GRACE_MS = 250;
    /** Restoring after an unknown viewer requires the owner to have been seen this recently. */
    public static final long OWNER_RECENT_MS = 700;
    /** "Hide when I look away": owner missing or looking away for this long hides the chat. */
    public static final long OWNER_AWAY_DELAY_MS = 600;
    /** ... and the owner has to look back this long to restore it. */
    public static final long OWNER_RETURN_MS = 300;
    /** Owner gaze deviation that counts as clearly looking away. */
    public static final float OWNER_LOOK_AWAY_DEG = 35f;

    public static final long BLUR_IN_MS = 160;
    public static final long BLUR_OUT_MS = 220;

    /* Face tracking */

    public static final int TRACK_CONFIRM_HITS = 3;
    public static final long TRACK_CONFIRM_MS = 250;
    /** A track survives this long without a detection (detector flicker, fast motion). */
    public static final long TRACK_COAST_MS = 450;
    public static final float TRACK_MATCH_IOU = 0.2f;

    /* Gaze */

    /** Gaze deviation from the screen at which the per-frame score starts to fall... */
    public static final float[] GAZE_ON_DEG = {6f, 11f, 18f};
    /** ...and reaches zero. */
    public static final float[] GAZE_OFF_DEG = {13f, 21f, 32f};
    /** The screen lies below the front camera, so a viewer reading it looks slightly down from the camera ray. */
    public static final float SCREEN_PITCH_BIAS_DEG = -6f;
    /** Degrees of eye rotation per unit of iris offset between the eye corners. */
    public static final float EYE_YAW_GAIN = 110f;
    /** Degrees of eye rotation per unit of the eyeLookUp - eyeLookDown blendshapes. */
    public static final float EYE_PITCH_GAIN = 30f;
    public static final float EYE_YAW_WEIGHT = 0.8f;
    public static final float EYE_PITCH_WEIGHT = 0.7f;
    /** Beyond these head angles eye landmarks are unreliable; the face is treated as not looking. */
    public static final float HEAD_MAX_YAW_DEG = 45f;
    public static final float HEAD_MAX_PITCH_DEG = 40f;
    /** Average eyeBlink blendshape above this means the eyes are closed. */
    public static final float EYES_CLOSED = 0.6f;
    /** Smoothed gaze score hysteresis. */
    public static final float GAZE_ENTER = 0.6f;
    public static final float GAZE_EXIT = 0.35f;
    public static final long GAZE_SCORE_TAU_MS = 120;
    /** Faces narrower than this fraction of the frame are too far away to read the screen. */
    public static final float[] MIN_FACE_WIDTH_FRACTION = {0.14f, 0.085f, 0.05f}; // low: only close faces, high: far ones too

    /* Owner recognition (cosine similarity of MobileFaceNet embeddings) */

    public static final float OWNER_ACCEPT = 0.55f;
    public static final float OWNER_ACCEPT_SINGLE = 0.68f;
    public static final float UNKNOWN_REJECT = 0.35f;
    public static final float UNKNOWN_REJECT_SINGLE = 0.2f;
    public static final float IDENTITY_EMA = 0.5f;
    public static final int EMBED_MIN_FACE_PX = 56;
    public static final float EMBED_MAX_YAW_DEG = 40f;
    public static final float EMBED_MAX_PITCH_DEG = 35f;
    public static final long REVERIFY_OWNER_MS = 2500;
    public static final long REVERIFY_UNKNOWN_MS = 1500;
    public static final long RESOLVE_INTERVAL_MS = 150;

    /* Liveness: printed or on-screen faces never move or blink */

    public static final long LIVE_WINDOW_MS = 1500;
    public static final float LIVE_POSE_STD_DEG = 0.6f;
    public static final float LIVE_MOTION_STD = 0.02f;
    public static final long LIVE_HOLD_MS = 10000;
    public static final float BLINK_CLOSED = 0.55f;
    public static final float BLINK_OPEN = 0.3f;
    public static final long BLINK_MAX_MS = 700;

    /* Frames */

    public static final int ANALYSIS_WIDTH = 640;
    public static final int ANALYSIS_HEIGHT = 480;
    public static final int FPS_ACTIVE = 15;
    public static final int FPS_IDLE = 8;
    public static final int FPS_MIN = 4;
    /** A mean luma jump this large means auto exposure is adapting; judgments are frozen meanwhile. */
    public static final float EXPOSURE_JUMP = 22f;
    public static final long EXPOSURE_SETTLE_MS = 400;

    /* Models */

    public static final int MAX_FACES = 4;
    public static final float DETECTION_THRESHOLD = 0.5f;
    public static final float DETECTION_NMS_IOU = 0.3f;
    public static final float LANDMARKS_PRESENCE_LOGIT = 2f;

    /* Camera sharing */

    /** After another camera user asked for the camera, do not reopen for at least this long. */
    public static final long CAMERA_YIELD_MS = 2000;
    /** Camera availability must be stable this long before reopening. */
    public static final long CAMERA_AVAILABLE_SETTLE_MS = 800;
    public static final long CAMERA_RETRY_MS = 1000;
}
