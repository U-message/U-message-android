package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/**
 * U message: hiding an account, iOS style. The account's avatar and name, what hiding does and where to find the
 * account again, and the Hidden chats PIN typed right here (chosen here, twice, when there is none yet).
 */
public class UMessageHideAccountSheet extends BottomSheet {

    private static final int PIN_LENGTH = 4;

    private final Runnable onHide;
    private final boolean choosing;
    private final StringBuilder pin = new StringBuilder();
    private final PinDots dots;
    private final LinearLayout keypad;
    private final TextView pinLabel, button, cancel;
    private final View avatarWrap;
    private String firstPin;
    private boolean busy;

    public static void show(BaseFragment fragment, int account, Runnable onHide) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        fragment.showDialog(new UMessageHideAccountSheet(fragment.getParentActivity(), account, onHide));
    }

    private UMessageHideAccountSheet(Context context, int account, Runnable onHide) {
        super(context, false, null);
        this.onHide = onHide;
        this.choosing = !UMessageConfig.hasHiddenPassword();
        final boolean dark = Theme.isCurrentThemeDark();
        final int bg = dark ? 0xff1c1c1e : 0xfff2f2f7, card = dark ? 0xff2c2c2e : 0xffffffff;
        final int text = dark ? 0xffffffff : 0xff000000, secondary = dark ? 0xff98989f : 0xff6d6d72;
        setBackgroundColor(bg);
        fixNavigationBar(bg);
        setApplyBottomPadding(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));

        // header: avatar, title, one line of what happens
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(36));
        if (user != null) {
            AvatarDrawable placeholder = new AvatarDrawable();
            placeholder.setInfo(account, user);
            avatar.setForUserOrChat(user, placeholder);
        }
        FrameLayout avatarWrap = new FrameLayout(context);
        this.avatarWrap = avatarWrap;
        avatarWrap.addView(avatar, LayoutHelper.createFrame(72, 72, Gravity.CENTER));
        ImageView badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.CENTER);
        badge.setImageResource(R.drawable.msg_archive_hide);
        badge.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(UMessageModernList.INDIGO);
        badgeBg.setStroke(dp(3), bg);
        badge.setBackground(badgeBg);
        badge.setScaleX(0.8f);
        badge.setScaleY(0.8f);
        avatarWrap.addView(badge, LayoutHelper.createFrame(34, 34, Gravity.CENTER, 26, 26, 0, 0));
        root.addView(avatarWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 84, 0, 8, 0, 0));

        TextView title = new TextView(context);
        title.setText(LocaleController.formatString(R.string.UMessageHideAccountTitle, user != null ? UserObject.getUserName(user) : ""));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 18));

        // what hiding does, as an inset grouped card with icon tiles
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackground(Theme.createRoundRectDrawable(dp(14), card));
        addRow(rows, R.drawable.msg_archive_hide, UMessageModernList.GRAPHITE, LocaleController.getString(R.string.UMessageHideRowList), text, secondary, true);
        addRow(rows, R.drawable.msg_mute, UMessageModernList.RED, LocaleController.getString(R.string.UMessageHideRowMute), text, secondary, true);
        addRow(rows, R.drawable.msg_folders_private, UMessageModernList.BLUE,
                LocaleController.getString(R.string.UMessageHideRowFind), text, secondary, false);
        root.addView(rows, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // the Hidden chats PIN, typed here
        pinLabel = new TextView(context);
        pinLabel.setTextColor(secondary);
        pinLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        pinLabel.setGravity(Gravity.CENTER);
        root.addView(pinLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 12));

        dots = new PinDots(context, text, secondary);
        dots.setContentDescription(LocaleController.getString(R.string.UMessageHiddenPinPrompt));
        dots.setOnClickListener(v -> showKeypad(true));
        root.addView(dots, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        // the app's own number pad (iOS style), shown when the PIN dots are tapped; the system keyboard never opens
        keypad = new LinearLayout(context);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setVisibility(View.GONE);
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                final String key = keys[r * 3 + c];
                View view;
                if (key.isEmpty()) {
                    ImageView close = new ImageView(context);
                    close.setScaleType(ImageView.ScaleType.CENTER);
                    close.setImageResource(R.drawable.arrow_more);
                    close.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
                    close.setContentDescription(LocaleController.getString(R.string.UMessageHideKeypad));
                    close.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(72), 0, Theme.multAlpha(text, 0.12f)));
                    close.setOnClickListener(v -> showKeypad(false));
                    view = close;
                } else if (key.equals("<")) {
                    ImageView back = new ImageView(context);
                    back.setScaleType(ImageView.ScaleType.CENTER);
                    back.setImageResource(R.drawable.msg_clear_input);
                    back.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
                    back.setContentDescription(LocaleController.getString(R.string.AccDescrBackspace));
                    back.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(72), 0, Theme.multAlpha(text, 0.12f)));
                    back.setOnClickListener(v -> type(null));
                    back.setOnLongClickListener(v -> {
                        pin.setLength(0);
                        onPinChanged();
                        return true;
                    });
                    view = back;
                } else {
                    TextView digit = new TextView(context);
                    digit.setText(key);
                    digit.setTextColor(text);
                    digit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
                    digit.setGravity(Gravity.CENTER);
                    digit.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(72), card, Theme.multAlpha(text, 0.18f)));
                    digit.setOnClickListener(v -> type(key));
                    view = digit;
                }
                line.addView(view, LayoutHelper.createLinear(72, 72, c == 0 ? 0 : 22, 0, 0, 0));
            }
            keypad.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, r == 0 ? 14 : 10, 0, 0));
        }
        root.addView(keypad, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        button = new TextView(context);
        button.setText(LocaleController.getString(R.string.UMessageHideAccount));
        button.setTextColor(0xffffffff);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), UMessageModernList.BLUE, 0x33ffffff));
        button.setOnClickListener(v -> {
            if (pin.length() < PIN_LENGTH) showKeypad(true); else submit();
        });
        root.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 22, 0, 0));

        // hide right away, without the PIN: the hidden chats (and so the account) still open only with it
        cancel = new TextView(context);
        cancel.setText(LocaleController.getString(R.string.UMessageHideWithoutPin));
        cancel.setTextColor(UMessageModernList.BLUE);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), 0, Theme.multAlpha(text, 0.08f)));
        cancel.setOnClickListener(v -> finish());
        // the PIN chosen here switches the hidden chats on: with no PIN yet there is nothing to skip
        cancel.setVisibility(choosing ? View.GONE : View.VISIBLE);
        root.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));

        // iOS close button in the top right corner
        FrameLayout frame = new FrameLayout(context);
        frame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ImageView closeSheet = new ImageView(context);
        closeSheet.setScaleType(ImageView.ScaleType.CENTER);
        closeSheet.setImageResource(R.drawable.ic_layer_close);
        closeSheet.setColorFilter(new PorterDuffColorFilter(secondary, PorterDuff.Mode.SRC_IN));
        closeSheet.setContentDescription(LocaleController.getString(R.string.Close));
        closeSheet.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(30), Theme.multAlpha(text, 0.08f), Theme.multAlpha(text, 0.18f)));
        closeSheet.setOnClickListener(v -> dismiss());
        frame.addView(closeSheet, LayoutHelper.createFrame(30, 30, Gravity.TOP | Gravity.RIGHT, 0, 10, 14, 0));

        setLabel(choosing ? R.string.UMessageHideChoosePin : R.string.UMessageHideEnterPin, false);
        updateButton();
        setCustomView(frame);
    }

    private void showKeypad(boolean show) {
        if ((keypad.getVisibility() == View.VISIBLE) == show) return;
        keypad.setVisibility(show ? View.VISIBLE : View.GONE);
        // room for the pad: the PIN is checked as soon as 4 digits are in, so the buttons and the big avatar can go
        avatarWrap.setVisibility(show ? View.GONE : View.VISIBLE);
        button.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) {
            keypad.setAlpha(0f);
            keypad.setTranslationY(dp(24));
            keypad.animate().alpha(1f).translationY(0).setDuration(220).start();
        }
    }

    private void type(String digit) {
        if (busy) return;
        if (digit == null) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if (pin.length() < PIN_LENGTH) {
            pin.append(digit);
        }
        try {
            dots.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignore) {
        }
        onPinChanged();
        if (pin.length() == PIN_LENGTH) {
            AndroidUtilities.runOnUIThread(this::submit, 120);
        }
    }

    private void onPinChanged() {
        dots.setCount(pin.length());
        updateButton();
    }

    private void addRow(LinearLayout parent, int icon, int color, CharSequence textValue, int textColor, int dividerColor, boolean divider) {
        final Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        ImageView tile = new ImageView(context);
        tile.setScaleType(ImageView.ScaleType.CENTER);
        tile.setImageResource(icon);
        tile.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        tile.setBackground(Theme.createRoundRectDrawable(dp(7), color));
        row.addView(tile, LayoutHelper.createLinear(30, 30));
        TextView label = new TextView(context);
        label.setText(textValue);
        label.setTextColor(textColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setLineSpacing(dp(2), 1f);
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (divider) {
            View line = new View(context);
            line.setBackgroundColor(Theme.multAlpha(dividerColor, 0.3f));
            parent.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 56, 0, 0, 0));
        }
    }

    private void setLabel(int res, boolean error) {
        CharSequence label = LocaleController.getString(res);
        if (choosing && !error) {
            // a new PIN is typed twice: say which time this is
            label = label + "  ·  " + (firstPin == null ? 1 : 2) + "/2";
        }
        pinLabel.setText(label);
        pinLabel.setTextColor(error ? UMessageModernList.RED : choosing && firstPin != null ? UMessageModernList.BLUE : (Theme.isCurrentThemeDark() ? 0xff98989f : 0xff6d6d72));
    }

    private void updateButton() {
        button.setEnabled(!busy);
    }

    private void reject(int res) {
        setLabel(res, true);
        pin.setLength(0);
        onPinChanged();
        dots.shake();
        AndroidUtilities.shakeViewSpring(pinLabel, 4);
        try {
            dots.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT);
        } catch (Exception ignore) {
        }
    }

    private void submit() {
        if (busy || pin.length() != PIN_LENGTH) return;
        final String pin = this.pin.toString();
        if (choosing) {
            if (firstPin == null) {
                // nothing is saved yet: the same PIN has to be typed a second time
                firstPin = pin;
                busy = true;
                dots.animate().alpha(0f).translationX(-dp(30)).setDuration(160).withEndAction(() -> {
                    this.pin.setLength(0);
                    busy = false;
                    onPinChanged();
                    setLabel(R.string.UMessageHiddenPasswordRepeat, false);
                    dots.setTranslationX(dp(30));
                    dots.animate().alpha(1f).translationX(0).setDuration(200).start();
                }).start();
                return;
            }
            if (!firstPin.equals(pin)) {
                firstPin = null;
                reject(R.string.UMessageHiddenPasswordMismatch);
                AndroidUtilities.runOnUIThread(() -> {
                    if (firstPin == null && this.pin.length() == 0) setLabel(R.string.UMessageHideChoosePin, false);
                }, 1600);
                return;
            }
            UMessageConfig.setHiddenPassword(pin);
        } else {
            long locked = UMessageConfig.getPinLockRemainingMs(UMessageConfig.PIN_LOCK_HIDDEN);
            if (locked <= 0 && !UMessageConfig.checkHiddenPassword(pin)) {
                locked = UMessageConfig.onPinLockFailed(UMessageConfig.PIN_LOCK_HIDDEN);
                if (locked <= 0) {
                    reject(R.string.UMessageHiddenPasswordWrong);
                    return;
                }
            }
            if (locked > 0) {
                reject(R.string.UMessageHiddenPasswordWrong);
                pinLabel.setText(UMessageConfig.formatPinLock(locked));
                return;
            }
            UMessageConfig.resetPinLock(UMessageConfig.PIN_LOCK_HIDDEN);
        }
        busy = true;
        updateButton();
        dots.success();
        AndroidUtilities.runOnUIThread(this::finish, 260);
    }

    private void finish() {
        dismiss();
        if (onHide != null) onHide.run();
    }

    /** Four iOS-style PIN dots: empty rings that fill as digits are typed. */
    private static class PinDots extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int filledColor;
        private int count;
        private boolean ok;

        PinDots(Context context, int filledColor, int ringColor) {
            super(context);
            this.filledColor = filledColor;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(1.5f));
            ring.setColor(ringColor);
        }

        void setCount(int c) {
            count = c;
            invalidate();
        }

        void success() {
            ok = true;
            invalidate();
        }

        void shake() {
            ValueAnimator a = ValueAnimator.ofFloat(0, 1);
            a.setDuration(420);
            a.addUpdateListener(an -> {
                float p = (float) an.getAnimatedValue();
                setTranslationX((float) Math.sin(p * Math.PI * 6) * dp(10) * (1 - p));
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float r = dp(7), gap = dp(22), total = PIN_LENGTH * r * 2 + (PIN_LENGTH - 1) * gap;
            float x = (getWidth() - total) / 2f + r;
            final float y = getHeight() / 2f;
            fill.setColor(ok ? UMessageModernList.GREEN : filledColor);
            for (int i = 0; i < PIN_LENGTH; i++, x += r * 2 + gap) {
                if (i < count) {
                    canvas.drawCircle(x, y, r, fill);
                } else {
                    canvas.drawCircle(x, y, r - ring.getStrokeWidth() / 2, ring);
                }
            }
        }
    }
}
