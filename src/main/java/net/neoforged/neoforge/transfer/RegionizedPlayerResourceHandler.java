/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Routes resource handler access for a server player through that player's owning region.
 */
public final class RegionizedPlayerResourceHandler<T extends Resource> implements ResourceHandler<T> {
    private final ServerPlayer player;
    private final Supplier<ResourceHandler<T>> delegate;

    public static <T extends Resource> ResourceHandler<T> of(ServerPlayer player, ResourceHandler<T> delegate) {
        return of(player, () -> delegate);
    }

    public static <T extends Resource> ResourceHandler<T> of(ServerPlayer player, Supplier<ResourceHandler<T>> delegate) {
        return new RegionizedPlayerResourceHandler<>(player, delegate);
    }

    private RegionizedPlayerResourceHandler(ServerPlayer player, Supplier<ResourceHandler<T>> delegate) {
        this.player = player;
        this.delegate = delegate;
    }

    @Override
    public int size() {
        return call("player resource handler size", () -> this.delegate.get().size());
    }

    @Override
    public T getResource(int index) {
        return call("player resource handler resource read", () -> this.delegate.get().getResource(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return call("player resource handler amount read", () -> this.delegate.get().getAmountAsLong(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return call("player resource handler capacity read", () -> this.delegate.get().getCapacityAsLong(index, resource));
    }

    @Override
    public boolean isValid(int index, T resource) {
        return call("player resource handler validity read", () -> this.delegate.get().isValid(index, resource));
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        return call("player resource handler insert", () -> this.delegate.get().insert(index, resource, amount, transaction));
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        return call("player resource handler bulk insert", () -> this.delegate.get().insert(resource, amount, transaction));
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        return call("player resource handler extract", () -> this.delegate.get().extract(index, resource, amount, transaction));
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        return call("player resource handler bulk extract", () -> this.delegate.get().extract(resource, amount, transaction));
    }

    private <R> R call(String reason, Supplier<R> task) {
        return RegionizedWorldGuard.callOnPlayerRegionAndWait(this.player, reason, task);
    }

    public <R> R callOnPlayerRegion(String reason, Supplier<R> task) {
        return call(reason, task);
    }

    public boolean isOnOwningRegion() {
        return RegionizedWorldGuard.isOnPlayerRegion(this.player);
    }

    ServerPlayer player() {
        return this.player;
    }

    RegionKey region() {
        return RegionizedWorldGuard.regionFor(this.player.level(), this.player.blockPosition());
    }

    ResourceHandler<T> delegateForReservedRegion() {
        return this.delegate.get();
    }
}
