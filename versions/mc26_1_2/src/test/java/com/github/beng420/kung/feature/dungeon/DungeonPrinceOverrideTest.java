package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DungeonPrinceOverrideTest {
    private DungeonConfig previousConfig;

    @Before public void isolateConfig() {
        previousConfig = KungConfig.get().dungeon;
        KungConfig.get().dungeon = new DungeonConfig();
        DungeonKnownRoomCatalog.reload();
    }

    @After public void restoreConfig() {
        KungConfig.get().dungeon = previousConfig;
        DungeonKnownRoomCatalog.reload();
    }

    @Test public void falseOverridesBundledTrueAndRefreshesTheMapWithoutRescanning() throws Exception {
        var original = room("Doors", RoomType.NORMAL);
        var tracker = new DungeonStateTracker();
        var component = original.components().getFirst();
        tracker.mapSnapshot().addScan(0, 0, 5, 5,
            List.of(point(component.coreHash(), component.stableCoreHash())));
        var before = tracker.renderPlan();
        assertTrue(before.matches().getFirst().template().prince());
        long scanRevision = tracker.mapSnapshot().scanRevision();

        DungeonKnownRoomCatalog.updateRoomPrince("Doors", original.type(), original.secrets(), false);
        var after = tracker.renderPlan();
        assertNotSame(before, after);
        assertFalse(after.matches().getFirst().template().prince());
        assertEquals(scanRevision, tracker.mapSnapshot().scanRevision());
        assertEquals(original.components(), room("Doors", RoomType.NORMAL).components());
        assertEquals(original.crypts(), room("Doors", RoomType.NORMAL).crypts());
        assertFalse(KungConfig.get().dungeon.localRoomDataEnabled());

        DungeonKnownRoomCatalog.updateRoomPrince("Doors", original.type(), original.secrets(), true);
        assertTrue(tracker.renderPlan().matches().getFirst().template().prince());
        DungeonKnownRoomCatalog.updateRoomPrince("Doors", original.type(), original.secrets(), false);
        DungeonKnownRoomCatalog.reload();
        assertFalse(room("Doors", RoomType.NORMAL).prince());
    }

    @Test public void overridesWinAfterCanonicalMetadataAndKeepNamesakesSeparate() throws Exception {
        DungeonKnownRoomCatalog.updateRoomPrince("Supertall", RoomType.NORMAL, 6, false);
        DungeonKnownRoomCatalog.updateRoomPrince("Black Flag", RoomType.NORMAL, 3, true);
        DungeonKnownRoomCatalog.updateRoomPrince("Lava Pit", RoomType.RARE, 0, true);
        DungeonKnownRoomCatalog.reload();

        assertFalse(room("Supertall", RoomType.NORMAL).prince());
        assertTrue(room("Black Flag", RoomType.NORMAL).prince());
        assertTrue(room("Lava Pit", RoomType.RARE).prince());
        assertFalse(room("Lava Pit", RoomType.NORMAL).prince());
        assertEquals(0, room("Lava Pit", RoomType.RARE).crypts());
        assertEquals(1, room("Lava Pit", RoomType.NORMAL).crypts());
        assertThrows(IllegalArgumentException.class,
            () -> DungeonKnownRoomCatalog.updateRoomPrince("Unknown room", RoomType.NORMAL, 0, true));
    }

    @Test public void preloadHintsAndExistingSessionAliasesUseTheCorrection() throws Exception {
        DungeonKnownRoomCatalog.updateRoomPrince("Blaze", RoomType.PUZZLE, 1, true);
        assertTrue(DungeonKnownRoomCatalog.knownCoreHint(-1783845896).prince());

        var doors = room("Doors", RoomType.NORMAL);
        var component = doors.components().getFirst();
        var earlier = point(1717171717, 1818181818);
        assertFalse(DungeonKnownRoomCatalog.isKnownCoreHash(earlier.coreHash()));
        DungeonKnownRoomCatalog.observePreloadTransition(earlier,
            point(component.coreHash(), component.stableCoreHash()));
        var snapshot = new DungeonMapSnapshot();
        snapshot.addScan(0, 0, 5, 5, List.of(earlier));
        assertTrue(DungeonKnownRoomCatalog.matchKnownRooms(snapshot).getFirst().template().prince());

        DungeonKnownRoomCatalog.updateRoomPrince("Doors", doors.type(), doors.secrets(), false);
        var corrected = DungeonKnownRoomCatalog.matchKnownRooms(snapshot).getFirst().template();
        assertEquals("Doors", corrected.name());
        assertFalse(corrected.prince());
    }

    @Test public void noWorldCannotChangeRoomMetadata() {
        long revision = DungeonKnownRoomCatalog.revision();
        var result = new DungeonStateTracker().updateCurrentRoomPrince(null, true);
        assertFalse(result.learned());
        assertEquals(revision, DungeonKnownRoomCatalog.revision());
    }

    private static DungeonKnownRoomCatalog.RoomTemplate room(String name, RoomType type) {
        return DungeonKnownRoomCatalog.loadTemplates().stream()
            .filter(room -> room.name().equals(name) && room.type() == type).findFirst().orElseThrow();
    }

    private static DungeonScanPoint point(int core, int stable) {
        return new DungeonScanPoint(0, 0, -185, -185, DungeonScanPointKind.ROOM, true,
            core, stable, 0, DungeonDoorKind.NONE);
    }
}
