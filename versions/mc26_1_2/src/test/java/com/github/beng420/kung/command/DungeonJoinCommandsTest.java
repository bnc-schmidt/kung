package com.github.beng420.kung.command;

import static org.junit.Assert.*;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public class DungeonJoinCommandsTest {
    private static final String[] NUMBERS = {"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN"};

    @Test
    public void everyFloorAndModeSendsItsInstanceName() {
        for (int floor = 1; floor <= 7; floor++) {
            assertEquals("joininstance CATACOMBS_FLOOR_" + NUMBERS[floor - 1], DungeonJoinCommands.serverCommand("f" + floor));
            assertEquals("joininstance MASTER_CATACOMBS_FLOOR_" + NUMBERS[floor - 1], DungeonJoinCommands.serverCommand("m" + floor));
        }
        assertEquals("joininstance CATACOMBS_ENTRANCE", DungeonJoinCommands.serverCommand("entrance"));
    }

    @Test
    public void otherNamesAreNoShortcuts() {
        for (String name : new String[] {"f0", "f8", "m0", "m8", "e", "f", "kuudra", "joininstance"}) {
            assertNull(name, DungeonJoinCommands.serverCommand(name));
        }
    }

    @Test
    public void everyShortcutIsRegisteredAsATopLevelCommand() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        DungeonJoinCommands.register(dispatcher);
        assertEquals(15, dispatcher.getRoot().getChildren().size());
        for (String name : new String[] {"entrance", "f1", "f7", "m1", "m7"}) {
            assertNotNull(name, dispatcher.getRoot().getChild(name));
            assertNotNull(name, dispatcher.getRoot().getChild(name).getCommand());
        }
        assertNull(dispatcher.getRoot().getChild("f8"));
    }
}
