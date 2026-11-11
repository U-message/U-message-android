package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared plumbing for U message's own HTTP calls (premium server, ads, error reports, chat guard, login log…).
 * They run on their own small pool instead of {@link Utilities#globalQueue}, so a slow or blocked server never
 * holds up Telegram's work queued there, and every response read is size-capped.
 */
public final class UMessageNet {

    /** Default cap for JSON / text replies. */
    public static final int MAX_JSON_BYTES = 1 << 20;

    private static final AtomicInteger threadNo = new AtomicInteger();
    private static final ThreadPoolExecutor executor;

    static {
        executor = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            final Thread t = new Thread(r, "umNet" + threadNo.incrementAndGet());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        executor.allowCoreThreadTimeOut(true);
    }

    private UMessageNet() {
    }

    /** Runs network work off the UI thread and off Telegram's global queue. */
    public static void run(Runnable r) {
        executor.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    /** Reads at most {@code max} bytes; a longer body throws instead of filling memory. Closes the stream. */
    public static byte[] readCapped(InputStream is, long max) throws IOException {
        if (is == null) return new byte[0];
        try (InputStream in = is) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IOException("response too large");
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    public static String readCappedString(InputStream is, long max) throws IOException {
        return new String(readCapped(is, max), "UTF-8");
    }
}
