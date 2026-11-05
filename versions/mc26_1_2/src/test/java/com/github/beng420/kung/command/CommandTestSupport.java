package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class CommandTestSupport {
    private CommandTestSupport() { }

    /** A dispatcher whose "kung" root got the given command group. */
    static CommandDispatcher<FabricClientCommandSource> kung(Consumer<LiteralArgumentBuilder<FabricClientCommandSource>> group) {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        var root = literal("kung");
        group.accept(root);
        dispatcher.register(root);
        return dispatcher;
    }

    static CommandDispatcher<FabricClientCommandSource> userDispatcher() {
        return kung(root -> UserCommandGroup.register(root, null));
    }

    static ParseResults<FabricClientCommandSource> parse(CommandDispatcher<FabricClientCommandSource> dispatcher, String command) {
        return dispatcher.parse(command, null);
    }
}
