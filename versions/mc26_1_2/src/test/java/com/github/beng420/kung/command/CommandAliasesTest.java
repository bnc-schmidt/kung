package com.github.beng420.kung.command;

import static com.github.beng420.kung.command.CommandTestSupport.kung;
import static com.github.beng420.kung.command.CommandTestSupport.parse;
import static com.github.beng420.kung.command.CommandTestSupport.userDispatcher;
import static org.junit.Assert.*;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public class CommandAliasesTest {
    private static boolean executable(CommandDispatcher<FabricClientCommandSource> dispatcher, String command) {
        var parsed = parse(dispatcher, command);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead() && parsed.getContext().getCommand() != null;
    }

    @Test
    public void everyNameOfSplitsRunsTheSameCommand() {
        var dispatcher = userDispatcher();
        for (String name : new String[] {"splits", "pbs", "personalbests"}) {
            assertTrue(name, executable(dispatcher, "kung " + name));
        }
        var splits = parse(dispatcher, "kung splits").getContext().getCommand();
        assertSame(splits, parse(dispatcher, "kung pbs").getContext().getCommand());
        assertSame(splits, parse(dispatcher, "kung personalbests").getContext().getCommand());
    }

    @Test
    public void anAliasTakesNoMoreThanItsCommandAndOthersAreUntouched() {
        var dispatcher = userDispatcher();
        assertFalse(executable(dispatcher, "kung pbs extra"));
        assertFalse(executable(dispatcher, "kung personalbest"));
        assertTrue(executable(dispatcher, "kung jerry"));
        assertTrue(executable(dispatcher, "kung greenhouse dump"));
    }

    @Test
    public void wpAndWpsAreWaypointsWithAllItsSubcommands() {
        var dispatcher = kung(WaypointCommandGroup::register);
        for (String name : new String[] {"waypoints", "wp", "wps"}) {
            for (String rest : new String[] {"", " list", " group new Mines", " move 1 2 up", " import here"}) {
                var parsed = parse(dispatcher, "kung " + name + rest);
                // An alias is a redirect, so the command to run sits in the last child context.
                assertTrue(name + rest, parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                    && parsed.getContext().getLastChild().getCommand() != null);
            }
        }
        var unknown = parse(dispatcher, "kung wpx");
        assertFalse(unknown.getExceptions().isEmpty() && !unknown.getReader().canRead());
    }

    @Test
    public void guiAndEditOpenTheHudEditorLikeHud() {
        var dispatcher = userDispatcher();
        for (String name : new String[] {"hud", "gui", "edit"}) {
            assertTrue(name, executable(dispatcher, "kung " + name));
        }
        assertFalse(executable(dispatcher, "kung gui extra"));
    }
}
