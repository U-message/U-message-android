package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

/** A short, non-interactive burst drawn above the header, removed at end, detach, or fragment pause. */
public final class GhostModeFeedback extends Drawable implements View.OnAttachStateChangeListener {
    private static final int COUNT = 7;
    private final Path ghost = GhostModeIcon.createGhostPath();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final View anchor;
    private final ViewGroup host;
    private final float originX, originY;
    private ValueAnimator animator;
    private float progress;
    private boolean dismissed;

    private GhostModeFeedback(View anchor, ViewGroup host, int color) {
        this.anchor = anchor;
        this.host = host;
        int[] button = new int[2], root = new int[2];
        anchor.getLocationInWindow(button);
        host.getLocationInWindow(root);
        originX = button[0] - root[0] + anchor.getWidth() / 2f;
        originY = button[1] - root[1] + anchor.getHeight() / 2f;
        paint.setColor(color);
        setBounds(0, 0, host.getWidth(), host.getHeight());
    }

    public static GhostModeFeedback play(View anchor, int color) {
        if (anchor == null || !anchor.isAttachedToWindow() || !anchor.isShown() || !animationsAllowed(anchor)
                || !(anchor.getRootView() instanceof ViewGroup)) return null;
        GhostModeFeedback effect = new GhostModeFeedback(anchor, (ViewGroup) anchor.getRootView(), color);
        effect.host.getOverlay().add(effect);
        anchor.addOnAttachStateChangeListener(effect);
        effect.animator = ValueAnimator.ofFloat(0, 1);
        effect.animator.setDuration(620);
        effect.animator.setInterpolator(new DecelerateInterpolator(1.35f));
        effect.animator.addUpdateListener(animation -> {
            effect.progress = (float) animation.getAnimatedValue();
            float pop = (float) Math.sin(Math.PI * Math.min(1, effect.progress * 1.7f));
            anchor.setScaleX(1f + 0.13f * pop);
            anchor.setScaleY(1f + 0.13f * pop);
            effect.invalidateSelf();
        });
        effect.animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { effect.dismiss(); }
        });
        effect.animator.start();
        return effect;
    }

    private static boolean animationsAllowed(View view) {
        if (!SharedConfig.animationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false;
        try {
            return Settings.Global.getFloat(view.getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    public void dismiss() {
        if (dismissed) return;
        dismissed = true;
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        anchor.setScaleX(1f);
        anchor.setScaleY(1f);
        anchor.removeOnAttachStateChangeListener(this);
        host.getOverlay().remove(this);
    }

    @Override public void draw(Canvas canvas) {
        if (dismissed) return;
        for (int index = 0; index < COUNT; index++) {
            // Fan up and to both sides; a slight curve makes the sprites float rather than explode.
            float angle = (float) Math.toRadians(-156 + index * 22);
            float travel = AndroidUtilities.dpf2(29 + index % 3 * 12) * progress;
            float x = originX + (float) Math.cos(angle) * travel;
            float y = originY + (float) Math.sin(angle) * travel - AndroidUtilities.dpf2(9) * progress * progress;
            float size = AndroidUtilities.dpf2(7 + index % 3) * (0.65f + progress * 0.4f);
            paint.setAlpha((int) (190 * Math.min(1, progress * 9) * (1 - progress)));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate((index - 3) * 7 * progress);
            canvas.scale(size / 24, size / 24);
            canvas.translate(-12, -12);
            canvas.drawPath(ghost, paint);
            canvas.restore();
        }
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { dismiss(); }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
