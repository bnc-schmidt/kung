package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.MatchedComponent;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.MatchedRoom;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.RoomTemplate;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.RoomTemplateVariant;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.CellKey;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Cached, render-only completion of every unseen cell in a uniquely placed room. */
final class DungeonRoomPrediction {
    private DungeonRoomPrediction() { }

    static List<MatchedRoom> predict(DungeonMapSnapshot snapshot, List<RoomTemplate> templates) {
        var points = snapshot.points().stream()
            .filter(point -> point.kind() == DungeonScanPointKind.ROOM
                && !DungeonRoomClassifier.isEmptyCore(point.coreHash())).toList();
        Map<Placement, MatchedRoom> candidates = new LinkedHashMap<>();
        for (RoomTemplate template : templates) {
            if (template.components().size() < 2 || template.components().size() > 4) continue;
            var anchors = points.stream().filter(point -> template.components().stream()
                .anyMatch(component -> component.matches(point.coreHash(), point.stableCoreHash()))).toList();
            if (anchors.size() < 2) continue;
            for (RoomTemplateVariant variant : template.variants()) {
                for (DungeonScanPoint point : anchors) {
                    for (var component : variant.components()) {
                        if (!component.matches(point.coreHash(), point.stableCoreHash())) continue;
                        MatchedRoom candidate = candidate(snapshot, variant,
                            point.gridX() / 2 - component.dx(), point.gridZ() / 2 - component.dz());
                        if (candidate != null) candidates.putIfAbsent(new Placement(template.name(), template.type(),
                            template.secrets(), cells(candidate)), candidate);
                    }
                }
            }
        }
        // Different placements sharing any cell are alternatives, never resolved by iteration order.
        Map<CellKey, Integer> alternatives = new HashMap<>();
        candidates.keySet().forEach(key -> key.cells().forEach(cell -> alternatives.merge(cell, 1, Integer::sum)));
        return candidates.entrySet().stream()
            .filter(entry -> entry.getValue().components().stream().anyMatch(component -> component.coreHash() == 0))
            .filter(entry -> entry.getKey().cells().stream().allMatch(cell -> alternatives.get(cell) == 1))
            .map(Map.Entry::getValue).toList();
    }

    private static MatchedRoom candidate(DungeonMapSnapshot snapshot, RoomTemplateVariant variant, int x, int z) {
        List<MatchedComponent> cells = new ArrayList<>();
        int missing = 0;
        for (var component : variant.components()) {
            int roomX = x + component.dx();
            int roomZ = z + component.dz();
            if (roomX < 0 || roomZ < 0 || roomX > 5 || roomZ > 5) return null;
            DungeonScanPoint point = snapshot.pointAt(roomX * 2, roomZ * 2);
            if (point != null && !DungeonRoomClassifier.isEmptyCore(point.coreHash())) {
                if (point.kind() != DungeonScanPointKind.ROOM
                    || !component.matches(point.coreHash(), point.stableCoreHash())) return null;
                cells.add(new MatchedComponent(roomX, roomZ, point.coreHash()));
            } else {
                if (++missing > variant.componentCount() - 2 || !snapshot.allowsRoomPrediction(roomX, roomZ)
                    || point != null && point.roomFullyLoaded()) return null;
                cells.add(new MatchedComponent(roomX, roomZ, 0));
            }
        }
        if (cells.size() - missing < 2) return null;
        for (int first = 0; first < cells.size(); first++) {
            for (int second = first + 1; second < cells.size(); second++) {
                var a = cells.get(first);
                var b = cells.get(second);
                if (Math.abs(a.roomGridX() - b.roomGridX()) + Math.abs(a.roomGridZ() - b.roomGridZ()) != 1) continue;
                int doorX = a.roomGridX() + b.roomGridX();
                int doorZ = a.roomGridZ() + b.roomGridZ();
                var door = snapshot.pointAt(doorX, doorZ);
                if (snapshot.hasMapRoomBoundary(doorX, doorZ)
                    || door != null && door.doorKind().visible()) return null;
            }
        }
        return new MatchedRoom(variant.template(), List.copyOf(cells));
    }

    static Set<CellKey> cells(MatchedRoom room) {
        return room.components().stream().map(c -> new CellKey(c.roomGridX(), c.roomGridZ())).collect(Collectors.toSet());
    }

    private record Placement(String name, RoomType type, int secrets, Set<CellKey> cells) { }
}
