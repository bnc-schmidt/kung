package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.DungeonSplitTracker;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.feature.misc.FrozenBlazeCapture;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;

final class DungeonDebugCommandGroup {
    private DungeonDebugCommandGroup() {
    }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root, DungeonStateTracker tracker) {
        if (!KungBuild.MODRINTH) root.then(literal("test")
            .then(literal("title")
                .executes(context -> KungCommandActions.testDoorTitle(context, tracker, 4))
                .then(argument("doors", IntegerArgumentType.integer(0, 20))
                    .executes(context -> KungCommandActions.testDoorTitle(context, tracker))))
            .then(literal("dungeonstart")
                .executes(context -> KungCommandActions.testDungeonStartTitle(context, tracker, 4))
                .then(argument("doors", IntegerArgumentType.integer(0, 20))
                    .executes(context -> KungCommandActions.testDungeonStartTitle(context, tracker)))));
        root.then(literal("dungeon").then(literal("debug")
                .executes(context -> KungCommandActions.copyUltraDebug(context, tracker))))
            .then(literal("instance").executes(KungCommandActions::debugInstanceContext))
            .then(literal("log")
                // Copy takes the whole stored trace, the same lines save writes.
                .executes(context -> KungCommandActions.copyDebugLog(context, KungDebugRecorder.MAX_STORED_LINES))
                .then(literal("copy").executes(context -> KungCommandActions.copyDebugLog(context, KungDebugRecorder.MAX_STORED_LINES)))
                .then(literal("tail")
                    .executes(context -> KungCommandActions.copyDebugLog(context, 200))
                    .then(argument("lines", IntegerArgumentType.integer(1, KungDebugRecorder.MAX_STORED_LINES))
                        .executes(context -> KungCommandActions.copyDebugLog(
                            context,
                            IntegerArgumentType.getInteger(context, "lines")
                        ))))
                .then(literal("save").executes(KungCommandActions::saveDebugLog))
                .then(literal("clear").executes(KungCommandActions::clearDebugLog)))
            .then(literal("fbcapture")
                .executes(context -> {
                    FrozenBlazeCapture.USAGE.forEach(line -> reply(context, line));
                    return 1;
                })
                .then(literal("start")
                    .executes(context -> reply(context, FrozenBlazeCapture.start(
                        context.getSource().getClient(), FrozenBlazeCapture.DEFAULT_SECONDS)))
                    .then(argument("seconds", IntegerArgumentType.integer(1, FrozenBlazeCapture.MAX_SECONDS))
                        .executes(context -> reply(context, FrozenBlazeCapture.start(
                            context.getSource().getClient(), IntegerArgumentType.getInteger(context, "seconds"))))))
                .then(literal("mark").then(argument("label", StringArgumentType.greedyString())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(FrozenBlazeCapture.MARKS, builder))
                    .executes(context -> reply(context, FrozenBlazeCapture.mark(StringArgumentType.getString(context, "label"))))))
                .then(literal("stop").executes(context -> reply(context, FrozenBlazeCapture.stop(
                    context.getSource().getClient(), "stop")))))
            .then(literal("door").then(literal("probe")
                .executes(KungCommandActions::probeDoorAhead)
                .then(argument("direction", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(KungCommandActions.directionNames(), builder))
                    .executes(KungCommandActions::probeDoorDirection))))
            .then(literal("split")
                .then(literal("mark").then(argument("name", StringArgumentType.greedyString())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(DungeonSplitTracker.defaultSplitNames(), builder))
                    .executes(context -> KungCommandActions.markSplit(context, tracker))))
                .then(literal("reset").executes(context -> KungCommandActions.resetSplits(context, tracker))));
    }

    private static int reply(CommandContext<FabricClientCommandSource> context, String text) {
        context.getSource().sendFeedback(KungMessages.info("FB capture", text));
        return 1;
    }
}
