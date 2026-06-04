/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.server.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import org.junit.jupiter.api.Test;

class RegionizedWorldGuardTest {
    private static final ResourceKey<Level> DIMENSION = Level.OVERWORLD;

    @Test
    void mergesActiveSectionsAcrossFoliaEmptyBridgeDistance() {
        Map<RegionKey, RegionKey> owners = buildOwners(counts(region(0, 0), region(3, 0)), List.of());

        assertEquals(region(0, 0), owners.get(region(0, 0)));
        assertEquals(region(0, 0), owners.get(region(1, 0)));
        assertEquals(region(0, 0), owners.get(region(2, 0)));
        assertEquals(region(0, 0), owners.get(region(3, 0)));
    }

    @Test
    void keepsActiveSectionsSeparateBeyondFoliaEmptyBridgeDistance() {
        Map<RegionKey, RegionKey> owners = buildOwners(counts(region(0, 0), region(4, 0)), List.of());

        assertEquals(region(0, 0), owners.get(region(0, 0)));
        assertEquals(region(0, 0), owners.get(region(1, 0)));
        assertNull(owners.get(region(2, 0)));
        assertEquals(region(4, 0), owners.get(region(3, 0)));
        assertEquals(region(4, 0), owners.get(region(4, 0)));
    }

    @Test
    void treatsPlayerSectionsAsActiveBeforeHolderSnapshotCatchesUp() {
        Map<RegionKey, RegionKey> owners = buildOwners(Map.of(), List.of(region(2, 2), region(5, 2)));

        assertEquals(region(2, 2), owners.get(region(2, 2)));
        assertEquals(region(2, 2), owners.get(region(3, 2)));
        assertEquals(region(2, 2), owners.get(region(4, 2)));
        assertEquals(region(2, 2), owners.get(region(5, 2)));
    }

    @Test
    void ownedFixedSectionsAreDeduplicatedAcrossAdjacentActiveSections() {
        Map<RegionKey, List<RegionKey>> fixedRegionsByOwner = RegionizedWorldGuard.buildFixedRegionsByOwnerSnapshotForTesting(
            DIMENSION,
            counts(region(0, 0), region(1, 0)),
            List.of()
        );

        assertEquals(List.of(region(-1, -1), region(-1, 0), region(-1, 1), region(0, -1), region(0, 0), region(0, 1),
            region(1, -1), region(1, 0), region(1, 1), region(2, -1), region(2, 0), region(2, 1)), fixedRegionsByOwner.get(region(0, 0)));
    }

    private static Map<RegionKey, RegionKey> buildOwners(Map<RegionKey, Integer> chunkCounts, List<RegionKey> playerRegions) {
        return RegionizedWorldGuard.buildOwnerSnapshotForTesting(DIMENSION, chunkCounts, playerRegions);
    }

    private static Map<RegionKey, Integer> counts(RegionKey... regions) {
        Map<RegionKey, Integer> counts = new LinkedHashMap<>();
        for (RegionKey region : regions) {
            counts.put(region, 1);
        }
        return counts;
    }

    private static RegionKey region(int x, int z) {
        return new RegionKey(DIMENSION, x, z);
    }
}
