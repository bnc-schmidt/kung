package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class DungeonStatisticsSourcesTest {
    private static final UUID SELF = new UUID(0, 1);
    private static final UUID TEAMMATE = new UUID(0, 2);

    private static DungeonRunStats run() {
        var stats = new DungeonRunStats();
        stats.rememberSelf(TEAMMATE, "Awesomeness_Boy");
        stats.rememberSelf(SELF, "Beng114");
        return stats;
    }

    @Test public void sharedIntegerNeverBelongsToSelfOrThePreviousPartyRow() {
        var stats = run();
        stats.observeTabLine(null, "[50] Awesomeness_Boy (Mage 50)", TEAMMATE);
        stats.observeTabLine(null, "Player Stats", null);
        stats.observeTabLine(null, "§aSecrets Found: §f7", null);
        stats.observeTabLine(null, "Secrets Found: 80.0%", null);
        assertFalse(stats.playerStats(SELF).hasSecretsFound());
        assertFalse(stats.playerStats(TEAMMATE).hasSecretsFound());
        assertEquals(80.0, stats.secretsPercent(), 0.001);
        assertEquals(7, stats.displayedSecretsFound(55));
        assertEquals(7, stats.displayedSecretsFound(56));
        assertEquals(7, stats.partySecretsFound(stats.summaryPlayers()));
        stats.observeTabLine(null, "Secrets Found: 6", null);
        assertEquals(6, stats.partySecretsFound(stats.summaryPlayers()));
        assertFalse(stats.playerStats(SELF).hasSecretsFound());
    }

    @Test public void partyRoomAndQuotedSecretFieldsNeverCreditSelf() {
        var stats = run();
        stats.observeStatLine(null, "Secrets: 44/55");
        stats.observeStatLine(null, "Party > Beng114: Secrets: 99/100");
        stats.observeStatLine(null, "+1 Gold Essence 4/10 Secrets");
        assertEquals(44, stats.partySecretsFound(stats.summaryPlayers()));
        assertFalse(stats.playerStats(SELF).hasSecretsFound());
        assertEquals(0, stats.roomSecretsFound(0, 0));
        stats.observeStatLine(null, "Secrets: 43/55");
        assertEquals(43, stats.partySecretsFound(stats.summaryPlayers()));
    }

    @Test public void exactSecretFieldGrammarKeepsCountersSeparate() {
        assertEquals(12, DungeonSecretCounts.partyFound("Secrets Found: 12"));
        assertEquals(-1, DungeonSecretCounts.partyFound("Secrets Found: 80.0%"));
        assertEquals(-1, DungeonSecretCounts.partyFound("Party > Beng114: Secrets Found: 12"));
        assertNull(DungeonSecretCounts.party("4/10 Secrets"));
        assertNull(DungeonSecretCounts.party("Beng114 Secrets: 12/55"));
        assertEquals(new DungeonSecretCounts.Fraction(12, 55), DungeonSecretCounts.party("Secrets: 12/55"));
        assertEquals(44, DungeonSecretCounts.fromPercent(80.0, 55));
        assertEquals(-1, DungeonSecretCounts.fromPercent(80.0, 56));
        assertEquals(-1, DungeonSecretCounts.fromPercent(101, 55));
    }

    @Test public void loggedPartyEchoDoesNotRegisterPartyOrCreditADeath() {
        var stats = run();
        stats.observeMessage(null, "Party > [VIP+] guans: [Skyblocker] ☠ Bdlt was killed by Crypt Dreadlord and became a ghost.", 100L);
        assertEquals(0, stats.deaths());
        assertFalse(stats.isKnownDungeonPlayer("Party"));
        assertNull(stats.dungeonPlayerUuid("Party"));
        stats.observeTabLine(null, "Team Deaths: 1", null);
        stats.observeMessage(null, "☠ Awesomeness_Boy died and became a ghost.", 200L);
        stats.observeTabLine(null, "Team Deaths: 1", null);
        assertEquals(1, stats.deaths());
        assertEquals(1, stats.playerStats(TEAMMATE).deaths());
        assertEquals(0, stats.playerStats(SELF).deaths());
        stats.observeMessage(null, "☠ You died and became a ghost.", 300L, DungeonDeathTracker.MessageSource.CHAT);
        assertEquals(1, stats.deaths());
    }

    @Test public void finalServerScoreWinsOverEstimateAndLaterGenericScore() {
        var stats = run();
        stats.observeStatLine(null, "Team Score: 301 (S+)");
        assertEquals(301, stats.score());
        assertEquals(301, stats.score(null, 55));
        stats.observeStatLine(null, "Score: 298");
        stats.observeStatLine(null, "Party > guans: Team Score: 400 (S+)");
        assertEquals(301, stats.score());
        stats.reset();
        assertNotEquals(301, stats.score());
    }

    @Test public void recordedSeptember18RunCountsGhostMessagesWithoutCountingRevives() {
        var stats = new DungeonRunStats();
        var names = List.of("Beng114", "_FabledHyperion_", "FLC_x1", "Frostelle", "Loveisla");
        for (int i = 1; i < names.size(); i++) stats.rememberSelf(new UUID(0, i + 1), names.get(i));
        stats.rememberSelf(SELF, names.getFirst());
        // Received chat from the matching latest.log fills the rate-limited trace's message gaps.
        String messages = """
            15:35:09|0|❣ You are reviving _FabledHyperion_!
            15:35:09|1|☠ _FabledHyperion_ disconnected and became a ghost.
            15:35:19|1|☠ _FabledHyperion_ reconnected.
            15:35:20|1|❣ FLC_x1 is reviving _FabledHyperion_!
            15:35:28|1|❣ _FabledHyperion_ was revived by FLC_x1!
            15:36:06|2|☠ _FabledHyperion_ was killed by Stormy Skull and became a ghost.
            15:36:22|2|❣ FLC_x1 is reviving _FabledHyperion_!
            15:36:28|2|❣ _FabledHyperion_ was revived by FLC_x1!
            15:36:42|3|☠ _FabledHyperion_ was killed by Stormy Mute and became a ghost.
            15:36:54|4|☠ FLC_x1 was killed by Withermancer and became a ghost.
            15:36:55|4|❣ FLC_x1 is reviving FLC_x1!
            15:37:01|4|❣ FLC_x1 was revived by FLC_x1!
            15:37:05|4|❣ _FabledHyperion_ was revived by Tyene the Fairy!
            15:37:51|5|☠ _FabledHyperion_ was killed by Maxor and became a ghost.
            15:37:51|5|❣ FLC_x1 is reviving _FabledHyperion_!
            15:37:59|5|❣ _FabledHyperion_ was revived by FLC_x1!
            15:38:00|6|☠ You were killed by Maxor and became a ghost.
            15:38:00|6|Your Revive Stone revived you and broke!
            15:38:04|7|☠ Frostelle was killed by Maxor and became a ghost.
            15:38:06|7|❣ Beng114 was revived by Beng114!
            15:38:07|8|☠ Loveisla was killed by Maxor and became a ghost.
            15:38:10|8|❣ Loveisla is reviving Loveisla!
            15:38:11|9|☠ _FabledHyperion_ disconnected and became a ghost.
            15:38:11|9|❣ Frostelle was revived by Frostelle!
            15:38:12|10|☠ You were killed by Maxor and became a ghost.
            15:38:12|10|Your Revive Stone revived you and broke!
            15:38:15|10|❣ Loveisla was revived by Loveisla!
            15:38:19|10|❣ Beng114 was revived by Beng114!
            15:38:25|11|☠ You died to a mob and became a ghost.
            15:38:25|12|☠ FLC_x1 was killed by Maxor and became a ghost.
            15:38:34|13|☠ Loveisla was killed by Maxor and became a ghost.
            15:38:41|13|❣ Loveisla was revived by Loveisla!
            15:38:53|13|❣ FLC_x1 was revived by FLC_x1!
            15:39:11|14|☠ FLC_x1 was killed by Storm and became a ghost.
            15:39:22|15|☠ Loveisla was killed by Storm and became a ghost.
            15:39:29|15|❣ Loveisla was revived by Loveisla! §8(§7x§r2§8)
            15:39:30|16|☠ Loveisla died to a mob and became a ghost.
            15:39:47|16|❣ FLC_x1 was revived by FLC_x1!
            15:39:51|17|☠ Frostelle was killed by Storm and became a ghost.
            15:39:58|18|☠ FLC_x1 was killed by Storm and became a ghost.
            """;
        for (String line : messages.lines().toList()) {
            String[] fields = line.split("\\|", 3);
            long tick = java.time.LocalTime.parse(fields[0]).toSecondOfDay() * 20L;
            stats.observeMessage(null, fields[2], tick);
            assertEquals(line, Integer.parseInt(fields[1]), stats.deaths());
        }
        assertEquals(5, stats.dungeonPlayersInOrder().size());
        int[] expectedDeaths = {3, 5, 4, 2, 4};
        for (int i = 0; i < names.size(); i++) {
            assertEquals(names.get(i), expectedDeaths[i], stats.playerStats(new UUID(0, i + 1)).deaths());
        }
    }

    @Test public void onlyKnownPlayersOwnSyncReportsCanSupplyPersonalSecrets() {
        var stats = run();
        stats.playerStats(SELF).setApiRunSecretsFound(7);
        stats.mergeRemoteLivePlayers(List.of(
            report("Party", 50, "Party", 10),
            report("Awesomeness_Boy", 99, "Beng114", 10),
            report("Beng114", 90, "Beng114", 10),
            report("Awesomeness_Boy", -1, "Awesomeness_Boy", 11)));
        assertFalse(stats.playerStats(TEAMMATE).hasSecretsFound());
        assertEquals(7, stats.playerStats(SELF).secretsFound());
        assertNull(stats.dungeonPlayerUuid("Party"));
        stats.mergeRemoteLivePlayers(List.of(report("Awesomeness_Boy", 9, "Awesomeness_Boy", 12)));
        assertEquals(9, stats.playerStats(TEAMMATE).secretsFound());
        assertEquals(16, stats.partySecretsFound(stats.summaryPlayers()));
    }

    @Test public void missingRemoteCountsAreUnknownInsteadOfZero() {
        var stats = run();
        assertTrue(DungeonRunStats.playerStatsSummaryLine(stats.playerStats(TEAMMATE), 44).contains("?/44 Secrets"));
        stats.playerStats(SELF).setApiRunSecretsFound(0);
        assertTrue(DungeonRunStats.playerStatsSummaryLine(stats.playerStats(SELF), 44).contains("0/44 Secrets"));
    }

    @Test public void legacySharedTabReportsCannotReintroduceFalsePersonalCounts() {
        var stats = run();
        stats.mergeRemoteLivePlayers(List.of(
            new DungeonRoomDataSyncClient.LivePlayerReport("Awesomeness_Boy", 52, 0, "Awesomeness_Boy", 100L),
            new DungeonRoomDataSyncClient.LivePlayerReport("Awesomeness_Boy", 52, 0, "Awesomeness_Boy", 101L, "PERSONAL_TAB")));
        assertFalse(stats.playerStats(TEAMMATE).hasSecretsFound());
        stats.mergeRemoteLivePlayers(List.of(report("Awesomeness_Boy", 9, "Awesomeness_Boy", 102L)));
        assertEquals(9, stats.playerStats(TEAMMATE).secretsFound());
        assertFalse(stats.playerStats(TEAMMATE).hasOwnRunSecrets()); // No forwarding another client's count.
        stats.playerStats(SELF).setApiRunSecretsFound(7);
        assertTrue(stats.playerStats(SELF).hasOwnRunSecrets());
    }

    @Test public void summaryExplainsMissingPersonalSourcesWithoutInventingZeros() {
        var stats = run();
        assertEquals("Personal secrets: 0/2 available. No Hypixel API key or Kung server set.",
            DungeonRunStats.personalSecretAvailability(stats.summaryPlayers(), false));
        stats.playerStats(SELF).setApiRunSecretsFound(0);
        assertEquals("Personal secrets: 1/2 available. Missing player data was not received.",
            DungeonRunStats.personalSecretAvailability(stats.summaryPlayers(), true));
        stats.mergeRemoteLivePlayers(List.of(report("Awesomeness_Boy", 9, "Awesomeness_Boy", 102L)));
        assertEquals("", DungeonRunStats.personalSecretAvailability(stats.summaryPlayers(), true));
    }

    @Test public void syncDecoderPreservesPersonalSourceAndKeepsLegacyReportsUnverified() throws Exception {
        var decode = DungeonRoomDataSyncClient.class.getDeclaredMethod("parseLiveSnapshot", String.class);
        decode.setAccessible(true);
        var snapshot = (DungeonRoomDataSyncClient.LiveSyncSnapshot) decode.invoke(null, """
            {"clients":[{"player":"Awesomeness_Boy","updatedAt":100,"rooms":[],"players":[
              {"name":"Awesomeness_Boy","secretsFound":52,"deaths":0},
              {"name":"Awesomeness_Boy","secretsFound":9,"secretsSource":"API_DELTA","deaths":0}
            ]}]}
            """);
        assertFalse(snapshot.players().getFirst().hasPersonalSecretSource());
        assertEquals("API_DELTA", snapshot.players().getLast().secretsSource());
        var stats = run();
        stats.mergeRemoteLivePlayers(snapshot.players());
        assertEquals(9, stats.playerStats(TEAMMATE).secretsFound());
    }

    private static DungeonRoomDataSyncClient.LivePlayerReport report(String name, int count, String source, long time) {
        return new DungeonRoomDataSyncClient.LivePlayerReport(name, count, 0, source, time, "API_DELTA");
    }
}
