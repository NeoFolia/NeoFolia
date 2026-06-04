/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

/**
 * Runs tasks for different regions in parallel while preserving single-threaded
 * ordering inside each individual region.
 */
public final class RegionizedTaskDispatcher implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REGION_TIMING_WINDOW = 100;
    private static final int REGION_TICK_REPORT_WINDOW = 20 * 15;
    private static final long REGION_TICK_REPORT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long MIN_REPORT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final ConcurrentHashMap<RegionKey, RegionQueue> regionQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, RegionTiming> regionTimings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, RegionStatsSnapshot> regionStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CoalescedRegionTaskKey, Boolean> coalescedRegionTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CoalescedGlobalTaskKey, Boolean> coalescedGlobalTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<DelayedRegionTask>> delayedRegionTasks = new ConcurrentHashMap<>();
    private final BlockingQueue<RegionQueue> readyRegions = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<GlobalTask> globalTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong currentServerTick = new AtomicLong();
    private final AtomicLong submittedRegionTasks = new AtomicLong();
    private final AtomicLong completedRegionTasks = new AtomicLong();
    private final AtomicLong failedRegionTasks = new AtomicLong();
    private final AtomicLong cancelledRegionTasks = new AtomicLong();
    private final AtomicLong queuedDelayedRegionTasks = new AtomicLong();
    private final AtomicLong submittedGlobalTasks = new AtomicLong();
    private final AtomicLong completedGlobalTasks = new AtomicLong();
    private final AtomicLong failedGlobalTasks = new AtomicLong();
    private final AtomicLong cancelledGlobalTasks = new AtomicLong();
    private volatile ExecutorService workers;
    private volatile int workerCount;
    private volatile int lastDrainedGlobalTaskCount;
    private volatile long lastGlobalDrainTimeNanos;
    private volatile long lastLongestGlobalTaskNanos;
    private volatile String lastLongestGlobalTaskLane = "";
    private volatile long activeGlobalTaskStartNanos;
    private volatile String activeGlobalTaskLane = "";

    public static int defaultThreadCount() {
        return RegionizedServerSettings.regionThreadCount();
    }

    public synchronized void start(int threadCount) {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }

        int workersToStart = Math.max(1, threadCount);
        this.workerCount = workersToStart;
        this.workers = Executors.newFixedThreadPool(workersToStart, new RegionThreadFactory());
        for (int i = 0; i < workersToStart; i++) {
            this.workers.execute(this::workerLoop);
        }
        LOGGER.info("Started NeoForge region dispatcher with {} worker thread(s)", workersToStart);
        LOGGER.info("NeoFolia region settings: {}", RegionizedServerSettings.describe());
    }

    public synchronized void execute(RegionKey region, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        if (!this.tryExecute(region, task)) {
            throw new IllegalStateException("Region dispatcher is not running");
        }
    }

    public synchronized boolean tryExecute(RegionKey region, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        return this.tryExecuteTask(region, new RunnableRegionTask(task, "region-task", System.nanoTime(), this.currentServerTick.get()));
    }

    public int executeCoalescedAll(String lane, Map<RegionKey, ? extends Runnable> tasks) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(tasks, "tasks");
        Map<RegionKey, Runnable> canonicalTasks = new LinkedHashMap<>();
        tasks.forEach((region, task) -> canonicalTasks.merge(
            this.canonicalRegion(region),
            task,
            RegionizedTaskDispatcher::combineTasks
        ));

        int submitted = 0;
        for (Map.Entry<RegionKey, Runnable> entry : canonicalTasks.entrySet()) {
            if (this.tryExecuteCoalesced(entry.getKey(), lane, entry.getValue())) {
                submitted++;
            }
        }
        return submitted;
    }

    public int executeAll(String lane, Map<RegionKey, ? extends Runnable> tasks) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return 0;
        }

        Map<RegionKey, Runnable> canonicalTasks = this.canonicalTaskMap(tasks);
        int submitted = 0;
        for (Map.Entry<RegionKey, Runnable> entry : canonicalTasks.entrySet()) {
            if (!this.tryExecuteTask(entry.getKey(), new RunnableRegionTask(entry.getValue(), lane, System.nanoTime(), this.currentServerTick.get()))) {
                throw new IllegalStateException("Region dispatcher is not running");
            }
            submitted++;
        }
        return submitted;
    }

    public boolean tryExecuteCoalesced(RegionKey region, String lane, Runnable task) {
        return this.tryExecuteCoalesced(region, lane, lane, task);
    }

    public boolean tryExecuteCoalesced(RegionKey region, String lane, Object key, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        region = this.canonicalRegion(region);
        CoalescedRegionTaskKey taskKey = new CoalescedRegionTaskKey(region, lane, key);
        if (this.coalescedRegionTasks.putIfAbsent(taskKey, Boolean.TRUE) != null) {
            return false;
        }

        Runnable coalescedTask = () -> {
            try {
                task.run();
            } finally {
                this.coalescedRegionTasks.remove(taskKey);
            }
        };
        if (!this.tryExecuteTask(region, new RunnableRegionTask(coalescedTask, lane, System.nanoTime(), this.currentServerTick.get()))) {
            this.coalescedRegionTasks.remove(taskKey);
            return false;
        }

        return true;
    }

    public boolean tryExecuteNextTick(RegionKey region, String lane, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        if (!this.running.get()) {
            return false;
        }

        region = this.canonicalRegion(region);
        long targetTick = this.currentServerTick.get() + 1L;
        RunnableRegionTask regionTask = new RunnableRegionTask(task, lane, System.nanoTime(), targetTick);
        this.delayedRegionTasks.computeIfAbsent(targetTick, unused -> new ConcurrentLinkedQueue<>()).add(new DelayedRegionTask(region, regionTask));
        this.queuedDelayedRegionTasks.incrementAndGet();
        return true;
    }

    private synchronized boolean tryExecuteTask(RegionKey region, RegionTask task) {
        return this.enqueueTask(region, task, true);
    }

    private synchronized boolean enqueueTask(RegionKey region, RegionTask task, boolean countSubmitted) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        if (!this.running.get()) {
            return false;
        }

        region = this.canonicalRegion(region);
        if (countSubmitted) {
            this.submittedRegionTasks.incrementAndGet();
        }
        this.regionQueues.computeIfAbsent(region, RegionQueue::new).add(task);
        return true;
    }

    public CompletableFuture<Void> submit(RegionKey region, Runnable task) {
        Objects.requireNonNull(task, "task");
        return this.submit(region, () -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submit(RegionKey region, Supplier<T> task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        region = this.canonicalRegion(region);
        if (RegionizedTickContext.currentRegion().map(region::equals).orElse(false)) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable throwable) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(throwable);
                return future;
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        if (!this.tryExecuteTask(region, new FutureRegionTask<>(task, future, "region-call", System.nanoTime(), this.currentServerTick.get()))) {
            future.completeExceptionally(new IllegalStateException("Region dispatcher is not running"));
        }
        return future;
    }

    public CompletableFuture<Void> submitAll(Map<RegionKey, ? extends Runnable> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Map<RegionKey, Runnable> canonicalTasks = this.canonicalTaskMap(tasks);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[canonicalTasks.size()];
        int index = 0;
        for (Map.Entry<RegionKey, Runnable> entry : canonicalTasks.entrySet()) {
            futures[index++] = this.submit(entry.getKey(), entry.getValue());
        }
        return CompletableFuture.allOf(futures);
    }

    public void awaitAll(String description, Map<RegionKey, ? extends Runnable> tasks) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return;
        }

        Map<RegionKey, Runnable> canonicalTasks = this.canonicalTaskMap(tasks);
        this.ensureSafeBlockingAwait(description, canonicalTasks);

        Map<RegionKey, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (Map.Entry<RegionKey, Runnable> entry : canonicalTasks.entrySet()) {
            futures.put(entry.getKey(), this.submit(entry.getKey(), entry.getValue()));
        }

        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        try {
            if (timeoutSeconds <= 0) {
                allTasks.get();
            } else {
                allTasks.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, futures, timeoutSeconds);
            throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)", ex);
        } catch (ExecutionException ex) {
            logFailedRegions(description, futures);
            throw new IllegalStateException("Failed while waiting for " + description, ex.getCause());
        }
    }

    public void awaitRegions(String description, Collection<RegionKey> regions, RegionKey primaryRegion, Runnable task) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(primaryRegion, "primaryRegion");
        Objects.requireNonNull(task, "task");

        List<RegionKey> uniqueRegions = this.canonicalRegions(regions);
        RegionKey canonicalPrimaryRegion = this.canonicalRegion(primaryRegion);
        if (uniqueRegions.isEmpty()) {
            task.run();
            return;
        }

        if (!uniqueRegions.contains(canonicalPrimaryRegion)) {
            uniqueRegions.add(0, canonicalPrimaryRegion);
        }

        this.ensureSafeBlockingAwait(description, uniqueRegions);
        if (!this.running.get()) {
            task.run();
            return;
        }

        if (uniqueRegions.size() == 1 && RegionizedTickContext.currentRegion().map(uniqueRegions.get(0)::equals).orElse(false)) {
            task.run();
            return;
        }

        int activeWorkerCount = this.workerCount;
        if (uniqueRegions.size() > activeWorkerCount) {
            if (activeWorkerCount == 1) {
                this.awaitPrimaryRegion(description, canonicalPrimaryRegion, task);
                return;
            }

            throw new IllegalStateException(
                "Cannot reserve "
                    + uniqueRegions.size()
                    + " region(s) for "
                    + description
                    + " with only "
                    + activeWorkerCount
                    + " region worker(s)"
            );
        }

        CountDownLatch readyRegions = new CountDownLatch(uniqueRegions.size());
        CountDownLatch releaseRegions = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<RegionKey, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (RegionKey region : uniqueRegions) {
            futures.put(region, this.submit(region, () -> {
                readyRegions.countDown();
                try {
                    awaitLatch(description + " region reservation", readyRegions);
                    if (RegionizedTickContext.currentRegion().map(canonicalPrimaryRegion::equals).orElse(false)) {
                        try {
                            task.run();
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            throw throwable;
                        } finally {
                            releaseRegions.countDown();
                        }
                    } else {
                        awaitLatch(description + " primary region completion", releaseRegions);
                        Throwable throwable = failure.get();
                        if (throwable != null) {
                            throw new IllegalStateException("Primary region failed while running " + description, throwable);
                        }
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    releaseRegions.countDown();
                    throw throwable;
                }
            }));
        }

        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        try {
            if (timeoutSeconds <= 0) {
                allTasks.get();
            } else {
                allTasks.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, futures, timeoutSeconds);
            throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)", ex);
        } catch (ExecutionException ex) {
            logFailedRegions(description, futures);
            throw new IllegalStateException("Failed while waiting for " + description, ex.getCause());
        }
    }

    public void awaitRegionsPaused(String description, Collection<RegionKey> regions, Runnable task) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(task, "task");

        List<RegionKey> uniqueRegions = this.canonicalRegions(regions);
        if (uniqueRegions.isEmpty()) {
            task.run();
            return;
        }

        this.ensureSafeBlockingAwait(description, uniqueRegions);
        if (!this.running.get()) {
            task.run();
            return;
        }

        if (uniqueRegions.size() == 1 && RegionizedTickContext.currentRegion().map(uniqueRegions.get(0)::equals).orElse(false)) {
            task.run();
            return;
        }

        int activeWorkerCount = this.workerCount;
        if (uniqueRegions.size() > activeWorkerCount) {
            if (activeWorkerCount == 1) {
                this.awaitPausedPrimaryRegion(description, uniqueRegions.get(0), task);
                return;
            }

            throw new IllegalStateException(
                "Cannot reserve "
                    + uniqueRegions.size()
                    + " region(s) for "
                    + description
                    + " with only "
                    + activeWorkerCount
                    + " region worker(s)"
            );
        }

        CountDownLatch readyRegions = new CountDownLatch(uniqueRegions.size());
        CountDownLatch releaseRegions = new CountDownLatch(1);
        Map<RegionKey, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (RegionKey region : uniqueRegions) {
            futures.put(region, this.submit(region, () -> {
                readyRegions.countDown();
                awaitLatch(description + " region pause", releaseRegions);
            }));
        }

        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        boolean regionsReady = false;
        Throwable taskFailure = null;
        try {
            if (timeoutSeconds <= 0) {
                waitWithoutTimeout(readyRegions);
            } else if (!readyRegions.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new TimeoutException();
            }

            regionsReady = true;
            try {
                task.run();
            } catch (RuntimeException | Error throwable) {
                taskFailure = throwable;
                throw throwable;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, futures, timeoutSeconds);
            throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)", ex);
        } finally {
            releaseRegions.countDown();
            if (regionsReady) {
                try {
                    awaitPausedRegionCompletion(description, futures);
                } catch (RuntimeException releaseFailure) {
                    if (taskFailure != null) {
                        taskFailure.addSuppressed(releaseFailure);
                    } else {
                        throw releaseFailure;
                    }
                }
            }
        }
    }

    private void awaitPrimaryRegion(String description, RegionKey primaryRegion, Runnable task) {
        CompletableFuture<Void> future = this.submit(primaryRegion, task);
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        try {
            if (timeoutSeconds <= 0) {
                future.get();
            } else {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, Map.of(primaryRegion, future), timeoutSeconds);
            throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)", ex);
        } catch (ExecutionException ex) {
            logFailedRegions(description, Map.of(primaryRegion, future));
            throw new IllegalStateException("Failed while waiting for " + description, ex.getCause());
        }
    }

    private void awaitPausedPrimaryRegion(String description, RegionKey primaryRegion, Runnable task) {
        CountDownLatch readyRegion = new CountDownLatch(1);
        CountDownLatch releaseRegion = new CountDownLatch(1);
        CompletableFuture<Void> future = this.submit(primaryRegion, () -> {
            readyRegion.countDown();
            awaitLatch(description + " region pause", releaseRegion);
        });

        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        boolean regionReady = false;
        Throwable taskFailure = null;
        try {
            if (timeoutSeconds <= 0) {
                waitWithoutTimeout(readyRegion);
            } else if (!readyRegion.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new TimeoutException();
            }

            regionReady = true;
            try {
                task.run();
            } catch (RuntimeException | Error throwable) {
                taskFailure = throwable;
                throw throwable;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, Map.of(primaryRegion, future), timeoutSeconds);
            throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)", ex);
        } finally {
            releaseRegion.countDown();
            if (regionReady) {
                try {
                    awaitPausedRegionCompletion(description, Map.of(primaryRegion, future));
                } catch (RuntimeException releaseFailure) {
                    if (taskFailure != null) {
                        taskFailure.addSuppressed(releaseFailure);
                    } else {
                        throw releaseFailure;
                    }
                }
            }
        }
    }

    private void awaitPausedRegionCompletion(String description, Map<RegionKey, CompletableFuture<Void>> futures) {
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        try {
            if (timeoutSeconds <= 0) {
                allTasks.get();
            } else {
                allTasks.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while releasing " + description, ex);
        } catch (TimeoutException ex) {
            logIncompleteRegions(description, futures, timeoutSeconds);
            throw new IllegalStateException("Timed out releasing " + description + " after " + timeoutSeconds + " second(s)", ex);
        } catch (ExecutionException ex) {
            logFailedRegions(description, futures);
            throw new IllegalStateException("Failed while releasing " + description, ex.getCause());
        }
    }

    private void ensureSafeBlockingAwait(String description, Map<RegionKey, ? extends Runnable> tasks) {
        this.ensureSafeBlockingAwait(description, tasks.keySet());
    }

    private void ensureSafeBlockingAwait(String description, Collection<RegionKey> regions) {
        RegionKey currentRegion = RegionizedTickContext.currentRegion().orElse(null);
        if (currentRegion == null) {
            return;
        }

        for (RegionKey region : regions) {
            if (!currentRegion.equals(region)) {
                throw new IllegalStateException(
                    "Region worker " + currentRegion + " cannot block waiting for " + description + " in another region " + region
                );
            }
        }
    }

    public synchronized void executeGlobal(Runnable task) {
        this.executeGlobal("global-task", task);
    }

    public synchronized void executeGlobal(String lane, Runnable task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        if (!this.tryExecuteGlobal(lane, task)) {
            throw new IllegalStateException("Region dispatcher is not running");
        }
    }

    public synchronized boolean tryExecuteGlobal(Runnable task) {
        return this.tryExecuteGlobal("global-task", task);
    }

    public synchronized boolean tryExecuteGlobal(String lane, Runnable task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        return this.tryExecuteGlobalTask(new RunnableGlobalTask(task, lane, System.nanoTime()));
    }

    public synchronized boolean tryExecuteGlobalCoalesced(String lane, Object key, Runnable task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        CoalescedGlobalTaskKey taskKey = new CoalescedGlobalTaskKey(lane, key);
        if (this.coalescedGlobalTasks.putIfAbsent(taskKey, Boolean.TRUE) != null) {
            return true;
        }

        Runnable coalescedTask = () -> {
            try {
                task.run();
            } finally {
                this.coalescedGlobalTasks.remove(taskKey);
            }
        };
        if (!this.tryExecuteGlobalTask(new RunnableGlobalTask(coalescedTask, lane, System.nanoTime()))) {
            this.coalescedGlobalTasks.remove(taskKey);
            return false;
        }

        return true;
    }

    private synchronized boolean tryExecuteGlobalTask(GlobalTask task) {
        Objects.requireNonNull(task, "task");
        if (!this.running.get()) {
            return false;
        }

        this.submittedGlobalTasks.incrementAndGet();
        this.globalTasks.add(task);
        return true;
    }

    public CompletableFuture<Void> submitGlobal(Runnable task) {
        return this.submitGlobal("global-call", task);
    }

    public CompletableFuture<Void> submitGlobal(String lane, Runnable task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        return this.submitGlobal(lane, () -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submitGlobal(Supplier<T> task) {
        return this.submitGlobal("global-call", task);
    }

    public <T> CompletableFuture<T> submitGlobal(String lane, Supplier<T> task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!this.tryExecuteGlobalTask(new FutureGlobalTask<>(task, future, lane, System.nanoTime()))) {
            future.completeExceptionally(new IllegalStateException("Region dispatcher is not running"));
        }
        return future;
    }

    public int drainGlobalTasks() {
        int maxTasks = RegionizedServerSettings.globalTaskMaxTasksPerTick();
        long maxTimeNanos = TimeUnit.MILLISECONDS.toNanos(RegionizedServerSettings.globalTaskMaxTimeMs());
        long drainStartNanos = System.nanoTime();
        long deadlineNanos = maxTimeNanos <= 0L ? Long.MAX_VALUE : drainStartNanos + maxTimeNanos;
        long longestTaskNanos = 0L;
        String longestTaskLane = "";
        int count = 0;
        GlobalTask task;
        while ((maxTasks <= 0 || count < maxTasks) && (task = this.globalTasks.poll()) != null) {
            long taskStartNanos = System.nanoTime();
            this.activeGlobalTaskStartNanos = taskStartNanos;
            this.activeGlobalTaskLane = task.lane();
            if (runSafely("global task " + task.lane(), task::run)) {
                this.completedGlobalTasks.incrementAndGet();
            } else {
                this.failedGlobalTasks.incrementAndGet();
            }
            long taskElapsedNanos = System.nanoTime() - taskStartNanos;
            if (taskElapsedNanos > longestTaskNanos) {
                longestTaskNanos = taskElapsedNanos;
                longestTaskLane = task.lane();
            }
            this.activeGlobalTaskStartNanos = 0L;
            this.activeGlobalTaskLane = "";
            count++;
            if (maxTimeNanos > 0L && System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
        this.activeGlobalTaskStartNanos = 0L;
        this.activeGlobalTaskLane = "";
        this.lastDrainedGlobalTaskCount = count;
        this.lastGlobalDrainTimeNanos = System.nanoTime() - drainStartNanos;
        this.lastLongestGlobalTaskNanos = longestTaskNanos;
        this.lastLongestGlobalTaskLane = longestTaskLane;
        return count;
    }

    public long queuedRegionTaskCount() {
        return this.regionQueues.values().stream().mapToLong(RegionQueue::taskCount).sum();
    }

    public long queuedDelayedRegionTaskCount() {
        return this.queuedDelayedRegionTasks.get();
    }

    public int queuedRegionCount() {
        return this.regionQueues.size();
    }

    public int queuedGlobalTaskCount() {
        return this.globalTasks.size();
    }

    public long oldestGlobalTaskAgeNanos() {
        long nowNanos = System.nanoTime();
        long oldestTaskNanos = Long.MAX_VALUE;
        for (GlobalTask task : this.globalTasks) {
            oldestTaskNanos = Math.min(oldestTaskNanos, task.enqueuedNanos());
        }

        if (oldestTaskNanos == Long.MAX_VALUE) {
            return 0L;
        }

        return Math.max(0L, nowNanos - oldestTaskNanos);
    }

    public int lastDrainedGlobalTaskCount() {
        return this.lastDrainedGlobalTaskCount;
    }

    public long lastGlobalDrainTimeNanos() {
        return this.lastGlobalDrainTimeNanos;
    }

    public long lastLongestGlobalTaskNanos() {
        return this.lastLongestGlobalTaskNanos;
    }

    public String lastLongestGlobalTaskLane() {
        return this.lastLongestGlobalTaskLane;
    }

    public long activeGlobalTaskAgeNanos() {
        long startNanos = this.activeGlobalTaskStartNanos;
        return startNanos <= 0L ? 0L : Math.max(0L, System.nanoTime() - startNanos);
    }

    public String activeGlobalTaskLane() {
        return this.activeGlobalTaskLane;
    }

    public int workerCount() {
        return this.workerCount;
    }

    public void beginServerTick(long tick) {
        this.currentServerTick.set(tick);
        this.promoteDelayedRegionTasks(tick);
    }

    private void promoteDelayedRegionTasks(long tick) {
        for (Map.Entry<Long, ConcurrentLinkedQueue<DelayedRegionTask>> entry : this.delayedRegionTasks.entrySet()) {
            long targetTick = entry.getKey();
            if (targetTick > tick) {
                continue;
            }

            ConcurrentLinkedQueue<DelayedRegionTask> tasks = entry.getValue();
            if (!this.delayedRegionTasks.remove(targetTick, tasks)) {
                continue;
            }

            DelayedRegionTask delayedTask;
            while ((delayedTask = tasks.poll()) != null) {
                this.queuedDelayedRegionTasks.decrementAndGet();
                if (!this.tryExecuteTask(delayedTask.region(), delayedTask.task())) {
                    this.cancelledRegionTasks.incrementAndGet();
                }
            }
        }
    }

    private RegionKey canonicalRegion(RegionKey region) {
        return RegionizedWorldGuard.ownerForFixedRegion(region);
    }

    private List<RegionKey> canonicalRegions(Collection<RegionKey> regions) {
        LinkedHashSet<RegionKey> uniqueRegions = new LinkedHashSet<>();
        for (RegionKey region : regions) {
            uniqueRegions.add(this.canonicalRegion(region));
        }
        return new ArrayList<>(uniqueRegions);
    }

    private Map<RegionKey, Runnable> canonicalTaskMap(Map<RegionKey, ? extends Runnable> tasks) {
        Map<RegionKey, Runnable> canonicalTasks = new LinkedHashMap<>();
        tasks.forEach((region, task) -> canonicalTasks.merge(
            this.canonicalRegion(region),
            task,
            RegionizedTaskDispatcher::combineTasks
        ));
        return canonicalTasks;
    }

    private static Runnable combineTasks(Runnable first, Runnable second) {
        return () -> {
            Throwable failure = null;
            try {
                first.run();
            } catch (Throwable throwable) {
                failure = throwable;
            }

            try {
                second.run();
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }

            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
        };
    }

    public void replaceRegionStats(Map<RegionKey, RegionStatsSnapshot> stats) {
        this.regionStats.clear();
        this.regionStats.putAll(stats);
    }

    public List<RegionPerformanceSnapshot> regionPerformanceSnapshots() {
        List<RegionPerformanceSnapshot> snapshots = new ArrayList<>();
        long nowNanos = System.nanoTime();
        Map<RegionKey, DelayedRegionStats> delayedStats = this.collectDelayedRegionStats(nowNanos);

        HashSet<RegionKey> regions = new HashSet<>();
        regions.addAll(this.regionTimings.keySet());
        regions.addAll(this.regionQueues.keySet());
        regions.addAll(this.regionStats.keySet());
        regions.addAll(delayedStats.keySet());

        for (RegionKey region : regions) {
            RegionTiming timing = this.regionTimings.get(region);
            RegionQueue queue = this.regionQueues.get(region);
            RegionStatsSnapshot stats = this.regionStats.getOrDefault(region, RegionStatsSnapshot.EMPTY);
            DelayedRegionStats delayed = delayedStats.get(region);
            long queuedTasks = queue == null ? 0 : queue.taskCount();
            long oldestQueuedTaskAgeNanos = queue == null ? 0L : queue.oldestQueuedTaskAgeNanos(nowNanos);
            long delayedTasks = delayed == null ? 0L : delayed.count();
            long oldestDelayedTaskAgeNanos = delayed == null ? 0L : delayed.oldestAgeNanos();
            snapshots.add(timing == null
                ? RegionTiming.emptySnapshot(region, stats, queuedTasks, oldestQueuedTaskAgeNanos, delayedTasks, oldestDelayedTaskAgeNanos)
                : timing.snapshot(region, stats, queuedTasks, oldestQueuedTaskAgeNanos, delayedTasks, oldestDelayedTaskAgeNanos, nowNanos));
        }
        return snapshots;
    }

    private Map<RegionKey, DelayedRegionStats> collectDelayedRegionStats(long nowNanos) {
        Map<RegionKey, DelayedRegionStats> stats = new HashMap<>();
        for (ConcurrentLinkedQueue<DelayedRegionTask> tasks : this.delayedRegionTasks.values()) {
            for (DelayedRegionTask delayedTask : tasks) {
                stats.computeIfAbsent(delayedTask.region(), unused -> new DelayedRegionStats()).record(delayedTask.task(), nowNanos);
            }
        }
        return stats;
    }

    public long submittedRegionTaskCount() {
        return this.submittedRegionTasks.get();
    }

    public long completedRegionTaskCount() {
        return this.completedRegionTasks.get();
    }

    public long failedRegionTaskCount() {
        return this.failedRegionTasks.get();
    }

    public long cancelledRegionTaskCount() {
        return this.cancelledRegionTasks.get();
    }

    public long submittedGlobalTaskCount() {
        return this.submittedGlobalTasks.get();
    }

    public long completedGlobalTaskCount() {
        return this.completedGlobalTasks.get();
    }

    public long failedGlobalTaskCount() {
        return this.failedGlobalTasks.get();
    }

    public long cancelledGlobalTaskCount() {
        return this.cancelledGlobalTasks.get();
    }

    public boolean isRunning() {
        return this.running.get();
    }

    @Override
    public synchronized void close() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }

        ExecutorService activeWorkers = this.workers;
        if (activeWorkers != null) {
            activeWorkers.shutdownNow();
        }
        IllegalStateException closed = new IllegalStateException("Region dispatcher is not running");
        for (RegionQueue queue : this.regionQueues.values()) {
            this.cancelledRegionTasks.addAndGet(queue.cancelPendingTasks(closed));
        }
        for (ConcurrentLinkedQueue<DelayedRegionTask> tasks : this.delayedRegionTasks.values()) {
            DelayedRegionTask delayedTask;
            while ((delayedTask = tasks.poll()) != null) {
                delayedTask.task().cancel(closed);
                this.cancelledRegionTasks.incrementAndGet();
                this.queuedDelayedRegionTasks.decrementAndGet();
            }
        }
        this.delayedRegionTasks.clear();
        this.readyRegions.clear();
        this.regionQueues.clear();
        this.coalescedRegionTasks.clear();
        GlobalTask globalTask;
        while ((globalTask = this.globalTasks.poll()) != null) {
            globalTask.cancel(closed);
            this.cancelledGlobalTasks.incrementAndGet();
        }
    }

    private void workerLoop() {
        while (this.running.get()) {
            try {
                RegionQueue queue = this.readyRegions.take();
                queue.runAvailableTasks();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable throwable) {
                LOGGER.error("Unexpected failure in NeoForge region worker", throwable);
            }
        }
    }

    private static boolean runSafely(String description, Runnable task) {
        try {
            task.run();
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("Failed to run {}", description, throwable);
            return false;
        }
    }

    private void recordRegionTask(RegionKey region, String lane, long tickId, long elapsedNanos, boolean successful) {
        this.recordRegionTask(region, lane, tickId, elapsedNanos, 0L, successful);
    }

    private void recordRegionTask(RegionKey region, String lane, long tickId, long elapsedNanos, long waitNanos, boolean successful) {
        this.regionTimings.computeIfAbsent(region, unused -> new RegionTiming()).record(lane, tickId, elapsedNanos, waitNanos, successful);
    }

    private void beginRegionTask(RegionKey region, String lane, long tickId, long startNanos, long waitNanos) {
        this.regionTimings.computeIfAbsent(region, unused -> new RegionTiming()).begin(lane, tickId, startNanos, waitNanos);
    }

    private static void awaitLatch(String description, CountDownLatch latch) {
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        try {
            boolean completed = timeoutSeconds <= 0 ? waitWithoutTimeout(latch) : latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                throw new IllegalStateException("Timed out waiting for " + description + " after " + timeoutSeconds + " second(s)");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, ex);
        }
    }

    private static boolean waitWithoutTimeout(CountDownLatch latch) throws InterruptedException {
        latch.await();
        return true;
    }

    private static void logIncompleteRegions(String description, Map<RegionKey, CompletableFuture<Void>> futures, int timeoutSeconds) {
        String incompleteRegions = futures.entrySet()
            .stream()
            .filter(entry -> !entry.getValue().isDone())
            .map(entry -> entry.getKey().toString())
            .collect(java.util.stream.Collectors.joining(", "));
        LOGGER.error("Timed out after {} second(s) waiting for {}; incomplete region(s): {}", timeoutSeconds, description, incompleteRegions);
        logFailedRegions(description, futures);
    }

    private static void logFailedRegions(String description, Map<RegionKey, CompletableFuture<Void>> futures) {
        String failedRegions = futures.entrySet()
            .stream()
            .filter(entry -> entry.getValue().isCompletedExceptionally())
            .map(entry -> entry.getKey().toString())
            .collect(java.util.stream.Collectors.joining(", "));
        if (!failedRegions.isEmpty()) {
            LOGGER.error("Failed region(s) while running {}: {}", description, failedRegions);
        }
    }

    private interface RegionTask {
        long enqueuedNanos();

        String lane();

        long tickId();

        void run();

        default void cancel(Throwable throwable) {
        }
    }

    private interface GlobalTask {
        long enqueuedNanos();

        String lane();

        void run();

        default void cancel(Throwable throwable) {
        }
    }

    private record CoalescedRegionTaskKey(RegionKey region, String lane, Object key) {
    }

    private record CoalescedGlobalTaskKey(String lane, Object key) {
    }

    private record DelayedRegionTask(RegionKey region, RegionTask task) {
    }

    public record RegionPerformanceSnapshot(
        RegionKey region,
        RegionStatsSnapshot stats,
        RegionTickReportData tickReport15s,
        long[] taskTimesNanos,
        int sampleCount,
        double averageTaskWaitNanos,
        long maxTaskWaitNanos,
        long queuedTasks,
        long oldestQueuedTaskAgeNanos,
        long delayedTasks,
        long oldestDelayedTaskAgeNanos,
        long completedTasks,
        long failedTasks,
        long lastCompletionNanos
    ) {
        public double averageTaskTimeNanos() {
            if (this.taskTimesNanos.length == 0) {
                return 0.0D;
            }

            long totalNanos = 0L;
            for (long taskTimeNanos : this.taskTimesNanos) {
                totalNanos += taskTimeNanos;
            }
            return (double) totalNanos / (double) this.taskTimesNanos.length;
        }
    }

    public record RegionStatsSnapshot(int chunks, int players, int entities) {
        public static final RegionStatsSnapshot EMPTY = new RegionStatsSnapshot(0, 0, 0);

        public boolean isEmpty() {
            return this.chunks == 0 && this.players == 0 && this.entities == 0;
        }
    }

    public record RegionTickReportData(
        int sampleCount,
        long totalTickTimeNanos,
        double averageTickTimeNanos,
        long reportWindowNanos,
        double averageTickLatencyNanos
    ) {
        public static final RegionTickReportData EMPTY = new RegionTickReportData(0, 0L, 0.0D, MIN_REPORT_WINDOW_NANOS, 0.0D);

        public double utilisation() {
            return this.reportWindowNanos <= 0L ? 0.0D : (double)this.totalTickTimeNanos / (double)this.reportWindowNanos;
        }

        public double tps(double maxTps) {
            if (this.sampleCount == 0 || this.reportWindowNanos <= 0L) {
                return maxTps;
            }

            double targetTickNanos = (double)TimeUnit.SECONDS.toNanos(1L) / maxTps;
            double effectiveTickTimeNanos = Math.max(this.averageTickTimeNanos, this.averageTickLatencyNanos);
            if (effectiveTickTimeNanos <= targetTickNanos) {
                return maxTps;
            }

            double processingRateTps = effectiveTickTimeNanos <= 0.0D
                ? maxTps
                : (double)TimeUnit.SECONDS.toNanos(1L) / effectiveTickTimeNanos;
            return Math.min(maxTps, processingRateTps);
        }
    }

    private static final class RegionTiming {
        private final LaneTiming aggregateTiming = new LaneTiming();
        private final Map<String, LaneTiming> laneTimings = new LinkedHashMap<>();
        private long completedTasks;
        private long failedTasks;
        private long lastCompletionNanos;

        private synchronized void begin(String lane, long tickId, long startNanos, long waitNanos) {
            this.aggregateTiming.begin(tickId, startNanos, waitNanos);
            this.laneTimings.computeIfAbsent(lane, unused -> new LaneTiming()).begin(tickId, startNanos, waitNanos);
        }

        private synchronized void record(String lane, long tickId, long elapsedNanos, long waitNanos, boolean successful) {
            long nowNanos = System.nanoTime();
            this.aggregateTiming.record(tickId, elapsedNanos, waitNanos, nowNanos);
            this.laneTimings.computeIfAbsent(lane, unused -> new LaneTiming()).record(tickId, elapsedNanos, waitNanos, nowNanos);
            this.lastCompletionNanos = nowNanos;
            if (successful) {
                this.completedTasks++;
            } else {
                this.failedTasks++;
            }
        }

        private static RegionPerformanceSnapshot emptySnapshot(
            RegionKey region,
            RegionStatsSnapshot stats,
            long queuedTasks,
            long oldestQueuedTaskAgeNanos,
            long delayedTasks,
            long oldestDelayedTaskAgeNanos
        ) {
            return new RegionPerformanceSnapshot(
                region,
                stats,
                RegionTickReportData.EMPTY,
                new long[0],
                0,
                0.0D,
                0L,
                queuedTasks,
                oldestQueuedTaskAgeNanos,
                delayedTasks,
                oldestDelayedTaskAgeNanos,
                0,
                0,
                0
            );
        }

        private synchronized RegionPerformanceSnapshot snapshot(
            RegionKey region,
            RegionStatsSnapshot stats,
            long queuedTasks,
            long oldestQueuedTaskAgeNanos,
            long delayedTasks,
            long oldestDelayedTaskAgeNanos,
            long nowNanos
        ) {
            LaneTiming reportTiming = this.reportTiming(stats);
            long[] samples = this.aggregateTiming.taskSamples();
            long[] waitSamples = this.aggregateTiming.waitSamples();
            long totalWaitNanos = 0L;
            long maxWaitNanos = 0L;
            for (long waitNanos : waitSamples) {
                totalWaitNanos += waitNanos;
                maxWaitNanos = Math.max(maxWaitNanos, waitNanos);
            }

            return new RegionPerformanceSnapshot(
                region,
                stats,
                reportTiming.tickReport(nowNanos),
                samples,
                samples.length,
                waitSamples.length == 0 ? 0.0D : (double)totalWaitNanos / (double)waitSamples.length,
                maxWaitNanos,
                queuedTasks,
                oldestQueuedTaskAgeNanos,
                delayedTasks,
                oldestDelayedTaskAgeNanos,
                this.completedTasks,
                this.failedTasks,
                this.lastCompletionNanos
            );
        }

        private LaneTiming reportTiming(RegionStatsSnapshot stats) {
            return this.aggregateTiming.hasSamples() ? this.aggregateTiming : LaneTiming.EMPTY;
        }
    }

    private static final class DelayedRegionStats {
        private long count;
        private long oldestAgeNanos;

        private void record(RegionTask task, long nowNanos) {
            this.count++;
            this.oldestAgeNanos = Math.max(this.oldestAgeNanos, Math.max(0L, nowNanos - task.enqueuedNanos()));
        }

        private long count() {
            return this.count;
        }

        private long oldestAgeNanos() {
            return this.oldestAgeNanos;
        }
    }

    private static final class LaneTiming {
        private static final LaneTiming EMPTY = new LaneTiming();

        private final long[] taskTimesNanos = new long[REGION_TIMING_WINDOW];
        private final long[] waitTimesNanos = new long[REGION_TIMING_WINDOW];
        private final long[] tickIds = new long[REGION_TICK_REPORT_WINDOW];
        private final long[] tickTimesNanos = new long[REGION_TICK_REPORT_WINDOW];
        private final long[] tickLatencyNanos = new long[REGION_TICK_REPORT_WINDOW];
        private final long[] tickCompletionNanos = new long[REGION_TICK_REPORT_WINDOW];
        private int nextIndex;
        private int nextTickIndex;
        private long recordedTasks;
        private long activeTickId = Long.MIN_VALUE;
        private long activeStartNanos;
        private long activeWaitNanos;

        private LaneTiming() {
            Arrays.fill(this.tickIds, Long.MIN_VALUE);
        }

        private void begin(long tickId, long startNanos, long waitNanos) {
            this.activeTickId = tickId;
            this.activeStartNanos = startNanos;
            this.activeWaitNanos = Math.max(0L, waitNanos);
        }

        private void record(long tickId, long elapsedNanos, long waitNanos, long nowNanos) {
            this.recordTickTime(tickId, Math.max(1L, elapsedNanos), Math.max(0L, waitNanos), nowNanos);
            this.taskTimesNanos[this.nextIndex] = Math.max(1L, elapsedNanos);
            this.waitTimesNanos[this.nextIndex] = Math.max(0L, waitNanos);
            this.nextIndex = (this.nextIndex + 1) % this.taskTimesNanos.length;
            this.recordedTasks++;
            if (this.activeTickId == tickId) {
                this.activeTickId = Long.MIN_VALUE;
                this.activeStartNanos = 0L;
                this.activeWaitNanos = 0L;
            }
        }

        private boolean hasSamples() {
            return this.recordedTasks > 0L || this.activeStartNanos > 0L;
        }

        private long[] taskSamples() {
            int sampleCount = (int) Math.min(this.recordedTasks, this.taskTimesNanos.length);
            long[] samples = new long[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int sourceIndex = Math.floorMod(this.nextIndex - sampleCount + i, this.taskTimesNanos.length);
                samples[i] = this.taskTimesNanos[sourceIndex];
            }
            return samples;
        }

        private long[] waitSamples() {
            int sampleCount = (int) Math.min(this.recordedTasks, this.waitTimesNanos.length);
            long[] samples = new long[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int sourceIndex = Math.floorMod(this.nextIndex - sampleCount + i, this.waitTimesNanos.length);
                samples[i] = this.waitTimesNanos[sourceIndex];
            }
            return samples;
        }

        private void recordTickTime(long tickId, long elapsedNanos, long waitNanos, long nowNanos) {
            long latencyNanos = Math.max(1L, elapsedNanos + waitNanos);
            for (int i = 0; i < this.tickIds.length; i++) {
                if (this.tickIds[i] == tickId) {
                    this.tickTimesNanos[i] += elapsedNanos;
                    this.tickLatencyNanos[i] = Math.max(this.tickLatencyNanos[i], latencyNanos);
                    this.tickCompletionNanos[i] = Math.max(this.tickCompletionNanos[i], nowNanos);
                    return;
                }
            }

            this.tickIds[this.nextTickIndex] = tickId;
            this.tickTimesNanos[this.nextTickIndex] = elapsedNanos;
            this.tickLatencyNanos[this.nextTickIndex] = latencyNanos;
            this.tickCompletionNanos[this.nextTickIndex] = nowNanos;
            this.nextTickIndex = (this.nextTickIndex + 1) % this.tickTimesNanos.length;
        }

        private RegionTickReportData tickReport(long nowNanos) {
            long oldestIncludedNanos = nowNanos - REGION_TICK_REPORT_WINDOW_NANOS;
            int sampleCount = 0;
            long totalTickTimeNanos = 0L;
            long totalTickLatencyNanos = 0L;
            long firstSampleNanos = Long.MAX_VALUE;
            boolean activeTaskMerged = false;
            long activeTickTimeNanos = this.activeStartNanos > 0L ? Math.max(1L, nowNanos - this.activeStartNanos) : 0L;
            long activeTickLatencyNanos = activeTickTimeNanos > 0L ? Math.max(1L, activeTickTimeNanos + this.activeWaitNanos) : 0L;

            for (int i = 0; i < this.tickTimesNanos.length; i++) {
                long tickTimeNanos = this.tickTimesNanos[i];
                long tickLatencyNanos = this.tickLatencyNanos[i];
                long completionNanos = this.tickCompletionNanos[i];
                if (activeTickTimeNanos > 0L && this.tickIds[i] == this.activeTickId) {
                    tickTimeNanos += activeTickTimeNanos;
                    tickLatencyNanos = Math.max(tickLatencyNanos, activeTickLatencyNanos);
                    completionNanos = nowNanos;
                    activeTaskMerged = true;
                }
                if (tickTimeNanos <= 0L || completionNanos < oldestIncludedNanos) {
                    continue;
                }

                sampleCount++;
                totalTickTimeNanos += tickTimeNanos;
                totalTickLatencyNanos += Math.max(1L, tickLatencyNanos);
                firstSampleNanos = Math.min(firstSampleNanos, completionNanos);
            }

            if (activeTickTimeNanos > 0L && !activeTaskMerged && nowNanos >= oldestIncludedNanos) {
                sampleCount++;
                totalTickTimeNanos += activeTickTimeNanos;
                totalTickLatencyNanos += Math.max(1L, activeTickLatencyNanos);
                firstSampleNanos = Math.min(firstSampleNanos, nowNanos);
            }

            if (sampleCount == 0) {
                return RegionTickReportData.EMPTY;
            }

            long observedWindowNanos = firstSampleNanos == Long.MAX_VALUE ? REGION_TICK_REPORT_WINDOW_NANOS : nowNanos - firstSampleNanos;
            long reportWindowNanos = Math.max(MIN_REPORT_WINDOW_NANOS, Math.min(REGION_TICK_REPORT_WINDOW_NANOS, observedWindowNanos));
            return new RegionTickReportData(
                sampleCount,
                totalTickTimeNanos,
                (double)totalTickTimeNanos / (double)sampleCount,
                reportWindowNanos,
                (double)totalTickLatencyNanos / (double)sampleCount
            );
        }
    }

    private record RunnableRegionTask(Runnable task, String lane, long enqueuedNanos, long tickId) implements RegionTask {
        private RunnableRegionTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(lane, "lane");
        }

        @Override
        public void run() {
            this.task.run();
        }
    }

    private record FutureRegionTask<T>(Supplier<T> task, CompletableFuture<T> future, String lane, long enqueuedNanos, long tickId) implements RegionTask {
        private FutureRegionTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(future, "future");
            Objects.requireNonNull(lane, "lane");
        }

        @Override
        public void run() {
            try {
                this.future.complete(this.task.get());
            } catch (Throwable throwable) {
                this.future.completeExceptionally(throwable);
                throw throwable;
            }
        }

        @Override
        public void cancel(Throwable throwable) {
            this.future.completeExceptionally(throwable);
        }
    }

    private record RunnableGlobalTask(Runnable task, String lane, long enqueuedNanos) implements GlobalTask {
        private RunnableGlobalTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(lane, "lane");
        }

        @Override
        public void run() {
            this.task.run();
        }
    }

    private record FutureGlobalTask<T>(Supplier<T> task, CompletableFuture<T> future, String lane, long enqueuedNanos) implements GlobalTask {
        private FutureGlobalTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(future, "future");
            Objects.requireNonNull(lane, "lane");
        }

        @Override
        public void run() {
            try {
                this.future.complete(this.task.get());
            } catch (Throwable throwable) {
                this.future.completeExceptionally(throwable);
                throw throwable;
            }
        }

        @Override
        public void cancel(Throwable throwable) {
            this.future.completeExceptionally(throwable);
        }
    }

    private final class RegionQueue {
        private final RegionKey region;
        private final ConcurrentLinkedQueue<RegionTask> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final RandomSource random = RandomSource.create();

        private RegionQueue(RegionKey region) {
            this.region = region;
        }

        private void add(RegionTask task) {
            this.tasks.add(task);
            if (this.scheduled.compareAndSet(false, true)) {
                RegionizedTaskDispatcher.this.readyRegions.add(this);
            }
        }

        private void runAvailableTasks() {
            RegionKey canonicalRegion = RegionizedTaskDispatcher.this.canonicalRegion(this.region);
            if (!canonicalRegion.equals(this.region)) {
                this.redirectPendingTasks(canonicalRegion);
                return;
            }

            int maxTasks = RegionizedServerSettings.regionTaskMaxTasksPerRun();
            long maxTimeNanos = TimeUnit.MILLISECONDS.toNanos(RegionizedServerSettings.regionTaskMaxTimeMs());
            long runStartNanos = System.nanoTime();
            long deadlineNanos = maxTimeNanos <= 0L ? Long.MAX_VALUE : runStartNanos + maxTimeNanos;
            int count = 0;
            try {
                RegionTask task;
                while ((maxTasks <= 0 || count < maxTasks) && RegionizedTaskDispatcher.this.running.get() && (task = this.tasks.poll()) != null) {
                    RegionTask currentTask = task;
                    RegionizedTickContext.runInRegion(this.region, this.random, () -> {
                        long startNanos = System.nanoTime();
                        long waitNanos = Math.max(0L, startNanos - currentTask.enqueuedNanos());
                        RegionizedTaskDispatcher.this.beginRegionTask(this.region, currentTask.lane(), currentTask.tickId(), startNanos, waitNanos);
                        boolean successful = runSafely("region task " + this.region, currentTask::run);
                        RegionizedTaskDispatcher.this.recordRegionTask(
                            this.region,
                            currentTask.lane(),
                            currentTask.tickId(),
                            System.nanoTime() - startNanos,
                            waitNanos,
                            successful
                        );
                        if (successful) {
                            RegionizedTaskDispatcher.this.completedRegionTasks.incrementAndGet();
                        } else {
                            RegionizedTaskDispatcher.this.failedRegionTasks.incrementAndGet();
                        }
                    });
                    count++;
                    if (maxTimeNanos > 0L && System.nanoTime() >= deadlineNanos) {
                        break;
                    }
                }
            } finally {
                this.scheduled.set(false);
                if (RegionizedTaskDispatcher.this.running.get() && !this.tasks.isEmpty() && this.scheduled.compareAndSet(false, true)) {
                    RegionizedTaskDispatcher.this.readyRegions.add(this);
                }
            }
        }

        private void redirectPendingTasks(RegionKey canonicalRegion) {
            try {
                RegionTask task;
                while (RegionizedTaskDispatcher.this.running.get() && (task = this.tasks.poll()) != null) {
                    if (!RegionizedTaskDispatcher.this.enqueueTask(canonicalRegion, task, false)) {
                        task.cancel(new IllegalStateException("Region dispatcher is not running"));
                        RegionizedTaskDispatcher.this.cancelledRegionTasks.incrementAndGet();
                    }
                }
            } finally {
                this.scheduled.set(false);
                if (RegionizedTaskDispatcher.this.running.get() && !this.tasks.isEmpty() && this.scheduled.compareAndSet(false, true)) {
                    RegionizedTaskDispatcher.this.readyRegions.add(this);
                }
            }
        }

        private int taskCount() {
            return this.tasks.size();
        }

        private long oldestQueuedTaskAgeNanos(long nowNanos) {
            long oldestQueuedTaskNanos = Long.MAX_VALUE;
            for (RegionTask task : this.tasks) {
                oldestQueuedTaskNanos = Math.min(oldestQueuedTaskNanos, task.enqueuedNanos());
            }

            if (oldestQueuedTaskNanos == Long.MAX_VALUE) {
                return 0L;
            }

            return Math.max(0L, nowNanos - oldestQueuedTaskNanos);
        }

        private int cancelPendingTasks(Throwable throwable) {
            int count = 0;
            RegionTask task;
            while ((task = this.tasks.poll()) != null) {
                task.cancel(throwable);
                count++;
            }
            return count;
        }
    }

    private static final class RegionThreadFactory implements ThreadFactory {
        private final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "NeoForge Region Worker #" + this.id.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
