package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

/** A theme-aware ghost with an active badge that stays visible on either search-bar background. */
public final class GhostModeIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ghost = createGhostPath();
    private boolean active;
    private int color, badgeColor, alpha = 255;

    public void setState(boolean active, int accent, int inactive, int background) {
        this.active = active;
        background = ColorUtils.setAlphaComponent(background, 255);
        color = contrastColor(active ? accent : inactive, background, active ? 4.5 : 3.0);
        badgeColor = ColorUtils.blendARGB(background, color, 0.13f);
        if (active) color = contrastColor(color, badgeColor, 4.5);
        invalidateSelf();
    }

    public int getGhostColor() { return color; }

    static int contrastColor(int foreground, int background, double minimum) {
        foreground = ColorUtils.setAlphaComponent(foreground, 255);
        int target = ColorUtils.calculateContrast(0xff000000, background)
                >= ColorUtils.calculateContrast(0xffffffff, background) ? 0xff000000 : 0xffffffff;
        for (int step = 0; step <= 20; step++) {
            int candidate = ColorUtils.blendARGB(foreground, target, step / 20f);
            if (ColorUtils.calculateContrast(candidate, background) >= minimum) return candidate;
        }
        return target;
    }

    /** Shared vector geometry for the button and small burst sprites; eyes are transparent cutouts. */
    static Path createGhostPath() {
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(4, 22);
        path.lineTo(4, 10);
        path.cubicTo(4, 5.6f, 7.6f, 2, 12, 2);
        path.cubicTo(16.4f, 2, 20, 5.6f, 20, 10);
        path.lineTo(20, 22);
        path.lineTo(16, 19.5f);
        path.lineTo(12, 22);
        path.lineTo(8, 19.5f);
        path.close();
        path.addCircle(8.75f, 10.5f, 1.35f, Path.Direction.CW);
        path.addCircle(15.25f, 10.5f, 1.35f, Path.Direction.CW);
        return path;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        canvas.save();
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2);
        canvas.scale(size / 28f, size / 28f);
        if (active) {
            paint.setColor(badgeColor);
            paint.setAlpha(alpha);
            canvas.drawCircle(14, 14, 14, paint);
        }
        canvas.translate(2, 2);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(ghost, paint);
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return AndroidUtilities.dp(28); }
    @Override public int getIntrinsicHeight() { return AndroidUtilities.dp(28); }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    // Colors are resolved against the actual theme background, not multiplied by menu tint filters.
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
