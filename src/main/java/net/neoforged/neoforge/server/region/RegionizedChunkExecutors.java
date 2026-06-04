/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated executors for chunk storage work that should not compete with the
 * vanilla global background pools during large player exploration spikes.
 */
public final class RegionizedChunkExecutors {
    private static final MeasuredExecutor CHUNK_IO = new MeasuredExecutor("NeoFolia Chunk IO", RegionizedServerSettings.chunkIoThreadCount());
    private static final MeasuredExecutor CHUNK_PARSE = new MeasuredExecutor("NeoFolia Chunk Parse", RegionizedServerSettings.chunkParseThreadCount());
    private static final MeasuredExecutor CHUNK_GENERATION = new MeasuredExecutor("NeoFolia Chunk Generation", RegionizedServerSettings.chunkWorkerThreadCount());

    private RegionizedChunkExecutors() {
    }

    public static Executor chunkIoExecutor() {
        return CHUNK_IO;
    }

    public static Executor chunkParseExecutor() {
        return CHUNK_PARSE;
    }

    public static Executor chunkGenerationExecutor() {
        return CHUNK_GENERATION;
    }

    public static ExecutorSnapshot chunkIoSnapshot() {
        return CHUNK_IO.snapshot();
    }

    public static ExecutorSnapshot chunkParseSnapshot() {
        return CHUNK_PARSE.snapshot();
    }

    public static ExecutorSnapshot chunkGenerationSnapshot() {
        return CHUNK_GENERATION.snapshot();
    }

    private static final class MeasuredExecutor extends ThreadPoolExecutor {
        private final ConcurrentHashMap<TimedRunnable, Long> activeTasks = new ConcurrentHashMap<>();
        private final AtomicLong longestTaskNanos = new AtomicLong();

        private MeasuredExecutor(String name, int threads) {
            super(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedDaemonThreadFactory(name)
            );
        }

        @Override
        public void execute(Runnable command) {
            super.execute(new TimedRunnable(command, System.nanoTime()));
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable runnable) {
            super.beforeExecute(thread, runnable);
            if (runnable instanceof TimedRunnable timedRunnable) {
                this.activeTasks.put(timedRunnable, System.nanoTime());
            }
        }

        @Override
        protected void afterExecute(Runnable runnable, Throwable throwable) {
            if (runnable instanceof TimedRunnable timedRunnable) {
                Long startedAt = this.activeTasks.remove(timedRunnable);
                if (startedAt != null) {
                    long taskTimeNanos = Math.max(0L, System.nanoTime() - startedAt);
                    this.longestTaskNanos.accumulateAndGet(taskTimeNanos, Math::max);
                }
            }
            super.afterExecute(runnable, throwable);
        }

        private ExecutorSnapshot snapshot() {
            long now = System.nanoTime();
            long oldestQueuedAgeNanos = 0L;
            for (Runnable runnable : this.getQueue()) {
                if (runnable instanceof TimedRunnable timedRunnable) {
                    oldestQueuedAgeNanos = Math.max(oldestQueuedAgeNanos, now - timedRunnable.enqueuedAtNanos());
                }
            }

            long oldestActiveAgeNanos = 0L;
            for (long startedAt : this.activeTasks.values()) {
                oldestActiveAgeNanos = Math.max(oldestActiveAgeNanos, now - startedAt);
            }

            return new ExecutorSnapshot(
                this.getPoolSize(),
                this.getActiveCount(),
                this.getQueue().size(),
                this.getCompletedTaskCount(),
                Math.max(0L, oldestQueuedAgeNanos),
                Math.max(0L, oldestActiveAgeNanos),
                this.longestTaskNanos.get()
            );
        }
    }

    private record TimedRunnable(Runnable task, long enqueuedAtNanos) implements Runnable {
        @Override
        public void run() {
            this.task.run();
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String name;
        private final AtomicInteger threadId = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, this.name + " #" + this.threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    public record ExecutorSnapshot(
        int poolSize,
        int activeTasks,
        int queuedTasks,
        long completedTasks,
        long oldestQueuedTaskAgeNanos,
        long oldestActiveTaskAgeNanos,
        long longestTaskNanos
    ) {
    }
}
