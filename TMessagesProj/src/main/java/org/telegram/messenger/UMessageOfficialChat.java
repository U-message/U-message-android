package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The official in-app "U message" chat (umessage@u). It is not a Telegram chat: the U message server
 * keeps a short message list per user (admin notices and username offers) and the app shows it as a
 * pinned row on top of the chat list, opening {@link org.telegram.ui.UMessageOfficialChatActivity}.
 */
public final class UMessageOfficialChat {

    public static final String UNAME = "umessage";

    public static final class Message {
        public String id;
        public String kind; // "text" | "offer"
        public String text;
        public String name;
        public String code;
        public String state; // offers: pending | accepted | declined | expired
        public long date;

        public boolean isOffer() {
            return "offer".equals(kind);
        }

        public boolean isPendingOffer() {
            return isOffer() && (state == null || "pending".equals(state));
        }
    }

    private static final UMessageOfficialChat[] instances = new UMessageOfficialChat[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageOfficialChat getInstance(int account) {
        UMessageOfficialChat local = instances[account];
        if (local == null) {
            synchronized (UMessageOfficialChat.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new UMessageOfficialChat(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private ArrayList<Message> messages = new ArrayList<>();
    private boolean loading;
    private long lastRefresh;
    private long ownerId; // the logged-in user these messages belong to; the slot may be reused by another login

    private UMessageOfficialChat(int account) {
        this.account = account;
        checkOwner();
    }

    /** Reloads the per-user cache when another user is logged in on this account slot. */
    private void checkOwner() {
        final long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid == ownerId) {
            return;
        }
        ownerId = uid;
        lastRefresh = 0;
        ArrayList<Message> list = new ArrayList<>();
        try {
            list = parse(new JSONArray(prefs().getString(key("cache"), "[]")));
        } catch (Throwable ignore) {
        }
        messages = list;
    }

    public ArrayList<Message> getMessages() {
        checkOwner();
        return messages;
    }

    public Message getLast() {
        checkOwner();
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public boolean isVisible() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.OFFICIAL_CHAT)) return false;
        checkOwner();
        return !messages.isEmpty();
    }

    public int getUnreadCount() {
        checkOwner();
        final long readUntil = prefs().getLong(key("read"), 0);
        int count = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).date > readUntil) {
                count++;
            }
        }
        return count;
    }

    public void markRead() {
        final Message last = getLast();
        if (last != null && getUnreadCount() > 0) {
            prefs().edit().putLong(key("read"), last.date).apply();
            notifyChanged();
        }
    }

    /** Muted like a Telegram chat: the unread badge in the chat list turns grey. */
    public boolean isMuted() {
        return prefs().getBoolean(key("muted"), false);
    }

    public void setMuted(boolean muted) {
        prefs().edit().putBoolean(key("muted"), muted).apply();
        notifyChanged();
    }

        /** Pulls the latest messages (throttled to once per 30 s unless forced). */
    public void refresh() {
        refresh(false);
    }

    public void refresh(boolean force) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.OFFICIAL_CHAT)) return;
        AndroidUtilities.runOnUIThread(() -> {
            checkOwner();
            final long now = System.currentTimeMillis();
            if (loading || !force && now - lastRefresh < 30_000) {
                return;
            }
            loading = true;
            lastRefresh = now;
            final long requestedFor = ownerId;
            UMessagePremiumController.getInstance().unameAction(account, "inbox", null, (result, error) -> {
                loading = false;
                if (requestedFor != UserConfig.getInstance(account).getClientUserId()) {
                    return; // another user logged in meanwhile: this inbox is not theirs
                }
                if ("bad_token".equals(error)) {
                    UMessagePremiumController.getInstance().reRegister(account);
                }
                if (error != null || result == null) {
                    return;
                }
                final JSONArray arr = result.optJSONArray("messages");
                if (arr == null) {
                    return;
                }
                checkOwner();
                messages = parse(arr);
                prefs().edit().putString(key("cache"), arr.toString()).apply();
                notifyChanged();
            });
        });
    }

    /** Local state change after accepting/declining an offer, before the next refresh. */
    public void setOfferState(String code, String state) {
        if (code == null) {
            return;
        }
        checkOwner();
        for (Message m : messages) {
            if (m.isOffer() && android.text.TextUtils.equals(code, m.code)) {
                m.state = state;
            }
        }
        notifyChanged();
        refresh(true);
    }

    private void notifyChanged() {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.umessageOfficialChanged);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static ArrayList<Message> parse(JSONArray arr) {
        final ArrayList<Message> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final Message m = new Message();
            m.id = o.optString("id");
            m.kind = o.optString("kind", "text");
            m.text = o.optString("text", "");
            m.name = o.isNull("name") ? null : o.optString("name", null);
            m.code = o.isNull("code") ? null : o.optString("code", null);
            m.state = o.isNull("state") ? null : o.optString("state", null);
            m.date = o.optLong("createdAt");
            list.add(m);
        }
        return list;
    }

    private String key(String name) {
        return name + "_" + UserConfig.getInstance(account).getClientUserId();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_official", android.content.Context.MODE_PRIVATE);
    }
}
