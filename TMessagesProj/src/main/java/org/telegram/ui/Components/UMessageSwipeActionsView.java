package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

/**
 * UMessage: floating glass menu for the multi-action message swipe
 * (swipe left, then slide up/down to pick Forward / Reply / Copy).
 * Drawn above the chat list with the same frosted glass as the input bar.
 */
public class UMessageSwipeActionsView extends View {

    public static final int FORWARD = 0, REPLY = 1, COPY = 2;

    private static final int STEP = 46, WIDTH = 48, PAD = 4, MARGIN = 10;

    private final Theme.ResourcesProvider resourcesProvider;
    private BlurredBackgroundDrawable pillBg, labelBg;
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final AnimatedFloat alpha = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat sel = new AnimatedFloat(1f, this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat labelWidth = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);

    private boolean shown, canForward, canCopy;
    private int action = REPLY;
    private float centerY;

    public UMessageSwipeActionsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        int[] res = {R.drawable.msg_forward, R.drawable.menu_reply, R.drawable.msg_copy};
        for (int i = 0; i < 3; i++) {
            icons[i] = ContextCompat.getDrawable(context, res[i]).mutate();
        }
        labels[FORWARD] = LocaleController.getString(R.string.Forward);
        labels[REPLY] = LocaleController.getString(R.string.Reply);
        labels[COPY] = LocaleController.getString(R.string.Copy);
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(dp(14));
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setBackgrounds(BlurredBackgroundDrawable pill, BlurredBackgroundDrawable label) {
        pillBg = pill;
        labelBg = label;
        if (pillBg != null) pillBg.setRadius(dp(WIDTH / 2f));
        if (labelBg != null) labelBg.setRadius(dp(16));
    }

    /** y is in this view's coordinates. */
    public void update(boolean shown, int action, boolean canForward, boolean canCopy, float y) {
        if (shown && !this.shown) {
            sel.set(action, true);
        }
        if (shown) {
            this.canForward = canForward;
            this.canCopy = canCopy;
            this.centerY = y;
        }
        if (this.shown != shown || this.action != action) {
            this.shown = shown;
            this.action = action;
            invalidate();
        }
    }

    /** Keeps the whole menu inside [visibleTop, visibleBottom] (above the input bar, below the header). */
    public static float clampCenter(float y, float visibleTop, float visibleBottom, boolean canForward, boolean canCopy) {
        float step = dp(STEP);
        float min = visibleTop + dp(8) + (canForward ? step : 0) + step / 2f + dp(PAD);
        float max = visibleBottom - dp(8) - (canCopy ? step : 0) - step / 2f - dp(PAD);
        if (max < min) return (visibleTop + visibleBottom) / 2f;
        return Math.max(min, Math.min(max, y));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float a = alpha.set(shown);
        float s = sel.set(action);
        if (a <= 0) return;

        int step = dp(STEP), w = dp(WIDTH), pad = dp(PAD);
        float right = getMeasuredWidth() - dp(MARGIN);
        float cx = right - w / 2f;
        int first = canForward ? FORWARD : REPLY;
        int last = canCopy ? COPY : REPLY;
        int top = (int) (centerY + (first - REPLY) * step - step / 2f - pad);
        int bottom = (int) (centerY + (last - REPLY) * step + step / 2f + pad);
        float selY = centerY + (s - REPLY) * step;

        int iconColor = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        int textColor = Theme.getColor(Theme.key_glass_defaultText, resourcesProvider);

        float scale = 0.8f + 0.2f * a;
        canvas.save();
        canvas.scale(scale, scale, right, centerY);

        // pill
        if (pillBg != null) {
            pillBg.setBounds((int) (right - w), top, (int) right, bottom);
            pillBg.setAlpha((int) (255 * a));
            pillBg.draw(canvas);
        }
        selPaint.setColor(iconColor);
        selPaint.setAlpha((int) (0x26 * a));
        canvas.drawCircle(cx, selY, dp(20), selPaint);

        int iconSize = dp(24);
        for (int i = first; i <= last; i++) {
            Drawable d = icons[i];
            float iy = centerY + (i - REPLY) * step;
            float k = Math.max(0, 1f - Math.abs(s - i));
            int half = (int) (iconSize * (1f + 0.12f * k) / 2f);
            d.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            d.setBounds((int) (cx - half), (int) (iy - half), (int) (cx + half), (int) (iy + half));
            d.setAlpha((int) (255 * a * (0.6f + 0.4f * k)));
            d.draw(canvas);
        }

        // label of the selected action, left of the pill
        String text = labels[action];
        float tw = textPaint.measureText(text);
        float lw = labelWidth.set(tw + dp(28));
        float lh = dp(32);
        float lr = right - w - dp(8);
        float ll = lr - lw;
        float lt = selY - lh / 2f;
        if (labelBg != null) {
            labelBg.setBounds((int) ll, (int) lt, (int) lr, (int) (lt + lh));
            labelBg.setAlpha((int) (255 * a));
            labelBg.draw(canvas);
        }
        canvas.save();
        canvas.clipRect(ll, lt, lr, lt + lh);
        textPaint.setColor(textColor);
        textPaint.setAlpha((int) (255 * a));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(text, (ll + lr - tw) / 2f, selY - (fm.ascent + fm.descent) / 2f, textPaint);
        canvas.restore();

        canvas.restore();
    }
}
