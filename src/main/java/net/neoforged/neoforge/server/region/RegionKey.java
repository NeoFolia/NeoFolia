/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Identifies one independently scheduled server region.
 */
public record RegionKey(ResourceKey<Level> level, int regionX, int regionZ) {
    public static RegionKey fromChunk(ResourceKey<Level> level, int chunkX, int chunkZ, int regionChunkShift) {
        return new RegionKey(level, chunkX >> regionChunkShift, chunkZ >> regionChunkShift);
    }

    public static RegionKey fromChunk(ResourceKey<Level> level, ChunkPos pos, int regionChunkShift) {
        return fromChunk(level, pos.x(), pos.z(), regionChunkShift);
    }

    public static RegionKey fromBlock(ResourceKey<Level> level, BlockPos pos, int regionChunkShift) {
        return fromChunk(level, pos.getX() >> 4, pos.getZ() >> 4, regionChunkShift);
    }
}
