package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.junit.Test;

public final class HypixelMembershipTest {
    @Test public void partyRefreshKeepsOnlyCurrentMembersAndPreservesObservedUuidsAndLatestCasing() throws Exception {
        var party = tracker(HypixelPartyTracker.class);
        UUID alice = UUID.randomUUID();
        party.observeOnlinePlayer("Alice", alice);
        observe(party, "Party Members (2)");
        observe(party, "Party Leader: [MVP+] Alice");
        observe(party, "Party Members: Bob");
        UUID bob = party.partyPlayerUuid("bOB");
        assertEquals(Set.of("Alice", "Bob"), party.knownPartyPlayerNames());
        assertEquals(alice, party.partyPlayerUuid("Alice"));
        assertEquals("alice", party.partyPlayerName(alice));
        assertTrue(party.isKnownPartyPlayer("ALICE"));

        observe(party, "aLICE joined the party.");
        assertEquals(2, party.partyPlayerCount());
        var snapshot = party.knownPartyPlayerNames();
        assertEquals(Set.of("aLICE", "Bob"), snapshot);
        observe(party, "Party Members (1)");
        assertEquals(0, party.partyPlayerCount());
        assertNull(party.partyPlayerUuid("Alice"));
        assertEquals(alice, party.observedOnlinePlayerUuid("Alice"));
        observe(party, "Party Leader: BOB");
        assertEquals(Set.of("BOB"), party.knownPartyPlayerNames());
        assertEquals(bob, party.partyPlayerUuid("bob"));
        assertEquals(Set.of("aLICE", "Bob"), snapshot);
        observe(party, "bob has left the party.");
        assertTrue(party.knownPartyPlayerNames().isEmpty());
        observe(party, "Alice joined the dungeon group!");
        assertEquals(alice, party.partyPlayerUuid("alice"));
        observe(party, "The party was disbanded.");
        assertEquals(0, party.partyPlayerCount());
    }

    @Test public void guildChatListsAndDeparturesShareCaseInsensitiveMembershipWithLatestDisplayNames() throws Exception {
        var guild = tracker(HypixelGuildTracker.class);
        assertTrue(guild.shouldRefreshMemberList());
        observe(guild, "Guild > [MVP+] Alice: hello");
        observe(guild, "Guild > Bob joined.");
        assertEquals(Set.of("Alice", "Bob"), guild.knownGuildMemberNames());
        observe(guild, "Guild Members");
        observe(guild, "[MVP+] ALICE ● bob Offline");
        assertEquals(Set.of("ALICE", "bob"), guild.knownGuildMemberNames());
        assertFalse(guild.shouldRefreshMemberList());
        observe(guild, "Guild > aLiCe left.");
        assertEquals(Set.of("bob"), guild.knownGuildMemberNames());
        observe(guild, "Guild > BOB left.");
        assertTrue(guild.knownGuildMemberNames().isEmpty());
        assertTrue(guild.shouldRefreshMemberList());
    }

    private static <T> T tracker(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void observe(Object tracker, String message) throws Exception {
        boolean party = tracker instanceof HypixelPartyTracker;
        var method = tracker.getClass().getDeclaredMethod("observeMessage",
            party ? new Class<?>[] {Minecraft.class, String.class} : new Class<?>[] {String.class});
        method.setAccessible(true);
        method.invoke(tracker, party ? new Object[] {null, message} : new Object[] {message});
    }
}
