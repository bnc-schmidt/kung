package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Test;

public final class DungeonRunRosterRegressionTest {
    private static final List<String> CURRENT = List.of("Beng114", "MisterPaladin", "GlossyPenguin", "marps", "DeviousVI");

    @After public void clearParty() throws Exception { partyMessage("You left the party."); }

    @Test public void screenshotRosterExcludesThreeOldPartyMembersWithoutDroppingCurrentPlayers() throws Exception {
        partyMessage("Party Members (8)");
        partyMessage("Party Leader: MisterPaladin");
        partyMessage("Party Members: ABCtheBass ImBlatte RaketeX Beng114 GlossyPenguin marps DeviousVI");
        assertEquals(8, HypixelPartyTracker.INSTANCE.partyPlayerCount());
        var stats = currentRun();
        assertEquals(Set.copyOf(CURRENT), names(stats));
        assertEquals(5, stats.summaryPlayers().size());
        assertEquals(5, stats.knownDungeonPlayerUuids().size());
        for (String stale : List.of("ABCtheBass", "ImBlatte", "RaketeX")) {
            assertFalse(stats.isKnownDungeonPlayer(stale));
            assertNull(stats.trackedPlayerUuid(stale));
        }
        // A party change after completion must not rewrite the run's participants.
        partyMessage("You left the party.");
        assertEquals(Set.copyOf(CURRENT), names(stats));
    }

    @Test public void sharedTabCountDoesNotCreditAll52SecretsToBeng() {
        var stats = currentRun();
        stats.observeTabLine(null, "Secrets Found: 52", null);
        stats.observeStatLine(null, "Secrets: 52/58");
        assertEquals(52, stats.partySecretsFound(stats.summaryPlayers()));
        for (var player : stats.summaryPlayers()) assertFalse(player.name(), player.hasSecretsFound());
        // A valid personal delta must survive later shared tab snapshots.
        stats.playerStats(uuid("Beng114")).setApiRunSecretsFound(8);
        stats.observeTabLine(null, "Secrets Found: 53", null);
        assertEquals(8, stats.playerStats(uuid("Beng114")).secretsFound());
        assertEquals(53, stats.partySecretsFound(stats.summaryPlayers()));
    }

    @Test public void sixthClassRowCannotExpandTheRunRoster() {
        var stats = currentRun();
        assertNull(stats.observeTabLine(null, "Outsider (Mage 50)", uuid("Outsider")));
        assertFalse(stats.isKnownDungeonPlayer("Outsider"));
        assertEquals(5, stats.dungeonPlayerCount());
        assertEquals(Set.copyOf(CURRENT), names(stats));
    }

    @Test public void uuidResolutionKeepsOneRowAndCountersWhileInstanceResetDropsOldRoster() {
        var stats = currentRun();
        var oldUuid = uuid("GlossyPenguin");
        var realUuid = uuid("resolved-GlossyPenguin");
        stats.playerStats(oldUuid).setApiRunSecretsFound(8);
        HypixelPartyTracker.INSTANCE.observeOnlinePlayer("GlossyPenguin", realUuid);
        stats.observeTabLine(null, "GlossyPenguin (Mage 50)", oldUuid);
        assertEquals(5, stats.summaryPlayers().size());
        assertEquals(realUuid, stats.trackedPlayerUuid("GlossyPenguin"));
        assertEquals(8, stats.playerStats(realUuid).secretsFound());
        assertNull(stats.playerStats(oldUuid));
        stats.resetForCountdown();
        assertEquals(Set.copyOf(CURRENT), names(stats));
        stats.reset();
        stats.rememberSelf(uuid("Beng114"), "Beng114");
        assertEquals(Set.of("Beng114"), names(stats));
        assertFalse(stats.isKnownDungeonPlayer("GlossyPenguin"));
    }

    private static DungeonRunStats currentRun() {
        var stats = new DungeonRunStats();
        stats.rememberSelf(uuid("Beng114"), "Beng114");
        for (String name : CURRENT) {
            HypixelPartyTracker.INSTANCE.observeOnlinePlayer(name, uuid(name));
            stats.observeTabLine(null, name + " (Mage 50)", uuid(name));
        }
        return stats;
    }

    private static Set<String> names(DungeonRunStats stats) {
        return stats.summaryPlayers().stream().map(DungeonPlayerStats::name).collect(Collectors.toSet());
    }

    private static UUID uuid(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }

    private static void partyMessage(String message) throws Exception {
        var method = HypixelPartyTracker.class.getDeclaredMethod("observeMessage", net.minecraft.client.Minecraft.class, String.class);
        method.setAccessible(true);
        method.invoke(HypixelPartyTracker.INSTANCE, null, message);
    }
}
