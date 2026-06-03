/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer.energy;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Routes energy handler access for a server entity through that entity's owning region.
 */
public final class RegionizedEntityEnergyHandler implements EnergyHandler {
    private final Entity entity;
    private final EnergyHandler delegate;

    public static EnergyHandler of(Entity entity, EnergyHandler delegate) {
        if (delegate instanceof RegionizedEntityEnergyHandler) {
            return delegate;
        }

        return new RegionizedEntityEnergyHandler(entity, delegate);
    }

    private RegionizedEntityEnergyHandler(Entity entity, EnergyHandler delegate) {
        this.entity = entity;
        this.delegate = delegate;
    }

    @Override
    public long getAmountAsLong() {
        return callOnEntityRegion("entity energy handler amount read", this.delegate::getAmountAsLong);
    }

    @Override
    public long getCapacityAsLong() {
        return callOnEntityRegion("entity energy handler capacity read", this.delegate::getCapacityAsLong);
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity energy handler insert", () -> this.delegate.insert(amount, transaction));
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity energy handler extract", () -> this.delegate.extract(amount, transaction));
    }

    public <R> R callOnEntityRegion(String reason, Supplier<R> task) {
        return RegionizedWorldGuard.callOnEntityRegionAndWait(this.entity, reason, task);
    }

    Entity entity() {
        return this.entity;
    }

    RegionKey region() {
        return RegionizedWorldGuard.regionFor((ServerLevel) this.entity.level(), this.entity.blockPosition());
    }

    EnergyHandler delegateForReservedRegion() {
        return this.delegate;
    }
}
