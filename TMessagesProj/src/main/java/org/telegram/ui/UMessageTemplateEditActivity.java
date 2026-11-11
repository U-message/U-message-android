package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.messenger.BotWebViewVibrationEffect;

import java.util.ArrayList;

/** Create or edit one U message quick reply, laid out like Telegram's business editors. */
public class UMessageTemplateEditActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;
    private static final int BUTTON_DELETE = 2;
    private static final int MAX_SHORTCUT_LENGTH = 32;
    private static final int MAX_TEXT_LENGTH = 4096;

    private final UMessageConfig.Template existing;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem doneButton;
    private EditTextCell shortcutEdit;
    private EditTextCell textEdit;
    private int shiftDp = -4;

    public UMessageTemplateEditActivity(UMessageConfig.Template existing) {
        this.existing = existing;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(existing == null ? R.string.UMessageTemplateAdd : R.string.UMessageTemplateEdit));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == DONE_BUTTON) {
                    processDone();
                }
            }
        });
        Drawable checkmark = context.getResources().getDrawable(R.drawable.ic_ab_done).mutate();
        checkmark.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.MULTIPLY));
        doneButton = actionBar.createMenu().addItemWithWidth(DONE_BUTTON, checkmark, dp(56), getString(R.string.Done));

        shortcutEdit = new EditTextCell(context, getString(R.string.UMessageTemplateShortcut), false, false, MAX_SHORTCUT_LENGTH, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                checkDone(true);
            }
        };
        shortcutEdit.autofocused = existing == null;
        shortcutEdit.setShowLimitOnFocus(true);
        shortcutEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        shortcutEdit.hideKeyboardOnEnter();

        textEdit = new EditTextCell(context, getString(R.string.UMessageTemplateText), true, false, MAX_TEXT_LENGTH, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                checkDone(true);
            }
        };
        textEdit.setShowLimitOnFocus(true);
        textEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        if (existing != null) {
            shortcutEdit.setText(existing.shortcut);
            textEdit.setText(existing.text);
        }

        FrameLayout contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        UMessageModernList.setup(listView);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        checkDone(false);
        return fragmentView = contentView;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward && existing == null && shortcutEdit != null) {
            shortcutEdit.editText.requestFocus();
            AndroidUtilities.showKeyboard(shortcutEdit.editText);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.BusinessRepliesNamePlaceholder)));
        items.add(UItem.asCustom(shortcutEdit));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.UMessageTemplateText)));
        items.add(UItem.asCustom(textEdit));
        items.add(UItem.asShadow(getString(R.string.UMessageTemplatesInfo)));
        if (existing != null) {
            items.add(UItem.asButton(BUTTON_DELETE, getString(R.string.BusinessRepliesDeleteTitle_one)).red());
            items.add(UItem.asShadow(null));
        }
        UMessageModernList.apply(items, null);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_DELETE && existing != null) {
            showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.BusinessRepliesDeleteTitle_one))
                .setMessage(getString(R.string.BusinessRepliesDeleteMessage_one))
                .setPositiveButton(getString(R.string.Remove), (di, w) -> {
                    UMessageConfig.getTemplates().remove(existing);
                    UMessageConfig.saveTemplates();
                    finishFragment();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
        }
    }

    private String getShortcut() {
        return UMessageConfig.normalizeShortcut(shortcutEdit.getText().toString());
    }

    private String getTemplateText() {
        return textEdit.getText().toString().trim();
    }

    private boolean hasChanges() {
        if (existing == null) {
            return !TextUtils.isEmpty(getShortcut()) || !TextUtils.isEmpty(getTemplateText());
        }
        return !TextUtils.equals(existing.shortcut, getShortcut()) || !TextUtils.equals(existing.text, getTemplateText());
    }

    private boolean showDone;
    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        final boolean show = hasChanges();
        if (showDone == show && animated) {
            return;
        }
        showDone = show;
        doneButton.setEnabled(show);
        if (animated) {
            doneButton.animate().alpha(show ? 1f : 0f).scaleX(show ? 1f : 0f).scaleY(show ? 1f : 0f).setDuration(180).start();
        } else {
            doneButton.setAlpha(show ? 1f : 0f);
            doneButton.setScaleX(show ? 1f : 0f);
            doneButton.setScaleY(show ? 1f : 0f);
        }
    }

    private void processDone() {
        final String shortcut = getShortcut();
        final String text = getTemplateText();
        if (TextUtils.isEmpty(shortcut)) {
            shake(shortcutEdit);
            return;
        }
        if (TextUtils.isEmpty(text)) {
            shake(textEdit);
            return;
        }
        final UMessageConfig.Template sameShortcut = UMessageConfig.findTemplate(shortcut);
        if (sameShortcut != null && sameShortcut != existing) {
            shake(shortcutEdit);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.BusinessRepliesNameBusy)).show();
            return;
        }
        if (existing != null) {
            existing.shortcut = shortcut;
            existing.text = text;
        } else {
            UMessageConfig.getTemplates().add(new UMessageConfig.Template(shortcut, text));
        }
        UMessageConfig.saveTemplates();
        finishFragment();
    }

    private void shake(View view) {
        BotWebViewVibrationEffect.APP_ERROR.vibrate();
        AndroidUtilities.shakeViewSpring(view, shiftDp = -shiftDp);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasChanges()) {
            if (invoked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                builder.setTitle(getString(R.string.UnsavedChanges));
                builder.setMessage(getString(R.string.UMessageTemplateUnsavedChanges));
                builder.setPositiveButton(getString(R.string.ApplyTheme), (dialog, which) -> processDone());
                builder.setNegativeButton(getString(R.string.PassportDiscard), (dialog, which) -> finishFragment());
                showDialog(builder.create());
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }
}
