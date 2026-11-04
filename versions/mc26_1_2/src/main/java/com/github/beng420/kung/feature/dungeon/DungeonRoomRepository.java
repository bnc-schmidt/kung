package com.github.beng420.kung.feature.dungeon;

import java.io.IOException;
import java.util.List;

/** Read-oriented boundary around the persisted dungeon room catalog. */
public interface DungeonRoomRepository {
    default long revision() {
        return 0L;
    }

    List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot);

    default List<DungeonKnownRoomCatalog.MatchedRoom> predictedRoomShapes(DungeonMapSnapshot snapshot) {
        return List.of();
    }

    default DungeonKnownRoomCatalog.KnownCoreHint knownCoreHint(int coreHash) {
        return null;
    }

    default DungeonKnownRoomCatalog.KnownCoreHint knownHintForPoint(DungeonMapSnapshot snapshot, DungeonScanPoint point) {
        var hint = knownCoreHint(point.coreHash());
        return hint != null || point.stableCoreHash() == 0 ? hint : knownCoreHint(point.stableCoreHash());
    }

    default DungeonKnownRoomCatalog.AutoLearnResult autoLearnStableHashes(
        DungeonKnownRoomCatalog.MatchedRoom match,
        DungeonMapSnapshot snapshot
    ) throws IOException {
        return DungeonKnownRoomCatalog.AutoLearnResult.none();
    }
}
