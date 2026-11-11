package org.telegram.messenger.privacyguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows faces across frames and turns noisy per frame measurements into stable per person judgments:
 * who is the owner (embedding similarity with hysteresis, one owner at most), whether an unknown person is
 * looking at the screen (filtered head pose + eyes, hysteresis), and whether the face is alive (blinks or
 * natural head micro motion; a poster or a photo on another screen shows neither).
 * Plain Java, no Android types, so it can be unit tested.
 */
final class FaceTracker {

    static final int IDENTITY_UNRESOLVED = 0;
    static final int IDENTITY_OWNER = 1;
    static final int IDENTITY_UNKNOWN = 2;

    /** One face in the current frame. */
    static final class Observation {
        float left, top, right, bottom;
        final FacePose pose = new FacePose();
        /** False when only the box is known this frame (mesh skipped for the recognized owner). */
        boolean hasPose = true;
        /** Index into the frame's face list, for the identity resolver. */
        int index;

        float width() { return right - left; }
        float height() { return bottom - top; }
        float centerX() { return (left + right) / 2f; }
        float centerY() { return (top + bottom) / 2f; }
    }

    interface IdentityResolver {
        /** Cosine similarity of the face to the enrolled owner, or NaN when it could not be computed. */
        float similarity(Observation observation);
    }

    static final class Summary {
        int faces;
        /** Debug only: tracks by identity (counts, never identity data). */
        int owners, unknowns, unresolved;
        boolean ownerVisible;
        boolean ownerLooking;
        /** A confirmed, live unknown face close enough to read the screen. */
        boolean unknownPresent;
        /** Such a face that is looking at the screen. */
        boolean unknownLooking;
    }

    private static final int HISTORY = 24;

    static final class Track {
        final int id;
        final long firstSeen;
        long lastSeen;
        long lastUpdate;
        int hits;
        boolean confirmed;
        boolean seenThisFrame;
        boolean poseFresh;
        boolean dismissed;
        float left, top, right, bottom;

        final OneEuroFilter yawFilter = new OneEuroFilter(1.0f, 0.05f, 1f);
        final OneEuroFilter pitchFilter = new OneEuroFilter(1.0f, 0.05f, 1f);
        final OneEuroFilter eyeYawFilter = new OneEuroFilter(1.5f, 0.05f, 1f);
        final OneEuroFilter eyePitchFilter = new OneEuroFilter(1.5f, 0.05f, 1f);
        float yaw, pitch, deviation, blink, widthPx, widthFraction;
        float gazeScore;
        boolean looking;

        int identity = IDENTITY_UNRESOLVED;
        float similarity;
        int samples;
        long lastResolveAttempt = Long.MIN_VALUE / 2;
        long lastResolved = Long.MIN_VALUE / 2;

        final long[] historyTime = new long[HISTORY];
        final float[] historyYaw = new float[HISTORY];
        final float[] historyPitch = new float[HISTORY];
        final float[] historyEye = new float[HISTORY];
        int historyCount, historyPos;
        boolean eyesClosed;
        long eyesClosedSince;
        long lastLiveEvidence = Long.MIN_VALUE / 2;

        Track(int id, long now) {
            this.id = id;
            this.firstSeen = now;
            this.lastSeen = now;
            this.lastUpdate = now;
        }

        float width() { return right - left; }

        boolean isLive(long now) {
            return now - lastLiveEvidence <= PrivacyGuardTuning.LIVE_HOLD_MS;
        }
    }

    private final ArrayList<Track> tracks = new ArrayList<>();
    private int nextId = 1;

    ArrayList<Track> getTracks() {
        return tracks;
    }

    void reset() {
        tracks.clear();
    }

    /** The recognized owner currently in view, or null. */
    Track getOwnerTrack() {
        for (Track t : tracks) {
            if (t.identity == IDENTITY_OWNER && t.seenThisFrame) {
                return t;
            }
        }
        return null;
    }

    static float overlap(Track t, float left, float top, float right, float bottom) {
        return iou(left, top, right, bottom, t.left, t.top, t.right, t.bottom);
    }

    /** Current faces no longer count as suspicious (the user chose to show the chat anyway). New faces still do. */
    void dismissCurrent() {
        for (Track t : tracks) {
            t.dismissed = true;
        }
    }

    void update(List<Observation> observations, long now, int frameWidth, PrivacyGuardSettings.Snapshot config, IdentityResolver resolver) {
        for (Track t : tracks) {
            t.seenThisFrame = false;
        }
        final Track[] assigned = associate(observations);
        for (int i = 0; i < observations.size(); i++) {
            Observation o = observations.get(i);
            Track t = assigned[i];
            if (t == null) {
                t = new Track(nextId++, now);
                tracks.add(t);
                assigned[i] = t;
            }
            t.seenThisFrame = true;
            t.lastSeen = now;
            t.hits++;
            t.left = o.left;
            t.top = o.top;
            t.right = o.right;
            t.bottom = o.bottom;
            if (!t.confirmed && t.hits >= PrivacyGuardTuning.TRACK_CONFIRM_HITS && now - t.firstSeen >= PrivacyGuardTuning.TRACK_CONFIRM_MS) {
                t.confirmed = true;
            }
            t.poseFresh = o.hasPose;
            if (o.hasPose) {
                updatePose(t, o.pose, now, frameWidth, config.sensitivity);
                updateLiveness(t, o.pose, now);
            }
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (!t.seenThisFrame) {
                if (now - t.lastSeen > PrivacyGuardTuning.TRACK_COAST_MS) {
                    tracks.remove(i);
                } else {
                    decay(t, now);
                }
            }
        }
        resolveIdentity(observations, assigned, now, resolver);
        enforceSingleOwner();
    }

    private Track[] associate(List<Observation> observations) {
        final Track[] assigned = new Track[observations.size()];
        final boolean[] taken = new boolean[tracks.size()];
        // greedy by overlap, then by center distance for fast motion
        while (true) {
            float best = PrivacyGuardTuning.TRACK_MATCH_IOU;
            int bestObs = -1, bestTrack = -1;
            for (int i = 0; i < observations.size(); i++) {
                if (assigned[i] != null) {
                    continue;
                }
                Observation o = observations.get(i);
                for (int j = 0; j < tracks.size(); j++) {
                    if (taken[j]) {
                        continue;
                    }
                    Track t = tracks.get(j);
                    float score = iou(o.left, o.top, o.right, o.bottom, t.left, t.top, t.right, t.bottom);
                    if (score < PrivacyGuardTuning.TRACK_MATCH_IOU) {
                        final float dist = (float) Math.hypot(o.centerX() - (t.left + t.right) / 2f, o.centerY() - (t.top + t.bottom) / 2f);
                        final float size = Math.max(o.width(), t.width());
                        final float ratio = Math.min(o.width(), t.width()) / Math.max(1f, size);
                        if (dist < size * 0.5f && ratio > 0.6f) {
                            score = PrivacyGuardTuning.TRACK_MATCH_IOU + 0.001f * (1f - dist / size);
                        }
                    }
                    if (score > best) {
                        best = score;
                        bestObs = i;
                        bestTrack = j;
                    }
                }
            }
            if (bestObs < 0) {
                break;
            }
            assigned[bestObs] = tracks.get(bestTrack);
            taken[bestTrack] = true;
        }
        return assigned;
    }

    private static float iou(float l1, float t1, float r1, float b1, float l2, float t2, float r2, float b2) {
        final float iw = Math.max(0, Math.min(r1, r2) - Math.max(l1, l2));
        final float ih = Math.max(0, Math.min(b1, b2) - Math.max(t1, t2));
        final float inter = iw * ih;
        final float union = (r1 - l1) * (b1 - t1) + (r2 - l2) * (b2 - t2) - inter;
        return union > 0 ? inter / union : 0;
    }

    private static void updatePose(Track t, FacePose p, long now, int frameWidth, int sensitivity) {
        t.yaw = t.yawFilter.filter(p.yaw, now);
        t.pitch = t.pitchFilter.filter(p.pitch, now);
        final float eyeYaw = t.eyeYawFilter.filter(p.irisReliable ? p.eyeYaw : t.eyeYawFilter.get(), now);
        final float eyePitch = t.eyePitchFilter.filter(p.eyePitch, now);
        final float gazeYaw = t.yaw + PrivacyGuardTuning.EYE_YAW_WEIGHT * eyeYaw;
        final float gazePitch = t.pitch + PrivacyGuardTuning.EYE_PITCH_WEIGHT * eyePitch;
        t.deviation = screenDeviation(gazeYaw, gazePitch);
        t.blink = p.blink;
        t.widthPx = p.faceWidth;
        t.widthFraction = frameWidth > 0 ? p.faceWidth / frameWidth : 0;

        float raw = 1f - smoothstep(PrivacyGuardTuning.GAZE_ON_DEG[sensitivity], PrivacyGuardTuning.GAZE_OFF_DEG[sensitivity], t.deviation);
        if (Math.abs(t.yaw) > PrivacyGuardTuning.HEAD_MAX_YAW_DEG || Math.abs(t.pitch) > PrivacyGuardTuning.HEAD_MAX_PITCH_DEG) {
            raw = 0;
        }
        if (p.blink > PrivacyGuardTuning.EYES_CLOSED) {
            raw = 0;
        }
        final float minFraction = PrivacyGuardTuning.MIN_FACE_WIDTH_FRACTION[sensitivity];
        raw *= Math.max(0f, Math.min(1f, (t.widthFraction - minFraction) / (minFraction * 0.3f)));
        smoothGaze(t, raw, now);
    }

    private static void decay(Track t, long now) {
        smoothGaze(t, 0f, now);
    }

    private static void smoothGaze(Track t, float target, long now) {
        final long dt = Math.max(1, now - t.lastUpdate);
        t.lastUpdate = now;
        final float alpha = 1f - (float) Math.exp(-dt / (double) PrivacyGuardTuning.GAZE_SCORE_TAU_MS);
        t.gazeScore += alpha * (target - t.gazeScore);
        t.looking = t.looking ? t.gazeScore > PrivacyGuardTuning.GAZE_EXIT : t.gazeScore > PrivacyGuardTuning.GAZE_ENTER;
    }

    private static void updateLiveness(Track t, FacePose p, long now) {
        // blinks
        if (!t.eyesClosed && p.blink > PrivacyGuardTuning.BLINK_CLOSED) {
            t.eyesClosed = true;
            t.eyesClosedSince = now;
        } else if (t.eyesClosed && p.blink < PrivacyGuardTuning.BLINK_OPEN) {
            t.eyesClosed = false;
            if (now - t.eyesClosedSince <= PrivacyGuardTuning.BLINK_MAX_MS) {
                t.lastLiveEvidence = now;
            }
        }
        // natural head and eye micro motion; the threshold grows for small, noisier faces
        t.historyTime[t.historyPos] = now;
        t.historyYaw[t.historyPos] = p.yaw;
        t.historyPitch[t.historyPos] = p.pitch;
        t.historyEye[t.historyPos] = p.eyeYaw;
        t.historyPos = (t.historyPos + 1) % HISTORY;
        t.historyCount = Math.min(HISTORY, t.historyCount + 1);

        int n = 0;
        long oldest = now;
        double sy = 0, sp = 0, se = 0, syy = 0, spp = 0, see = 0;
        for (int i = 0; i < t.historyCount; i++) {
            if (now - t.historyTime[i] > PrivacyGuardTuning.LIVE_WINDOW_MS) {
                continue;
            }
            oldest = Math.min(oldest, t.historyTime[i]);
            n++;
            sy += t.historyYaw[i]; syy += t.historyYaw[i] * t.historyYaw[i];
            sp += t.historyPitch[i]; spp += t.historyPitch[i] * t.historyPitch[i];
            se += t.historyEye[i]; see += t.historyEye[i] * t.historyEye[i];
        }
        if (n >= 5 && now - oldest >= 600) {
            final double poseStd = Math.sqrt(Math.max(0, syy / n - (sy / n) * (sy / n)) + Math.max(0, spp / n - (sp / n) * (sp / n)));
            final double eyeStd = Math.sqrt(Math.max(0, see / n - (se / n) * (se / n)));
            final float noise = Math.max(1f, 120f / Math.max(1f, t.widthPx));
            if (poseStd > PrivacyGuardTuning.LIVE_POSE_STD_DEG * noise || eyeStd > 2f * PrivacyGuardTuning.LIVE_POSE_STD_DEG * noise) {
                t.lastLiveEvidence = now;
            }
        }
    }

    private void resolveIdentity(List<Observation> observations, Track[] assigned, long now, IdentityResolver resolver) {
        if (resolver == null) {
            return;
        }
        int bestIndex = -1;
        int bestPriority = 0;
        float bestWidth = 0;
        for (int i = 0; i < observations.size(); i++) {
            Track t = assigned[i];
            if (t.hits < 2 || !t.poseFresh || !canEmbed(t)) {
                continue;
            }
            final int priority;
            if (t.identity == IDENTITY_UNRESOLVED) {
                priority = now - t.lastResolveAttempt >= PrivacyGuardTuning.RESOLVE_INTERVAL_MS ? 2 : 0;
            } else {
                final long interval = t.identity == IDENTITY_OWNER ? PrivacyGuardTuning.REVERIFY_OWNER_MS : PrivacyGuardTuning.REVERIFY_UNKNOWN_MS;
                priority = now - t.lastResolved >= interval ? 1 : 0;
            }
            if (priority > bestPriority || priority == bestPriority && priority > 0 && t.widthPx > bestWidth) {
                bestPriority = priority;
                bestWidth = t.widthPx;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            return;
        }
        final Observation o = observations.get(bestIndex);
        final Track t = assigned[bestIndex];
        t.lastResolveAttempt = now;
        final float sim = resolver.similarity(o);
        if (Float.isNaN(sim)) {
            return;
        }
        t.lastResolved = now;
        applySimilarity(t, sim);
    }

    static void applySimilarity(Track t, float sim) {
        t.similarity = t.samples == 0 ? sim : t.similarity + PrivacyGuardTuning.IDENTITY_EMA * (sim - t.similarity);
        t.samples++;
        final boolean enough = t.samples >= 2;
        if (t.identity != IDENTITY_OWNER) {
            if (enough && t.similarity >= PrivacyGuardTuning.OWNER_ACCEPT || sim >= PrivacyGuardTuning.OWNER_ACCEPT_SINGLE && t.identity == IDENTITY_UNRESOLVED) {
                t.identity = IDENTITY_OWNER;
            } else if (enough && t.similarity <= PrivacyGuardTuning.UNKNOWN_REJECT || sim <= PrivacyGuardTuning.UNKNOWN_REJECT_SINGLE) {
                t.identity = IDENTITY_UNKNOWN;
            }
        } else if (enough && t.similarity <= PrivacyGuardTuning.UNKNOWN_REJECT) {
            t.identity = IDENTITY_UNKNOWN;
        }
    }

    private static boolean canEmbed(Track t) {
        return t.widthPx >= PrivacyGuardTuning.EMBED_MIN_FACE_PX
                && Math.abs(t.yaw) <= PrivacyGuardTuning.EMBED_MAX_YAW_DEG
                && Math.abs(t.pitch) <= PrivacyGuardTuning.EMBED_MAX_PITCH_DEG
                && t.blink < 0.5f;
    }

    /** There is one owner: if several faces match, the best one keeps the role. */
    private void enforceSingleOwner() {
        Track owner = null;
        for (Track t : tracks) {
            if (t.identity == IDENTITY_OWNER && (owner == null || t.similarity > owner.similarity)) {
                owner = t;
            }
        }
        for (Track t : tracks) {
            if (t != owner && t.identity == IDENTITY_OWNER) {
                t.identity = IDENTITY_UNKNOWN;
            }
        }
    }

    Summary summarize(long now, PrivacyGuardSettings.Snapshot config) {
        Summary s = new Summary();
        Track owner = null;
        for (Track t : tracks) {
            if (t.seenThisFrame) {
                s.faces++;
                if (t.identity == IDENTITY_OWNER) s.owners++;
                else if (t.identity == IDENTITY_UNKNOWN) s.unknowns++;
                else s.unresolved++;
            }
            if (t.identity == IDENTITY_OWNER && now - t.lastSeen <= 300) {
                owner = t;
            }
        }
        s.ownerVisible = owner != null;
        s.ownerLooking = owner != null && owner.deviation < PrivacyGuardTuning.OWNER_LOOK_AWAY_DEG && owner.blink < PrivacyGuardTuning.EYES_CLOSED;
        final float minFraction = PrivacyGuardTuning.MIN_FACE_WIDTH_FRACTION[config.sensitivity];
        for (Track t : tracks) {
            if (t == owner || !t.confirmed || t.dismissed) {
                continue;
            }
            // a face we could not identify next to the recognized owner is someone else
            final boolean unknown = t.identity == IDENTITY_UNKNOWN
                    || t.identity == IDENTITY_UNRESOLVED && owner != null && now - t.firstSeen >= 400
                    && iou(t.left, t.top, t.right, t.bottom, owner.left, owner.top, owner.right, owner.bottom) < 0.1f;
            if (!unknown || t.widthFraction < minFraction || !t.isLive(now)) {
                continue;
            }
            s.unknownPresent = true;
            if (t.looking) {
                s.unknownLooking = true;
            }
        }
        return s;
    }

    /** Angle between where the person looks and the screen. */
    static float screenDeviation(float gazeYaw, float gazePitch) {
        return (float) Math.hypot(gazeYaw, gazePitch - PrivacyGuardTuning.SCREEN_PITCH_BIAS_DEG);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        final float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
