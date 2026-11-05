package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.*;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.Test;

public final class DungeonMapTrapTest extends MinecraftBootstrapTest {
    @Test public void exactTrapBackgroundSurvivesCheckmarksButNormalOrangeDoesNotQualify() {
        for (int size : List.of(16, 18)) {
            for (int checkColor : List.of(18, 30, 34)) {
                var map = mapRoom(size, 62);
                for (int[] pixel : List.of(new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5},
                    new int[]{3, 4}, new int[]{4, 3}, new int[]{5, 2}, new int[]{6, 1}, new int[]{7, 0})) {
                    map.colors[24 + pixel[0] + (24 + pixel[1]) * 128] = (byte) checkColor;
                }
                assertTrue(DungeonMapCheckmarkReader.isTrapRoom(map, 20, 20, size));
            }
        }
        for (int color : List.of(0, 18, 30, 34, 60, 61, 63, 66, 74, 82, 85)) {
            var map = mapRoom(16, color);
            map.colors[24 + 24 * 128] = 62;
            assertFalse("Background " + color, DungeonMapCheckmarkReader.isTrapRoom(map, 20, 20, 16));
        }
        assertFalse(DungeonMapCheckmarkReader.isTrapRoom(mapRoom(16, 62), 120, 120, 16));
    }

    @Test public void completedTrapRendersBeforeLoadingAndAfterAnUnknownChangedHash() {
        var snapshot = new DungeonMapSnapshot();
        snapshot.observeStartRoom(5, 5);
        snapshot.observeMapTrapRoom(1, 0);
        snapshot.observeRoomClearState(1, 0, DungeonMapClearState.COMPLETED);
        for (int hash : List.of(0, -318865360, 827369333)) {
            if (hash != 0) snapshot.addScan(1, 1, 5, 5, List.of(room(hash)));
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
            assertEquals(RoomType.TRAP, plan.roomTypeAt(1, 0));
            assertTrue(plan.isCompletedRoom(1, 0));
            assertTrue("Unknown totals stay unknown", plan.hasUnknownRooms(snapshot));
            assertEquals(-1, DungeonRunStats.estimatedSecretTotal(plan));
            assertTrue("Map color must not invent a catalog match", plan.matches().isEmpty());
            assertNull(plan.hintAt(1, 0));
            var layout = DungeonRoomRenderLayout.from(plan);
            assertEquals(1, layout.rooms().size());
            var rendered = layout.rooms().getFirst();
            assertEquals("Trap", rendered.template().name());
            assertEquals(-1, rendered.template().secrets());
            assertEquals(-1, rendered.template().crypts());
            assertTrue(rendered.contains(1, 0));
        }
    }

    @Test public void purpleIsAPuzzleAndOnlyPurple() {
        assertTrue(DungeonMapCheckmarkReader.isPuzzleRoom(mapRoom(16, 66), 20, 20, 16));
        for (int color : List.of(62, 63, 74, 82, 85)) {
            assertFalse("Background " + color, DungeonMapCheckmarkReader.isPuzzleRoom(mapRoom(16, color), 20, 20, 16));
        }
    }

    @Test public void solvedBlazeWithAChangedHashIsNamedByTheOneTabPuzzleLeft() {
        // Blaze changes its blocks once solved, so a player who first loads it then scans an unknown hash.
        var snapshot = new DungeonMapSnapshot();
        snapshot.observeStartRoom(5, 5);
        snapshot.observeMapPuzzleRoom(1, 0);
        snapshot.observeRoomClearState(1, 0, DungeonMapClearState.COMPLETED);
        snapshot.addScan(1, 1, 5, 5, List.of(room(827369333)));
        snapshot.observeTabPuzzles(java.util.Set.of("higher or lower"));
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals(RoomType.PUZZLE, plan.roomTypeAt(1, 0));
        assertTrue(plan.isCompletedRoom(1, 0));
        var rendered = DungeonRoomRenderLayout.from(plan).rooms().getFirst().template();
        assertEquals("Blaze", rendered.name());
        assertEquals(-1, rendered.secrets());
        // Two names left: no guess, just the type.
        snapshot.observeTabPuzzles(java.util.Set.of("higher or lower", "quiz"));
        var unsure = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals("Puzzle", DungeonRoomRenderLayout.from(unsure).rooms().getFirst().template().name());
        assertEquals("Ice Fill", DungeonLiveMapWriter.MatchRenderPlan.catalogPuzzleName("ice fill"));
        assertEquals("higher or lower", DungeonLiveMapWriter.MatchRenderPlan.tabPuzzleName("Blaze"));
    }

    @Test public void mapTypeIsIdempotentAndResetsWithTheInstance() {
        var snapshot = new DungeonMapSnapshot();
        snapshot.observeMapVisibleRoom(1, 0);
        long previous = snapshot.scanRevision();
        snapshot.observeMapTrapRoom(1, 0);
        assertTrue(snapshot.scanRevision() > previous);
        long typed = snapshot.scanRevision();
        snapshot.observeMapTrapRoom(1, 0);
        assertEquals(typed, snapshot.scanRevision());
        snapshot.observeMapTrapRoom(-1, 0);
        assertFalse(snapshot.isMapTrapRoom(-1, 0));
        snapshot.reset();
        assertFalse(snapshot.isMapTrapRoom(1, 0));
        assertTrue(DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null)).roomOwners().isEmpty());
    }

    @Test public void knownIdentityWinsOverGenericMapType() {
        var snapshot = new DungeonMapSnapshot();
        snapshot.observeMapTrapRoom(1, 0);
        snapshot.addScan(1, 1, 5, 5, List.of(room(827369333)));
        for (var hint : List.of(new KnownCoreHint("New Trap", RoomType.TRAP, 3, 1),
            new KnownCoreHint("Known room", RoomType.NORMAL, 5, 2))) {
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(hint));
            var rendered = DungeonRoomRenderLayout.from(plan).rooms().getFirst().template();
            assertEquals(hint.name(), rendered.name());
            assertEquals(hint.type(), plan.roomTypeAt(1, 0));
            assertEquals(hint.secrets(), rendered.secrets());
            assertEquals(hint.crypts(), rendered.crypts());
            assertFalse(plan.hasUnknownRooms(snapshot));
        }
    }

    private static MapItemSavedData mapRoom(int size, int color) {
        var map = MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD);
        for (int z = 20; z < 20 + size; z++) for (int x = 20; x < 20 + size; x++) {
            map.colors[x + z * 128] = (byte) color;
        }
        return map;
    }

    private static DungeonScanPoint room(int hash) {
        return new DungeonScanPoint(2, 0, -153, -185, DungeonScanPointKind.ROOM, true,
            hash, hash, 0, DungeonDoorKind.NONE, true);
    }

    private static DungeonRoomRepository repository(KnownCoreHint hint) {
        return new DungeonRoomRepository() {
            @Override public List<MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) { return List.of(); }
            @Override public KnownCoreHint knownCoreHint(int hash) { return hint; }
        };
    }
}
