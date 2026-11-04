package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.*;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.CellKey;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class DungeonRoomPredictionTest {
    private static final RoomTemplate L = template("L room", new TemplateComponent(0, 0, 101, 1001),
        new TemplateComponent(1, 0, 102, 1002), new TemplateComponent(0, 1, 103, 1003));
    private static final RoomTemplate SQUARE = template("Square room", new TemplateComponent(0, 0, 101, 1001),
        new TemplateComponent(1, 0, 102, 1002), new TemplateComponent(0, 1, 103, 1003), new TemplateComponent(1, 1, 104, 1004));
    private static final RoomTemplate LINE = template("Long room", new TemplateComponent(0, 0, 201, 2001),
        new TemplateComponent(1, 0, 202, 2002), new TemplateComponent(2, 0, 203, 2003), new TemplateComponent(3, 0, 204, 2004));

    @Test public void uniqueLCompletesWithTwoVisibleLoadedCellsWithoutInventingScansOrScoreCredit() {
        var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
        snapshot.observeMapVisibleRoom(0, 0);
        snapshot.observeMapVisibleRoom(1, 0);
        snapshot.observeRoomClearState(0, 0, DungeonMapClearState.COMPLETED);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(L));
        var layout = DungeonRoomRenderLayout.from(plan);
        assertEquals(1, layout.rooms().size());
        assertEquals(Set.of(new CellKey(0, 0), new CellKey(1, 0), new CellKey(0, 1)), layout.cells());
        assertTrue(layout.isInternalDoor(0, 1));
        assertEquals(2, plan.matches().stream().mapToInt(match -> match.components().size()).sum());
        assertEquals(2, plan.roomOwners().size());
        assertNull(snapshot.pointAt(0, 2));
        assertFalse(plan.completedRooms().contains(new CellKey(0, 1)));
        assertFalse(plan.internalDoors().contains(new CellKey(0, 1)));
    }

    @Test public void threeCellsCompleteTheUniqueFourthCellOfAOneByFourRoom() {
        var snapshot = snapshot(room(1, 2, 201), room(2, 2, 202), room(3, 2, 203));
        var layout = DungeonRoomRenderLayout.from(DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(LINE)));
        assertEquals(1, layout.rooms().size());
        assertEquals(Set.of(new CellKey(1, 2), new CellKey(2, 2), new CellKey(3, 2), new CellKey(4, 2)), layout.cells());
        assertTrue(layout.isInternalDoor(7, 4));
    }

    @Test public void twoAdjacentSquareCellsCompleteBothUnseenCellsWithoutInventingFactualState() {
        var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
        snapshot.observeMapVisibleRoom(0, 0);
        snapshot.observeMapVisibleRoom(1, 0);
        snapshot.observeRoomClearState(0, 0, DungeonMapClearState.COMPLETED);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(SQUARE));
        var layout = DungeonRoomRenderLayout.from(plan);
        assertEquals(1, layout.rooms().size());
        assertEquals(Set.of(new CellKey(0, 0), new CellKey(1, 0), new CellKey(0, 1), new CellKey(1, 1)), layout.cells());
        assertEquals(Set.of(new CellKey(1, 0), new CellKey(1, 2), new CellKey(0, 1), new CellKey(2, 1)), layout.internalDoors());
        assertEquals(2, plan.matches().stream().mapToInt(match -> match.components().size()).sum());
        assertEquals(2, plan.roomOwners().size());
        for (int x = 0; x <= 1; x++) {
            assertNull(snapshot.pointAt(x * 2, 2));
            var unseen = new CellKey(x, 1);
            assertFalse(plan.matchedRoomCells().contains(unseen));
            assertFalse(plan.visitedRooms().contains(unseen));
            assertFalse(plan.clearedRooms().contains(unseen));
            assertFalse(plan.completedRooms().contains(unseen));
        }
        assertFalse(plan.internalDoors().contains(new CellKey(1, 2)));
    }

    @Test public void diagonalSquareCellsCompleteTheUniqueFootprintDespiteMirroredUnseenHashes() {
        var snapshot = snapshot(room(2, 2, 101), room(3, 3, 104));
        var layout = DungeonRoomRenderLayout.from(DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(SQUARE)));
        assertEquals(1, layout.rooms().size());
        assertEquals(Set.of(new CellKey(2, 2), new CellKey(3, 2), new CellKey(2, 3), new CellKey(3, 3)), layout.cells());
    }

    @Test public void twoMiddleLineCellsCompleteBothUniqueEnds() {
        var snapshot = snapshot(room(2, 2, 202), room(3, 2, 203));
        var layout = DungeonRoomRenderLayout.from(DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(LINE)));
        assertEquals(1, layout.rooms().size());
        assertEquals(Set.of(new CellKey(1, 2), new CellKey(2, 2), new CellKey(3, 2), new CellKey(4, 2)), layout.cells());
        assertTrue(layout.isInternalDoor(3, 4));
        assertTrue(layout.isInternalDoor(7, 4));
    }

    @Test public void squareWithTwoPossibleSidesWaitsForConflictingEvidence() {
        var snapshot = snapshot(room(2, 2, 101), room(3, 2, 102));
        assertTrue(DungeonRoomPrediction.predict(snapshot, List.of(SQUARE)).isEmpty());
        snapshot.addScan(1, 1, 5, 5, List.of(room(2, 1, 999)));
        var prediction = DungeonRoomPrediction.predict(snapshot, List.of(SQUARE));
        assertEquals(1, prediction.size());
        assertEquals(Set.of(new CellKey(2, 2), new CellKey(3, 2), new CellKey(2, 3), new CellKey(3, 3)),
            DungeonRoomPrediction.cells(prediction.getFirst()));
    }

    @Test public void ambiguousLWaitsUntilOtherObservedRoomRulesOutOneSide() {
        var snapshot = snapshot(room(2, 2, 101), room(3, 2, 102));
        assertTrue(DungeonRoomPrediction.predict(snapshot, List.of(L)).isEmpty());
        snapshot.addScan(1, 1, 5, 5, List.of(room(2, 1, 999)));
        var prediction = DungeonRoomPrediction.predict(snapshot, List.of(L));
        assertEquals(1, prediction.size());
        assertTrue(prediction.getFirst().contains(2, 3));
        assertFalse(prediction.getFirst().contains(2, 1));
    }

    @Test public void identicalLineCellsDoNotGuessBetweenTwoPossibleEnds() {
        var ambiguous = template("Repeated line", new TemplateComponent(0, 0, 201, 2001),
            new TemplateComponent(1, 0, 201, 2001), new TemplateComponent(2, 0, 201, 2001),
            new TemplateComponent(3, 0, 201, 2001));
        var snapshot = snapshot(room(1, 2, 201), room(2, 2, 201), room(3, 2, 201));
        assertTrue(DungeonRoomPrediction.predict(snapshot, List.of(ambiguous)).isEmpty());
    }

    @Test public void knownCompleteVariantPreventsExtendingAnAlreadyCompleteRoom() {
        var complete = template("L room", new TemplateComponent(0, 0, 101, 1001), new TemplateComponent(1, 0, 102, 1002));
        var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
        assertTrue(DungeonRoomPrediction.predict(snapshot, List.of(L, complete)).isEmpty());
    }

    @Test public void largerCompatibleShapeMakesAnApparentlyUniqueLMissingCellAmbiguous() {
        var square = template("L room", new TemplateComponent(0, 0, 101, 1001),
            new TemplateComponent(1, 0, 102, 1002), new TemplateComponent(0, 1, 103, 1003),
            new TemplateComponent(1, 1, 104, 1004));
        var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
        assertTrue(DungeonRoomPrediction.predict(snapshot, List.of(L, square)).isEmpty());
        snapshot.addScan(1, 1, 5, 5, List.of(room(1, 1, 999)));
        assertEquals(1, DungeonRoomPrediction.predict(snapshot, List.of(L, square)).size());
    }

    @Test public void mapDoorsLoadedEmptyCellsAndOtherRoomCoresStopPrediction() {
        for (int conflict = 0; conflict < 5; conflict++) {
            var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
            if (conflict == 0) snapshot.observeMapOpenDoor(0, 1);
            if (conflict == 1) snapshot.addScan(1, 1, 5, 5, List.of(room(0, 1, 0)));
            if (conflict == 2) snapshot.addScan(1, 1, 5, 5, List.of(room(0, 1, 999)));
            if (conflict == 3) snapshot.observeMapVisibleRoom(0, 1);
            if (conflict == 4) snapshot.addScan(1, 1, 5, 5, List.of(new DungeonScanPoint(0, 1, -185, -169,
                DungeonScanPointKind.DOOR, true, 0, 0, 1, DungeonDoorKind.WITHER)));
            assertTrue("conflict=" + conflict, DungeonRoomPrediction.predict(snapshot, List.of(L)).isEmpty());
        }
    }

    @Test public void conflictInEitherUnseenSquareCellRejectsTheWholePrediction() {
        for (int x = 0; x <= 1; x++) {
            for (int conflict = 0; conflict < 4; conflict++) {
                var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
                if (conflict == 0) snapshot.observeMapVisibleRoom(x, 1);
                if (conflict == 1) snapshot.addScan(1, 1, 5, 5, List.of(room(x, 1, 0)));
                if (conflict == 2) snapshot.addScan(1, 1, 5, 5, List.of(room(x, 1, 999)));
                if (conflict == 3) snapshot.observeMapOpenDoor(x * 2, 1);
                assertTrue("x=" + x + ", conflict=" + conflict,
                    DungeonRoomPrediction.predict(snapshot, List.of(SQUARE)).isEmpty());
            }
        }
    }

    @Test public void fullWorldObservationReplacesPredictionWithExactShape() {
        var snapshot = snapshot(room(0, 0, 101), room(1, 0, 102));
        assertEquals(1, DungeonRoomPrediction.predict(snapshot, List.of(L)).size());
        snapshot.addScan(1, 1, 5, 5, List.of(room(0, 1, 103)));
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, repository(L));
        assertTrue(plan.predictedRooms().isEmpty());
        assertEquals(1, plan.matches().size());
        assertEquals(3, DungeonRoomRenderLayout.from(plan).cells().size());
    }

    @Test public void bundledRoomPredictionsAreCachedAcrossPlayerUpdatesAndReplacedByActualScans() {
        var museum = DungeonKnownRoomCatalog.loadTemplates().stream()
            .filter(template -> template.name().equals("Museum") && template.components().size() == 4).findFirst().orElseThrow();
        var points = museum.components().stream().map(c -> new DungeonScanPoint(c.dx() * 2, c.dz() * 2,
            -185 + c.dx() * 32, -185 + c.dz() * 32, DungeonScanPointKind.ROOM, true,
            c.coreHash(), c.stableCoreHash(), 0, DungeonDoorKind.NONE, true)).toList();
        var snapshot = snapshot(points.subList(0, 2).toArray(DungeonScanPoint[]::new));
        var repository = KnownDungeonRoomRepository.INSTANCE;
        var predicted = repository.predictedRoomShapes(snapshot);
        assertEquals(1, predicted.size());
        snapshot.observePlayerGrid(4, 5);
        snapshot.observeRoomClearState(0, 0, DungeonMapClearState.COMPLETED);
        assertSame(predicted, repository.predictedRoomShapes(snapshot));
        snapshot.addScan(1, 1, 4, 5, List.of(points.get(2)));
        var withThirdCell = repository.predictedRoomShapes(snapshot);
        assertNotSame(predicted, withThirdCell);
        assertEquals(1, withThirdCell.size());
        assertEquals(1, withThirdCell.getFirst().components().stream().filter(component -> component.coreHash() == 0).count());
        snapshot.addScan(1, 1, 4, 5, List.of(points.getLast()));
        assertTrue(repository.predictedRoomShapes(snapshot).isEmpty());
        assertEquals(4, repository.matchKnownRooms(snapshot).getFirst().components().size());
        snapshot.reset();
        assertTrue(repository.predictedRoomShapes(snapshot).isEmpty());
    }

    @Test public void unchangedEmptyHashBecomingFullyLoadedInvalidatesCachedPrediction() {
        var museum = DungeonKnownRoomCatalog.loadTemplates().stream()
            .filter(template -> template.name().equals("Museum") && template.components().size() == 4).findFirst().orElseThrow();
        var points = museum.components().stream().map(c -> new DungeonScanPoint(c.dx() * 2, c.dz() * 2,
            -185 + c.dx() * 32, -185 + c.dz() * 32, DungeonScanPointKind.ROOM, true,
            c.coreHash(), c.stableCoreHash(), 0, DungeonDoorKind.NONE, true)).toList();
        var snapshot = snapshot(points.subList(0, 3).toArray(DungeonScanPoint[]::new));
        var last = points.getLast();
        var empty = new DungeonScanPoint(last.gridX(), last.gridZ(), last.worldX(), last.worldZ(),
            DungeonScanPointKind.ROOM, true, 0, 0, 0, DungeonDoorKind.NONE, false);
        snapshot.addScan(1, 1, 5, 5, List.of(empty));
        var repository = KnownDungeonRoomRepository.INSTANCE;
        var predicted = repository.predictedRoomShapes(snapshot);
        assertEquals(1, predicted.size());
        long revision = snapshot.scanRevision();

        var loadedEmpty = new DungeonScanPoint(empty.gridX(), empty.gridZ(), empty.worldX(), empty.worldZ(),
            DungeonScanPointKind.ROOM, true, 0, 0, 0, DungeonDoorKind.NONE, true);
        snapshot.addScan(2, 2, 5, 5, List.of(loadedEmpty));
        assertTrue(snapshot.scanRevision() > revision);
        var withoutPrediction = repository.predictedRoomShapes(snapshot);
        assertTrue(withoutPrediction.isEmpty());
        assertNotSame(predicted, withoutPrediction);
        long loadedRevision = snapshot.scanRevision();
        snapshot.addScan(3, 3, 5, 5, List.of(empty, loadedEmpty));
        assertEquals(loadedRevision, snapshot.scanRevision());
        assertSame(withoutPrediction, repository.predictedRoomShapes(snapshot));
    }

    private static RoomTemplate template(String name, TemplateComponent... components) {
        return new RoomTemplate(name, RoomType.NORMAL, 6, 0, false, 1, List.of(components));
    }

    private static DungeonMapSnapshot snapshot(DungeonScanPoint... points) {
        var snapshot = new DungeonMapSnapshot();
        snapshot.addScan(0, 0, 5, 5, List.of(points));
        return snapshot;
    }

    private static DungeonScanPoint room(int x, int z, int core) {
        return new DungeonScanPoint(x * 2, z * 2, -185 + x * 32, -185 + z * 32,
            DungeonScanPointKind.ROOM, true, core, core == 0 ? 0 : core < 200 ? core + 900 : core + 1800,
            0, DungeonDoorKind.NONE, true);
    }

    private static DungeonRoomRepository repository(RoomTemplate template) {
        Map<Integer, KnownCoreHint> hints = new HashMap<>();
        template.components().forEach(c -> hints.put(c.coreHash(), new KnownCoreHint(template.name(), template.type(), template.secrets())));
        return new DungeonRoomRepository() {
            @Override public List<MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
                return DungeonKnownRoomCatalog.matchKnownRooms(snapshot, List.of(template), hints);
            }
            @Override public List<MatchedRoom> predictedRoomShapes(DungeonMapSnapshot snapshot) {
                return DungeonRoomPrediction.predict(snapshot, List.of(template));
            }
            @Override public KnownCoreHint knownCoreHint(int hash) { return hints.get(hash); }
        };
    }
}
