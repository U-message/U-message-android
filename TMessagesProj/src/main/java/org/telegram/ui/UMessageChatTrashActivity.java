package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageChatTrash;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** U message: chats deleted in the last 7 days — restore them or delete them for real. */
public class UMessageChatTrashActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_EMPTY = 1;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem emptyItem;

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
        actionBar.setTitle(LocaleController.getString(R.string.UMessageChatTrash));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_EMPTY) {
                    confirmEmptyTrash();
                }
            }
        });
        emptyItem = actionBar.createMenu().addItem(MENU_EMPTY, R.drawable.msg_delete);
        emptyItem.setContentDescription(LocaleController.getString(R.string.UMessageChatTrashEmpty));

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final ArrayList<UMessageChatTrash.Entry> entries = UMessageChatTrash.getInstance(currentAccount).getTrashed();
        for (UMessageChatTrash.Entry entry : entries) {
            final long dialogId = entry.dialogId;
            TLObject object = dialogId > 0 ? getMessagesController().getUser(dialogId) : getMessagesController().getChat(-dialogId);
            if (object == null) {
                continue;
            }
            UItem item = UItem.asProfileCell(object);
            item.id = (int) (dialogId ^ (dialogId >>> 32));
            item.longValue = dialogId;
            item.subtext = LocaleController.formatString(R.string.UMessageChatTrashDaysLeft, entry.getDaysLeft());
            items.add(item);
        }
        items.add(UItem.asShadow(LocaleController.getString(entries.isEmpty() ? R.string.UMessageChatTrashEmptyInfo : R.string.UMessageChatTrashInfo)));
        if (emptyItem != null) {
            emptyItem.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.viewType != UniversalAdapter.VIEW_TYPE_PROFILE_CELL) {
            return;
        }
        final long dialogId = item.longValue;
        final UMessageChatTrash trash = UMessageChatTrash.getInstance(currentAccount);
        final UMessageChatTrash.Entry entry = trash.getEntry(dialogId);
        if (entry == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(item.object instanceof TLRPC.User ? UserObject.getUserName((TLRPC.User) item.object) : ((TLRPC.Chat) item.object).title);
        builder.setMessage(LocaleController.formatString(R.string.UMessageChatTrashDaysLeft, entry.getDaysLeft()));
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageChatTrashRestore), (dialog, which) -> {
            trash.restore(dialogId);
            listView.adapter.update(true);
        });
        builder.setNeutralButton(LocaleController.getString(R.string.UMessageChatTrashDeleteNow), (dialog, which) -> {
            trash.deleteNow(dialogId);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        TextView button = (TextView) alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void confirmEmptyTrash() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageChatTrashEmpty));
        builder.setMessage(LocaleController.getString(R.string.UMessageChatTrashEmptyConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageChatTrashEmpty), (dialog, which) -> {
            UMessageChatTrash.getInstance(currentAccount).emptyTrash();
            listView.adapter.update(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        TextView button = (TextView) alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload && listView != null) {
            listView.adapter.update(true);
        }
    }
}
