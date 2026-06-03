/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

class EnergyHandlerUtilTest {
    @Test
    void nullHandlersReturnZero() {
        SimpleEnergyHandler handler = new SimpleEnergyHandler(100);

        assertEquals(0, EnergyHandlerUtil.move(null, handler, 10, null));
        assertEquals(0, EnergyHandlerUtil.move(handler, null, 10, null));
    }

    @Test
    void zeroAmountReturnsZero() {
        SimpleEnergyHandler source = new SimpleEnergyHandler(100, 100, 100, 50);
        SimpleEnergyHandler target = new SimpleEnergyHandler(100);

        assertEquals(0, EnergyHandlerUtil.move(source, target, 0, null));
        assertEquals(50, source.getAmountAsLong());
        assertEquals(0, target.getAmountAsLong());
    }

    @Test
    void movesRequestedAmount() {
        SimpleEnergyHandler source = new SimpleEnergyHandler(100, 100, 100, 50);
        SimpleEnergyHandler target = new SimpleEnergyHandler(100);

        assertEquals(30, EnergyHandlerUtil.move(source, target, 30, null));
        assertEquals(20, source.getAmountAsLong());
        assertEquals(30, target.getAmountAsLong());
    }

    @Test
    void moveIsLimitedByTargetCapacity() {
        SimpleEnergyHandler source = new SimpleEnergyHandler(100, 100, 100, 50);
        SimpleEnergyHandler target = new SimpleEnergyHandler(20);

        assertEquals(20, EnergyHandlerUtil.move(source, target, 50, null));
        assertEquals(30, source.getAmountAsLong());
        assertEquals(20, target.getAmountAsLong());
    }

    @Test
    void transactionIsRespected() {
        SimpleEnergyHandler source = new SimpleEnergyHandler(100, 100, 100, 50);
        SimpleEnergyHandler target = new SimpleEnergyHandler(100);

        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(30, EnergyHandlerUtil.move(source, target, 30, tx));
            assertEquals(20, source.getAmountAsLong());
            assertEquals(30, target.getAmountAsLong());
        }

        assertEquals(50, source.getAmountAsLong());
        assertEquals(0, target.getAmountAsLong());
    }
}
