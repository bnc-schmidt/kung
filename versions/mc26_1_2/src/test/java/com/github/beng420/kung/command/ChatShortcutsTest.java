package com.github.beng420.kung.command;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.misc.ChatChannels;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.Test;

public class ChatShortcutsTest {
    @Test
    public void everyShortcutSendsTheChatCommandOfItsChannel() {
        assertEquals("chat party", ChatShortcuts.serverCommand("cp"));
        assertEquals("chat all", ChatShortcuts.serverCommand("ca"));
        assertEquals("chat guild", ChatShortcuts.serverCommand("cg"));
        assertEquals("chat coop", ChatShortcuts.serverCommand("cc"));
    }

    @Test
    public void chatChannelsFollowsTheSwitchTheShortcutSends() {
        // The sent command goes through Chat Channels' own reading of /chat, so the tab state follows a shortcut.
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.chatCommand(ChatShortcuts.serverCommand("cp")));
        assertEquals(ChatChannels.Send.ALL, ChatChannels.chatCommand(ChatShortcuts.serverCommand("ca")));
        assertEquals(ChatChannels.Send.GUILD, ChatChannels.chatCommand(ChatShortcuts.serverCommand("cg")));
        assertEquals(ChatChannels.Send.COOP, ChatChannels.chatCommand(ChatShortcuts.serverCommand("cc")));
    }

    @Test
    public void otherNamesAreNoShortcuts() {
        for (String name : new String[] {"c", "co", "cs", "chat", "cpp", "pc", "gc", "ac"}) {
            assertNull(name, ChatShortcuts.serverCommand(name));
        }
    }

    @Test
    public void everyShortcutIsATopLevelCommandThatTakesNoArguments() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        ChatShortcuts.register(dispatcher);
        assertEquals(4, dispatcher.getRoot().getChildren().size());
        for (String name : new String[] {"cp", "ca", "cg", "cc"}) {
            assertNotNull(name, dispatcher.getRoot().getChild(name));
            assertNotNull(name, dispatcher.getRoot().getChild(name).getCommand());
            assertTrue(name, dispatcher.getRoot().getChild(name).getChildren().isEmpty());
        }
    }
}
