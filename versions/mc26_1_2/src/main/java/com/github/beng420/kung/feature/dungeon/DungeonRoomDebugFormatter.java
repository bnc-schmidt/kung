package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.KungConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DungeonRoomDebugFormatter {
    private DungeonRoomDebugFormatter() {
    }

    static List<String> matchOverlayLines(
        DungeonKnownRoomCatalog.MatchedRoom match,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan
    ) {
        if (!KungConfig.get().dungeon.debugRoomMatches()) {
            return List.of();
        }

        List<String> parts = new ArrayList<>();
        for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
            String state = renderPlan.isVisitedRoom(component.roomGridX(), component.roomGridZ()) ? "v" : "u";
            parts.add(component.roomGridX() + "," + component.roomGridZ()
                + ":" + shortHash(component.coreHash()) + state);
        }
        return splitDebugParts(parts);
    }

    static List<String> hintOverlayLines(DungeonScanPoint point, int roomGridX, int roomGridZ) {
        if (!KungConfig.get().dungeon.debugRoomMatches()) {
            return List.of();
        }
        return List.of(
            "H " + roomGridX + "," + roomGridZ
                + ":" + shortHash(point.coreHash())
                + "/" + shortHash(point.stableCoreHash())
        );
    }

    static List<String> summaryLines(
        String runTimestamp,
        DungeonMapSnapshot snapshot,
        DungeonLiveMapWriter.MatchRenderPlan plan
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("run=" + runTimestamp
            + " scan=" + snapshot.lastScanNumber()
            + " player=" + snapshot.playerGridX() + "," + snapshot.playerGridZ()
            + " rooms=" + plan.observedRoomCount()
            + " rawCells=" + snapshot.observedRoomCount()
            + " matches=" + plan.matches().size()
            + " hints=" + plan.hints().size());

        for (DungeonKnownRoomCatalog.MatchedRoom match : plan.matches()) {
            lines.add("M " + match.template().name()
                + " " + match.template().secrets()
                + "s cells=" + matchCells(match));
        }
        for (Map.Entry<DungeonLiveMapWriter.CellKey, DungeonKnownRoomCatalog.KnownCoreHint> entry
            : plan.hints().entrySet()) {
            DungeonScanPoint point = snapshot.pointAt(entry.getKey().x() * 2, entry.getKey().z() * 2);
            lines.add("H " + entry.getValue().name()
                + " " + entry.getValue().secrets()
                + "s cell=" + entry.getKey().x() + "," + entry.getKey().z()
                + " hash=" + (point == null ? "?" : point.coreHash() + "/" + point.stableCoreHash()));
        }
        return lines;
    }

    private static String matchCells(DungeonKnownRoomCatalog.MatchedRoom match) {
        List<String> cells = new ArrayList<>();
        for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
            cells.add(component.roomGridX() + "," + component.roomGridZ() + ":" + component.coreHash());
        }
        return String.join(" ", cells);
    }

    private static List<String> splitDebugParts(List<String> parts) {
        if (parts.isEmpty()) {
            return List.of();
        }
        String firstLine = "M " + String.join(" ", parts.subList(0, Math.min(2, parts.size())));
        if (parts.size() <= 2) {
            return List.of(firstLine);
        }
        return List.of(firstLine, "M " + String.join(" ", parts.subList(2, parts.size())));
    }

    private static String shortHash(int hash) {
        if (hash == 0) {
            return "0";
        }
        String value = Integer.toString(hash);
        return value.length() <= 5 ? value : value.substring(0, 5);
    }
}
