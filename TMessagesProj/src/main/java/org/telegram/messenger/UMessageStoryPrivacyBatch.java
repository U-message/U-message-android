package org.telegram.messenger;

import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Applies one audience to many profile stories: reads current server state, skips what already
 * matches, edits the rest, then moves stories in or out of the profile with single bulk requests.
 * "Nobody" relies on the archive for expired stories; active ones must also be restricted.
 */
public final class UMessageStoryPrivacyBatch {
    private static final int REQUEST_CHUNK = 100;
    private static final int MAX_PARALLEL = 4;
    private static final int MAX_SINGLE_WAIT = 300;
    private static final int MAX_TOTAL_WAIT = 900;
    private static final int MAX_TIMEOUT_RETRIES = 5;
    public static final String ERROR_NOT_RESTRICTED = "STORY_AUDIENCE_NOT_RESTRICTED";
    public static final String ERROR_NOT_MOVED = "STORY_PROFILE_STATE_NOT_CHANGED";

    public interface Host {
        void send(TLObject request, RequestDelegate callback);
        void schedule(Runnable action, int seconds);
        int currentTime();
        long selfId();
        TLRPC.InputPeer inputPeer();
        boolean isHidden(int storyId);
        void peers(TL_stories.TL_stories_stories response);
        void updates(TLRPC.Updates updates);
        void edited(TL_stories.StoryItem story);
        void hidden(ArrayList<TL_stories.StoryItem> stories);
        void shown(ArrayList<TL_stories.StoryItem> stories);
        void gone(ArrayList<TL_stories.StoryItem> stories);
        void progress(int done, int total);
        void waiting(int seconds);
        void finished(Result result);
    }

    public static final class Result {
        public int changed, skipped, failed;
        public boolean cancelled;
        public int floodSeconds;
        public TLRPC.TL_error error;
    }

    private static final class Work {
        final TL_stories.StoryItem local;
        TL_stories.StoryItem story;
        boolean edit, move, done, failed;
        int attempt;
        Work(TL_stories.StoryItem local) { this.local = local; }
    }

    private final Host host;
    private final ArrayList<TL_stories.StoryItem> selected = new ArrayList<>();
    private final boolean hide;
    private final List<ArrayList<TLRPC.InputPrivacyRule>> audiences = new ArrayList<>();
    private final ArrayList<TLRPC.PrivacyRule> output;
    private final Result result = new Result();
    private final ArrayList<Work> works = new ArrayList<>();
    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private int active, limit = MAX_PARALLEL, waited, doneCount, timeouts;
    private boolean paused, stopped, finished, editsDone;

    public UMessageStoryPrivacyBatch(Host host, ArrayList<TL_stories.StoryItem> stories, boolean hide, ArrayList<TLRPC.InputPrivacyRule> rules, ArrayList<TLRPC.PrivacyRule> output) {
        this.host = host;
        this.hide = hide;
        this.output = output;
        HashSet<Integer> seen = new HashSet<>();
        for (TL_stories.StoryItem story : stories) if (seen.add(story.id)) selected.add(story);
        if (hide) {
            ArrayList<TLRPC.InputPrivacyRule> onlyMe = new ArrayList<>();
            TLRPC.TL_inputPrivacyValueAllowUsers allowSelf = new TLRPC.TL_inputPrivacyValueAllowUsers();
            allowSelf.users.add(new TLRPC.TL_inputUserSelf());
            onlyMe.add(allowSelf);
            audiences.add(onlyMe);
            ArrayList<TLRPC.InputPrivacyRule> nobody = new ArrayList<>();
            nobody.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
            audiences.add(nobody);
        } else {
            audiences.add(rules);
        }
    }

    public void start() {
        ArrayList<Integer> ids = new ArrayList<>();
        for (TL_stories.StoryItem story : selected) ids.add(story.id);
        fetch(ids, this::plan);
    }

    /** Stops immediately; responses still in flight only update local state. */
    public void cancel() {
        if (finished) return;
        result.cancelled = true;
        stopped = true;
        finish();
    }

    private interface Fetched { void run(HashMap<Integer, TL_stories.StoryItem> stories); }

    private void fetch(ArrayList<Integer> ids, Fetched done) {
        fetch(ids, 0, new HashMap<>(), done);
    }

    private void fetch(ArrayList<Integer> ids, int offset, HashMap<Integer, TL_stories.StoryItem> found, Fetched done) {
        if (stopped) return;
        if (offset >= ids.size()) {
            done.run(found);
            return;
        }
        TL_stories.TL_stories_getStoriesByID req = new TL_stories.TL_stories_getStoriesByID();
        req.peer = host.inputPeer();
        req.id.addAll(ids.subList(offset, Math.min(offset + REQUEST_CHUNK, ids.size())));
        host.send(req, (response, error) -> {
            if (finished) return;
            if (response instanceof TL_stories.TL_stories_stories) {
                TL_stories.TL_stories_stories stories = (TL_stories.TL_stories_stories) response;
                host.peers(stories);
                for (TL_stories.StoryItem story : stories.stories) {
                    story.dialogId = host.selfId();
                    found.put(story.id, story);
                }
                fetch(ids, offset + REQUEST_CHUNK, found, done);
            } else if (!waitFlood(error, () -> fetch(ids, offset, found, done))) {
                fail(error);
            }
        });
    }

    private void plan(HashMap<Integer, TL_stories.StoryItem> fresh) {
        int now = host.currentTime();
        ArrayList<TL_stories.StoryItem> gone = new ArrayList<>();
        ArrayList<TL_stories.StoryItem> adopt = new ArrayList<>();
        ArrayList<TL_stories.StoryItem> unmark = new ArrayList<>();
        for (TL_stories.StoryItem local : selected) {
            TL_stories.StoryItem story = fresh.get(local.id);
            if (!(story instanceof TL_stories.TL_storyItem)) {
                gone.add(local);
                continue;
            }
            boolean marked = host.isHidden(story.id);
            Work work = new Work(local);
            work.story = story;
            if (hide) {
                work.edit = story.expire_date > now && !UMessageStoryPrivacy.isSelfOnly(story, host.selfId());
                work.move = story.pinned;
                if (!work.move && !marked && local.pinned && !work.edit) {
                    adopt.add(story);
                    result.changed++;
                    continue;
                }
            } else {
                work.edit = !UMessageStoryPrivacy.matches(story, output);
                work.move = marked && !story.pinned;
                if (marked && story.pinned) unmark.add(story);
            }
            if (work.edit || work.move) {
                works.add(work);
                if (work.edit) queue.add(work);
            } else {
                result.skipped++;
            }
        }
        if (!gone.isEmpty()) host.gone(gone);
        if (!adopt.isEmpty()) host.hidden(adopt);
        if (!unmark.isEmpty()) host.shown(unmark);
        host.progress(0, works.size());
        move();
    }

    private void pump() {
        if (finished || editsDone) return;
        while (!stopped && !paused && active < limit && !queue.isEmpty()) sendEdit(queue.poll());
        if (active == 0 && !paused && (queue.isEmpty() || stopped)) {
            editsDone = true;
            if (stopped) finish();
            else move();
        }
    }

    private void sendEdit(Work work) {
        TL_stories.TL_stories_editStory req = new TL_stories.TL_stories_editStory();
        req.peer = host.inputPeer();
        req.id = work.story.id;
        req.flags |= 4;
        req.privacy_rules = audiences.get(work.attempt);
        active++;
        host.send(req, (response, error) -> {
            active--;
            onEdited(work, response, error);
            pump();
        });
    }

    private void onEdited(Work work, TLObject response, TLRPC.TL_error error) {
        boolean notModified = error != null && "STORY_NOT_MODIFIED".equals(error.text);
        if (error != null && !notModified) {
            if (finished) return;
            if (isFlood(error)) {
                if (waitFlood(error, null)) {
                    queue.addFirst(work);
                } else {
                    failWork(work, error);
                    stop(error);
                }
            } else if (error.code < 0 && timeouts < MAX_TIMEOUT_RETRIES) {
                timeouts++;
                limit = 1;
                queue.addFirst(work);
            } else if (hide && work.attempt + 1 < audiences.size()) {
                work.attempt++;
                queue.addFirst(work);
            } else {
                failWork(work, error);
                if (error.code < 0) stop(error);
            }
            return;
        }
        TL_stories.StoryItem updated = null;
        if (response instanceof TLRPC.Updates) {
            TLRPC.Updates updates = (TLRPC.Updates) response;
            for (TLRPC.Update update : updates.updates) {
                if (update instanceof TL_stories.TL_updateStory && ((TL_stories.TL_updateStory) update).story != null && ((TL_stories.TL_updateStory) update).story.id == work.story.id) {
                    updated = ((TL_stories.TL_updateStory) update).story;
                }
            }
            host.updates(updates);
        }
        if (updated instanceof TL_stories.TL_storyItem) {
            updated.dialogId = host.selfId();
            work.story = updated;
        }
        if (hide) {
            if (!notModified && !(updated instanceof TL_stories.TL_storyItem && UMessageStoryPrivacy.isSelfOnly(updated, host.selfId()))) {
                if (work.attempt + 1 < audiences.size() && !finished) {
                    work.attempt++;
                    queue.addFirst(work);
                } else {
                    failWork(work, restrictionError());
                }
                return;
            }
        } else if (!(updated instanceof TL_stories.TL_storyItem)) {
            work.story.privacy = new ArrayList<>(output);
        }
        host.edited(work.story);
        work.edit = false;
        if (!work.move) completeWork(work);
    }

    private TLRPC.TL_error restrictionError() {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 400;
        error.text = ERROR_NOT_RESTRICTED;
        return error;
    }

    private void move() {
        if (finished) return;
        ArrayList<Work> pending = new ArrayList<>();
        for (Work work : works) if (work.move && !work.edit && !work.done && !work.failed) pending.add(work);
        if (pending.isEmpty()) {
            if (editsDone) finish();
            else pump();
            return;
        }
        List<Work> chunk = new ArrayList<>(pending.subList(0, Math.min(REQUEST_CHUNK, pending.size())));
        TL_stories.togglePinned req = new TL_stories.togglePinned();
        req.peer = host.inputPeer();
        req.pinned = !hide;
        for (Work work : chunk) req.id.add(work.story.id);
        host.send(req, (response, error) -> {
            if (error != null) {
                if (finished) return;
                if (waitFlood(error, this::move)) return;
                for (Work work : chunk) failWork(work, error);
                if (error.code < 0 || isFlood(error)) fail(error);
                else move();
                return;
            }
            HashSet<Integer> confirmed = new HashSet<>();
            if (response instanceof Vector) confirmed.addAll(((Vector<?>) response).toIntArray());
            ArrayList<Work> ok = new ArrayList<>();
            ArrayList<Integer> check = new ArrayList<>();
            for (Work work : chunk) {
                if (confirmed.contains(work.story.id)) ok.add(work);
                else check.add(work.story.id);
            }
            applyMoved(ok);
            if (check.isEmpty()) {
                move();
                return;
            }
            fetch(check, found -> {
                ArrayList<Work> verified = new ArrayList<>();
                for (Work work : chunk) {
                    if (!check.contains(work.story.id)) continue;
                    TL_stories.StoryItem story = found.get(work.story.id);
                    if (story != null && story.pinned == !hide) verified.add(work);
                    else {
                        TLRPC.TL_error notMoved = new TLRPC.TL_error();
                        notMoved.code = 400;
                        notMoved.text = ERROR_NOT_MOVED;
                        failWork(work, notMoved);
                    }
                }
                applyMoved(verified);
                move();
            });
        });
    }

    private void applyMoved(ArrayList<Work> moved) {
        if (moved.isEmpty()) return;
        ArrayList<TL_stories.StoryItem> stories = new ArrayList<>();
        for (Work work : moved) {
            work.story.pinned = !hide;
            stories.add(work.story);
            completeWork(work);
        }
        if (hide) host.hidden(stories);
        else host.shown(stories);
    }

    private void completeWork(Work work) {
        if (work.done) return;
        work.done = true;
        doneCount++;
        result.changed++;
        if (!finished) host.progress(doneCount, works.size());
    }

    private void failWork(Work work, TLRPC.TL_error error) {
        work.failed = true;
        if (result.error == null) result.error = error;
    }

    private static boolean isFlood(TLRPC.TL_error error) {
        return error != null && (error.code == 420 || error.text != null && error.text.contains("FLOOD_"));
    }

    private static int floodSeconds(TLRPC.TL_error error) {
        if (error.text != null) {
            int index = error.text.lastIndexOf('_');
            if (index >= 0) {
                try {
                    return Integer.parseInt(error.text.substring(index + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 60;
    }

    /** Waits out a short limit so the batch finishes by itself; longer limits are shown to the user. */
    private boolean waitFlood(TLRPC.TL_error error, Runnable retry) {
        if (!isFlood(error)) return false;
        limit = 1;
        if (paused) return true;
        int seconds = floodSeconds(error);
        if (seconds > MAX_SINGLE_WAIT || waited + seconds > MAX_TOTAL_WAIT) {
            result.floodSeconds = seconds;
            return false;
        }
        waited += seconds;
        paused = true;
        countdown(seconds + 1, retry);
        return true;
    }

    private void countdown(int left, Runnable retry) {
        if (finished) return;
        if (left <= 0) {
            paused = false;
            if (retry != null) retry.run();
            else pump();
            return;
        }
        host.waiting(left);
        host.schedule(() -> countdown(left - 1, retry), 1);
    }

    private void stop(TLRPC.TL_error error) {
        stopped = true;
        if (result.error == null) result.error = error;
        queue.clear();
    }

    private void fail(TLRPC.TL_error error) {
        stop(error);
        finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        stopped = true;
        queue.clear();
        int total = selected.size();
        result.failed = 0;
        if (works.isEmpty() && result.changed == 0 && result.skipped == 0 && (result.error != null || result.cancelled)) {
            result.failed = total;
        } else {
            for (Work work : works) if (!work.done) result.failed++;
        }
        host.finished(result);
    }
}
