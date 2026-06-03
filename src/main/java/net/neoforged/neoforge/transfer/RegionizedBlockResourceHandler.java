/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Routes resource handler access for a server block position through that position's owning region.
 */
public final class RegionizedBlockResourceHandler<T extends Resource> implements ResourceHandler<T> {
    private final ServerLevel level;
    private final BlockPos pos;
    private final ResourceHandler<T> delegate;

    public static <T extends Resource> ResourceHandler<T> of(ServerLevel level, BlockPos pos, ResourceHandler<T> delegate) {
        if (delegate instanceof RegionizedBlockResourceHandler<?>) {
            return delegate;
        }

        return new RegionizedBlockResourceHandler<>(level, pos.immutable(), delegate);
    }

    private RegionizedBlockResourceHandler(ServerLevel level, BlockPos pos, ResourceHandler<T> delegate) {
        this.level = level;
        this.pos = pos;
        this.delegate = delegate;
    }

    @Override
    public int size() {
        return callOnBlockRegion("block resource handler size", this.delegate::size);
    }

    @Override
    public T getResource(int index) {
        return callOnBlockRegion("block resource handler resource read", () -> this.delegate.getResource(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return callOnBlockRegion("block resource handler amount read", () -> this.delegate.getAmountAsLong(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return callOnBlockRegion("block resource handler capacity read", () -> this.delegate.getCapacityAsLong(index, resource));
    }

    @Override
    public boolean isValid(int index, T resource) {
        return callOnBlockRegion("block resource handler validity read", () -> this.delegate.isValid(index, resource));
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        return callOnBlockRegion("block resource handler insert", () -> this.delegate.insert(index, resource, amount, transaction));
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        return callOnBlockRegion("block resource handler bulk insert", () -> this.delegate.insert(resource, amount, transaction));
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        return callOnBlockRegion("block resource handler extract", () -> this.delegate.extract(index, resource, amount, transaction));
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        return callOnBlockRegion("block resource handler bulk extract", () -> this.delegate.extract(resource, amount, transaction));
    }

    public <R> R callOnBlockRegion(String reason, Supplier<R> task) {
        return RegionizedWorldGuard.callOnRegionAndWait(this.level, this.pos, reason, task);
    }

    public boolean isOnOwningRegion() {
        return RegionizedWorldGuard.isRegionThreadFor(this.level, this.pos);
    }

    ServerLevel level() {
        return this.level;
    }

    RegionKey region() {
        return RegionizedWorldGuard.regionFor(this.level, this.pos);
    }

    ResourceHandler<T> delegateForReservedRegion() {
        return this.delegate;
    }
}
