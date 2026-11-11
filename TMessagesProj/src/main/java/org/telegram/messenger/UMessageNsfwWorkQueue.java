package org.telegram.messenger;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One classifier thread: visible media goes ahead of queued background previews. */
public final class UMessageNsfwWorkQueue {
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "nsfw-filter");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setDaemon(true);
                return thread;
            });

    private final class Job implements Runnable, Comparable<Job> {
        final String key;
        final long order = sequence.getAndIncrement();
        final Runnable work;
        int priority;
        Job(String key, int priority, Runnable work) {
            this.key = key;
            this.priority = priority;
            this.work = work;
        }
        @Override public int compareTo(Job other) {
            int difference = Integer.compare(priority, other.priority);
            return difference != 0 ? difference : Long.compare(order, other.order);
        }
        @Override public void run() { work.run(); }
    }

    public void execute(Runnable work) { executor.execute(new Job(null, 1, work)); }
    public void execute(String key, boolean visible, Runnable work) {
        executor.execute(new Job(key, visible ? 0 : 2, work));
    }
    public void promote(String key) {
        for (Runnable runnable : executor.getQueue()) {
            Job job = (Job) runnable;
            if (key.equals(job.key) && job.priority != 0 && executor.getQueue().remove(job)) {
                job.priority = 0;
                executor.getQueue().offer(job);
            }
        }
    }
}
