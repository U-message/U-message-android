package org.telegram.messenger.privacyguard;

/**
 * Decides when the chat is hidden, from per frame summaries of the tracked faces. Knows nothing about cameras,
 * models or views; all timings come from {@link PrivacyGuardTuning}.
 *
 * <pre>
 * SCANNING / SAFE_OWNER_ONLY / UNKNOWN_NOT_LOOKING
 *     -- unknown looks at the screen --> SUSPICIOUS_GAZE
 * SUSPICIOUS_GAZE -- continuously for the dwell time --> PROTECTED
 *                 -- looks away / leaves (beyond a short grace) --> back
 * PROTECTED -- nothing suspicious for the release delay and the owner is present --> back
 * </pre>
 */
public final class PrivacyGuardStateMachine {

    public enum State {
        DISABLED,
        /** The camera is temporarily used by something else (video message, call, camera screen). */
        PAUSED,
        /** No recognized face yet. */
        SCANNING,
        SAFE_OWNER_ONLY,
        UNKNOWN_NOT_LOOKING,
        SUSPICIOUS_GAZE,
        PROTECTED
    }

    public static final int REASON_NONE = 0;
    public static final int REASON_UNKNOWN_VIEWER = 1;
    public static final int REASON_UNKNOWN_FACE = 2;
    public static final int REASON_OWNER_AWAY = 3;

    /** Per frame input. */
    public static final class Observation {
        public long now;
        /** Auto exposure is adapting; do not start new judgments. */
        public boolean unstable;
        public boolean ownerVisible;
        public boolean ownerLooking;
        public boolean unknownPresent;
        public boolean unknownLooking;
    }

    private State state = State.SCANNING;
    private int reason = REASON_NONE;

    private long suspiciousStart = -1;
    private long lastSuspicious = -1;
    private long lastOwnerSeen = -1;
    private long ownerAwaySince = -1;
    private long ownerBackSince = -1;
    private boolean ownerAwaySuppressed;

    public State getState() {
        return state;
    }

    public int getReason() {
        return reason;
    }

    public boolean isProtected() {
        return state == State.PROTECTED;
    }

    public void reset() {
        state = State.SCANNING;
        reason = REASON_NONE;
        suspiciousStart = -1;
        lastSuspicious = -1;
        lastOwnerSeen = -1;
        ownerAwaySince = -1;
        ownerBackSince = -1;
        ownerAwaySuppressed = false;
    }

    /** The user chose to show the chat: drop the current protection; it must build up again from scratch. */
    public void dismiss() {
        if (state == State.PROTECTED && reason == REASON_OWNER_AWAY) {
            ownerAwaySuppressed = true;
        }
        state = State.SCANNING;
        reason = REASON_NONE;
        suspiciousStart = -1;
        lastSuspicious = -1;
        ownerAwaySince = -1;
    }

    public State update(Observation o, PrivacyGuardSettings.Snapshot config) {
        final long now = o.now;
        if (o.ownerVisible) {
            lastOwnerSeen = now;
        }

        final boolean suspicious = config.mode == PrivacyGuardSettings.MODE_ANY_FACE ? o.unknownPresent : o.unknownLooking;
        if (suspicious) {
            if (suspiciousStart < 0 && !o.unstable) {
                suspiciousStart = now;
            }
            lastSuspicious = now;
        } else if (suspiciousStart >= 0 && now - lastSuspicious > PrivacyGuardTuning.SUSPICIOUS_GRACE_MS) {
            suspiciousStart = -1;
        }

        final boolean ownerAttentive = o.ownerVisible && o.ownerLooking;
        if (ownerAttentive) {
            ownerAwaySince = -1;
            if (ownerBackSince < 0) {
                ownerBackSince = now;
            }
            ownerAwaySuppressed = false;
        } else {
            ownerBackSince = -1;
            if (ownerAwaySince < 0 && !o.unstable) {
                ownerAwaySince = now;
            }
        }
        final boolean ownerAway = config.hideWhenOwnerAway && !ownerAwaySuppressed
                && ownerAwaySince >= 0 && now - ownerAwaySince >= PrivacyGuardTuning.OWNER_AWAY_DELAY_MS;

        if (state == State.PROTECTED) {
            final boolean clear = suspiciousStart < 0 && (lastSuspicious < 0 || now - lastSuspicious >= PrivacyGuardTuning.RELEASE_DELAY_MS);
            boolean release;
            if (reason == REASON_OWNER_AWAY) {
                release = clear && ownerAttentive && now - ownerBackSince >= PrivacyGuardTuning.OWNER_RETURN_MS;
            } else {
                final boolean ownerRecent = lastOwnerSeen >= 0 && now - lastOwnerSeen <= PrivacyGuardTuning.OWNER_RECENT_MS;
                release = clear && ownerRecent;
                if (release && ownerAway) {
                    reason = REASON_OWNER_AWAY;
                    release = false;
                }
            }
            if (release) {
                reason = REASON_NONE;
                state = derive(o, suspicious);
            }
            return state;
        }

        if (suspiciousStart >= 0 && now - suspiciousStart >= PrivacyGuardTuning.ACTIVATE_DWELL_MS[config.sensitivity]) {
            state = State.PROTECTED;
            reason = config.mode == PrivacyGuardSettings.MODE_ANY_FACE ? REASON_UNKNOWN_FACE : REASON_UNKNOWN_VIEWER;
            return state;
        }
        if (ownerAway && !o.unstable) {
            state = State.PROTECTED;
            reason = REASON_OWNER_AWAY;
            return state;
        }
        state = derive(o, suspicious);
        return state;
    }

    private State derive(Observation o, boolean suspicious) {
        if (suspiciousStart >= 0 || suspicious) {
            return State.SUSPICIOUS_GAZE;
        }
        if (o.unknownPresent) {
            return State.UNKNOWN_NOT_LOOKING;
        }
        if (o.ownerVisible) {
            return State.SAFE_OWNER_ONLY;
        }
        return State.SCANNING;
    }
}
