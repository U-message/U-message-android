package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Owner-only profile entries whose server copy has been moved to the story archive.
 * The server stays the source of truth: expired, unpinned stories are invisible to others.
 * This device only remembers that the owner still wants to see them in the profile grid.
 */
public final class UMessageHiddenStories {
    private static final int REQUEST_CHUNK = 100;
    private static final HashMap<String, LinkedHashMap<Integer, TL_stories.StoryItem>> cache = new HashMap<>();
    private static final HashSet<String> reconciled = new HashSet<>();

    private static String key(int account) {
        return account + "_" + UserConfig.getInstance(account).getClientUserId();
    }

    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_hidden_stories_" + key(account), 0);
    }

    private static LinkedHashMap<Integer, TL_stories.StoryItem> load(int account, long dialogId) {
        String key = key(account);
        LinkedHashMap<Integer, TL_stories.StoryItem> stories = cache.get(key);
        if (stories != null) return stories;
        stories = new LinkedHashMap<>();
        for (Object value : preferences(account).getAll().values()) {
            try {
                SerializedData data = new SerializedData(Base64.decode((String) value, Base64.NO_WRAP));
                TL_stories.StoryItem story = TL_stories.StoryItem.TLdeserialize(data, data.readInt32(true), true);
                data.cleanup();
                if (story instanceof TL_stories.TL_storyItem) {
                    story.dialogId = dialogId;
                    story.pinned = false;
                    stories.put(story.id, story);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        cache.put(key, stories);
        return stories;
    }

    private static boolean owner(int account, long dialogId) {
        return dialogId == UserConfig.getInstance(account).getClientUserId();
    }

    public static synchronized ArrayList<TL_stories.StoryItem> get(int account, long dialogId) {
        if (!owner(account, dialogId)) return new ArrayList<>();
        return new ArrayList<>(load(account, dialogId).values());
    }

    public static synchronized boolean contains(int account, long dialogId, int id) {
        return owner(account, dialogId) && load(account, dialogId).containsKey(id);
    }

    private static void write(SharedPreferences.Editor editor, TL_stories.StoryItem story) {
        SerializedData data = new SerializedData();
        story.serializeToStream(data);
        editor.putString(Integer.toString(story.id), Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP));
        data.cleanup();
    }

    public static synchronized void add(int account, long dialogId, List<TL_stories.StoryItem> items) {
        if (!owner(account, dialogId)) return;
        LinkedHashMap<Integer, TL_stories.StoryItem> stories = load(account, dialogId);
        SharedPreferences.Editor editor = preferences(account).edit();
        for (TL_stories.StoryItem story : items) {
            story.dialogId = dialogId;
            story.pinned = false;
            stories.put(story.id, story);
            write(editor, story);
        }
        editor.apply();
    }

    public static synchronized void remove(int account, long dialogId, List<TL_stories.StoryItem> items) {
        if (!owner(account, dialogId)) return;
        LinkedHashMap<Integer, TL_stories.StoryItem> stories = load(account, dialogId);
        SharedPreferences.Editor editor = preferences(account).edit();
        for (TL_stories.StoryItem story : items) {
            stories.remove(story.id);
            editor.remove(Integer.toString(story.id));
        }
        editor.apply();
    }

    /** A live server update: drop the marker when the story was deleted or published again elsewhere. */
    public static synchronized boolean onServerUpdate(int account, long dialogId, TL_stories.StoryItem story) {
        if (story == null || !contains(account, dialogId, story.id)) return false;
        if (story instanceof TL_stories.TL_storyItemDeleted || story.pinned) {
            ArrayList<TL_stories.StoryItem> gone = new ArrayList<>();
            gone.add(story);
            remove(account, dialogId, gone);
            return true;
        }
        if (story instanceof TL_stories.TL_storyItem && !story.min) {
            ArrayList<TL_stories.StoryItem> fresh = new ArrayList<>();
            fresh.add(story);
            add(account, dialogId, fresh);
            return true;
        }
        return false;
    }

    /** Applies a getStoriesByID answer for the requested markers. */
    public static synchronized void applyServerState(int account, long dialogId, List<Integer> requested, List<TL_stories.StoryItem> server) {
        HashMap<Integer, TL_stories.StoryItem> found = new HashMap<>();
        for (TL_stories.StoryItem story : server) found.put(story.id, story);
        ArrayList<TL_stories.StoryItem> gone = new ArrayList<>();
        ArrayList<TL_stories.StoryItem> fresh = new ArrayList<>();
        LinkedHashMap<Integer, TL_stories.StoryItem> stories = load(account, dialogId);
        for (int id : requested) {
            TL_stories.StoryItem current = stories.get(id);
            if (current == null) continue;
            TL_stories.StoryItem story = found.get(id);
            if (story == null || story instanceof TL_stories.TL_storyItemDeleted || story.pinned) gone.add(current);
            else if (story instanceof TL_stories.TL_storyItem && !story.min) fresh.add(story);
        }
        if (!gone.isEmpty()) remove(account, dialogId, gone);
        if (!fresh.isEmpty()) add(account, dialogId, fresh);
    }

    /** Once per process: fetches markers from the server so media references and deleted stories stay correct. */
    public static void reconcile(int account, long dialogId, Runnable changed) {
        ArrayList<TL_stories.StoryItem> stories = get(account, dialogId);
        if (stories.isEmpty()) return;
        String key = key(account);
        synchronized (UMessageHiddenStories.class) {
            if (!reconciled.add(key)) return;
        }
        for (int offset = 0; offset < stories.size(); offset += REQUEST_CHUNK) {
            TL_stories.TL_stories_getStoriesByID req = new TL_stories.TL_stories_getStoriesByID();
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            for (TL_stories.StoryItem story : stories.subList(offset, Math.min(offset + REQUEST_CHUNK, stories.size()))) req.id.add(story.id);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TL_stories.TL_stories_stories) {
                    applyServerState(account, dialogId, req.id, ((TL_stories.TL_stories_stories) response).stories);
                    if (changed != null) changed.run();
                } else {
                    synchronized (UMessageHiddenStories.class) {
                        reconciled.remove(key);
                    }
                }
            }));
        }
    }
}
