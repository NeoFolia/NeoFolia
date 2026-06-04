/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import org.jspecify.annotations.Nullable;

/**
 * Minimal per-region vanilla world state used by Folia-style regional ticks.
 */
public final class RegionizedWorldData {
    public int catSpawnerNextTick;
    public int patrolSpawnerNextTick;
    public int phantomSpawnerNextTick;
    public VillageSiegeState villageSiegeState = new VillageSiegeState();
    public WanderingTraderData wanderingTraderData = new WanderingTraderData();
    public int wanderingTraderTickDelay = 1200;
    public NaturalSpawner.@Nullable SpawnState lastSpawnState;
    private final List<TickingBlockEntity> pendingBlockEntityTickers = new ArrayList<>();
    private final List<TickingBlockEntity> blockEntityTickers = new ArrayList<>();
    private boolean tickingBlockEntities;
    private volatile int chunkCount;
    private volatile int playerCount;
    private volatile int entityCount;

    public int getChunkCount() {
        return this.chunkCount;
    }

    public int getPlayerCount() {
        return this.playerCount;
    }

    public int getEntityCount() {
        return this.entityCount;
    }

    public void updateStats(int chunks, int players, int entities) {
        this.chunkCount = Math.max(0, chunks);
        this.playerCount = Math.max(0, players);
        this.entityCount = Math.max(0, entities);
    }

    public synchronized void addBlockEntityTicker(TickingBlockEntity ticker) {
        (this.tickingBlockEntities ? this.pendingBlockEntityTickers : this.blockEntityTickers).add(ticker);
    }

    public synchronized boolean hasBlockEntityTickers() {
        return !this.blockEntityTickers.isEmpty() || !this.pendingBlockEntityTickers.isEmpty();
    }

    public synchronized List<TickingBlockEntity> beginBlockEntityTick() {
        this.tickingBlockEntities = true;
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
            this.pendingBlockEntityTickers.clear();
        }

        return this.blockEntityTickers;
    }

    public synchronized void endBlockEntityTick(Collection<TickingBlockEntity> toRemove) {
        if (!toRemove.isEmpty()) {
            this.blockEntityTickers.removeAll(toRemove);
        }

        this.tickingBlockEntities = false;
    }

    public static final class VillageSiegeState {
        public boolean hasSetupSiege;
        public VillageSiege.State siegeState = VillageSiege.State.SIEGE_DONE;
        public int zombiesToSpawn;
        public int nextSpawnTime;
        public int spawnX;
        public int spawnY;
        public int spawnZ;
    }
}
