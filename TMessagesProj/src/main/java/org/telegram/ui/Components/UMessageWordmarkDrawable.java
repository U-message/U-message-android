package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * "U message" header wordmark: the name, optionally preceded by the logo, drawn in white
 * so it can be tinted with a MULTIPLY color filter like the original telegram_logo_2.
 */
public class UMessageWordmarkDrawable extends Drawable {

    private static final String NAME = "U message";
    private static final float MARK_FADE_START = 0.6f;

    private final Drawable mark;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int markWidth, markHeight, gap, textWidth, height;
    private float markProgress = 1f;

    public UMessageWordmarkDrawable(Context context, boolean withMark) {
        mark = context.getResources().getDrawable(R.drawable.umessage_mark).mutate();
        markHeight = dp(15);
        markWidth = withMark ? (int) (markHeight * 740f / 425f) : 0;
        gap = withMark ? dp(8) : 0;
        textPaint.setColor(0xffffffff);
        textPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        textPaint.setTextSize(dp(18));
        textWidth = (int) Math.ceil(textPaint.measureText(NAME));
        height = dp(22);
    }

    /**
     * 0: the logo is hidden, 1: the logo is shown before the name.
     * The layout never changes: move the whole view by {@link #getMarkOffset()} * (1 - progress)
     * so the name sits at the left edge while the logo is hidden. The logo only fades in once that
     * move has left room for all of it, so the screen edge can never cut it.
     */
    public void setMarkProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (markProgress != progress) {
            markProgress = progress;
            invalidateSelf();
        }
    }

    public int getMarkOffset() {
        return (int) ((markWidth + gap) * getScale());
    }

    /** Width actually drawn: the wordmark shrinks to its bounds when the header is short of space. */
    public int getDrawnWidth() {
        return (int) (getIntrinsicWidth() * getScale());
    }

    private float getScale() {
        Rect b = getBounds();
        return b.isEmpty() ? 1f : Math.min(1f, b.width() / (float) getIntrinsicWidth());
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float scale = getScale();
        canvas.save();
        canvas.translate(b.left, b.top + (b.height() - height * scale) / 2f);
        canvas.scale(scale, scale);
        int markTop = (height - markHeight) / 2;
        if (markWidth > 0 && markProgress > MARK_FADE_START) {
            mark.setBounds(0, markTop, markWidth, markTop + markHeight);
            mark.setAlpha((int) ((markProgress - MARK_FADE_START) / (1f - MARK_FADE_START) * textPaint.getAlpha()));
            mark.draw(canvas);
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = height / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(NAME, markWidth + gap, baseline, textPaint);
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return markWidth + gap + textWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        textPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        textPaint.setColorFilter(colorFilter);
        mark.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
