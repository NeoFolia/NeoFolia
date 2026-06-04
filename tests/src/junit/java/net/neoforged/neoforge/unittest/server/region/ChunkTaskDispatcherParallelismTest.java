/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.server.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ChunkTaskDispatcherParallelismTest {
    @Test
    void runsDifferentChunksInParallelWhenCapacityAllows() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ChunkTaskDispatcher dispatcher = dispatcher(executor, 2)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondRan = new CountDownLatch(1);

            dispatcher.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, ChunkPos.pack(0, 0), () -> 0);

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            dispatcher.submit(secondRan::countDown, ChunkPos.pack(1, 0), () -> 0);

            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
            releaseFirst.countDown();
            awaitIdle(dispatcher);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void keepsTasksForSameChunkSerialEvenWithParallelCapacity() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ChunkTaskDispatcher dispatcher = dispatcher(executor, 2)) {
            long chunk = ChunkPos.pack(0, 0);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondRan = new CountDownLatch(1);

            dispatcher.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, chunk, () -> 0);

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            dispatcher.submit(secondRan::countDown, chunk, () -> 0);

            assertFalse(secondRan.await(250, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
            awaitIdle(dispatcher);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void prefersAnotherRegionWhenCurrentRegionAlreadyUsesItsShare() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ChunkTaskDispatcher dispatcher = regionFairDispatcher(executor, 2)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch sameRegionRan = new CountDownLatch(1);
            CountDownLatch otherRegionRan = new CountDownLatch(1);

            dispatcher.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, ChunkPos.pack(0, 0), () -> 0);

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            dispatcher.submit(sameRegionRan::countDown, ChunkPos.pack(1, 0), () -> 0);
            dispatcher.submit(otherRegionRan::countDown, ChunkPos.pack(16, 0), () -> 0);

            assertTrue(otherRegionRan.await(2, TimeUnit.SECONDS));
            assertFalse(sameRegionRan.await(250, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(sameRegionRan.await(2, TimeUnit.SECONDS));
            awaitIdle(dispatcher);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void throttledDispatcherKeepsRegionShareUntilChunkRelease() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ThrottlingChunkTaskDispatcher dispatcher = throttledRegionFairDispatcher(executor, 2)) {
            long firstChunk = ChunkPos.pack(0, 0);
            long sameRegionChunk = ChunkPos.pack(1, 0);
            long otherRegionChunk = ChunkPos.pack(16, 0);
            CountDownLatch firstRan = new CountDownLatch(1);
            CountDownLatch sameRegionRan = new CountDownLatch(1);
            CountDownLatch otherRegionRan = new CountDownLatch(1);

            dispatcher.submit(firstRan::countDown, firstChunk, () -> 0);

            assertTrue(firstRan.await(2, TimeUnit.SECONDS));
            assertTrue(awaitChunksInExecution(dispatcher, 1));

            dispatcher.submit(sameRegionRan::countDown, sameRegionChunk, () -> 0);
            dispatcher.submit(otherRegionRan::countDown, otherRegionChunk, () -> 0);

            assertTrue(otherRegionRan.await(2, TimeUnit.SECONDS));
            assertFalse(sameRegionRan.await(250, TimeUnit.MILLISECONDS));

            dispatcher.release(firstChunk, () -> {}, false);
            assertTrue(sameRegionRan.await(2, TimeUnit.SECONDS));

            dispatcher.release(otherRegionChunk, () -> {}, false);
            dispatcher.release(sameRegionChunk, () -> {}, false);
            awaitIdle(dispatcher);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ChunkTaskDispatcher dispatcher(ExecutorService executor, int maxParallelChunks) {
        return new ChunkTaskDispatcher(TaskScheduler.wrapExecutor("chunk-dispatcher-test", executor), executor, maxParallelChunks);
    }

    private static ChunkTaskDispatcher regionFairDispatcher(ExecutorService executor, int maxParallelChunks) {
        return new ChunkTaskDispatcher(
            TaskScheduler.wrapExecutor("chunk-dispatcher-test", executor),
            executor,
            maxParallelChunks,
            chunkPos -> ChunkPos.unpack(chunkPos).x() >> 4
        );
    }

    private static ThrottlingChunkTaskDispatcher throttledRegionFairDispatcher(ExecutorService executor, int maxParallelChunks) {
        return new ThrottlingChunkTaskDispatcher(
            TaskScheduler.wrapExecutor("chunk-dispatcher-test", executor),
            executor,
            maxParallelChunks,
            chunkPos -> ChunkPos.unpack(chunkPos).x() >> 4
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void awaitIdle(ChunkTaskDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dispatcher.hasWork() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        assertFalse(dispatcher.hasWork());
    }

    private static boolean awaitChunksInExecution(ThrottlingChunkTaskDispatcher dispatcher, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dispatcher.chunksInExecution() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        return dispatcher.chunksInExecution() == expected;
    }
}
