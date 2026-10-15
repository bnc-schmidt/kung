package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.Test;

public final class DungeonMimicPersistenceTest {
    @Test public void disappearingAndReloadingChunkDoesNotEraseOrDuplicateChestEvidence() {
        var memory = new DungeonMimicChestMemory();
        var chest = new BlockPos(-122, 78, -22);
        memory.observe(List.of(chest), pos -> true);
        for (int scan = 0; scan < 80; scan++) memory.observe(List.of(), pos -> true);
        assertEquals(List.of(chest), memory.positions());
        assertEquals(chest, memory.mapChest(pos -> "mimic-room"));
        memory.observe(List.of(chest), pos -> true);
        assertEquals(List.of(chest), memory.positions());
    }

    @Test public void lateRoomIdentificationCanRejectAStaticChestWithoutLosingTheMimic() {
        var memory = new DungeonMimicChestMemory();
        var mimic = new BlockPos(-122, 78, -22);
        var staticChest = new BlockPos(-185, 72, -185);
        memory.observe(List.of(mimic, staticChest), pos -> true);
        assertNull(memory.mapChest(pos -> pos.equals(mimic) ? "mimic-room" : "static-room"));
        memory.observe(List.of(), pos -> !pos.equals(staticChest));
        assertEquals(List.of(mimic), memory.positions());
        assertEquals(mimic, memory.mapChest(pos -> "mimic-room"));
        memory.clear();
        assertTrue(memory.positions().isEmpty());
        assertNull(memory.mapChest(pos -> "old-room"));
    }

    @Test public void remembersImmutablePositionAndIndependentSnapshot() {
        var memory = new DungeonMimicChestMemory();
        var mutable = new BlockPos.MutableBlockPos(-122, 78, -22);
        memory.observe(List.of(mutable), pos -> true);
        var saved = memory.positions();
        mutable.set(0, 0, 0);
        memory.clear();
        assertEquals(List.of(new BlockPos(-122, 78, -22)), saved);
    }

    @Test public void babyZombieDeathAtTheOpenedChestIsTheMimicKillButOneElsewhereOrAtAClosedChestIsNot() {
        // M7 trace 15:05:03: armored Mimic, dead 21 ms after the first hit at the chest the ESP had known all run.
        var chest = new BlockPos(-68, 142, -117);
        var atChest = new DungeonStateTracker.BabyZombieDeath(chest, 1000L);
        var far = new DungeonStateTracker.BabyZombieDeath(new BlockPos(-40, 142, -117), 1000L);
        var stale = new DungeonStateTracker.BabyZombieDeath(chest, 900L);
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        // Only the chest the Mimic came out of is opened; every other remembered chest is still trapped.
        Predicate<BlockPos> opened = chest::equals;

        assertSame(atChest, DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(far, atChest), 1010L, opened));
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(far), 1010L, opened));
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(stale), 1010L, opened));
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(), List.of(atChest), 1010L, opened));
        var noMimicFloor = new DungeonRunStats();
        noMimicFloor.configureForFloor(5, false);
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(noMimicFloor, List.of(chest), List.of(atChest), 1010L, opened));

        // A baby zombie dying 1 block from a chest that is still trapped (any room, never opened) is no kill.
        var trapped = new BlockPos(-30, 142, -117);
        var besideTrapped = new DungeonStateTracker.BabyZombieDeath(trapped.east(), 1000L);
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(trapped), List.of(besideTrapped), 1010L, opened));
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(trapped, chest), List.of(besideTrapped), 1010L, opened));
        assertSame(besideTrapped, DungeonMimicEspFeature.mimicDeathAtChest(
            stats, List.of(trapped), List.of(besideTrapped), 1010L, trapped::equals));

        // The Mimic moves a little before it dies: 4 blocks still counts, 5 does not.
        var movedFour = new DungeonStateTracker.BabyZombieDeath(chest.south(4), 1000L);
        var movedFive = new DungeonStateTracker.BabyZombieDeath(chest.south(5), 1000L);
        assertSame(movedFour, DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(movedFour), 1010L, opened));
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(movedFive), 1010L, opened));

        var previous = KungConfig.get().dungeon;
        KungConfig.get().dungeon = new DungeonConfig();
        try {
            stats.observeMimicEspKill(null, "mimic-death-at-chest");
        } finally {
            KungConfig.get().dungeon = previous;
        }
        assertNull(DungeonMimicEspFeature.mimicDeathAtChest(stats, List.of(chest), List.of(atChest), 1010L, opened));
    }

    @Test public void combatXpActionbarWithThousandsSeparatorsStillCountsAsKillXp() {
        var pattern = DungeonMimicEspFeature.COMBAT_XP_PATTERN;
        assertTrue(pattern.matcher("+1,251.9 Combat (722,792,684/0) 0/3 Secrets").matches());
        assertTrue(pattern.matcher("+12.5 Combat (1/2)").matches());
        assertFalse(pattern.matcher("+1,251.9 Mining (1/2)").matches());
    }

    @Test public void snapshotGenerationChangesOnlyWhenTheInstanceMapIsReset() {
        var snapshot = new DungeonMapSnapshot();
        long first = snapshot.resetGeneration();
        snapshot.observeMimicRoom(2, 5, "test");
        snapshot.observePlayerGrid(1, 0);
        assertEquals(first, snapshot.resetGeneration());
        snapshot.reset();
        assertEquals(first + 1, snapshot.resetGeneration());
        assertTrue(snapshot.mimicRooms().isEmpty());
        snapshot.reset();
        assertEquals(first + 2, snapshot.resetGeneration());
    }
}
