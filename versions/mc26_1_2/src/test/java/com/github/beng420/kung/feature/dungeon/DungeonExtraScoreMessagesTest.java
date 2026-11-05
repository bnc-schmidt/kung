package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.Gson;
import org.junit.Test;

public final class DungeonExtraScoreMessagesTest {
    @Test
    public void september19MiddayBonusGapKeepsOverlappingReportsWithoutGuessingExtraCredit() {
        KungDebugRecorder.clear();
        try {
            var stats = new DungeonRunStats();
            stats.configureForFloor(7, true);
            stats.observeTabLine(null, "Completed Rooms: 36/36", null);
            stats.observeScoreboardLine(null, "Cleared: 100%");
            stats.observeTabLine(null, "Crypts: 8", null);
            stats.observeStatLine(null, "Secrets: 46/50");
            stats.observeTabLine(null, "Secrets Found: 92.0%", null);
            stats.observeTabLine(null, "Team Deaths: 1", null);
            // Accepted sync and party report at 12:27:34; server message at 12:27:44.
            stats.observeSkyblockerBonus(DungeonBonusContribution.BAT);
            stats.observeMessage(null, "Party > [MVP+] omeowdy: Bat dead!", 1L);
            stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 201L);
            // omeowdy's party report and the server's own line ten seconds later are two players' Bats.
            assertEquals(302, stats.score(null, 50)); // Captured estimate was 301; three of the four points left.
            assertFalse(stats.mimicKilled());
            assertFalse(stats.princeKilled());
            String trace = KungDebugRecorder.dump();
            assertTrue(trace.contains("source=skyblocker bonus=BAT"));
            assertTrue(trace.contains("message=\"Party > [MVP+] omeowdy: Bat dead!\""));
            assertTrue(trace.contains("message=\"A Bat has been slain. +1 Bonus Score\""));
            stats.observeStatLine(null, "Team Score: 305 (S+)");
            assertEquals(305, stats.score(null, 50));
            stats.observeStatLine(null, "Score: 301");
            assertEquals(305, stats.score(null, 50));
        } finally {
            KungDebugRecorder.clear();
        }
    }

    @Test
    public void september19SkyblockerSyncRestoresTheThreeMissingBonusPoints() throws Exception {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        stats.observeScoreboardLine(null, "Cleared: 97%");
        stats.observeTabLine(null, "Crypts: 3", null);
        stats.observeStatLine(null, "Secrets: 43/52");
        stats.observeTabLine(null, "Secrets Found: 82.7%", null);
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 1L);
        stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 2L);
        assertEquals(297, stats.score(null, 52));

        // Archived Minecraft log: sync received Mimic at 23:55:48, Prince at 23:55:58.
        stats.observeSkyblockerBonus(DungeonBonusContribution.MIMIC);
        assertEquals(299, stats.score(null, 52));
        stats.observeSkyblockerBonus(DungeonBonusContribution.PRINCE);
        assertEquals(300, stats.score(null, 52));
        assertEquals(43, stats.sPlusSecretTarget(null, 52));
        assertFalse(requested(stats, "mimicMessageSent"));
        assertFalse(requested(stats, "princeMessageSent"));

        stats.observeTabLine(null, "Completed Rooms: 36/36", null);
        stats.observeTabLine(null, "Team Deaths: 5", null);
        assertEquals(291, stats.score(null, 52)); // Server breakdown: 91 + 93 + 100 + 7.
        stats.observeStatLine(null, "Team Score: 291 (S)");
        assertEquals(291, stats.score(null, 52)); // No end-of-run correction needed.
    }

    @Test
    public void skyblockerBonusesAreIdempotentSilentAndResetWithTheRun() throws Exception {
        var previous = KungConfig.get().dungeon;
        KungConfig.get().dungeon = new DungeonConfig();
        KungConfig.get().dungeon.setExtraScoreMessagesEnabled(true);
        KungDebugRecorder.clear();
        try {
            var stats = new DungeonRunStats();
            stats.rememberSelf(new java.util.UUID(0, 1), "Beng114");
            for (var bonus : DungeonBonusContribution.values()) {
                stats.observeSkyblockerBonus(bonus);
                stats.observeSkyblockerBonus(bonus);
                assertTrue(KungDebugRecorder.dump().contains("source=skyblocker bonus=" + bonus));
            }
            stats.observeMessage(null, "Party > Beng114: Mimic dead!", 1L);
            stats.observeMessage(null, "A Prince falls. +1 Bonus Score", 2L);
            stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 3L);
            assertTrue(stats.mimicKilled());
            assertTrue(stats.princeKilled());
            assertTrue(stats.batScoreKilled());
            assertFalse(requested(stats, "mimicMessageSent"));
            assertFalse(requested(stats, "princeMessageSent"));
            assertFalse(requested(stats, "batMessageSent"));
            assertEquals("", stats.playerStats(new java.util.UUID(0, 1)).bonusMarkers());
            stats.resetForCountdown();
            assertFalse(stats.mimicKilled());
            assertFalse(stats.princeKilled());
            assertFalse(stats.batScoreKilled());
        } finally {
            KungConfig.get().dungeon = previous;
            KungDebugRecorder.clear();
        }
    }

    @Test
    public void september18ScoreGapMatchesMissingMimicEvidenceWithoutInventingAKill() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        stats.observeScoreboardLine(null, "Cleared: 97%");
        stats.observeTabLine(null, "Crypts: 9", null);
        stats.observeStatLine(null, "Secrets: 45/57");
        stats.observeTabLine(null, "Secrets Found: 78.9%", null);
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 1L);
        stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 2L);
        stats.observeMessage(null, "A Prince falls. +1 Bonus Score", 3L);
        // 16:42:19 in kung-trace-20260918-164256.log, before Noamm's 300 announcement.
        assertFalse(stats.mimicKilled());
        assertEquals(298, stats.score(null, 57));
        assertEquals(48, stats.sPlusSecretTarget(null, 57));

        stats.observeStatLine(null, "Secrets: 46/57");
        stats.observeTabLine(null, "Secrets Found: 80.7%", null);
        assertEquals(299, stats.score(null, 57));
        stats.observeTabLine(null, "Team Deaths: 1", null);
        assertEquals(298, stats.score(null, 57));
        // The final server result wins, but does not identify which missing input caused the gap.
        stats.observeStatLine(null, "Team Score: 300 (S+)");
        assertEquals(300, stats.score(null, 57));
        assertFalse(stats.mimicKilled());
    }

    @Test
    public void livePrinceReportWithoutExclamationRestoresMissingPointAndSecretTarget() throws Exception {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        stats.observeScoreboardLine(null, "Cleared: 97%");
        stats.observeTabLine(null, "Crypts: 9", null);
        stats.observeStatLine(null, "Secrets: 45/57");
        stats.observeTabLine(null, "Secrets Found: 78.9%", null);
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 1L);
        stats.observeMessage(null, "Party > [VIP] gemothic: Mimic dead!", 2L);
        stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 3L);
        assertEquals(299, stats.score(null, 57));
        assertEquals(46, stats.sPlusSecretTarget(null, 57));

        // 22:11:02.054 in kung-trace-20260917-221608.log: no trailing exclamation mark.
        stats.observeMessage(null, "Party > [VIP] gemothic: Prince Killed", 4L);
        assertTrue(stats.princeKilled());
        assertEquals(300, stats.score(null, 57));
        assertEquals(45, stats.sPlusSecretTarget(null, 57));
        assertEquals(0, stats.sPlusSecretsRemaining(null, 57));
        assertFalse(requested(stats, "princeMessageSent"));
        stats.observeMessage(null, "Party > [VIP] gemothic: Prince Killed!", 5L);
        assertEquals(300, stats.score(null, 57));
        stats.observeStatLine(null, "Team Score: 302 (S+)");
        assertEquals(302, stats.score(null, 57));
    }

    @Test
    public void everyPlayersBatKillScoresOnceNoMatterHowManyModsEchoIt() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        // 2026-11-01 01:38:54-01:39:50: each report comes as "Bat Killed!" and "Bat Killed".
        stats.observeSkyblockerBonus(DungeonBonusContribution.BAT);
        stats.observeMessage(null, "Party > [VIP+] EinArtxr: Bat Killed!", 1L);
        stats.observeMessage(null, "Party > [VIP+] EinArtxr: Bat Killed", 2L);
        assertEquals(1, stats.batKills());
        stats.observeMessage(null, "Party > [MVP+] ALonelyDancer: Bat Killed!", 1100L);
        assertEquals(2, stats.batKills());
        stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 1200L);
        assertEquals(3, stats.batKills());
        stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 1300L);
        assertEquals(3, stats.batKills()); // the same player twice is still one point
        stats.reset();
        assertEquals(0, stats.batKills());
    }

    @Test
    public void allBonusKilledSpellingsAllowOptionalExclamationWithoutDoubleCounting() throws Exception {
        for (String bat : new String[] {"Bat", "BatScore", "Bat Score"}) {
            var stats = new DungeonRunStats();
            stats.configureForFloor(7, true);
            for (String suffix : new String[] {"", "!"}) {
                stats.observeMessage(null, "Party > [MVP+] Alice: Mimic Killed" + suffix, 1L);
                stats.observeMessage(null, "Party > [MVP+] Alice: Prince Killed" + suffix, 2L);
                stats.observeMessage(null, "Party > [MVP+] Alice: " + bat + " Killed" + suffix, 3L);
                assertTrue(stats.mimicKilled());
                assertTrue(stats.princeKilled());
                assertTrue(stats.batScoreKilled());
            }
            assertFalse(requested(stats, "mimicMessageSent"));
            assertFalse(requested(stats, "princeMessageSent"));
            assertFalse(requested(stats, "batMessageSent"));
        }
    }

    @Test
    public void mimicTraceSeparatesDisabledOwnDetectionFromPartyEvidenceWithoutEchoes() throws Exception {
        var previous = KungConfig.get().dungeon;
        var config = new DungeonConfig();
        KungConfig.get().dungeon = config;
        KungDebugRecorder.clear();
        try {
            var ownKill = new DungeonRunStats();
            ownKill.observeMimicEspKill(null, "mimic-entity-dead");
            assertTrue(ownKill.mimicKilled());
            assertFalse(requested(ownKill, "mimicMessageSent"));
            assertTrue(KungDebugRecorder.dump().contains("source=mimic-entity-dead first=true"));
            assertTrue(KungDebugRecorder.dump().contains("suppressed=extra-score-messages-off"));

            config.setExtraScoreMessagesEnabled(true);
            var reportedKill = new DungeonRunStats();
            reportedKill.observeMessage(null, "Party > [MVP+] starziiiii: Mimic Killed!", 100L);
            reportedKill.observeMimicEspKill(null, "entity-death");
            assertTrue(reportedKill.mimicKilled());
            assertFalse(requested(reportedKill, "mimicMessageSent"));
            String trace = KungDebugRecorder.dump();
            assertTrue(trace.contains("source=chat first=true"));
            assertTrue(trace.contains("suppressed=reported-kill"));
            assertTrue(trace.contains("source=entity-death first=false"));
            assertTrue(trace.contains("suppressed=already-killed"));
        } finally {
            KungConfig.get().dungeon = previous;
            KungDebugRecorder.clear();
        }
    }

    @Test
    public void switchesGateEachAnnouncementWithoutSuppressingBonusTracking() throws Exception {
        var previous = KungConfig.get().dungeon;
        try {
            for (int switches = 0; switches < 16; switches++) {
                var config = new DungeonConfig();
                KungConfig.get().dungeon = config;
                config.setExtraScoreMessagesEnabled((switches & 8) != 0);
                config.setMimicMessageEnabled((switches & 4) != 0);
                config.setPrinceMessageEnabled((switches & 2) != 0);
                config.setBatMessageEnabled((switches & 1) != 0);
                var stats = new DungeonRunStats();
                stats.observeMimicEspKill(null, "esp");
                stats.observeMessage(null, "A Prince falls. +1 Bonus Score", 1L);
                stats.observeMessage(null, "A Bat has been slain. +1 Bonus Score", 2L);
                assertTrue(stats.mimicKilled());
                assertTrue(stats.princeKilled());
                assertTrue(stats.batScoreKilled());
                // Null client prevents network sends; these flags record the
                // actual run-statistics branches that request an announcement.
                assertEquals(config.extraScoreMessagesEnabled() && config.mimicMessageEnabled(), requested(stats, "mimicMessageSent"));
                assertEquals(config.extraScoreMessagesEnabled() && config.princeMessageEnabled(), requested(stats, "princeMessageSent"));
                assertEquals(config.extraScoreMessagesEnabled() && config.batMessageEnabled(), requested(stats, "batMessageSent"));
            }
        } finally {
            KungConfig.get().dungeon = previous;
        }
    }

    @Test
    public void extraScoreMessagesWorkWithoutEnablingWorldScans() {
        var config = new Gson().fromJson("{}", KungConfig.class);
        config.dungeon.onChange(null);
        config.dungeon.setExtraScoreMessagesEnabled(true);
        var workload = DungeonWorkload.from(config);
        assertTrue(workload.players());
        assertFalse(workload.rooms());
    }

    private static boolean requested(DungeonRunStats stats, String fieldName) throws Exception {
        var field = DungeonRunStats.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(stats);
    }
}
