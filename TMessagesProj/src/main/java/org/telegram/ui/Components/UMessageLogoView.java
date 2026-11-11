package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * U message logo: the original "7C" mark ("me") plus its twin rotated 180° about the ring centre ("U").
 *
 * The logo is protected: it is always drawn whole. Never draw only one half, never reveal it in parts and
 * never move one half alone. It can only move as one piece: a small press, or a turn about its own centre.
 * A turned logo needs more room than the view, so the parents that use it must not clip their children.
 */
public class UMessageLogoView extends View {

    // Mark geometry in source units (see branding/umessage/umessage-logo.svg)
    private static final float BAR_X1 = 155, BAR_X2 = 525, BAR_Y = 49;
    private static final float CX = 525, CY = 224.5f, R = 175.5f, STROKE = 74;
    private static final float MIN_X = 155, MAX_X = 895, MIN_Y = 12, MAX_Y = 437;

    private static final long PLAYBACK_DURATION = 420;
    private static final float PRESS = 0.08f;
    private static final long TURN_DURATION = 600;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF(CX - R, CY - R, CX + R, CY + R);

    private ValueAnimator animator;
    private float press;
    private ValueAnimator turnAnimator;
    private float angle, targetAngle;

    public UMessageLogoView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(STROKE);
        paint.setColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText)); // never default black on a dark theme
        setOnClickListener(v -> play());
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /**
     * A half turn (180°) of the whole logo about its own centre (forward: clockwise), so its two ends swap places.
     * It runs on its own clock, not the finger, so a fast swipe stays readable.
     * A turn asked for during another one is added to it, so no turn is lost.
     */
    public void turn(boolean forward) {
        targetAngle += forward ? 180f : -180f;
        if (turnAnimator != null) {
            turnAnimator.cancel();
        }
        turnAnimator = ValueAnimator.ofFloat(angle, targetAngle);
        turnAnimator.setDuration(TURN_DURATION);
        turnAnimator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        turnAnimator.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            invalidate();
        });
        turnAnimator.start();
    }

    public void play() {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(PLAYBACK_DURATION);
        animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        animator.addUpdateListener(a -> {
            press = PRESS * (float) Math.sin(Math.PI * (float) a.getAnimatedValue());
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (turnAnimator != null) {
            turnAnimator.cancel();
            turnAnimator = null;
        }
        press = 0;
        angle = targetAngle = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(w / (MAX_X - MIN_X), h / (MAX_Y - MIN_Y)) * (1f - press);

        canvas.save();
        canvas.translate(getPaddingLeft() + w / 2f, getPaddingTop() + h / 2f);
        canvas.scale(scale, scale);
        canvas.rotate(angle);
        canvas.translate(-(MIN_X + MAX_X) / 2f, -(MIN_Y + MAX_Y) / 2f);
        drawMark(canvas);
        canvas.rotate(180f, CX, CY);
        drawMark(canvas);
        canvas.restore();
    }

    private void drawMark(Canvas canvas) {
        canvas.drawLine(BAR_X1, BAR_Y, BAR_X2, BAR_Y, paint);
        // from the top of the ring, counter-clockwise through the left side to the bottom
        canvas.drawArc(arcRect, 270, -180, false, paint);
    }
}
