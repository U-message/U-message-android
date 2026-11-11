package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.QuickRepliesActivity;
import org.telegram.ui.Business.QuickRepliesController;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * U message quick replies: "/shortcut " in a chat is replaced with the template text.
 * Built like Telegram's business Quick Replies screen (same rows, selection and reorder).
 */
public class UMessageTemplatesActivity extends BaseFragment {

    private static final int BUTTON_ADD = 1;
    private static final int ACTION_EDIT = 1;
    private static final int ACTION_DELETE = 2;

    private UniversalRecyclerView listView;
    private NumberTextView countText;
    private ActionBarMenuItem editItem;
    private int repliesOrderId;

    /** Selected templates, kept by identity: templates have no ids of their own. */
    private final ArrayList<UMessageConfig.Template> selected = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.UMessageTemplates));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (selected.isEmpty()) {
                        finishFragment();
                    } else {
                        clearSelection();
                    }
                } else if (id == ACTION_EDIT) {
                    if (selected.size() == 1) {
                        UMessageConfig.Template template = selected.get(0);
                        clearSelection();
                        presentFragment(new UMessageTemplateEditActivity(template));
                    }
                } else if (id == ACTION_DELETE) {
                    showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                        .setTitle(formatPluralString("BusinessRepliesDeleteTitle", selected.size()))
                        .setMessage(formatPluralString("BusinessRepliesDeleteMessage", selected.size()))
                        .setPositiveButton(getString(R.string.Remove), (di, w) -> {
                            UMessageConfig.getTemplates().removeAll(selected);
                            UMessageConfig.saveTemplates();
                            clearSelection();
                            listView.adapter.update(true);
                        })
                        .setNegativeButton(getString(R.string.Cancel), null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .create());
                }
            }
        });

        ActionBarMenu actionModeMenu = actionBar.createActionMode();
        countText = new NumberTextView(context);
        countText.setTextSize(18);
        countText.setTypeface(AndroidUtilities.bold());
        countText.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionModeMenu.addView(countText, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        countText.setOnTouchListener((v, event) -> true);
        editItem = actionModeMenu.addItem(ACTION_EDIT, R.drawable.msg_edit);
        editItem.setContentDescription(getString(R.string.Edit));
        ActionBarMenuItem deleteItem = actionModeMenu.addItem(ACTION_DELETE, R.drawable.msg_delete);
        deleteItem.setContentDescription(getString(R.string.Delete));

        FrameLayout contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        UMessageModernList.setup(listView);
        listView.listenReorder(this::whenReordered);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView, true);

        return fragmentView = contentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selected.isEmpty()) {
            if (invoked) {
                clearSelection();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getString(R.string.UMessageTemplates), getString(R.string.UMessageTemplatesInfo), "RestrictedEmoji", "📝"));
        adapter.whiteSectionStart();
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_viewintopic, getString(R.string.UMessageTemplateAdd)).accent());
        repliesOrderId = adapter.reorderSectionStart();
        final ArrayList<UMessageConfig.Template> templates = UMessageConfig.getTemplates();
        for (int i = 0; i < templates.size(); i++) {
            UMessageConfig.Template template = templates.get(i);
            UItem item = UItem.asQuickReply(toQuickReply(template, i));
            item.object2 = template;
            items.add(item.setChecked(selected.contains(template)));
        }
        adapter.reorderSectionEnd();
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(templates.isEmpty() ? null : getString(R.string.BusinessRepliesAddInfo)));
        UMessageModernList.apply(items, item -> item.id == BUTTON_ADD ? UMessageModernList.tile(R.drawable.msg_viewintopic, UMessageModernList.BLUE) : null);
    }

    /** Wraps a template into Telegram's quick reply model so it is drawn by QuickReplyView. */
    private QuickRepliesController.QuickReply toQuickReply(UMessageConfig.Template template, int index) {
        QuickRepliesController.QuickReply reply = QuickRepliesController.getInstance(currentAccount).new QuickReply();
        reply.id = index + 1;
        reply.name = template.shortcut;
        reply.order = index;
        reply.messagesCount = 1;
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = index + 1;
        message.message = template.text;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = getUserConfig().getClientUserId();
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = getUserConfig().getClientUserId();
        message.out = true;
        reply.topMessage = new MessageObject(currentAccount, message, false, false);
        return reply;
    }

    private void whenReordered(int id, ArrayList<UItem> items) {
        if (id != repliesOrderId) {
            return;
        }
        ArrayList<UMessageConfig.Template> templates = UMessageConfig.getTemplates();
        templates.clear();
        for (UItem item : items) {
            if (item.object2 instanceof UMessageConfig.Template) {
                templates.add((UMessageConfig.Template) item.object2);
            }
        }
        UMessageConfig.saveTemplates();
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_ADD) {
            presentFragment(new UMessageTemplateEditActivity(null));
        } else if (item.object2 instanceof UMessageConfig.Template) {
            if (!selected.isEmpty()) {
                updateSelect(item, view);
                return;
            }
            presentFragment(new UMessageTemplateEditActivity((UMessageConfig.Template) item.object2));
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.object2 instanceof UMessageConfig.Template) {
            updateSelect(item, view);
            return true;
        }
        return false;
    }

    private void updateSelect(UItem item, View view) {
        UMessageConfig.Template template = (UMessageConfig.Template) item.object2;
        if (selected.contains(template)) {
            selected.remove(template);
        } else {
            selected.add(template);
        }
        listView.allowReorder(!selected.isEmpty());
        if (view instanceof QuickRepliesActivity.QuickReplyView) {
            ((QuickRepliesActivity.QuickReplyView) view).setChecked(item.checked = selected.contains(template), true);
        }
        if (actionBar.isActionModeShowed() == selected.isEmpty()) {
            if (selected.isEmpty()) {
                actionBar.hideActionMode();
            } else {
                actionBar.showActionMode();
            }
        }
        countText.setNumber(Math.max(1, selected.size()), true);
        updateEditItem();
    }

    private boolean shownEditItem = true;
    private void updateEditItem() {
        boolean show = selected.size() == 1;
        if (shownEditItem != show) {
            shownEditItem = show;
            editItem.animate().alpha(show ? 1f : 0f).scaleX(show ? 1f : .7f).scaleY(show ? 1f : .7f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(340).start();
        }
    }

    private void clearSelection() {
        selected.clear();
        AndroidUtilities.forEachViews(listView, view -> {
            if (view instanceof QuickRepliesActivity.QuickReplyView) {
                ((QuickRepliesActivity.QuickReplyView) view).setChecked(false, true);
            }
        });
        actionBar.hideActionMode();
        listView.allowReorder(false);
        updateEditItem();
    }
}
