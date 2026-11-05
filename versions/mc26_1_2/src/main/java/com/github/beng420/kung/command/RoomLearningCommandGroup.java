package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.command.KungCommandActions.LearnMode;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;

final class RoomLearningCommandGroup {
    private RoomLearningCommandGroup() {
    }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root, DungeonStateTracker tracker) {
        root.then(literal("room")
                .then(literal("roomdata")
                    .executes(context -> KungCommandActions.copyRoomData(context, tracker))
                    .then(roomInput().executes(context -> KungCommandActions.exportRoomInput(context, tracker))))
                .then(literal("type")
                    .then(argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(KungCommandActions.roomTypeNames(), builder))
                        .executes(context -> KungCommandActions.learnRoomType(context, tracker))))
                .then(literal("prince")
                    .then(argument("prince", BoolArgumentType.bool())
                        .executes(context -> KungCommandActions.updateCurrentRoomPrince(context, tracker))))
                .then(literal("project")
                    .executes(KungCommandActions::roomProjectStatus)
                    .then(literal("status").executes(KungCommandActions::roomProjectStatus))
                    .then(literal("off").executes(KungCommandActions::disableRoomProject))
                    .then(argument("path", StringArgumentType.greedyString())
                        .executes(KungCommandActions::setRoomProject)))
                .then(literal("debug").executes(context -> KungCommandActions.debugRooms(context, tracker)))
                .then(literal("mapdebug").executes(context -> KungCommandActions.debugMapDecorations(context, tracker)))
                .then(literal("undo").executes(KungCommandActions::undoLastRoomLearn))
                .then(literal("delete").then(roomInput().executes(KungCommandActions::deleteRoomInput)))
                .then(literal("export").then(roomInput().executes(context -> KungCommandActions.exportRoomInput(context, tracker))))
                .then(literal("look").then(roomInput().executes(context -> KungCommandActions.learnRoomInput(context, tracker, LearnMode.LOOKED))))
                .then(literal("learnmulti").then(roomInput().executes(context -> KungCommandActions.learnRoomInput(context, tracker, LearnMode.MULTI))))
                .then(literal("learn").then(roomInput().executes(context -> KungCommandActions.learnRoomInput(context, tracker, LearnMode.HERE)))))
            .then(literal("roomdata")
                .executes(context -> KungCommandActions.copyRoomData(context, tracker))
                .then(roomInput().executes(context -> KungCommandActions.exportRoomInput(context, tracker))))
            .then(literal("crypts")
                .then(argument("count", IntegerArgumentType.integer(0, 99))
                    .executes(context -> KungCommandActions.updateCurrentRoomCrypts(context, tracker))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> roomInput() {
        return argument("input", StringArgumentType.greedyString()).suggests(KungCommandActions::suggestRoomNames);
    }
}
