package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.misc.ChatChannels.Channel;
import org.junit.Test;

/** Every line below is a real [message] line from a Kung trace, unless a comment says otherwise. */
public final class ChatChannelsTest {
    @Test
    public void guildLinesAreGuildIncludingJoinsAndLeaves() {
        assertKind(Channel.GUILD, "Guild > [MVP+] swyvy [400]: You Supercrafted Quick Bite I (Book)! (x16)");
        assertKind(Channel.GUILD, "Guild > Night1Emu joined.");
        assertKind(Channel.GUILD, "Guild > Drewdeli1 left.");
        // From the Hypixel wiki, in no trace: officer chat has the same shape as guild chat.
        assertKind(Channel.GUILD, "Officer > [MVP+] Araqz [Mod]: hello");
    }

    @Test
    public void partyLinesAreParty() {
        assertKind(Channel.PARTY, "Party > [MVP+] Beng114: Watcher will move in 1.35s.");
        assertKind(Channel.PARTY, "Party > Big_Foot_14: [Skyblocker] Crypts: 0/5");
        assertKind(Channel.PARTY, "Party > [MVP+] Beng114: [20:24:55] From [MVP+] Beng114: Boop!");
    }

    @Test
    public void coopLinesAreCoop() {
        assertKind(Channel.COOP, "Co-op > [MVP+] Araqz: SKILL LEVEL UP Alchemy 4➜5");
        assertKind(Channel.COOP, "Co-op > [MVP+] yrkuna: .");
    }

    @Test
    public void directMessagesGoBothWaysWithOrWithoutRank() {
        assertKind(Channel.DIRECT, "From [MVP+] Beng114: Boop!");
        assertKind(Channel.DIRECT, "To [MVP+] yrkuna: !inv");
        assertKind(Channel.DIRECT, "From [MVP+] Araqz: ich habe vergessen wie der coop chat funktioniorte");
        // Without a rank Hypixel sends the bare name (the format is known, no trace has such a line).
        assertKind(Channel.DIRECT, "From Beng114: hi");
        assertKind(Channel.DIRECT, "To [VIP+] [Mod] Araqz: hi");
    }

    @Test
    public void otherLinesAreNoChannel() {
        assertKind(null, "[MVP+] Beng114 joined the lobby!");
        assertKind(null, "Party Finder > Beng114 joined the dungeon group! (Archer Level 46)");
        assertKind(null, "Party Finder > Your party has been queued in the dungeon finder!");
        assertKind(null, "-------------- Guild: Message Of The Day --------------");
        assertKind(null, "[616] ♲ [MVP+] Araqz: hello");
        assertKind(null, "");
        assertKind(null, null);
    }

    @Test
    public void stashAndOtherFromToSentencesAreNoDirectMessage() {
        assertKind(null, "From stash: Hard Stone");
        assertKind(null, "From stash: Clean Zombie Commander Chestplate");
        assertKind(null, "To prevent your inventory from getting filled with them, you can store them in the Accessory Bag!");
        // A ranked player may be called stash.
        assertKind(Channel.DIRECT, "From [MVP+] stash: hi");
    }

    @Test
    public void aPlayerCannotFakeAChannelInPublicChat() {
        assertKind(null, "[616] [MVP+] Araqz: Guild > [MVP+] Beng114: hi");
        assertKind(null, "[MVP+] Araqz: Party > hi");
        assertKind(null, "[MVP+] Araqz: From [MVP+] Beng114: hi");
        assertKind(null, "Hello Guild > there");
    }

    @Test
    public void aClockStampInFrontIsIgnored() {
        assertKind(Channel.GUILD, "[12:34:56] Guild > Night1Emu joined.");
        assertKind(Channel.PARTY, "[09:05] Party > [MVP+] Beng114: hi");
        assertKind(Channel.COOP, "[3:04 PM] Co-op > [MVP+] yrkuna: .");
        assertKind(Channel.DIRECT, "[12/31 23:59:59] From [MVP+] Beng114: Boop!");
        assertKind(Channel.GUILD, "[12:34:56]Guild > Night1Emu joined.");
        // The stamp is only a stamp: without a channel after it the line stays public.
        assertKind(null, "[12:34:56] [MVP+] Araqz: Guild > hi");
        // A SkyBlock level is no clock.
        assertKind(null, "[616] Guild > hi");
    }

    @Test
    public void colorCodesAndPaddingDoNotMatter() {
        assertKind(Channel.GUILD, "§2Guild > §b[MVP+] Beng114§f: hi");
        assertKind(Channel.PARTY, "  Party >  [MVP+] Beng114: hi");
    }

    @Test
    public void theSavedChannelComesBackByNameAndAnythingElseIsAll() {
        for (ChatChannels.Send send : ChatChannels.Send.values()) {
            assertEquals(send, ChatChannels.storedSend(send.name()));
        }
        assertEquals(ChatChannels.Send.ALL, ChatChannels.storedSend("NOPE"));
        assertEquals(ChatChannels.Send.ALL, ChatChannels.storedSend(null));
    }

    /** Both notices below are real lines from Kung traces and SkyHanni's logs. */
    @Test
    public void hypixelsSwitchNoticeNamesTheChannel() {
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.switchNotice("You are now in the PARTY channel"));
        assertEquals(ChatChannels.Send.ALL, ChatChannels.switchNotice("You are now in the ALL channel"));
        assertEquals(ChatChannels.Send.GUILD, ChatChannels.switchNotice("You are now in the GUILD channel"));
        assertEquals(ChatChannels.Send.GUILD, ChatChannels.switchNotice("§aYou are now in the §r§6GUILD§r§a channel"));
        // From the Hypixel format, in no trace.
        assertEquals(ChatChannels.Send.OFFICER, ChatChannels.switchNotice("You are now in the OFFICER channel"));
        assertEquals(ChatChannels.Send.COOP, ChatChannels.switchNotice("You are now in the SKYBLOCK CO-OP channel"));
        assertEquals(ChatChannels.Send.ALL,
            ChatChannels.switchNotice("You are not in a party and were moved to the ALL channel."));
        assertNull(ChatChannels.switchNotice("You are now in the MYSTERY channel"));
        assertNull(ChatChannels.switchNotice("[MVP+] Araqz: You are now in the PARTY channel"));
        assertNull(ChatChannels.switchNotice("Party > [MVP+] Araqz: You are now in the PARTY channel"));
        assertNull(ChatChannels.switchNotice(null));
    }

    @Test
    public void theAlreadyNoticeIsExactAndPlayersCannotSendIt() {
        assertTrue(ChatChannels.alreadyNotice("You're already in this channel!"));
        assertTrue(ChatChannels.alreadyNotice("§cYou're already in this channel!"));
        assertFalse(ChatChannels.alreadyNotice("[MVP+] Araqz: You're already in this channel!"));
        assertFalse(ChatChannels.alreadyNotice(null));
    }

    @Test
    public void chatCommandsNameTheChannelTheySwitchTo() {
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.chatCommand("chat party"));
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.chatCommand("/chat Party"));
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.chatCommand("chat p"));
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.chatCommand("pchat"));
        assertEquals(ChatChannels.Send.ALL, ChatChannels.chatCommand("chat all"));
        assertEquals(ChatChannels.Send.ALL, ChatChannels.chatCommand("chat a"));
        assertEquals(ChatChannels.Send.GUILD, ChatChannels.chatCommand("chat guild"));
        assertEquals(ChatChannels.Send.OFFICER, ChatChannels.chatCommand("chat officer"));
        assertEquals(ChatChannels.Send.COOP, ChatChannels.chatCommand("chat coop"));
        for (ChatChannels.Send send : ChatChannels.Send.values()) {
            assertEquals("the command a tab sends is read back", send, ChatChannels.chatCommand(send.command()));
        }
        // One message to a channel does not switch to it.
        assertNull(ChatChannels.chatCommand("pc hello"));
        assertNull(ChatChannels.chatCommand("chat"));
        assertNull(ChatChannels.chatCommand("chat party hello"));
        assertNull(ChatChannels.chatCommand("chat nonsense"));
        assertNull(ChatChannels.chatCommand(null));
    }

    private static void assertKind(Channel expected, String line) {
        assertEquals(line, expected, ChatChannels.classify(line));
        if (expected == null) assertNull(ChatChannels.classify(line));
    }
}
