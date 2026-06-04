/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeUtil;
import net.minecraft.world.TickRateManager;
import net.neoforged.neoforge.server.region.RegionKey;
import net.neoforged.neoforge.server.region.RegionizedTaskDispatcher;
import net.neoforged.neoforge.server.region.RegionizedTaskDispatcher.RegionPerformanceSnapshot;
import net.neoforged.neoforge.server.region.RegionizedTaskDispatcher.RegionStatsSnapshot;
import net.neoforged.neoforge.server.region.RegionizedTaskDispatcher.RegionTickReportData;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;

class TPSCommand {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("########0.0");
    private static final DecimalFormat TWO_DECIMAL = new DecimalFormat("########0.00");
    private static final int HIGHEST_UTILISATION_REGIONS = 3;

    private static final int HEADER = rgb(79, 164, 240);
    private static final int PRIMARY = rgb(48, 145, 237);
    private static final int SECONDARY = rgb(104, 177, 240);
    private static final int INFORMATION = rgb(145, 198, 243);
    private static final int LIST = rgb(33, 97, 188);
    private static final int GOLD = rgb(255, 170, 0);

    static LiteralArgumentBuilder<CommandSourceStack> registerRootAlias() {
        return register();
    }

    static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("tps")
                .executes(TPSCommand::sendServerHealth);
    }

    private static int sendServerHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (CommandUtils.scheduleGlobalIfRegionized(source, () -> sendServerHealth(context))) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        RegionizedTaskDispatcher dispatcher = server.regionizedTaskDispatcher();
        if (!dispatcher.isRunning()) {
            source.sendFailure(Component.literal("NeoFolia region dispatcher is not running."));
            return Command.SINGLE_SUCCESS;
        }

        TickRateManager tickRateManager = server.tickRateManager();
        List<RegionHealth> regions = dispatcher.regionPerformanceSnapshots().stream()
                .map(snapshot -> RegionHealth.from(tickRateManager, snapshot))
                .toList();

        ChunkRates chunkRates = collectChunkRates(server);
        source.sendSuccess(() -> createServerHealthComponent(server, dispatcher, regions, chunkRates), false);
        return Command.SINGLE_SUCCESS;
    }

    private static Component createServerHealthComponent(MinecraftServer server, RegionizedTaskDispatcher dispatcher, List<RegionHealth> regions, ChunkRates chunkRates) {
        List<RegionHealth> reportableRegions = regions.stream()
                .filter(RegionHealth::hasRegionState)
                .toList();
        List<Double> sortedTps = reportableRegions.stream().map(RegionHealth::tps).sorted().toList();
        double lowestTps = sortedTps.isEmpty() ? targetTps(server.tickRateManager()) : sortedTps.get(0);
        double medianTps = sortedTps.isEmpty() ? targetTps(server.tickRateManager()) : median(sortedTps);
        double highestTps = sortedTps.isEmpty() ? targetTps(server.tickRateManager()) : sortedTps.get(sortedTps.size() - 1);
        double totalUtilisation = reportableRegions.stream().mapToDouble(RegionHealth::utilisation).sum();
        double maxUtilisation = Math.max(1, dispatcher.workerCount());

        List<RegionHealth> topRegions = reportableRegions.stream()
                .sorted(Comparator.comparingDouble(RegionHealth::utilisation).reversed())
                .limit(HIGHEST_UTILISATION_REGIONS)
                .toList();

        MutableComponent component = Component.literal("Server Health Report\n")
                .withColor(HEADER)
                .append(reportLine("Online Players: ", Integer.toString(server.getPlayerList().getPlayerCount()) + "\n", INFORMATION))
                .append(reportLine("Total regions: ", Integer.toString(reportableRegions.size()) + "\n", INFORMATION))
                .append(Component.literal(" - ").withColor(LIST))
                .append(Component.literal("Utilisation: ").withColor(PRIMARY))
                .append(Component.literal(ONE_DECIMAL.format(totalUtilisation * 100.0D)).withColor(utilisationColor(totalUtilisation / maxUtilisation)))
                .append(Component.literal("% / ").withColor(PRIMARY))
                .append(Component.literal(ONE_DECIMAL.format(maxUtilisation * 100.0D)).withColor(INFORMATION))
                .append(Component.literal("%\n").withColor(PRIMARY))
                .append(reportLine("Load rate: ", TWO_DECIMAL.format(chunkRates.loadRate()), INFORMATION))
                .append(Component.literal(", Gen rate: ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(chunkRates.generationRate()) + "\n").withColor(INFORMATION))
                .append(Component.literal(" - ").withColor(LIST))
                .append(Component.literal("Lowest Region TPS: ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(lowestTps) + "\n").withColor(tpsColor(lowestTps, server.tickRateManager())))
                .append(Component.literal(" - ").withColor(LIST))
                .append(Component.literal("Median Region TPS: ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(medianTps) + "\n").withColor(tpsColor(medianTps, server.tickRateManager())))
                .append(Component.literal(" - ").withColor(LIST))
                .append(Component.literal("Highest Region TPS: ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(highestTps) + "\n").withColor(tpsColor(highestTps, server.tickRateManager())))
                .append(Component.literal("Highest ").withColor(HEADER))
                .append(Component.literal(Integer.toString(HIGHEST_UTILISATION_REGIONS)).withColor(INFORMATION))
                .append(Component.literal(" utilisation regions\n").withColor(HEADER));

        if (topRegions.isEmpty()) {
            component.append(Component.literal(" - ").withColor(LIST))
                    .append(Component.literal("No region timing samples have been recorded yet.").withColor(SECONDARY));
            return component;
        }

        for (int i = 0; i < topRegions.size(); i++) {
            component.append(formatRegion(topRegions.get(i), i + 1 < topRegions.size()));
        }
        return component;
    }

    private static Component formatRegion(RegionHealth region, boolean newline) {
        RegionStatsSnapshot stats = region.stats();
        return Component.literal(" - ").withColor(LIST)
                .append(Component.literal("Region around block ").withColor(PRIMARY))
                .append(Component.literal(formatLocation(region.snapshot().region())).withColor(INFORMATION))
                .append(Component.literal(":\n").withColor(PRIMARY))
                .append(Component.literal("    ").withColor(PRIMARY))
                .append(Component.literal(ONE_DECIMAL.format(region.utilisation() * 100.0D)).withColor(utilisationColor(region.utilisation())))
                .append(Component.literal("% util at ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(region.mspt())).withColor(msptColor(region.mspt())))
                .append(Component.literal(" MSPT at ").withColor(PRIMARY))
                .append(Component.literal(TWO_DECIMAL.format(region.tps())).withColor(tpsColor(region.tps(), region.tickRateManager())))
                .append(Component.literal(" TPS\n").withColor(PRIMARY))
                .append(Component.literal("    Chunks: ").withColor(PRIMARY))
                .append(Component.literal(Integer.toString(stats.chunks())).withColor(INFORMATION))
                .append(Component.literal(" Players: ").withColor(PRIMARY))
                .append(Component.literal(Integer.toString(stats.players())).withColor(INFORMATION))
                .append(Component.literal(" Entities: ").withColor(PRIMARY))
                .append(Component.literal(Integer.toString(stats.entities()) + (newline ? "\n" : "")).withColor(INFORMATION));
    }

    private static Component reportLine(String label, String value, int valueColor) {
        return Component.literal(" - ").withColor(LIST)
                .append(Component.literal(label).withColor(PRIMARY))
                .append(Component.literal(value).withColor(valueColor));
    }

    private static ChunkRates collectChunkRates(MinecraftServer server) {
        double loadRate = 0.0D;
        double generationRate = 0.0D;
        for (ServerLevel level : server.getAllLevels()) {
            loadRate += level.getChunkSource().chunkLoadRatePerSecond();
            generationRate += level.getChunkSource().chunkGenerationRatePerSecond();
        }
        return new ChunkRates(loadRate, generationRate);
    }

    private static String formatLocation(RegionKey region) {
        int regionSizeBlocks = (1 << RegionizedWorldGuard.DEFAULT_REGION_CHUNK_SHIFT) << 4;
        int centerBlockX = region.regionX() * regionSizeBlocks + (regionSizeBlocks >> 1);
        int centerBlockZ = region.regionZ() * regionSizeBlocks + (regionSizeBlocks >> 1);
        return String.format(Locale.ROOT, "[w:'%s',%d,80,%d]", worldName(region), centerBlockX, centerBlockZ);
    }

    private static String worldName(RegionKey region) {
        String path = region.level().identifier().getPath();
        return "overworld".equals(path) ? "world" : path;
    }

    private static double median(List<Double> values) {
        int middle = values.size() >> 1;
        if ((values.size() & 1) == 0) {
            return (values.get(middle - 1) + values.get(middle)) / 2.0D;
        }
        return values.get(middle);
    }

    private static double targetTps(TickRateManager tickRateManager) {
        return TimeUtil.MILLISECONDS_PER_SECOND / tickRateManager.millisecondsPerTick();
    }

    private static double nanosToMillis(double nanos) {
        return nanos / TimeUtil.NANOSECONDS_PER_MILLISECOND;
    }

    private static int utilisationColor(double utilisation) {
        if (utilisation < 0.7D) {
            return CommonColors.GREEN;
        }
        if (utilisation < 0.9D) {
            return CommonColors.YELLOW;
        }
        if (utilisation <= 1.0D) {
            return GOLD;
        }
        return CommonColors.RED;
    }

    private static int msptColor(double mspt) {
        if (mspt <= 40.0D) {
            return CommonColors.GREEN;
        }
        if (mspt <= 45.0D) {
            return CommonColors.YELLOW;
        }
        if (mspt <= 50.0D) {
            return GOLD;
        }
        return CommonColors.RED;
    }

    private static int tpsColor(double tps, TickRateManager tickRateManager) {
        float maxTps = TimeUtil.MILLISECONDS_PER_SECOND / tickRateManager.millisecondsPerTick();
        return Mth.hsvToRgb((float)(Mth.inverseLerp(tps, 0.0D, maxTps) * 0.33F), 1.0F, 1.0F);
    }

    private static int rgb(int red, int green, int blue) {
        return red << 16 | green << 8 | blue;
    }

    private record RegionHealth(
            TickRateManager tickRateManager,
            RegionPerformanceSnapshot snapshot,
            RegionStatsSnapshot stats,
            double utilisation,
            double mspt,
            double tps
    ) {
        private boolean hasRegionState() {
            return !this.stats.isEmpty();
        }

        private static RegionHealth from(TickRateManager tickRateManager, RegionPerformanceSnapshot snapshot) {
            RegionTickReportData report = snapshot.tickReport15s();
            double mspt = report.sampleCount() == 0 ? 0.0D : nanosToMillis(report.averageTickTimeNanos());
            double tps = report.tps(targetTps(tickRateManager));
            double utilisation = report.utilisation();
            return new RegionHealth(tickRateManager, snapshot, snapshot.stats(), utilisation, mspt, tps);
        }
    }

    private record ChunkRates(double loadRate, double generationRate) {
    }
}
