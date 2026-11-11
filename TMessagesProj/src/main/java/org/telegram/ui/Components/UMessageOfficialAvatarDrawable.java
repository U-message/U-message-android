package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/** Avatar of the official "U message" account: the U mark on black, same as the official chat row. */
public class UMessageOfficialAvatarDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mark = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.umessage_mark).mutate();

    public UMessageOfficialAvatarDrawable() {
        paint.setColor(0xFF000000);
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect b = getBounds();
        final float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
        canvas.drawCircle(cx, cy, r, paint);
        final int mw = (int) (r * 2 * 30f / 56f), mh = (int) (mw * 425f / 740f);
        mark.setBounds((int) cx - mw / 2, (int) cy - mh / 2, (int) cx + mw / 2, (int) cy + mh / 2);
        mark.setAlpha(paint.getAlpha());
        mark.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
