package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.ui.Components.blur3.LiquidGlassEffect;

/**
 * U message: iOS style 0..1 slider. At rest the knob is a white pill; while dragged it swells into a clear liquid glass
 * lens that refracts the track under it (Android 13+, the app's liquid glass shader; a glassy knob below that),
 * stretches with the drag speed and springs back on release.
 */
public class LiquidGlassSlider extends View {

    public interface Delegate {
        void onChanged(float progress, boolean stop);
    }

    // iOS 26 style: a big glass pill knob; the track tapers from thin (left) to thick (right)
    private static final float REST_W = 46, REST_H = 28, PRESSED_W = 68, PRESSED_H = 42, TRACK_H = 5, TRACK_THIN = 2.5f, TRACK_THICK = 10;

    private Delegate delegate;
    private float progress;
    private boolean bipolar; // -1..1 shown with 0 in the middle: the fill grows from the center
    private int activeColor = 0xff3d8af7, inactiveColor = 0x33787880;

    private float press; // 0 rest .. 1 dragged, spring driven
    private float stretch; // horizontal stretch from the drag speed
    private float lastX;
    private long lastTime;
    private boolean dragging;
    private float dragOffset;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF trackRect = new RectF();
    private float rimGradientTop = Float.NaN, rimGradientHeight;

    private final SpringAnimation pressSpring;
    private RenderNode trackNode;
    private LiquidGlassEffect glass;

    public LiquidGlassSlider(Context context) {
        super(context);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1.2f));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1, dp(0.66f)));
        shadowPaint.setColor(0);
        shadowPaint.setShadowLayer(dp(6), 0, dp(2), 0x33000000);
        setLayerType(LAYER_TYPE_NONE, null);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        pressSpring = new SpringAnimation(new FloatValueHolder(0));
        pressSpring.setSpring(new SpringForce(0).setStiffness(420f).setDampingRatio(0.62f));
        pressSpring.setMinimumVisibleChange(0.002f);
        pressSpring.addUpdateListener((animation, value, velocity) -> {
            press = value;
            invalidate();
        });

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                trackNode = new RenderNode("LiquidGlassSliderTrack");
                glass = new LiquidGlassEffect(trackNode);
            } catch (Throwable e) {
                trackNode = null;
                glass = null;
            }
        }
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** The track fills from its middle (progress 0.5) toward the knob, with a center tick. */
    public void setBipolar(boolean bipolar) {
        this.bipolar = bipolar;
        invalidate();
    }

    public void setColors(int active, int inactive) {
        activeColor = active;
        inactiveColor = inactive;
        invalidate();
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0, Math.min(1, progress));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    private float trackLeft() {
        return dp(PRESSED_W) / 2f + dp(4);
    }

    private float trackRight() {
        return getWidth() - dp(PRESSED_W) / 2f - dp(4);
    }

    private float knobX() {
        return trackLeft() + (trackRight() - trackLeft()) * progress;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(PRESSED_H + 10), MeasureSpec.EXACTLY));
    }

    private final android.graphics.Path trackPath = new android.graphics.Path();

    private void drawTrack(Canvas canvas, float x, float cy) {
        final float l = trackLeft(), r = trackRight();
        if (bipolar) {
            final float th = dp(TRACK_H);
            trackPaint.setColor(inactiveColor);
            trackRect.set(l, cy - th / 2f, r, cy + th / 2f);
            canvas.drawRoundRect(trackRect, th / 2f, th / 2f, trackPaint);
            trackPaint.setColor(activeColor);
            final float mid = (l + r) / 2f;
            trackRect.set(Math.min(mid, x), cy - th / 2f, Math.max(mid, x), cy + th / 2f);
            canvas.drawRoundRect(trackRect, th / 2f, th / 2f, trackPaint);
            final float tick = dp(1.5f);
            trackRect.set(mid - tick / 2f, cy - th * 1.3f, mid + tick / 2f, cy + th * 1.3f);
            canvas.drawRoundRect(trackRect, tick / 2f, tick / 2f, trackPaint);
            return;
        }
        // wedge: thin on the left, thick on the right, both ends rounded
        final float h0 = dp(TRACK_THIN) / 2f, h1 = dp(TRACK_THICK) / 2f;
        trackPath.rewind();
        trackPath.moveTo(l, cy - h0);
        trackPath.lineTo(r, cy - h1);
        trackRect.set(r - h1, cy - h1, r + h1, cy + h1);
        trackPath.arcTo(trackRect, -90, 180, false);
        trackPath.lineTo(l, cy + h0);
        trackRect.set(l - h0, cy - h0, l + h0, cy + h0);
        trackPath.arcTo(trackRect, 90, 180, false);
        trackPath.close();
        trackPaint.setColor(inactiveColor);
        canvas.drawPath(trackPath, trackPaint);
        canvas.save();
        canvas.clipRect(l - h0 - 1, cy - h1 - 1, Math.max(l, x), cy + h1 + 1);
        trackPaint.setColor(activeColor);
        canvas.drawPath(trackPath, trackPaint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight(), cy = h / 2f;
        final float x = knobX();
        final float p = Math.max(0f, Math.min(1f, press));

        // the knob: a pill that swells and stretches along the drag
        final float kw = (dp(REST_W) + (dp(PRESSED_W) - dp(REST_W)) * p) * (1f + stretch);
        final float kh = (dp(REST_H) + (dp(PRESSED_H) - dp(REST_H)) * p) * (1f - stretch * 0.35f);
        rect.set(x - kw / 2f, cy - kh / 2f, x + kw / 2f, cy + kh / 2f);
        final float radius = kh / 2f;

        final boolean lens = glass != null && trackNode != null && canvas.isHardwareAccelerated();
        if (lens) {
            trackNode.setPosition(0, 0, (int) w, (int) h);
            final Canvas c = trackNode.beginRecording();
            drawTrack(c, x, cy);
            trackNode.endRecording();
            glass.update(rect.left, rect.top, rect.right, rect.bottom, radius, radius, radius, radius,
                    Math.max(1f, Math.min(dp(12), kh / 2.6f)), 0.25f + p * 0.6f, 1.5f, 0);
            canvas.drawRenderNode(trackNode);
        } else {
            drawTrack(canvas, x, cy);
        }

        // soft drop shadow, fading as the knob turns to glass
        final float rest = Math.max(0f, Math.min(1f, 1f - p));
        shadowPaint.setShadowLayer(dp(5 + 5 * p), 0, dp(2), ColorUtils.setAlphaComponent(Color.BLACK, (int) (0x48 - 0x10 * p)));
        knobPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (0.08f + 0.22f * rest))));
        shadowPaint.setColor(knobPaint.getColor());
        canvas.drawRoundRect(rect, radius, radius, shadowPaint);
        {
            // hairline edge so the white pill and the clear glass both read on a white page
            borderPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, (int) (0x22 + 0x10 * p)));
            final float inset = borderPaint.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, radius - inset, radius - inset, borderPaint);
            rect.inset(-inset, -inset);
        }

        {
            // glass body: a faint frost, a bright rim lit from above and a specular streak
            if (rimGradientTop != rect.top || rimGradientHeight != rect.height()) {
                rimGradientTop = rect.top;
                rimGradientHeight = rect.height();
                rimPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0xf2ffffff, 0x40ffffff, 0x8cffffff}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            rimPaint.setAlpha((int) (255 * Math.min(1f, 0.55f + 0.45f * p)));
            final float inset = rimPaint.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, radius - inset, radius - inset, rimPaint);
            rect.inset(-inset, -inset);

            shinePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (110 * Math.min(1f, p))));
            final float sw = kw * 0.42f, sh = Math.max(dp(2), kh * 0.12f);
            final float sx = rect.left + kw * 0.2f, sy = rect.top + kh * 0.16f;
            canvas.drawRoundRect(sx, sy, sx + sw, sy + sh, sh / 2f, sh / 2f, shinePaint);
        }

        if (stretch > 0.001f) {
            stretch *= dragging ? 0.82f : 0.7f;
            postInvalidateOnAnimation();
        } else {
            stretch = 0;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        final float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragging = true;
                final float knob = knobX();
                // grabbing the knob keeps its offset; tapping the track jumps there
                dragOffset = Math.abs(x - knob) <= dp(REST_W) ? knob - x : 0;
                lastX = x;
                lastTime = SystemClock.uptimeMillis();
                pressSpring.animateToFinalPosition(1f);
                updateFromTouch(x, false);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                final long now = SystemClock.uptimeMillis();
                final float dt = Math.max(1, now - lastTime);
                final float speed = Math.abs(x - lastX) / dt; // px per ms
                stretch = Math.max(stretch, Math.min(0.22f, speed / dp(1) * 0.045f));
                lastX = x;
                lastTime = now;
                updateFromTouch(x, false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                pressSpring.animateToFinalPosition(0f);
                updateFromTouch(x, true);
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x, boolean stop) {
        final float l = trackLeft(), r = trackRight();
        final float old = progress;
        progress = Math.max(0, Math.min(1, (x + dragOffset - l) / Math.max(1, r - l)));
        if (bipolar && Math.abs(progress - 0.5f) < 0.02f) {
            progress = 0.5f; // snap to neutral
        }
        if ((progress == 0 || progress == 1 || bipolar && progress == 0.5f) && old != progress) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        if (delegate != null) {
            delegate.onChanged(progress, stop);
        }
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, progress * 100));
    }
    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!isEnabled()) return super.performAccessibilityAction(action, arguments);
        float next;
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null) {
            next = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100f;
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            next = progress + 0.05f;
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            next = progress - 0.05f;
        } else {
            return super.performAccessibilityAction(action, arguments);
        }
        setProgress(next);
        if (delegate != null) delegate.onChanged(progress, true);
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        pressSpring.cancel();
        press = 0;
        dragging = false;
        super.onDetachedFromWindow();
    }

}
