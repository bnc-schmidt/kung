package com.github.beng420.kung.command;

import static com.github.beng420.kung.command.CommandTestSupport.kung;
import static org.junit.Assert.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public final class RoomPrinceCommandTest {
    @Test public void trueAndFalseResolveToExecutableCommandsWithTheRequestedValue() {
        var dispatcher = dispatcher();
        for (boolean prince : new boolean[] {true, false}) {
            String input = "kung room prince " + prince;
            var parsed = dispatcher.parse(input, null);
            assertTrue(parsed.getExceptions().isEmpty());
            assertFalse(parsed.getReader().canRead());
            assertNotNull(parsed.getContext().getCommand());
            assertEquals(prince, BoolArgumentType.getBool(parsed.getContext().build(input), "prince"));
        }
    }

    @Test public void invalidOrMissingBooleansCannotExecute() {
        var dispatcher = dispatcher();
        var invalid = dispatcher.parse("kung room prince yes", null);
        assertFalse(invalid.getExceptions().isEmpty());
        assertNull(invalid.getContext().getCommand());
        var missing = dispatcher.parse("kung room prince", null);
        assertNull(missing.getContext().getCommand());
    }

    @Test public void projectStatusAndOffResolveToTheirLiteralCommands() {
        var dispatcher = dispatcher();
        for (String input : new String[] {"kung room project", "kung room project status", "kung room project off"}) {
            var parsed = dispatcher.parse(input, null);
            assertTrue(parsed.getExceptions().isEmpty());
            assertFalse(parsed.getReader().canRead());
            assertNotNull(parsed.getContext().getCommand());
            assertThrows(IllegalArgumentException.class,
                () -> StringArgumentType.getString(parsed.getContext().build(input), "path"));
        }
    }

    @Test public void projectPathKeepsSpacesAndWindowsSeparators() {
        var dispatcher = dispatcher();
        for (String path : new String[] {"D:/Downloads/macros/kung", "D:\\My Projects\\kung"}) {
            String input = "kung room project " + path;
            var parsed = dispatcher.parse(input, null);
            assertTrue(parsed.getExceptions().isEmpty());
            assertFalse(parsed.getReader().canRead());
            assertNotNull(parsed.getContext().getCommand());
            assertEquals(path, StringArgumentType.getString(parsed.getContext().build(input), "path"));
        }
    }

    private static CommandDispatcher<FabricClientCommandSource> dispatcher() {
        return kung(root -> RoomLearningCommandGroup.register(root, null));
    }
}
