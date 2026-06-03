/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Routes resource handler access for a server entity through that entity's owning region.
 */
public final class RegionizedEntityResourceHandler<T extends Resource> implements ResourceHandler<T> {
    private final Entity entity;
    private final ResourceHandler<T> delegate;

    public static <T extends Resource> ResourceHandler<T> of(Entity entity, ResourceHandler<T> delegate) {
        if (delegate instanceof RegionizedEntityResourceHandler<?> || delegate instanceof RegionizedPlayerResourceHandler<?>) {
            return delegate;
        }

        return new RegionizedEntityResourceHandler<>(entity, delegate);
    }

    private RegionizedEntityResourceHandler(Entity entity, ResourceHandler<T> delegate) {
        this.entity = entity;
        this.delegate = delegate;
    }

    @Override
    public int size() {
        return callOnEntityRegion("entity resource handler size", this.delegate::size);
    }

    @Override
    public T getResource(int index) {
        return callOnEntityRegion("entity resource handler resource read", () -> this.delegate.getResource(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return callOnEntityRegion("entity resource handler amount read", () -> this.delegate.getAmountAsLong(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return callOnEntityRegion("entity resource handler capacity read", () -> this.delegate.getCapacityAsLong(index, resource));
    }

    @Override
    public boolean isValid(int index, T resource) {
        return callOnEntityRegion("entity resource handler validity read", () -> this.delegate.isValid(index, resource));
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity resource handler insert", () -> this.delegate.insert(index, resource, amount, transaction));
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity resource handler bulk insert", () -> this.delegate.insert(resource, amount, transaction));
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity resource handler extract", () -> this.delegate.extract(index, resource, amount, transaction));
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        return callOnEntityRegion("entity resource handler bulk extract", () -> this.delegate.extract(resource, amount, transaction));
    }

    public <R> R callOnEntityRegion(String reason, Supplier<R> task) {
        return RegionizedWorldGuard.callOnEntityRegionAndWait(this.entity, reason, task);
    }

    public boolean isOnOwningRegion() {
        return this.entity.level() instanceof ServerLevel level && RegionizedWorldGuard.isRegionThreadFor(level, this.entity.blockPosition());
    }

    Entity entity() {
        return this.entity;
    }

    RegionKey region() {
        return RegionizedWorldGuard.regionFor((ServerLevel) this.entity.level(), this.entity.blockPosition());
    }

    ResourceHandler<T> delegateForReservedRegion() {
        return this.delegate;
    }
}
