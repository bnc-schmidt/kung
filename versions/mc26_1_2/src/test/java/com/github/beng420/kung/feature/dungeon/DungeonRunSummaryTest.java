package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class DungeonRunSummaryTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    @Test
    public void playerStatsSwitchGatesTheEntireSummaryWithMapAndOtherConsumersEnabled() {
        var previous = KungConfig.get().dungeon;
        var config = new DungeonConfig();
        KungConfig.get().dungeon = config;
        try {
            config.setEnabled(true);
            config.setExtraScoreMessagesEnabled(true);
            var stats = new DungeonRunStats();
            stats.rememberSelf(ALICE, "Alice");
            stats.observeStatLine(null, "Secrets: 52/58");
            stats.observeStatLine(null, "Team Score: 302 (S+)");
            stats.playerStats(ALICE).setApiRunSecretsFound(12);
            var plan = new DungeonStateTracker().renderPlan();
            var lines = new ArrayList<String>();

            stats.sendRunSummary(plan, String::length, line -> lines.add(line.getString()));
            assertTrue(lines.isEmpty());
            assertEquals(52, stats.partySecretsFound(stats.summaryPlayers()));
            config.setPlayerTrackingEnabled(true);
            stats.sendRunSummary(plan, String::length, line -> lines.add(line.getString()));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Run Stats")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Secrets 52/58")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Player Stats")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Alice") && line.contains("12/52 Secrets")));

            // Each half switches off on its own.
            lines.clear();
            config.setPlayerStatsReportEnabled(false);
            stats.sendRunSummary(plan, String::length, line -> lines.add(line.getString()));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Secrets 52/58")));
            assertFalse(lines.stream().anyMatch(line -> line.contains("Player Stats") || line.contains("Alice")));
            lines.clear();
            config.setPlayerStatsReportEnabled(true);
            config.setRunStatsReportEnabled(false);
            stats.sendRunSummary(plan, String::length, line -> lines.add(line.getString()));
            assertFalse(lines.stream().anyMatch(line -> line.contains("Run Stats") || line.contains("Secrets 52/58")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Alice")));
            config.setRunStatsReportEnabled(true);

            // A summary already prepared before a late toggle must also stay silent.
            lines.clear();
            config.setPlayerTrackingEnabled(false);
            stats.sendRunSummary(plan, String::length, line -> lines.add(line.getString()));
            assertTrue(lines.isEmpty());
            assertEquals(12, stats.playerStats(ALICE).secretsFound());
        } finally {
            KungConfig.get().dungeon = previous;
        }
    }

    @Test
    public void countdownKeepsRosterAndPreparedBaselinesWhileResettingRunCounters() throws Exception {
        var stats = new DungeonRunStats();
        stats.rememberSelf(BOB, "Bob");
        stats.rememberSelf(ALICE, "Alice");
        stats.configureForFloor(7, true);
        var alice = stats.playerStats(ALICE);
        alice.setDungeonClass(DungeonRunStats.DungeonClass.MAGE);
        alice.setTotalSecretsFound(100);
        alice.setSecretsFound(3);
        alice.incrementDeaths(1);
        alice.setRoomClearBounds(1, 2);
        alice.addBonus(DungeonBonusContribution.PRINCE);
        var enrichmentField = DungeonRunStats.class.getDeclaredField("apiEnrichment");
        enrichmentField.setAccessible(true);
        var api = (DungeonApiEnrichment) enrichmentField.get(stats);
        var baselineField = DungeonApiEnrichment.class.getDeclaredField("baselines");
        baselineField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Integer> baselines = (Map<String, Integer>) baselineField.get(api);
        baselines.put("alice", 100);

        stats.resetForCountdown();
        assertEquals(List.of("Alice", "Bob"), stats.summaryPlayers().stream().map(DungeonPlayerStats::name).toList());
        assertEquals(7, stats.floor());
        assertTrue(stats.masterMode());
        assertEquals(DungeonRunStats.DungeonClass.MAGE, alice.dungeonClass());
        assertEquals(100, alice.totalSecretsFound());
        assertEquals(Integer.valueOf(100), baselines.get("alice"));
        assertEquals(0, alice.secretsFound());
        assertEquals(0, alice.deaths());
        assertEquals(0, alice.roomsCleared());
        assertEquals("", alice.bonusMarkers());

        stats.reset();
        assertTrue(stats.summaryPlayers().isEmpty());
        assertTrue(baselines.isEmpty());
    }

    @Test
    public void disconnectedSummaryKeepsSelfFirstWithoutAClientPlayer() {
        var stats = new DungeonRunStats();
        stats.rememberSelf(ALICE, "Alice");
        stats.rememberSelf(BOB, "Bob");
        stats.playerStats(ALICE).setSecretsFound(4);
        stats.playerStats(BOB).setSecretsFound(7);
        var roster = stats.summaryPlayers(); // No Minecraft instance or LocalPlayer is supplied.
        assertEquals(List.of("Bob", "Alice"), roster.stream().map(DungeonPlayerStats::name).toList());
        assertEquals(11, stats.partySecretsFound(roster));
    }

    @Test
    public void floorMetadataIsStructuredAndBossDialogueCannotDowngradeMasterMode() {
        var stats = new DungeonRunStats();
        stats.observeFloorMetadata("Necron's Handle");
        assertEquals(0, stats.floor());
        stats.observeFloorMetadata("The Catacombs - Floor I");
        assertEquals(1, stats.floor());
        stats.configureForFloor(7, true);
        stats.observeFloorMetadata("[BOSS] Necron: All this, for nothing...");
        assertEquals(7, stats.floor());
        assertTrue(stats.masterMode());
    }

    @Test
    public void playerStatsCollectRoomsEvenWhenTheMapIsDisabled() {
        KungConfig config = new Gson().fromJson("{}", KungConfig.class);
        config.dungeon.onChange(() -> { });
        config.dungeon.setPlayerTrackingEnabled(true);
        assertFalse(config.dungeon.enabled());
        assertTrue(DungeonWorkload.from(config).rooms());
        assertTrue(DungeonWorkload.from(config).players());
    }

    @Test
    public void clearBoundsDistinguishSoloGroupAndStalePresence() {
        var attribution = new DungeonRoomClearAttribution();
        attribution.observe(Set.of(0), Set.of(ALICE), null);
        attribution.observe(Set.of(1), Set.of(ALICE, BOB), null);
        attribution.observe(Set.of(2), Set.of(), BOB);
        assertEquals(new DungeonRoomClearAttribution.Bounds(1, 2), attribution.bounds().get(ALICE));
        assertEquals(new DungeonRoomClearAttribution.Bounds(0, 2), attribution.bounds().get(BOB));
    }

    @Test
    public void recognitionMergesPreviouslyUnknownCellsWithoutExtraClears() {
        var attribution = new DungeonRoomClearAttribution();
        attribution.observe(Set.of(0), Set.of(ALICE), null);
        attribution.observe(Set.of(1), Set.of(ALICE), null);
        assertEquals(new DungeonRoomClearAttribution.Bounds(2, 2), attribution.bounds().get(ALICE));
        attribution.observe(Set.of(0, 1, 2), Set.of(BOB), null);
        assertEquals(new DungeonRoomClearAttribution.Bounds(1, 1), attribution.bounds().get(ALICE));
        assertFalse(attribution.bounds().containsKey(BOB));
        assertFalse(attribution.observe(Set.of(0, 1, 2), Set.of(BOB), null));
    }

    @Test
    public void differentClearWitnessesDoNotBecomeCertainSoloCredit() {
        var attribution = new DungeonRoomClearAttribution();
        attribution.observe(Set.of(0), Set.of(ALICE), null);
        attribution.observe(Set.of(1), Set.of(BOB), null);
        attribution.observe(Set.of(0, 1), Set.of(BOB), null);
        assertEquals(new DungeonRoomClearAttribution.Bounds(0, 1), attribution.bounds().get(ALICE));
        assertEquals(new DungeonRoomClearAttribution.Bounds(0, 1), attribution.bounds().get(BOB));
        attribution.remapPlayer(BOB, ALICE);
        assertEquals(new DungeonRoomClearAttribution.Bounds(0, 1), attribution.bounds().get(ALICE));
        attribution.reset();
        assertTrue(attribution.bounds().isEmpty());
    }

    @Test
    public void anUnknownCellLaterIdentifiedAsFairyDoesNotKeepClearCredit() {
        var attribution = new DungeonRoomClearAttribution();
        attribution.observe(Set.of(0), Set.of(ALICE), null);
        attribution.observe(Set.of(1), Set.of(ALICE), null);
        assertTrue(attribution.exclude(Set.of(0)));
        assertEquals(new DungeonRoomClearAttribution.Bounds(1, 1), attribution.bounds().get(ALICE));
        assertFalse(attribution.exclude(Set.of(0)));
    }

    @Test
    public void summaryShowsRunSecretsEstimatedBoundsAndOnlyEarnedMarkers() {
        var alice = new DungeonPlayerStats(ALICE, "Alice");
        alice.setSecretsFound(6);
        alice.setRoomClearBounds(2, 4);
        String plain = DungeonRunStats.playerStatsSummaryLine(alice, 17);
        assertTrue(plain.contains("6/17 Secrets"));
        assertTrue(plain.contains("2-4 Rooms"));
        assertFalse(plain.contains("estimated"));
        assertTrue(plain.endsWith("0 Deaths"));
        alice.addBonus(DungeonBonusContribution.BAT);
        alice.addBonus(DungeonBonusContribution.PRINCE);
        alice.addBonus(DungeonBonusContribution.PRINCE);
        assertTrue(DungeonRunStats.playerStatsSummaryLine(alice, 17).endsWith(" | PB"));
        var bob = new DungeonPlayerStats(BOB, "Bob");
        bob.setSecretsFound(11);
        assertEquals(17, new DungeonRunStats().partySecretsFound(List.of(alice, bob)));
    }

    @Test
    public void genericAndEchoedKillAnnouncementsCannotIdentifyTheKiller() {
        for (String line : List.of("A Prince falls. +1 Bonus Score", "A Bat has been slain. +1 Bonus Score",
            "Party > [MVP+] Alice: Mimic dead!", "Party > Alice: [Kung] Prince dead!",
            "Party > Alice: [Kung] I killed Prince", "Alice: I killed Mimic")) {
            assertNull(line, DungeonBonusContribution.namedClaim(line));
        }
        assertEquals(new DungeonBonusContribution.Claim("Alice", DungeonBonusContribution.MIMIC),
            DungeonBonusContribution.namedClaim("Party > [MVP+] Alice: I killed the Mimic!"));
        assertEquals(new DungeonBonusContribution.Claim("Bob", DungeonBonusContribution.PRINCE),
            DungeonBonusContribution.namedClaim("Party > Alice: Prince was killed by Bob!"));
        assertEquals(new DungeonBonusContribution.Claim("Bob", DungeonBonusContribution.BAT),
            DungeonBonusContribution.namedClaim("Bob killed the Bat!"));
    }
}
