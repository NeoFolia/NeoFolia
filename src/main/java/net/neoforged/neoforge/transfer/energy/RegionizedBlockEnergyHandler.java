/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer.energy;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Routes energy handler access for a server block position through that position's owning region.
 */
public final class RegionizedBlockEnergyHandler implements EnergyHandler {
    private final ServerLevel level;
    private final BlockPos pos;
    private final EnergyHandler delegate;

    public static EnergyHandler of(ServerLevel level, BlockPos pos, EnergyHandler delegate) {
        if (delegate instanceof RegionizedBlockEnergyHandler) {
            return delegate;
        }

        return new RegionizedBlockEnergyHandler(level, pos.immutable(), delegate);
    }

    private RegionizedBlockEnergyHandler(ServerLevel level, BlockPos pos, EnergyHandler delegate) {
        this.level = level;
        this.pos = pos;
        this.delegate = delegate;
    }

    @Override
    public long getAmountAsLong() {
        return callOnBlockRegion("block energy handler amount read", this.delegate::getAmountAsLong);
    }

    @Override
    public long getCapacityAsLong() {
        return callOnBlockRegion("block energy handler capacity read", this.delegate::getCapacityAsLong);
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return callOnBlockRegion("block energy handler insert", () -> this.delegate.insert(amount, transaction));
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return callOnBlockRegion("block energy handler extract", () -> this.delegate.extract(amount, transaction));
    }

    public <R> R callOnBlockRegion(String reason, Supplier<R> task) {
        return RegionizedWorldGuard.callOnRegionAndWait(this.level, this.pos, reason, task);
    }

    ServerLevel level() {
        return this.level;
    }

    RegionKey region() {
        return RegionizedWorldGuard.regionFor(this.level, this.pos);
    }

    EnergyHandler delegateForReservedRegion() {
        return this.delegate;
    }
}
