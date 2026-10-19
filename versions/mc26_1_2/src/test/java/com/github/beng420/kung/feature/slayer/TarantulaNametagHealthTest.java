package com.github.beng420.kung.feature.slayer;

import static org.junit.Assert.*;

import org.junit.Test;

public final class TarantulaNametagHealthTest {
    @Test
    public void nametagsCarryTheRealHealth() {
        // Mobs name their maximum, a slayer boss does not; the entity's own health is full either way.
        assertArrayEquals(new double[] {795_100, 1_200_000},
            TarantulaHelperFeature.nametagHealth("[Lv90] Magma Cube Rider 795.1k/1.2M❤"), 1.0);
        assertArrayEquals(new double[] {9_400_000, -1},
            TarantulaHelperFeature.nametagHealth("☠ Tarantula Broodfather V 9.4M❤"), 1.0);
        assertArrayEquals(new double[] {6_500, 13_000},
            TarantulaHelperFeature.nametagHealth("[Lv55] Zealot 6,500/13,000❤"), 1.0);
        assertNull(TarantulaHelperFeature.nametagHealth("☠ Tarantula Broodfather V"));
        // 2026-10-18 trace: the boss tag names its maximum like a mob and has no "Spawned by" stand any more.
        assertArrayEquals(new double[] {2_000_000, 10_000_000},
            TarantulaHelperFeature.nametagHealth("[Lv396]  Tarantula Broodfather V 2M/10M❤"), 1.0);
        assertArrayEquals(new double[] {0, 20_000_000},
            TarantulaHelperFeature.nametagHealth("[Lv396]  Conjoined Brood 0/20M❤"), 1.0);
    }

    @Test
    public void untaggedBossesAreOursOnlyAfterOurSpawnMessageOrWhileWeFightThem() {
        assertTrue(TarantulaHelperFeature.isBossSpawnMessage("slayer boss! the tarantula broodfather v spawned!"));
        assertFalse(TarantulaHelperFeature.isBossSpawnMessage("slayer miniboss! the primordial jockey spawned!"));
        assertFalse(TarantulaHelperFeature.isBossSpawnMessage("slayer boss! the revenant horror v spawned!"));

        var quiet = java.util.List.of("SKYBLOCK", "Slayer Quest", "Tarantula Broodfather V", "(1,000/10k) Combat");
        var boss = java.util.List.of("SKYBLOCK", "Slayer Quest", "Tarantula Broodfather V", "Slay the boss!");
        assertTrue(TarantulaHelperFeature.sidebarShowsBoss(boss));
        assertFalse(TarantulaHelperFeature.sidebarShowsBoss(quiet));

        // 2026-10-18 trace: the cocoon of the last boss completes its quest after the next boss spawned. The sidebar
        // keeps saying "Slay the boss!", so that late message cannot make the helper forget the new fight.
        assertTrue(TarantulaHelperFeature.fightIsOn(boss, -1)); // no spawn message needed, e.g. joined mid-fight
        assertFalse(TarantulaHelperFeature.fightIsOn(quiet, 60_000)); // sidebar read: the quest line, no boss alive
        assertTrue(TarantulaHelperFeature.fightIsOn(quiet, 28)); // sidebar not updated yet, our message just came
        assertTrue(TarantulaHelperFeature.fightIsOn(java.util.List.of(), 200_000)); // no sidebar at all: message decides
        assertFalse(TarantulaHelperFeature.fightIsOn(java.util.List.of(), 400_000));
        assertFalse(TarantulaHelperFeature.fightIsOn(java.util.List.of(), -1));

        assertTrue(TarantulaHelperFeature.adoptsUntagged(false, true)); // our boss spawned, however far
        assertFalse(TarantulaHelperFeature.adoptsUntagged(false, false)); // someone else's boss
        assertTrue(TarantulaHelperFeature.adoptsUntagged(true, false)); // already fighting: the Conjoined Brood rebind
    }
}
