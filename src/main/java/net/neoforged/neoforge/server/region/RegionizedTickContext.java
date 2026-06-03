/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.Optional;
import net.minecraft.util.RandomSource;

/**
 * Tracks which region is currently being processed by a region worker.
 */
public final class RegionizedTickContext {
    private static final ThreadLocal<RegionKey> CURRENT_REGION = new ThreadLocal<>();
    private static final ThreadLocal<RandomSource> CURRENT_RANDOM = new ThreadLocal<>();

    private RegionizedTickContext() {
    }

    public static Optional<RegionKey> currentRegion() {
        return Optional.ofNullable(CURRENT_REGION.get());
    }

    public static Optional<RandomSource> currentRandom() {
        return Optional.ofNullable(CURRENT_RANDOM.get());
    }

    public static boolean isRegionThread() {
        return CURRENT_REGION.get() != null;
    }

    static void runInRegion(RegionKey region, RandomSource random, Runnable task) {
        RegionKey previous = CURRENT_REGION.get();
        RandomSource previousRandom = CURRENT_RANDOM.get();
        CURRENT_REGION.set(region);
        CURRENT_RANDOM.set(random);
        try {
            task.run();
        } finally {
            if (previous == null) {
                CURRENT_REGION.remove();
            } else {
                CURRENT_REGION.set(previous);
            }

            if (previousRandom == null) {
                CURRENT_RANDOM.remove();
            } else {
                CURRENT_RANDOM.set(previousRandom);
            }
        }
    }
}
