package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** U message: chats kept out of the chat list — hidden chats or chats with strangers. */
public class UMessageChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    public static final int MODE_STRANGERS = 0;
    public static final int MODE_HIDDEN = 1;

    private final int mode;

    public boolean isHiddenMode() {
        return mode == MODE_HIDDEN;
    }
    private final ArrayList<Long> dialogIds = new ArrayList<>();
    private UniversalRecyclerView listView;

    public UMessageChatsActivity(int mode) {
        this.mode = mode;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(mode == MODE_HIDDEN ? R.string.UMessageHiddenChats : R.string.UMessageStrangerChats));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, this::onItemLongClick);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    private void loadDialogs() {
        dialogIds.clear();
        final MessagesController controller = getMessagesController();
        if (mode == MODE_HIDDEN) {
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                if (UMessageConfig.getHiddenChats(currentAccount).contains(dialog.id)) {
                    dialogIds.add(dialog.id);
                }
            }
        } else {
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                if (UMessageConfig.isStranger(currentAccount, dialog)) {
                    dialogIds.add(dialog.id);
                }
            }
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        loadDialogs();
        for (long dialogId : dialogIds) {
            TLObject object = dialogId > 0 ? getMessagesController().getUser(dialogId) : getMessagesController().getChat(-dialogId);
            if (object != null) {
                UItem item = UItem.asProfileCell(object);
                item.id = (int) (dialogId ^ (dialogId >>> 32));
                item.longValue = dialogId;
                items.add(item);
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(dialogIds.isEmpty()
                ? (mode == MODE_HIDDEN ? R.string.UMessageHiddenChatsEmpty : R.string.UMessageStrangerChatsEmpty)
                : (mode == MODE_HIDDEN ? R.string.UMessageHiddenChatsInfo : R.string.UMessageStrangerChatsInfo))));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.viewType != UniversalAdapter.VIEW_TYPE_PROFILE_CELL) {
            return;
        }
        Bundle args = new Bundle();
        if (item.longValue > 0) {
            args.putLong("user_id", item.longValue);
        } else {
            args.putLong("chat_id", -item.longValue);
        }
        presentFragment(new ChatActivity(args));
    }

    private boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        if (mode != MODE_HIDDEN || item.viewType != UniversalAdapter.VIEW_TYPE_PROFILE_CELL) {
            return false;
        }
        final long dialogId = item.longValue;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageUnhideChat));
        builder.setMessage(LocaleController.getString(R.string.UMessageUnhideChatInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageUnhideChat), (dialog, which) -> {
            ArrayList<Long> ids = new ArrayList<>();
            ids.add(dialogId);
            UMessageConfig.setChatsHidden(currentAccount, ids, false);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload && listView != null) {
            listView.adapter.update(true);
        }
    }
}
