/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.transfer.access;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

final class RegionizedPlayerItemAccess implements ItemAccess {
    private final ServerPlayer player;
    private final ItemAccess delegate;

    RegionizedPlayerItemAccess(ServerPlayer player, ItemAccess delegate) {
        this.player = player;
        this.delegate = delegate;
    }

    @Override
    public ItemResource getResource() {
        return call("player item access resource read", this.delegate::getResource);
    }

    @Override
    public int getAmount() {
        return call("player item access amount read", this.delegate::getAmount);
    }

    @Override
    public int insert(ItemResource resource, int amount, TransactionContext transaction) {
        return call("player item access insert", () -> this.delegate.insert(resource, amount, transaction));
    }

    @Override
    public int extract(ItemResource resource, int amount, TransactionContext transaction) {
        return call("player item access extract", () -> this.delegate.extract(resource, amount, transaction));
    }

    private <T> T call(String reason, Supplier<T> task) {
        return RegionizedWorldGuard.callOnPlayerRegionAndWait(this.player, reason, task);
    }
}
