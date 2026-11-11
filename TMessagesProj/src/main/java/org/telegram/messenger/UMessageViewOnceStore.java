package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * U message "Save view-once media": opened view-once photos, videos, round videos, voice and audio are copied from Telegram's cache
 * into an app-private folder and stay there until the user shares, saves or deletes them.
 * Nothing is written to the gallery automatically.
 */
public class UMessageViewOnceStore {

    public static class Item {
        public String id;
        public String fileName;
        public boolean isVideo;
        public String peerName;
        public int date;
        public long ownerUserId;
        public long dialogId;
        private long dataGeneration = UMessageLocalData.getGeneration();

        public File getFile() {
            return new File(getDirectory(), fileName);
        }

        /** Photos and videos (round ones too) go back as media, voice and audio as files. */
        public boolean isMedia() {
            return isVideo || fileName.endsWith(".jpg");
        }
    }

    private static final Object lock = UMessageLocalData.LOCK;
    /** parsed index.json, reused while the file on disk is unchanged (guarded by lock) */
    private static ArrayList<Item> cachedIndex;
    /** cachedIndex by id, so contains() on every message bind never touches the disk (guarded by lock) */
    private static java.util.HashMap<String, Item> cachedById;
    /** only this class writes index.json, so the parsed copy stays valid until a save or a local data clear */
    private static long cachedGeneration = -1;
    /** ids being copied right now, so the same media is not copied twice (guarded by lock) */
    private static final HashSet<String> copying = new HashSet<>();

    public static File getDirectory() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "umessage-view-once");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File getIndexFile() {
        return new File(getDirectory(), "index.json");
    }

    /** A captured login, not just a reusable account slot. A logout/profile replacement invalidates it. */
    public static final class Session {
        public final int account;
        public final long ownerUserId;
        private final Object user;
        private final long generation;

        private Session(int account, long ownerUserId, Object user) {
            this.account = account;
            this.ownerUserId = ownerUserId;
            this.user = user;
            generation = UMessageLocalData.getGeneration();
        }

        public boolean isCurrent() {
            UserConfig config = UserConfig.getInstance(account);
            return generation == UMessageLocalData.getGeneration() && !UMessageLocalData.isClearing()
                    && config.isClientActivated() && config.getClientUserId() == ownerUserId;
            // no reference check on the TLRPC.User: putUser() replaces the self object all the time
        }
    }

    public static Session captureSession(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        UserConfig config = UserConfig.getInstance(account);
        Object user = config.getCurrentUser();
        long owner = config.getClientUserId();
        if (user == null || owner <= 0 || !config.isClientActivated()) return null;
        Session session = new Session(account, owner, user);
        return session.isCurrent() ? session : null;
    }

    public static String makeId(Session session, MessageObject messageObject) {
        return makeId(session, messageObject.getDialogId(), messageObject.getId());
    }

    public static String makeId(Session session, long dialogId, int messageId) {
        return session != null && session.isCurrent() && dialogId != 0 && messageId != 0
                ? "u" + session.ownerUserId + "_d" + dialogId + "_m" + messageId : null;
    }

    private static long[] identity(String id) {
        if (id == null || !id.matches("u[1-9][0-9]*_d-?[0-9]+_m-?[0-9]+")) return null;
        try {
            String[] parts = id.split("_");
            long owner = Long.parseLong(parts[0].substring(1));
            long dialog = Long.parseLong(parts[1].substring(1));
            int message = Integer.parseInt(parts[2].substring(1));
            return owner > 0 && dialog != 0 && message != 0 ? new long[]{owner, dialog, message} : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    /** Legacy slot-only records stay on disk, but are never assigned to the current user. */
    public static boolean owns(Session session, Item item) {
        if (session == null || item == null || !session.isCurrent()
                || item.dataGeneration != UMessageLocalData.getGeneration()) return false;
        long[] identity = identity(item.id);
        return identity != null && identity[0] == session.ownerUserId
                && item.ownerUserId == identity[0] && item.dialogId == identity[1]
                && item.fileName != null && item.fileName.startsWith(item.id + ".")
                && item.fileName.substring(item.id.length()).matches("\\.[A-Za-z0-9]{1,12}");
    }

    /** General settings and transient Save actions have no hidden-chat PIN unlock authority. */
    public static boolean canAccess(Session session, Item item) {
        return owns(session, item) && !UMessageConfig.isChatHidden(session.account, item.dialogId);
    }

    public static ArrayList<Item> getItems(Session session) {
        synchronized (lock) {
            ArrayList<Item> items = load();
            items.removeIf(item -> !canAccess(session, item));
            Collections.sort(items, (a, b) -> Integer.compare(b.date, a.date));
            return items;
        }
    }

    public static boolean contains(Session session, String id) {
        synchronized (lock) {
            final Item item = id == null ? null : index().get(id);
            return item != null && owns(session, item) && item.getFile().exists();
        }
    }

    /** Copies a completed cache file; returns the stored item (or the existing one), null on failure. */
    public static Item add(Session session, String id, File source, boolean isVideo, String ext, String peerName, int date) {
        long[] identity = identity(id);
        if (session == null || !session.isCurrent() || identity == null || identity[0] != session.ownerUserId
                || ext == null || !ext.matches("\\.[A-Za-z0-9]{1,12}")) return null;
        final Item item = new Item();
        item.id = id;
        item.fileName = id + ext;
        item.isVideo = isVideo;
        item.peerName = peerName;
        item.date = date;
        item.ownerUserId = session.ownerUserId;
        item.dialogId = identity[1];
        final File temp;
        final long generation;
        synchronized (lock) {
            generation = UMessageLocalData.getGeneration();
            if (!session.isCurrent() || !UMessageConfig.isSaveViewOnce()) return null;
            final Item existing = index().get(id);
            if (existing != null && existing.getFile().exists()) return owns(session, existing) ? existing : null;
            if (source == null || !source.exists() || !copying.add(id)) {
                return null;
            }
            temp = new File(getDirectory(), item.fileName + ".part");
        }
        // the copy (a whole video) runs outside the app-wide lock, only the index update holds it
        try {
            final boolean encrypted = source.getName().endsWith(".enc");
            final File key = new File(FileLoader.getInternalCacheDir(), source.getName() + ".key");
            try (InputStream in = encrypted
                    ? new org.telegram.messenger.secretmedia.EncryptedFileInputStream(source, key)
                    : new FileInputStream(source); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer, 0, buffer.length)) > 0) {
                    out.write(buffer, 0, read);
                }
            } catch (Exception e) {
                FileLog.e(e);
                temp.delete();
                return null;
            }
            synchronized (lock) {
                // local data may have been cleared (and saving switched off) while copying
                if (!session.isCurrent() || generation != UMessageLocalData.getGeneration() || !UMessageConfig.isSaveViewOnce() || !temp.exists() || !temp.renameTo(item.getFile())) {
                    temp.delete();
                    return null;
                }
                ArrayList<Item> items = load();
                items.add(item);
                save(items);
                return item;
            }
        } finally {
            synchronized (lock) {
                copying.remove(id);
            }
        }
    }

    public static void remove(Session session, String id) {
        synchronized (lock) {
            ArrayList<Item> items = load();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(id) && canAccess(session, items.get(i))) {
                    items.get(i).getFile().delete();
                    items.remove(i);
                    break;
                }
            }
            save(items);
        }
    }

    /** The parsed index by id; index.json is read once per process (and again after a save or a clear). */
    private static java.util.HashMap<String, Item> index() {
        final long generation = UMessageLocalData.getGeneration();
        if (cachedIndex == null || cachedById == null || cachedGeneration != generation) {
            final File index = getIndexFile();
            cachedIndex = index.exists() ? parse(index) : new ArrayList<>();
            cachedById = new java.util.HashMap<>();
            for (Item item : cachedIndex) cachedById.put(item.id, item);
            cachedGeneration = generation;
        }
        return cachedById;
    }

    /** A fresh list of the stored items whose files still exist. */
    private static ArrayList<Item> load() {
        ArrayList<Item> items = new ArrayList<>();
        index();
        for (Item item : cachedIndex) {
            if (item.getFile().exists()) {
                items.add(item);
            }
        }
        return items;
    }

    private static ArrayList<Item> parse(File index) {
        ArrayList<Item> items = new ArrayList<>();
        try (InputStream in = new FileInputStream(index)) {
            byte[] data = new byte[(int) index.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
            JSONArray array = new JSONArray(new String(data, 0, offset, "UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Item item = new Item();
                item.id = json.getString("id");
                item.fileName = json.getString("fileName");
                item.isVideo = json.optBoolean("isVideo");
                item.peerName = json.optString("peerName");
                item.date = json.optInt("date");
                item.ownerUserId = json.optLong("ownerUserId", 0);
                item.dialogId = json.optLong("dialogId", 0);
                items.add(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return items;
    }

    private static void save(ArrayList<Item> items) {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) {
                JSONObject json = new JSONObject();
                json.put("id", item.id);
                json.put("fileName", item.fileName);
                json.put("isVideo", item.isVideo);
                json.put("peerName", item.peerName);
                json.put("date", item.date);
                json.put("ownerUserId", item.ownerUserId);
                json.put("dialogId", item.dialogId);
                array.put(json);
            }
            // write a temp file and rename it over the index, so a crash mid-write cannot corrupt it
            final File index = getIndexFile();
            final File temp = new File(index.getParentFile(), index.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(array.toString().getBytes("UTF-8"));
                out.getFD().sync();
            }
            if (!temp.renameTo(index)) {
                temp.delete();
                throw new java.io.IOException("index rename failed");
            }
            cachedIndex = new ArrayList<>(items);
            cachedById = new java.util.HashMap<>();
            for (Item item : cachedIndex) cachedById.put(item.id, item);
            cachedGeneration = UMessageLocalData.getGeneration();
        } catch (Exception e) {
            FileLog.e(e);
            cachedIndex = null;
            cachedById = null;
        }
    }
}
