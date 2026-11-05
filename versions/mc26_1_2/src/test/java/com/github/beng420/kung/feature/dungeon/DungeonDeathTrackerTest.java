package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class DungeonDeathTrackerTest {
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAMMATE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Map<String, UUID> ROSTER = Map.of("Beng114", SELF, "Awesomeness_Boy", TEAMMATE);

    @Test public void rejectsTheRecordedPartyEchoThatInventedAPlayerNamedParty() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        String echo = "Party > [VIP+] guans: [Skyblocker] ☠ Bdlt was killed by Crypt Dreadlord and became a ghost.";
        assertNull(tracker.observeMessage(echo, DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 0L));
        assertNull(tracker.observeMessage("Party > [MVP+] Beng114: [Kung] Party died 1 time | Total Deaths: 1",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 1L));
        assertEquals(0, tracker.totalDeaths());
        var death = tracker.observeMessage("☠ Awesomeness_Boy died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 100L);
        assertNotNull(death);
        assertEquals(TEAMMATE, death.playerId());
        assertEquals(1, death.playerDeaths());
        assertEquals(1, death.totalDeaths());
    }

    @Test public void acceptsActualSelfAndTeammateSystemDeathsOnlyForKnownRosterPlayers() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        var self = tracker.observeMessage("§c☠ You were killed by Lost Adventurer and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 0L);
        assertEquals(SELF, self.playerId());
        assertNull(tracker.observeMessage("☠ UnknownPlayer was killed by Crypt Dreadlord and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 1L));
        assertEquals(1, tracker.totalDeaths());
        var teammate = tracker.observeMessage("☠ Awesomeness_Boy disconnected and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 1L);
        assertEquals(TEAMMATE, teammate.playerId());
        assertEquals(2, tracker.totalDeaths());
    }

    @Test public void rejectsSpoofedChatAndActionbarEvenIfTheBodyIsAnExactDeathMessage() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        String exact = "☠ Awesomeness_Boy died and became a ghost.";
        for (var source : new DungeonDeathTracker.MessageSource[] {
            DungeonDeathTracker.MessageSource.CHAT, DungeonDeathTracker.MessageSource.ACTIONBAR
        }) assertNull(tracker.observeMessage(exact, source, SELF, ROSTER::get, 0L));
        for (String line : new String[] {
            "[MVP+] Beng114: " + exact, "[BOSS] Storm: " + exact,
            "Party > " + exact, "☠ Beng114 says: You died and became a ghost.",
            "You died!", "☠ Party > [VIP+] guans: Bdlt died and became a ghost."
        }) assertNull(line, tracker.observeMessage(line, DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 0L));
        assertEquals(0, tracker.totalDeaths());
    }

    @Test public void sameDeathSeenAsSelfAndNamedMessagesOnAdjacentTicksCountsOnce() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        assertNotNull(tracker.observeMessage("☠ You were killed by Lost Adventurer and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 100L));
        assertNull(tracker.observeMessage("☠ Beng114 died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 101L));
        assertEquals(1, tracker.playerDeaths(SELF));
        assertNotNull(tracker.observeMessage("☠ Beng114 died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 200L));
        assertEquals(2, tracker.playerDeaths(SELF));
    }

    @Test public void serverTotalAndChatReconcileInEitherOrderWithoutDoubleCounting() {
        for (boolean serverFirst : new boolean[] {false, true}) {
            DungeonDeathTracker tracker = new DungeonDeathTracker();
            if (serverFirst) tracker.observeTeamTotal(1);
            tracker.observeMessage("☠ You were killed by Lost Adventurer and became a ghost.",
                DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 0L);
            tracker.observeTeamTotal(1);
            tracker.observePlayerTotal(SELF, 1);
            assertEquals(1, tracker.totalDeaths());
            assertEquals(1, tracker.playerDeaths(SELF));
            assertEquals(0, tracker.unattributedDeaths());
            tracker.observeTeamTotal(0);
            tracker.observePlayerTotal(SELF, 0);
            assertEquals(1, tracker.totalDeaths());
        }
    }

    @Test public void unobservedDeathsStayUnattributedInsteadOfCreatingFakePlayers() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        tracker.observeTeamTotal(2);
        assertEquals(2, tracker.unattributedDeaths());
        tracker.observeMessage("☠ Awesomeness_Boy died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 0L);
        assertEquals(2, tracker.totalDeaths());
        assertEquals(1, tracker.unattributedDeaths());
        tracker.observePlayerTotal(SELF, 1);
        assertEquals(2, tracker.totalDeaths());
        assertEquals(0, tracker.unattributedDeaths());
        tracker.reset();
        assertEquals(0, tracker.totalDeaths());
        assertEquals(0, tracker.playerDeaths(TEAMMATE));
    }

    @Test public void countersAcceptOnlyExactServerStatFields() {
        assertEquals(2, DungeonDeathTracker.teamTotal("§cTeam Deaths: 2"));
        assertEquals(-1, DungeonDeathTracker.teamTotal("Deaths: 2"));
        assertEquals(-1, DungeonDeathTracker.teamTotal("Party > [MVP+] Beng114: Total Deaths: 2"));
        assertEquals(-1, DungeonDeathTracker.teamTotal("[Kung] Party died 1 time | Total Deaths: 1"));
    }

    @Test public void resolvingADuplicatePlayerIdentityMergesCountsInsteadOfAddingThem() {
        DungeonDeathTracker tracker = new DungeonDeathTracker();
        UUID temporary = UUID.fromString("00000000-0000-0000-0000-000000000003");
        tracker.observeMessage("☠ You died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, temporary, ROSTER::get, 100L);
        tracker.observeMessage("☠ Beng114 died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 101L);
        tracker.observePlayerTotal(temporary, 1);
        tracker.observePlayerTotal(SELF, 1);
        tracker.observeTeamTotal(1);
        tracker.remapPlayer(temporary, SELF);
        assertEquals(1, tracker.totalDeaths());
        assertEquals(1, tracker.playerDeaths(SELF));
        assertEquals(0, tracker.playerDeaths(temporary));
        assertNull(tracker.observeMessage("☠ You died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 102L));
        tracker.observeMessage("☠ You died and became a ghost.",
            DungeonDeathTracker.MessageSource.SYSTEM, SELF, ROSTER::get, 200L);
        assertEquals(2, tracker.playerDeaths(SELF));
        assertEquals(2, tracker.totalDeaths());
    }
}
