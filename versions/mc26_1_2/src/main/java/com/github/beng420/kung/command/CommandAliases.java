package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Extra names for a /kung command, registered after the command itself: {@code add(root, "splits", "pbs")} makes
 * /kung pbs run exactly what /kung splits runs, sub-commands and all. (Chat commands list their names in the
 * {@code ChatCommand} enum constructor instead.)
 */
final class CommandAliases {
    private CommandAliases() { }

    static void add(LiteralArgumentBuilder<FabricClientCommandSource> root, String command, String... aliases) {
        CommandNode<FabricClientCommandSource> target = root.getArguments().stream()
            .filter(node -> node.getName().equals(command)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No command " + command + " to alias"));
        for (String alias : aliases) {
            // A redirect alone only runs the target's sub-commands; the command itself has to be copied.
            root.then(literal(alias).executes(target.getCommand()).redirect(target));
        }
    }
}
