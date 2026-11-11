package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.configuration.LocalDirectory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArraySet;

/** Owns extended local copies and history only. Never sends Telegram delete RPCs. */
public final class UMessageLocalData {
    public static final Object LOCK = new Object();
    private static final AtomicBoolean clearing = new AtomicBoolean();
    private static final AtomicLong generation = new AtomicLong();
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    public interface Callback {
        void complete(long cacheBytes, long historyBytes, boolean success);
    }

    public static boolean isClearing() { return clearing.get(); }
    public static long getGeneration() { return generation.get(); }
    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    /* ------------------------------------------------------------------ per Telegram user storage */

    /** Per-slot files that now live in "<base>u<telegramUserId>". */
    static final String FILE_HISTORY = "umessage_history_";
    static final String FILE_FRIENDS = "umfriends_";
    static final String FILE_PARENTAL = "umparental_";
    private static final String[] USER_FILES = {FILE_HISTORY, FILE_FRIENDS, FILE_PARENTAL};
    /** {shared file, key prefix}: "<prefix><slot>[_...]" keys that now live under "<prefix>u<telegramUserId>[_...]". */
    private static final String[][] USER_KEYS = {
            {"umessage_trash", "trash_"},
            {"umessage", "hidden_chats_"},
            {"umessage", "hidden_folders_"},
            {"umessage", "folder_emoticon_"},
            {"umessage", "admin_folders_"},
    };
    private static final Object MIGRATION_LOCK = new Object();
    private static final java.util.Set<String> migrated = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Telegram user id logged in on the slot, 0 when the slot is empty. */
    public static long ownerId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0;
        return UserConfig.getInstance(account).getClientUserId();
    }

    public static SharedPreferences userPrefs(String base, int account) {
        return userPrefs(base, account, ownerId(account));
    }

    /**
     * Preferences that belong to a Telegram user instead of an account slot. The legacy "<base><slot>" file is
     * handed once to the user logged in on that slot.
     */
    public static SharedPreferences userPrefs(String base, int account, long uid) {
        if (uid != 0) migrateFile(base, account, uid);
        return ApplicationLoader.applicationContext.getSharedPreferences(base + "u" + uid, Context.MODE_PRIVATE);
    }

    /** Key prefix "<prefix>u<uid>" inside a shared file; legacy "<prefix><slot>[_...]" keys move to it once. */
    public static String userKey(String file, String prefix, int account, long uid) {
        if (uid != 0) migrateKeys(file, prefix, account, uid);
        return prefix + "u" + uid;
    }

    public static String userKey(String file, String prefix, int account) {
        return userKey(file, prefix, account, ownerId(account));
    }

    private static void migrateFile(String base, int account, long uid) {
        final String id = "f|" + base + account;
        if (migrated.contains(id)) return;
        synchronized (MIGRATION_LOCK) {
            if (migrated.contains(id)) return;
            try {
                final SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences(base + account, Context.MODE_PRIVATE);
                final Map<String, ?> values = legacy.getAll();
                if (!values.isEmpty()) {
                    final SharedPreferences target = ApplicationLoader.applicationContext.getSharedPreferences(base + "u" + uid, Context.MODE_PRIVATE);
                    final SharedPreferences.Editor editor = target.edit();
                    for (Map.Entry<String, ?> entry : values.entrySet()) {
                        if (!target.contains(entry.getKey())) put(editor, entry.getKey(), entry.getValue());
                    }
                    if (!editor.commit()) return;
                    legacy.edit().clear().commit();
                }
                migrated.add(id);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static void migrateKeys(String file, String prefix, int account, long uid) {
        final String id = "k|" + file + "|" + prefix + account;
        if (migrated.contains(id)) return;
        synchronized (MIGRATION_LOCK) {
            if (migrated.contains(id)) return;
            try {
                final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE);
                final String legacy = prefix + account;
                final String target = prefix + "u" + uid;
                SharedPreferences.Editor editor = null;
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    final String key = entry.getKey();
                    if (!key.startsWith(legacy)) continue;
                    final String suffix = key.substring(legacy.length());
                    if (!suffix.isEmpty() && suffix.charAt(0) != '_') continue;
                    if (editor == null) editor = prefs.edit();
                    if (!prefs.contains(target + suffix)) put(editor, target + suffix, entry.getValue());
                    editor.remove(key);
                }
                if (editor != null && !editor.commit()) return;
                migrated.add(id);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
    }

    /** Moves every legacy per-slot store of this slot to the user logged in on it. */
    public static void migrateAccount(int account) {
        final long uid = ownerId(account);
        if (uid == 0) return;
        for (String base : USER_FILES) migrateFile(base, account, uid);
        for (String[] key : USER_KEYS) migrateKeys(key[0], key[1], account, uid);
    }

    /**
     * The slot is being logged out or reused (UserConfig.clearConfig). Legacy slot data still goes to the user
     * leaving, then every in-memory instance of the slot is dropped so the next user never sees it. Per-user data
     * stays on disk (it returns when that user logs in again); pure caches are wiped.
     */
    public static void onAccountReset(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        try { migrateAccount(account); } catch (Exception e) { FileLog.e(e); }
        try { UMessageFriendLocations.resetAccount(account); } catch (Exception e) { FileLog.e(e); }
        try { UMessageParental.resetAccount(account); } catch (Exception e) { FileLog.e(e); }
        try { UMessageChatTrash.resetAccount(account); } catch (Exception e) { FileLog.e(e); }
        try { UMessageConfig.resetAccount(account); } catch (Exception e) { FileLog.e(e); }
        try { UMessageStories.resetAccount(account); } catch (Exception e) { FileLog.e(e); }
    }

    private static SharedPreferences preferences(int account) {
        return UMessageHistory.prefs(account);
    }

    private static File directory() throws IOException {
        return new File(ApplicationLoader.getFilesDirFixed().getCanonicalFile(), "umessage-view-once");
    }

    public static void inspect(Callback callback) {
        Utilities.globalQueue.postRunnable(() -> processAccount(0, false, new long[2], callback));
    }

    /** The UI must ask for confirmation before invoking this method. */
    public static void clear(Callback callback) {
        synchronized (LOCK) {
            if (!clearing.compareAndSet(false, true)) {
                AndroidUtilities.runOnUIThread(() -> callback.complete(0, 0, false));
                return;
            }
            generation.incrementAndGet();
        }
        try {
            UMessageConfig.set(UMessageConfig.KEY_SAVE_EDITED, false);
            UMessageConfig.set(UMessageConfig.KEY_SAVE_DELETED, false);
            UMessageConfig.set(UMessageConfig.KEY_SAVE_VIEW_ONCE, false);
            Utilities.globalQueue.postRunnable(() -> processAccount(0, true, new long[2], callback));
        } catch (Exception exception) {
            FileLog.e(exception);
            finish(true, new long[2], false, callback);
        }
    }

    private static void processAccount(int account, boolean remove, long[] totals, Callback callback) {
        try {
            processAccountInternal(account, remove, totals, callback);
        } catch (Exception exception) {
            FileLog.e(exception);
            finish(remove, totals, false, callback);
        }
    }

    private static void processAccountInternal(int account, boolean remove, long[] totals, Callback callback) {
        if (account == UserConfig.MAX_ACCOUNT_COUNT) {
            Utilities.globalQueue.postRunnable(() -> {
                boolean success = true;
                try {
                    synchronized (LOCK) {
                        if (remove) {
                            LocalDirectory.clear(directory());
                            clearOtherUsersHistory();
                        }
                        totals[0] = LocalDirectory.size(directory());
                    }
                } catch (Exception exception) {
                    FileLog.e(exception);
                    success = false;
                }
                finish(remove, totals, success, callback);
            });
            return;
        }
        final SharedPreferences preferences = preferences(account);
        final Map<String, ?> values = preferences.getAll();
        final HashMap<Long, ArrayList<Integer>> groups = new HashMap<>();
        try {
            for (String key : values.keySet()) {
                if (!key.startsWith("d_") || !Boolean.TRUE.equals(values.get(key))) continue;
                final String[] parts = key.split("_");
                if (parts.length != 3) throw new IOException("Invalid local history key");
                final long idSpace = Long.parseLong(parts[1]);
                final int id = Integer.parseInt(parts[2]);
                if (idSpace > 0 || id <= 0) throw new IOException("Invalid local history id");
                ArrayList<Integer> ids = groups.get(idSpace);
                if (ids == null) groups.put(idSpace, ids = new ArrayList<>());
                ids.add(id);
            }
        } catch (Exception exception) {
            FileLog.e(exception);
            finish(remove, totals, false, callback);
            return;
        }
        if (groups.isEmpty()) {
            if (!updatePreferences(account, preferences, values, remove, totals)) {
                finish(remove, totals, false, callback);
                return;
            }
            processAccount(account + 1, remove, totals, callback);
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                for (Map.Entry<Long, ArrayList<Integer>> group : groups.entrySet()) {
                    final long idSpace = group.getKey();
                    final ArrayList<Integer> allIds = group.getValue();
                    for (int start = 0; start < allIds.size(); start += 200) {
                        final ArrayList<Integer> ids = new ArrayList<>(allIds.subList(start, Math.min(start + 200, allIds.size())));
                        final String predicate = "mid IN (" + TextUtils.join(",", ids) + ") AND "
                                + (idSpace == 0 ? "is_channel = 0" : "uid = " + idSpace);
                        if (remove) {
                            final ArrayList<Long> dialogs = storage.markMessagesAsDeleted(idSpace, ids, false, false, 0, 0);
                            storage.updateDialogsWithDeletedMessages(idSpace, -idSpace, ids, dialogs);
                        }
                        SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                                "SELECT COUNT(*), COALESCE(SUM(length(data) + COALESCE(length(replydata), 0) + COALESCE(length(custom_params), 0)), 0) FROM messages_v2 WHERE " + predicate);
                        try {
                            if (cursor.next()) {
                                if (remove && cursor.intValue(0) != 0) throw new IOException("Local rows remain");
                                totals[1] += cursor.longValue(1);
                            }
                        } finally {
                            cursor.dispose();
                        }
                        if (remove) AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account)
                                .postNotificationName(NotificationCenter.messagesDeleted, ids, -idSpace, false));
                    }
                }
                if (!updatePreferences(account, preferences, values, remove, totals)) throw new IOException("Local preferences remain");
                processAccount(account + 1, remove, totals, callback);
            } catch (Exception exception) {
                FileLog.e(exception);
                finish(remove, totals, false, callback);
            }
        });
    }

    private static boolean updatePreferences(int account, SharedPreferences preferences, Map<String, ?> values, boolean remove, long[] totals) {
        synchronized (LOCK) {
            if (remove) return preferences.edit().clear().commit();
            if (!values.isEmpty()) {
                File root = ApplicationLoader.applicationContext.getApplicationInfo().dataDir == null ? null
                        : new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "shared_prefs");
                if (root != null) totals[1] += new File(root, FILE_HISTORY + "u" + ownerId(account) + ".xml").length();
            }
            return true;
        }
    }

    /** History of users that are not logged in right now (their message rows are gone with their database). */
    private static void clearOtherUsersHistory() {
        final String dataDir = ApplicationLoader.applicationContext.getApplicationInfo().dataDir;
        final File[] files = dataDir == null ? null : new File(dataDir, "shared_prefs").listFiles();
        if (files == null) return;
        for (File file : files) {
            final String name = file.getName();
            if (!name.startsWith(FILE_HISTORY) || !name.endsWith(".xml")) continue;
            ApplicationLoader.applicationContext.getSharedPreferences(name.substring(0, name.length() - 4), Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private static void finish(boolean remove, long[] totals, boolean success, Callback callback) {
        if (remove) clearing.set(false);
        AndroidUtilities.runOnUIThread(() -> {
            try { callback.complete(totals[0], totals[1], success); }
            catch (Exception exception) { FileLog.e(exception); }
            if (remove) {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.umessageMessagesChanged);
                }
                for (Runnable listener : listeners) {
                    try { listener.run(); } catch (Exception exception) { FileLog.e(exception); }
                }
            }
        });
    }
}
