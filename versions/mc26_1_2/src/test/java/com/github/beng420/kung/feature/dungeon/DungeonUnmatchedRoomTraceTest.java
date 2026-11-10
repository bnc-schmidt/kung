package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class DungeonUnmatchedRoomTraceTest {
    private static final DungeonRoomRepository EMPTY_REPOSITORY = new DungeonRoomRepository() {
        @Override
        public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
            return List.of();
        }
    };

    /** A room no catalog entry names stays "?" on the map; its trace line must carry the hash a new entry needs. */
    @Test
    public void aRoomWithNoNameShowsItsHashesInTheTrace() {
        DungeonMapSnapshot snapshot = new DungeonMapSnapshot();
        snapshot.addScan(0, 0, 0, 0, List.of(new DungeonScanPoint(2, 6, -153, 7, DungeonScanPointKind.ROOM, true,
            777, 888, 0, DungeonDoorKind.NONE)));
        snapshot.observeMapVisibleRoom(1, 3);
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY);
        String trace = String.join("\n", plan.topologyDebugLines(snapshot));
        assertTrue(trace, trace.contains("unmatched core=777 stable=888"));
    }
}
