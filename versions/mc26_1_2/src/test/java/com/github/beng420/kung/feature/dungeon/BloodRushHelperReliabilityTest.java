package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.BRHelperConfig;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.runtime.AppServices;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.Gson;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

public final class BloodRushHelperReliabilityTest {
    @org.junit.Before
    public void fullBuildOnly() {
        org.junit.Assume.assumeFalse("Blood Rush is not in the Modrinth build", com.github.beng420.kung.KungBuild.MODRINTH);
    }

    private static final DungeonRoomRepository EMPTY_REPOSITORY = new DungeonRoomRepository() {
        @Override
        public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
            return List.of();
        }
    };

    @Test
    public void lateDoorScansCannotResumeTitlesAfterTheBloodOpenMessage() throws Exception {
        for (boolean exact : List.of(false, true)) withBloodRush(tracker -> {
            var helper = BloodRushHelperFeature.INSTANCE;
            var snapshot = tracker.mapSnapshot();
            var rooms = remoteBloodRushSnapshot();
            snapshot.replaceRemoteLiveData(rooms.remoteRooms(), rooms.remoteDoors());
            if (exact) for (int x = 0; x < 4; x++) snapshot.observeMapVisibleRoom(x, 0);
            snapshot.addScan(0, 0, 0, 0,
                List.of(door(3, 0, DungeonDoorKind.WITHER), door(5, 0, DungeonDoorKind.BLOOD)));
            helper.observeProgress(null, tracker);
            assertTrue(KungDebugRecorder.dump().contains("baseline opened=0 remaining=2"));

            helper.observeMessage(null, "The BLOOD DOOR has been opened!", true, tracker);
            assertTrue(KungDebugRecorder.dump().contains("blood done subtitle="));
            KungDebugRecorder.clear();

            // Trace: opening confirmed at 17:07:15; Wither scans catch up at 17:07:35.
            snapshot.addScan(1, 1, 0, 0, List.of(door(3, 0, DungeonDoorKind.OPEN)));
            var plan = tracker.renderPlan();
            var estimate = BloodRushDoorEstimate.remaining(plan, snapshot);
            assertEquals(1, estimate.count());
            assertEquals(exact ? "Blood next" : "1+ door left", estimate.remainingTitle(plan.bloodIsNext()));
            helper.observeProgress(null, tracker);
            helper.maybeShowDoorTitle(null, tracker);
            helper.observeMessage(null, "The BLOOD DOOR has been opened!", true, tracker);
            assertFalse("Finished rush must ignore late progress and duplicate completion",
                KungDebugRecorder.dump().contains("[door-title]"));

            helper.clear(100);
            helper.scheduleInitial(101);
            assertTrue(helper.doorTitlePending(101));
            helper.observeProgress(null, tracker);
            assertTrue("A new run must accept progress again", KungDebugRecorder.dump().contains("baseline opened=1"));
        });
    }

    @Test
    public void bloodOpenCancelsAnInitialTitleStillWaitingForScans() throws Exception {
        withBloodRush(tracker -> {
            var helper = BloodRushHelperFeature.INSTANCE;
            assertTrue(helper.doorTitlePending(0));
            helper.observeMessage(null, "The BLOOD DOOR has been opened!", false, tracker);
            assertTrue("Messages before the run must not finish it", helper.doorTitlePending(0));
            helper.observeMessage(null, "§cThe BLOOD DOOR has been opened!", true, tracker);
            assertFalse(helper.doorTitlePending(0));
            assertFalse(helper.shouldFastScanDoors(0));
            KungDebugRecorder.clear();
            helper.maybeShowDoorTitle(null, tracker);
            assertFalse(KungDebugRecorder.dump().contains("[door-title]"));
        });
    }

    private static void withBloodRush(Consumer<DungeonStateTracker> check) throws Exception {
        var config = KungConfig.get();
        var previous = config.bloodRush;
        var helper = BloodRushHelperFeature.INSTANCE;
        boolean initialized = helper.initialized();
        config.bloodRush = new Gson().fromJson("{\"enabled\":true}", BRHelperConfig.class);
        try {
            var tracker = new DungeonStateTracker(EMPTY_REPOSITORY);
            helper.initialize(AppServices.create(config, tracker));
            var started = DungeonStateTracker.class.getDeclaredField("realRunStarted");
            started.setAccessible(true);
            started.setBoolean(tracker, true);
            helper.clear(0);
            helper.scheduleInitial(0);
            KungDebugRecorder.clear();
            check.accept(tracker);
        } finally {
            helper.clear(0);
            if (!initialized) helper.shutdown();
            config.bloodRush = previous;
            KungDebugRecorder.clear();
        }
    }

    @Test
    public void lockedDoorDisappearingStillCountsAsOpened() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(door(3, 0, DungeonDoorKind.WITHER)));
        snapshot.addScan(2, 2L, 0, 0, List.of(door(3, 0, DungeonDoorKind.NONE)));

        assertTrue(snapshot.openedLockedDoors().contains(new DungeonMapSnapshot.GridKey(3, 0)));
        assertEquals(DungeonDoorKind.NONE, snapshot.pointAt(3, 0).doorKind());
    }

    @Test
    public void lockedDoorRevertingToUnclassifiedBlockDoesNotCountAsOpened() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(door(3, 0, DungeonDoorKind.BLOOD, 11458)));
        snapshot.addScan(2, 2L, 0, 0, List.of(door(3, 0, DungeonDoorKind.NONE, 15292)));

        assertFalse(snapshot.openedLockedDoors().contains(new DungeonMapSnapshot.GridKey(3, 0)));
        assertTrue(snapshot.observedLockedDoors().contains(new DungeonMapSnapshot.GridKey(3, 0)));
        assertEquals(DungeonDoorKind.BLOOD, snapshot.pointAt(3, 0).doorKind());
    }

    @Test
    public void arbitraryUnrecognizedSolidBlockDoesNotCountAsAnOpenedDoor() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(door(3, 0, DungeonDoorKind.WITHER, 42)));
        snapshot.addScan(2, 2L, 0, 0, List.of(door(3, 0, DungeonDoorKind.NONE, 1234)));
        assertFalse(snapshot.isOpenedLockedDoor(new DungeonMapSnapshot.GridKey(3, 0)));
        assertEquals(DungeonDoorKind.WITHER, snapshot.pointAt(3, 0).doorKind());
    }

    @Test
    public void exactBloodRushDoorEstimateRequiresVisiblePath() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertFalse(plan.bloodRushDoorEstimateExact(snapshot));
        assertEquals(2, plan.bloodRushTotalSpecialDoorCount());

        snapshot.observeMapVisibleRoom(0, 0);
        snapshot.observeMapVisibleRoom(3, 0);
        plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertFalse(plan.bloodRushDoorEstimateExact(snapshot));

        snapshot.observeMapVisibleRoom(1, 0);
        snapshot.observeMapVisibleRoom(2, 0);
        plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertTrue(plan.bloodRushDoorEstimateExact(snapshot));
        assertEquals(2, plan.bloodRushTotalSpecialDoorCount());
    }

    @Test
    public void matchingRawDoorCountsAloneDoNotProveThePathComplete() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertFalse(plan.bloodRushDoorEstimateExact(snapshot));
        assertFalse(plan.rawNonStartSpecialDoorEstimateExact(snapshot));
    }

    @Test
    public void unknownMiddleRoomCannotMakeATitleExact() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(List.of(room(0, 0, "Start", RoomType.START),
            room(1, 0, "", RoomType.UNKNOWN), room(2, 0, "Blood", RoomType.BLOOD)),
            List.of(remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)));
        snapshot.observeMapVisibleRoom(0, 0);
        snapshot.observeMapVisibleRoom(2, 0);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);
        assertFalse(BloodRushDoorEstimate.initial(plan, snapshot).exact());
    }

    @Test
    public void fullyScannedPathCanBeExactBeforeMapItemRevealsIt() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        java.util.ArrayList<DungeonScanPoint> points = new java.util.ArrayList<>();
        for (int x = 0; x < 4; x++) {
            points.add(new DungeonScanPoint(x * 2, 0, -185 + x * 32, -185,
                DungeonScanPointKind.ROOM, true, 100 + x, 200 + x, 0, DungeonDoorKind.NONE));
        }
        points.add(door(3, 0, DungeonDoorKind.WITHER));
        points.add(door(5, 0, DungeonDoorKind.BLOOD));
        snapshot.addScan(0, 0, 0, 0, points);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);
        assertTrue(BloodRushDoorEstimate.initial(plan, snapshot).exact());
        assertEquals("2 doors", BloodRushDoorEstimate.initial(plan, snapshot).initialTitle());
    }

    @Test
    public void titleWordingPreservesUncertaintyAndCountsEachOpenedDoorOnce() {
        assertEquals("2+ doors", new BloodRushDoorEstimate(2, false, 2).initialTitle());
        assertEquals("1+ door left", new BloodRushDoorEstimate(1, false, 2).remainingTitle(true));
        assertEquals("Blood next", new BloodRushDoorEstimate(1, true, 2).remainingTitle(true));
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(0, 0, 0, 0, List.of(door(3, 0, DungeonDoorKind.WITHER), door(5, 0, DungeonDoorKind.BLOOD)));
        snapshot.addScan(1, 1, 0, 0, List.of(door(3, 0, DungeonDoorKind.OPEN)));
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);
        assertEquals(1, BloodRushDoorEstimate.remaining(plan, snapshot).count());
        snapshot.addScan(2, 2, 0, 0, List.of(door(3, 0, DungeonDoorKind.OPEN)));
        plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);
        assertEquals(1, BloodRushDoorEstimate.remaining(plan, snapshot).count());
    }

    @Test
    public void openPuzzleDoorDoesNotInflateBloodRushDoorTotal() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshotWithPuzzleDoor();
        snapshot.addScan(1, 1L, 0, 0, List.of(door(1, 2, DungeonDoorKind.OPEN)));
        snapshot.observeMapOpenDoor(1, 2);

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(2, plan.bloodRushTotalSpecialDoorCount());
        assertEquals(2, plan.knownNonStartSpecialDoorCount());
    }

    @Test
    public void mapOpenSignalDoesNotOverrideVisibleLockedDoor() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));
        snapshot.observeMapOpenDoor(3, 0);

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.WITHER, plan.doorAt(3, 0).kind());
        assertEquals(0, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertTrue(plan.openedRawNonStartSpecialDoorCells(snapshot).isEmpty());
        assertTrue(plan.bloodRushOpenedSpecialDoorCells().isEmpty());
    }

    @Test
    public void traversedSignalDoesNotOverrideVisibleLockedDoor() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(1, 1L, 1, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));
        snapshot.addScan(2, 2L, 2, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.WITHER, plan.doorAt(3, 0).kind());
        assertEquals(0, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertTrue(plan.openedRawNonStartSpecialDoorCells(snapshot).isEmpty());
        assertTrue(plan.bloodRushOpenedSpecialDoorCells().isEmpty());
    }

    @Test
    public void blockTransitionOpensLockedDoorAfterMapOpenSignal() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));
        snapshot.observeMapOpenDoor(3, 0);
        snapshot.addScan(2, 2L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.OPEN),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.OPEN, plan.doorAt(3, 0).kind());
        assertEquals(1, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertTrue(plan.openedRawNonStartSpecialDoorCells(snapshot).contains(new DungeonLiveMapWriter.CellKey(3, 0)));
        assertTrue(plan.bloodRushOpenedSpecialDoorCells().contains(new DungeonLiveMapWriter.CellKey(3, 0)));
    }

    @Test
    public void remoteOpenSignalDoesNotOverrideVisibleBloodDoor() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Hall", RoomType.NORMAL),
                room(2, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.OPEN, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(door(3, 0, DungeonDoorKind.BLOOD)));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.BLOOD, plan.doorAt(3, 0).kind());
        assertEquals(0, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertTrue(plan.openedRawNonStartSpecialDoorCells(snapshot).isEmpty());
        assertTrue(plan.bloodRushOpenedSpecialDoorCells().isEmpty());
    }

    @Test
    public void localLockedDoorsAreAllKeptWhenTopologyHasDuplicateRoomPairEdges() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Tall Hall", RoomType.NORMAL),
                room(1, 1, "Tall Hall", RoomType.NORMAL),
                room(2, 0, "Tall Branch", RoomType.NORMAL),
                room(2, 1, "Tall Branch", RoomType.NORMAL),
                room(3, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(remoteDoor(5, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD))
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(3, 2, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.WITHER, plan.doorAt(3, 0).kind());
        assertEquals(DungeonDoorKind.WITHER, plan.doorAt(3, 2).kind());
        assertEquals(DungeonDoorKind.BLOOD, plan.doorAt(5, 0).kind());
        assertEquals(3, plan.rawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void localLockedDoorEvidenceWinsOverRemoteOpenDoor() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Hall", RoomType.NORMAL),
                room(2, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.OPEN, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(door(3, 0, DungeonDoorKind.WITHER)));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(DungeonDoorKind.WITHER, plan.doorAt(3, 0).kind());
        assertTrue(plan.bloodRushOpenedSpecialDoorCells().isEmpty());
    }

    @Test
    public void rawSpecialDoorLowerBoundKeepsOpenedDoorsInTheTotal() {
        DungeonMapSnapshot snapshot = remoteBloodRushSnapshot();
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));
        snapshot.addScan(2, 2L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.OPEN),
            door(5, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(2, plan.rawNonStartSpecialDoorCount(snapshot));
        assertEquals(1, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertEquals(1, plan.visibleRawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void fairyWitherExitCountsRegardlessOfRemoteTargetColor() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Pre Fairy", RoomType.NORMAL),
                room(2, 0, "Fairy", RoomType.FAIRY),
                room(3, 0, "After Fairy", RoomType.NORMAL),
                room(4, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.OPEN, RoomType.FAIRY),
                remoteDoor(5, 0, DungeonDoorKind.WITHER, RoomType.FAIRY),
                remoteDoor(7, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(5, 0, DungeonDoorKind.WITHER),
            door(7, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertTrue(plan.bloodRushPath().contains(new DungeonLiveMapWriter.CellKey(5, 0)));
        assertEquals(2, plan.bloodRushLockedSpecialDoorCount());
        assertEquals(2, plan.bloodRushTotalSpecialDoorCount());
        assertEquals(2, plan.knownNonStartSpecialDoorCount());
        assertEquals(2, plan.rawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void fairyAdjacentDoorTargetingNormalStillCountsOnBloodRushPath() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Pre Fairy", RoomType.NORMAL),
                room(2, 0, "Fairy", RoomType.FAIRY),
                room(3, 0, "After Fairy", RoomType.NORMAL),
                room(4, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.OPEN, RoomType.FAIRY),
                remoteDoor(5, 0, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(7, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(5, 0, DungeonDoorKind.WITHER),
            door(7, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertTrue(
            String.join("\n", plan.topologyDebugLines(snapshot)),
            plan.bloodRushPath().contains(new DungeonLiveMapWriter.CellKey(5, 0))
        );
        assertEquals(2, plan.bloodRushLockedSpecialDoorCount());
        assertEquals(2, plan.bloodRushTotalSpecialDoorCount());
        assertEquals(2, plan.knownNonStartSpecialDoorCount());
        assertEquals(2, plan.rawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void fairyExitCountsInRawFallbackBeforeBloodPathIsKnown() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Pre Fairy", RoomType.NORMAL),
                room(2, 0, "Fairy", RoomType.FAIRY),
                room(3, 0, "After Fairy", RoomType.NORMAL),
                room(5, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(9, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.OPEN),
            door(5, 0, DungeonDoorKind.WITHER),
            door(9, 0, DungeonDoorKind.BLOOD)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(2, plan.rawNonStartSpecialDoorCount(snapshot));
        assertEquals(2, plan.visibleRawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void ambiguousLockedFairyDoorsAreNotGuessedAsEntrance() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(4, 3, "Pre Fairy", RoomType.NORMAL),
                room(5, 3, "Fairy", RoomType.FAIRY),
                room(5, 4, "After Fairy", RoomType.NORMAL)
            ),
            List.of(
                remoteDoor(9, 6, DungeonDoorKind.WITHER, RoomType.FAIRY),
                remoteDoor(10, 7, DungeonDoorKind.WITHER, RoomType.FAIRY)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(9, 6, DungeonDoorKind.WITHER),
            door(10, 7, DungeonDoorKind.WITHER)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertFalse(plan.doorAt(9, 6).colorAsSpecial());
        assertFalse(plan.doorAt(10, 7).colorAsSpecial());
        assertEquals(2, plan.rawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void openFairyDoorIsMarkedAsEntranceWithoutStartDistance() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(4, 3, "Pre Fairy", RoomType.NORMAL),
                room(5, 3, "Fairy", RoomType.FAIRY),
                room(5, 4, "After Fairy", RoomType.NORMAL)
            ),
            List.of(
                remoteDoor(9, 6, DungeonDoorKind.OPEN, RoomType.FAIRY),
                remoteDoor(10, 7, DungeonDoorKind.WITHER, RoomType.FAIRY)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(9, 6, DungeonDoorKind.OPEN),
            door(10, 7, DungeonDoorKind.WITHER)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertTrue(plan.doorAt(9, 6).colorAsSpecial());
        assertFalse(plan.doorAt(10, 7).colorAsSpecial());
        assertEquals(1, plan.rawNonStartSpecialDoorCount(snapshot));
    }

    @Test
    public void openFairyEntranceKeepsFairyRenderColor() throws Exception {
        java.lang.reflect.Method colorForDoor = DungeonMapFeature.class.getDeclaredMethod(
            "colorForDoor",
            DungeonLiveMapWriter.DoorRenderInfo.class
        );
        colorForDoor.setAccessible(true);

        int color = (int) colorForDoor.invoke(
            null,
            new DungeonLiveMapWriter.DoorRenderInfo(DungeonDoorKind.OPEN, RoomType.FAIRY, true, true)
        );

        assertEquals(RoomType.FAIRY.color(), color);
    }

    @Test
    public void openedFairyExitStillReducesRemainingDoorCountWhenPathBypassesFairy() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Path 1", RoomType.NORMAL),
                room(2, 0, "Path 2", RoomType.NORMAL),
                room(3, 0, "Blood", RoomType.BLOOD),
                room(1, 1, "Fairy", RoomType.FAIRY),
                room(2, 1, "Side Path", RoomType.NORMAL)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(5, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD),
                remoteDoor(2, 1, DungeonDoorKind.OPEN, RoomType.FAIRY),
                remoteDoor(3, 2, DungeonDoorKind.WITHER, RoomType.FAIRY)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.WITHER),
            door(5, 0, DungeonDoorKind.BLOOD),
            door(2, 1, DungeonDoorKind.OPEN),
            door(3, 2, DungeonDoorKind.WITHER)
        ));
        snapshot.addScan(2, 2L, 0, 0, List.of(
            door(3, 0, DungeonDoorKind.OPEN),
            door(5, 0, DungeonDoorKind.BLOOD),
            door(2, 1, DungeonDoorKind.OPEN),
            door(3, 2, DungeonDoorKind.OPEN)
        ));

        DungeonLiveMapWriter.MatchRenderPlan plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(3, plan.rawNonStartSpecialDoorCount(snapshot));
        assertEquals(2, plan.openedRawNonStartSpecialDoorCount(snapshot));
        assertEquals(2, plan.openedRawNonStartSpecialDoorCells(snapshot).size());
    }

    @Test
    public void fairyExitFromTraceCountsOnceAndOpeningPreservesEntrance() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(0, 1, "First Key", RoomType.NORMAL),
                room(0, 2, "Pre Fairy", RoomType.NORMAL),
                room(0, 3, "Fairy Entrance Side", RoomType.NORMAL),
                room(0, 4, "Fairy", RoomType.FAIRY),
                room(0, 5, "After Fairy", RoomType.NORMAL),
                room(1, 5, "Second Key", RoomType.NORMAL),
                room(2, 5, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(0, 1, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(0, 3, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(0, 5, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(0, 7, DungeonDoorKind.OPEN, RoomType.FAIRY),
                remoteDoor(0, 9, DungeonDoorKind.WITHER, RoomType.FAIRY),
                remoteDoor(1, 10, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(3, 10, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        snapshot.addScan(1, 1L, 0, 0, List.of(
            door(0, 3, DungeonDoorKind.WITHER),
            door(0, 5, DungeonDoorKind.WITHER),
            door(0, 7, DungeonDoorKind.OPEN),
            door(0, 9, DungeonDoorKind.WITHER),
            door(1, 10, DungeonDoorKind.WITHER),
            door(3, 10, DungeonDoorKind.BLOOD)
        ));
        DungeonLiveMapWriter.MatchRenderPlan beforeExit =
            DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertTrue(beforeExit.doorAt(0, 7).colorAsSpecial());
        assertFalse(beforeExit.doorAt(0, 9).colorAsSpecial());
        assertEquals(5, beforeExit.rawNonStartSpecialDoorCount(snapshot));
        assertEquals(5, beforeExit.knownNonStartSpecialDoorCount());
        assertEquals(5, beforeExit.bloodRushTotalSpecialDoorCount());

        snapshot.addScan(2, 2L, 0, 0, List.of(
            door(0, 3, DungeonDoorKind.WITHER),
            door(0, 5, DungeonDoorKind.WITHER),
            door(0, 7, DungeonDoorKind.OPEN),
            door(0, 9, DungeonDoorKind.OPEN),
            door(1, 10, DungeonDoorKind.WITHER),
            door(3, 10, DungeonDoorKind.BLOOD)
        ));
        DungeonLiveMapWriter.MatchRenderPlan afterExit =
            DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);

        assertEquals(5, afterExit.rawNonStartSpecialDoorCount(snapshot));
        assertEquals(1, afterExit.openedRawNonStartSpecialDoorCount(snapshot));
        assertTrue(afterExit.doorAt(0, 7).colorAsSpecial());
        assertFalse(afterExit.doorAt(0, 9).colorAsSpecial());
        assertTrue(afterExit.openedRawNonStartSpecialDoorCells(snapshot)
            .contains(new DungeonLiveMapWriter.CellKey(0, 9)));
    }

    private static DungeonMapSnapshot remoteBloodRushSnapshot() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Hall 1", RoomType.NORMAL),
                room(2, 0, "Hall 2", RoomType.NORMAL),
                room(3, 0, "Blood", RoomType.BLOOD)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(5, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        return snapshot;
    }

    private static DungeonMapSnapshot remoteBloodRushSnapshotWithPuzzleDoor() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(
                room(0, 0, "Start", RoomType.START),
                room(1, 0, "Hall 1", RoomType.NORMAL),
                room(2, 0, "Hall 2", RoomType.NORMAL),
                room(3, 0, "Blood", RoomType.BLOOD),
                room(0, 1, "Side Room", RoomType.NORMAL),
                room(1, 1, "Puzzle", RoomType.PUZZLE)
            ),
            List.of(
                remoteDoor(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL),
                remoteDoor(3, 0, DungeonDoorKind.WITHER, RoomType.NORMAL),
                remoteDoor(5, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)
            )
        );
        return snapshot;
    }

    private static DungeonMapSnapshot.RemoteRoom room(int x, int z, String name, RoomType type) {
        return new DungeonMapSnapshot.RemoteRoom(x, z, name, type, 0, 0, 0, 0, true, false, false, "test", 1L);
    }

    private static DungeonMapSnapshot.RemoteDoor remoteDoor(int x, int z, DungeonDoorKind kind, RoomType targetType) {
        return new DungeonMapSnapshot.RemoteDoor(x, z, kind, targetType, false, "test", 1L);
    }

    private static DungeonScanPoint door(int x, int z, DungeonDoorKind kind) {
        return new DungeonScanPoint(x, z, x * 16, z * 16, DungeonScanPointKind.DOOR, true, 0, 0,
            kind == DungeonDoorKind.NONE || kind == DungeonDoorKind.OPEN ? 0 : 1, kind);
    }

    private static DungeonScanPoint door(int x, int z, DungeonDoorKind kind, int blockId) {
        return new DungeonScanPoint(x, z, x * 16, z * 16, DungeonScanPointKind.DOOR, true, 0, 0, blockId, kind);
    }
}
