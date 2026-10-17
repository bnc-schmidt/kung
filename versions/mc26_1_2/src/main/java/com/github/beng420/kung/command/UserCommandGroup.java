package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class UserCommandGroup {
    private UserCommandGroup() {
    }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root, DungeonStateTracker tracker) {
        root.then(literal("calculatecata")
                .executes(context -> KungCommandActions.openCatacombsCalculator(context, ""))
                .then(argument("username", StringArgumentType.word())
                    .executes(context -> KungCommandActions.openCatacombsCalculator(
                        context,
                        StringArgumentType.getString(context, "username")
                    ))))
            .then(literal("settings").executes(KungCommandActions::openSettings))
            .then(literal("splits").executes(context -> KungCommandActions.openSplitPersonalBests(context, tracker)))
            .then(literal("updates").executes(KungCommandActions::openUpdates))
            .then(literal("greenhouse").executes(KungCommandActions::openGreenhouse)
                .then(literal("dump").executes(KungCommandActions::dumpGreenhouse))
                .then(hologram("blueprint"))
                .then(hologram("holo")))
            .then(literal("changelog").executes(KungCommandActions::openChangelog))
            .then(literal("visitors")
                .then(literal("mute").executes(KungCommandActions::muteVisitorAlarm)))
            .then(literal("preview")
                .then(literal("updates").executes(KungCommandActions::previewUpdateNotification)))
            .then(literal("hud").executes(context -> KungCommandActions.openHudEditor(context, tracker)));
    }

    /** The greenhouse hologram commands; "holo" is a second name for "blueprint". */
    private static LiteralArgumentBuilder<FabricClientCommandSource> hologram(String name) {
        return literal(name).executes(KungCommandActions::showHologram)
            .then(literal("here").executes(KungCommandActions::showHologramHere))
            .then(literal("off").executes(KungCommandActions::hideHologram))
            .then(literal("rotate").executes(KungCommandActions::rotateHologram))
            .then(literal("mirror").executes(KungCommandActions::mirrorHologram));
    }
}
