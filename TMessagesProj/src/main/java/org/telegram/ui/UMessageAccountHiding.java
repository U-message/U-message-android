package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;


/**
 * U message: hide accounts from the account lists (long press). Hidden accounts show up, dimmed, only in the account
 * list of the hidden chats page: tap to switch to one, long press to show it everywhere again.
 */
public final class UMessageAccountHiding {

    private UMessageAccountHiding() {
    }

    private static String accountName(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null ? UserObject.getUserName(user) : String.valueOf(account + 1);
    }

    public static void confirmHide(BaseFragment fragment, int account) {
        confirmHide(fragment, account, null);
    }

    public static void confirmHide(BaseFragment fragment, int account, Runnable onHidden) {
        if (fragment == null || fragment.getParentActivity() == null || !UserConfig.isValidAccount(account)
                || !org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.HIDDEN_CHATS)) {
            return; // without the hidden chats there would be no way back to the account
        }
        final Runnable hide = () -> {
            // the PIN set here is the Hidden chats PIN: the hidden chats switch on with it, the way back to the account
            if (!UMessageConfig.isHiddenChatsEnabled()) {
                UMessageConfig.set(UMessageConfig.KEY_HIDDEN_CHATS, true);
            }
            // the one way the sheet tells about: long press "New chat" opens Hidden chats & accounts
            UMessageConfig.setHiddenAccess(UMessageConfig.HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS);
            UMessageConfig.setAccountVisible(account, false);
            if (onHidden != null) {
                onHidden.run();
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_archive_hide,
                    LocaleController.getString(R.string.UMessageAccountHidden)).show();
        };
        org.telegram.ui.Components.UMessageHideAccountSheet.show(fragment, account, hide);
    }

    /** Long press on a hidden account in the hidden chats' account list: it shows in every account list again. */
    public static void unhide(BaseFragment fragment, int account) {
        if (fragment == null || !UserConfig.isValidAccount(account)) {
            return;
        }
        UMessageConfig.setAccountVisible(account, true);
        BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_archive_show,
                LocaleController.getString(R.string.UMessageAccountShown)).show();
    }
}
