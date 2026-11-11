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
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessagePremiumController;
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

import java.util.ArrayList;

/** Pick or remove your own U message username ("name@u", 5+ characters). */
public class UMessageUsernameActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;
    private static final int BUTTON_REMOVE = 2;
    private static final int MIN_LEN = 5;
    private static final int MAX_LEN = 32;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem doneButton;
    private EditTextCell nameEdit;
    private String current;
    private CharSequence status;
    private String checkedName;
    private boolean available;
    private boolean saving;
    private int shiftDp = -4;
    private final Runnable checkRunnable = this::check;

    /** User-facing text for a server/network error code. */
    public static String errorText(String error) {
        if (error == null) return getString(R.string.UnknownError);
        switch (error) {
            case "taken": return getString(R.string.UMessageUnameTaken);
            case "too_short": return LocaleController.formatString(R.string.UMessageUnameTooShort, MIN_LEN);
            case "too_long":
            case "bad_chars":
            case "empty": return getString(R.string.UMessageUnameInvalid);
            case "reserved": return getString(R.string.UMessageUnameTaken);
            case "bad_code":
            case "used_code": return getString(R.string.UMessageUnameOfferExpired);
            case "already_yours": return getString(R.string.UMessageUnameStateAccepted);
            case "blocked": return getString(R.string.UMessageBlockedTitle);
            case "bad_token": return getString(R.string.UMessageUnameBadToken);
            case "network": return getString(R.string.WaitingForNetwork);
            default: return getString(R.string.UnknownError);
        }
    }

    @Override
    public View createView(Context context) {
        current = UMessagePremiumController.getInstance().getUname(getUserConfig().getClientUserId());

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.UMessageUsername));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_BUTTON) {
                    save();
                }
            }
        });
        Drawable checkmark = context.getResources().getDrawable(R.drawable.ic_ab_done).mutate();
        checkmark.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.MULTIPLY));
        doneButton = actionBar.createMenu().addItemWithWidth(DONE_BUTTON, checkmark, dp(56), getString(R.string.Done));

        nameEdit = new EditTextCell(context, getString(R.string.UMessageUsernamePlaceholder), false, false, MAX_LEN, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                onNameChanged();
            }
        };
        nameEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        nameEdit.hideKeyboardOnEnter();
        if (current != null) {
            nameEdit.setText(current);
        }

        FrameLayout contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        UMessageModernList.setup(listView);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);
        updateDone();
        return fragmentView = contentView;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward && nameEdit != null) {
            nameEdit.editText.requestFocus();
            AndroidUtilities.showKeyboard(nameEdit.editText);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.UMessageUsername)));
        items.add(UItem.asCustom(nameEdit));
        items.add(UItem.asShadow(status != null ? status : LocaleController.formatString(R.string.UMessageUsernameInfo, MIN_LEN)));
        if (current != null) {
            items.add(UItem.asButton(BUTTON_REMOVE, getString(R.string.UMessageUsernameRemove)).red());
            items.add(UItem.asShadow(null));
        }
        UMessageModernList.apply(items, null);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_REMOVE) {
            showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.UMessageUsernameRemove))
                .setMessage(LocaleController.formatString(R.string.UMessageUsernameRemoveText, UMessagePremiumController.formatUname(current)))
                .setPositiveButton(getString(R.string.Remove), (d, w) -> run("clear", null))
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
        }
    }

    private String getName() {
        String s = nameEdit.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("@")) s = s.substring(1);
        if (s.endsWith("@u")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private void onNameChanged() {
        AndroidUtilities.cancelRunOnUIThread(checkRunnable);
        final String name = getName();
        available = false;
        checkedName = null;
        if (TextUtils.isEmpty(name) || name.equals(current)) {
            setStatus(null);
        } else if (!name.matches("[a-z][a-z0-9_]*")) {
            setStatus(getString(R.string.UMessageUnameInvalid));
        } else if (name.length() < MIN_LEN) {
            setStatus(LocaleController.formatString(R.string.UMessageUnameTooShort, MIN_LEN));
        } else {
            setStatus(getString(R.string.UMessageUnameChecking));
            AndroidUtilities.runOnUIThread(checkRunnable, 400);
        }
        updateDone();
    }

    private void check() {
        final String name = getName();
        UMessagePremiumController.getInstance().checkUname(name, (result, error) -> {
            if (!name.equals(getName())) {
                return;
            }
            checkedName = name;
            available = error == null && result != null && result.optBoolean("available");
            if (available) {
                setStatus(LocaleController.formatString(R.string.UMessageUnameAvailable, UMessagePremiumController.formatUname(name)));
            } else {
                setStatus(errorText(error != null ? error : result == null ? null : result.optString("error", null)));
            }
            updateDone();
        });
    }

    private void setStatus(CharSequence text) {
        status = text;
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    private void updateDone() {
        final String name = getName();
        final boolean show = !saving && !name.equals(current == null ? "" : current) && available && name.equals(checkedName);
        doneButton.setEnabled(show);
        doneButton.setAlpha(show ? 1f : 0.4f);
    }

    private void save() {
        final String name = getName();
        if (!available || !name.equals(checkedName)) {
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            AndroidUtilities.shakeViewSpring(nameEdit, shiftDp = -shiftDp);
            return;
        }
        run("set", name);
    }

    private void run(String action, String name) {
        saving = true;
        updateDone();
        UMessagePremiumController.getInstance().unameAction(currentAccount, action, name, (result, error) -> {
            saving = false;
            if (error == null) {
                finishFragment();
            } else {
                updateDone();
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
            }
        });
    }
}
