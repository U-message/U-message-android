package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.Components.UMessageSecurityGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Group anti-scam: spam bots join groups posing as a girl, post an attractive photo and keep a
 * channel link in their bio or personal channel. That channel shows a lure image with a scam link.
 * Accounts are scored on join / first message; once the score crosses the threshold the account is
 * flagged for that group and its messages are hidden locally (new ones and loaded history).
 */
public final class UMessageGroupGuard {

    private static final int BAN_THRESHOLD = 7;
    private static final long RECHECK_INTERVAL = 6L * 60 * 60 * 1000;
    private static final Pattern LINK = Pattern.compile("(https?://|t\\.me/|telegram\\.me/|@[a-zA-Z0-9_]{5,}|\\.(uz|ru|com|net|org|top|xyz|site|online|click|lol|icu|cc|me)\\b)", Pattern.CASE_INSENSITIVE);
    private static final String[] LURE_TEXT = {"yozing", "yozinglar", "shaxsiy", "lichka", "lichkaga", "intim", "tanish", "yolg'iz", "yolgiz", "zerik", "kanalimga", "kanalim",
            "profilimga", "profilim", "bioda", "biomda", "ssilka", "silteme", "havola", "18+", "xxx", "sex", "seks", "qiz", "qizlar", "ayol", "juvon", "uchrash", "vstrech",
            "znakom", "пишите", "пиши", "лс", "личк", "одинок", "скуча", "девушк", "интим", "канал", "профил", "био", "ссылк", "video", "rasm", "foto", "pul ishlash",
            "daromad", "kazino", "casino", "stavka", "1xbet", "pin-up", "mostbet", "bonus", "sovg'a", "sovga", "yutib", "yutuq", "kripto", "crypto", "usdt", "investitsiya"};

    /** account -> (chatId:userId -> last check time) */
    private static final HashMap<String, Long> checked = new HashMap<>();
    private static final HashSet<String> flagged = new HashSet<>();
    /** account:userId -> {time, profileScore, channelScore} of the last profile lookup (message path throttle). */
    private static final HashMap<String, long[]> userScores = new HashMap<>();
    /** account:userId -> start time of a lookup still running (getFullUser does not call back on errors) */
    private static final HashMap<String, Long> pendingUsers = new HashMap<>();
    private static final long PENDING_TIMEOUT = 2 * 60 * 1000;
    /** account:channelId -> {time, score} */
    private static final HashMap<String, long[]> channelScores = new HashMap<>();
    private static boolean flaggedLoaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_group_guard", Context.MODE_PRIVATE);
    }

    private static void ensureFlaggedLoaded() {
        if (flaggedLoaded) {
            return;
        }
        flaggedLoaded = true;
        try {
            flagged.addAll(prefs().getAll().keySet());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean isFlagged(int account, long chatId, long userId) {
        ensureFlaggedLoaded();
        return flagged.contains(account + ":" + chatId + ":" + userId);
    }

    /** Drops messages of flagged accounts from a list about to reach the UI. */
    public static void filter(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (dialogId >= 0 || messages == null || messages.isEmpty() || !UMessageConfig.isGroupSpamGuard()) {
            return;
        }
        ensureFlaggedLoaded();
        if (flagged.isEmpty()) {
            return;
        }
        long chatId = -dialogId;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message == null || message.isOut()) {
                continue;
            }
            long senderId = message.getSenderId();
            if (senderId > 0 && flagged.contains(account + ":" + chatId + ":" + senderId)) {
                messages.remove(i);
            }
        }
    }

    private UMessageGroupGuard() {
    }

    public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (dialogId >= 0 || messages == null || messages.isEmpty() || !UMessageConfig.isGroupSpamGuard()) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null || ChatObject.isChannelAndNotMegaGroup(chat)) {
            return;
        }
        filter(account, dialogId, messages);
        for (MessageObject message : messages) {
            if (message == null || message.messageOwner == null || message.isOut()) {
                continue;
            }
            TLRPC.Message owner = message.messageOwner;
            if (owner.action instanceof TLRPC.TL_messageActionChatAddUser) {
                for (long userId : ((TLRPC.TL_messageActionChatAddUser) owner.action).users) {
                    check(account, chat, controller.getUser(userId), null);
                }
            } else if (owner.action instanceof TLRPC.TL_messageActionChatJoinedByLink || owner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) {
                check(account, chat, controller.getUser(message.getSenderId()), null);
            } else if (owner.action == null || owner.action instanceof TLRPC.TL_messageActionEmpty) {
                long senderId = message.getSenderId();
                if (senderId > 0) {
                    check(account, chat, controller.getUser(senderId), message);
                }
            }
        }
    }

    /**
     * Decision rule (deliberately strict so real members are never hidden):
     *  - Telegram's own scam/fake flag -> hide.
     *  - Otherwise a linked channel (bio or personal channel) MUST exist and its latest posts must be
     *    a lure image with an external link (channelScore >= 4). On top of that the member must have
     *    posted something spam-like (messageScore >= 2), or, on a silent join, the channel must be
     *    unmistakable (channelScore >= 6) and both bio link and personal channel present.
     *  - No channel, big channel (> 2000 members) or verified channel -> never hidden.
     */
    private static void check(int account, TLRPC.Chat chat, TLRPC.User user, MessageObject message) {
        if (user == null || user.self || user.contact || user.mutual_contact || user.bot || UserObject.isService(user.id) || user.verified) {
            return;
        }
        String key = account + ":" + chat.id + ":" + user.id;
        Long last = checked.get(key);
        long now = System.currentTimeMillis();
        if (message == null && last != null && now - last < RECHECK_INTERVAL) {
            return;
        }
        checked.put(key, now);

        if (user.scam || user.fake) {
            hide(account, chat, user, message, "scam flag");
            return;
        }
        final int messageScore = scoreMessage(message);
        if (message != null && messageScore < 2) {
            return; // plain chat messages are never a reason to look further
        }
        // one profile/channel lookup per user per interval, whatever the number of chats or messages:
        // every lookup is loadFullUser + resolveUsername + getHistory, which quickly ends in FLOOD_WAIT
        final String userKey = account + ":" + user.id;
        long[] cached = userScores.get(userKey);
        if (cached != null && now - cached[0] < RECHECK_INTERVAL) {
            decide(account, chat, user, message, messageScore, (int) cached[1], (int) cached[2]);
            return;
        }
        Long pendingSince = pendingUsers.get(userKey);
        if (pendingSince != null && now - pendingSince < PENDING_TIMEOUT) {
            return;
        }
        pendingUsers.put(userKey, now);
        MessagesController controller = MessagesController.getInstance(account);
        controller.loadFullUser(user, 0, true, full -> AndroidUtilities.runOnUIThread(() -> {
            String about = full != null && full.about != null ? full.about : "";
            boolean linkInBio = LINK.matcher(about).find();
            long channelId = full != null ? full.personal_channel_id : 0;
            final int profileScore = (linkInBio ? 2 : 0) + (channelId != 0 ? 2 : 0);
            if (profileScore == 0) {
                pendingUsers.remove(userKey);
                userScores.put(userKey, new long[]{System.currentTimeMillis(), 0, 0});
                return; // no channel to verify against -> not the scheme we target
            }
            Utilities.Callback<Integer> onChannel = channelScore -> {
                pendingUsers.remove(userKey);
                userScores.put(userKey, new long[]{System.currentTimeMillis(), profileScore, channelScore});
                decide(account, chat, user, message, messageScore, profileScore, channelScore);
            };
            if (channelId != 0) {
                inspectChannel(account, controller.getChat(channelId), onChannel);
            } else {
                resolveLink(account, about, resolved -> {
                    if (resolved == null) {
                        onChannel.run(0);
                        return;
                    }
                    inspectChannel(account, resolved, onChannel);
                });
            }
        }));
    }

    private static void decide(int account, TLRPC.Chat chat, TLRPC.User user, MessageObject message, int messageScore, int profileScore, int channelScore) {
        if (profileScore == 0) {
            return;
        }
        boolean spam = channelScore >= 4 && messageScore >= 2
                || message == null && channelScore >= 6 && profileScore >= 4;
        if (spam) {
            hide(account, chat, user, message, "msg " + messageScore + " profile " + profileScore + " channel " + channelScore);
        }
    }

    private static boolean hasFemaleLook(TLRPC.User user) {
        // Any profile is a candidate; this only short-circuits users with no message and no bio.
        return true;
    }

    private static int scoreMessage(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return 0;
        }
        int score = 0;
        TLRPC.Message owner = message.messageOwner;
        if (owner.media instanceof TLRPC.TL_messageMediaPhoto) {
            score += 1;
        }
        String text = owner.message == null ? "" : owner.message;
        if (LINK.matcher(text).find()) {
            score += 2;
        }
        if (owner.entities != null) {
            for (TLRPC.MessageEntity entity : owner.entities) {
                if (entity instanceof TLRPC.TL_messageEntityTextUrl && UMessageSecurityGuard.isSuspiciousUrl(((TLRPC.TL_messageEntityTextUrl) entity).url)) {
                    score += 3;
                } else if (entity instanceof TLRPC.TL_messageEntityUrl && UMessageSecurityGuard.isSuspiciousUrl(text.substring(entity.offset, Math.min(text.length(), entity.offset + entity.length)))) {
                    score += 3;
                }
            }
        }
        if (owner.reply_markup != null && hasUrlButton(owner.reply_markup)) {
            score += 2;
        }
        if (owner.media instanceof TLRPC.TL_messageMediaWebPage) {
            score += 1;
        }
        score += lureScore(text);
        return score;
    }

    private static int lureScore(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String word : LURE_TEXT) {
            if (lower.contains(word)) {
                hits++;
            }
        }
        return Math.min(4, hits * 2);
    }

    private static boolean hasUrlButton(TLRPC.ReplyMarkup markup) {
        return !buttonUrls(markup).isEmpty();
    }

    private static ArrayList<String> buttonUrls(TLRPC.ReplyMarkup markup) {
        ArrayList<String> urls = new ArrayList<>();
        if (!(markup instanceof TLRPC.TL_replyInlineMarkup)) {
            return urls;
        }
        for (TL_keyboard.KeyboardInlineButtonRow row : ((TLRPC.TL_replyInlineMarkup) markup).rows) {
            for (TL_keyboard.KeyboardInlineButton button : row.buttons) {
                Object type = button.getType();
                if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
                    urls.add(((TL_keyboard.TL_inlineButtonTypeUrl) type).url);
                } else if (type instanceof TL_keyboard.TL_buttonTypeSimpleWebView) {
                    urls.add(((TL_keyboard.TL_buttonTypeSimpleWebView) type).url);
                }
            }
        }
        return urls;
    }

    /** Scammer channels: last posts are a lure image with an external / suspicious link under it. */
    private static void inspectChannel(int account, TLRPC.Chat channel, Utilities.Callback<Integer> callback) {
        if (channel == null) {
            callback.run(0);
            return;
        }
        if (channel.scam || channel.fake) {
            callback.run(10);
            return;
        }
        if (channel.verified || channel.participants_count > 2000) {
            callback.run(0);
            return;
        }
        final String channelKey = account + ":" + channel.id;
        long[] cachedScore = channelScores.get(channelKey);
        if (cachedScore != null && System.currentTimeMillis() - cachedScore[0] < RECHECK_INTERVAL) {
            callback.run((int) cachedScore[1]);
            return;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = controller.getInputPeer(-channel.id);
        req.limit = 5;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            int score = 0;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (TLRPC.Message msg : res.messages) {
                    int one = 0;
                    boolean photo = msg.media instanceof TLRPC.TL_messageMediaPhoto || msg.media instanceof TLRPC.TL_messageMediaDocument;
                    String text = msg.message == null ? "" : msg.message;
                    boolean external = EXTERNAL_LINK.matcher(text).find() || msg.media instanceof TLRPC.TL_messageMediaWebPage;
                    for (String url : buttonUrls(msg.reply_markup)) {
                        if (EXTERNAL_LINK.matcher(url).find()) {
                            external = true;
                        }
                    }
                    if (msg.entities != null) {
                        for (TLRPC.MessageEntity entity : msg.entities) {
                            if (entity instanceof TLRPC.TL_messageEntityTextUrl && EXTERNAL_LINK.matcher(((TLRPC.TL_messageEntityTextUrl) entity).url).find()) {
                                external = true;
                            }
                        }
                    }
                    if (photo && external) {
                        one += 4;
                    } else if (external) {
                        one += 2;
                    }
                    if (msg.entities != null) {
                        for (TLRPC.MessageEntity entity : msg.entities) {
                            if (entity instanceof TLRPC.TL_messageEntityTextUrl && UMessageSecurityGuard.isSuspiciousUrl(((TLRPC.TL_messageEntityTextUrl) entity).url)) {
                                one += 3;
                            }
                        }
                    }
                    for (String url : buttonUrls(msg.reply_markup)) {
                        if (UMessageSecurityGuard.isSuspiciousUrl(url)) {
                            one += 3;
                        }
                    }
                    one += lureScore(text);
                    score = Math.max(score, one);
                }
                // A tiny channel that only exists to host the link
                if (res.messages.size() > 0 && res.messages.size() <= 5 && score >= 4) {
                    score += 1;
                }
            }
            final int result = score;
            final boolean ok = error == null;
            AndroidUtilities.runOnUIThread(() -> {
                if (ok) {
                    channelScores.put(channelKey, new long[]{System.currentTimeMillis(), result});
                }
                callback.run(result);
            });
        });
    }

    /** http(s) link that is not Telegram's own domains. */
    private static final Pattern EXTERNAL_LINK = Pattern.compile("https?://(?!(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.org|telegra\\.ph|telesco\\.pe)(?:/|$))[^\\s]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern USERNAME = Pattern.compile("(?:t\\.me/|telegram\\.me/|@)([a-zA-Z0-9_]{5,32})", Pattern.CASE_INSENSITIVE);

    private static void resolveLink(int account, String about, Utilities.Callback<TLRPC.Chat> callback) {
        Matcher matcher = USERNAME.matcher(about);
        if (!matcher.find()) {
            callback.run(null);
            return;
        }
        String username = matcher.group(1);
        if ("joinchat".equalsIgnoreCase(username) || username.startsWith("+")) {
            callback.run(null);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putChats(res.chats, false);
                MessagesController.getInstance(account).putUsers(res.users, false);
                callback.run(res.chats.isEmpty() ? null : res.chats.get(0));
            } else {
                callback.run(null);
            }
        }));
    }

    private static void hide(int account, TLRPC.Chat chat, TLRPC.User user, MessageObject message, String reason) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                String key = account + ":" + chat.id + ":" + user.id;
                ensureFlaggedLoaded();
                if (flagged.add(key)) {
                    prefs().edit().putLong(key, System.currentTimeMillis()).apply();
                }
                if (message != null) {
                    // Local-only delete: removes the already shown message without touching the server.
                    ArrayList<Integer> ids = new ArrayList<>();
                    ids.add(message.getId());
                    MessagesController.getInstance(account).deleteMessages(ids, null, null, -chat.id, 0, false, 0, true);
                }
                FileLog.d("UMessageGroupGuard hid " + user.id + " in " + chat.id + ": " + reason);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
