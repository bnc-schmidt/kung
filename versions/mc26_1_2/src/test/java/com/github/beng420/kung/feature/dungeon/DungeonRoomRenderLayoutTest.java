package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class DungeonRoomRenderLayoutTest {
    // The bundled Layers L variant: one upper-left cell plus a two-cell right column.
    private static final DungeonKnownRoomCatalog.RoomTemplate LAYERS =
        new DungeonKnownRoomCatalog.RoomTemplate("Layers", RoomType.NORMAL, 8, 3, false, 1, List.of(
            new DungeonKnownRoomCatalog.TemplateComponent(0, 0, 1532735021, 189854538),
            new DungeonKnownRoomCatalog.TemplateComponent(1, 0, 1452729338, 1826719119),
            new DungeonKnownRoomCatalog.TemplateComponent(1, 1, -337998129, -1518054068)
        ));
    private static final DungeonKnownRoomCatalog.KnownCoreHint LAYERS_HINT =
        new DungeonKnownRoomCatalog.KnownCoreHint("Layers", RoomType.NORMAL, 8, 3, false);
    private static final Map<Integer, DungeonKnownRoomCatalog.KnownCoreHint> HINTS = Map.of(
        1532735021, LAYERS_HINT, 1452729338, LAYERS_HINT, -337998129, LAYERS_HINT
    );
    private static final Set<DungeonLiveMapWriter.CellKey> LAYERS_CELLS = Set.of(
        new DungeonLiveMapWriter.CellKey(0, 0),
        new DungeonLiveMapWriter.CellKey(1, 0),
        new DungeonLiveMapWriter.CellKey(1, 1)
    );

    @Test
    public void theCornerCellIsAlwaysACellOfTheRoomEvenWhenTheBoundsCornerIsEmpty() {
        // Withermancer-like L shape missing its bottom-right cell: the bounds' corner (5, 1) belongs to the next room.
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(4, 1, 0), cornerCellOf(cells(4, 0, 5, 0, 4, 1)));
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(5, 1, 0), cornerCellOf(cells(4, 0, 4, 1, 5, 1)));
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(5, 1, 0), cornerCellOf(cells(5, 0, 4, 1, 5, 1)));
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(6, 0, 0), cornerCellOf(cells(4, 0, 5, 0, 6, 0)));
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(4, 1, 0), cornerCellOf(cells(4, 0, 4, 1)));
        assertEquals(new DungeonKnownRoomCatalog.MatchedComponent(2, 2, 0), cornerCellOf(cells(1, 1, 2, 1, 1, 2, 2, 2)));
    }

    private static List<DungeonKnownRoomCatalog.MatchedComponent> cells(int... xz) {
        List<DungeonKnownRoomCatalog.MatchedComponent> cells = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            cells.add(new DungeonKnownRoomCatalog.MatchedComponent(xz[i], xz[i + 1], 0));
        }
        return cells;
    }

    private static DungeonKnownRoomCatalog.MatchedComponent cornerCellOf(
        List<DungeonKnownRoomCatalog.MatchedComponent> cells
    ) {
        return new DungeonKnownRoomCatalog.MatchedRoom(LAYERS, cells).cornerCell();
    }

    @Test
    public void layersSplitByAnOpenScanHasOneConnectedRenderRoomAndOneSecretLabel() {
        DungeonMapSnapshot snapshot = layersSnapshot(DungeonDoorKind.OPEN);
        snapshot.observePlayerGrid(0, 0);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(true));

        // Keep conservative catalog matching separate from the already resolved map topology.
        assertEquals(2, plan.matches().size());
        assertEquals(List.of(1, 2), plan.matches().stream()
            .map(match -> match.components().size()).sorted().toList());
        assertEquals(1L, LAYERS_CELLS.stream().map(plan.roomOwners()::get).distinct().count());
        assertTrue(plan.internalDoors().contains(new DungeonLiveMapWriter.CellKey(1, 0)));
        assertTrue(plan.internalDoors().contains(new DungeonLiveMapWriter.CellKey(2, 1)));
        assertTrue(plan.visitedRooms().containsAll(LAYERS_CELLS));

        DungeonRoomRenderLayout layout = DungeonRoomRenderLayout.from(plan);
        assertEquals(LAYERS_CELLS, layout.cells());
        assertEquals(1, layout.rooms().size());
        var renderedRoom = layout.rooms().getFirst();
        assertEquals("Layers", renderedRoom.template().name());
        assertEquals(8, renderedRoom.template().secrets());
        assertEquals(3, renderedRoom.components().size());
        for (var cell : LAYERS_CELLS) {
            assertTrue(renderedRoom.contains(cell.x(), cell.z()));
        }
        assertFalse("The missing lower-left corner must stay empty", renderedRoom.contains(0, 1));
        assertEquals("Rendering must not rewrite the catalog result", 2, plan.matches().size());
    }

    @Test
    public void bridgesTraceKeepsItsThreeAndTwoCellMatchesSeparateWithoutSharingCompletion() {
        // Match geometry retained at 22:22:41.563 in kung-trace-20260918-222334.log.
        // The trace lost the cell hashes; these matches reproduce the input to owner merging.
        var upperCells = List.of(new DungeonKnownRoomCatalog.MatchedComponent(4, 0, 0),
            new DungeonKnownRoomCatalog.MatchedComponent(5, 0, 0),
            new DungeonKnownRoomCatalog.MatchedComponent(4, 1, 0));
        var lowerCells = List.of(new DungeonKnownRoomCatalog.MatchedComponent(3, 2, 0),
            new DungeonKnownRoomCatalog.MatchedComponent(4, 2, 0));
        var template = new DungeonKnownRoomCatalog.RoomTemplate("Bridges", RoomType.NORMAL, 6, 6, false, 1, List.of());
        var matches = List.of(new DungeonKnownRoomCatalog.MatchedRoom(template, upperCells),
            new DungeonKnownRoomCatalog.MatchedRoom(template, lowerCells));
        DungeonRoomRepository rooms = new DungeonRoomRepository() {
            @Override public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) { return matches; }
        };
        for (boolean extraMapConnection : List.of(false, true)) {
            DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
            snapshot.observeMapPlayerRoom(4, 0);
            snapshot.observeRoomClearState(4, 0, DungeonMapClearState.COMPLETED);
            // Even erroneous map connectivity must not bypass the owner-size limit.
            if (extraMapConnection) snapshot.observeMapRoomConnection(8, 3);
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, rooms);
            DungeonRoomRenderLayout layout = DungeonRoomRenderLayout.from(plan);
            assertEquals(List.of(2, 3), layout.rooms().stream().map(room -> room.components().size()).sorted().toList());
            assertEquals(2L, plan.roomOwners().values().stream().distinct().count());
            assertFalse(plan.internalDoors().contains(new DungeonLiveMapWriter.CellKey(8, 3)));
            for (var cell : lowerCells) {
                var key = new DungeonLiveMapWriter.CellKey(cell.roomGridX(), cell.roomGridZ());
                assertFalse(plan.visitedRooms().contains(key));
                assertFalse(plan.completedRooms().contains(key));
            }
            assertEquals(new DungeonRoomProgress(1, 1, 2), DungeonRoomProgress.from(plan));
            assertEquals(matches, plan.matches());
        }
    }

    @Test
    public void narrowMapDoorKeepsSameNamedRoomsSeparateBelowTheSizeLimit() {
        var snapshot = layersSnapshot(DungeonDoorKind.OPEN);
        snapshot.observeMapOpenDoor(1, 0);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(true));
        assertEquals(2, DungeonRoomRenderLayout.from(plan).rooms().size());
        assertFalse(plan.internalDoors().contains(new DungeonLiveMapWriter.CellKey(1, 0)));
        assertTrue(plan.externalDoors().containsKey(new DungeonLiveMapWriter.CellKey(1, 0)));

        snapshot.observeMapRoomConnection(1, 0);
        var connected = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(true));
        assertEquals("A broad internal connection still joins the Layers fragments", 1,
            DungeonRoomRenderLayout.from(connected).rooms().size());
    }

    @Test
    public void visibleCellsStayConnectedBeforeMapConnectionsArrive() {
        for (boolean mapVisible : List.of(true, false)) {
            var snapshot = layersSnapshot(DungeonDoorKind.OPEN);
            for (var cell : LAYERS_CELLS) {
                if (mapVisible) snapshot.observeMapVisibleRoom(cell.x(), cell.z());
                else {
                    var point = snapshot.pointAt(cell.x() * 2, cell.z() * 2).point();
                    snapshot.addScan(1, 1, 5, 5, List.of(new DungeonScanPoint(point.gridX(), point.gridZ(),
                        point.worldX(), point.worldZ(), point.kind(), point.loaded(), point.coreHash(),
                        point.stableCoreHash(), point.doorBlockId(), point.doorKind(), true)));
                }
            }
            var early = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(true));
            assertEquals(1, DungeonRoomRenderLayout.from(early).rooms().size());
            assertEquals(LAYERS_CELLS, DungeonRoomRenderLayout.from(early).cells());

            snapshot.observeMapRoomConnection(1, 0);
            snapshot.observeMapRoomConnection(2, 1);
            var connected = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(true));
            assertEquals(1, DungeonRoomRenderLayout.from(connected).rooms().size());
            assertEquals(LAYERS_CELLS, DungeonRoomRenderLayout.from(connected).cells());
        }
    }

    @Test
    public void lockedDoorsKeepSameNamedRoomsSeparate() {
        for (DungeonDoorKind kind : List.of(DungeonDoorKind.WITHER, DungeonDoorKind.BLOOD)) {
            var plan = DungeonLiveMapWriter.MatchRenderPlan.from(layersSnapshot(kind), repository(true));
            DungeonRoomRenderLayout layout = DungeonRoomRenderLayout.from(plan);

            assertEquals(kind.name(), 2, layout.rooms().size());
            assertEquals(LAYERS_CELLS, layout.cells());
            assertFalse(plan.internalDoors().contains(new DungeonLiveMapWriter.CellKey(1, 0)));
            assertTrue(layout.rooms().stream().noneMatch(room -> room.contains(0, 0) && room.contains(1, 0)));
        }
    }

    @Test
    public void sameRoomCoreHintsShareTheResolvedRenderGeometry() {
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(
            layersSnapshot(DungeonDoorKind.OPEN), repository(false));
        assertTrue(plan.matches().isEmpty());
        assertEquals(3, plan.hints().size());

        DungeonRoomRenderLayout layout = DungeonRoomRenderLayout.from(plan);
        assertEquals(1, layout.rooms().size());
        assertEquals(LAYERS_CELLS, layout.cells());
        assertEquals("Layers", layout.rooms().getFirst().template().name());
        assertEquals(8, layout.rooms().getFirst().template().secrets());
        assertEquals(3, layout.rooms().getFirst().components().size());
    }

    @Test
    public void hintOnlyProjectionPreservesRemoteSecretMaximumFromAnotherCell() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(List.of(
            new DungeonMapSnapshot.RemoteRoom(0, 0, "Remote gallery", RoomType.NORMAL,
                0, 0, 0, 0, true, false, false, "teammate", 1L),
            new DungeonMapSnapshot.RemoteRoom(1, 0, "Remote gallery", RoomType.NORMAL,
                0, 0, 3, 6, true, false, false, "teammate", 1L)
        ), List.of());
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(false));
        assertTrue(plan.matches().isEmpty());
        assertEquals(0, plan.hintAt(0, 0).secrets());
        assertEquals(0, plan.hintAt(1, 0).secrets());
        assertEquals(3, plan.remoteRoomSecretsFoundOnly(1, 0));

        DungeonRoomRenderLayout layout = DungeonRoomRenderLayout.from(plan);
        assertEquals(1, layout.rooms().size());
        assertEquals(2, layout.rooms().getFirst().components().size());
        assertEquals("Remote gallery", layout.rooms().getFirst().template().name());
        assertEquals(6, layout.rooms().getFirst().template().secrets());
        assertEquals("Projection must not rewrite the original hint", 0, plan.hintAt(0, 0).secrets());
        assertEquals("Projection must not rewrite the original hint", 0, plan.hintAt(1, 0).secrets());

        snapshot.observeMapVisibleRoom(0, 0);
        snapshot.observeMapVisibleRoom(1, 0);
        snapshot.observeMapOpenDoor(1, 0);
        var separated = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(false));
        assertEquals("Remote room names must not bypass the observed map boundary", 2,
            DungeonRoomRenderLayout.from(separated).rooms().size());
        snapshot.observeMapRoomConnection(1, 0);
        assertEquals(1, DungeonRoomRenderLayout.from(
            DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(false))).rooms().size());
    }

    private static DungeonMapSnapshot layersSnapshot(DungeonDoorKind separatorKind) {
        List<DungeonScanPoint> points = new ArrayList<>();
        for (var component : LAYERS.components()) {
            points.add(new DungeonScanPoint(component.dx() * 2, component.dz() * 2,
                -185 + component.dx() * 32, -185 + component.dz() * 32,
                DungeonScanPointKind.ROOM, true, component.coreHash(), component.stableCoreHash(),
                0, DungeonDoorKind.NONE));
        }
        points.add(new DungeonScanPoint(1, 0, -169, -185,
            DungeonScanPointKind.DOOR, true, 0, 0, 42, separatorKind));
        points.add(new DungeonScanPoint(2, 1, -153, -169,
            DungeonScanPointKind.DOOR, true, 0, 0, 0, DungeonDoorKind.NONE));
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(0, 0L, 5, 5, points);
        return snapshot;
    }

    private static DungeonRoomRepository repository(boolean matchTemplates) {
        return new DungeonRoomRepository() {
            @Override public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
                return matchTemplates
                    ? DungeonKnownRoomCatalog.matchKnownRooms(snapshot, List.of(LAYERS), HINTS)
                    : List.of();
            }

            @Override public DungeonKnownRoomCatalog.KnownCoreHint knownCoreHint(int coreHash) {
                return HINTS.get(coreHash);
            }
        };
    }
}
