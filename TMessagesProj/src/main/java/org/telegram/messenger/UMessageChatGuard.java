package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Channels / groups blocked by the U message admin ("Kanallar" in the admin panel), even if Telegram
 * didn't block them: they are hidden from the chat list and can't be opened, and an admin of such a chat
 * gets their U message account blocked. Only with the user's opt-in ("Share chat reports", off by default)
 * it also feeds the admin's list: chats the user reports, and public chats the user is in whose title /
 * username matches a keyword (keywords shorter than {@link #MIN_KEYWORD} are ignored, so a server list can
 * not match nearly every chat).
 */
public final class UMessageChatGuard {

    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long CANDIDATE_RESEND_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int MIN_KEYWORD = 4;
    private static final String KEY_SHARE = "share_reports";

    private static volatile HashSet<Long> blockedIds = new HashSet<>();
    private static volatile HashSet<String> blockedNames = new HashSet<>();
    /** Every chat in the admin's list (not ignored): the user's admin role in them is reported. */
    private static volatile HashSet<Long> watchIds = new HashSet<>();
    private static volatile ArrayList<String> keywords = new ArrayList<>();
    private static long lastRefresh;
    private static boolean loaded;

    private UMessageChatGuard() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_chatguard", Context.MODE_PRIVATE);
    }

    /** The user's opt-in to send their reports and matching public chats to U message. Off by default. */
    public static boolean isSharingEnabled() {
        return false; // the "Data sharing" setting was removed: reported/matched chats are never sent to the server
    }

    public static void setSharingEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_SHARE, enabled).apply();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            apply(new JSONObject(prefs().getString("rules", "{}")));
        } catch (Throwable ignore) {
        }
    }

    private static void apply(JSONObject r) {
        final HashSet<Long> ids = new HashSet<>();
        final HashSet<String> names = new HashSet<>();
        final JSONArray blocked = r.optJSONArray("blocked");
        for (int i = 0; blocked != null && i < blocked.length(); i++) {
            final JSONObject o = blocked.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                ids.add(Long.parseLong(o.optString("id")));
            } catch (NumberFormatException ignore) {
            }
            final String u = o.optString("username");
            if (!TextUtils.isEmpty(u)) {
                names.add(u.toLowerCase(Locale.US));
            }
        }
        final HashSet<Long> watch = new HashSet<>();
        final JSONArray w = r.optJSONArray("watch");
        for (int i = 0; w != null && i < w.length(); i++) {
            try {
                watch.add(Long.parseLong(w.optString(i)));
            } catch (NumberFormatException ignore) {
            }
        }
        watchIds = watch;
        final ArrayList<String> kw = new ArrayList<>();
        final JSONArray k = r.optJSONArray("keywords");
        for (int i = 0; k != null && i < k.length(); i++) {
            final String word = k.optString(i).trim().toLowerCase(Locale.US);
            if (word.length() >= MIN_KEYWORD) {
                kw.add(word);
            }
        }
        blockedIds = ids;
        blockedNames = names;
        keywords = kw;
    }

    public static boolean isBlocked(TLRPC.Chat chat) {
        if (chat == null) {
            return false;
        }
        ensureLoaded();
        if (blockedIds.contains(chat.id)) {
            return true;
        }
        final String username = ChatObject.getPublicUsername(chat);
        return username != null && blockedNames.contains(username.toLowerCase(Locale.US));
    }

    public static boolean isBlockedDialog(int account, long dialogId) {
        return dialogId < 0 && isBlocked(MessagesController.getInstance(account).getChat(-dialogId));
    }

    /** Pulls the blocked list (throttled), then checks this account's chats a few seconds later. */
    public static void refresh(int account) {
        ensureLoaded();
        final long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_MS) {
            AndroidUtilities.runOnUIThread(() -> scanDialogs(account), 8000);
            return;
        }
        lastRefresh = now;
        UMessageNet.run(() -> {
            try {
                final JSONObject r = UMessagePremiumController.httpGet(UMessagePremiumController.BASE_URL + "/api/status?chatrules=1");
                prefs().edit().putString("rules", r.toString()).apply();
                apply(r);
                AndroidUtilities.runOnUIThread(() -> {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            MessagesController.getInstance(a).sortDialogs(null);
                            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                        }
                    }
                });
            } catch (Throwable ignore) {
                // offline: keep the cached list
            }
            AndroidUtilities.runOnUIThread(() -> scanDialogs(account), 8000);
        });
    }

    /** Admin of a blocked chat -> block the account; public chats matching a keyword -> admin's list. */
    private static void scanDialogs(int account) {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(mc.getAllDialogs());
        final ArrayList<String> kw = keywords;
        final JSONArray candidates = new JSONArray();
        final SharedPreferences p = prefs();
        final SharedPreferences.Editor editor = p.edit();
        final long now = System.currentTimeMillis();
        final JSONArray adminOf = new JSONArray();
        final boolean sharing = isSharingEnabled();
        boolean adminOfBlocked = false;
        for (TLRPC.Dialog d : dialogs) {
            if (d.id >= 0) {
                continue;
            }
            final TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null) {
                continue;
            }
            final boolean blocked = isBlocked(chat);
            if ((blocked || watchIds.contains(chat.id)) && (chat.creator || chat.admin_rights != null)) {
                try {
                    adminOf.put(new JSONObject().put("id", String.valueOf(chat.id)).put("role", chat.creator ? "creator" : "admin"));
                } catch (Exception ignore) {
                }
                adminOfBlocked |= blocked;
            }
            if (blocked) {
                continue;
            }
            final String username = ChatObject.getPublicUsername(chat);
            if (!sharing || username == null || kw.isEmpty() || now - p.getLong("sent_" + chat.id, 0) < CANDIDATE_RESEND_MS) {
                continue;
            }
            final String hay = ((chat.title == null ? "" : chat.title) + "\n" + username).toLowerCase(Locale.US);
            for (String k : kw) {
                if (k.length() >= MIN_KEYWORD && hay.contains(k)) {
                    candidates.put(chatJson(chat));
                    editor.putLong("sent_" + chat.id, now);
                    break;
                }
            }
            if (candidates.length() >= 20) {
                break;
            }
        }
        editor.apply();
        if (adminOf.length() > 0) {
            UMessagePremiumController.getInstance().reportChatAdmins(account, adminOf, adminOfBlocked);
        }
        if (candidates.length() > 0) {
            post("candidates", candidates);
        }
    }

    /** Hooked in ConnectionsManager: a report about a channel / group is also sent to U message. */
    public static void onRequest(int account, TLObject req) {
        if (!isSharingEnabled()) {
            return; // the report goes to Telegram only
        }
        TLRPC.InputPeer peer = null;
        String reason = "";
        String text = "";
        if (req instanceof TLRPC.TL_messages_report) {
            peer = ((TLRPC.TL_messages_report) req).peer;
            text = ((TLRPC.TL_messages_report) req).message;
            reason = "message";
        } else if (req instanceof TL_account.reportPeer) {
            final TL_account.reportPeer r = (TL_account.reportPeer) req;
            peer = r.peer;
            text = r.message;
            reason = r.reason == null ? "" : r.reason.getClass().getSimpleName().replace("TL_inputReportReason", "");
        } else if (req instanceof TLRPC.TL_messages_reportSpam) {
            peer = ((TLRPC.TL_messages_reportSpam) req).peer;
            reason = "Spam";
        } else if (req instanceof TLRPC.TL_channels_reportSpam) {
            final TLRPC.InputChannel ch = ((TLRPC.TL_channels_reportSpam) req).channel;
            if (ch != null) {
                send(account, ch.channel_id, "Spam", "");
            }
            return;
        }
        if (peer == null) {
            return;
        }
        final long chatId = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
        if (chatId != 0) {
            send(account, chatId, reason, text);
        }
    }

    private static void send(int account, long chatId, String reason, String text) {
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
        try {
            final JSONObject o = chat != null ? chatJson(chat) : new JSONObject().put("id", String.valueOf(chatId));
            o.put("reason", reason == null ? "" : reason);
            o.put("text", text == null ? "" : text);
            post("report", o);
        } catch (Throwable ignore) {
        }
    }

    private static JSONObject chatJson(TLRPC.Chat chat) {
        final JSONObject o = new JSONObject();
        try {
            o.put("id", String.valueOf(chat.id));
            o.put("title", chat.title == null ? "" : chat.title);
            final String username = ChatObject.getPublicUsername(chat);
            o.put("username", username == null ? "" : username);
            o.put("type", ChatObject.isChannelAndNotMegaGroup(chat) ? "channel" : "group");
            o.put("members", chat.participants_count);
        } catch (Throwable ignore) {
        }
        return o;
    }

    private static void post(String key, Object value) {
        UMessageNet.run(() -> {
            try {
                UMessagePremiumController.httpPost(UMessagePremiumController.BASE_URL + "/api/status", new JSONObject().put(key, value).toString());
            } catch (Throwable ignore) {
            }
        });
    }
}
