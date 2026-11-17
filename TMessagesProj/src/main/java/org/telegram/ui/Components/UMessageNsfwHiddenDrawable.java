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
    private android.graphics.Bitmap blurSource;
    private final android.graphics.RectF blurRect = new android.graphics.RectF();
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();

    /** Already-blurred tiny preview to show behind "Checking…"; null keeps the plain surface. */
    public void setBlurSource(android.graphics.Bitmap bitmap) {
        blurSource = bitmap;
    }

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
        dimPaint.setColor(0x73000000);
        icon = ContextCompat.getDrawable(context, R.drawable.menu_hide_gift).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (checking && blurSource != null && !blurSource.isRecycled()) {
            // a heavily blurred tiny preview (the stripped thumbnail): shapes and colors only, no detail
            float scale = Math.max(b.width() / (float) blurSource.getWidth(), b.height() / (float) blurSource.getHeight());
            float bw = blurSource.getWidth() * scale, bh = blurSource.getHeight() * scale;
            blurRect.set(b.exactCenterX() - bw / 2f, b.exactCenterY() - bh / 2f, b.exactCenterX() + bw / 2f, b.exactCenterY() + bh / 2f);
            canvas.drawBitmap(blurSource, null, blurRect, blurPaint);
            canvas.drawRect(b, dimPaint);
        } else {
            canvas.drawRect(b, background);
        }
        if (checking) {
            // the cell draws its own progress button in the middle; the label sits centered below it
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            textPaint.setTextSize(dp(b.width() < dp(170) ? 11 : 13));
            int width = Math.max(1, b.width() - dp(16));
            StaticLayout label = StaticLayoutEx.createStaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER,
                    1f, 0f, false, android.text.TextUtils.TruncateAt.END, width, 2);
            float top = cy + dp(30);
            if (top + label.getHeight() > b.bottom - dp(4)) {
                top = Math.max(b.top + dp(4), b.bottom - dp(4) - label.getHeight());
            }
            canvas.save();
            canvas.translate(cx - width / 2f, top);
            label.draw(canvas);
            canvas.restore();
            return;
        }
        // Fits any cell: album tiles can be narrow, so sizes shrink, the title is cut with "…",
        // the description wraps to a few lines and is dropped (then the title) when there is no room.
        int w = b.width(), h = b.height();
        boolean small = w < dp(220) || h < dp(220);
        int pad = small ? dp(12) : dp(32);
        int width = Math.max(1, w - pad * 2);
        int iconSize = small ? dp(30) : dp(44);
        titlePaint.setTextSize(dp(small ? 14 : 18));
        textPaint.setTextSize(dp(small ? 12 : 14));
        String shownTitle = android.text.TextUtils.ellipsize(title, titlePaint, width, android.text.TextUtils.TruncateAt.END).toString();
        float titleH = titlePaint.getTextSize();
        int gap1 = small ? dp(8) : dp(14), gap2 = small ? dp(4) : dp(8);
        StaticLayout body = width >= dp(110) ? StaticLayoutEx.createStaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER,
                1.1f, 0f, false, android.text.TextUtils.TruncateAt.END, width, small ? 3 : 4) : null;
        boolean showTitle = w >= dp(90);
        float total = iconSize + (showTitle ? gap1 + titleH : 0) + (body != null ? gap2 + body.getHeight() : 0);
        if (body != null && total > h - pad) {
            total -= gap2 + body.getHeight();
            body = null;
        }
        if (showTitle && total > h - pad) {
            total -= gap1 + titleH;
            showTitle = false;
        }
        canvas.save();
        canvas.clipRect(b);
        float cx = b.exactCenterX(), y = b.exactCenterY() - total / 2f;
        icon.setBounds((int) (cx - iconSize / 2f), (int) y, (int) (cx + iconSize / 2f), (int) y + iconSize);
        icon.draw(canvas);
        y += iconSize;
        if (showTitle) {
            y += gap1 + titleH;
            canvas.drawText(shownTitle, cx, y - titlePaint.descent(), titlePaint);
        }
        if (body != null) {
            y += gap2;
            canvas.translate(cx - width / 2f, y);
            body.draw(canvas);
        }
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
