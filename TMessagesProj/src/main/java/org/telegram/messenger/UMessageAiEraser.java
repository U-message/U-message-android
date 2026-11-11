package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.CancellationException;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** LaMa inference stays on device. Only the model weights are downloaded. */
public final class UMessageAiEraser {
    private static final int SIZE = 512;
    private static final long MODEL_BYTES = 208044816L;
    private static final String MODEL_SHA = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6";
    private static final String MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/c3c0c9e468934d62e79c329e35d82dd09ff8c444/lama_fp32.onnx";

    public interface Progress {
        boolean isCancelled();
        void onDownload(int percent);
    }

    public static boolean isModelReady() {
        return modelFile().length() == MODEL_BYTES;
    }

    private static File modelFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "ai-eraser-lama-v1.onnx");
    }

    private static void checkCancelled(Progress progress) {
        if (progress.isCancelled()) throw new CancellationException();
    }

    private static synchronized File prepareModel(Progress progress) throws Exception {
        File target = modelFile();
        if (isModelReady()) return target;
        File partial = new File(target.getPath() + ".download");
        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Model download failed");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            int lastPercent = -1;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(progress);
                    total += count;
                    if (total > MODEL_BYTES) throw new IOException("Unexpected model size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    int percent = (int) (total * 100 / MODEL_BYTES);
                    if (percent != lastPercent) {
                        progress.onDownload(percent);
                        lastPercent = percent;
                    }
                }
            }
            StringBuilder sha = new StringBuilder();
            for (byte value : digest.digest()) sha.append(String.format(java.util.Locale.US, "%02x", value & 255));
            if (total != MODEL_BYTES || !MODEL_SHA.equals(sha.toString())) throw new IOException("Invalid model checksum");
            checkCancelled(progress);
            if (!partial.renameTo(target)) throw new IOException("Cannot store AI model");
            return target;
        } finally {
            connection.disconnect();
            // A partial cache is never used for inference.
            if (partial.exists()) partial.delete();
        }
    }

    // The LaMa session is ~200 MB of native memory: built once and kept while the app is in front,
    // dropped when the app goes to the background or memory runs low.
    private static final Object sessionLock = new Object();
    private static OrtSession cachedSession;
    private static int sessionUsers;
    private static boolean closeRequested;
    private static boolean callbacksRegistered;

    private static OrtSession acquireSession(File model) throws Exception {
        synchronized (sessionLock) {
            if (!callbacksRegistered) {
                callbacksRegistered = true;
                ApplicationLoader.applicationContext.registerComponentCallbacks(new android.content.ComponentCallbacks2() {
                    @Override
                    public void onTrimMemory(int level) {
                        if (level >= TRIM_MEMORY_UI_HIDDEN) releaseSession();
                    }

                    @Override
                    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
                    }

                    @Override
                    public void onLowMemory() {
                        releaseSession();
                    }
                });
            }
            closeRequested = false;
            if (cachedSession == null) {
                OrtEnvironment environment = OrtEnvironment.getEnvironment();
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(2);
                    options.setMemoryPatternOptimization(false);
                    cachedSession = environment.createSession(model.getPath(), options);
                }
            }
            sessionUsers++;
            return cachedSession;
        }
    }

    private static void returnSession() {
        synchronized (sessionLock) {
            sessionUsers = Math.max(0, sessionUsers - 1);
            if (closeRequested && sessionUsers == 0) {
                closeSessionLocked();
            }
        }
    }

    /** Frees the cached session now, or right after the inference that is running. */
    public static void releaseSession() {
        synchronized (sessionLock) {
            if (sessionUsers > 0) {
                closeRequested = true;
            } else {
                closeSessionLocked();
            }
        }
    }

    private static void closeSessionLocked() {
        closeRequested = false;
        if (cachedSession != null) {
            try {
                cachedSession.close();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            cachedSession = null;
        }
    }

    public static Bitmap erase(Bitmap source, Bitmap mask, Progress progress) throws Exception {
        try {
            return eraseInternal(source, mask, progress);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            // OutOfMemoryError and native allocation failures: free the model and report a normal failure
            releaseSession();
            throw new Exception(t);
        }
    }

    private static Bitmap eraseInternal(Bitmap source, Bitmap mask, Progress progress) throws Exception {
        File model = prepareModel(progress);
        checkCancelled(progress);
        progress.onDownload(101);
        int width = source.getWidth(), height = source.getHeight();
        // bounding box of the selection, one row at a time (no full-image pixel array)
        int[] maskRow = new int[width];
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            mask.getPixels(maskRow, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (Color.alpha(maskRow[x]) > 0) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
        }
        maskRow = null;
        if (right < left) throw new IllegalArgumentException("Empty selection");
        // Keep context around the selection, retaining full resolution outside it.
        int side = Math.min(Math.max(width, height), Math.max(256, Math.max(right - left + 1, bottom - top + 1) * 2));
        int cropWidth = Math.min(width, side), cropHeight = Math.min(height, side);
        left = Math.max(0, Math.min(width - cropWidth, (left + right - cropWidth) / 2));
        top = Math.max(0, Math.min(height - cropHeight, (top + bottom - cropHeight) / 2));
        Rect region = new Rect(left, top, left + cropWidth, top + cropHeight);
        Bitmap inputBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Bitmap maskBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(inputBitmap).drawBitmap(source, region, new Rect(0, 0, SIZE, SIZE), paint);
        new Canvas(maskBitmap).drawBitmap(mask, region, new Rect(0, 0, SIZE, SIZE), paint);
        int pixelsCount = SIZE * SIZE;
        int[] pixels = new int[pixelsCount], smallMask = new int[pixelsCount];
        inputBitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        maskBitmap.getPixels(smallMask, 0, SIZE, 0, 0, SIZE, SIZE);
        inputBitmap.recycle(); maskBitmap.recycle();
        float[] imageData = new float[pixelsCount * 3], maskData = new float[pixelsCount];
        for (int i = 0; i < pixelsCount; i++) {
            imageData[i] = Color.red(pixels[i]) / 255f;
            imageData[pixelsCount + i] = Color.green(pixels[i]) / 255f;
            imageData[pixelsCount * 2 + i] = Color.blue(pixels[i]) / 255f;
            maskData[i] = Color.alpha(smallMask[i]) > 0 ? 1f : 0f;
        }
        smallMask = null;
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession session = acquireSession(model);
        try (OnnxTensor imageTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(imageData), new long[]{1, 3, SIZE, SIZE});
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(maskData), new long[]{1, 1, SIZE, SIZE})) {
            checkCancelled(progress);
            HashMap<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("image", imageTensor); inputs.put("mask", maskTensor);
            try (OrtSession.Result output = session.run(inputs)) {
                checkCancelled(progress);
                FloatBuffer values = ((OnnxTensor) output.get(0)).getFloatBuffer();
                for (int i = 0; i < pixelsCount; i++) {
                    pixels[i] = Color.rgb(channel(values.get(i)), channel(values.get(pixelsCount + i)), channel(values.get(pixelsCount * 2 + i)));
                }
            }
        } finally {
            returnSession();
        }
        Bitmap prediction = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Bitmap restored = Bitmap.createScaledBitmap(prediction, cropWidth, cropHeight, true);
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        // blend only inside the crop region, row by row
        int[] row = new int[cropWidth], originalRow = new int[cropWidth], cropMask = new int[cropWidth];
        try {
            for (int y = 0; y < cropHeight; y++) {
                checkCancelled(progress);
                restored.getPixels(row, 0, cropWidth, 0, y, cropWidth, 1);
                source.getPixels(originalRow, 0, cropWidth, left, top + y, cropWidth, 1);
                mask.getPixels(cropMask, 0, cropWidth, left, top + y, cropWidth, 1);
                for (int x = 0; x < cropWidth; x++) {
                    int alpha = Color.alpha(cropMask[x]);
                    int original = originalRow[x];
                    int generated = row[x];
                    row[x] = Color.argb(Color.alpha(original), blend(Color.red(original), Color.red(generated), alpha),
                        blend(Color.green(original), Color.green(generated), alpha), blend(Color.blue(original), Color.blue(generated), alpha));
                }
                result.setPixels(row, 0, cropWidth, left, top + y, cropWidth, 1);
            }
        } catch (Throwable e) {
            result.recycle();
            throw e;
        } finally {
            if (restored != prediction) restored.recycle();
            prediction.recycle();
        }
        return result;
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static int blend(int original, int generated, int alpha) { return (original * (255 - alpha) + generated * alpha + 127) / 255; }
}
