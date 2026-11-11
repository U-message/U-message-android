package org.telegram.messenger.privacyguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Random;

/**
 * Privacy Guard decision logic (FaceTracker + PrivacyGuardStateMachine) on synthetic face tracks at 10 fps,
 * covering the scenarios the feature must get right. Each scenario runs with several noise seeds.
 */
public class PrivacyGuardScenarioTest {

    private static final int FRAME_WIDTH = 480;
    private static final long FRAME_MS = 100;
    private static final int SEEDS = 5;

    private static final PrivacyGuardSettings.Snapshot BALANCED = new PrivacyGuardSettings.Snapshot(
            PrivacyGuardSettings.MODE_LOOKING, PrivacyGuardSettings.ACTION_BLUR, PrivacyGuardSettings.SENSITIVITY_BALANCED, false);
    private static final PrivacyGuardSettings.Snapshot ANY_FACE = new PrivacyGuardSettings.Snapshot(
            PrivacyGuardSettings.MODE_ANY_FACE, PrivacyGuardSettings.ACTION_BLUR, PrivacyGuardSettings.SENSITIVITY_BALANCED, false);
    private static final PrivacyGuardSettings.Snapshot OWNER_AWAY = new PrivacyGuardSettings.Snapshot(
            PrivacyGuardSettings.MODE_LOOKING, PrivacyGuardSettings.ACTION_BLUR, PrivacyGuardSettings.SENSITIVITY_BALANCED, true);

    /** A synthetic person in front of the camera. */
    private static class Person {
        boolean owner;
        boolean visible = true;
        /** Blinks and natural micro motion; false for a photo or poster. */
        boolean alive = true;
        float cx, cy, width;
        float yaw, pitch, eyeYaw;

        float similarity() {
            return owner ? 0.78f : 0.12f;
        }
    }

    private interface Script {
        void step(long t, Person[] people);

        default boolean unstable(long t) {
            return false;
        }
    }

    private static class Result {
        long firstProtected = -1;
        long protectedMs;
        int transitions;
        boolean endProtected;
    }

    private static Person owner() {
        Person p = new Person();
        p.owner = true;
        p.cx = 240;
        p.cy = 330;
        p.width = 230;
        p.yaw = 2;
        p.pitch = -4;
        return p;
    }

    private static Person stranger(float yaw, float eyeYaw) {
        Person p = new Person();
        p.cx = 90;
        p.cy = 160;
        p.width = 70;
        p.yaw = yaw;
        p.pitch = -5;
        p.eyeYaw = eyeYaw;
        return p;
    }

    private static Result run(Person[] people, long durationMs, PrivacyGuardSettings.Snapshot config, Script script, long seed) {
        final Random random = new Random(seed);
        final FaceTracker tracker = new FaceTracker();
        final PrivacyGuardStateMachine machine = new PrivacyGuardStateMachine();
        final Result result = new Result();
        boolean wasProtected = false;
        for (long t = 0; t < durationMs; t += FRAME_MS) {
            final long now = 10_000 + t;
            if (script != null) {
                script.step(t, people);
            }
            final ArrayList<FaceTracker.Observation> observations = new ArrayList<>();
            final ArrayList<Person> byIndex = new ArrayList<>();
            for (Person p : people) {
                if (!p.visible) {
                    continue;
                }
                final float jitter = p.alive ? 1f : 0.15f;
                final float cx = p.cx + (float) random.nextGaussian() * 1.5f;
                final float cy = p.cy + (float) random.nextGaussian() * 1.5f;
                FaceTracker.Observation o = new FaceTracker.Observation();
                o.left = cx - p.width / 2;
                o.right = cx + p.width / 2;
                o.top = cy - p.width * 0.625f;
                o.bottom = cy + p.width * 0.625f;
                o.pose.yaw = p.yaw + (float) random.nextGaussian() * jitter;
                o.pose.pitch = p.pitch + (float) random.nextGaussian() * jitter;
                o.pose.eyeYaw = p.eyeYaw + (float) random.nextGaussian() * jitter * 2;
                o.pose.irisReliable = true;
                o.pose.blink = p.alive && t % 3700 < 150 ? 0.8f : 0.1f;
                o.pose.faceWidth = p.width;
                o.index = observations.size();
                observations.add(o);
                byIndex.add(p);
            }
            tracker.update(observations, now, FRAME_WIDTH, config,
                    o -> byIndex.get(o.index).similarity() + (float) random.nextGaussian() * 0.04f);
            final FaceTracker.Summary summary = tracker.summarize(now, config);
            final PrivacyGuardStateMachine.Observation input = new PrivacyGuardStateMachine.Observation();
            input.now = now;
            input.unstable = script != null && script.unstable(t);
            input.ownerVisible = summary.ownerVisible;
            input.ownerLooking = summary.ownerLooking;
            input.unknownPresent = summary.unknownPresent;
            input.unknownLooking = summary.unknownLooking;
            machine.update(input, config);
            final boolean isProtected = machine.isProtected();
            if (isProtected && result.firstProtected < 0) {
                result.firstProtected = t;
            }
            if (isProtected) {
                result.protectedMs += FRAME_MS;
            }
            if (isProtected != wasProtected) {
                result.transitions++;
            }
            wasProtected = isProtected;
        }
        result.endProtected = wasProtected;
        return result;
    }

    private interface Scenario {
        Result run(long seed);
    }

    private static void forEachSeed(Scenario scenario, java.util.function.Consumer<Result> check) {
        for (long seed = 1; seed <= SEEDS; seed++) {
            check.accept(scenario.run(seed));
        }
    }

    @Test
    public void ownerOnly_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner()}, 20_000, BALANCED, null, seed),
                r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void strangerLookingAway_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(-40, -15)}, 20_000, BALANCED, null, seed),
                r -> assertEquals(0, r.protectedMs));
        forEachSeed(seed -> run(new Person[]{owner(), stranger(-25, 0)}, 20_000, BALANCED, null, seed),
                r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void strangerLookingAtScreen_hiddenAfterDwell() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 6_000, BALANCED, null, seed), r -> {
            assertTrue("hidden", r.firstProtected > 0);
            // confirmation (~250 ms) + gaze smoothing + 800 ms dwell
            assertTrue("hidden late: " + r.firstProtected, r.firstProtected <= 1_600);
            assertTrue("hidden too early: " + r.firstProtected, r.firstProtected >= 800);
            assertEquals("no flicker", 1, r.transitions);
        });
    }

    @Test
    public void strangerLooksAway_restoredWithoutFlicker() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 8_000, BALANCED, (t, p) -> p[1].yaw = t < 4_000 ? 3 : -45, seed), r -> {
            assertEquals(2, r.transitions);
            assertFalse(r.endProtected);
        });
    }

    @Test
    public void strangerWalksByWithBriefGlance_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 8_000, BALANCED, (t, p) -> {
            p[1].visible = t > 2_000 && t < 4_000;
            p[1].cx = 20 + (t - 2_000) * 0.2f;
            p[1].yaw = t > 2_600 && t < 3_100 ? 3 : -35;
        }, seed), r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void photoOfFace_neverHidden() {
        forEachSeed(seed -> {
            Person poster = stranger(1, 0);
            poster.alive = false;
            return run(new Person[]{owner(), poster}, 20_000, BALANCED, null, seed);
        }, r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void singleFrameDetections_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 20_000, BALANCED, (t, p) -> p[1].visible = (t / 100) % 7 == 3, seed),
                r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void exposureChange_waitsUntilStable() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 6_000, BALANCED, new Script() {
            @Override
            public void step(long t, Person[] people) {
            }

            @Override
            public boolean unstable(long t) {
                return t < 2_000;
            }
        }, seed), r -> assertTrue("at " + r.firstProtected, r.firstProtected >= 2_000 && r.firstProtected <= 3_500));
    }

    @Test
    public void ownerGoneAfterTrigger_staysHidden() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(3, 0)}, 8_000, BALANCED, (t, p) -> {
            p[0].visible = t < 3_000;
            p[1].visible = t < 4_000;
        }, seed), r -> assertTrue(r.endProtected));
    }

    @Test
    public void ownerLooksAway_optionOn_hiddenThenRestored() {
        forEachSeed(seed -> run(new Person[]{owner()}, 9_000, OWNER_AWAY, (t, p) -> p[0].yaw = t > 3_000 && t < 6_000 ? 60 : 2, seed), r -> {
            assertEquals(2, r.transitions);
            assertFalse(r.endProtected);
            assertTrue("at " + r.firstProtected, r.firstProtected >= 4_400 && r.firstProtected <= 5_000);
        });
    }

    @Test
    public void ownerLeaves_optionOff_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner()}, 9_000, BALANCED, (t, p) -> p[0].visible = t < 3_000 || t > 6_000, seed),
                r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void anyFaceMode_strangerNotLooking_hidden() {
        forEachSeed(seed -> run(new Person[]{owner(), stranger(-40, -15)}, 6_000, ANY_FACE, null, seed),
                r -> assertTrue(r.firstProtected > 0 && r.firstProtected <= 1_600));
    }

    @Test
    public void strangerTooFarAway_neverHidden() {
        forEachSeed(seed -> {
            Person far = stranger(2, 0);
            far.width = 24;
            return run(new Person[]{owner(), far}, 10_000, BALANCED, null, seed);
        }, r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void ownerTrackJumps_neverHidden() {
        forEachSeed(seed -> run(new Person[]{owner()}, 10_000, BALANCED, (t, p) -> p[0].cx = t % 2_000 < 100 ? 390 : 240, seed),
                r -> assertEquals(0, r.protectedMs));
    }

    @Test
    public void dismiss_suppressesCurrentViewerOnly() {
        final FaceTracker tracker = new FaceTracker();
        final PrivacyGuardStateMachine machine = new PrivacyGuardStateMachine();
        machine.dismiss();
        assertFalse(machine.isProtected());
        tracker.dismissCurrent();
        assertTrue(tracker.getTracks().isEmpty());
    }

    @Test
    public void identity_hysteresis() {
        FaceTracker.Track t = new FaceTracker.Track(1, 0);
        FaceTracker.applySimilarity(t, 0.5f);
        assertEquals(FaceTracker.IDENTITY_UNRESOLVED, t.identity);
        FaceTracker.applySimilarity(t, 0.62f);
        assertEquals(FaceTracker.IDENTITY_OWNER, t.identity);
        // a single poor sample (bad light, motion blur) must not flip the owner to unknown
        FaceTracker.applySimilarity(t, 0.3f);
        assertEquals(FaceTracker.IDENTITY_OWNER, t.identity);

        FaceTracker.Track s = new FaceTracker.Track(2, 0);
        FaceTracker.applySimilarity(s, 0.1f);
        assertEquals(FaceTracker.IDENTITY_UNKNOWN, s.identity);
    }
}
