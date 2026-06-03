/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Shared ownership rules for the first NeoFolia region scheduler layer.
 */
public final class RegionizedWorldGuard {
    public static final int DEFAULT_REGION_CHUNK_SHIFT = 4;

    private RegionizedWorldGuard() {
    }

    public static RegionKey regionFor(ServerLevel level, int chunkX, int chunkZ) {
        return RegionKey.fromChunk(level.dimension(), chunkX, chunkZ, DEFAULT_REGION_CHUNK_SHIFT);
    }

    public static RegionKey regionFor(ServerLevel level, ChunkPos pos) {
        return regionFor(level, pos.x(), pos.z());
    }

    public static RegionKey regionFor(ServerLevel level, BlockPos pos) {
        return RegionKey.fromBlock(level.dimension(), pos, DEFAULT_REGION_CHUNK_SHIFT);
    }

    public static boolean isRegionThreadFor(ServerLevel level, int chunkX, int chunkZ) {
        RegionKey expected = regionFor(level, chunkX, chunkZ);
        return RegionizedTickContext.currentRegion().map(expected::equals).orElse(false);
    }

    public static boolean isRegionThreadFor(Level level, int chunkX, int chunkZ) {
        return level instanceof ServerLevel serverLevel && isRegionThreadFor(serverLevel, chunkX, chunkZ);
    }

    public static boolean isRegionThreadFor(ServerLevel level, ChunkPos pos) {
        return isRegionThreadFor(level, pos.x(), pos.z());
    }

    public static boolean isRegionThreadFor(ServerLevel level, BlockPos pos) {
        return isRegionThreadFor(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isRegionThreadFor(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel && isRegionThreadFor(serverLevel, pos);
    }

    public static boolean shouldLoadChunk(Level level, int chunkX, int chunkZ) {
        return !RegionizedTickContext.isRegionThread() || isRegionThreadFor(level, chunkX, chunkZ);
    }

    public static @Nullable AABB restrictToCurrentRegion(Level level, AABB box) {
        if (!RegionizedTickContext.isRegionThread()) {
            return box;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("Region entity query must not run from a region worker in non-server level " + level.dimension());
        }

        RegionKey region = RegionizedTickContext.currentRegion().orElse(null);
        if (region == null || !region.level().equals(serverLevel.dimension())) {
            return null;
        }

        int minRegionBlockX = (region.regionX() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int minRegionBlockZ = (region.regionZ() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int maxRegionBlockX = ((region.regionX() + 1) << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int maxRegionBlockZ = ((region.regionZ() + 1) << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        double minX = Math.max(box.minX, minRegionBlockX);
        double maxX = Math.min(box.maxX, maxRegionBlockX);
        double minZ = Math.max(box.minZ, minRegionBlockZ);
        double maxZ = Math.min(box.maxZ, maxRegionBlockZ);
        if (minX >= maxX || minZ >= maxZ) {
            return null;
        }

        return new AABB(minX, box.minY, minZ, maxX, box.maxY, maxZ);
    }

    public static <T extends Entity> @Nullable T restrictEntityToCurrentRegion(Level level, @Nullable T entity) {
        if (entity == null || !RegionizedTickContext.isRegionThread()) {
            return entity;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("Region entity access must not run from a region worker in non-server level " + level.dimension());
        }

        if (entity.level() != level || !isRegionThreadFor(serverLevel, entity.blockPosition())) {
            return null;
        }

        return entity;
    }

    public static boolean scheduleEntityIfRegionizedOutsideRegion(Level level, Entity entity, Runnable task) {
        return scheduleEntityIfRegionizedOutsideRegion(level, entity, "Entity add", task);
    }

    public static boolean scheduleEntityIfRegionizedOutsideRegion(Level level, Entity entity, String reason, Runnable task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
        }

        BlockPos pos = entity.blockPosition();
        if (isRegionThreadFor(serverLevel, pos)) {
            return false;
        }

        return scheduleEntityOnOwningRegion(serverLevel, entity, reason, task);
    }

    public static boolean scheduleEntityIfRegionized(Level level, Entity entity, String reason, Runnable task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!RegionizedServerSettings.regionizedEntityTicksEnabled()) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            if (RegionizedTickContext.isRegionThread()) {
                throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
            }

            return false;
        }

        BlockPos pos = entity.blockPosition();
        if (isRegionThreadFor(serverLevel, pos)) {
            return false;
        }

        return scheduleEntityOnOwningRegion(serverLevel, entity, reason, task);
    }

    private static boolean scheduleEntityOnOwningRegion(ServerLevel level, Entity entity, String reason, Runnable task) {
        RegionKey region = regionFor(level, entity.blockPosition());
        if (RegionizedTickContext.currentRegion().map(region::equals).orElse(false)) {
            task.run();
            return true;
        }

        return level.getServer().regionizedTaskDispatcher().tryExecute(region, () -> {
            RegionKey currentRegion = regionFor(level, entity.blockPosition());
            if (!RegionizedTickContext.currentRegion().map(currentRegion::equals).orElse(false)) {
                scheduleEntityOnOwningRegion(level, entity, reason, task);
                return;
            }

            task.run();
        });
    }

    public static boolean scheduleGlobalIfRegionizedOutsideRegion(Level level, BlockPos pos, String reason, Runnable task) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
        }

        if (isRegionThreadFor(serverLevel, pos)) {
            return false;
        }

        return serverLevel.getServer().regionizedTaskDispatcher().tryExecuteGlobal(task);
    }

    public static boolean scheduleAtRegionIfRegionized(Level level, BlockPos pos, String reason, Runnable task) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!(level instanceof ServerLevel serverLevel)) {
            if (RegionizedTickContext.isRegionThread()) {
                throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
            }

            return false;
        }

        if (!serverLevel.getServer().regionizedTaskDispatcher().isRunning()) {
            return false;
        }

        if (isRegionThreadFor(serverLevel, pos)) {
            return false;
        }

        return serverLevel.getServer().regionizedTaskDispatcher().tryExecute(regionFor(serverLevel, pos), () -> {
            if (!isRegionThreadFor(serverLevel, pos)) {
                scheduleAtRegionIfRegionized(serverLevel, pos, reason, task);
                return;
            }

            task.run();
        });
    }

    public static boolean scheduleAtRegionIfRegionizedOutsideRegion(Level level, BlockPos pos, String reason, Runnable task) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        return scheduleAtRegionIfRegionized(level, pos, reason, task);
    }

    public static boolean scheduleGlobalIfRegionized(Level level, String reason, Runnable task) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
        }

        return serverLevel.getServer().regionizedTaskDispatcher().tryExecuteGlobal(task);
    }

    public static void ensureRegionThreadFor(ServerLevel level, int chunkX, int chunkZ, String reason) {
        if (!isRegionThreadFor(level, chunkX, chunkZ)) {
            RegionKey expected = regionFor(level, chunkX, chunkZ);
            String current = RegionizedTickContext.currentRegion().map(RegionKey::toString).orElse("server thread/no region");
            throw new IllegalStateException(reason + " must run on " + expected + ", currently on " + current);
        }
    }

    public static void ensureRegionThreadFor(ServerLevel level, ChunkPos pos, String reason) {
        ensureRegionThreadFor(level, pos.x(), pos.z(), reason);
    }

    public static void ensureRegionThreadFor(ServerLevel level, BlockPos pos, String reason) {
        ensureRegionThreadFor(level, pos.getX() >> 4, pos.getZ() >> 4, reason);
    }

    public static void ensureRegionThreadIfRegionized(Level level, BlockPos pos, String reason) {
        if (!RegionizedTickContext.isRegionThread()) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + level.dimension());
        }

        ensureRegionThreadFor(serverLevel, pos, reason);
    }

    public static void ensureChunkLoadIfRegionized(Level level, int chunkX, int chunkZ, String reason) {
        if (!RegionizedTickContext.isRegionThread()) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException(reason + " must not load chunks from a region worker in non-server level " + level.dimension());
        }

        ensureRegionThreadFor(serverLevel, chunkX, chunkZ, reason);
    }

    public static boolean scheduleIfRegionizedOutsideRegion(Level level, BlockPos pos, Runnable task) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(task, "task");
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        return scheduleAtRegionIfRegionized(level, pos, "Cross-region task", task);
    }

    public static void executeOnPlayerRegion(ServerPlayer player, Runnable task) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        MinecraftServer server = player.level().getServer();
        if (server != null && server.regionizedTaskDispatcher().isRunning()) {
            if (!executeOnPlayerRegion(server, player, task)) {
                if (RegionizedTickContext.isRegionThread() && !isOnPlayerRegion(player)) {
                    throw new IllegalStateException("Cannot execute player task from another region worker after region dispatcher stopped");
                }

                task.run();
            }
        } else {
            if (RegionizedTickContext.isRegionThread() && !isOnPlayerRegion(player)) {
                throw new IllegalStateException("Cannot execute player task from another region worker while region dispatcher is not running");
            }

            task.run();
        }
    }

    public static boolean isOnPlayerRegion(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        RegionKey region = regionFor(player.level(), player.blockPosition());
        return RegionizedTickContext.currentRegion().map(region::equals).orElse(false);
    }

    public static void executeOnPlayerRegionAndWait(ServerPlayer player, String reason, Runnable task) {
        Objects.requireNonNull(task, "task");
        callOnPlayerRegionAndWait(player, reason, () -> {
            task.run();
            return Boolean.TRUE;
        });
    }

    public static <T> T callOnPlayerRegionAndWait(ServerPlayer player, String reason, Supplier<T> task) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        MinecraftServer server = player.level().getServer();
        if (server == null || !server.regionizedTaskDispatcher().isRunning() || isOnPlayerRegion(player)) {
            return task.get();
        }

        if (RegionizedTickContext.isRegionThread()) {
            throw new IllegalStateException("Cannot wait for " + reason + " from another region worker");
        }

        long deadlineNanos = regionWaitDeadlineNanos();
        boolean completed = false;
        T result = null;
        while (!completed) {
            ensureRegionWaitDeadline(reason, deadlineNanos);
            RegionKey region = regionFor(player.level(), player.blockPosition());
            CompletableFuture<RegionCallResult<T>> future = server.regionizedTaskDispatcher().submit(region, () -> {
                RegionKey currentRegion = regionFor(player.level(), player.blockPosition());
                if (!RegionizedTickContext.currentRegion().map(currentRegion::equals).orElse(false)) {
                    return new RegionCallResult<T>(false, null);
                }

                return new RegionCallResult<>(true, task.get());
            });
            RegionCallResult<T> holder = waitForRegionCall(reason, deadlineNanos, future);
            completed = holder.completed();
            result = holder.result();
        }

        return result;
    }

    public static <T> @Nullable T callOnEntityRegionAndWait(Entity entity, String reason, Supplier<@Nullable T> task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        if (!(entity.level() instanceof ServerLevel level)) {
            if (RegionizedTickContext.isRegionThread()) {
                throw new IllegalStateException(reason + " must not run from a region worker in non-server level " + entity.level().dimension());
            }

            return task.get();
        }

        MinecraftServer server = level.getServer();
        if (server == null || !server.regionizedTaskDispatcher().isRunning() || isRegionThreadFor(level, entity.blockPosition())) {
            return task.get();
        }

        if (RegionizedTickContext.isRegionThread()) {
            throw new IllegalStateException("Cannot wait for " + reason + " from another region worker");
        }

        long deadlineNanos = regionWaitDeadlineNanos();
        boolean completed = false;
        T result = null;
        while (!completed) {
            ensureRegionWaitDeadline(reason, deadlineNanos);
            RegionKey region = regionFor(level, entity.blockPosition());
            CompletableFuture<RegionCallResult<T>> future = server.regionizedTaskDispatcher().submit(region, () -> {
                RegionKey currentRegion = regionFor(level, entity.blockPosition());
                if (!RegionizedTickContext.currentRegion().map(currentRegion::equals).orElse(false)) {
                    return new RegionCallResult<T>(false, null);
                }

                return new RegionCallResult<>(true, task.get());
            });
            RegionCallResult<T> holder = waitForRegionCall(reason, deadlineNanos, future);
            completed = holder.completed();
            result = holder.result();
        }

        return result;
    }

    public static <T> @Nullable T callOnRegionAndWait(ServerLevel level, BlockPos pos, String reason, Supplier<@Nullable T> task) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(task, "task");
        MinecraftServer server = level.getServer();
        if (server == null || !server.regionizedTaskDispatcher().isRunning()) {
            return task.get();
        }

        RegionKey region = regionFor(level, pos);
        if (RegionizedTickContext.currentRegion().map(region::equals).orElse(false)) {
            return task.get();
        }

        if (RegionizedTickContext.isRegionThread()) {
            throw new IllegalStateException("Cannot wait for " + reason + " from another region worker");
        }

        long deadlineNanos = regionWaitDeadlineNanos();
        CompletableFuture<T> future = server.regionizedTaskDispatcher().submit(region, task);
        return waitForRegionFuture(reason, deadlineNanos, future);
    }

    private static boolean executeOnPlayerRegion(MinecraftServer server, ServerPlayer player, Runnable task) {
        if (isOnPlayerRegion(player)) {
            task.run();
            return true;
        }

        RegionKey region = regionFor(player.level(), player.blockPosition());
        return server.regionizedTaskDispatcher().tryExecute(region, () -> {
            RegionKey currentRegion = regionFor(player.level(), player.blockPosition());
            if (!RegionizedTickContext.currentRegion().map(currentRegion::equals).orElse(false)) {
                executeOnPlayerRegion(server, player, task);
                return;
            }

            task.run();
        });
    }

    private static long regionWaitDeadlineNanos() {
        int timeoutSeconds = RegionizedServerSettings.regionTaskTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return Long.MAX_VALUE;
        }

        return System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    private static void ensureRegionWaitDeadline(String reason, long deadlineNanos) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("Timed out waiting for " + reason + " to reach owning region");
        }
    }

    private static <T> RegionCallResult<T> waitForRegionCall(
        String reason,
        long deadlineNanos,
        CompletableFuture<RegionCallResult<T>> future
    ) {
        try {
            if (deadlineNanos == Long.MAX_VALUE) {
                return future.get();
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new TimeoutException();
            }

            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + reason + " to reach owning region", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Timed out waiting for " + reason + " to reach owning region", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed while waiting for " + reason + " to reach owning region", ex.getCause());
        }
    }

    private static <T> T waitForRegionFuture(String reason, long deadlineNanos, CompletableFuture<T> future) {
        try {
            if (deadlineNanos == Long.MAX_VALUE) {
                return future.get();
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new TimeoutException();
            }

            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + reason + " to reach owning region", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Timed out waiting for " + reason + " to reach owning region", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed while waiting for " + reason + " to reach owning region", ex.getCause());
        }
    }

    private record RegionCallResult<T>(boolean completed, @Nullable T result) {
    }

    public static void sendToPlayer(ServerPlayer player, Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        executeOnPlayerRegion(player, () -> player.connection.send(packet));
    }

    public static void sendToPlayer(ServerPlayer player, Packet<?> packet, @Nullable ChannelFutureListener listener) {
        Objects.requireNonNull(packet, "packet");
        executeOnPlayerRegion(player, () -> player.connection.send(packet, listener));
    }

    public static void sendPayloadToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Objects.requireNonNull(payload, "payload");
        sendToPlayer(player, new ClientboundCustomPayloadPacket(payload));
    }

    public static void awardStat(Player player, Stat<?> stat) {
        awardStat(player, stat, 1);
    }

    public static void awardStat(Player player, Identifier stat) {
        awardStat(player, Stats.CUSTOM.get(stat), 1);
    }

    public static void awardStat(Player player, Identifier stat, int amount) {
        awardStat(player, Stats.CUSTOM.get(stat), amount);
    }

    public static void awardStat(Player player, Stat<?> stat, int amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(stat, "stat");
        if (player instanceof ServerPlayer serverPlayer) {
            executeOnPlayerRegion(serverPlayer, () -> serverPlayer.awardStat(stat, amount));
        } else {
            player.awardStat(stat, amount);
        }
    }
}
