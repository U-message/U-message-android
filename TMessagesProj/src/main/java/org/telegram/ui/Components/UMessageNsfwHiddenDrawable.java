package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * What the media viewer shows instead of a picture the adult-content filter covers: a dark card with a
 * crossed-out eye, "Hidden content" and what is under it (or "Checking…" while the picture is being judged).
 */
public class UMessageNsfwHiddenDrawable extends Drawable {

    private final Paint background = new Paint();
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final String title, text;
    private boolean checking;

    public UMessageNsfwHiddenDrawable(Context context, String title, String text) {
        this.title = title;
        this.text = text;
        background.setColor(0xff0f1115);
        titlePaint.setColor(0xffffffff);
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dp(18));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xb3ffffff);
        textPaint.setTextSize(dp(14));
        icon = ContextCompat.getDrawable(context, R.drawable.menu_hide_gift).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b, background);
        if (checking) {
            // An opaque neutral loading surface, never a blurred copy of unchecked pixels.
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            Paint ring = background;
            int color = ring.getColor();
            ring.setColor(0xff89939e);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            float phase = android.os.SystemClock.elapsedRealtime() % 1200 / 1200f * 360;
            canvas.drawArc(cx - dp(10), cy - dp(22), cx + dp(10), cy - dp(2), phase, 250, false, ring);
            if (getCallback() != null) invalidateSelf();
            ring.setStyle(Paint.Style.FILL);
            ring.setColor(color);
            textPaint.setTextSize(dp(b.width() < dp(170) ? 11 : 13));
            String label = android.text.TextUtils.ellipsize(text, textPaint,
                    Math.max(1, b.width() - dp(20)), android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(label, cx, cy + dp(20), textPaint);
            return;
        }
        int width = Math.max(1, b.width() - dp(64));
        StaticLayout body = new StaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false);
        int iconSize = dp(44);
        float total = iconSize + dp(14) + titlePaint.getTextSize() + dp(8) + body.getHeight();
        float cx = b.exactCenterX(), y = b.exactCenterY() - total / 2f;
        icon.setBounds((int) (cx - iconSize / 2f), (int) y, (int) (cx + iconSize / 2f), (int) y + iconSize);
        icon.draw(canvas);
        y += iconSize + dp(14) + titlePaint.getTextSize();
        canvas.drawText(title, cx, y, titlePaint);
        y += dp(8);
        canvas.save();
        canvas.translate(cx - width / 2f, y);
        body.draw(canvas);
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return 1080;
    }

    @Override
    public int getIntrinsicHeight() {
        return 1350;
    }

    @Override
    public void setAlpha(int alpha) {
        background.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    public static UMessageNsfwHiddenDrawable checking(Context context) {
        UMessageNsfwHiddenDrawable drawable = new UMessageNsfwHiddenDrawable(context, "", LocaleController.getString(R.string.UMessageNsfwChecking));
        drawable.checking = true;
        drawable.background.setColor(0xff252a30);
        return drawable;
    }
    public static UMessageNsfwHiddenDrawable unavailable(Context context) {
        UMessageNsfwHiddenDrawable drawable = new UMessageNsfwHiddenDrawable(context, "", LocaleController.getString(R.string.UMessageNsfwRetryTitle));
        drawable.checking = true;
        drawable.background.setColor(0xff252a30);
        return drawable;
    }
}
