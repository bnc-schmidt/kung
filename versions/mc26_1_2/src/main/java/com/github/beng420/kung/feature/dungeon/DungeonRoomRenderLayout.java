package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.MatchedComponent;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.MatchedRoom;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.RoomTemplate;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.CellKey;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.MatchRenderPlan;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Render-only projections of logical rooms. Never pass these to catalog learning/matching. */
record DungeonRoomRenderLayout(List<MatchedRoom> rooms, Set<CellKey> cells, Set<CellKey> internalDoors) {
    static DungeonRoomRenderLayout from(MatchRenderPlan plan) {
        Map<CellKey, RoomTemplate> metadata = new HashMap<>();
        Map<CellKey, MatchedComponent> components = new HashMap<>();
        for (MatchedRoom match : plan.matches()) {
            for (MatchedComponent component : match.components()) {
                CellKey cell = new CellKey(component.roomGridX(), component.roomGridZ());
                metadata.put(cell, match.template());
                components.put(cell, component);
            }
        }
        plan.hints().forEach((cell, hint) -> metadata.putIfAbsent(cell,
            new RoomTemplate(hint.name(), hint.type(), hint.secrets(), hint.crypts(), hint.prince(), 0, List.of())));

        List<CellKey> ordered = new ArrayList<>(plan.roomOwners().keySet());
        ordered.sort(CellKey.ORDER);
        Set<CellKey> seen = new HashSet<>();
        Set<CellKey> rendered = new HashSet<>();
        List<MatchedRoom> rooms = new ArrayList<>();
        for (CellKey first : ordered) {
            if (!seen.add(first)) continue;
            String owner = plan.roomOwners().get(first);
            List<CellKey> group = new ArrayList<>();
            ArrayDeque<CellKey> pending = new ArrayDeque<>();
            pending.add(first);
            while (!pending.isEmpty()) {
                CellKey cell = pending.removeFirst();
                group.add(cell);
                for (CellKey neighbor : cell.neighbors()) {
                    if (owner.equals(plan.roomOwners().get(neighbor))
                        && plan.internalDoors().contains(cell.doorTo(neighbor))
                        && seen.add(neighbor)) {
                        pending.addLast(neighbor);
                    }
                }
            }
            // Retain the room catalog's safety limit even if bad remote ownership leaks in.
            if (group.size() > 4) {
                for (CellKey cell : group) addRoom(List.of(cell), plan, metadata, components, rooms, rendered);
            } else {
                addRoom(group, plan, metadata, components, rooms, rendered);
            }
        }
        Set<CellKey> doors = new HashSet<>(plan.internalDoors());
        for (MatchedRoom prediction : plan.predictedRooms()) {
            Set<CellKey> predictedCells = DungeonRoomPrediction.cells(prediction);
            if (prediction.components().stream().anyMatch(c -> c.coreHash() == 0
                && plan.roomOwners().containsKey(new CellKey(c.roomGridX(), c.roomGridZ())))) continue;
            // A prediction cannot cut up an existing logical room or overwrite remote evidence.
            if (rooms.stream().anyMatch(room -> room.components().stream().anyMatch(c -> prediction.contains(c.roomGridX(), c.roomGridZ()))
                && !predictedCells.containsAll(DungeonRoomPrediction.cells(room)))) continue;
            Set<CellKey> predictedDoors = new HashSet<>();
            for (CellKey first : predictedCells) for (CellKey second : predictedCells) {
                CellKey door = first.doorTo(second);
                if (door != null) predictedDoors.add(door);
            }
            if (predictedDoors.stream().anyMatch(plan.externalDoors()::containsKey)) continue;
            rooms.removeIf(room -> room.components().stream().anyMatch(c -> prediction.contains(c.roomGridX(), c.roomGridZ())));
            rooms.add(prediction);
            rendered.addAll(predictedCells);
            doors.addAll(predictedDoors);
        }
        return new DungeonRoomRenderLayout(List.copyOf(rooms), Set.copyOf(rendered), Set.copyOf(doors));
    }

    boolean isInternalDoor(int x, int z) {
        return internalDoors.contains(new CellKey(x, z));
    }

    private static void addRoom(List<CellKey> cells, MatchRenderPlan plan, Map<CellKey, RoomTemplate> metadata,
        Map<CellKey, MatchedComponent> components, List<MatchedRoom> rooms, Set<CellKey> rendered) {
        RoomTemplate template = null;
        // Prefer direct matches over hints for canonical metadata such as Prince/crypt counts.
        for (CellKey cell : cells) {
            if (components.containsKey(cell)) {
                template = metadata.get(cell);
                break;
            }
        }
        if (template == null) {
            for (CellKey cell : cells) {
                if ((template = metadata.get(cell)) != null) break;
            }
            if (template != null) {
                int secrets = template.secrets();
                for (CellKey cell : cells) {
                    secrets = Math.max(secrets, plan.remoteRoomSecretsMax(cell.x(), cell.z(), secrets));
                }
                if (secrets != template.secrets()) {
                    template = new RoomTemplate(template.name(), template.type(), secrets, template.crypts(),
                        template.prince(), template.variantNumber(), template.components());
                }
            }
        }
        if (template == null && cells.size() == 1) {
            CellKey cell = cells.getFirst();
            RoomType mapType = plan.roomTypeAt(cell.x(), cell.z());
            // Map color confirms the type, not Old/New Trap or which puzzle, nor their secret/crypt totals.
            if (mapType == RoomType.TRAP) {
                template = new RoomTemplate("Trap", RoomType.TRAP, -1, -1, false, 0, List.of());
            } else if (mapType == RoomType.PUZZLE) {
                template = new RoomTemplate(plan.mapPuzzleNames().getOrDefault(cell, "Puzzle"),
                    RoomType.PUZZLE, -1, -1, false, 0, List.of());
            }
        }
        if (template == null) return;
        RoomType renderedType = plan.roomTypeAt(cells.getFirst().x(), cells.getFirst().z());
        if (renderedType == RoomType.RARE && template.type() != RoomType.RARE) {
            template = new RoomTemplate(template.name(), RoomType.RARE, template.secrets(), template.crypts(),
                template.prince(), template.variantNumber(), template.components());
        }
        List<MatchedComponent> grouped = cells.stream()
            .sorted(CellKey.ORDER)
            .map(cell -> components.getOrDefault(cell, new MatchedComponent(cell.x(), cell.z(), 0)))
            .toList();
        rooms.add(new MatchedRoom(template, grouped));
        rendered.addAll(cells);
    }
}
