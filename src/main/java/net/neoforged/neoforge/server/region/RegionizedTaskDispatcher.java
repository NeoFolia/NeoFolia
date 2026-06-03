/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final String THREAD_COUNT_PROPERTY = "neoforge.regionThreads";
    private static final int REGION_TIMING_WINDOW = 100;
    private static final int REGION_TICK_REPORT_WINDOW = 20 * 15;
    private static final long REGION_TICK_REPORT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long MIN_REPORT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final ConcurrentHashMap<RegionKey, RegionQueue> regionQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, RegionTiming> regionTimings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, RegionStatsSnapshot> regionStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CoalescedRegionTaskKey, Boolean> coalescedRegionTasks = new ConcurrentHashMap<>();
    private final BlockingQueue<RegionQueue> readyRegions = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<GlobalTask> globalTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong currentServerTick = new AtomicLong();
    private final AtomicLong submittedRegionTasks = new AtomicLong();
    private final AtomicLong completedRegionTasks = new AtomicLong();
    private final AtomicLong failedRegionTasks = new AtomicLong();
    private final AtomicLong cancelledRegionTasks = new AtomicLong();
    private final AtomicLong submittedGlobalTasks = new AtomicLong();
    private final AtomicLong completedGlobalTasks = new AtomicLong();
    private final AtomicLong failedGlobalTasks = new AtomicLong();
    private final AtomicLong cancelledGlobalTasks = new AtomicLong();
    private volatile ExecutorService workers;
    private volatile int workerCount;

    public static int defaultThreadCount() {
        String configured = System.getProperty(THREAD_COUNT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured));
            } catch (NumberFormatException ex) {
                LOGGER.warn("Invalid {} value '{}', using automatic region thread count", THREAD_COUNT_PROPERTY, configured);
            }
        }

        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
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
        int submitted = 0;
        for (Map.Entry<RegionKey, ? extends Runnable> entry : tasks.entrySet()) {
            if (this.tryExecuteCoalesced(entry.getKey(), lane, entry.getValue())) {
                submitted++;
            }
        }
        return submitted;
    }

    public boolean tryExecuteCoalesced(RegionKey region, String lane, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        CoalescedRegionTaskKey key = new CoalescedRegionTaskKey(region, lane);
        if (this.coalescedRegionTasks.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }

        Runnable coalescedTask = () -> {
            try {
                task.run();
            } finally {
                this.coalescedRegionTasks.remove(key);
            }
        };
        if (!this.tryExecuteTask(region, new RunnableRegionTask(coalescedTask, lane, System.nanoTime(), this.currentServerTick.get()))) {
            this.coalescedRegionTasks.remove(key);
            return false;
        }

        return true;
    }

    private synchronized boolean tryExecuteTask(RegionKey region, RegionTask task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        if (!this.running.get()) {
            return false;
        }

        this.submittedRegionTasks.incrementAndGet();
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

        CompletableFuture<?>[] futures = new CompletableFuture<?>[tasks.size()];
        int index = 0;
        for (Map.Entry<RegionKey, ? extends Runnable> entry : tasks.entrySet()) {
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

        this.ensureSafeBlockingAwait(description, tasks);

        Map<RegionKey, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (Map.Entry<RegionKey, ? extends Runnable> entry : tasks.entrySet()) {
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

        List<RegionKey> uniqueRegions = new ArrayList<>(new LinkedHashSet<>(regions));
        if (uniqueRegions.isEmpty()) {
            task.run();
            return;
        }

        if (!uniqueRegions.contains(primaryRegion)) {
            uniqueRegions.add(0, primaryRegion);
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
                this.awaitPrimaryRegion(description, primaryRegion, task);
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
                    if (RegionizedTickContext.currentRegion().map(primaryRegion::equals).orElse(false)) {
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

        List<RegionKey> uniqueRegions = new ArrayList<>(new LinkedHashSet<>(regions));
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
        Objects.requireNonNull(task, "task");
        if (!this.tryExecuteGlobal(task)) {
            throw new IllegalStateException("Region dispatcher is not running");
        }
    }

    public synchronized boolean tryExecuteGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        return this.tryExecuteGlobalTask(new RunnableGlobalTask(task));
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
        Objects.requireNonNull(task, "task");
        return this.submitGlobal(() -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submitGlobal(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!this.tryExecuteGlobalTask(new FutureGlobalTask<>(task, future))) {
            future.completeExceptionally(new IllegalStateException("Region dispatcher is not running"));
        }
        return future;
    }

    public int drainGlobalTasks() {
        int count = 0;
        GlobalTask task;
        while ((task = this.globalTasks.poll()) != null) {
            if (runSafely("global task", task::run)) {
                this.completedGlobalTasks.incrementAndGet();
            } else {
                this.failedGlobalTasks.incrementAndGet();
            }
            count++;
        }
        return count;
    }

    public long queuedRegionTaskCount() {
        return this.regionQueues.values().stream().mapToLong(RegionQueue::taskCount).sum();
    }

    public int queuedRegionCount() {
        return this.regionQueues.size();
    }

    public int queuedGlobalTaskCount() {
        return this.globalTasks.size();
    }

    public int workerCount() {
        return this.workerCount;
    }

    public void beginServerTick(long tick) {
        this.currentServerTick.set(tick);
    }

    public void replaceRegionStats(Map<RegionKey, RegionStatsSnapshot> stats) {
        this.regionStats.clear();
        this.regionStats.putAll(stats);
    }

    public List<RegionPerformanceSnapshot> regionPerformanceSnapshots() {
        List<RegionPerformanceSnapshot> snapshots = new ArrayList<>();
        long nowNanos = System.nanoTime();

        HashSet<RegionKey> regions = new HashSet<>();
        regions.addAll(this.regionTimings.keySet());
        regions.addAll(this.regionQueues.keySet());
        regions.addAll(this.regionStats.keySet());

        for (RegionKey region : regions) {
            RegionTiming timing = this.regionTimings.get(region);
            RegionQueue queue = this.regionQueues.get(region);
            RegionStatsSnapshot stats = this.regionStats.getOrDefault(region, RegionStatsSnapshot.EMPTY);
            long queuedTasks = queue == null ? 0 : queue.taskCount();
            long oldestQueuedTaskAgeNanos = queue == null ? 0L : queue.oldestQueuedTaskAgeNanos(nowNanos);
            snapshots.add(timing == null
                ? RegionTiming.emptySnapshot(region, stats, queuedTasks, oldestQueuedTaskAgeNanos)
                : timing.snapshot(region, stats, queuedTasks, oldestQueuedTaskAgeNanos, nowNanos));
        }
        return snapshots;
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
        this.regionTimings.computeIfAbsent(region, unused -> new RegionTiming()).record(lane, tickId, elapsedNanos, successful);
    }

    private void beginRegionTask(RegionKey region, String lane, long tickId, long startNanos) {
        this.regionTimings.computeIfAbsent(region, unused -> new RegionTiming()).begin(lane, tickId, startNanos);
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
        void run();

        default void cancel(Throwable throwable) {
        }
    }

    private record CoalescedRegionTaskKey(RegionKey region, String lane) {
    }

    public record RegionPerformanceSnapshot(
        RegionKey region,
        RegionStatsSnapshot stats,
        RegionTickReportData tickReport15s,
        long[] taskTimesNanos,
        int sampleCount,
        long queuedTasks,
        long oldestQueuedTaskAgeNanos,
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
        long reportWindowNanos
    ) {
        public static final RegionTickReportData EMPTY = new RegionTickReportData(0, 0L, 0.0D, MIN_REPORT_WINDOW_NANOS);

        public double utilisation() {
            return this.reportWindowNanos <= 0L ? 0.0D : (double)this.totalTickTimeNanos / (double)this.reportWindowNanos;
        }

        public double tps(double maxTps) {
            if (this.sampleCount == 0 || this.reportWindowNanos <= 0L) {
                return maxTps;
            }

            double targetTickNanos = (double)TimeUnit.SECONDS.toNanos(1L) / maxTps;
            if (this.averageTickTimeNanos <= targetTickNanos && this.utilisation() <= 1.0D) {
                return maxTps;
            }

            double seconds = (double)this.reportWindowNanos / (double)TimeUnit.SECONDS.toNanos(1L);
            double sampleRateTps = (double)this.sampleCount / seconds;
            double processingRateTps = this.averageTickTimeNanos <= 0.0D
                ? maxTps
                : (double)TimeUnit.SECONDS.toNanos(1L) / this.averageTickTimeNanos;
            return Math.min(maxTps, Math.min(sampleRateTps, processingRateTps));
        }
    }

    private static final class RegionTiming {
        private final LaneTiming aggregateTiming = new LaneTiming();
        private final Map<String, LaneTiming> laneTimings = new LinkedHashMap<>();
        private long completedTasks;
        private long failedTasks;
        private long lastCompletionNanos;

        private synchronized void begin(String lane, long tickId, long startNanos) {
            this.aggregateTiming.begin(tickId, startNanos);
            this.laneTimings.computeIfAbsent(lane, unused -> new LaneTiming()).begin(tickId, startNanos);
        }

        private synchronized void record(String lane, long tickId, long elapsedNanos, boolean successful) {
            long nowNanos = System.nanoTime();
            this.aggregateTiming.record(tickId, elapsedNanos, nowNanos);
            this.laneTimings.computeIfAbsent(lane, unused -> new LaneTiming()).record(tickId, elapsedNanos, nowNanos);
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
            long oldestQueuedTaskAgeNanos
        ) {
            return new RegionPerformanceSnapshot(
                region,
                stats,
                RegionTickReportData.EMPTY,
                new long[0],
                0,
                queuedTasks,
                oldestQueuedTaskAgeNanos,
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
            long nowNanos
        ) {
            LaneTiming reportTiming = this.reportTiming(stats);
            long[] samples = reportTiming.taskSamples();

            return new RegionPerformanceSnapshot(
                region,
                stats,
                reportTiming.tickReport(nowNanos),
                samples,
                samples.length,
                queuedTasks,
                oldestQueuedTaskAgeNanos,
                this.completedTasks,
                this.failedTasks,
                this.lastCompletionNanos
            );
        }

        private LaneTiming reportTiming(RegionStatsSnapshot stats) {
            if (stats.entities() > 0) {
                LaneTiming entityTiming = this.laneTimings.get("entity-ticks");
                if (entityTiming != null && entityTiming.hasSamples()) {
                    return entityTiming;
                }
            }

            LaneTiming chunkTiming = this.laneTimings.get("chunk-ticks");
            if (chunkTiming != null && chunkTiming.hasSamples()) {
                return chunkTiming;
            }

            return this.aggregateTiming;
        }
    }

    private static final class LaneTiming {
        private final long[] taskTimesNanos = new long[REGION_TIMING_WINDOW];
        private final long[] tickTimesNanos = new long[REGION_TICK_REPORT_WINDOW];
        private final long[] tickCompletionNanos = new long[REGION_TICK_REPORT_WINDOW];
        private int nextIndex;
        private int nextTickIndex;
        private long recordedTasks;
        private long currentTickId = Long.MIN_VALUE;
        private long currentTickTimeNanos;
        private long currentTickCompletionNanos;
        private long activeTickId = Long.MIN_VALUE;
        private long activeStartNanos;

        private void begin(long tickId, long startNanos) {
            if (this.currentTickId != tickId) {
                this.flushCurrentTick();
                this.currentTickId = tickId;
            }

            this.activeTickId = tickId;
            this.activeStartNanos = startNanos;
        }

        private void record(long tickId, long elapsedNanos, long nowNanos) {
            if (this.currentTickId != tickId) {
                this.flushCurrentTick();
                this.currentTickId = tickId;
            }

            this.currentTickTimeNanos += Math.max(1L, elapsedNanos);
            this.currentTickCompletionNanos = nowNanos;
            this.taskTimesNanos[this.nextIndex] = Math.max(1L, elapsedNanos);
            this.nextIndex = (this.nextIndex + 1) % this.taskTimesNanos.length;
            this.recordedTasks++;
            if (this.activeTickId == tickId) {
                this.activeTickId = Long.MIN_VALUE;
                this.activeStartNanos = 0L;
            }
        }

        private boolean hasSamples() {
            return this.recordedTasks > 0L || this.currentTickTimeNanos > 0L;
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

        private void flushCurrentTick() {
            if (this.currentTickTimeNanos <= 0L) {
                return;
            }

            this.tickTimesNanos[this.nextTickIndex] = this.currentTickTimeNanos;
            this.tickCompletionNanos[this.nextTickIndex] = this.currentTickCompletionNanos;
            this.nextTickIndex = (this.nextTickIndex + 1) % this.tickTimesNanos.length;
            this.currentTickTimeNanos = 0L;
            this.currentTickCompletionNanos = 0L;
        }

        private RegionTickReportData tickReport(long nowNanos) {
            long oldestIncludedNanos = nowNanos - REGION_TICK_REPORT_WINDOW_NANOS;
            int sampleCount = 0;
            long totalTickTimeNanos = 0L;
            long firstSampleNanos = Long.MAX_VALUE;

            for (int i = 0; i < this.tickTimesNanos.length; i++) {
                long tickTimeNanos = this.tickTimesNanos[i];
                long completionNanos = this.tickCompletionNanos[i];
                if (tickTimeNanos <= 0L || completionNanos < oldestIncludedNanos) {
                    continue;
                }

                sampleCount++;
                totalTickTimeNanos += tickTimeNanos;
                firstSampleNanos = Math.min(firstSampleNanos, completionNanos);
            }

            long activeTickTimeNanos = this.activeStartNanos > 0L ? Math.max(1L, nowNanos - this.activeStartNanos) : 0L;
            long liveCurrentTickTimeNanos = this.currentTickTimeNanos + activeTickTimeNanos;
            long liveCurrentCompletionNanos = activeTickTimeNanos > 0L ? nowNanos : this.currentTickCompletionNanos;
            if (liveCurrentTickTimeNanos > 0L && liveCurrentCompletionNanos >= oldestIncludedNanos) {
                sampleCount++;
                totalTickTimeNanos += liveCurrentTickTimeNanos;
                firstSampleNanos = Math.min(firstSampleNanos, liveCurrentCompletionNanos);
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
                reportWindowNanos
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

    private record RunnableGlobalTask(Runnable task) implements GlobalTask {
        private RunnableGlobalTask {
            Objects.requireNonNull(task, "task");
        }

        @Override
        public void run() {
            this.task.run();
        }
    }

    private record FutureGlobalTask<T>(Supplier<T> task, CompletableFuture<T> future) implements GlobalTask {
        private FutureGlobalTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(future, "future");
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
            try {
                RegionTask task;
                while (RegionizedTaskDispatcher.this.running.get() && (task = this.tasks.poll()) != null) {
                    RegionTask currentTask = task;
                    RegionizedTickContext.runInRegion(this.region, this.random, () -> {
                        long startNanos = System.nanoTime();
                        RegionizedTaskDispatcher.this.beginRegionTask(this.region, currentTask.lane(), currentTask.tickId(), startNanos);
                        boolean successful = runSafely("region task " + this.region, currentTask::run);
                        RegionizedTaskDispatcher.this.recordRegionTask(
                            this.region,
                            currentTask.lane(),
                            currentTask.tickId(),
                            System.nanoTime() - startNanos,
                            successful
                        );
                        if (successful) {
                            RegionizedTaskDispatcher.this.completedRegionTasks.incrementAndGet();
                        } else {
                            RegionizedTaskDispatcher.this.failedRegionTasks.incrementAndGet();
                        }
                    });
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
