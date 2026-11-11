package org.telegram.messenger;

/**
 * Sleep timer for the music player: stops playback after a chosen number of minutes (with a soft fade)
 * or when the current track ends. Lives only in memory: a restart of the app cancels it.
 */
public final class UMessageSleepTimer {

    private static long endTime;
    private static boolean endOfTrack;
    private static final Runnable fire = UMessageSleepTimer::stopNow;
    /** UI hook (the open player) refreshed whenever the timer starts, stops or fires. */
    public static Runnable listener;

    private UMessageSleepTimer() {
    }

    public static void start(int minutes) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.SLEEP_TIMER)) return;
        cancel();
        long delay = minutes * 60_000L;
        endTime = System.currentTimeMillis() + delay;
        AndroidUtilities.runOnUIThread(fire, delay);
        notifyChanged();
    }

    public static void startEndOfTrack() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.SLEEP_TIMER)) return;
        cancel();
        endOfTrack = true;
        notifyChanged();
    }

    public static void cancel() {
        AndroidUtilities.cancelRunOnUIThread(fire);
        endTime = 0;
        endOfTrack = false;
        notifyChanged();
    }

    public static boolean isActive() {
        return endOfTrack || endTime > System.currentTimeMillis();
    }

    public static boolean isEndOfTrack() {
        return endOfTrack;
    }

    /** Wall-clock time the music stops at, 0 when the timer waits for the end of the track or is off. */
    public static long getEndTime() {
        return endTime > System.currentTimeMillis() ? endTime : 0;
    }

    /** Called by MediaController when a track finishes; true means stop here instead of playing the next one. */
    public static boolean consumeTrackEnd() {
        if (!endOfTrack) {
            return false;
        }
        endOfTrack = false;
        notifyChanged();
        return true;
    }

    private static void notifyChanged() {
        if (listener != null) {
            listener.run();
        }
    }

    private static void stopNow() {
        endTime = 0;
        notifyChanged();
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        if (playing != null && !mc.isMessagePaused()) {
            mc.pauseMessage(playing, true);
        }
    }
}
