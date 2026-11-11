package org.telegram.messenger.privacyguard;

/** What the UI needs to know. Deliberately contains no biometric data. */
public final class PrivacyGuardStatus {

    public static final PrivacyGuardStatus DISABLED = new PrivacyGuardStatus(PrivacyGuardStateMachine.State.DISABLED, PrivacyGuardStateMachine.REASON_NONE);

    public final PrivacyGuardStateMachine.State state;
    public final int reason;

    public PrivacyGuardStatus(PrivacyGuardStateMachine.State state, int reason) {
        this.state = state;
        this.reason = reason;
    }

    public boolean isProtected() {
        return state == PrivacyGuardStateMachine.State.PROTECTED;
    }

    public boolean isRunning() {
        return state != PrivacyGuardStateMachine.State.DISABLED && state != PrivacyGuardStateMachine.State.PAUSED;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PrivacyGuardStatus)) {
            return false;
        }
        PrivacyGuardStatus other = (PrivacyGuardStatus) o;
        return state == other.state && reason == other.reason;
    }

    @Override
    public int hashCode() {
        return state.hashCode() * 31 + reason;
    }

    /** Generic, loggable: e.g. PROTECTED(UNKNOWN_VIEWER). */
    @Override
    public String toString() {
        switch (reason) {
            case PrivacyGuardStateMachine.REASON_UNKNOWN_VIEWER: return state + "(UNKNOWN_VIEWER)";
            case PrivacyGuardStateMachine.REASON_UNKNOWN_FACE: return state + "(UNKNOWN_FACE)";
            case PrivacyGuardStateMachine.REASON_OWNER_AWAY: return state + "(OWNER_AWAY)";
            default: return state.toString();
        }
    }
}
