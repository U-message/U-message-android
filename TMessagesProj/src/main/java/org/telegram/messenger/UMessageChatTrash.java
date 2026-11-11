package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message "Chat trash": a deleted chat is only hidden locally for {@link #TTL_MS} and can be restored from
 * Settings → U message → Chat trash. After that (or on "Delete now") the real Telegram deletion / leave runs
 * with the parameters the user chose when deleting.
 */
public class UMessageChatTrash {

    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** deleteMode for "leave group / channel" (deleteParticipantFromChat) instead of deleteDialog */
    public static final int MODE_LEAVE = -1;
    private static final long CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    private static final String PREFS = "umessage_trash";

    public static class Entry {
        public long dialogId;
        public long trashedAt;
        public boolean revoke;
        public int deleteMode;

        public long getExpiresAt() {
            return trashedAt + TTL_MS;
        }

        public int getDaysLeft() {
            return (int) Math.max(0, (getExpiresAt() - System.currentTimeMillis() + 24 * 60 * 60 * 1000L - 1) / (24 * 60 * 60 * 1000L));
        }
    }

    private static final UMessageChatTrash[] instances = new UMessageChatTrash[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageChatTrash getInstance(int account) {
        final long uid = UMessageLocalData.ownerId(account);
        UMessageChatTrash t = instances[account];
        if (t == null || t.ownerUid != uid) {
            synchronized (UMessageChatTrash.class) {
                t = instances[account];
                if (t == null || t.ownerUid != uid) instances[account] = t = new UMessageChatTrash(account, uid);
            }
        }
        return t;
    }

    /** Logout / slot reuse: the next user on this slot gets their own trash. */
    static void resetAccount(int account) {
        synchronized (UMessageChatTrash.class) {
            instances[account] = null;
        }
    }

    public static boolean isTrashed(int account, long dialogId) {
        return getInstance(account).isTrashed(dialogId);
    }

    private final int account;
    private final long ownerUid;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private boolean scheduled;

    private UMessageChatTrash(int account, long ownerUid) {
        this.account = account;
        this.ownerUid = ownerUid;
        load();
    }

    private String key() {
        return UMessageLocalData.userKey(PREFS, "trash_", account, ownerUid);
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void load() {
        try {
            JSONArray array = new JSONArray(prefs().getString(key(), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry e = new Entry();
                e.dialogId = o.getLong("id");
                e.trashedAt = o.getLong("at");
                e.revoke = o.optBoolean("revoke", false);
                e.deleteMode = o.optInt("mode", 0);
                entries.put(e.dialogId, e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : entries.values()) {
                JSONObject o = new JSONObject();
                o.put("id", e.dialogId);
                o.put("at", e.trashedAt);
                o.put("revoke", e.revoke);
                o.put("mode", e.deleteMode);
                array.put(o);
            }
            prefs().edit().putString(key(), array.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public boolean isTrashed(long dialogId) {
        return !entries.isEmpty() && entries.containsKey(dialogId);
    }

    public int getCount() {
        return entries.size();
    }

    /** newest first */
    public ArrayList<Entry> getTrashed() {
        ArrayList<Entry> list = new ArrayList<>(entries.values());
        Collections.sort(list, (a, b) -> Long.compare(b.trashedAt, a.trashedAt));
        return list;
    }

    public Entry getEntry(long dialogId) {
        return entries.get(dialogId);
    }

    /**
     * @param deleteMode the {@code onlyHistory} value for MessagesController.deleteDialog or {@link #MODE_LEAVE}
     */
    public void trash(long dialogId, int deleteMode, boolean revoke) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            performDelete(dialogId, deleteMode, revoke);
            return;
        }
        Entry e = new Entry();
        e.dialogId = dialogId;
        e.trashedAt = System.currentTimeMillis();
        e.revoke = revoke;
        e.deleteMode = deleteMode;
        entries.put(dialogId, e);
        save();
        NotificationsController.getInstance(account).removeNotificationsForDialog(dialogId);
        refreshDialogs();
        scheduleCheck();
    }

    public void restore(long dialogId) {
        if (entries.remove(dialogId) != null) {
            save();
            refreshDialogs();
        }
    }

    public void deleteNow(long dialogId) {
        Entry e = entries.remove(dialogId);
        if (e != null) {
            save();
            performDelete(e.dialogId, e.deleteMode, e.revoke);
            refreshDialogs();
        }
    }

    public void emptyTrash() {
        ArrayList<Entry> list = getTrashed();
        entries.clear();
        save();
        for (Entry e : list) {
            performDelete(e.dialogId, e.deleteMode, e.revoke);
        }
        refreshDialogs();
    }

    /** Really deletes every chat that has been in the trash longer than {@link #TTL_MS}. */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Entry e : getTrashed()) {
            if (now - e.trashedAt >= TTL_MS && entries.remove(e.dialogId) != null) {
                changed = true;
                performDelete(e.dialogId, e.deleteMode, e.revoke);
            }
        }
        if (changed) {
            save();
            refreshDialogs();
        }
    }

    /** Runs purgeExpired() now and then once an hour while the app is alive. */
    public void scheduleCheck() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        AndroidUtilities.runOnUIThread(this::check);
    }

    private void check() {
        if (!UserConfig.getInstance(account).isClientActivated() || UMessageLocalData.ownerId(account) != ownerUid) {
            scheduled = false;
            return;
        }
        purgeExpired();
        if (entries.isEmpty()) {
            scheduled = false;
            return;
        }
        AndroidUtilities.runOnUIThread(this::check, CHECK_INTERVAL_MS);
    }

    private void performDelete(long dialogId, int deleteMode, boolean revoke) {
        AndroidUtilities.runOnUIThread(() -> {
            // never act on the account of another user who now uses this slot
            if (UMessageLocalData.ownerId(account) != ownerUid) return;
            MessagesController controller = MessagesController.getInstance(account);
            if (deleteMode == MODE_LEAVE) {
                TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat != null && !ChatObject.isNotInChat(chat)) {
                    TLRPC.User currentUser = controller.getUser(UserConfig.getInstance(account).getClientUserId());
                    controller.deleteParticipantFromChat(-dialogId, currentUser, null, revoke, false);
                } else {
                    controller.deleteDialog(dialogId, 0, revoke);
                }
            } else {
                controller.deleteDialog(dialogId, deleteMode, revoke);
            }
        });
    }

    private void refreshDialogs() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            MessagesController.getInstance(account).sortDialogs(null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }
}
