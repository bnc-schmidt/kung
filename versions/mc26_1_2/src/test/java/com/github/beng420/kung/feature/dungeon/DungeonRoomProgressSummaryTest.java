package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.List;
import org.junit.Test;

public final class DungeonRoomProgressSummaryTest {
    private static final DungeonRoomRepository EMPTY_REPOSITORY = new DungeonRoomRepository() {
        @Override
        public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
            return List.of();
        }
    };

    private static String summary(DungeonMapSnapshot snapshot) {
        return new DungeonRunStats().roomProgressSummary(DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, EMPTY_REPOSITORY));
    }

    @Test
    public void theRoomLineEndsWithTheBloodRushDoors() {
        var snapshot = new DungeonMapSnapshot();
        snapshot.replaceRemoteLiveData(
            List.of(room(0, 0, "Start", RoomType.START), room(1, 0, "Hall 1", RoomType.NORMAL),
                room(2, 0, "Hall 2", RoomType.NORMAL), room(3, 0, "Blood", RoomType.BLOOD)),
            List.of(door(1, 0, DungeonDoorKind.OPEN, RoomType.NORMAL), door(3, 0, DungeonDoorKind.WITHER, RoomType.NORMAL),
                door(5, 0, DungeonDoorKind.BLOOD, RoomType.BLOOD)));
        assertEquals(" | BR 2", summary(snapshot).substring(summary(snapshot).lastIndexOf(" | BR ")));
    }

    @Test
    public void withoutAKnownBloodRoomTheCountIsAQuestionMark() {
        String line = summary(new DungeonMapSnapshot());
        assertEquals(line, " | BR ?", line.substring(line.lastIndexOf(" | BR ")));
    }

    private static DungeonMapSnapshot.RemoteRoom room(int x, int z, String name, RoomType type) {
        return new DungeonMapSnapshot.RemoteRoom(x, z, name, type, 0, 0, 0, 0, true, false, false, "test", 1L);
    }

    private static DungeonMapSnapshot.RemoteDoor door(int x, int z, DungeonDoorKind kind, RoomType targetType) {
        return new DungeonMapSnapshot.RemoteDoor(x, z, kind, targetType, false, "test", 1L);
    }
}
