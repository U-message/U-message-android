package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

/**
 * iOS-style document icon for file messages: a rounded "sheet" with a folded corner, colored by file type,
 * with the extension written on it (PDF red, Word blue, Excel green, archives orange, apps purple...).
 */
public final class UMessageFileBadge {

    private static final Paint sheetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint foldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final Path sheet = new Path();
    private static final Path fold = new Path();

    static {
        labelPaint.setTypeface(AndroidUtilities.bold());
        labelPaint.setColor(0xffffffff);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private UMessageFileBadge() {
    }

    public static String extension(TLRPC.Document document) {
        if (document == null) return "";
        String name = FileLoader.getDocumentFileName(document);
        String ext = "";
        if (!TextUtils.isEmpty(name)) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1);
            }
        }
        if (TextUtils.isEmpty(ext) && document.mime_type != null) {
            int slash = document.mime_type.lastIndexOf('/');
            if (slash >= 0) ext = document.mime_type.substring(slash + 1);
        }
        ext = ext.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return ext.length() > 4 ? ext.substring(0, 4) : ext;
    }

    public static int color(String ext) {
        switch (ext == null ? "" : ext) {
            case "PDF": case "PPT": case "PPTX": case "KEY":
                return 0xffef5350;
            case "DOC": case "DOCX": case "TXT": case "RTF": case "ODT": case "PAGE": case "MD": case "EPUB": case "FB2":
                return 0xff3d8bf2;
            case "XLS": case "XLSX": case "CSV": case "NUMB": case "ODS":
                return 0xff2fb35c;
            case "ZIP": case "RAR": case "7Z": case "TAR": case "GZ": case "BZ2": case "XZ":
                return 0xfff09a37;
            case "APK": case "APKS": case "XAPK": case "AAB": case "IPA": case "EXE": case "DMG": case "MSI":
                return 0xff8a63e8;
            case "MP3": case "WAV": case "M4A": case "OGG": case "FLAC": case "AAC": case "OPUS":
                return 0xffe2508a;
            case "MP4": case "MOV": case "AVI": case "MKV": case "WEBM": case "3GP":
                return 0xff22a8c4;
            case "JPG": case "JPEG": case "PNG": case "GIF": case "WEBP": case "HEIC": case "SVG":
                return 0xff19a974;
            default:
                return 0xff7b8898;
        }
    }

    /** Slightly darker shade for the pressed state. */
    public static int pressed(int color) {
        return androidx.core.graphics.ColorUtils.blendARGB(color, 0xff000000, 0.15f);
    }

    /**
     * Draws the sheet into {@code r} (the 44dp button area). The label is drawn only when {@code label} is true,
     * i.e. the file is on the device; otherwise the download/cancel icon is drawn on top by RadialProgress2.
     */
    public static void draw(Canvas canvas, RectF r, String ext, int color, boolean label, float alpha) {
        if (alpha <= 0) return;
        final float w = r.width() * 0.80f;
        final float h = r.height();
        final float left = r.centerX() - w / 2f, top = r.top, right = left + w, bottom = top + h;
        final float radius = AndroidUtilities.dp(7);
        final float corner = w * 0.36f;

        sheet.rewind();
        sheet.moveTo(left + radius, top);
        sheet.lineTo(right - corner, top);
        sheet.lineTo(right, top + corner);
        sheet.lineTo(right, bottom - radius);
        sheet.quadTo(right, bottom, right - radius, bottom);
        sheet.lineTo(left + radius, bottom);
        sheet.quadTo(left, bottom, left, bottom - radius);
        sheet.lineTo(left, top + radius);
        sheet.quadTo(left, top, left + radius, top);
        sheet.close();

        fold.rewind();
        fold.moveTo(right - corner, top);
        fold.lineTo(right - corner, top + corner - AndroidUtilities.dp(3));
        fold.quadTo(right - corner, top + corner, right - corner + AndroidUtilities.dp(3), top + corner);
        fold.lineTo(right, top + corner);
        fold.close();

        sheetPaint.setColor(color);
        sheetPaint.setAlpha((int) (0xff * alpha));
        canvas.drawPath(sheet, sheetPaint);
        foldPaint.setColor(0xffffffff);
        foldPaint.setAlpha((int) (0x55 * alpha));
        canvas.drawPath(fold, foldPaint);

        if (label && !TextUtils.isEmpty(ext)) {
            float size = AndroidUtilities.dp(ext.length() >= 4 ? 10 : 11.5f);
            labelPaint.setTextSize(size);
            float maxW = w - AndroidUtilities.dp(6);
            float tw = labelPaint.measureText(ext);
            if (tw > maxW) {
                labelPaint.setTextSize(size * maxW / tw);
            }
            labelPaint.setAlpha((int) (0xff * alpha));
            canvas.drawText(ext, r.centerX(), bottom - AndroidUtilities.dp(9), labelPaint);
        }
    }
}
