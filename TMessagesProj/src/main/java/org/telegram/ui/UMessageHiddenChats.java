package org.telegram.ui;

import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.BaseFragment;

/** U message: entry points to the PIN protected hidden chats page (a chat list of its own inside the app). */
public class UMessageHiddenChats {

    /** Asks for the PIN (or to choose one first) on the lock screen, then opens the hidden chats. */
    public static void open(BaseFragment fragment) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.HIDDEN_CHATS)) return;
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (UMessageConfig.hasHiddenPassword()) {
            fragment.presentFragment(new UMessageLockActivity(UMessageLockActivity.MODE_UNLOCK, false));
        } else {
            fragment.presentFragment(new UMessageLockActivity(UMessageLockActivity.MODE_SET, true));
        }
    }

    /** Settings: choose a PIN, or change it after entering the current one. */
    public static void changePassword(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        fragment.presentFragment(new UMessageLockActivity(UMessageConfig.hasHiddenPassword() ? UMessageLockActivity.MODE_CHANGE : UMessageLockActivity.MODE_SET, false));
    }
}
