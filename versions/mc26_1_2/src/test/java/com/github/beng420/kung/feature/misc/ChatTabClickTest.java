package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.category.MiscConfig;
import org.junit.Test;

public final class ChatTabClickTest extends MinecraftBootstrapTest {
    @Test
    public void aTabClickSendsItsChannelOnlyWhileChatFollowsTab() {
        assertEquals(ChatChannels.Send.GUILD, ChatChannelsFeature.tabSendTarget(true, ChatChannels.Channel.GUILD, ChatChannels.Send.ALL));
        assertEquals(ChatChannels.Send.ALL, ChatChannelsFeature.tabSendTarget(true, null, ChatChannels.Send.PARTY));
        assertEquals(ChatChannels.Send.COOP, ChatChannelsFeature.tabSendTarget(true, ChatChannels.Channel.COOP, ChatChannels.Send.GUILD));
        // Off: the click only changes what the chat shows.
        assertNull(ChatChannelsFeature.tabSendTarget(false, ChatChannels.Channel.GUILD, ChatChannels.Send.ALL));
        assertNull(ChatChannelsFeature.tabSendTarget(false, null, ChatChannels.Send.PARTY));
    }

    @Test
    public void noClickSendsTheChannelItIsAlreadyInOrOneForTheDmTab() {
        assertNull(ChatChannelsFeature.tabSendTarget(true, ChatChannels.Channel.PARTY, ChatChannels.Send.PARTY));
        assertNull(ChatChannelsFeature.tabSendTarget(true, null, ChatChannels.Send.ALL));
        assertNull(ChatChannelsFeature.tabSendTarget(true, ChatChannels.Channel.DIRECT, ChatChannels.Send.ALL));
    }

    @Test
    public void chatFollowsTabIsOnByDefaultAndSeparateFromTabFollowsChat() {
        MiscConfig config = new MiscConfig();
        assertEquals(true, config.chatChannelsChatFollowsTab());
        assertEquals(false, config.chatChannelsFollowChat());
        config.setChatChannelsChatFollowsTab(false);
        assertEquals(false, config.chatChannelsChatFollowsTab());
        assertEquals(false, config.chatChannelsFollowChat());
    }
}
