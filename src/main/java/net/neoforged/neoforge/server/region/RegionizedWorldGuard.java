/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.resources.ResourceKey;
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
    private static final int EMPTY_REGION_CREATE_RADIUS = 1;
    private static final int REGION_MERGE_RADIUS = 1;
    private static final int PLAYER_REGION_MERGE_SEARCH_RADIUS = EMPTY_REGION_CREATE_RADIUS * 2 + REGION_MERGE_RADIUS;
    private static final PlayerRegionGroups PLAYER_REGION_GROUPS = new PlayerRegionGroups();
    private static final RegionEntityBuckets REGION_LOADED_ENTITIES = new RegionEntityBuckets();
    private static final RegionEntityBuckets REGION_TICKING_ENTITIES = new RegionEntityBuckets();
    private static final ConcurrentHashMap<RegionKey, RegionizedWorldData> REGION_WORLD_DATA = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, RegionizedWorldData> GLOBAL_WORLD_DATA = new ConcurrentHashMap<>();

    private RegionizedWorldGuard() {
    }

    public static RegionKey regionFor(ServerLevel level, int chunkX, int chunkZ) {
        RegionKey fixedRegion = fixedRegionFor(level.dimension(), chunkX, chunkZ);
        return PLAYER_REGION_GROUPS.resolve(level, fixedRegion);
    }

    public static RegionKey regionFor(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        RegionKey fixedRegion = fixedRegionFor(dimension, chunkX, chunkZ);
        return PLAYER_REGION_GROUPS.resolve(dimension, fixedRegion);
    }

    public static RegionKey ownerForFixedRegion(ServerLevel level, RegionKey fixedRegion) {
        return PLAYER_REGION_GROUPS.resolve(level, fixedRegion);
    }

    public static RegionKey ownerForFixedRegion(RegionKey fixedRegion) {
        return PLAYER_REGION_GROUPS.resolve(fixedRegion.level(), fixedRegion);
    }

    public static void refreshChunkRegions(ServerLevel level, Map<RegionKey, Integer> fixedVisibleChunkCounts) {
        PLAYER_REGION_GROUPS.refreshChunkRegions(level, fixedVisibleChunkCounts);
    }

    public static List<ServerPlayer> localPlayersForCurrentRegion(ServerLevel level) {
        RegionKey currentRegion = RegionizedTickContext.currentRegion().orElse(null);
        if (currentRegion == null || !currentRegion.level().equals(level.dimension())) {
            return List.of();
        }

        return PLAYER_REGION_GROUPS.localPlayers(level, currentRegion);
    }

    public static Map<RegionKey, List<ServerPlayer>> localPlayersByRegion(ServerLevel level) {
        return PLAYER_REGION_GROUPS.localPlayersByRegion(level);
    }

    public static RegionizedWorldData currentWorldData(ServerLevel level) {
        RegionKey currentRegion = RegionizedTickContext.currentRegion().orElse(null);
        if (currentRegion != null && currentRegion.level().equals(level.dimension())) {
            RegionKey ownerRegion = ownerForFixedRegion(level, currentRegion);
            return REGION_WORLD_DATA.computeIfAbsent(ownerRegion, unused -> new RegionizedWorldData());
        }

        return GLOBAL_WORLD_DATA.computeIfAbsent(level.dimension(), unused -> new RegionizedWorldData());
    }

    public static RegionizedWorldData worldDataForBlock(ServerLevel level, BlockPos pos) {
        RegionKey ownerRegion = regionFor(level, pos);
        return REGION_WORLD_DATA.computeIfAbsent(ownerRegion, unused -> new RegionizedWorldData());
    }

    public static Map<RegionKey, List<RegionizedWorldData>> regionWorldDataByOwner(ServerLevel level) {
        Map<RegionKey, List<RegionizedWorldData>> dataByOwner = new HashMap<>();
        REGION_WORLD_DATA.forEach((region, data) -> {
            if (region.level().equals(level.dimension())) {
                RegionKey ownerRegion = ownerForFixedRegion(level, region);
                dataByOwner.computeIfAbsent(ownerRegion, unused -> new ArrayList<>()).add(data);
            }
        });
        return Map.copyOf(dataByOwner);
    }

    public static Map<RegionKey, RegionizedTaskDispatcher.RegionStatsSnapshot> collectRegionStats(
        ServerLevel level,
        Map<RegionKey, Integer> visibleChunkCountsByOwner
    ) {
        Map<RegionKey, MutableRegionStats> mutableStats = new HashMap<>();
        visibleChunkCountsByOwner.forEach((region, chunks) -> {
            if (chunks > 0 && region.level().equals(level.dimension())) {
                RegionKey ownerRegion = ownerForFixedRegion(level, region);
                mutableStats.computeIfAbsent(ownerRegion, unused -> new MutableRegionStats()).chunks += chunks;
            }
        });

        REGION_LOADED_ENTITIES.entityCountsByOwnerRegion(level).forEach((region, entities) ->
            mutableStats.computeIfAbsent(region, unused -> new MutableRegionStats()).entities += entities
        );

        localPlayersByRegion(level).forEach((region, players) ->
            mutableStats.computeIfAbsent(region, unused -> new MutableRegionStats()).players += players.size()
        );

        Map<RegionKey, RegionizedTaskDispatcher.RegionStatsSnapshot> snapshots = new HashMap<>();
        mutableStats.forEach((region, mutable) -> {
            RegionizedWorldData worldData = REGION_WORLD_DATA.computeIfAbsent(region, unused -> new RegionizedWorldData());
            worldData.updateStats(mutable.chunks, mutable.players, mutable.entities);
            snapshots.put(region, new RegionizedTaskDispatcher.RegionStatsSnapshot(
                worldData.getChunkCount(),
                worldData.getPlayerCount(),
                worldData.getEntityCount()
            ));
        });
        return Map.copyOf(snapshots);
    }

    public static List<RegionKey> fixedRegionsForOwner(ServerLevel level, RegionKey ownerRegion) {
        return PLAYER_REGION_GROUPS.fixedRegionsForOwner(level, ownerRegion);
    }

    public static void addLoadedEntity(ServerLevel level, Entity entity) {
        REGION_LOADED_ENTITIES.addOrUpdate(level, entity);
    }

    public static void updateLoadedEntityRegion(ServerLevel level, Entity entity) {
        REGION_LOADED_ENTITIES.updateIfPresent(level, entity);
    }

    public static void removeLoadedEntity(ServerLevel level, Entity entity) {
        REGION_LOADED_ENTITIES.remove(level, entity);
    }

    public static Iterable<Entity> loadedEntitiesForCurrentRegion(ServerLevel level) {
        RegionKey currentRegion = RegionizedTickContext.currentRegion().orElse(null);
        if (currentRegion == null || !currentRegion.level().equals(level.dimension())) {
            return List.of();
        }

        List<RegionKey> fixedRegions = fixedRegionsForOwner(level, currentRegion);
        if (fixedRegions.isEmpty()) {
            fixedRegions = List.of(currentRegion);
        }

        return REGION_LOADED_ENTITIES.entitiesForFixedRegions(level, fixedRegions);
    }

    public static void addTickingEntity(ServerLevel level, Entity entity) {
        REGION_TICKING_ENTITIES.addOrUpdate(level, entity);
    }

    public static void updateTickingEntityRegion(ServerLevel level, Entity entity) {
        REGION_TICKING_ENTITIES.updateIfPresent(level, entity);
    }

    public static void removeTickingEntity(ServerLevel level, Entity entity) {
        REGION_TICKING_ENTITIES.remove(level, entity);
    }

    public static Map<RegionKey, List<Entity>> tickingEntitiesByOwnerRegion(ServerLevel level) {
        return REGION_TICKING_ENTITIES.entitiesByOwnerRegion(level);
    }

    public static Map<RegionKey, RegionKey> buildOwnerSnapshotForTesting(
        ResourceKey<Level> dimension,
        Map<RegionKey, Integer> fixedVisibleChunkCounts,
        List<RegionKey> playerFixedRegions
    ) {
        return GroupSnapshot.build(dimension, fixedVisibleChunkCounts, playerFixedRegions).ownersByFixedRegion();
    }

    public static Map<RegionKey, List<RegionKey>> buildFixedRegionsByOwnerSnapshotForTesting(
        ResourceKey<Level> dimension,
        Map<RegionKey, Integer> fixedVisibleChunkCounts,
        List<RegionKey> playerFixedRegions
    ) {
        return GroupSnapshot.build(dimension, fixedVisibleChunkCounts, playerFixedRegions).fixedRegionsByOwner();
    }

    private static RegionKey fixedRegionFor(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return RegionKey.fromChunk(dimension, chunkX, chunkZ, DEFAULT_REGION_CHUNK_SHIFT);
    }

    public static RegionKey regionFor(ServerLevel level, ChunkPos pos) {
        return regionFor(level, pos.x(), pos.z());
    }

    public static RegionKey regionFor(ServerLevel level, BlockPos pos) {
        return regionFor(level, pos.getX() >> 4, pos.getZ() >> 4);
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

    public static boolean isTickThread(@Nullable MinecraftServer server) {
        return RegionizedTickContext.isRegionThread() || server != null && server.isSameThread();
    }

    public static boolean isRegionOwnedAndLoaded(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel && RegionizedTickContext.isRegionThread() && !isRegionThreadFor(serverLevel, pos)) {
            return false;
        }

        return level.hasChunkAt(pos);
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

        RegionBounds bounds = PLAYER_REGION_GROUPS.boundsFor(serverLevel, region);
        int minRegionBlockX = (bounds.minRegionX() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int minRegionBlockZ = (bounds.minRegionZ() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int maxRegionBlockX = (bounds.maxRegionXExclusive() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int maxRegionBlockZ = (bounds.maxRegionZExclusive() << DEFAULT_REGION_CHUNK_SHIFT) << 4;
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

    public static boolean isCurrentRegionFor(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        RegionKey expected = regionFor(dimension, chunkX, chunkZ);
        return RegionizedTickContext.currentRegion().map(expected::equals).orElse(false);
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

        return serverLevel.getServer().regionizedTaskDispatcher().tryExecuteGlobal(reason, task);
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

        return serverLevel.getServer().regionizedTaskDispatcher().tryExecuteGlobal(reason, task);
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

    private static final class MutableRegionStats {
        private int chunks;
        private int players;
        private int entities;
    }

    private record RegionBounds(int minRegionX, int minRegionZ, int maxRegionXExclusive, int maxRegionZExclusive) {
        private static RegionBounds single(RegionKey region) {
            return new RegionBounds(region.regionX(), region.regionZ(), region.regionX() + 1, region.regionZ() + 1);
        }
    }

    private static final class RegionEntityBuckets {
        private final ConcurrentHashMap<ResourceKey<Level>, DimensionEntityBuckets> levels = new ConcurrentHashMap<>();

        private void addOrUpdate(ServerLevel level, Entity entity) {
            this.levels.computeIfAbsent(level.dimension(), unused -> new DimensionEntityBuckets()).addOrUpdate(level, entity);
        }

        private void updateIfPresent(ServerLevel level, Entity entity) {
            DimensionEntityBuckets buckets = this.levels.get(level.dimension());
            if (buckets != null) {
                buckets.updateIfPresent(level, entity);
            }
        }

        private void remove(ServerLevel level, Entity entity) {
            DimensionEntityBuckets buckets = this.levels.get(level.dimension());
            if (buckets != null) {
                buckets.remove(entity);
            }
        }

        private List<Entity> entitiesForFixedRegions(ServerLevel level, List<RegionKey> fixedRegions) {
            DimensionEntityBuckets buckets = this.levels.get(level.dimension());
            return buckets == null ? List.of() : buckets.entitiesForFixedRegions(fixedRegions);
        }

        private Map<RegionKey, List<Entity>> entitiesByOwnerRegion(ServerLevel level) {
            DimensionEntityBuckets buckets = this.levels.get(level.dimension());
            if (buckets == null) {
                return Map.of();
            }

            Map<RegionKey, List<Entity>> fixedSnapshot = buckets.entitiesByFixedRegionSnapshot();
            Map<RegionKey, List<Entity>> mutableByOwner = new LinkedHashMap<>();
            fixedSnapshot.forEach((fixedRegion, entities) -> {
                RegionKey ownerRegion = ownerForFixedRegion(level, fixedRegion);
                mutableByOwner.computeIfAbsent(ownerRegion, unused -> new ArrayList<>()).addAll(entities);
            });

            Map<RegionKey, List<Entity>> byOwner = new LinkedHashMap<>();
            mutableByOwner.forEach((ownerRegion, entities) -> byOwner.put(ownerRegion, List.copyOf(entities)));
            return Map.copyOf(byOwner);
        }

        private Map<RegionKey, Integer> entityCountsByOwnerRegion(ServerLevel level) {
            DimensionEntityBuckets buckets = this.levels.get(level.dimension());
            if (buckets == null) {
                return Map.of();
            }

            Map<RegionKey, Integer> fixedSnapshot = buckets.entityCountsByFixedRegionSnapshot();
            Map<RegionKey, Integer> byOwner = new HashMap<>();
            fixedSnapshot.forEach((fixedRegion, entities) -> {
                RegionKey ownerRegion = ownerForFixedRegion(level, fixedRegion);
                byOwner.merge(ownerRegion, entities, Integer::sum);
            });
            return Map.copyOf(byOwner);
        }
    }

    private static final class DimensionEntityBuckets {
        private final IdentityHashMap<Entity, RegionKey> fixedRegionByEntity = new IdentityHashMap<>();
        private final Map<RegionKey, LinkedHashSet<Entity>> entitiesByFixedRegion = new HashMap<>();

        private synchronized void addOrUpdate(ServerLevel level, Entity entity) {
            if (entity.level() != level) {
                this.remove(entity);
                return;
            }

            ChunkPos chunkPos = entity.chunkPosition();
            RegionKey fixedRegion = fixedRegionFor(level.dimension(), chunkPos.x(), chunkPos.z());
            RegionKey previousRegion = this.fixedRegionByEntity.put(entity, fixedRegion);
            if (fixedRegion.equals(previousRegion)) {
                return;
            }

            if (previousRegion != null) {
                this.removeFromRegion(previousRegion, entity);
            }

            this.entitiesByFixedRegion.computeIfAbsent(fixedRegion, unused -> new LinkedHashSet<>()).add(entity);
        }

        private synchronized void updateIfPresent(ServerLevel level, Entity entity) {
            if (this.fixedRegionByEntity.containsKey(entity)) {
                this.addOrUpdate(level, entity);
            }
        }

        private synchronized void remove(Entity entity) {
            RegionKey previousRegion = this.fixedRegionByEntity.remove(entity);
            if (previousRegion != null) {
                this.removeFromRegion(previousRegion, entity);
            }
        }

        private synchronized List<Entity> entitiesForFixedRegions(List<RegionKey> fixedRegions) {
            List<Entity> entities = new ArrayList<>();
            for (RegionKey fixedRegion : fixedRegions) {
                LinkedHashSet<Entity> regionEntities = this.entitiesByFixedRegion.get(fixedRegion);
                if (regionEntities != null) {
                    entities.addAll(regionEntities);
                }
            }

            return List.copyOf(entities);
        }

        private synchronized Map<RegionKey, List<Entity>> entitiesByFixedRegionSnapshot() {
            Map<RegionKey, List<Entity>> snapshot = new LinkedHashMap<>();
            this.entitiesByFixedRegion.forEach((fixedRegion, entities) -> snapshot.put(fixedRegion, List.copyOf(entities)));
            return Map.copyOf(snapshot);
        }

        private synchronized Map<RegionKey, Integer> entityCountsByFixedRegionSnapshot() {
            Map<RegionKey, Integer> snapshot = new LinkedHashMap<>();
            this.entitiesByFixedRegion.forEach((fixedRegion, entities) -> snapshot.put(fixedRegion, entities.size()));
            return Map.copyOf(snapshot);
        }

        private void removeFromRegion(RegionKey fixedRegion, Entity entity) {
            LinkedHashSet<Entity> entities = this.entitiesByFixedRegion.get(fixedRegion);
            if (entities == null) {
                return;
            }

            entities.remove(entity);
            if (entities.isEmpty()) {
                this.entitiesByFixedRegion.remove(fixedRegion);
            }
        }
    }

    private static final class PlayerRegionGroups {
        private final ConcurrentHashMap<ResourceKey<Level>, CachedLevelGroups> levels = new ConcurrentHashMap<>();

        private RegionKey resolve(ServerLevel level, RegionKey fixedRegion) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            return groups.resolve(level, fixedRegion);
        }

        private RegionKey resolve(ResourceKey<Level> dimension, RegionKey fixedRegion) {
            CachedLevelGroups groups = this.levels.get(dimension);
            return groups == null ? fixedRegion : groups.resolve(fixedRegion);
        }

        private RegionBounds boundsFor(ServerLevel level, RegionKey region) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            return groups.boundsFor(level, region);
        }

        private void refreshChunkRegions(ServerLevel level, Map<RegionKey, Integer> fixedVisibleChunkCounts) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            groups.refreshChunkRegions(level, fixedVisibleChunkCounts);
        }

        private List<ServerPlayer> localPlayers(ServerLevel level, RegionKey ownerRegion) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            return groups.localPlayers(level, ownerRegion);
        }

        private Map<RegionKey, List<ServerPlayer>> localPlayersByRegion(ServerLevel level) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            return groups.localPlayersByRegion(level);
        }

        private List<RegionKey> fixedRegionsForOwner(ServerLevel level, RegionKey ownerRegion) {
            CachedLevelGroups groups = this.levels.computeIfAbsent(level.dimension(), unused -> new CachedLevelGroups());
            return groups.fixedRegionsForOwner(level, ownerRegion);
        }
    }

    private static final class CachedLevelGroups {
        private volatile int tick = Integer.MIN_VALUE;
        private volatile Map<RegionKey, Integer> fixedVisibleChunkCounts = Map.of();
        private volatile Map<RegionKey, RegionKey> ownersByFixedRegion = Map.of();
        private volatile Map<RegionKey, RegionBounds> boundsByOwner = Map.of();
        private volatile Map<RegionKey, List<RegionKey>> fixedRegionsByOwner = Map.of();
        private volatile Map<RegionKey, List<ServerPlayer>> localPlayersByOwner = Map.of();

        private RegionKey resolve(ServerLevel level, RegionKey fixedRegion) {
            this.refreshIfNeeded(level);
            return this.resolve(fixedRegion);
        }

        private RegionKey resolve(RegionKey fixedRegion) {
            return this.ownersByFixedRegion.getOrDefault(fixedRegion, fixedRegion);
        }

        private RegionBounds boundsFor(ServerLevel level, RegionKey region) {
            this.refreshIfNeeded(level);
            return this.boundsByOwner.getOrDefault(region, RegionBounds.single(region));
        }

        private List<ServerPlayer> localPlayers(ServerLevel level, RegionKey ownerRegion) {
            this.refreshIfNeeded(level);
            return this.localPlayersByOwner.getOrDefault(ownerRegion, List.of());
        }

        private Map<RegionKey, List<ServerPlayer>> localPlayersByRegion(ServerLevel level) {
            this.refreshIfNeeded(level);
            return this.localPlayersByOwner;
        }

        private List<RegionKey> fixedRegionsForOwner(ServerLevel level, RegionKey ownerRegion) {
            this.refreshIfNeeded(level);
            return this.fixedRegionsByOwner.getOrDefault(ownerRegion, List.of());
        }

        private void refreshIfNeeded(ServerLevel level) {
            int currentTick = level.getServer().getTickCount();
            if (this.tick == currentTick) {
                return;
            }

            synchronized (this) {
                if (this.tick == currentTick) {
                    return;
                }

                this.refreshSnapshots(level, this.fixedVisibleChunkCounts, currentTick);
            }
        }

        private void refreshChunkRegions(ServerLevel level, Map<RegionKey, Integer> fixedVisibleChunkCounts) {
            Map<RegionKey, Integer> filteredCounts = new HashMap<>();
            fixedVisibleChunkCounts.forEach((region, chunks) -> {
                if (chunks > 0 && region.level().equals(level.dimension())) {
                    filteredCounts.put(region, chunks);
                }
            });

            synchronized (this) {
                this.fixedVisibleChunkCounts = Map.copyOf(filteredCounts);
                this.refreshSnapshots(level, this.fixedVisibleChunkCounts, level.getServer().getTickCount());
            }
        }

        private void refreshSnapshots(ServerLevel level, Map<RegionKey, Integer> fixedVisibleChunkCounts, int tick) {
            GroupSnapshot snapshot = GroupSnapshot.build(level, fixedVisibleChunkCounts);
            this.ownersByFixedRegion = snapshot.ownersByFixedRegion();
            this.boundsByOwner = snapshot.boundsByOwner();
            this.fixedRegionsByOwner = snapshot.fixedRegionsByOwner();
            this.localPlayersByOwner = buildLocalPlayersByOwner(level, snapshot.ownersByFixedRegion());
            this.tick = tick;
        }

        private static Map<RegionKey, List<ServerPlayer>> buildLocalPlayersByOwner(
            ServerLevel level,
            Map<RegionKey, RegionKey> ownersByFixedRegion
        ) {
            Map<RegionKey, List<ServerPlayer>> mutablePlayers = new HashMap<>();
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayersAcrossRegions()) {
                if (player.level() != level) {
                    continue;
                }

                ChunkPos chunkPos = player.chunkPosition();
                RegionKey fixedRegion = fixedRegionFor(level.dimension(), chunkPos.x(), chunkPos.z());
                RegionKey owner = ownersByFixedRegion.getOrDefault(fixedRegion, fixedRegion);
                mutablePlayers.computeIfAbsent(owner, unused -> new ArrayList<>()).add(player);
            }

            Map<RegionKey, List<ServerPlayer>> localPlayers = new HashMap<>();
            mutablePlayers.forEach((region, players) -> localPlayers.put(region, List.copyOf(players)));
            return Map.copyOf(localPlayers);
        }
    }

    private record GroupSnapshot(
        Map<RegionKey, RegionKey> ownersByFixedRegion,
        Map<RegionKey, RegionBounds> boundsByOwner,
        Map<RegionKey, List<RegionKey>> fixedRegionsByOwner
    ) {
        private static GroupSnapshot build(ServerLevel level, Map<RegionKey, Integer> fixedVisibleChunkCounts) {
            List<RegionKey> playerFixedRegions = new ArrayList<>();
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayersAcrossRegions()) {
                if (player.level() != level) {
                    continue;
                }

                ChunkPos chunkPos = player.chunkPosition();
                playerFixedRegions.add(fixedRegionFor(level.dimension(), chunkPos.x(), chunkPos.z()));
            }

            return build(level.dimension(), fixedVisibleChunkCounts, playerFixedRegions);
        }

        private static GroupSnapshot build(
            ResourceKey<Level> dimension,
            Map<RegionKey, Integer> fixedVisibleChunkCounts,
            List<RegionKey> playerFixedRegions
        ) {
            Map<RegionKey, RegionKey> parents = new LinkedHashMap<>();
            List<RegionKey> activeRegions = new ArrayList<>();
            for (RegionKey fixedRegion : fixedVisibleChunkCounts.keySet()) {
                if (!fixedRegion.level().equals(dimension)) {
                    continue;
                }

                if (!parents.containsKey(fixedRegion)) {
                    parents.put(fixedRegion, fixedRegion);
                    activeRegions.add(fixedRegion);
                }
            }

            for (RegionKey fixedRegion : playerFixedRegions) {
                if (!fixedRegion.level().equals(dimension)) {
                    continue;
                }

                if (!parents.containsKey(fixedRegion)) {
                    parents.put(fixedRegion, fixedRegion);
                    activeRegions.add(fixedRegion);
                }
            }

            Map<Long, RegionKey> activeRegionsByCoordinate = new HashMap<>();
            for (RegionKey region : activeRegions) {
                activeRegionsByCoordinate.put(regionCoordinateKey(region.regionX(), region.regionZ()), region);
            }

            for (RegionKey region : activeRegions) {
                for (int dx = -PLAYER_REGION_MERGE_SEARCH_RADIUS; dx <= PLAYER_REGION_MERGE_SEARCH_RADIUS; dx++) {
                    for (int dz = -PLAYER_REGION_MERGE_SEARCH_RADIUS; dz <= PLAYER_REGION_MERGE_SEARCH_RADIUS; dz++) {
                        if ((dx | dz) == 0) {
                            continue;
                        }

                        RegionKey neighbour = activeRegionsByCoordinate.get(regionCoordinateKey(region.regionX() + dx, region.regionZ() + dz));
                        if (neighbour != null) {
                            union(parents, region, neighbour);
                        }
                    }
                }
            }

            Map<RegionKey, RegionKey> ownersByFixedRegion = new HashMap<>();
            Map<RegionKey, LinkedHashSet<RegionKey>> mutableFixedRegionsByOwner = new HashMap<>();
            RegionBoundsBuilder boundsBuilder = new RegionBoundsBuilder();
            for (RegionKey activeRegion : activeRegions) {
                RegionKey owner = find(parents, activeRegion);
                for (int dx = -EMPTY_REGION_CREATE_RADIUS; dx <= EMPTY_REGION_CREATE_RADIUS; dx++) {
                    for (int dz = -EMPTY_REGION_CREATE_RADIUS; dz <= EMPTY_REGION_CREATE_RADIUS; dz++) {
                        RegionKey coveredRegion = new RegionKey(dimension, activeRegion.regionX() + dx, activeRegion.regionZ() + dz);
                        ownersByFixedRegion.put(coveredRegion, owner);
                        mutableFixedRegionsByOwner.computeIfAbsent(owner, unused -> new LinkedHashSet<>()).add(coveredRegion);
                        boundsBuilder.include(owner, coveredRegion);
                    }
                }
            }

            Map<RegionKey, List<RegionKey>> fixedRegionsByOwner = new HashMap<>();
            mutableFixedRegionsByOwner.forEach((owner, regions) -> fixedRegionsByOwner.put(owner, List.copyOf(regions)));
            return new GroupSnapshot(Map.copyOf(ownersByFixedRegion), boundsBuilder.build(), Map.copyOf(fixedRegionsByOwner));
        }

        private static long regionCoordinateKey(int regionX, int regionZ) {
            return ((long)regionX << 32) ^ (regionZ & 0xffffffffL);
        }

        private static void union(Map<RegionKey, RegionKey> parents, RegionKey first, RegionKey second) {
            RegionKey firstRoot = find(parents, first);
            RegionKey secondRoot = find(parents, second);
            if (firstRoot.equals(secondRoot)) {
                return;
            }

            RegionKey owner = canonicalOwner(firstRoot, secondRoot);
            RegionKey child = owner.equals(firstRoot) ? secondRoot : firstRoot;
            parents.put(child, owner);
        }

        private static RegionKey find(Map<RegionKey, RegionKey> parents, RegionKey region) {
            RegionKey parent = parents.get(region);
            if (parent == null || parent.equals(region)) {
                return region;
            }

            RegionKey root = find(parents, parent);
            parents.put(region, root);
            return root;
        }

        private static RegionKey canonicalOwner(RegionKey first, RegionKey second) {
            int compareX = Integer.compare(first.regionX(), second.regionX());
            if (compareX < 0) {
                return first;
            }
            if (compareX > 0) {
                return second;
            }

            return first.regionZ() <= second.regionZ() ? first : second;
        }
    }

    private static final class RegionBoundsBuilder {
        private final Map<RegionKey, MutableRegionBounds> mutableBounds = new HashMap<>();

        private void include(RegionKey owner, RegionKey region) {
            this.mutableBounds.computeIfAbsent(owner, unused -> new MutableRegionBounds(region)).include(region);
        }

        private Map<RegionKey, RegionBounds> build() {
            Map<RegionKey, RegionBounds> bounds = new HashMap<>();
            this.mutableBounds.forEach((owner, mutable) -> bounds.put(owner, mutable.toBounds()));
            return Map.copyOf(bounds);
        }
    }

    private static final class MutableRegionBounds {
        private int minRegionX;
        private int minRegionZ;
        private int maxRegionXExclusive;
        private int maxRegionZExclusive;

        private MutableRegionBounds(RegionKey region) {
            this.minRegionX = region.regionX();
            this.minRegionZ = region.regionZ();
            this.maxRegionXExclusive = region.regionX() + 1;
            this.maxRegionZExclusive = region.regionZ() + 1;
        }

        private void include(RegionKey region) {
            this.minRegionX = Math.min(this.minRegionX, region.regionX());
            this.minRegionZ = Math.min(this.minRegionZ, region.regionZ());
            this.maxRegionXExclusive = Math.max(this.maxRegionXExclusive, region.regionX() + 1);
            this.maxRegionZExclusive = Math.max(this.maxRegionZExclusive, region.regionZ() + 1);
        }

        private RegionBounds toBounds() {
            return new RegionBounds(this.minRegionX, this.minRegionZ, this.maxRegionXExclusive, this.maxRegionZExclusive);
        }
    }

    public static void sendToPlayer(ServerPlayer player, Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        if (player.isChangingDimension()) {
            player.connection.send(packet);
            return;
        }

        executeOnPlayerRegion(player, () -> player.connection.send(packet));
    }

    public static void sendPreparedPacketToPlayer(ServerPlayer player, Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        player.connection.send(packet);
    }

    public static void sendPreparedPacketToPlayer(ServerPlayer player, Packet<?> packet, boolean flush) {
        Objects.requireNonNull(packet, "packet");
        player.connection.getConnection().send(packet, null, flush);
    }

    public static void sendToPlayer(ServerPlayer player, Packet<?> packet, @Nullable ChannelFutureListener listener) {
        Objects.requireNonNull(packet, "packet");
        if (player.isChangingDimension()) {
            player.connection.send(packet, listener);
            return;
        }

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
