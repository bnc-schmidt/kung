package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static org.junit.Assert.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public class CommandAliasesTest {
    private static CommandDispatcher<FabricClientCommandSource> dispatcher() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        var root = literal("kung");
        UserCommandGroup.register(root, null);
        dispatcher.register(root);
        return dispatcher;
    }

    private static ParseResults<FabricClientCommandSource> parse(CommandDispatcher<FabricClientCommandSource> dispatcher, String command) {
        return dispatcher.parse(command, null);
    }

    private static boolean executable(CommandDispatcher<FabricClientCommandSource> dispatcher, String command) {
        var parsed = parse(dispatcher, command);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead() && parsed.getContext().getCommand() != null;
    }

    @Test
    public void everyNameOfSplitsRunsTheSameCommand() {
        var dispatcher = dispatcher();
        for (String name : new String[] {"splits", "pbs", "personalbests"}) {
            assertTrue(name, executable(dispatcher, "kung " + name));
        }
        var splits = parse(dispatcher, "kung splits").getContext().getCommand();
        assertSame(splits, parse(dispatcher, "kung pbs").getContext().getCommand());
        assertSame(splits, parse(dispatcher, "kung personalbests").getContext().getCommand());
    }

    @Test
    public void anAliasTakesNoMoreThanItsCommandAndOthersAreUntouched() {
        var dispatcher = dispatcher();
        assertFalse(executable(dispatcher, "kung pbs extra"));
        assertFalse(executable(dispatcher, "kung personalbest"));
        assertTrue(executable(dispatcher, "kung jerry"));
        assertTrue(executable(dispatcher, "kung greenhouse dump"));
    }

    @Test
    public void aliasingAnUnknownCommandFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> CommandAliases.add(literal("kung"), "nothing", "x"));
    }
}
