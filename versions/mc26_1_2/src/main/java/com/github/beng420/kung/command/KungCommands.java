package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.runtime.AppServices;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public final class KungCommands {
    private KungCommands() {
    }

    public static void register(AppServices services) {
        DungeonStateTracker tracker = services.dungeonStateTracker();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = literal("kung")
                .executes(KungCommandActions::openSettings);
            RoomLearningCommandGroup.register(root, tracker);
            if (!KungBuild.MODRINTH) MimicCommandGroup.register(root, tracker);
            RoomSyncCommandGroup.register(root, tracker);
            DungeonDebugCommandGroup.register(root, tracker);
            UserCommandGroup.register(root, tracker);
            WaypointCommandGroup.register(root);
            DeveloperCommandGroup.register(root);
            dispatcher.register(root);
            DungeonJoinCommands.register(dispatcher);
        });
    }
}
