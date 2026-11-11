package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.Theme;

/**
 * Themed, animated passcode screen for Privacy Guard. The owner sets a Guard passcode under the face
 * enrollment (kept in {@link PrivacyGuardSettings}, separate from the phone passcode); this screen
 * verifies it. A cover-eyes monkey watches while digits are entered and opens its eyes on unlock,
 * the app glyph is drawn faintly behind, and an optional biometric button offers face / fingerprint.
 */
@SuppressLint("ViewConstructor")
public class PrivacyGuardUnlockView extends FrameLayout {

    public static final int MODE_VERIFY = 0;
    public static final int MODE_CREATE = 1;

    public interface Callback {
        /** Verify: the correct passcode was entered. Create: a new passcode was set. */
        void onUnlocked();
        void onCancel();
        void onBiometric();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Callback callback;
    private final int mode;
    private final boolean biometricAvailable;

    private final Drawable logo;

    private final LinearLayout content;
    private final RLottieImageView monkey;
    private final DotsView dots;
    private final TextView titleView;
    private final TextView hintView;
    private final ImageViewButton actionKey; // biometric (verify) or confirm (create)

    private ImageViewButton eyeButton;
    private final StringBuilder entered = new StringBuilder();
    private String firstEntry; // create mode: the first pass to confirm against
    private boolean done;
    private boolean revealed;

    private RLottieDrawable monkeyIdle, monkeyClose, monkeyPeek;

    public PrivacyGuardUnlockView(Context context, Theme.ResourcesProvider resourcesProvider, int mode, boolean biometricAvailable, Callback callback) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.callback = callback;
        this.mode = mode;
        this.biometricAvailable = biometricAvailable && mode == MODE_VERIFY;
        setWillNotDraw(false);
        setFocusableInTouchMode(true);
        setBackgroundColor(color(Theme.key_windowBackgroundWhite));

        logo = ContextCompat.getDrawable(context, R.drawable.umessage_icon_foreground);

        final ImageViewButton close = new ImageViewButton(context, R.drawable.ic_close_white);
        close.setColorFilter(color(Theme.key_windowBackgroundWhiteGrayText));
        close.setOnClickListener(v -> {
            if (callback != null) callback.onCancel();
        });
        addView(close, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.LEFT, 10, 10, 0, 0));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        monkey = new RLottieImageView(context);
        monkeyIdle = new RLottieDrawable(R.raw.tsv_setup_monkey_idle1, dp(110), dp(110), true, null);
        monkeyClose = new RLottieDrawable(R.raw.tsv_monkey_close, dp(110), dp(110), true, null);
        monkeyPeek = new RLottieDrawable(R.raw.tsv_setup_monkey_peek, dp(110), dp(110), true, null);
        monkeyClose.setPlayInDirectionOfCustomEndFrame(true);
        monkeyClose.setCurrentFrame(0, false);
        monkeyClose.setCustomEndFrame(monkeyClose.getFramesCount() - 1);
        monkey.setAnimation(monkeyClose);
        monkey.playAnimation();
        content.addView(monkey, LayoutHelper.createLinear(110, 110, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hintView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        hintView.setGravity(Gravity.CENTER);
        content.addView(hintView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 26));

        dots = new DotsView(context);
        final FrameLayout dotsRow = new FrameLayout(context);
        dotsRow.addView(dots, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 22, Gravity.CENTER));
        if (mode == MODE_CREATE) {
            eyeButton = new ImageViewButton(context, R.drawable.msg_views);
            eyeButton.setOnClickListener(v -> toggleReveal());
            updateEyeIcon();
            dotsRow.addView(eyeButton, LayoutHelper.createFrame(44, 44, Gravity.CENTER_VERTICAL | Gravity.RIGHT));
        }
        content.addView(dotsRow, LayoutHelper.createLinear(250, 44, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        // Bottom-left keypad slot: hide/unhide toggle when unlocking, confirm when creating.
        if (mode == MODE_VERIFY) {
            actionKey = new ImageViewButton(context, R.drawable.msg_views);
            actionKey.setOnClickListener(v -> toggleReveal());
            eyeButton = actionKey;
            updateEyeIcon();
        } else {
            actionKey = new ImageViewButton(context, R.drawable.input_done);
            actionKey.setColorFilter(color(Theme.key_windowBackgroundWhiteBlueText));
            actionKey.setOnClickListener(v -> onAction());
        }

        content.addView(buildKeypad(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        updateTexts();
        updateActionKey();

        content.setAlpha(0f);
        content.setTranslationY(dp(24));
        content.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private void updateTexts() {
        if (mode == MODE_CREATE) {
            titleView.setText(LocaleController.getString(firstEntry == null ? R.string.PrivacyGuardCreatePasscode : R.string.PrivacyGuardConfirmPasscode));
            hintView.setText(LocaleController.getString(R.string.PrivacyGuardPasscodeHint));
        } else {
            titleView.setText(LocaleController.getString(R.string.PrivacyGuardUnlockTitle));
            hintView.setText(LocaleController.getString(R.string.PrivacyGuardEnterPasscode));
        }
    }

    private LinearLayout buildKeypad(Context context) {
        final LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        final String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] row : rows) {
            final LinearLayout r = new LinearLayout(context);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (String d : row) {
                r.addView(digitKey(context, d));
            }
            grid.addView(r);
        }
        final LinearLayout last = new LinearLayout(context);
        last.setOrientation(LinearLayout.HORIZONTAL);
        last.addView(wrapKey(context, actionKey));
        last.addView(digitKey(context, "0"));
        final ImageViewButton back = new ImageViewButton(context, R.drawable.msg_clear_input);
        back.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
        back.setOnClickListener(v -> onBackspace());
        last.addView(wrapKey(context, back));
        grid.addView(last);
        return grid;
    }

    private FrameLayout wrapKey(Context context, View child) {
        final FrameLayout wrap = new FrameLayout(context);
        wrap.addView(child, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(70)));
        return wrap;
    }

    private FrameLayout digitKey(Context context, String digit) {
        final KeyButton key = new KeyButton(context, digit);
        key.setOnClickListener(v -> onDigit(digit));
        return wrapKey(context, key);
    }

    private void onDigit(String digit) {
        if (done || entered.length() >= 16) {
            return;
        }
        entered.append(digit);
        refreshDots();
        refreshMonkey();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        if (mode == MODE_VERIFY) {
            if (PrivacyGuardSettings.checkGuardPasscode(entered.toString())) {
                success();
            } else if (entered.length() >= 4) {
                AndroidUtilities.cancelRunOnUIThread(checkWrong);
                AndroidUtilities.runOnUIThread(checkWrong, 550);
            }
        }
    }

    private final Runnable checkWrong = () -> {
        if (!done && entered.length() >= 4 && !PrivacyGuardSettings.checkGuardPasscode(entered.toString())) {
            wrong(LocaleController.getString(R.string.PrivacyGuardEnterPasscode));
        }
    };

    private void onBackspace() {
        if (done || entered.length() == 0) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(checkWrong);
        entered.deleteCharAt(entered.length() - 1);
        refreshDots();
        refreshMonkey();
    }

    /** Confirm (create mode only). */
    private void onAction() {
        if (done || mode != MODE_CREATE) {
            return;
        }
        if (entered.length() < 4) {
            wrong(LocaleController.getString(R.string.PrivacyGuardPasscodeHint));
            return;
        }
        if (firstEntry == null) {
            firstEntry = entered.toString();
            entered.setLength(0);
            refreshDots();
            updateTexts();
            refreshMonkey();
        } else if (firstEntry.equals(entered.toString())) {
            PrivacyGuardSettings.setGuardPasscode(firstEntry);
            success();
        } else {
            firstEntry = null;
            updateTexts();
            wrong(LocaleController.getString(R.string.PrivacyGuardPasscodesDontMatch));
        }
    }

    private void refreshDots() {
        dots.setValue(entered.length(), entered.toString(), revealed);
        updateActionKey();
    }

    private void toggleReveal() {
        revealed = !revealed;
        updateEyeIcon();
        refreshDots();
        refreshMonkey();
    }

    private void updateEyeIcon() {
        if (eyeButton != null) {
            eyeButton.setColorFilter(color(revealed ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText));
            eyeButton.setAlpha(revealed ? 1f : 0.65f);
        }
    }

    private void updateActionKey() {
        // Verify: the hide/unhide toggle is always available. Create: the confirm shows once valid.
        actionKey.setVisibility(mode == MODE_VERIFY || entered.length() >= 4 ? VISIBLE : INVISIBLE);
    }

    private void success() {
        done = true;
        AndroidUtilities.cancelRunOnUIThread(checkWrong);
        dots.setState(color(Theme.key_checkbox), true);
        peekMonkey();
        content.postDelayed(() -> content.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> {
                    if (callback != null) callback.onUnlocked();
                }).start(), 260);
    }

    private void wrong(CharSequence restoreHint) {
        entered.setLength(0);
        dots.setState(color(Theme.key_text_RedRegular), false);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateActionKey();
        dots.shake(() -> {
            dots.reset();
            hintView.setText(restoreHint);
        });
        refreshMonkey();
    }

    /* Monkey */

    /** Move the same hand animation in either direction so toggling never jumps frames. */
    private void refreshMonkey() {
        if (monkey.getAnimatedDrawable() != monkeyClose) {
            monkey.setAnimation(monkeyClose);
        }
        monkeyClose.setCustomEndFrame(revealed ? 0 : monkeyClose.getFramesCount() - 1);
        monkey.playAnimation();
    }

    private void peekMonkey() {
        if (monkey.getAnimatedDrawable() != monkeyPeek) {
            monkey.setAnimation(monkeyPeek);
            monkey.setProgress(0f);
            monkey.playAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(checkWrong);
        if (monkeyIdle != null) monkeyIdle.recycle(false);
        if (monkeyClose != null) monkeyClose.recycle(false);
        if (monkeyPeek != null) monkeyPeek.recycle(false);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawHalfLogo(canvas);
        super.dispatchDraw(canvas);
    }

    /** The app glyph as a soft, centered watermark behind the content. */
    private void drawHalfLogo(Canvas canvas) {
        if (logo == null) {
            return;
        }
        final int size = (int) (Math.min(getWidth(), getHeight()) * 0.62f);
        final int cx = getWidth() / 2;
        final int cy = (int) (getHeight() * 0.44f);
        logo.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        logo.setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        logo.setAlpha(20);
        logo.draw(canvas);
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            if (callback != null) callback.onCancel();
            return true;
        }
        return super.dispatchKeyEventPreIme(event);
    }

    /* Views */

    private class DotsView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private String text = "";
        private boolean showText;
        private int stateColor;
        private boolean stateFilled;
        private float pop;

        DotsView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(1.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(20));
            textPaint.setTypeface(AndroidUtilities.bold());
        }

        void reset() {
            stateColor = 0;
            stateFilled = false;
            count = 0;
            text = "";
            requestLayout();
            invalidate();
        }

        void setValue(int c, String t, boolean reveal) {
            final boolean grew = c > count;
            count = c;
            text = t == null ? "" : t;
            showText = reveal;
            stateColor = 0;
            requestLayout();
            if (grew) {
                pop = 0f;
                final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
                a.addUpdateListener(v -> {
                    pop = (float) v.getAnimatedValue();
                    invalidate();
                });
                a.setInterpolator(new OvershootInterpolator(2.4f));
                a.setDuration(260);
                a.start();
            } else {
                pop = 1f;
            }
            invalidate();
        }

        void setState(int c, boolean filled) {
            stateColor = c;
            stateFilled = filled;
            showText = false;
            invalidate();
        }

        void shake(Runnable onEnd) {
            final float d = dp(8);
            animate().translationX(-d).setDuration(50).withEndAction(() ->
                animate().translationX(d).setDuration(50).withEndAction(() ->
                    animate().translationX(-d / 2).setDuration(45).withEndAction(() ->
                        animate().translationX(0).setDuration(45).withEndAction(onEnd).start()
                    ).start()
                ).start()
            ).start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            final int n = Math.max(4, count);
            setMeasuredDimension(n * dp(18) + dp(6), dp(22));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int n = Math.max(4, count);
            final float r = dp(6);
            final float step = dp(18);
            final float startX = (getWidth() - (n - 1) * step) / 2f;
            final float cy = getHeight() / 2f;
            final int accent = stateColor != 0 ? stateColor : color(Theme.key_windowBackgroundWhiteBlackText);
            for (int i = 0; i < n; i++) {
                final float cx = startX + i * step;
                final boolean active = i < count;
                if (active && showText && i < text.length()) {
                    textPaint.setColor(accent);
                    final float scale = i == count - 1 ? 0.6f + 0.4f * pop : 1f;
                    textPaint.setTextSize(dp(20) * scale);
                    final float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
                    canvas.drawText(String.valueOf(text.charAt(i)), cx, ty, textPaint);
                } else if (active) {
                    fill.setColor(accent);
                    final float scale = i == count - 1 ? 0.6f + 0.4f * pop : 1f;
                    canvas.drawCircle(cx, cy, r * scale, fill);
                } else if (stateFilled) {
                    fill.setColor(accent);
                    canvas.drawCircle(cx, cy, r, fill);
                } else {
                    ring.setColor(Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)));
                    canvas.drawCircle(cx, cy, r - dp(0.75f), ring);
                }
            }
        }
    }

    /** Circular digit key with a press ripple and a spring scale. */
    private class KeyButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String digit;

        KeyButton(Context context, String digit) {
            super(context);
            this.digit = digit;
            textPaint.setColor(color(Theme.key_windowBackgroundWhiteBlackText));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(26));
            textPaint.setTypeface(AndroidUtilities.getTypeface("fonts/rregular.ttf"));
            bg.setColor(color(Theme.key_listSelector));
            ScaleStateListAnimator.apply(this, 0.06f, 1.6f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float r = Math.min(getWidth(), getHeight()) / 2f;
            if (isPressed()) {
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, bg);
            }
            final float y = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(digit, getWidth() / 2f, y, textPaint);
        }
    }

    private class ImageViewButton extends ImageView {
        ImageViewButton(Context context, int res) {
            super(context);
            setImageResource(res);
            setScaleType(ScaleType.CENTER_INSIDE);
            ScaleStateListAnimator.apply(this, 0.06f, 1.6f);
        }
    }
}
