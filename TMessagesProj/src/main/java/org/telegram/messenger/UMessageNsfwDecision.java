package org.telegram.messenger;

/** Display policy shared by chat cells, taps and the full-screen media viewer. */
public final class UMessageNsfwDecision {
    public enum State { CLEAR, CHECKING, UNAVAILABLE, HIDDEN }

    private UMessageNsfwDecision() { }

    public static State resolve(boolean enabled, boolean applicable, Byte verdict, int level,
                                boolean revealed, boolean temporalPending, boolean unavailable) {
        if (!enabled || !applicable || revealed) return State.CLEAR;
        int threshold = level == 0 ? 3 : level == 2 ? 1 : 2;
        // An already unsafe preview remains hidden even while temporal sampling is running.
        if (verdict != null && verdict >= threshold) return State.HIDDEN;
        if (verdict == null || temporalPending) return unavailable ? State.UNAVAILABLE : State.CHECKING;
        return State.CLEAR;
    }
}
