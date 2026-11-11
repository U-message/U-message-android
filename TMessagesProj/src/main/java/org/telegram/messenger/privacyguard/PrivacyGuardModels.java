package org.telegram.messenger.privacyguard;

import android.content.res.AssetManager;

import org.telegram.messenger.ApplicationLoader;
import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Loads the bundled on-device models (read into direct buffers, so compressed assets work too) and shares the
 * read-only model buffers between interpreters. Buffers are dropped when nothing uses them.
 */
final class PrivacyGuardModels {

    static final String DETECTOR = "privacy_guard/face_detector.tflite";
    static final String LANDMARKS = "privacy_guard/face_landmarks.tflite";
    static final String BLENDSHAPES = "privacy_guard/face_blendshapes.tflite";
    static final String EMBEDDING = "privacy_guard/face_embedding.tflite";

    private static final HashMap<String, ByteBuffer> buffers = new HashMap<>();
    private static int users;

    private PrivacyGuardModels() {
    }

    static synchronized void acquire() {
        users++;
    }

    static synchronized void release() {
        if (--users <= 0) {
            users = 0;
            buffers.clear();
        }
    }

    static Interpreter create(String asset, int threads) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(threads);
        options.setUseXNNPACK(true);
        return new Interpreter(load(asset), options);
    }

    private static synchronized ByteBuffer load(String asset) throws IOException {
        ByteBuffer buffer = buffers.get(asset);
        if (buffer != null) {
            return buffer;
        }
        AssetManager assets = ApplicationLoader.applicationContext.getAssets();
        byte[] bytes;
        try (InputStream in = assets.open(asset)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            bytes = out.toByteArray();
        }
        buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        buffer.put(bytes);
        buffer.rewind();
        buffers.put(asset, buffer);
        return buffer;
    }

    static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
