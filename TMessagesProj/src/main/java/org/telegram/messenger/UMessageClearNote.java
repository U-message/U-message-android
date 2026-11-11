package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * U message: when a chat with another U message user is cleared / deleted only on one side, the
 * other side gets a notice (optionally with a note, e.g. "Don't write, I'll write myself").
 * The notice is a normal text message whose leading emoji carries a hidden link; U message draws
 * it with Telegram's gift card (ChatActionCell), plain Telegram shows the readable text.
 */
public class UMessageClearNote {

    public static final int MAX_NOTE = 2000;
    private static final String LINK = UMessagePremiumController.BASE_URL + "/c#umc1";

    /** Note of a clear-notice message ("" when none), or null when it is not a notice. */
    public static String parse(TLRPC.Message m) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.CLEAR_NOTE)) return null;
        if (m == null || !(m.peer_id instanceof TLRPC.TL_peerUser) || m.entities == null || m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty) && !(m.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return null;
        }
        // only a notice written by one of the two people of this private chat: not forwarded,
        // not sent via an inline bot, and (when incoming) really from the chat peer
        if (m.fwd_from != null || m.via_bot_id != 0 || m.via_business_bot_id != 0) {
            return null;
        }
        if (!m.out && m.from_id != null && !(m.from_id instanceof TLRPC.TL_peerUser && m.from_id.user_id == m.peer_id.user_id)) {
            return null;
        }
        String t = m.message == null ? "" : m.message;
        int firstLineEnd = t.indexOf('\n');
        if (firstLineEnd < 0) firstLineEnd = t.length();
        // the first line is replaced by the localized title, so it must not hide anything else
        if (firstLineEnd > 120) {
            return null;
        }
        boolean found = false;
        for (int i = 0; i < m.entities.size(); i++) {
            TLRPC.MessageEntity e = m.entities.get(i);
            if (e instanceof TLRPC.TL_messageEntityTextUrl && e.offset == 0 && e.url != null && e.url.equals(LINK)) {
                found = true;
            } else if (e != null && e.offset < firstLineEnd) {
                return null;
            }
        }
        if (!found) {
            return null;
        }
        // the note is whatever follows the first line, so nothing of the real text is hidden
        if (firstLineEnd >= t.length()) {
            return "";
        }
        String rest = t.substring(firstLineEnd).trim();
        int c = rest.indexOf(": ");
        if (c > 0 && c <= 20 && rest.substring(0, c).indexOf(' ') < 0 && rest.substring(0, c).indexOf('\n') < 0) {
            rest = rest.substring(c + 2).trim(); // "<label>: <note>"
        }
        return rest;
    }

    /** Service line above the card: "Ali cleared the chat on their side" / "You cleared ...". */
    public static String title(int account, TLRPC.Message m) {
        if (m.out) {
            return LocaleController.getString(R.string.UMessageClearNoteClearedYou);
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(m.peer_id.user_id);
        return LocaleController.formatString(R.string.UMessageClearNoteCleared, user == null ? "" : UserObject.getFirstName(user));
    }

    public static boolean canNotify(int account, TLRPC.User user) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.CLEAR_NOTE)) return false;
        return user != null && !UserObject.isUserSelf(user) && !UserObject.isBot(user) && !UserObject.isDeleted(user)
                && user.id != UserConfig.getInstance(account).getClientUserId()
                && UMessagePremiumController.getInstance().isUMessageUser(user.id);
    }

    /** Sends the notice to {@code uid}; {@code onSent} runs on the UI thread once the server has it (or after a timeout). */
    public static void send(int account, long uid, String note, Runnable onSent) {
        note = note == null ? "" : note.trim();
        if (note.length() > MAX_NOTE) note = note.substring(0, MAX_NOTE);
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        String name = self == null ? "" : UserObject.getFirstName(self);
        String emoji = "✉️";
        StringBuilder text = new StringBuilder(emoji).append(' ')
                .append(LocaleController.formatString(R.string.UMessageClearNoteCleared, name));
        if (!note.isEmpty()) {
            text.append("\n\n").append(LocaleController.getString(R.string.UMessageClearNoteLabel)).append(": ").append(note);
        }
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.offset = 0;
        link.length = emoji.length();
        link.url = LINK; // long notes stay in the text, not in the link
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(link);

        final boolean[] done = new boolean[1];
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            nc.removeObserver(observer[0], NotificationCenter.messageReceivedByServer);
            nc.removeObserver(observer[0], NotificationCenter.messageSendError);
            if (onSent != null) onSent.run();
        };
        observer[0] = (id, acc, args) -> {
            if (id == NotificationCenter.messageReceivedByServer) {
                if (args.length > 3 && args[3] instanceof Long && (Long) args[3] == uid) {
                    // let the new id settle in the dialog before the history is cleared
                    AndroidUtilities.runOnUIThread(finish, 300);
                }
            } else if (id == NotificationCenter.messageSendError) {
                AndroidUtilities.runOnUIThread(finish);
            }
        };
        nc.addObserver(observer[0], NotificationCenter.messageReceivedByServer);
        nc.addObserver(observer[0], NotificationCenter.messageSendError);
        AndroidUtilities.runOnUIThread(finish, 8000);

        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text.toString(), uid);
        params.entities = entities;
        params.searchLinks = false;
        SendMessagesHelper.getInstance(account).sendMessage(params);
    }
}
