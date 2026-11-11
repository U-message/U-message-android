package org.telegram.messenger.privacyguard;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * A camera frame copied out of an {@link Image} so the camera buffer is returned at once. Lives only in memory,
 * is reused for the next frame and is never written anywhere.
 */
final class YuvFrame {

    int width, height;
    byte[] y, u, v;
    int yRowStride, uvRowStride, uvPixelStride;
    long timestamp;

    void copyFrom(Image image) {
        width = image.getWidth();
        height = image.getHeight();
        timestamp = image.getTimestamp();
        Image.Plane[] planes = image.getPlanes();
        y = copy(planes[0].getBuffer(), y);
        u = copy(planes[1].getBuffer(), u);
        v = copy(planes[2].getBuffer(), v);
        yRowStride = planes[0].getRowStride();
        uvRowStride = planes[1].getRowStride();
        uvPixelStride = planes[1].getPixelStride();
    }

    private static byte[] copy(ByteBuffer buffer, byte[] target) {
        buffer.rewind();
        final int size = buffer.remaining();
        if (target == null || target.length < size) {
            target = new byte[size];
        }
        buffer.get(target, 0, size);
        return target;
    }

    /** Overwrites the pixel data, so no frame lingers in memory after Privacy Guard stops. */
    void wipe() {
        if (y != null) java.util.Arrays.fill(y, (byte) 0);
        if (u != null) java.util.Arrays.fill(u, (byte) 0);
        if (v != null) java.util.Arrays.fill(v, (byte) 0);
    }

    /** Converts to an upright ARGB bitmap rotated clockwise by {@code rotation} degrees. */
    static final class Converter {

        private int[] argb;
        private Bitmap bitmap;
        float meanLuma;

        Bitmap convert(YuvFrame f, int rotation) {
            final boolean swap = rotation == 90 || rotation == 270;
            final int outW = swap ? f.height : f.width;
            final int outH = swap ? f.width : f.height;
            if (bitmap == null || bitmap.getWidth() != outW || bitmap.getHeight() != outH) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                argb = new int[outW * outH];
            }
            final byte[] yp = f.y, up = f.u, vp = f.v;
            final int yRow = f.yRowStride, uvRow = f.uvRowStride, uvPix = f.uvPixelStride;
            final int srcW = f.width, srcH = f.height;
            long lumaSum = 0;
            int lumaCount = 0;
            for (int oy = 0; oy < outH; oy++) {
                final int rowOffset = oy * outW;
                for (int ox = 0; ox < outW; ox++) {
                    final int sx, sy;
                    switch (rotation) {
                        case 90: sx = oy; sy = srcH - 1 - ox; break;
                        case 180: sx = srcW - 1 - ox; sy = srcH - 1 - oy; break;
                        case 270: sx = srcW - 1 - oy; sy = ox; break;
                        default: sx = ox; sy = oy; break;
                    }
                    final int yv = yp[sy * yRow + sx] & 0xff;
                    final int uvIndex = (sy >> 1) * uvRow + (sx >> 1) * uvPix;
                    final int uu = (up[uvIndex] & 0xff) - 128;
                    final int vv = (vp[uvIndex] & 0xff) - 128;
                    int r = yv + ((1436 * vv) >> 10);
                    int g = yv - ((352 * uu + 731 * vv) >> 10);
                    int b = yv + ((1815 * uu) >> 10);
                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);
                    argb[rowOffset + ox] = 0xff000000 | (r << 16) | (g << 8) | b;
                    if (((ox | oy) & 7) == 0) {
                        lumaSum += yv;
                        lumaCount++;
                    }
                }
            }
            bitmap.setPixels(argb, 0, outW, 0, 0, outW, outH);
            meanLuma = lumaCount > 0 ? lumaSum / (float) lumaCount : 0;
            return bitmap;
        }

        void release() {
            if (bitmap != null) {
                bitmap.eraseColor(0);
                bitmap.recycle();
                bitmap = null;
            }
            if (argb != null) {
                java.util.Arrays.fill(argb, 0);
                argb = null;
            }
        }
    }
}
