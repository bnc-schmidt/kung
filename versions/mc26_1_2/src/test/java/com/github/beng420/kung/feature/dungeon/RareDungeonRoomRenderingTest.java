package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public final class RareDungeonRoomRenderingTest {
    @Before public void useBundledTypes() {
        DungeonRoomClassifier.reload();
    }

    @Test public void confirmedRareHashGetsAColorWithoutInventingNameOrSecrets() {
        var snapshot = snapshot();
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertNull(plan.hintAt(0, 0));
        assertTrue(plan.matches().isEmpty());
        assertTrue(DungeonRoomRenderLayout.from(plan).rooms().isEmpty());
        var door = plan.externalDoors().get(new DungeonLiveMapWriter.CellKey(1, 0));
        assertNotNull(door);
        assertEquals(DungeonDoorKind.OPEN, door.kind());
        assertFalse("Rare is a room color, not a special/locked entrance", door.colorAsSpecial());
        snapshot.observeRoomClearState(0, 0, DungeonMapClearState.CLEARED);
        var cleared = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals("Rare rooms still contribute to room clear statistics", 1,
            DungeonRoomProgress.from(cleared).cleared());
    }

    @Test public void oldNormalLearnStaysBlueWithoutRewritingItsSavedIdentity() {
        var template = new DungeonKnownRoomCatalog.RoomTemplate("Lava Pool", RoomType.NORMAL,
            2, 0, false, 1, List.of(new DungeonKnownRoomCatalog.TemplateComponent(
                0, 0, -1005518830, 1296131753)));
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot(), repository(template));
        var projected = DungeonRoomRenderLayout.from(plan).rooms().getFirst();
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertEquals(RoomType.RARE, projected.template().type());
        assertEquals("Lava Pool", projected.template().name());
        assertEquals(2, projected.template().secrets());
        assertSame(template, plan.matches().getFirst().template());
        assertEquals(RoomType.NORMAL, template.type());
    }

    @Test public void remoteRareRoomKeepsItsTypeAndProgress() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(List.of(new DungeonMapSnapshot.RemoteRoom(0, 0,
            "Trinity", RoomType.RARE, 4, 0, 2, 4, true, true, false, "teammate", 1L)), List.of());
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        var room = DungeonRoomRenderLayout.from(plan).rooms().getFirst();
        assertEquals(RoomType.RARE, room.template().type());
        assertEquals(4, room.template().secrets());
        assertEquals(2, plan.remoteRoomSecretsFound(0, 0, 0));
        assertEquals(1, DungeonRoomProgress.from(plan).cleared());
    }

    @Test public void oldPeerNormalTypeCanBeRefinedByRareAndNeverDowngraded() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(RoomType.NORMAL, RoomType.RARE, RoomType.NORMAL).stream()
                .map(type -> new DungeonMapSnapshot.RemoteRoom(0, 0,
                    "Custom rare room", type, 4, 0, 0, 4, true, false, false, "teammate", 1L))
                .toList(),
            List.of());
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertEquals(RoomType.RARE, DungeonRoomRenderLayout.from(plan).rooms().getFirst().template().type());
    }

    @Test public void legacyRemoteRareNameNeedsNoLocalChunksToGetBlue() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(List.of(new DungeonMapSnapshot.RemoteRoom(0, 0,
            "Stone Window", RoomType.NORMAL, 2, 1, 1, 2, true, false, false, "older peer", 1L)), List.of());
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertEquals(RoomType.RARE, DungeonRoomRenderLayout.from(plan).rooms().getFirst().template().type());
        assertEquals(2, plan.hintAt(0, 0).secrets());
    }

    @Test public void remoteRareLavaPitIsNotDowngradedByItsNameWithoutLocalChunks() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(List.of(new DungeonMapSnapshot.RemoteRoom(0, 0,
            "Lava Pit", RoomType.RARE, 3, 1, 1, 3, true, false, false, "older peer", 1L)), List.of());
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertEquals(RoomType.RARE, DungeonRoomRenderLayout.from(plan).rooms().getFirst().template().type());
    }

    @Test public void localNormalHashesCorrectStalePeerTypeWhileRareHashesStayBlue() {
        for (boolean rare : new boolean[] {false, true}) {
            DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
            snapshot.addScan(0, 0L, 5, 5, List.of(new DungeonScanPoint(0, 0, -185, -185,
                DungeonScanPointKind.ROOM, true, rare ? -1005518830 : 1192954774,
                rare ? 1296131753 : -408192692, 0, DungeonDoorKind.NONE)));
            snapshot.replaceRemoteLiveData(List.of(new DungeonMapSnapshot.RemoteRoom(0, 0,
                "Lava Pit", RoomType.RARE, 3, 1, 1, 3, true, false, false, "peer", 1L)), List.of());
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(null));
            assertEquals(rare ? RoomType.RARE : RoomType.NORMAL, plan.roomTypeAt(0, 0));
        }
    }

    @Test public void bundledNamesakesRemainSeparateRoomsAndRetainClearCredit() {
        DungeonKnownRoomCatalog.reload();
        var snapshot = snapshot();
        snapshot.addScan(1, 1L, 5, 5, List.of(new DungeonScanPoint(2, 0, -153, -185,
            DungeonScanPointKind.ROOM, true, 1192954774, -408192692, 0, DungeonDoorKind.NONE)));
        snapshot.observeRoomClearState(0, 0, DungeonMapClearState.CLEARED);
        snapshot.observeRoomClearState(1, 0, DungeonMapClearState.CLEARED);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, KnownDungeonRoomRepository.INSTANCE);
        assertEquals(2, plan.matches().size());
        assertEquals(RoomType.RARE, plan.roomTypeAt(0, 0));
        assertEquals(RoomType.NORMAL, plan.roomTypeAt(1, 0));
        assertFalse(plan.sameRoomOwner(new DungeonLiveMapWriter.CellKey(0, 0), new DungeonLiveMapWriter.CellKey(1, 0)));
        assertEquals(2, DungeonRoomProgress.from(plan).cleared());
        for (var room : DungeonRoomRenderLayout.from(plan).rooms()) assertEquals("Lava Pit", room.template().name());
    }

    @Test public void staleLocalTypeFileCannotTurnCorrectedLavaPitBlueAgain() throws Exception {
        var types = DungeonRoomClassifier.class.getDeclaredField("knownRoomTypes");
        types.setAccessible(true);
        try {
            types.set(null, Map.of(1192954774, RoomType.RARE));
            var template = new DungeonKnownRoomCatalog.RoomTemplate("Lava Pit", RoomType.NORMAL,
                3, 1, false, 1, List.of(new DungeonKnownRoomCatalog.TemplateComponent(0, 0, 1192954774, -408192692)));
            DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
            snapshot.addScan(0, 0L, 5, 5, List.of(new DungeonScanPoint(0, 0, -185, -185,
                DungeonScanPointKind.ROOM, true, 1192954774, -408192692, 0, DungeonDoorKind.NONE)));
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(template));
            assertEquals(RoomType.NORMAL, plan.roomTypeAt(0, 0));
            assertEquals(RoomType.NORMAL, DungeonRoomRenderLayout.from(plan).rooms().getFirst().template().type());
        } finally {
            DungeonRoomClassifier.reload();
        }
    }

    private static DungeonMapSnapshot snapshot() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(0, 0L, 5, 5, List.of(
            new DungeonScanPoint(0, 0, -185, -185, DungeonScanPointKind.ROOM, true,
                -1005518830, 1296131753, 0, DungeonDoorKind.NONE),
            new DungeonScanPoint(2, 0, -153, -185, DungeonScanPointKind.ROOM, true,
                101, 1001, 0, DungeonDoorKind.NONE),
            new DungeonScanPoint(1, 0, -169, -185, DungeonScanPointKind.DOOR, true,
                0, 0, 0, DungeonDoorKind.OPEN)));
        return snapshot;
    }

    private static DungeonRoomRepository repository(DungeonKnownRoomCatalog.RoomTemplate template) {
        return new DungeonRoomRepository() {
            @Override public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
                return template == null ? List.of() : List.of(new DungeonKnownRoomCatalog.MatchedRoom(
                    template, List.of(new DungeonKnownRoomCatalog.MatchedComponent(0, 0, template.components().getFirst().coreHash()))));
            }
        };
    }
}
