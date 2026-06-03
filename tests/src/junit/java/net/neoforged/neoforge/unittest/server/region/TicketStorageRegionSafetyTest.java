/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.server.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.junit.jupiter.api.Test;

class TicketStorageRegionSafetyTest {
    @Test
    void chunkUpdateListenersRunOutsideTicketStorageMonitor() {
        TicketStorage storage = new TicketStorage();
        AtomicBoolean listenerObserved = new AtomicBoolean();
        long chunk = ChunkPos.pack(0, 0);
        Ticket ticket = new Ticket(TicketType.PLAYER_LOADING, 31);

        storage.setLoadingChunkUpdatedListener((node, level, onlyDecreased) -> {
            assertFalse(Thread.holdsLock(storage));
            listenerObserved.set(true);
        });

        storage.addTicket(chunk, ticket);
        assertTrue(listenerObserved.get());

        listenerObserved.set(false);
        storage.removeTicket(chunk, ticket);
        assertTrue(listenerObserved.get());
    }
}
