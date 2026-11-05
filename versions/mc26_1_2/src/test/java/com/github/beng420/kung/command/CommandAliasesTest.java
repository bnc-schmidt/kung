package com.github.beng420.kung.command;

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
}
