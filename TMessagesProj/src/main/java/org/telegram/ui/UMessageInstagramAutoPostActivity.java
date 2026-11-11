package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** New Instagram posts into one of the user's channels. Runs only while the app is opened. */
public class UMessageInstagramAutoPostActivity extends BaseFragment {

    private static final int TOGGLE = 1;
    private static final int CHANNEL = 2;

    private UniversalRecyclerView listView;
    private boolean busy;
    private boolean enableAfterPick;

    private UMessageInstagramBusiness api() {
        return UMessageInstagramBusiness.getInstance(currentAccount);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.UMessageInstagramAutoPost));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        final FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        UMessageModernList.setup(listView);
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView = content;
    }

    private TLRPC.Chat channel() {
        final long dialogId = api().getAutoPostDialog();
        return dialogId == 0 ? null : getMessagesController().getChat(-dialogId);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final TLRPC.Chat chat = channel();
        items.add(UItem.asHeader(getString(R.string.UMessageInstagramAutoPost)));
        items.add(UItem.asCheck(TOGGLE, getString(R.string.UMessageInstagramAutoPostEnable)).setChecked(api().isAutoPostEnabled()).setEnabled(!busy));
        items.add(UItem.asButton(CHANNEL, getString(R.string.UMessageInstagramAutoPostChannel),
            chat != null ? chat.title : getString(R.string.UMessageInstagramAutoPostChoose)));
        if (UMessageInstagramBusiness.ERROR_NO_RIGHTS.equals(api().getAutoPostProblem())) {
            items.add(UItem.asShadow(getString(R.string.UMessageInstagramAutoPostNoRights)));
        } else {
            items.add(UItem.asShadow(getString(R.string.UMessageInstagramAutoPostInfo)));
        }
        final int count = api().getAutoPostCount();
        if (count > 0) {
            items.add(UItem.asShadow(LocaleController.formatString(R.string.UMessageInstagramAutoPostCount, count)));
        }
        UMessageModernList.apply(items, item -> item.id == TOGGLE ? UMessageModernList.tile(R.drawable.msg_photos, UMessageModernList.PURPLE)
                : item.id == CHANNEL ? UMessageModernList.tile(R.drawable.msg_channel, UMessageModernList.BLUE) : null);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == CHANNEL) {
            enableAfterPick = false;
            pickChannel();
        } else if (item.id == TOGGLE && !busy) {
            if (api().isAutoPostEnabled()) {
                setEnabled(false);
            } else if (channel() == null) {
                enableAfterPick = true;
                pickChannel();
            } else if (!ChatObject.canPost(channel())) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.UMessageInstagramAutoPostNoRights)).show();
            } else {
                setEnabled(true);
            }
        }
    }

    private void setEnabled(boolean enabled) {
        busy = true;
        listView.adapter.update(true);
        api().setAutoPostEnabled(enabled, () -> {
            busy = false;
            if (fragmentView != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void pickChannel() {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY);
        args.putBoolean("allowGroups", false);
        args.putBoolean("allowUsers", false);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSwitchAccount", false);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            final long dialogId = dids.isEmpty() ? 0 : dids.get(0).dialogId;
            final TLRPC.Chat chat = dialogId < 0 ? getMessagesController().getChat(-dialogId) : null;
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || !ChatObject.canPost(chat)) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.UMessageInstagramAutoPostNoRights)).show();
                return true;
            }
            api().setAutoPostDialog(dialogId);
            fragment.finishFragment();
            if (enableAfterPick) {
                enableAfterPick = false;
                setEnabled(true);
            } else {
                listView.adapter.update(true);
            }
            return true;
        });
        presentFragment(picker);
    }
}
