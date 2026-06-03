/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.server.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedTaskDispatcher;
import net.neoforged.neoforge.server.region.RegionizedTickContext;
import org.junit.jupiter.api.Test;

class RegionizedTaskDispatcherTest {
    private static final RegionKey REGION_A = new RegionKey(Level.OVERWORLD, 0, 0);
    private static final RegionKey REGION_B = new RegionKey(Level.OVERWORLD, 1, 0);

    @Test
    void runsTaskWithRegionContext() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            RegionKey observed = dispatcher.submit(REGION_A, () -> RegionizedTickContext.currentRegion().orElseThrow()).join();

            assertEquals(REGION_A, observed);
        }
    }

    @Test
    void preservesOrderWithinSingleRegion() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            ConcurrentLinkedQueue<Integer> order = new ConcurrentLinkedQueue<>();

            CompletableFuture<Void> first = dispatcher.submit(REGION_A, () -> {
                order.add(1);
            });
            CompletableFuture<Void> second = dispatcher.submit(REGION_A, () -> {
                order.add(2);
            });
            CompletableFuture.allOf(first, second).join();

            assertEquals(1, order.poll());
            assertEquals(2, order.poll());
            assertTrue(order.isEmpty());
        }
    }

    @Test
    void runsNestedSubmitForCurrentRegionInline() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            RegionKey observed = dispatcher.submit(REGION_A, () -> dispatcher.submit(REGION_A, () -> {
                assertEquals(0, dispatcher.queuedRegionTaskCount());
                return RegionizedTickContext.currentRegion().orElseThrow();
            }).join()).join();

            assertEquals(REGION_A, observed);
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.queuedRegionTaskCount());
        }
    }

    @Test
    void rejectsBlockingAwaitForOtherRegionFromWorker() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            Map<RegionKey, Runnable> tasks = new LinkedHashMap<>();
            tasks.put(REGION_B, () -> {
            });

            CompletableFuture<Void> future = dispatcher.submit(REGION_A, () -> dispatcher.awaitAll("test await", tasks));
            CompletionException thrown = assertThrows(CompletionException.class, future::join);

            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    void allowsBlockingAwaitForCurrentRegionFromWorker() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            ConcurrentLinkedQueue<RegionKey> observed = new ConcurrentLinkedQueue<>();
            Map<RegionKey, Runnable> tasks = new LinkedHashMap<>();
            tasks.put(REGION_A, () -> observed.add(RegionizedTickContext.currentRegion().orElseThrow()));

            dispatcher.submit(REGION_A, () -> dispatcher.awaitAll("same region await", tasks)).join();

            assertEquals(REGION_A, observed.poll());
            assertTrue(observed.isEmpty());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 1);
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.queuedRegionTaskCount());
        }
    }

    @Test
    void reservesMultipleRegionsForPrimaryRegionTask() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            AtomicInteger runs = new AtomicInteger();
            dispatcher.awaitRegions("multi region action", List.of(REGION_A, REGION_B), REGION_A, () -> {
                assertEquals(REGION_A, RegionizedTickContext.currentRegion().orElseThrow());
                runs.incrementAndGet();
            });

            assertEquals(1, runs.get());
            assertEquals(2, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 2);
            assertEquals(2, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void allowsSameRegionReservationFromRegionWorker() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            AtomicInteger runs = new AtomicInteger();

            dispatcher.submit(
                REGION_A,
                () -> dispatcher.awaitRegions("same region action", List.of(REGION_A, REGION_A), REGION_A, () -> {
                    assertEquals(REGION_A, RegionizedTickContext.currentRegion().orElseThrow());
                    runs.incrementAndGet();
                })
            ).join();

            assertEquals(1, runs.get());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 1);
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void rejectsMultiRegionReservationFromRegionWorker() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            CompletableFuture<Void> future = dispatcher.submit(
                REGION_A,
                () -> dispatcher.awaitRegions("multi region action", List.of(REGION_A, REGION_B), REGION_A, () -> {
                })
            );
            CompletionException thrown = assertThrows(CompletionException.class, future::join);

            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    void serializesMultiRegionReservationWithSingleWorker() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            AtomicInteger runs = new AtomicInteger();

            dispatcher.awaitRegions("multi region action", List.of(REGION_A, REGION_B), REGION_A, () -> {
                assertEquals(REGION_A, RegionizedTickContext.currentRegion().orElseThrow());
                runs.incrementAndGet();
            });

            assertEquals(1, runs.get());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 1);
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void rejectsMultiRegionReservationWithoutEnoughParallelWorkers() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            assertThrows(
                IllegalStateException.class,
                () -> dispatcher.awaitRegions("multi region action", List.of(
                    REGION_A,
                    REGION_B,
                    new RegionKey(Level.OVERWORLD, 2, 0)
                ), REGION_A, () -> {
                })
            );
        }
    }

    @Test
    void pausesMultipleRegionsForCallerThreadTask() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            AtomicInteger runs = new AtomicInteger();
            dispatcher.awaitRegionsPaused("multi region pause", List.of(REGION_A, REGION_B), () -> {
                assertFalse(RegionizedTickContext.isRegionThread());
                assertTrue(dispatcher.queuedRegionTaskCount() >= 0);
                runs.incrementAndGet();
            });

            assertEquals(1, runs.get());
            assertEquals(2, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 2);
            assertEquals(2, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void allowsSameRegionPausedReservationFromRegionWorker() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            AtomicInteger runs = new AtomicInteger();

            dispatcher.submit(
                REGION_A,
                () -> dispatcher.awaitRegionsPaused("same region pause", List.of(REGION_A, REGION_A), () -> {
                    assertEquals(REGION_A, RegionizedTickContext.currentRegion().orElseThrow());
                    runs.incrementAndGet();
                })
            ).join();

            assertEquals(1, runs.get());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 1);
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void rejectsPausedMultiRegionReservationFromRegionWorker() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            CompletableFuture<Void> future = dispatcher.submit(
                REGION_A,
                () -> dispatcher.awaitRegionsPaused("multi region pause", List.of(REGION_A, REGION_B), () -> {
                })
            );
            CompletionException thrown = assertThrows(CompletionException.class, future::join);

            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    void serializesPausedMultiRegionReservationWithSingleWorker() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            AtomicInteger runs = new AtomicInteger();

            dispatcher.awaitRegionsPaused("multi region pause", List.of(REGION_A, REGION_B), () -> {
                assertFalse(RegionizedTickContext.isRegionThread());
                runs.incrementAndGet();
            });

            assertEquals(1, runs.get());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 1);
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void releasesPausedRegionsWhenCallerTaskFails() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> dispatcher.awaitRegionsPaused("failing multi region pause", List.of(REGION_A, REGION_B), () -> {
                    throw new RuntimeException("boom");
                })
            );

            assertEquals("boom", thrown.getMessage());
            assertEquals(2, dispatcher.submittedRegionTaskCount());
            awaitCompletedRegionTasks(dispatcher, 2);
            assertEquals(2, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
        }
    }

    @Test
    void drainsGlobalTasksOutsideRegionContext() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
            dispatcher.executeGlobal(() -> order.add("first:" + RegionizedTickContext.isRegionThread()));
            dispatcher.executeGlobal(() -> order.add("second:" + RegionizedTickContext.isRegionThread()));

            assertEquals(2, dispatcher.drainGlobalTasks());
            assertEquals("first:false", order.poll());
            assertEquals("second:false", order.poll());
            assertTrue(order.isEmpty());
        }
    }

    @Test
    void submitsGlobalTasksAsFutureOutsideRegionContext() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            CompletableFuture<String> future = dispatcher.submitGlobal(() -> "global:" + RegionizedTickContext.isRegionThread());

            assertFalse(future.isDone());
            assertEquals(1, dispatcher.drainGlobalTasks());
            assertEquals("global:false", future.join());
            assertEquals(1, dispatcher.submittedGlobalTaskCount());
            assertEquals(1, dispatcher.completedGlobalTaskCount());
        }
    }

    @Test
    void regionWorkerCanSubmitGlobalFutureWithoutRunningItInline() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            CompletableFuture<String> future = dispatcher.submit(REGION_A, () -> dispatcher.submitGlobal(() -> "global:" + RegionizedTickContext.isRegionThread())).join();

            assertFalse(future.isDone());
            assertEquals(1, dispatcher.drainGlobalTasks());
            assertEquals("global:false", future.join());
            assertEquals(1, dispatcher.submittedRegionTaskCount());
            assertEquals(1, dispatcher.completedRegionTaskCount());
            assertEquals(1, dispatcher.submittedGlobalTaskCount());
            assertEquals(1, dispatcher.completedGlobalTaskCount());
        }
    }

    @Test
    void submitGlobalCompletesFutureExceptionallyWhenTaskFails() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            CompletableFuture<Void> future = dispatcher.submitGlobal(() -> {
                throw new IllegalStateException("boom");
            });

            assertFalse(future.isDone());
            assertEquals(1, dispatcher.drainGlobalTasks());
            CompletionException thrown = assertThrows(CompletionException.class, future::join);
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertEquals(1, dispatcher.failedGlobalTaskCount());
            assertEquals(0, dispatcher.completedGlobalTaskCount());
        }
    }

    @Test
    void reportsQueuedAndCompletedTaskCounts() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            CompletableFuture<Void> first = dispatcher.submit(REGION_A, () -> {
                firstStarted.countDown();
                try {
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> second = dispatcher.submit(REGION_A, () -> {
            });
            dispatcher.executeGlobal(() -> {
            });

            assertEquals(2, dispatcher.submittedRegionTaskCount());
            assertEquals(1, dispatcher.queuedRegionTaskCount());
            assertEquals(1, dispatcher.queuedGlobalTaskCount());
            assertTrue(dispatcher.queuedRegionCount() >= 1);
            Thread.sleep(1);
            RegionizedTaskDispatcher.RegionPerformanceSnapshot queuedSnapshot = dispatcher.regionPerformanceSnapshots()
                    .stream()
                    .filter(snapshot -> REGION_A.equals(snapshot.region()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, queuedSnapshot.queuedTasks());
            assertTrue(queuedSnapshot.oldestQueuedTaskAgeNanos() > 0L);

            releaseFirst.countDown();
            CompletableFuture.allOf(first, second).join();
            awaitCompletedRegionTasks(dispatcher, 2);
            assertEquals(1, dispatcher.drainGlobalTasks());

            assertEquals(2, dispatcher.completedRegionTaskCount());
            assertEquals(0, dispatcher.failedRegionTaskCount());
            assertEquals(0, dispatcher.cancelledRegionTaskCount());
            assertEquals(1, dispatcher.submittedGlobalTaskCount());
            assertEquals(1, dispatcher.completedGlobalTaskCount());
            assertEquals(0, dispatcher.failedGlobalTaskCount());
            assertEquals(0, dispatcher.cancelledGlobalTaskCount());
        }
    }

    @Test
    void recordsRegionPerformanceSnapshots() throws Exception {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(2)) {
            CompletableFuture<Void> first = dispatcher.submit(REGION_A, () -> {
            });
            CompletableFuture<Void> second = dispatcher.submit(REGION_B, () -> {
            });
            CompletableFuture.allOf(first, second).join();
            awaitCompletedRegionTasks(dispatcher, 2);

            List<RegionizedTaskDispatcher.RegionPerformanceSnapshot> snapshots = dispatcher.regionPerformanceSnapshots();
            assertEquals(2, snapshots.size());

            RegionizedTaskDispatcher.RegionPerformanceSnapshot snapshot = snapshots.stream()
                    .filter(candidate -> REGION_A.equals(candidate.region()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, snapshot.completedTasks());
            assertEquals(0, snapshot.failedTasks());
            assertEquals(0, snapshot.queuedTasks());
            assertEquals(1, snapshot.sampleCount());
            assertTrue(snapshot.averageTaskTimeNanos() > 0.0D);
            assertTrue(snapshot.lastCompletionNanos() > 0L);
        }
    }

    @Test
    void reportsFailedTaskCounts() {
        try (RegionizedTaskDispatcher dispatcher = startedDispatcher(1)) {
            CompletableFuture<Void> regionFailure = dispatcher.submit(REGION_A, () -> {
                throw new IllegalStateException("boom");
            });

            assertThrows(CompletionException.class, regionFailure::join);
            dispatcher.executeGlobal(() -> {
                throw new IllegalStateException("boom");
            });
            assertEquals(1, dispatcher.drainGlobalTasks());

            assertEquals(1, dispatcher.failedRegionTaskCount());
            assertEquals(1, dispatcher.failedGlobalTaskCount());
        }
    }

    @Test
    void closeStopsAndRejectsNewTasks() {
        RegionizedTaskDispatcher dispatcher = startedDispatcher(1);
        dispatcher.executeGlobal(() -> {
        });

        dispatcher.close();

        assertFalse(dispatcher.isRunning());
        assertEquals(0, dispatcher.queuedGlobalTaskCount());
        assertEquals(0, dispatcher.queuedRegionTaskCount());
        assertEquals(1, dispatcher.cancelledGlobalTaskCount());
        assertFalse(dispatcher.tryExecute(REGION_A, () -> {
        }));
        assertFalse(dispatcher.tryExecuteGlobal(() -> {
        }));
        assertThrows(IllegalStateException.class, () -> dispatcher.execute(REGION_A, () -> {
        }));
        assertThrows(IllegalStateException.class, () -> dispatcher.executeGlobal(() -> {
        }));
        assertThrows(CompletionException.class, () -> dispatcher.submit(REGION_A, () -> {
        }).join());
        assertThrows(CompletionException.class, () -> dispatcher.submitGlobal(() -> {
        }).join());
    }

    @Test
    void closeCompletesQueuedSubmittedTasksExceptionally() throws Exception {
        RegionizedTaskDispatcher dispatcher = startedDispatcher(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = dispatcher.submit(REGION_A, () -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> queued = dispatcher.submit(REGION_A, () -> {
        });

        dispatcher.close();
        releaseFirst.countDown();

        assertThrows(CompletionException.class, queued::join);
        assertEquals(1, dispatcher.cancelledRegionTaskCount());
        first.join();
    }

    @Test
    void closeCompletesQueuedGlobalSubmittedTasksExceptionally() {
        RegionizedTaskDispatcher dispatcher = startedDispatcher(1);
        CompletableFuture<Void> queued = dispatcher.submitGlobal(() -> {
        });

        dispatcher.close();

        assertThrows(CompletionException.class, queued::join);
        assertEquals(1, dispatcher.cancelledGlobalTaskCount());
    }

    @Test
    void closeDoesNotRunQueuedExecuteTasks() throws Exception {
        RegionizedTaskDispatcher dispatcher = startedDispatcher(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger queuedRuns = new AtomicInteger();

        CompletableFuture<Void> first = dispatcher.submit(REGION_A, () -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        dispatcher.execute(REGION_A, queuedRuns::incrementAndGet);

        dispatcher.close();
        releaseFirst.countDown();

        first.join();
        assertEquals(0, queuedRuns.get());
        assertEquals(1, dispatcher.cancelledRegionTaskCount());
    }

    private static RegionizedTaskDispatcher startedDispatcher(int threadCount) {
        RegionizedTaskDispatcher dispatcher = new RegionizedTaskDispatcher();
        dispatcher.start(threadCount);
        return dispatcher;
    }

    private static void awaitCompletedRegionTasks(RegionizedTaskDispatcher dispatcher, long expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.completedRegionTaskCount() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
