package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public class GreenhouseHologramCommandTest {
    private static CommandDispatcher<FabricClientCommandSource> dispatcher() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        var root = literal("kung");
        UserCommandGroup.register(root, null);
        dispatcher.register(root);
        return dispatcher;
    }

    private static ParseResults<FabricClientCommandSource> parse(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                                                 String command) {
        return dispatcher.parse(command, null);
    }

    private static void assertExecutable(CommandDispatcher<FabricClientCommandSource> dispatcher, String command) {
        ParseResults<FabricClientCommandSource> parsed = parse(dispatcher, command);
        assertTrue(command, parsed.getExceptions().isEmpty());
        assertFalse(command, parsed.getReader().canRead());
        assertNotNull(command, parsed.getContext().getCommand());
    }

    @Test
    public void greenhouseStillOpensThePlannerAndTheHologramHangsBelowIt() {
        var dispatcher = dispatcher();

        assertExecutable(dispatcher, "kung greenhouse");
        assertExecutable(dispatcher, "kung greenhouse dump");
        for (String name : new String[] {"blueprint", "holo"}) {
            assertExecutable(dispatcher, "kung greenhouse " + name);
            for (String action : new String[] {"here", "off", "rotate", "mirror"}) {
                assertExecutable(dispatcher, "kung greenhouse " + name + " " + action);
            }
        }
    }

    @Test
    public void theHologramCommandsAreNotThePlanner() {
        var dispatcher = dispatcher();

        var planner = parse(dispatcher, "kung greenhouse").getContext().getCommand();
        var blueprint = parse(dispatcher, "kung greenhouse blueprint").getContext().getCommand();
        var here = parse(dispatcher, "kung greenhouse blueprint here").getContext().getCommand();
        assertNotEquals(planner, blueprint);
        assertNotEquals(blueprint, here);
        assertNotEquals(planner, parse(dispatcher, "kung greenhouse holo").getContext().getCommand());
    }

    @Test
    public void unknownSubcommandsDoNotParseToACommand() {
        var dispatcher = dispatcher();

        for (String command : new String[] {"kung greenhouse bogus", "kung greenhouse blueprint bogus",
            "kung greenhouse holo off extra"}) {
            ParseResults<FabricClientCommandSource> parsed = parse(dispatcher, command);
            assertTrue(command, !parsed.getExceptions().isEmpty() || parsed.getReader().canRead());
            // Leftover input is a syntax error before any handler runs.
            assertThrows(command, CommandSyntaxException.class, () -> dispatcher.execute(parsed));
        }
    }
}
