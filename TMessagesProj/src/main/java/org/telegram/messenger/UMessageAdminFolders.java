package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * U message "admin folders": four real chat folders (my groups, my channels, groups I admin,
 * channels I admin) created on the server, so they sync like any other folder.
 */
public class UMessageAdminFolders {

    private static final int MY_GROUPS = 0;
    private static final int MY_CHANNELS = 1;
    private static final int ADMIN_GROUPS = 2;
    private static final int ADMIN_CHANNELS = 3;

    private static final int[] NAMES = {
            R.string.UMessageFolderMyGroups,
            R.string.UMessageFolderMyChannels,
            R.string.UMessageFolderAdminGroups,
            R.string.UMessageFolderAdminChannels
    };
    private static final String[] EMOTICONS = {"👑", "📢", "👥", "⭐"};

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage", Context.MODE_PRIVATE);
    }

    /** Creates the folders; {@code done} gets false when nothing could be created (no chats or folder limit). */
    public static void enable(BaseFragment fragment, Utilities.Callback<Boolean> done) {
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);

        ArrayList<ArrayList<Long>> groups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            groups.add(new ArrayList<>());
        }
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat == null || chat.left || chat.kicked || chat.deactivated || chat.migrated_to != null) {
                continue;
            }
            final boolean channel = ChatObject.isChannelAndNotMegaGroup(chat);
            if (chat.creator) {
                groups.get(channel ? MY_CHANNELS : MY_GROUPS).add(dialog.id);
            } else if (chat.admin_rights != null) {
                groups.get(channel ? ADMIN_CHANNELS : ADMIN_GROUPS).add(dialog.id);
            }
        }

        final int maxChats = UserConfig.getInstance(account).isPremium() ? controller.dialogFiltersChatsLimitPremium : controller.dialogFiltersChatsLimitDefault;
        final int maxFolders = UserConfig.getInstance(account).isPremium() ? controller.dialogFiltersLimitPremium : controller.dialogFiltersLimitDefault;
        int freeFolders = maxFolders - (controller.getDialogFilters().size() - 1);

        ArrayList<TLRPC.TL_messages_updateDialogFilter> requests = new ArrayList<>();
        int nextId = 2;
        for (int type = 0; type < 4; type++) {
            ArrayList<Long> ids = groups.get(type);
            if (ids.isEmpty() || freeFolders <= 0) {
                continue;
            }
            while (controller.dialogFiltersById.get(nextId) != null) {
                nextId++;
            }
            TLRPC.TL_messages_updateDialogFilter req = new TLRPC.TL_messages_updateDialogFilter();
            req.id = nextId++;
            req.flags |= 1;
            req.filter = new TLRPC.TL_dialogFilter();
            req.filter.id = req.id;
            req.filter.title = new TLRPC.TL_textWithEntities();
            req.filter.title.text = LocaleController.getString(NAMES[type]);
            req.filter.emoticon = EMOTICONS[type];
            req.filter.flags |= 1 << 25;
            for (int i = 0; i < ids.size() && i < maxChats; i++) {
                TLRPC.InputPeer peer = controller.getInputPeer(ids.get(i));
                if (peer != null) {
                    req.filter.include_peers.add(peer);
                }
            }
            if (req.filter.include_peers.isEmpty()) {
                continue;
            }
            requests.add(req);
            freeFolders--;
        }

        if (requests.isEmpty()) {
            done.run(false);
            return;
        }
        StringBuilder created = new StringBuilder();
        for (TLRPC.TL_messages_updateDialogFilter req : requests) {
            if (created.length() > 0) created.append(',');
            created.append(req.id);
        }
        prefs().edit().putString(UMessageLocalData.userKey("umessage", "admin_folders_", account), created.toString()).apply();
        send(account, requests, 0, () -> {
            controller.loadRemoteFilters(true);
            done.run(true);
        });
    }

    /** Deletes the folders this feature created (folders the user made are never touched). */
    public static void disable(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        final String created = prefs().getString(UMessageLocalData.userKey("umessage", "admin_folders_", account), "");
        prefs().edit().remove(UMessageLocalData.userKey("umessage", "admin_folders_", account)).apply();
        ArrayList<TLRPC.TL_messages_updateDialogFilter> requests = new ArrayList<>();
        for (String id : created.split(",")) {
            try {
                if (id.isEmpty()) continue;
                final int filterId = Integer.parseInt(id);
                MessagesController.DialogFilter filter = controller.dialogFiltersById.get(filterId);
                if (filter != null) {
                    controller.removeFilter(filter);
                    MessagesStorage.getInstance(account).deleteDialogFilter(filter);
                }
                TLRPC.TL_messages_updateDialogFilter req = new TLRPC.TL_messages_updateDialogFilter();
                req.id = filterId;
                requests.add(req);
            } catch (NumberFormatException ignore) {
            }
        }
        send(account, requests, 0, () -> controller.loadRemoteFilters(true));
    }

    private static void send(int account, ArrayList<TLRPC.TL_messages_updateDialogFilter> requests, int index, Runnable whenDone) {
        if (index >= requests.size()) {
            AndroidUtilities.runOnUIThread(whenDone);
            return;
        }
        ConnectionsManager.getInstance(account).sendRequest(requests.get(index), (response, error) -> send(account, requests, index + 1, whenDone));
    }

    public static void showLimitError(BaseFragment fragment) {
        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.UMessageAdminFoldersNone)).show();
    }
}
