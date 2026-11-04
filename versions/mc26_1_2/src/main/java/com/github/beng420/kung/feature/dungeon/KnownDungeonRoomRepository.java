package com.github.beng420.kung.feature.dungeon;

import java.io.IOException;
import java.util.List;

public enum KnownDungeonRoomRepository implements DungeonRoomRepository {
    INSTANCE;

    private DungeonMapSnapshot lastSnapshot;
    private long lastScanRevision = Long.MIN_VALUE;
    private long lastCatalogRevision = Long.MIN_VALUE;
    private List<DungeonKnownRoomCatalog.MatchedRoom> lastMatches = List.of();
    private List<DungeonKnownRoomCatalog.MatchedRoom> lastPredictions;

    @Override
    public long revision() {
        return DungeonKnownRoomCatalog.revision();
    }

    @Override
    public List<DungeonKnownRoomCatalog.MatchedRoom> matchKnownRooms(DungeonMapSnapshot snapshot) {
        long catalogRevision = revision();
        if (lastSnapshot != snapshot || lastScanRevision != snapshot.scanRevision()
            || lastCatalogRevision != catalogRevision) {
            lastMatches = List.copyOf(DungeonKnownRoomCatalog.matchKnownRooms(snapshot));
            lastPredictions = null;
            lastSnapshot = snapshot;
            lastScanRevision = snapshot.scanRevision();
            lastCatalogRevision = catalogRevision;
        }
        return lastMatches;
    }

    @Override
    public List<DungeonKnownRoomCatalog.MatchedRoom> predictedRoomShapes(DungeonMapSnapshot snapshot) {
        matchKnownRooms(snapshot);
        if (lastPredictions == null) {
            lastPredictions = DungeonRoomPrediction.predict(snapshot, DungeonKnownRoomCatalog.loadTemplates());
        }
        return lastPredictions;
    }

    @Override
    public DungeonKnownRoomCatalog.KnownCoreHint knownCoreHint(int coreHash) {
        return DungeonKnownRoomCatalog.knownCoreHint(coreHash);
    }

    @Override
    public DungeonKnownRoomCatalog.KnownCoreHint knownHintForPoint(DungeonMapSnapshot snapshot, DungeonScanPoint point) {
        return DungeonKnownRoomCatalog.knownHintForPoint(snapshot, point);
    }

    @Override
    public DungeonKnownRoomCatalog.AutoLearnResult autoLearnStableHashes(
        DungeonKnownRoomCatalog.MatchedRoom match,
        DungeonMapSnapshot snapshot
    ) throws IOException {
        return DungeonKnownRoomCatalog.autoLearnStableHashes(match, snapshot);
    }
}
