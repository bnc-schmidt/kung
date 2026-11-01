package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatContext;
import org.junit.Test;

public final class ChatChannelTest {
    @Test
    public void partyGuildPrivateAndPublicLinesKeepTheirChannel() {
        assertContext(ChatChannel.PARTY, "Araqz", "!ca50", "Party > [MVP+] Araqz: !ca50");
        assertContext(ChatChannel.GUILD, "Araqz", "!ca50", "Guild > [MVP+] Araqz [Mod]: !ca50");
        assertContext(ChatChannel.PRIVATE, "Araqz", "!ca50", "From [MVP+] Araqz: !ca50");
        assertContext(ChatChannel.ALL, "Araqz", "!ca50", "[616] ♲ [MVP+] Araqz: !ca50");
        // A ">" in the message is the message, not a channel.
        assertContext(ChatChannel.ALL, "Araqz", "a > b", "[MVP+] Araqz: a > b");
    }

    @Test
    public void aCoopLineIsItsOwnChannelAnsweredWithCc() {
        assertContext(ChatChannel.COOP, "Araqz", "!ca50", "Co-op > [MVP+] Araqz: !ca50");
        assertContext(ChatChannel.COOP, "Araqz", "!ca50", "[12:34:56] Co-op > Araqz: !ca50");
        assertEquals("cc", ChatChannel.COOP.commandPrefix(""));
    }

    @Test
    public void anOfficerLineIsNeverTakenForPublicChat() {
        // The reply would have gone to /ac, in front of the whole lobby.
        assertNull(ChatCommandsFeature.chatContext(null, "Officer > [MVP+] Araqz: !ca50"));
    }

    @Test
    public void coopFollowsTheDefaultChannelsUntilACommandHasItsOwn() {
        MiscConfig config = new MiscConfig();
        config.setChatCommandsEnabled(true);
        assertTrue("on by default, like party and guild", ChatCommand.CA.isEnabled(config, ChatChannel.COOP));
        config.setDefaultCoopCommandsEnabled(false);
        assertFalse(ChatCommand.CA.isEnabled(config, ChatChannel.COOP));
        assertFalse(ChatCommand.C.isEnabled(config, ChatChannel.COOP));
        assertTrue(ChatCommand.CA.isEnabled(config, ChatChannel.PARTY));
        // Its own channels win once the command stops following the defaults.
        config.setCa50UseDefaultChannels(false);
        assertTrue(ChatCommand.CA.isEnabled(config, ChatChannel.COOP));
        assertFalse(ChatCommand.C.isEnabled(config, ChatChannel.COOP));
    }

    private static void assertContext(ChatChannel channel, String sender, String message, String line) {
        ChatContext context = ChatCommandsFeature.chatContext(null, line);
        assertEquals(line, channel, context.channel());
        assertEquals(line, sender, context.senderName());
        assertEquals(line, message, context.message());
    }
}
