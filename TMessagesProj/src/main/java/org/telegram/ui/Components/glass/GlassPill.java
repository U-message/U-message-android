package org.telegram.ui.Components.glass;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

/**
 * U message: the tab selection lens, built like LiquidLensView of Telegram iOS. Lifted, it is a clear glass that lets the
 * page behind the bar through, warped at its edges, and magnifies the tabs under it by 1.15. iOS gets this from a system
 * class, here it is our own lens shader (Android 13+); below that it is only frosted.
 */
public class GlassPill {

    public interface ContentDrawer {
        void draw(Canvas canvas);
    }

    // calmer than Telegram iOS, which grows by 16pt and magnifies by 1.15
    public static final float LIFTED_GROW_DP = 10;
    private static final float LIFTED_SCALE = 1.08f;

    private final View host;
    private final RectF tmp = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lensPath = new Path();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SpringAnimation pressSpring = new SpringAnimation(new FloatValueHolder(0));

    private float rimTop = Float.NaN, rimHeight;
    private float press; // 0 rest .. 1 dragged, spring driven
    private float stretch; // horizontal stretch from the drag speed
    private RenderNode contentNode, lensNode;
    private BlurredBackgroundDrawable lens, background;
    private LiquidLensEffect effect;

    public GlassPill(View host) {
        this.host = host;
        rimPaint.setStyle(Paint.Style.STROKE);
        pressSpring.setSpring(new SpringForce(0).setStiffness(420f).setDampingRatio(0.62f));
        pressSpring.setMinimumVisibleChange(0.002f);
        pressSpring.addUpdateListener((animation, value, velocity) -> {
            press = value;
            host.invalidate();
        });
        prepareEffect();
    }

    /** @param lens plain clear glass over the same source as the bar's background, it is warped here with the tabs */
    public void setLens(BlurredBackgroundDrawable lens, BlurredBackgroundDrawable background) {
        this.lens = lens;
        this.background = background;
    }

    /** The lens stays glass at rest, on the devices where it can: the bar must not draw its flat selector then. */
    public boolean isSupported() {
        return effect != null;
    }

    private void prepareEffect() {
        // not tied to the liquid glass setting, which is off on many devices: it is only this small lens
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                contentNode = new RenderNode("GlassPillContent");
                lensNode = new RenderNode("GlassPillLens");
                effect = new LiquidLensEffect(lensNode);
            } catch (Throwable e) {
                FileLog.e(e);
                contentNode = lensNode = null;
                effect = null;
            }
        }
    }

    public void press() {
        if (lens != null) {
            lens.updateColors();
        }
        pressSpring.animateToFinalPosition(1f);
    }

    public void release() {
        pressSpring.animateToFinalPosition(0f);
    }

    public void reset() {
        pressSpring.cancel();
        press = 0;
        stretch = 0;
    }

    /** Spring value, overshoots 1 for the jelly swell. */
    public float getPress() {
        return press;
    }

    /** 0..1 */
    public float getProgress() {
        return Math.max(0f, Math.min(1f, press));
    }

    public float getStretch() {
        return stretch;
    }

    public boolean isSettled() {
        return !pressSpring.isRunning() && press < 0.01f;
    }

    public void onDragged(float dx, long dtMs) {
        final float speed = Math.abs(dx) / Math.max(1, dtMs); // px per ms
        stretch = Math.max(stretch, Math.min(0.2f, speed / dp(1) * 0.045f));
    }

    /** Draws the bar's content together with the lens; call once a frame, it also decays the stretch. */
    public void draw(Canvas canvas, RectF rect, boolean dragging, ContentDrawer content) {
        final float p = getProgress();
        if (effect != null && canvas.isHardwareAccelerated()) {
            drawLiquid(canvas, rect, p, content);
        } else {
            content.draw(canvas);
            if (p > 0f) {
                final float radius = rect.height() / 2f;
                fillPaint.setColor(ColorUtils.setAlphaComponent(0xffffffff, (int) (255 * p * 0.22f)));
                canvas.drawRoundRect(rect, radius, radius, fillPaint);
                drawRim(canvas, rect, p);
            }
        }

        if (stretch > 0.001f) {
            stretch *= dragging ? 0.82f : 0.7f;
            host.postInvalidateOnAnimation();
        } else {
            stretch = 0;
        }
    }

    private void drawLiquid(Canvas canvas, RectF rect, float p, ContentDrawer content) {
        final int w = host.getWidth(), h = host.getHeight();
        final float radius = rect.height() / 2f;
        lensPath.rewind();
        lensPath.addRoundRect(rect, radius, radius, Path.Direction.CW);

        contentNode.setPosition(0, 0, w, h);
        final Canvas c = contentNode.beginRecording(w, h);
        content.draw(c);
        contentNode.endRecording();

        // the tabs outside the lens, and its soft shadow on them
        canvas.save();
        canvas.clipOutPath(lensPath);
        canvas.drawRenderNode(contentNode);
        shadowPaint.setShadowLayer(dp(9), 0, dp(2), ColorUtils.setAlphaComponent(0xff000000, (int) (0x24 * p)));
        if (p > 0f) {
            canvas.drawRoundRect(rect, radius, radius, shadowPaint);
        }
        canvas.restore();

        // inside it: the page behind the bar and the magnified tabs, warped together
        lensNode.setPosition(0, 0, w, h);
        final Canvas l = lensNode.beginRecording(w, h);
        if (lens != null) {
            if (background != null) {
                lens.setSourceOffset(background.getSourceOffsetX(), background.getSourceOffsetY());
            }
            lens.setRadius(radius);
            lens.setBounds((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom);
            lens.setAlpha((int) (255 * p));
            lens.draw(l);
        }
        final float scale = 1f + (LIFTED_SCALE - 1f) * p;
        l.save();
        l.scale(scale, scale, rect.centerX(), rect.centerY());
        l.drawRenderNode(contentNode);
        l.restore();
        lensNode.endRecording();
        // at rest the text stays untouched, the glass is only its edge light
        effect.update(rect.left, rect.top, rect.right, rect.bottom,
            Math.min(dp(14), rect.height() / 2f), dp(4) * p, 0f, lerp(0.08f, 0.16f, p));
        canvas.save();
        canvas.clipPath(lensPath);
        canvas.drawRenderNode(lensNode);
        canvas.restore();

        drawRim(canvas, rect, p);
    }

    // a bright rim lit from above
    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private void drawRim(Canvas canvas, RectF rect, float p) {
        final float radius = rect.height() / 2f;
        if (rimTop != rect.top || rimHeight != rect.height()) {
            rimTop = rect.top;
            rimHeight = rect.height();
            rimPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x8cffffff, 0x14ffffff, 0x47ffffff}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
        final float rim = Math.max(1, dp(0.7f));
        rimPaint.setStrokeWidth(rim);
        rimPaint.setAlpha((int) (255 * (effect != null ? lerp(0.35f, 0.8f, p) : p)));
        tmp.set(rect);
        tmp.inset(rim / 2f, rim / 2f);
        canvas.drawRoundRect(tmp, radius - rim / 2f, radius - rim / 2f, rimPaint);
    }
}
