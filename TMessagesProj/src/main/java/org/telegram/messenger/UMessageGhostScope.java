package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message ghost mode scope: everyone, only the chosen users, or everyone except them.
 *
 * Typing, read receipts and auto reply are decided per chat on the client. The online
 * status is one server-side value, so for the two list scopes the client stays online
 * normally and the "Last seen & online" privacy rule hides it from the right people.
 * The user's own rule is kept as a backup and put back when the scope is no longer used.
 */
public class UMessageGhostScope {

    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_ONLY = 1;
    public static final int SCOPE_EXCEPT = 2;

    private static final String KEY_SCOPE = "ghost_scope";
    private static final String KEY_USERS = "ghost_users_";
    private static final String KEY_BACKUP = "ghost_privacy_backup_";
    private static final String KEY_DIRTY = "ghost_privacy_dirty_";

    private static volatile Integer scope;
    private static final ConcurrentHashMap<Integer, Set<Long>> users = new ConcurrentHashMap<>();
    private static final boolean[] syncing = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] lastSyncTry = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int[] generation = new int[UserConfig.MAX_ACCOUNT_COUNT];

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage", Context.MODE_PRIVATE);
    }

    public static int getScope() {
        if (scope == null) {
            scope = prefs().getInt(KEY_SCOPE, SCOPE_ALL);
        }
        return scope;
    }

    public static void setScope(int value) {
        scope = value;
        prefs().edit().putInt(KEY_SCOPE, value).apply();
        onChanged();
    }

    public static Set<Long> getUsers(int account) {
        Set<Long> set = users.get(account);
        if (set == null) {
            set = new HashSet<>();
            final String raw = prefs().getString(KEY_USERS + account, "");
            if (!TextUtils.isEmpty(raw)) {
                for (String s : raw.split(",")) {
                    try {
                        set.add(Long.parseLong(s));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            set = Collections.unmodifiableSet(set);
            users.put(account, set);
        }
        return set;
    }

    public static void setUsers(int account, ArrayList<Long> ids) {
        final HashSet<Long> set = new HashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                set.add(id);
            }
        }
        users.put(account, Collections.unmodifiableSet(set));
        prefs().edit().putString(KEY_USERS + account, TextUtils.join(",", set)).apply();
        markDirty(account);
        sync(account);
    }

    /** Whether ghost mode hides typing / read receipts / auto reply in this chat. */
    public static boolean isGhostFor(int account, long dialogId) {
        if (!UMessageConfig.isGhostMode(account)) {
            return false;
        }
        final int s = getScope();
        if (s == SCOPE_ALL) {
            return true;
        }
        long userId = dialogId;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            final TLRPC.EncryptedChat chat = MessagesController.getInstance(account).getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            userId = chat != null ? chat.user_id : 0;
        }
        final boolean listed = userId > 0 && getUsers(account).contains(userId);
        return s == SCOPE_ONLY ? listed : !listed;
    }

    /** Whether the client must keep reporting offline (only the "everyone" scope). */
    public static boolean isGhostOnline() {
        return UMessageConfig.isGhostMode() && getScope() == SCOPE_ALL;
    }

    /** Ghost online status for one account (switches are per account). */
    public static boolean isGhostOnline(int account) {
        return UMessageConfig.isGhostMode(account) && getScope() == SCOPE_ALL;
    }

    /** Ghost mode or its scope changed: the online privacy rule follows on every account. */
    public static void onChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                markDirty(a);
                sync(a);
            }
        }
    }

    /** Retries a privacy update that failed earlier (e.g. while offline). Called from the timer. */
    public static void syncIfNeeded(int account) {
        if (prefs().getBoolean(KEY_DIRTY + account, false) && Math.abs(System.currentTimeMillis() - lastSyncTry[account]) >= 30000) {
            sync(account);
        }
    }

    private static void markDirty(int account) {
        generation[account]++;
        prefs().edit().putBoolean(KEY_DIRTY + account, true).apply();
    }

    private static boolean wantsRule(int account) {
        if (!UMessageConfig.isGhostMode(account)) {
            return false;
        }
        final int s = getScope();
        return s == SCOPE_EXCEPT || s == SCOPE_ONLY && !getUsers(account).isEmpty();
    }

    private static void sync(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (syncing[account] || !UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            syncing[account] = true;
            lastSyncTry[account] = System.currentTimeMillis();
            final int gen = generation[account];
            final ArrayList<TLRPC.InputPrivacyRule> backup = readBackup(account);
            if (wantsRule(account)) {
                if (backup != null) {
                    send(account, build(account, backup), false, gen);
                } else {
                    // first time: remember the user's own rule before replacing it
                    TL_account.getPrivacy req = new TL_account.getPrivacy();
                    req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
                    ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (!(response instanceof TL_account.privacyRules)) {
                            syncing[account] = false;
                            return;
                        }
                        final TL_account.privacyRules rules = (TL_account.privacyRules) response;
                        final MessagesController mc = MessagesController.getInstance(account);
                        mc.putUsers(rules.users, false);
                        mc.putChats(rules.chats, false);
                        final ArrayList<TLRPC.InputPrivacyRule> original = toInput(account, rules.rules);
                        writeBackup(account, original);
                        send(account, build(account, original), false, gen);
                    }));
                }
            } else if (backup != null) {
                send(account, backup, true, gen);
            } else {
                prefs().edit().putBoolean(KEY_DIRTY + account, false).apply();
                syncing[account] = false;
            }
        });
    }

    private static void send(int account, ArrayList<TLRPC.InputPrivacyRule> rules, boolean restoring, int gen) {
        TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        req.rules = rules;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            syncing[account] = false;
            if (!(response instanceof TL_account.privacyRules)) {
                return;
            }
            final TL_account.privacyRules result = (TL_account.privacyRules) response;
            final MessagesController mc = MessagesController.getInstance(account);
            mc.putUsers(result.users, false);
            mc.putChats(result.chats, false);
            ContactsController.getInstance(account).setPrivacyRules(result.rules, ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);
            final SharedPreferences.Editor e = prefs().edit();
            if (restoring) {
                e.remove(KEY_BACKUP + account);
            }
            // the settings may have changed again while the request was in flight
            final boolean changed = gen != generation[account];
            e.putBoolean(KEY_DIRTY + account, changed).apply();
            if (changed) {
                sync(account);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static ArrayList<TLRPC.InputPrivacyRule> build(int account, ArrayList<TLRPC.InputPrivacyRule> original) {
        final ArrayList<TLRPC.TL_inputUser> picked = inputUsers(account);
        final ArrayList<TLRPC.InputPrivacyRule> out = new ArrayList<>();
        if (getScope() == SCOPE_EXCEPT) {
            if (!picked.isEmpty()) {
                TLRPC.TL_inputPrivacyValueAllowUsers allow = new TLRPC.TL_inputPrivacyValueAllowUsers();
                allow.users.addAll(picked);
                out.add(allow);
            }
            out.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
            return out;
        }
        // SCOPE_ONLY: the user's own rule, plus the chosen users never seeing the status
        final Set<Long> ids = getUsers(account);
        TLRPC.TL_inputPrivacyValueDisallowUsers disallow = new TLRPC.TL_inputPrivacyValueDisallowUsers();
        disallow.users.addAll(picked);
        out.add(disallow);
        for (TLRPC.InputPrivacyRule rule : original) {
            if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                TLRPC.TL_inputPrivacyValueAllowUsers copy = new TLRPC.TL_inputPrivacyValueAllowUsers();
                for (TLRPC.InputUser u : ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users) {
                    if (!ids.contains(u.user_id)) {
                        copy.users.add(u);
                    }
                }
                if (!copy.users.isEmpty()) {
                    out.add(copy);
                }
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) {
                for (TLRPC.InputUser u : ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users) {
                    if (!ids.contains(u.user_id)) {
                        disallow.users.add(u);
                    }
                }
            } else {
                out.add(rule);
            }
        }
        return out;
    }

    private static ArrayList<TLRPC.TL_inputUser> inputUsers(int account) {
        final ArrayList<TLRPC.TL_inputUser> out = new ArrayList<>();
        final MessagesController mc = MessagesController.getInstance(account);
        for (Long id : getUsers(account)) {
            final TLRPC.User user = mc.getUser(id);
            if (user != null && !UserObject.isUserSelf(user)) {
                TLRPC.TL_inputUser input = new TLRPC.TL_inputUser();
                input.user_id = user.id;
                input.access_hash = user.access_hash;
                out.add(input);
            }
        }
        return out;
    }

    private static ArrayList<TLRPC.InputPrivacyRule> toInput(int account, ArrayList<TLRPC.PrivacyRule> rules) {
        final ArrayList<TLRPC.InputPrivacyRule> out = new ArrayList<>();
        final MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.PrivacyRule r : rules) {
            if (r instanceof TLRPC.TL_privacyValueAllowAll) {
                out.add(new TLRPC.TL_inputPrivacyValueAllowAll());
            } else if (r instanceof TLRPC.TL_privacyValueDisallowAll) {
                out.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
            } else if (r instanceof TLRPC.TL_privacyValueAllowContacts) {
                out.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
            } else if (r instanceof TLRPC.TL_privacyValueDisallowContacts) {
                out.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
            } else if (r instanceof TLRPC.TL_privacyValueAllowCloseFriends) {
                out.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
            } else if (r instanceof TLRPC.TL_privacyValueAllowPremium) {
                out.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
            } else if (r instanceof TLRPC.TL_privacyValueAllowBots) {
                out.add(new TLRPC.TL_inputPrivacyValueAllowBots());
            } else if (r instanceof TLRPC.TL_privacyValueDisallowBots) {
                out.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
            } else if (r instanceof TLRPC.TL_privacyValueAllowUsers) {
                TLRPC.TL_inputPrivacyValueAllowUsers in = new TLRPC.TL_inputPrivacyValueAllowUsers();
                for (Long id : ((TLRPC.TL_privacyValueAllowUsers) r).users) {
                    TLRPC.InputUser u = mc.getInputUser(id);
                    if (u != null && !(u instanceof TLRPC.TL_inputUserEmpty)) in.users.add(u);
                }
                out.add(in);
            } else if (r instanceof TLRPC.TL_privacyValueDisallowUsers) {
                TLRPC.TL_inputPrivacyValueDisallowUsers in = new TLRPC.TL_inputPrivacyValueDisallowUsers();
                for (Long id : ((TLRPC.TL_privacyValueDisallowUsers) r).users) {
                    TLRPC.InputUser u = mc.getInputUser(id);
                    if (u != null && !(u instanceof TLRPC.TL_inputUserEmpty)) in.users.add(u);
                }
                out.add(in);
            } else if (r instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                TLRPC.TL_inputPrivacyValueAllowChatParticipants in = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                in.chats.addAll(((TLRPC.TL_privacyValueAllowChatParticipants) r).chats);
                out.add(in);
            } else if (r instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                TLRPC.TL_inputPrivacyValueDisallowChatParticipants in = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                in.chats.addAll(((TLRPC.TL_privacyValueDisallowChatParticipants) r).chats);
                out.add(in);
            }
        }
        return out;
    }

    private static void writeBackup(int account, ArrayList<TLRPC.InputPrivacyRule> rules) {
        SerializedData data = new SerializedData();
        data.writeInt32(rules.size());
        for (TLRPC.InputPrivacyRule r : rules) {
            r.serializeToStream(data);
        }
        prefs().edit().putString(KEY_BACKUP + account, Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)).apply();
        data.cleanup();
    }

    private static ArrayList<TLRPC.InputPrivacyRule> readBackup(int account) {
        final String raw = prefs().getString(KEY_BACKUP + account, null);
        if (raw == null) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(Base64.decode(raw, Base64.NO_WRAP));
            final int n = data.readInt32(true);
            final ArrayList<TLRPC.InputPrivacyRule> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(TLRPC.InputPrivacyRule.TLdeserialize(data, data.readInt32(true), true));
            }
            data.cleanup();
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
