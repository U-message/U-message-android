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
 * U message: the two account-switch PINs, set iOS style. Both accounts are shown at the top; each one's PIN is typed
 * twice on the app's own number pad (the system keyboard never opens), the active account is ringed in blue and a
 * finished one gets a green check.
 */
public class UMessageAccountPinsSheet extends BottomSheet {

    private static final int PIN_LENGTH = 4;

    private final int[] accounts;
    private final String[] pins = new String[2];
    private final Runnable onChanged;
    private final boolean dark;
    private final int text, secondary;
    private final Chip[] chips = new Chip[2];
    private final TextView label;
    private final Dots dots;
    private final StringBuilder pin = new StringBuilder();
    private int step; // account index 0/1
    private String first;
    private boolean busy;

    public static void show(BaseFragment fragment, int[] accounts, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        fragment.showDialog(new UMessageAccountPinsSheet(fragment.getParentActivity(), accounts, onChanged));
    }

    private UMessageAccountPinsSheet(Context context, int[] accounts, Runnable onChanged) {
        super(context, false, null);
        this.accounts = accounts;
        this.onChanged = onChanged;
        dark = Theme.isCurrentThemeDark();
        final int bg = dark ? 0xff1c1c1e : 0xfff2f2f7, card = dark ? 0xff2c2c2e : 0xffffffff;
        text = dark ? 0xffffffff : 0xff000000;
        secondary = dark ? 0xff98989f : 0xff6d6d72;
        setBackgroundColor(bg);
        fixNavigationBar(bg);
        setApplyBottomPadding(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.UMessageAccountAutoSwitch));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 40, 4, 40, 6));

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.UMessageAccountAutoSwitchInfo));
        info.setTextColor(secondary);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setGravity(Gravity.CENTER);
        root.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 16));

        // the two accounts, side by side on a card
        LinearLayout pair = new LinearLayout(context);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER);
        pair.setBackground(Theme.createRoundRectDrawable(dp(16), card));
        pair.setPadding(dp(8), dp(14), dp(8), dp(12));
        for (int i = 0; i < 2; i++) {
            chips[i] = new Chip(context, accounts[i]);
            pair.addView(chips[i], LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            if (i == 0) {
                ImageView arrow = new ImageView(context);
                arrow.setScaleType(ImageView.ScaleType.CENTER);
                arrow.setImageResource(R.drawable.msg_replace);
                arrow.setColorFilter(new PorterDuffColorFilter(secondary, PorterDuff.Mode.SRC_IN));
                pair.addView(arrow, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 0, 18));
            }
        }
        root.addView(pair, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setGravity(Gravity.CENTER);
        root.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 10));
        dots = new Dots(context);
        root.addView(dots, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));

        // the app's own number pad
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                final String key = keys[r * 3 + c];
                View view;
                if (key.isEmpty()) {
                    view = new View(context);
                } else if (key.equals("<")) {
                    ImageView back = new ImageView(context);
                    back.setScaleType(ImageView.ScaleType.CENTER);
                    back.setImageResource(R.drawable.msg_clear_input);
                    back.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
                    back.setContentDescription(LocaleController.getString(R.string.AccDescrBackspace));
                    back.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(68), 0, Theme.multAlpha(text, 0.12f)));
                    back.setOnClickListener(v -> type(null));
                    back.setOnLongClickListener(v -> {
                        pin.setLength(0);
                        dots.setCount(0);
                        return true;
                    });
                    view = back;
                } else {
                    TextView digit = new TextView(context);
                    digit.setText(key);
                    digit.setTextColor(text);
                    digit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 27);
                    digit.setGravity(Gravity.CENTER);
                    digit.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(68), card, Theme.multAlpha(text, 0.18f)));
                    digit.setOnClickListener(v -> type(key));
                    view = digit;
                }
                line.addView(view, LayoutHelper.createLinear(68, 68, c == 0 ? 0 : 24, 0, 0, 0));
            }
            root.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, r == 0 ? 14 : 10, 0, 0));
        }

        if (UMessageConfig.hasAccountSwitchPins()) {
            TextView disable = new TextView(context);
            disable.setText(LocaleController.getString(R.string.Disable));
            disable.setTextColor(UMessageModernList.RED);
            disable.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            disable.setGravity(Gravity.CENTER);
            disable.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), 0, Theme.multAlpha(text, 0.08f)));
            disable.setOnClickListener(v -> {
                UMessageConfig.clearAccountSwitchPins();
                dismiss();
                if (onChanged != null) onChanged.run();
            });
            root.addView(disable, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 14, 0, 0));
        }

        FrameLayout frame = new FrameLayout(context);
        frame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_layer_close);
        close.setColorFilter(new PorterDuffColorFilter(secondary, PorterDuff.Mode.SRC_IN));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(30), Theme.multAlpha(text, 0.08f), Theme.multAlpha(text, 0.18f)));
        close.setOnClickListener(v -> dismiss());
        frame.addView(close, LayoutHelper.createFrame(30, 30, Gravity.TOP | Gravity.RIGHT, 0, 14, 14, 0));
        setCustomView(frame);
        refresh(false);
    }

    private String name(int i) {
        TLRPC.User user = UserConfig.getInstance(accounts[i]).getCurrentUser();
        return user != null ? UserObject.getFirstName(user) : String.valueOf(i + 1);
    }

    private void refresh(boolean error) {
        for (int i = 0; i < 2; i++) chips[i].setState(pins[i] != null ? Chip.DONE : i == step ? Chip.ACTIVE : Chip.IDLE);
        if (!error) {
            label.setText(first == null
                    ? LocaleController.formatString(R.string.UMessageAccountPinFor, name(step)) + "  ·  1/2"
                    : LocaleController.getString(R.string.UMessageHiddenPasswordRepeat) + "  ·  2/2");
            label.setTextColor(first == null ? text : UMessageModernList.BLUE);
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
        dots.setCount(pin.length());
        if (pin.length() == PIN_LENGTH) {
            busy = true;
            AndroidUtilities.runOnUIThread(this::submit, 140);
        }
    }

    private void reject(int res) {
        label.setText(LocaleController.getString(res));
        label.setTextColor(UMessageModernList.RED);
        pin.setLength(0);
        dots.setCount(0);
        dots.shake();
        try {
            dots.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT);
        } catch (Exception ignore) {
        }
        busy = false;
        AndroidUtilities.runOnUIThread(() -> {
            if (pin.length() == 0) refresh(false);
        }, 1600);
    }

    private void submit() {
        final String value = pin.toString();
        if (first == null) {
            if (step == 1 && value.equals(pins[0])) {
                reject(R.string.UMessageAccountAutoSwitchDifferent);
                return;
            }
            first = value;
            slide(() -> refresh(false));
            return;
        }
        if (!first.equals(value)) {
            first = null;
            reject(R.string.UMessageHiddenPasswordMismatch);
            return;
        }
        pins[step] = value;
        first = null;
        if (step == 0) {
            step = 1;
            dots.success();
            slide(() -> refresh(false));
            return;
        }
        dots.success();
        refresh(false);
        UMessageConfig.setAccountSwitchPins(accounts[0], pins[0], accounts[1], pins[1]);
        AndroidUtilities.runOnUIThread(() -> {
            dismiss();
            if (onChanged != null) onChanged.run();
        }, 420);
    }

    /** The dots slide out to the left and come back empty from the right. */
    private void slide(Runnable middle) {
        dots.animate().alpha(0f).translationX(-dp(30)).setDuration(160).withEndAction(() -> {
            pin.setLength(0);
            dots.reset();
            middle.run();
            dots.setTranslationX(dp(30));
            dots.animate().alpha(1f).translationX(0).setDuration(200).withEndAction(() -> busy = false).start();
        }).setStartDelay(140).start();
    }

    /** An account: avatar ringed blue while its PIN is typed, a green check once it is set. */
    private class Chip extends LinearLayout {
        static final int IDLE = 0, ACTIVE = 1, DONE = 2;
        final FrameLayout ringWrap;
        final ImageView check;
        final GradientDrawable ring = new GradientDrawable();

        Chip(Context context, int account) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            ringWrap = new FrameLayout(context);
            ring.setShape(GradientDrawable.OVAL);
            ringWrap.setBackground(ring);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(26));
            if (user != null) {
                AvatarDrawable placeholder = new AvatarDrawable();
                placeholder.setInfo(account, user);
                avatar.setForUserOrChat(user, placeholder);
            }
            ringWrap.addView(avatar, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
            check = new ImageView(context);
            check.setScaleType(ImageView.ScaleType.CENTER);
            check.setImageResource(R.drawable.account_check);
            check.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            GradientDrawable checkBg = new GradientDrawable();
            checkBg.setShape(GradientDrawable.OVAL);
            checkBg.setColor(UMessageModernList.GREEN);
            checkBg.setStroke(dp(2), dark ? 0xff2c2c2e : 0xffffffff);
            check.setBackground(checkBg);
            ringWrap.addView(check, LayoutHelper.createFrame(22, 22, Gravity.BOTTOM | Gravity.RIGHT));
            addView(ringWrap, LayoutHelper.createLinear(60, 60));
            TextView name = new TextView(context);
            name.setText(user != null ? UserObject.getUserName(user) : "");
            name.setTextColor(text);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 6, 4, 0));
        }

        void setState(int state) {
            ring.setStroke(dp(2.5f), state == ACTIVE ? UMessageModernList.BLUE : state == DONE ? UMessageModernList.GREEN : 0);
            check.setVisibility(state == DONE ? VISIBLE : GONE);
            setAlpha(state == IDLE ? 0.55f : 1f);
        }
    }

    /** Four iOS-style PIN dots. */
    private class Dots extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private boolean ok;

        Dots(Context context) {
            super(context);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.5f));
            ringPaint.setColor(secondary);
        }

        void setCount(int c) {
            count = c;
            invalidate();
        }

        void success() {
            ok = true;
            invalidate();
        }

        void reset() {
            ok = false;
            count = 0;
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
            fill.setColor(ok ? UMessageModernList.GREEN : text);
            for (int i = 0; i < PIN_LENGTH; i++, x += r * 2 + gap) {
                if (i < count || ok) {
                    canvas.drawCircle(x, y, r, fill);
                } else {
                    canvas.drawCircle(x, y, r - ringPaint.getStrokeWidth() / 2, ringPaint);
                }
            }
        }
    }
}
