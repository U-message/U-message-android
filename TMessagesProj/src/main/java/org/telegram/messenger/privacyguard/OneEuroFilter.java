package org.telegram.messenger.privacyguard;

/** One Euro filter (Casiez et al.): little lag on fast motion, strong smoothing when still. */
public final class OneEuroFilter {

    private final float minCutoff;
    private final float beta;
    private final float derivativeCutoff;
    private boolean initialized;
    private float value;
    private float derivative;
    private long lastTime;

    public OneEuroFilter(float minCutoff, float beta, float derivativeCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.derivativeCutoff = derivativeCutoff;
    }

    public float filter(float x, long timeMs) {
        if (!initialized) {
            initialized = true;
            value = x;
            derivative = 0;
            lastTime = timeMs;
            return x;
        }
        final float dt = Math.max(1, timeMs - lastTime) / 1000f;
        lastTime = timeMs;
        final float dx = (x - value) / dt;
        derivative += alpha(derivativeCutoff, dt) * (dx - derivative);
        final float cutoff = minCutoff + beta * Math.abs(derivative);
        value += alpha(cutoff, dt) * (x - value);
        return value;
    }

    public float get() {
        return value;
    }

    private static float alpha(float cutoff, float dt) {
        final float tau = 1f / (2f * (float) Math.PI * cutoff);
        return 1f / (1f + tau / dt);
    }
}
