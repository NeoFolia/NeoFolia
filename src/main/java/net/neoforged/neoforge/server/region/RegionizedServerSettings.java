/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Configuration switches for experimental NeoFolia region features.
 */
public final class RegionizedServerSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_PATH = Path.of("config", "neofolia.toml");
    private static final String REGION_THREADS_KEY = "region_threads";
    private static final String REGIONIZED_CHUNK_TICKS_KEY = "regionized_chunk_ticks";
    private static final String REGIONIZED_SCHEDULED_TICKS_KEY = "regionized_scheduled_ticks";
    private static final String REGIONIZED_BLOCK_ENTITY_TICKS_KEY = "regionized_block_entity_ticks";
    private static final String REGIONIZED_ENTITY_TICKS_KEY = "regionized_entity_ticks";
    private static final String CHUNK_WORKER_THREADS_KEY = "chunk_worker_threads";
    private static final String CHUNK_IO_THREADS_KEY = "chunk_io_threads";
    private static final String CHUNK_PARSE_THREADS_KEY = "chunk_parse_threads";
    private static final String MAX_PENDING_LOGINS_KEY = "max_pending_logins";
    private static final String PLAYER_CHUNK_LOAD_CONCURRENCY_KEY = "player_chunk_load_concurrency";
    private static final String PLAYER_CHUNK_SEND_REGION_FANOUT_KEY = "player_chunk_send_region_fanout";
    private static final String PLAYER_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK_KEY = "player_packet_processor_max_packets_per_tick";
    private static final String PLAYER_PACKET_PROCESSOR_MAX_TIME_MS_KEY = "player_packet_processor_max_time_ms";
    private static final String NETTY_IO_THREADS_KEY = "netty_io_threads";
    private static final String REGION_TASK_MAX_TASKS_PER_RUN_KEY = "region_task_max_tasks_per_run";
    private static final String REGION_TASK_MAX_TIME_MS_KEY = "region_task_max_time_ms";
    private static final String PACKET_PROCESSOR_MAX_PACKETS_PER_TICK_KEY = "packet_processor_max_packets_per_tick";
    private static final String PACKET_PROCESSOR_MAX_TIME_MS_KEY = "packet_processor_max_time_ms";
    private static final String GLOBAL_TASK_MAX_TASKS_PER_TICK_KEY = "global_task_max_tasks_per_tick";
    private static final String GLOBAL_TASK_MAX_TIME_MS_KEY = "global_task_max_time_ms";
    private static final String CHUNK_UNLOAD_MAX_TASKS_PER_TICK_KEY = "chunk_unload_max_tasks_per_tick";
    private static final String CHUNK_UNLOAD_MAX_TIME_MS_KEY = "chunk_unload_max_time_ms";
    private static final String REGION_TASK_TIMEOUT_SECONDS_KEY = "region_task_timeout_seconds";
    private static final int DEFAULT_REGION_THREADS = 0;
    private static final int DEFAULT_CHUNK_WORKER_THREADS = 0;
    private static final int DEFAULT_CHUNK_IO_THREADS = 0;
    private static final int DEFAULT_CHUNK_PARSE_THREADS = 0;
    private static final int DEFAULT_MAX_PENDING_LOGINS = 0;
    private static final int DEFAULT_PLAYER_CHUNK_LOAD_CONCURRENCY = 0;
    private static final int DEFAULT_PLAYER_CHUNK_SEND_REGION_FANOUT = 0;
    private static final int DEFAULT_PLAYER_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK = 256;
    private static final int DEFAULT_PLAYER_PACKET_PROCESSOR_MAX_TIME_MS = 2;
    private static final int DEFAULT_NETTY_IO_THREADS = 0;
    private static final int DEFAULT_REGION_TASK_MAX_TASKS_PER_RUN = 256;
    private static final int DEFAULT_REGION_TASK_MAX_TIME_MS = 5;
    private static final int DEFAULT_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK = 4096;
    private static final int DEFAULT_PACKET_PROCESSOR_MAX_TIME_MS = 10;
    private static final int DEFAULT_GLOBAL_TASK_MAX_TASKS_PER_TICK = 1024;
    private static final int DEFAULT_GLOBAL_TASK_MAX_TIME_MS = 10;
    private static final int DEFAULT_CHUNK_UNLOAD_MAX_TASKS_PER_TICK = 256;
    private static final int DEFAULT_CHUNK_UNLOAD_MAX_TIME_MS = 5;
    private static final int DEFAULT_REGION_TASK_TIMEOUT_SECONDS = 60;
    private static final Settings SETTINGS = load();

    private RegionizedServerSettings() {
    }

    public static int regionThreadCount() {
        int configuredThreads = SETTINGS.regionThreads();
        if (configuredThreads <= 0) {
            return automaticRegionThreadCount();
        }

        return configuredThreads;
    }

    public static boolean regionizedChunkTicksEnabled() {
        return SETTINGS.regionizedChunkTicks();
    }

    public static boolean regionizedScheduledTicksEnabled() {
        return SETTINGS.regionizedScheduledTicks();
    }

    public static boolean regionizedBlockEntityTicksEnabled() {
        return SETTINGS.regionizedBlockEntityTicks();
    }

    public static boolean regionizedEntityTicksEnabled() {
        return SETTINGS.regionizedEntityTicks();
    }

    public static int chunkWorkerThreadCount() {
        int configuredThreads = SETTINGS.chunkWorkerThreads();
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        return automaticChunkWorkerThreadCount();
    }

    public static int chunkIoThreadCount() {
        int configuredThreads = SETTINGS.chunkIoThreads();
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        return automaticChunkIoThreadCount();
    }

    public static int chunkParseThreadCount() {
        int configuredThreads = SETTINGS.chunkParseThreads();
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        return automaticChunkParseThreadCount();
    }

    public static int maxPendingLogins() {
        int configuredLogins = SETTINGS.maxPendingLogins();
        if (configuredLogins > 0) {
            return configuredLogins;
        }

        return Math.max(2, Math.min(8, regionThreadCount() / 2));
    }

    public static int playerChunkLoadConcurrency() {
        int configuredConcurrency = SETTINGS.playerChunkLoadConcurrency();
        if (configuredConcurrency > 0) {
            return configuredConcurrency;
        }

        return Math.max(4, Math.min(64, regionThreadCount() * 2));
    }

    public static int playerChunkSendRegionFanout() {
        int configuredFanout = SETTINGS.playerChunkSendRegionFanout();
        if (configuredFanout > 0) {
            return configuredFanout;
        }

        return Math.max(1, Math.min(4, regionThreadCount()));
    }

    public static int playerPacketProcessorMaxPacketsPerTick() {
        return SETTINGS.playerPacketProcessorMaxPacketsPerTick();
    }

    public static int playerPacketProcessorMaxTimeMs() {
        return SETTINGS.playerPacketProcessorMaxTimeMs();
    }

    public static int nettyIoThreadCount() {
        int configuredThreads = SETTINGS.nettyIoThreads();
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        return automaticNettyIoThreadCount();
    }

    private static int automaticRegionThreadCount() {
        int availableProcessors = availableProcessors();
        int targetAllocatedThreads = Math.max(1, (int)Math.floor(availableProcessors * 0.9D));
        int reservedThreads = 1 // server thread
            + configuredOrAutomatic(SETTINGS.chunkWorkerThreads(), automaticChunkWorkerThreadCount())
            + configuredOrAutomatic(SETTINGS.chunkIoThreads(), automaticChunkIoThreadCount())
            + configuredOrAutomatic(SETTINGS.chunkParseThreads(), automaticChunkParseThreadCount())
            + configuredOrAutomatic(SETTINGS.nettyIoThreads(), automaticNettyIoThreadCount());
        return Math.max(1, targetAllocatedThreads - Math.max(0, reservedThreads));
    }

    private static int automaticChunkWorkerThreadCount() {
        return Math.max(1, Math.min(8, availableProcessors() / 4));
    }

    private static int automaticChunkIoThreadCount() {
        return Math.max(1, Math.min(3, availableProcessors() / 8));
    }

    private static int automaticChunkParseThreadCount() {
        return Math.max(1, Math.min(2, availableProcessors() / 12));
    }

    private static int automaticNettyIoThreadCount() {
        return Math.max(1, Math.min(4, availableProcessors() / 8));
    }

    private static int configuredOrAutomatic(int configuredThreads, int automaticThreads) {
        return configuredThreads > 0 ? configuredThreads : automaticThreads;
    }

    private static int availableProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public static int regionTaskMaxTasksPerRun() {
        return SETTINGS.regionTaskMaxTasksPerRun();
    }

    public static int regionTaskMaxTimeMs() {
        return SETTINGS.regionTaskMaxTimeMs();
    }

    public static int packetProcessorMaxPacketsPerTick() {
        return SETTINGS.packetProcessorMaxPacketsPerTick();
    }

    public static int packetProcessorMaxTimeMs() {
        return SETTINGS.packetProcessorMaxTimeMs();
    }

    public static int globalTaskMaxTasksPerTick() {
        return SETTINGS.globalTaskMaxTasksPerTick();
    }

    public static int globalTaskMaxTimeMs() {
        return SETTINGS.globalTaskMaxTimeMs();
    }

    public static int chunkUnloadMaxTasksPerTick() {
        return SETTINGS.chunkUnloadMaxTasksPerTick();
    }

    public static int chunkUnloadMaxTimeMs() {
        return SETTINGS.chunkUnloadMaxTimeMs();
    }

    public static int regionTaskTimeoutSeconds() {
        return SETTINGS.regionTaskTimeoutSeconds();
    }

    public static String configPath() {
        return CONFIG_PATH.toString();
    }

    public static String describe() {
        return "config=" + configPath()
            + ", " + REGION_THREADS_KEY + "=" + SETTINGS.regionThreads() + " (workers=" + regionThreadCount() + ")"
            + ", " + REGIONIZED_CHUNK_TICKS_KEY + "=" + regionizedChunkTicksEnabled()
            + ", " + REGIONIZED_SCHEDULED_TICKS_KEY + "=" + regionizedScheduledTicksEnabled()
            + ", " + REGIONIZED_BLOCK_ENTITY_TICKS_KEY + "=" + regionizedBlockEntityTicksEnabled()
            + ", " + REGIONIZED_ENTITY_TICKS_KEY + "=" + regionizedEntityTicksEnabled()
            + ", " + REGION_TASK_TIMEOUT_SECONDS_KEY + "=" + regionTaskTimeoutSeconds();
    }

    private static Settings load() {
        ensureConfigFile();
        Map<String, String> values = readConfigValues();
        return new Settings(
            readInt(values, REGION_THREADS_KEY, DEFAULT_REGION_THREADS, 0),
            readBoolean(values, REGIONIZED_CHUNK_TICKS_KEY, true),
            readBoolean(values, REGIONIZED_SCHEDULED_TICKS_KEY, true),
            readBoolean(values, REGIONIZED_BLOCK_ENTITY_TICKS_KEY, true),
            readBoolean(values, REGIONIZED_ENTITY_TICKS_KEY, true),
            readInt(values, CHUNK_WORKER_THREADS_KEY, DEFAULT_CHUNK_WORKER_THREADS, 0),
            readInt(values, CHUNK_IO_THREADS_KEY, DEFAULT_CHUNK_IO_THREADS, 0),
            readInt(values, CHUNK_PARSE_THREADS_KEY, DEFAULT_CHUNK_PARSE_THREADS, 0),
            readInt(values, MAX_PENDING_LOGINS_KEY, DEFAULT_MAX_PENDING_LOGINS, 0),
            readInt(values, PLAYER_CHUNK_LOAD_CONCURRENCY_KEY, DEFAULT_PLAYER_CHUNK_LOAD_CONCURRENCY, 0),
            readInt(values, PLAYER_CHUNK_SEND_REGION_FANOUT_KEY, DEFAULT_PLAYER_CHUNK_SEND_REGION_FANOUT, 0),
            readInt(values, PLAYER_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK_KEY, DEFAULT_PLAYER_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK, 0),
            readInt(values, PLAYER_PACKET_PROCESSOR_MAX_TIME_MS_KEY, DEFAULT_PLAYER_PACKET_PROCESSOR_MAX_TIME_MS, 0),
            readInt(values, NETTY_IO_THREADS_KEY, DEFAULT_NETTY_IO_THREADS, 0),
            readInt(values, REGION_TASK_MAX_TASKS_PER_RUN_KEY, DEFAULT_REGION_TASK_MAX_TASKS_PER_RUN, 0),
            readInt(values, REGION_TASK_MAX_TIME_MS_KEY, DEFAULT_REGION_TASK_MAX_TIME_MS, 0),
            readInt(values, PACKET_PROCESSOR_MAX_PACKETS_PER_TICK_KEY, DEFAULT_PACKET_PROCESSOR_MAX_PACKETS_PER_TICK, 0),
            readInt(values, PACKET_PROCESSOR_MAX_TIME_MS_KEY, DEFAULT_PACKET_PROCESSOR_MAX_TIME_MS, 0),
            readInt(values, GLOBAL_TASK_MAX_TASKS_PER_TICK_KEY, DEFAULT_GLOBAL_TASK_MAX_TASKS_PER_TICK, 0),
            readInt(values, GLOBAL_TASK_MAX_TIME_MS_KEY, DEFAULT_GLOBAL_TASK_MAX_TIME_MS, 0),
            readInt(values, CHUNK_UNLOAD_MAX_TASKS_PER_TICK_KEY, DEFAULT_CHUNK_UNLOAD_MAX_TASKS_PER_TICK, 0),
            readInt(values, CHUNK_UNLOAD_MAX_TIME_MS_KEY, DEFAULT_CHUNK_UNLOAD_MAX_TIME_MS, 0),
            readInt(values, REGION_TASK_TIMEOUT_SECONDS_KEY, DEFAULT_REGION_TASK_TIMEOUT_SECONDS, 0)
        );
    }

    private static void ensureConfigFile() {
        if (Files.exists(CONFIG_PATH)) {
            appendMissingConfigKeys();
            return;
        }

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, defaultConfigContents(), StandardCharsets.UTF_8);
            LOGGER.info("Created NeoFolia region config at {}", CONFIG_PATH.toAbsolutePath());
        } catch (IOException ex) {
            LOGGER.warn("Failed to create NeoFolia region config at {}, using built-in defaults", CONFIG_PATH.toAbsolutePath(), ex);
        }
    }

    private static void appendMissingConfigKeys() {
        String contents;
        try {
            contents = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to read NeoFolia region config at {}, using built-in defaults", CONFIG_PATH.toAbsolutePath(), ex);
            return;
        }

        StringBuilder missing = new StringBuilder();
        if (!containsConfigKey(contents, REGION_THREADS_KEY)) {
            missing.append("""

                region_threads = 0
                """);
        }
        if (!containsConfigKey(contents, REGIONIZED_CHUNK_TICKS_KEY)) {
            missing.append("""

                regionized_chunk_ticks = true
                """);
        }
        if (!containsConfigKey(contents, REGIONIZED_SCHEDULED_TICKS_KEY)) {
            missing.append("""

                regionized_scheduled_ticks = true
                """);
        }
        if (!containsConfigKey(contents, REGIONIZED_BLOCK_ENTITY_TICKS_KEY)) {
            missing.append("""

                regionized_block_entity_ticks = true
                """);
        }
        if (!containsConfigKey(contents, REGIONIZED_ENTITY_TICKS_KEY)) {
            missing.append("""

                regionized_entity_ticks = true
                """);
        }
        if (!containsConfigKey(contents, REGION_TASK_TIMEOUT_SECONDS_KEY)) {
            missing.append("""

                region_task_timeout_seconds = 60
                """);
        }

        if (missing.isEmpty()) {
            return;
        }

        try {
            Files.writeString(CONFIG_PATH, contents.stripTrailing() + System.lineSeparator() + missing, StandardCharsets.UTF_8);
            LOGGER.info("Updated NeoFolia region config at {} with new setting(s)", CONFIG_PATH.toAbsolutePath());
        } catch (IOException ex) {
            LOGGER.warn("Failed to update NeoFolia region config at {}", CONFIG_PATH.toAbsolutePath(), ex);
        }
    }

    private static boolean containsConfigKey(String contents, String key) {
        for (String line : contents.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }

            if (key.equals(trimmed.substring(0, equals).trim())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> readConfigValues() {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(CONFIG_PATH)) {
            return values;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(CONFIG_PATH, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to read NeoFolia region config at {}, using built-in defaults", CONFIG_PATH.toAbsolutePath(), ex);
            return values;
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
                continue;
            }

            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }

            String key = trimmed.substring(0, equals).trim();
            String value = stripInlineComment(trimmed.substring(equals + 1).trim());
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }

        return values;
    }

    private static String stripInlineComment(String value) {
        int comment = value.indexOf('#');
        if (comment >= 0) {
            value = value.substring(0, comment).trim();
        }

        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static boolean readBoolean(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }

        if ("false".equals(normalized)) {
            return false;
        }

        LOGGER.warn("Invalid boolean value '{}' for {} in {}, using {}", value, key, CONFIG_PATH, fallback);
        return fallback;
    }

    private static int readInt(Map<String, String> values, String key, int fallback, int minValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Math.max(minValue, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid integer value '{}' for {} in {}, using {}", value, key, CONFIG_PATH, fallback);
            return fallback;
        }
    }

    private static String defaultConfigContents() {
        return """
            # NeoFolia region threading settings.
            # region_threads = 0 automatically uses available CPU cores.
            # Set region_threads to a positive number for manual worker thread control.
            region_threads = 0

            regionized_chunk_ticks = true
            regionized_scheduled_ticks = true
            regionized_block_entity_ticks = true
            regionized_entity_ticks = true

            # 0 disables timeout checks for blocking region waits.
            region_task_timeout_seconds = 60
            """;
    }

    private record Settings(
        int regionThreads,
        boolean regionizedChunkTicks,
        boolean regionizedScheduledTicks,
        boolean regionizedBlockEntityTicks,
        boolean regionizedEntityTicks,
        int chunkWorkerThreads,
        int chunkIoThreads,
        int chunkParseThreads,
        int maxPendingLogins,
        int playerChunkLoadConcurrency,
        int playerChunkSendRegionFanout,
        int playerPacketProcessorMaxPacketsPerTick,
        int playerPacketProcessorMaxTimeMs,
        int nettyIoThreads,
        int regionTaskMaxTasksPerRun,
        int regionTaskMaxTimeMs,
        int packetProcessorMaxPacketsPerTick,
        int packetProcessorMaxTimeMs,
        int globalTaskMaxTasksPerTick,
        int globalTaskMaxTimeMs,
        int chunkUnloadMaxTasksPerTick,
        int chunkUnloadMaxTimeMs,
        int regionTaskTimeoutSeconds
    ) {
    }
}
