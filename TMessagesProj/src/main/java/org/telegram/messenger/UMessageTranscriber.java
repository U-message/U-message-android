package org.telegram.messenger;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * U message: voice message transcription for everyone, on the device.
 * The voice file (Ogg/Opus) is decoded to 16 kHz mono PCM and handed to Android's speech
 * recognizer as its audio source (Android 13+), in segments so long messages work too.
 */
public class UMessageTranscriber {

    private static final int TARGET_RATE = 16000;
    private static final long TIMEOUT_MS = 3 * 60 * 1000;
    /** longer audio is cut: 10 min of 16 kHz mono 16-bit PCM is ~19 MB, already a lot to hold in memory */
    private static final int MAX_PCM_BYTES = 10 * 60 * TARGET_RATE * 2;
    /** speech decode/recognition is long CPU work: keep it off the shared globalQueue */
    private static final DispatchQueue queue = new DispatchQueue("umSpeech");

    public static boolean isAvailable() {
        return (UMessageUzbekSpeech.isSupported() && UMessageUzbekSpeech.isEnabled()) || (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(ApplicationLoader.applicationContext));
    }

    /** {@code done} runs on the UI thread with the text, or null when recognition failed. */
    public static void transcribe(File file, Utilities.Callback<String> done) {
        if (UMessageUzbekSpeech.isSupported() && UMessageUzbekSpeech.isEnabled() && UMessageUzbekSpeech.isReady() && file != null && file.exists()) {
            // the 7OS keyboard's offline Uzbek model (sherpa-onnx)
            queue.postRunnable(() -> {
                String text = null;
                try {
                    final byte[] pcm = decodeToPcm16k(file);
                    if (pcm != null && pcm.length > 0) {
                        text = UMessageUzbekSpeech.transcribe(UMessageUzbekSpeech.toFloats(pcm));
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                final String result = text;
                AndroidUtilities.runOnUIThread(() -> done.run(result));
            });
            return;
        }
        transcribeWithSystemRecognizer(file, done);
    }

    /** Fallback for devices without the offline model (x86, or the model was not downloaded). */
    private static void transcribeWithSystemRecognizer(File file, Utilities.Callback<String> done) {
        if (Build.VERSION.SDK_INT < 33 || file == null || !file.exists()) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        queue.postRunnable(() -> {
            byte[] pcm = null;
            try {
                pcm = decodeToPcm16k(file);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (pcm == null || pcm.length == 0) {
                AndroidUtilities.runOnUIThread(() -> done.run(null));
                return;
            }
            final byte[] fpcm = pcm;
            AndroidUtilities.runOnUIThread(() -> recognize(fpcm, done));
        });
    }

    private static void recognize(byte[] pcm, Utilities.Callback<String> done) {
        if (Build.VERSION.SDK_INT < 33) {
            done.run(null);
            return;
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception e) {
            FileLog.e(e);
            done.run(null);
            return;
        }
        final ParcelFileDescriptor readEnd = pipe[0], writeEnd = pipe[1];
        // feed the audio from a background thread; the recognizer reads it as a stream
        new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                out.write(pcm);
            } catch (Exception ignore) {
            }
        }, "UMessageTranscriberFeed").start();

        final SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(ApplicationLoader.applicationContext);
        final ArrayList<String> segments = new ArrayList<>();
        final boolean[] finished = new boolean[1];
        final Runnable[] timeout = new Runnable[1];
        final Utilities.Callback<String> finish = text -> {
            if (finished[0]) return;
            finished[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
            try {
                recognizer.destroy();
            } catch (Exception ignore) {
            }
            try {
                readEnd.close();
            } catch (Exception ignore) {
            }
            done.run(text);
        };
        timeout[0] = () -> finish.run(segments.isEmpty() ? null : TextUtils.join(" ", segments));
        AndroidUtilities.runOnUIThread(timeout[0], TIMEOUT_MS);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onSegmentResults(Bundle segmentResults) {
                final String text = best(segmentResults);
                if (!TextUtils.isEmpty(text)) {
                    segments.add(text);
                }
            }

            @Override
            public void onEndOfSegmentedSession() {
                finish.run(TextUtils.join(" ", segments).trim());
            }

            @Override
            public void onResults(Bundle results) {
                final String text = best(results);
                if (!TextUtils.isEmpty(text)) {
                    segments.add(text);
                }
                finish.run(TextUtils.join(" ", segments).trim());
            }

            @Override
            public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    finish.run(TextUtils.join(" ", segments).trim());
                } else {
                    FileLog.e("UMessageTranscriber error " + error);
                    finish.run(segments.isEmpty() ? null : TextUtils.join(" ", segments));
                }
            }
        });

        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLanguageTag());
        // voice messages stay on the phone where an offline model exists
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE);
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            FileLog.e(e);
            finish.run(null);
        }
    }

    private static String best(Bundle results) {
        if (results == null) return null;
        final ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** The app language first (it is what the user chats in), falling back to the device locale. */
    private static String getLanguageTag() {
        try {
            final String lang = LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode;
            if (!TextUtils.isEmpty(lang)) {
                return Locale.forLanguageTag(lang.replace('_', '-')).toLanguageTag();
            }
        } catch (Exception ignore) {
        }
        return Locale.getDefault().toLanguageTag();
    }

    /** Decodes the voice file with MediaCodec and downmixes/resamples it to 16 kHz mono 16-bit PCM. */
    private static byte[] decodeToPcm16k(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                return null;
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            double position = 0; // fractional source position for resampling
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final short[] samples = new short[info.size / 2];
                        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
                        final int frames = samples.length / Math.max(1, channels);
                        final double step = sampleRate / (double) TARGET_RATE;
                        while (position < frames) {
                            final int frame = (int) position;
                            int sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += samples[frame * channels + c];
                            }
                            final int value = sum / Math.max(1, channels);
                            out.write(value & 0xff);
                            out.write((value >> 8) & 0xff);
                            position += step;
                        }
                        position -= frames;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || out.size() >= MAX_PCM_BYTES) {
                        outputDone = true;
                    }
                }
            }
            return out.toByteArray();
        } catch (Throwable e) {
            // OutOfMemoryError included: a huge file must not take the app down
            FileLog.e(e);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
        }
    }
}
