package org.telegram.ui;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UMessageLocalData;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UMessageViewOnceStore;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;

/**
 * U message "Save view-once media": opening a kept view-once photo/video (voice and round ones only get a copy) still tells the sender it was
 * opened, but nothing expires locally and a copy goes to U message's cache (see UMessageViewOnceStore).
 */
public class UMessageViewOnce {

    /** Call when the user opens a message for which {@link MessageObject#umKeepsViewOnce()} is true. */
    public static void onOpened(int account, MessageObject messageObject) {
        if (messageObject.isContentUnread()) {
            MessagesController.getInstance(account).markMessageContentAsRead(messageObject);
        }
        saveCopy(account, messageObject);
    }

    /**
     * Voice and round view-once messages still burn as in Telegram; only a copy is kept when they are played.
     */
    public static void onPlayedOnce(int account, MessageObject messageObject) {
        if (!UMessageConfig.isSaveViewOnce() || messageObject.isOutOwner()) return;
        saveCopy(account, messageObject);
    }

    // All access is on the UI thread; repeated cell binds must not install duplicate observers.
    private static final java.util.HashMap<String, Runnable> pending = new java.util.HashMap<>();
    private static final java.util.HashSet<String> copying = new java.util.HashSet<>();
    private static final java.util.HashMap<String, File> saved = new java.util.HashMap<>();
    private static long savedGeneration = -1;
    private static final java.util.HashSet<Integer> observedAccounts = new java.util.HashSet<>();

    private static void observeLogout(int account) {
        if (!observedAccounts.add(account)) return;
        NotificationCenter.getInstance(account).addObserver((notification, acc, args) -> {
            // Cancel download observers immediately: slot reuse must not complete an older user's copy.
            for (Runnable cleanup : new ArrayList<>(pending.values())) {
                cleanup.run();
                AndroidUtilities.cancelRunOnUIThread(cleanup);
            }
            pending.clear();
            copying.clear();
            saved.clear();
            savedGeneration = -1;
        }, NotificationCenter.appDidLogout);
    }

    /** Watch incoming expiring attachments, including GIFs, stickers, audio and secret-chat files. */
    public static void onMediaAvailable(int account, MessageObject message) {
        if (message == null || !UMessageConfig.isSaveViewOnce() || message.isOutOwner()) return;
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media == null || media.ttl_seconds == 0 && message.messageOwner.ttl <= 0) return;
        if (message.getDocument() == null && media.photo == null) return;
        saveCopy(account, message);
    }

    private static File resolveFile(int account, MessageObject message) {
        File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        if (file != null) {
            File encrypted = new File(file.getAbsolutePath() + ".enc");
            if (encrypted.isFile()) return encrypted;
            if (file.isFile()) return file;
        }
        if (message.messageOwner.attachPath != null && !message.messageOwner.attachPath.isEmpty()) {
            File attached = new File(message.messageOwner.attachPath);
            if (attached.isFile()) return attached;
        }
        return null;
    }

    private static void saveCopy(int account, MessageObject messageObject) {
        if (!UMessageConfig.isSaveViewOnce() || messageObject.isOutOwner()) return;
        final UMessageViewOnceStore.Session session = UMessageViewOnceStore.captureSession(account);
        if (session == null) return;
        observeLogout(account);
        final long operationGeneration = UMessageLocalData.getGeneration();
        if (savedGeneration != operationGeneration) {
            saved.clear();
            savedGeneration = operationGeneration;
        }
        final String id = UMessageViewOnceStore.makeId(session, messageObject);
        if (id == null) return;
        File retained = saved.get(id);
        if (retained != null && retained.isFile()) {
            messageObject.messageOwner.attachPath = retained.getAbsolutePath();
            messageObject.attachPathExists = true;
            return;
        }
        if (pending.containsKey(id)) return;
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final String attachName = FileLoader.getAttachFileName(messageObject.getDocument() != null ? messageObject.getDocument() : FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, AndroidUtilities.getPhotoSize(true), false, null, true));
        if (attachName == null || attachName.isEmpty()) return;
        NotificationCenter.NotificationCenterDelegate observer = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int notification, int acc, Object... args) {
                if (!session.isCurrent()) {
                    Runnable stale = pending.remove(id);
                    if (stale != null) { stale.run(); AndroidUtilities.cancelRunOnUIThread(stale); }
                    return;
                }
                if (args.length == 0 || !attachName.equals(args[0])) return;
                Runnable cleanup = pending.remove(id);
                if (cleanup != null) {
                    cleanup.run();
                    AndroidUtilities.cancelRunOnUIThread(cleanup);
                }
                if (notification == NotificationCenter.fileLoaded) {
                    File loaded = resolveFile(account, messageObject);
                    if (loaded == null && args.length > 1 && args[1] instanceof File) loaded = (File) args[1];
                    keep(session, messageObject, id, loaded, operationGeneration);
                }
            }
        };
        Runnable cleanup = () -> {
            center.removeObserver(observer, NotificationCenter.fileLoaded);
            center.removeObserver(observer, NotificationCenter.fileLoadFailed);
            pending.remove(id);
        };
        pending.put(id, cleanup);
        center.addObserver(observer, NotificationCenter.fileLoaded);
        center.addObserver(observer, NotificationCenter.fileLoadFailed);
        AndroidUtilities.runOnUIThread(cleanup, 10 * 60 * 1000);
        // Subscribe before checking disk so a completed download cannot be missed.
        File file = resolveFile(account, messageObject);
        if (file != null) {
            cleanup.run();
            AndroidUtilities.cancelRunOnUIThread(cleanup);
            keep(session, messageObject, id, file, operationGeneration);
        } else {
            // The original cache may have been evicted while a retained copy still exists.
            keep(session, messageObject, id, null, operationGeneration);
        }
    }

    private static void keep(UMessageViewOnceStore.Session session, MessageObject messageObject, String id, File file, long operationGeneration) {
        if (!session.isCurrent()) return;
        final int account = session.account;
        final boolean ownsCopy = file != null;
        if (ownsCopy && !copying.add(id)) return;
        final boolean isVideo = messageObject.isVideo() || messageObject.isRoundVideo();
        final String ext = getExtension(messageObject, file);
        final String peerName = getPeerName(account, messageObject);
        final int date = messageObject.messageOwner.date;
        Utilities.globalQueue.postRunnable(() -> {
            // index lookup reads disk under the app-wide data lock: never on the UI thread
            UMessageViewOnceStore.Item stored = null;
            boolean alreadySaved = false;
            try {
                if (session.isCurrent() && operationGeneration == UMessageLocalData.getGeneration() && UMessageConfig.isSaveViewOnce()) {
                    alreadySaved = UMessageViewOnceStore.contains(session, id);
                    stored = UMessageViewOnceStore.add(session, id, file, isVideo, ext, peerName, date);
                }
            } finally {
                if (ownsCopy) AndroidUtilities.runOnUIThread(() -> copying.remove(id));
            }
            final UMessageViewOnceStore.Item item = stored;
            final boolean showBulletin = !alreadySaved;
            if (item == null) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (!UMessageViewOnceStore.owns(session, item) || operationGeneration != UMessageLocalData.getGeneration() || !UMessageConfig.isSaveViewOnce()) return;
                saved.put(id, item.getFile());
                messageObject.messageOwner.attachPath = item.getFile().getAbsolutePath();
                messageObject.attachPathExists = true;
                if (!showBulletin || !UMessageViewOnceStore.canAccess(session, item)) return;
                BulletinFactory.global().createSimpleBulletin(R.raw.fire_on,
                    LocaleController.getString(R.string.UMessageViewOnceKept),
                    LocaleController.getString(R.string.Save),
                    () -> sendToSaved(session, item)).show();
            });
        });
    }

    private static String getExtension(MessageObject messageObject, File file) {
        if (messageObject.isVideo() || messageObject.isRoundVideo()) return ".mp4";
        if (messageObject.isVoice()) return ".ogg";
        if (messageObject.getDocument() == null) return ".jpg";
        final String name = FileLoader.getDocumentFileName(messageObject.getDocument());
        final int dot = name.lastIndexOf('.');
        if (dot > 0 && name.substring(dot + 1).matches("[A-Za-z0-9]{1,12}")) return name.substring(dot).toLowerCase(java.util.Locale.US);
        final String mime = messageObject.getDocument().mime_type;
        final String extension = mime == null ? null : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return extension == null ? ".bin" : "." + extension;
    }

    private static String getPeerName(int account, MessageObject messageObject) {
        final MessagesController controller = MessagesController.getInstance(account);
        final long fromId = messageObject.getFromChatId();
        if (fromId > 0) {
            final TLRPC.User user = controller.getUser(fromId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else {
            final TLRPC.Chat chat = controller.getChat(-fromId);
            if (chat != null) {
                return chat.title;
            }
        }
        return "";
    }

    /** Sends the kept copy to Saved Messages as a new message from the user. */
    public static void sendToSaved(UMessageViewOnceStore.Session session, UMessageViewOnceStore.Item item) {
        if (!UMessageViewOnceStore.canAccess(session, item)) return;
        final int account = session.account;
        final File file = item.getFile();
        if (!file.exists()) {
            return;
        }
        if (!item.isMedia()) {
            if (!UMessageViewOnceStore.canAccess(session, item)) return;
            SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), file.getAbsolutePath(), file.getAbsolutePath(), null, null, null,
                    session.ownerUserId, null, null, null, null, null, true, 0, null, SendMessageChatArguments.EMPTY, false);
            BulletinFactory.global().createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.UMessageViewOnceSentToSaved)).show();
            return;
        }
        final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = file.getAbsolutePath();
        info.isVideo = item.isVideo;
        final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        media.add(info);
        if (!UMessageViewOnceStore.canAccess(session, item)) return;
        SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, session.ownerUserId,
                null, null, null, null, false, false, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
        BulletinFactory.global().createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.UMessageViewOnceSentToSaved)).show();
    }
}
