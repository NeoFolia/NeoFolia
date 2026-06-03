/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

/**
 * System-property switches for experimental NeoFolia region features.
 */
public final class RegionizedServerSettings {
    private static final String REGIONIZED_CHUNK_TICKS_PROPERTY = "neoforge.regionizedChunkTicks";
    private static final String REGIONIZED_SCHEDULED_TICKS_PROPERTY = "neoforge.regionizedScheduledTicks";
    private static final String REGIONIZED_BLOCK_ENTITY_TICKS_PROPERTY = "neoforge.regionizedBlockEntityTicks";
    private static final String REGIONIZED_ENTITY_TICKS_PROPERTY = "neoforge.regionizedEntityTicks";
    private static final String REGION_TASK_TIMEOUT_SECONDS_PROPERTY = "neoforge.regionTaskTimeoutSeconds";
    private static final int DEFAULT_REGION_TASK_TIMEOUT_SECONDS = 60;

    private RegionizedServerSettings() {
    }

    public static boolean regionizedChunkTicksEnabled() {
        return Boolean.getBoolean(REGIONIZED_CHUNK_TICKS_PROPERTY);
    }

    public static boolean regionizedScheduledTicksEnabled() {
        return Boolean.getBoolean(REGIONIZED_SCHEDULED_TICKS_PROPERTY);
    }

    public static boolean regionizedBlockEntityTicksEnabled() {
        return Boolean.getBoolean(REGIONIZED_BLOCK_ENTITY_TICKS_PROPERTY);
    }

    public static boolean regionizedEntityTicksEnabled() {
        return Boolean.getBoolean(REGIONIZED_ENTITY_TICKS_PROPERTY);
    }

    public static int regionTaskTimeoutSeconds() {
        String configured = System.getProperty(REGION_TASK_TIMEOUT_SECONDS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_REGION_TASK_TIMEOUT_SECONDS;
        }

        try {
            return Math.max(0, Integer.parseInt(configured));
        } catch (NumberFormatException ex) {
            return DEFAULT_REGION_TASK_TIMEOUT_SECONDS;
        }
    }

    public static String describe() {
        return REGIONIZED_CHUNK_TICKS_PROPERTY + "=" + regionizedChunkTicksEnabled()
            + ", " + REGIONIZED_SCHEDULED_TICKS_PROPERTY + "=" + regionizedScheduledTicksEnabled()
            + ", " + REGIONIZED_BLOCK_ENTITY_TICKS_PROPERTY + "=" + regionizedBlockEntityTicksEnabled()
            + ", " + REGIONIZED_ENTITY_TICKS_PROPERTY + "=" + regionizedEntityTicksEnabled()
            + ", " + REGION_TASK_TIMEOUT_SECONDS_PROPERTY + "=" + regionTaskTimeoutSeconds();
    }
}
