package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import org.telegram.messenger.AndroidUtilities;

/**
 * iOS-style drum wheel: rows sit on a rotating cylinder (they shrink and fade toward the edges),
 * the list loops endlessly, flings land exactly on a row and every passed row gives a light tick.
 * A fixed unit label ("soat", "daq") stays next to the centre row like the iOS timer.
 */
public class UMessageWheelPicker extends View {

    public interface OnValueChanged {
        void onChanged(int value);
    }

    private final TextPaint numberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unitPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Scroller scroller;
    private VelocityTracker velocity;
    private final int touchSlop, minFling, maxFling;

    public final int itemHeight = dp(36);
    private final int radius = dp(104);

    private int min = 0, max = 59;
    private float scrollY;
    private int lastIndex;
    private String unit;
    private OnValueChanged listener;

    private float downY, lastY;
    private boolean dragging;

    public UMessageWheelPicker(Context context) {
        super(context);
        scroller = new Scroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        numberPaint.setTextSize(dp(23));
        numberPaint.setTypeface(Typeface.DEFAULT);
        unitPaint.setTextSize(dp(17));
        unitPaint.setTypeface(AndroidUtilities.bold());
        setHapticFeedbackEnabled(true);
    }

    public void setRange(int min, int max) {
        this.min = min;
        this.max = max;
        invalidate();
    }

    public void setUnit(String unit) {
        this.unit = unit;
        invalidate();
    }

    public void setTextColor(int color) {
        numberPaint.setColor(color);
        unitPaint.setColor(color);
        invalidate();
    }

    public void setOnValueChanged(OnValueChanged listener) {
        this.listener = listener;
    }

    public void setValue(int value) {
        scroller.forceFinished(true);
        lastIndex = value - min;
        scrollY = lastIndex * itemHeight;
        invalidate();
    }

    public int getValue() {
        return min + mod(Math.round(scrollY / itemHeight), count());
    }

    private int count() {
        return max - min + 1;
    }

    private static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), radius * 2 + dp(8));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cy = getHeight() / 2f;
        final float numW = numberPaint.measureText(String.valueOf(max));
        final float gap = dp(8);
        final float unitW = unit != null ? unitPaint.measureText(unit) : 0;
        final float numberRight = (getWidth() - (numW + gap + unitW)) / 2f + numW;
        final float numberBaseline = -(numberPaint.descent() + numberPaint.ascent()) / 2f;
        final int color = numberPaint.getColor();

        final float pos = scrollY / itemHeight;
        final int base = (int) Math.floor(pos);
        for (int i = base - 6; i <= base + 6; i++) {
            float d = i - pos;
            double angle = d * itemHeight / (double) radius;
            if (Math.abs(angle) >= Math.PI / 2 - 0.05) {
                continue;
            }
            float cos = (float) Math.cos(angle);
            float y = cy + (float) (radius * Math.sin(angle));
            float near = Math.max(0f, 1f - Math.abs(d));
            float alpha = near + (1f - near) * 0.42f * cos * cos;
            numberPaint.setAlpha((int) (Color_alpha(color) * alpha));

            String text = String.valueOf(min + mod(i, count()));
            canvas.save();
            canvas.translate(numberRight, y);
            canvas.scale(0.92f + 0.08f * cos, cos);
            canvas.drawText(text, -numberPaint.measureText(text), numberBaseline, numberPaint);
            canvas.restore();
        }
        numberPaint.setColor(color);

        if (unit != null) {
            float unitBaseline = cy - (unitPaint.descent() + unitPaint.ascent()) / 2f;
            canvas.drawText(unit, numberRight + gap, unitBaseline, unitPaint);
        }
    }

    private static int Color_alpha(int color) {
        return color >>> 24;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (velocity == null) {
            velocity = VelocityTracker.obtain();
        }
        velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downY = lastY = e.getY();
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - downY) > touchSlop) {
                    dragging = true;
                    lastY = e.getY();
                }
                if (dragging) {
                    scrollY -= e.getY() - lastY;
                    lastY = e.getY();
                    checkTick();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    float vy = velocity.getYVelocity();
                    if (Math.abs(vy) > minFling) {
                        scroller.fling(0, Math.round(scrollY), 0, (int) -vy, 0, 0, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
                        scroller.setFinalY(Math.round(scroller.getFinalY() / (float) itemHeight) * itemHeight);
                    } else {
                        snapTo(Math.round(scrollY / itemHeight) * itemHeight);
                    }
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    float off = Math.max(-radius, Math.min(radius, e.getY() - getHeight() / 2f));
                    int steps = Math.round((float) (Math.asin(off / radius) * radius / itemHeight));
                    snapTo((Math.round(scrollY / itemHeight) + steps) * itemHeight);
                }
                dragging = false;
                velocity.recycle();
                velocity = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void snapTo(int target) {
        int start = Math.round(scrollY);
        scroller.startScroll(0, start, 0, target - start, 260);
        invalidate();
    }

    private void checkTick() {
        int index = Math.round(scrollY / itemHeight);
        if (index != lastIndex) {
            lastIndex = index;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) {
                listener.onChanged(getValue());
            }
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.getCurrY();
            checkTick();
            postInvalidateOnAnimation();
        }
    }
}
