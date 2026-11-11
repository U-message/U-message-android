package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;

/**
 * Circular country flags from assets/flags/&lt;iso2&gt;.webp
 * (circle-flags by HatScripts, MIT, see assets/circle_flags_LICENSE.txt).
 */
public class UMessageCountryFlag {

    private static final LruCache<String, Bitmap> cache = new LruCache<>(64);
    private static final HashSet<String> missing = new HashSet<>();
    private static final int RING_COLOR = 0x40808080;

    @Nullable
    public static Bitmap getBitmap(String iso) {
        if (TextUtils.isEmpty(iso) || iso.length() != 2) {
            return null;
        }
        String key = iso.toLowerCase(Locale.US);
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null) {
                return b;
            }
            if (missing.contains(key)) {
                return null;
            }
        }
        Bitmap bitmap = null;
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("flags/" + key + ".webp")) {
            bitmap = BitmapFactory.decodeStream(in);
        } catch (Exception ignore) {
        } catch (Throwable e) {
            FileLog.e(e);
        }
        synchronized (cache) {
            if (bitmap != null) {
                cache.put(key, bitmap);
            } else {
                missing.add(key);
            }
        }
        return bitmap;
    }

    /** Circular flag with a hairline ring, or null if there is no image for this code. */
    @Nullable
    public static Drawable getDrawable(String iso, int sizeDp) {
        Bitmap b = getBitmap(iso);
        return b == null ? null : new FlagDrawable(b, AndroidUtilities.dp(sizeDp));
    }

    /** "[flag] name" with the flag drawn as an image, or null if there is no image (use emoji fallback). */
    @Nullable
    public static CharSequence nameWithFlag(String iso, CharSequence name, int sizeDp) {
        Drawable d = getDrawable(iso, sizeDp);
        if (d == null) {
            return null;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("\u2060");
        sb.setSpan(new FlagSpan(d, AndroidUtilities.dp(10)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(name);
        return sb;
    }

    public static class FlagDrawable extends Drawable {
        private final Bitmap bitmap;
        private final int size;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src;

        public FlagDrawable(Bitmap bitmap, int size) {
            this.bitmap = bitmap;
            this.size = size;
            src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(1)));
            ringPaint.setColor(RING_COLOR);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            canvas.drawBitmap(bitmap, src, b, bitmapPaint);
            float stroke = ringPaint.getStrokeWidth();
            float r = Math.min(b.width(), b.height()) / 2f - stroke / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, ringPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            bitmapPaint.setAlpha(alpha);
            ringPaint.setAlpha(alpha * (RING_COLOR >>> 24) / 255);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            bitmapPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }

    /** Draws the flag vertically centred on the text line, growing the line height if needed. */
    public static class FlagSpan extends ReplacementSpan {
        private final Drawable drawable;
        private final int gap;

        public FlagSpan(Drawable drawable, int gap) {
            this.drawable = drawable;
            this.gap = gap;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            int size = drawable.getIntrinsicHeight();
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                int center = (pfm.ascent + pfm.descent) / 2;
                int half = size / 2 + AndroidUtilities.dp(1);
                fm.ascent = Math.min(pfm.ascent, center - half);
                fm.descent = Math.max(pfm.descent, center + half);
                fm.top = Math.min(pfm.top, fm.ascent);
                fm.bottom = Math.max(pfm.bottom, fm.descent);
            }
            return drawable.getIntrinsicWidth() + gap;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
            int center = y + (pfm.ascent + pfm.descent) / 2;
            int size = drawable.getIntrinsicHeight();
            int left = (int) x;
            drawable.setBounds(left, center - size / 2, left + drawable.getIntrinsicWidth(), center - size / 2 + size);
            drawable.setAlpha(paint.getAlpha());
            drawable.draw(canvas);
        }
    }
}
