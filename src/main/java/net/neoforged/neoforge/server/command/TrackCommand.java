/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.region.RegionizedTickContext;
import net.neoforged.neoforge.server.region.RegionizedWorldGuard;
import net.neoforged.neoforge.server.timings.ObjectTimings;
import net.neoforged.neoforge.server.timings.TimeTracker;

class TrackCommand {
    private static final DecimalFormat TIME_FORMAT = new DecimalFormat("#####0.00");

    static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("track")
                .then(StartTrackingCommand.register())
                .then(ResetTrackingCommand.register())
                .then(TrackResultsEntity.register())
                .then(TrackResultsBlockEntity.register())
                .then(StartTrackingCommand.register());
    }

    private static class StartTrackingCommand {
        static ArgumentBuilder<CommandSourceStack, ?> register() {
            return Commands.literal("start")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) //permission
                    .then(Commands.literal("blockentity")
                            .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        int duration = IntegerArgumentType.getInteger(ctx, "duration");
                                        if (scheduleGlobalIfRegionized(ctx.getSource(), () -> {
                                            TimeTracker.BLOCK_ENTITY_UPDATE.reset();
                                            TimeTracker.BLOCK_ENTITY_UPDATE.enable(duration);
                                            ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.be.enabled", duration), true);
                                        })) {
                                            return 0;
                                        }

                                        TimeTracker.BLOCK_ENTITY_UPDATE.reset();
                                        TimeTracker.BLOCK_ENTITY_UPDATE.enable(duration);
                                        ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.be.enabled", duration), true);
                                        return 0;
                                    })))
                    .then(Commands.literal("entity")
                            .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        int duration = IntegerArgumentType.getInteger(ctx, "duration");
                                        if (scheduleGlobalIfRegionized(ctx.getSource(), () -> {
                                            TimeTracker.ENTITY_UPDATE.reset();
                                            TimeTracker.ENTITY_UPDATE.enable(duration);
                                            ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.entity.enabled", duration), true);
                                        })) {
                                            return 0;
                                        }

                                        TimeTracker.ENTITY_UPDATE.reset();
                                        TimeTracker.ENTITY_UPDATE.enable(duration);
                                        ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.entity.enabled", duration), true);
                                        return 0;
                                    })));
        }
    }

    private static class ResetTrackingCommand {
        static ArgumentBuilder<CommandSourceStack, ?> register() {
            return Commands.literal("reset")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) //permission
                    .then(Commands.literal("blockentity")
                            .executes(ctx -> {
                                if (scheduleGlobalIfRegionized(ctx.getSource(), () -> {
                                    TimeTracker.BLOCK_ENTITY_UPDATE.reset();
                                    ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.be.reset"), true);
                                })) {
                                    return 0;
                                }

                                TimeTracker.BLOCK_ENTITY_UPDATE.reset();
                                ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.be.reset"), true);
                                return 0;
                            }))
                    .then(Commands.literal("entity")
                            .executes(ctx -> {
                                if (scheduleGlobalIfRegionized(ctx.getSource(), () -> {
                                    TimeTracker.ENTITY_UPDATE.reset();
                                    ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.entity.reset"), true);
                                })) {
                                    return 0;
                                }

                                TimeTracker.ENTITY_UPDATE.reset();
                                ctx.getSource().sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.entity.reset"), true);
                                return 0;
                            }));
        }
    }

    private static class TrackResults {
        /**
         * Returns the time objects recorded by the time tracker sorted by average time
         *
         * @return A list of time objects
         */
        private static <T> List<ObjectTimings<T>> getSortedTimings(TimeTracker<T> tracker) {
            ArrayList<ObjectTimings<T>> list = new ArrayList<>();

            list.addAll(tracker.getTimingData());
            list.sort(Comparator.comparingDouble(ObjectTimings::getAverageTimings));
            Collections.reverse(list);

            return list;
        }

        private static <T> int execute(CommandSourceStack source, TimeTracker<T> tracker, Function<ObjectTimings<T>, Component> toString) {
            if (scheduleGlobalIfRegionized(source, () -> execute(source, tracker, toString))) {
                return 0;
            }

            List<ObjectTimings<T>> timingsList = getSortedTimings(tracker);
            if (timingsList.isEmpty()) {
                source.sendSuccess(() -> CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.no_data"), true);
            } else {
                timingsList.stream()
                        .filter(timings -> timings.getObject().get() != null)
                        .limit(10)
                        .forEach(timings -> source.sendSuccess(() -> toString.apply(timings), true));
            }
            return 0;
        }
    }

    private static class TrackResultsEntity {
        static ArgumentBuilder<CommandSourceStack, ?> register() {
            return Commands.literal("entity").executes(ctx -> TrackResults.execute(ctx.getSource(), TimeTracker.ENTITY_UPDATE, data -> {
                Entity entity = data.getObject().get();
                if (entity == null)
                    return CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.invalid");

                EntityTrackingSnapshot snapshot = snapshotEntity(entity);
                return CommandUtils.makeTranslatableWithFallback(
                    "commands.neoforge.tracking.timing_entry",
                    snapshot.typeId(),
                    snapshot.dimensionId(),
                    snapshot.pos().getX(),
                    snapshot.pos().getY(),
                    snapshot.pos().getZ(),
                    formatTickTime(data.getAverageTimings())
                );
            }));
        }

        private static EntityTrackingSnapshot snapshotEntity(Entity entity) {
            return RegionizedWorldGuard.callOnEntityRegionAndWait(
                entity,
                "entity tracking timing snapshot",
                () -> new EntityTrackingSnapshot(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.level().dimension().identifier().toString(),
                    entity.blockPosition()
                )
            );
        }

        private record EntityTrackingSnapshot(String typeId, String dimensionId, BlockPos pos) {
        }
    }

    private static class TrackResultsBlockEntity {
        static ArgumentBuilder<CommandSourceStack, ?> register() {
            return Commands.literal("blockentity").executes(ctx -> TrackResults.execute(ctx.getSource(), TimeTracker.BLOCK_ENTITY_UPDATE, data -> {
                BlockEntity be = data.getObject().get();
                if (be == null)
                    return CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.invalid");

                BlockEntityTrackingSnapshot snapshot = snapshotBlockEntity(be);
                if (snapshot == null) {
                    return CommandUtils.makeTranslatableWithFallback("commands.neoforge.tracking.invalid");
                }

                return CommandUtils.makeTranslatableWithFallback(
                    "commands.neoforge.tracking.timing_entry",
                    snapshot.typeId(),
                    snapshot.dimensionId(),
                    snapshot.pos().getX(),
                    snapshot.pos().getY(),
                    snapshot.pos().getZ(),
                    formatTickTime(data.getAverageTimings())
                );
            }));
        }

        private static BlockEntityTrackingSnapshot snapshotBlockEntity(BlockEntity blockEntity) {
            Level level = blockEntity.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }

            BlockPos pos = blockEntity.getBlockPos().immutable();
            return RegionizedWorldGuard.callOnRegionAndWait(
                serverLevel,
                pos,
                "block entity tracking timing snapshot",
                () -> new BlockEntityTrackingSnapshot(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString(),
                    serverLevel.dimension().identifier().toString(),
                    pos
                )
            );
        }

        private record BlockEntityTrackingSnapshot(String typeId, String dimensionId, BlockPos pos) {
        }
    }

    private static String formatTickTime(double averageTimings) {
        return (averageTimings > 1000 ? TIME_FORMAT.format(averageTimings / 1000) : TIME_FORMAT.format(averageTimings))
            + (averageTimings < 1000 ? "\u03bcs" : "ms");
    }

    private static boolean scheduleGlobalIfRegionized(CommandSourceStack source, Runnable task) {
        if (!RegionizedTickContext.isRegionThread()) {
            return false;
        }

        if (source.getServer().regionizedTaskDispatcher().tryExecuteGlobal(task)) {
            return true;
        }

        throw new IllegalStateException("Cannot run global track command task from region worker while region dispatcher is not running");
    }
}
