package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PasscodeView;

/** U message: Telegram's own lock screen, here guarding the hidden chats with their own 4 digit PIN. */
public class UMessageLockActivity extends BaseFragment {

    /** Enter the PIN, then the hidden chats open in place of this screen. */
    public static final int MODE_UNLOCK = 0;
    /** Choose a PIN (twice); with openAfter the hidden chats open afterwards. */
    public static final int MODE_SET = 1;
    /** Current PIN first, then a new one (twice). */
    public static final int MODE_CHANGE = 2;

    private final int mode;
    private final boolean openAfter;
    private PasscodeView passcodeView;
    private int step;
    private String firstPin;

    public UMessageLockActivity(int mode, boolean openAfter) {
        this.mode = mode;
        this.openAfter = openAfter;
        this.step = mode == MODE_CHANGE ? -1 : 0;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        FrameLayout contentView = new FrameLayout(context);
        passcodeView = new PasscodeView(context);
        passcodeView.setUMessageMode(this::check, currentTitle());
        passcodeView.setDelegate(view -> onAccepted());
        contentView.addView(passcodeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    private CharSequence currentTitle() {
        if (mode == MODE_UNLOCK || step < 0) {
            return LocaleController.getString(R.string.UMessageHiddenPinPrompt);
        }
        return LocaleController.getString(step == 0 ? R.string.UMessageHiddenPinNew : R.string.UMessageHiddenPasswordRepeat);
    }

    @Override
    public boolean isLightStatusBar() {
        return !Theme.isCurrentThemeDark();
    }

    @Override
    public int getNavigationBarColor() {
        return getThemedColor(Theme.key_windowBackgroundWhite);
    }

    private final Runnable restoreTitle = () -> {
        if (passcodeView != null) {
            passcodeView.setUMessageTitle(currentTitle());
        }
    };

    private int check(String pin) {
        if (mode == MODE_UNLOCK || step < 0) {
            long locked = UMessageConfig.getPinLockRemainingMs(UMessageConfig.PIN_LOCK_HIDDEN);
            if (locked <= 0 && !UMessageConfig.checkHiddenPassword(pin)) {
                locked = UMessageConfig.onPinLockFailed(UMessageConfig.PIN_LOCK_HIDDEN);
                if (locked <= 0) {
                    return PasscodeView.UM_REJECT;
                }
            }
            if (locked > 0) {
                passcodeView.setUMessageTitle(UMessageConfig.formatPinLock(locked));
                AndroidUtilities.cancelRunOnUIThread(restoreTitle);
                AndroidUtilities.runOnUIThread(restoreTitle, locked);
                return PasscodeView.UM_REJECT;
            }
            UMessageConfig.resetPinLock(UMessageConfig.PIN_LOCK_HIDDEN);
            AndroidUtilities.cancelRunOnUIThread(restoreTitle);
            if (mode == MODE_UNLOCK) {
                return PasscodeView.UM_ACCEPT;
            }
            step = 0;
            passcodeView.setUMessageTitle(currentTitle());
            return PasscodeView.UM_NEXT;
        }
        if (step == 0) {
            firstPin = pin;
            step = 1;
            passcodeView.setUMessageTitle(currentTitle());
            return PasscodeView.UM_NEXT;
        }
        if (!pin.equals(firstPin)) {
            // start over: the two entries differ
            step = 0;
            firstPin = null;
            passcodeView.setUMessageTitle(LocaleController.getString(R.string.UMessageHiddenPasswordMismatch));
            return PasscodeView.UM_REJECT;
        }
        UMessageConfig.setHiddenPassword(pin);
        return PasscodeView.UM_ACCEPT;
    }

    private void onAccepted() {
        if (mode == MODE_UNLOCK || openAfter) {
            Bundle args = new Bundle();
            args.putBoolean("umessageHidden", true);
            presentFragment(new DialogsActivity(args), true);
        } else {
            finishFragment();
            BaseFragment previous = getParentLayout() != null ? getParentLayout().getLastFragment() : null;
            if (previous != null) {
                BulletinFactory.of(previous).createSimpleBulletin(R.raw.passcode_lock, LocaleController.getString(R.string.UMessageHiddenPasswordSaved)).show();
            }
        }
    }

    /** FLAG_SECURE while the PIN screen is shown: keeps the PIN pad out of screenshots and recents. */
    private FlagSecureReason flagSecure;

    @Override
    public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            if (flagSecure == null) flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
            flagSecure.attach();
        }
        if (passcodeView != null) {
            passcodeView.onShow(false, false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (flagSecure != null) flagSecure.detach();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (flagSecure != null) flagSecure.detach();
        AndroidUtilities.cancelRunOnUIThread(restoreTitle);
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return false;
    }
}
