package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.feature.dungeon.room.RoomType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

final class DungeonMapCheckmarkReader {
    private static final int MAP_SIZE = 128;
    private static final int MAP_ROOM_GAP = 4;
    private static final int MAP_SEARCH_STEP = 10;
    private static final int MIN_ROOM_SIZE = 5;
    private static final int CHECKMARK_SCAN_RADIUS = 6;
    private static final int CHECKMARK_MIN_PIXELS = 4;
    private static final int CHECKMARK_MAX_PIXELS = 42;
    private static final int CHECKMARK_MIN_SPAN = 3;
    private static final int RED_CHECK_COLOR = 18;
    private static final int GREEN_CHECK_COLOR = 30;
    private static final int ENTRANCE_COLOR = GREEN_CHECK_COLOR;
    private static final int WHITE_CHECK_COLOR = 34;
    private static final int PLAYER_DECORATION_SEARCH_RADIUS = 3;
    private static String lastObserveDiagnostic = "";
    private static MapItemSavedData cachedAnchorMap;
    private static DungeonMapSnapshot.GridKey cachedAnchorEntranceRoom;
    private static MapAnchor cachedAnchor;
    private static DungeonMapSnapshot cachedAnchorSnapshot;
    private static int lastAnchorSearchTick = Integer.MIN_VALUE;

    private DungeonMapCheckmarkReader() {
    }

    static void observe(Minecraft client, DungeonMapSnapshot snapshot) {
        MapItemSavedData map = mapData(client);
        if (map == null) {
            clearCachedAnchor();
            logObserveDiagnostic("map=null");
            return;
        }

        MapAnchor anchor = mapAnchor(map, snapshot);
        if (anchor == null) {
            logObserveDiagnostic("anchor=null decorations=" + decorationCount(map)
                + " entranceRoom=" + (entranceRoom(snapshot) != null)
                + " playerMarker=" + (mapPlayerPos(map) != null));
            return;
        }

        int stride = anchor.roomSize() + MAP_ROOM_GAP;
        int visibleRooms = 0;
        int visitedRooms = 0;
        int clearedRooms = 0;
        int completedRooms = 0;
        Set<DungeonMapSnapshot.GridKey> visibleMapRooms = new HashSet<>();
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                MapPixel roomTopLeft = mapRoomTopLeft(anchor, roomGridX, roomGridZ, stride);
                int mapX = roomTopLeft.x();
                int mapZ = roomTopLeft.z();
                DungeonMapClearState clearState = checkmarkState(map, mapX, mapZ, anchor.roomSize());
                if (isVisibleRoom(map, mapX, mapZ, anchor.roomSize())) {
                    snapshot.observeMapVisibleRoom(roomGridX, roomGridZ);
                    if (isTrapRoom(map, mapX, mapZ, anchor.roomSize())) {
                        snapshot.observeMapTrapRoom(roomGridX, roomGridZ);
                    } else if (isPuzzleRoom(map, mapX, mapZ, anchor.roomSize())) {
                        snapshot.observeMapPuzzleRoom(roomGridX, roomGridZ);
                    }
                    visibleMapRooms.add(new DungeonMapSnapshot.GridKey(roomGridX, roomGridZ));
                    visibleRooms++;
                }
                snapshot.observeRoomClearState(roomGridX, roomGridZ, clearState);
                if (clearState != DungeonMapClearState.UNCLEARED || isVisitedRoom(map, mapX, mapZ, anchor.roomSize())) {
                    snapshot.observeVisitedRoom(roomGridX, roomGridZ);
                    visitedRooms++;
                }
                if (clearState == DungeonMapClearState.CLEARED) {
                    clearedRooms++;
                } else if (clearState == DungeonMapClearState.COMPLETED) {
                    clearedRooms++;
                    completedRooms++;
                }
            }
        }
        MapDoorObservation doorObservation = observeMapDoors(map, snapshot, anchor, visibleMapRooms, stride);
        logObserveDiagnostic("anchor="
            + anchor.mapEntranceX()
            + ","
            + anchor.mapEntranceZ()
            + " size="
            + anchor.roomSize()
            + " visible="
            + visibleRooms
            + " visited="
            + visitedRooms
            + " cleared="
            + clearedRooms
            + " completed="
            + completedRooms
            + " mapOpenDoors="
            + doorObservation.openDoors()
            + " mapRoomConnections="
            + doorObservation.roomConnections());
    }

    static MapRoom roomFromDecoration(MapDecoration decoration, Minecraft client, DungeonMapSnapshot snapshot) {
        MapItemSavedData map = mapData(client);
        if (map == null) {
            return null;
        }

        MapAnchor anchor = mapAnchor(map, snapshot);
        if (anchor == null) {
            return null;
        }

        MapPixel center = decorationPixel(decoration);
        int bestDistance = Integer.MAX_VALUE;
        MapRoom bestRoom = null;
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                MapPixel roomCenter = mapRoomCenter(anchor, roomGridX, roomGridZ);
                int dx = roomCenter.x() - center.x();
                int dz = roomCenter.z() - center.z();
                int distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestRoom = new MapRoom(roomGridX, roomGridZ, roomCenter.x(), roomCenter.z(), anchor.roomSize());
                }
            }
        }

        int radius = Math.max(anchor.roomSize() / 2 + PLAYER_DECORATION_SEARCH_RADIUS, 6);
        return bestDistance <= radius * radius ? bestRoom : null;
    }

    static MapPixel mapPixelForDecoration(MapDecoration decoration) {
        return decorationPixel(decoration);
    }

    static boolean isPlayerDecoration(MapDecoration decoration) {
        return decoration.type().equals(MapDecorationTypes.PLAYER)
            || decoration.type().equals(MapDecorationTypes.PLAYER_OFF_MAP)
            || decoration.type().equals(MapDecorationTypes.PLAYER_OFF_LIMITS)
            || decoration.type().equals(MapDecorationTypes.BLUE_MARKER)
            || decoration.type().equals(MapDecorationTypes.FRAME);
    }

    static String decorationName(MapDecoration decoration) {
        return decoration.name()
            .map(component -> component.getString().replaceAll("§.", "").trim())
            .orElse("");
    }

    static String decorationTypeName(MapDecoration decoration) {
        if (decoration.type().equals(MapDecorationTypes.PLAYER)) {
            return "PLAYER";
        }
        if (decoration.type().equals(MapDecorationTypes.PLAYER_OFF_MAP)) {
            return "PLAYER_OFF_MAP";
        }
        if (decoration.type().equals(MapDecorationTypes.PLAYER_OFF_LIMITS)) {
            return "PLAYER_OFF_LIMITS";
        }
        if (decoration.type().equals(MapDecorationTypes.BLUE_MARKER)) {
            return "BLUE_MARKER";
        }
        if (decoration.type().equals(MapDecorationTypes.FRAME)) {
            return "FRAME";
        }
        return String.valueOf(decoration.type());
    }

    static MapPixel overlayPixelForDecoration(
        MapDecoration decoration,
        Minecraft client,
        DungeonMapSnapshot snapshot,
        int overlayRoomSize,
        int overlayDoorSize,
        int overlayCellGap
    ) {
        MapItemSavedData map = mapData(client);
        if (map == null) {
            return null;
        }

        MapAnchor anchor = mapAnchor(map, snapshot);
        DungeonMapSnapshot.GridKey entranceRoom = entranceRoom(snapshot);
        if (anchor == null || entranceRoom == null) {
            return null;
        }

        MapPixel decorationPixel = decorationPixel(decoration);
        int mapStride = anchor.roomSize() + MAP_ROOM_GAP;
        int overlayStride = overlayRoomSize + overlayDoorSize + overlayCellGap * 2;
        int overlayEntranceCenterX = scanGridToOverlayPixel(entranceRoom.gridX() * 2, overlayRoomSize, overlayDoorSize, overlayCellGap)
            + overlayRoomSize / 2;
        int overlayEntranceCenterZ = scanGridToOverlayPixel(entranceRoom.gridZ() * 2, overlayRoomSize, overlayDoorSize, overlayCellGap)
            + overlayRoomSize / 2;
        int mapEntranceCenterX = anchor.mapEntranceX() + anchor.roomSize() / 2;
        int mapEntranceCenterZ = anchor.mapEntranceZ() + anchor.roomSize() / 2;
        int overlayGridSize = overlayGridPixelSize(overlayRoomSize, overlayDoorSize, overlayCellGap);
        int overlayX = overlayEntranceCenterX
            + Math.round((decorationPixel.x() - mapEntranceCenterX) * overlayStride / (float) mapStride);
        int overlayZ = overlayEntranceCenterZ
            + Math.round((decorationPixel.z() - mapEntranceCenterZ) * overlayStride / (float) mapStride);
        return new MapPixel(
            Math.clamp(overlayX, 0, overlayGridSize),
            Math.clamp(overlayZ, 0, overlayGridSize)
        );
    }

    private static MapItemSavedData mapData(Minecraft client) {
        if (client.level == null || client.player == null) {
            return null;
        }
        return DungeonMapItems.mapData(client);
    }

    private static MapAnchor mapAnchor(MapItemSavedData map, DungeonMapSnapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        int tick = client.player == null ? 0 : client.player.tickCount;
        if (map == cachedAnchorMap && snapshot == cachedAnchorSnapshot && lastAnchorSearchTick == tick) {
            return cachedAnchor;
        }
        DungeonMapSnapshot.GridKey entranceRoom = entranceRoom(snapshot);
        if (entranceRoom == null) {
            return null;
        }

        if (map == cachedAnchorMap && snapshot == cachedAnchorSnapshot
            && entranceRoom.equals(cachedAnchorEntranceRoom) && cachedAnchor != null
            && isEntranceColor(map, cachedAnchor.mapEntranceX(), cachedAnchor.mapEntranceZ())
            && mapRoomSize(map, cachedAnchor.mapEntranceX(), cachedAnchor.mapEntranceZ()) == cachedAnchor.roomSize()) {
            lastAnchorSearchTick = tick;
            return cachedAnchor;
        }

        MapEntrance mapEntrance = mapEntrance(map);
        cachedAnchorMap = map;
        cachedAnchorSnapshot = snapshot;
        cachedAnchorEntranceRoom = entranceRoom;
        lastAnchorSearchTick = tick;
        if (mapEntrance == null) {
            cachedAnchor = null;
            return null;
        }

        MapAnchor anchor = new MapAnchor(
            mapEntrance.x(),
            mapEntrance.z(),
            mapEntrance.roomSize(),
            physicalRoomX(entranceRoom.gridX()),
            physicalRoomZ(entranceRoom.gridZ())
        );
        cachedAnchorMap = map;
        cachedAnchorEntranceRoom = entranceRoom;
        cachedAnchor = anchor;
        return anchor;
    }

    private static void clearCachedAnchor() {
        cachedAnchorMap = null;
        cachedAnchorEntranceRoom = null;
        cachedAnchor = null;
        cachedAnchorSnapshot = null;
        lastAnchorSearchTick = Integer.MIN_VALUE;
    }

    private static DungeonMapSnapshot.GridKey entranceRoom(DungeonMapSnapshot snapshot) {
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                DungeonMapSnapshot.ObservedPoint point = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
                if (point == null
                    || point.point().kind() != DungeonScanPointKind.ROOM
                    || DungeonRoomClassifier.isEmptyCore(point.point().coreHash())) {
                    continue;
                }
                if (DungeonRoomClassifier.classifyRoom(point.point().coreHash()) == RoomType.START) {
                    return new DungeonMapSnapshot.GridKey(roomGridX, roomGridZ);
                }
            }
        }

        DungeonMapSnapshot.GridKey startRoom = snapshot.startRoom();
        if (startRoom == null) {
            return null;
        }
        return new DungeonMapSnapshot.GridKey(startRoom.gridX() / 2, startRoom.gridZ() / 2);
    }

    private static MapEntrance mapEntrance(MapItemSavedData map) {
        MapPixel mapPlayerPos = mapPlayerPos(map);
        if (mapPlayerPos == null) {
            return null;
        }

        Queue<MapPixel> toCheck = new ArrayDeque<>();
        Set<MapPixel> checked = new HashSet<>();
        toCheck.add(mapPlayerPos);
        checked.add(mapPlayerPos);

        while (!toCheck.isEmpty()) {
            MapPixel pos = toCheck.remove();
            if (isEntranceColor(map, pos.x(), pos.z())) {
                MapEntrance entrance = mapEntranceAt(map, pos);
                if (entrance != null) {
                    return entrance;
                }
            }

            addEntranceSearchPos(toCheck, checked, pos.x() - MAP_SEARCH_STEP, pos.z());
            addEntranceSearchPos(toCheck, checked, pos.x(), pos.z() - MAP_SEARCH_STEP);
            addEntranceSearchPos(toCheck, checked, pos.x() + MAP_SEARCH_STEP, pos.z());
            addEntranceSearchPos(toCheck, checked, pos.x(), pos.z() + MAP_SEARCH_STEP);
        }

        return null;
    }

    private static void addEntranceSearchPos(Queue<MapPixel> toCheck, Set<MapPixel> checked, int x, int z) {
        if (!insideMap(x, z)) {
            return;
        }

        MapPixel pos = new MapPixel(x, z);
        if (checked.add(pos)) {
            toCheck.add(pos);
        }
    }

    private static MapEntrance mapEntranceAt(MapItemSavedData map, MapPixel pos) {
        int x = pos.x();
        int z = pos.z();
        while (isEntranceColor(map, x - 1, z)) {
            x--;
        }
        while (isEntranceColor(map, x, z - 1)) {
            z--;
        }

        int roomSize = mapRoomSize(map, x, z);
        if (roomSize <= MIN_ROOM_SIZE) {
            return null;
        }
        return new MapEntrance(x, z, roomSize);
    }

    private static int mapRoomSize(MapItemSavedData map, int roomX, int roomZ) {
        int size = 0;
        while (isEntranceColor(map, roomX + size, roomZ)) {
            size++;
        }
        return size;
    }

    private static MapPixel mapPlayerPos(MapItemSavedData map) {
        for (MapDecoration decoration : map.getDecorations()) {
            if (decoration.type().equals(MapDecorationTypes.FRAME)) {
                return decorationPixel(decoration);
            }
        }
        for (MapDecoration decoration : map.getDecorations()) {
            if (decoration.type().equals(MapDecorationTypes.PLAYER)
                || decoration.type().equals(MapDecorationTypes.BLUE_MARKER)) {
                MapPixel pixel = decorationPixel(decoration);
                if (insideMap(pixel.x(), pixel.z())) {
                    return pixel;
                }
            }
        }
        return null;
    }

    private static int decorationCount(MapItemSavedData map) {
        int count = 0;
        for (MapDecoration ignored : map.getDecorations()) {
            count++;
        }
        return count;
    }

    private static void logObserveDiagnostic(String diagnostic) {
        if (diagnostic.equals(lastObserveDiagnostic)) {
            return;
        }
        lastObserveDiagnostic = diagnostic;
        com.github.beng420.kung.util.KungDebugRecorder.event("map-check", diagnostic);
    }

    private static MapPixel decorationPixel(MapDecoration decoration) {
        return new MapPixel((decoration.x() >> 1) + MAP_SIZE / 2, (decoration.y() >> 1) + MAP_SIZE / 2);
    }

    private static MapPixel mapRoomCenter(MapAnchor anchor, int roomGridX, int roomGridZ) {
        int stride = anchor.roomSize() + MAP_ROOM_GAP;
        MapPixel topLeft = mapRoomTopLeft(anchor, roomGridX, roomGridZ, stride);
        return new MapPixel(topLeft.x() + anchor.roomSize() / 2, topLeft.z() + anchor.roomSize() / 2);
    }

    private static MapPixel mapRoomTopLeft(MapAnchor anchor, int roomGridX, int roomGridZ, int stride) {
        int physicalX = physicalRoomX(roomGridX);
        int physicalZ = physicalRoomZ(roomGridZ);
        int mapX = anchor.mapEntranceX()
            + ((physicalX - anchor.physicalEntranceX()) / DungeonScanUtils.ROOM_SIZE_BLOCKS) * stride;
        int mapZ = anchor.mapEntranceZ()
            + ((physicalZ - anchor.physicalEntranceZ()) / DungeonScanUtils.ROOM_SIZE_BLOCKS) * stride;
        return new MapPixel(mapX, mapZ);
    }

    private static MapDoorObservation observeMapDoors(
        MapItemSavedData map,
        DungeonMapSnapshot snapshot,
        MapAnchor anchor,
        Set<DungeonMapSnapshot.GridKey> visibleRooms,
        int stride
    ) {
        int openDoors = 0;
        int roomConnections = 0;
        int maxRoomGrid = DungeonScanUtils.SCAN_GRID_SIZE / 2;
        for (int roomGridZ = 0; roomGridZ <= maxRoomGrid; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= maxRoomGrid; roomGridX++) {
                DungeonMapSnapshot.GridKey room = new DungeonMapSnapshot.GridKey(roomGridX, roomGridZ);
                if (!visibleRooms.contains(room)) {
                    continue;
                }

                if (roomGridX < maxRoomGrid) {
                    DungeonMapSnapshot.GridKey east = new DungeonMapSnapshot.GridKey(roomGridX + 1, roomGridZ);
                    if (visibleRooms.contains(east)) {
                        DoorBridge bridge = horizontalBridge(map, anchor, roomGridX, roomGridZ, stride);
                        if (bridge.connected()) {
                            snapshot.observeMapOpenDoor(roomGridX * 2 + 1, roomGridZ * 2);
                            openDoors++;
                        }
                        if (bridge.roomConnection()) {
                            snapshot.observeMapRoomConnection(roomGridX * 2 + 1, roomGridZ * 2);
                            roomConnections++;
                        }
                    }
                }

                if (roomGridZ < maxRoomGrid) {
                    DungeonMapSnapshot.GridKey south = new DungeonMapSnapshot.GridKey(roomGridX, roomGridZ + 1);
                    if (visibleRooms.contains(south)) {
                        DoorBridge bridge = verticalBridge(map, anchor, roomGridX, roomGridZ, stride);
                        if (bridge.connected()) {
                            snapshot.observeMapOpenDoor(roomGridX * 2, roomGridZ * 2 + 1);
                            openDoors++;
                        }
                        if (bridge.roomConnection()) {
                            snapshot.observeMapRoomConnection(roomGridX * 2, roomGridZ * 2 + 1);
                            roomConnections++;
                        }
                    }
                }
            }
        }
        return new MapDoorObservation(openDoors, roomConnections);
    }

    private static DoorBridge horizontalBridge(
        MapItemSavedData map,
        MapAnchor anchor,
        int roomGridX,
        int roomGridZ,
        int stride
    ) {
        MapPixel west = mapRoomTopLeft(anchor, roomGridX, roomGridZ, stride);
        int gapStartX = west.x() + anchor.roomSize();
        int gapEndX = west.x() + stride;
        return bridgePixels(map, gapStartX, gapEndX, west.z(), west.z() + anchor.roomSize());
    }

    private static DoorBridge verticalBridge(
        MapItemSavedData map,
        MapAnchor anchor,
        int roomGridX,
        int roomGridZ,
        int stride
    ) {
        MapPixel north = mapRoomTopLeft(anchor, roomGridX, roomGridZ, stride);
        int gapStartZ = north.z() + anchor.roomSize();
        int gapEndZ = north.z() + stride;
        return bridgePixels(map, north.x(), north.x() + anchor.roomSize(), gapStartZ, gapEndZ);
    }

    private static DoorBridge bridgePixels(MapItemSavedData map, int minX, int maxX, int minZ, int maxZ) {
        int sampled = 0;
        int visible = 0;
        int centerVisible = 0;
        int centerSamples = 0;
        int width = Math.max(1, maxX - minX);
        int height = Math.max(1, maxZ - minZ);
        int centerMinX = minX + width / 3;
        int centerMaxX = maxX - width / 3;
        int centerMinZ = minZ + height / 3;
        int centerMaxZ = maxZ - height / 3;
        for (int z = minZ; z < maxZ; z++) {
            for (int x = minX; x < maxX; x++) {
                int color = colorAt(map, x, z);
                if (color < 0) {
                    continue;
                }
                sampled++;
                boolean inCenter = x >= centerMinX && x < centerMaxX && z >= centerMinZ && z < centerMaxZ;
                if (inCenter) {
                    centerSamples++;
                }
                if (!isVisibleRoomColor(color)) {
                    continue;
                }
                visible++;
                if (inCenter) {
                    centerVisible++;
                }
            }
        }

        boolean connected = sampled > 0
            && (centerVisible >= Math.max(2, centerSamples / 3) || visible >= Math.max(2, sampled / 5));
        boolean roomConnection = sampled > 0 && visible >= Math.max(3, sampled * 3 / 5);
        return new DoorBridge(connected, roomConnection);
    }

    private static int scanGridToOverlayPixel(int gridPosition, int roomSize, int doorSize, int cellGap) {
        int pixel = 0;
        for (int index = 0; index < gridPosition; index++) {
            pixel += (index & 1) == 0 ? roomSize : doorSize;
            pixel += cellGap;
        }
        return pixel;
    }

    private static int overlayGridPixelSize(int roomSize, int doorSize, int cellGap) {
        return scanGridToOverlayPixel(DungeonScanUtils.SCAN_GRID_SIZE, roomSize, doorSize, cellGap);
    }

    private static DungeonMapClearState checkmarkState(MapItemSavedData map, int roomX, int roomZ, int roomSize) {
        int halfRoomSize = roomSize / 2;
        int x = roomX + halfRoomSize;
        int z = roomZ + halfRoomSize;
        int horizontalRadius = Math.max(2, halfRoomSize - 2);
        int topOffset = -Math.max(1, halfRoomSize / 3);
        for (int offsetZ = topOffset; offsetZ < halfRoomSize; offsetZ++) {
            for (int offsetX = -horizontalRadius; offsetX <= horizontalRadius; offsetX++) {
                int checkX = x + offsetX;
                int checkZ = z + offsetZ;
                int color = colorAt(map, checkX, checkZ);
                if (color == GREEN_CHECK_COLOR && isCheckmarkGlyph(map, checkX, checkZ, GREEN_CHECK_COLOR)) {
                    return DungeonMapClearState.COMPLETED;
                }
            }
        }
        for (int offsetZ = topOffset; offsetZ < halfRoomSize; offsetZ++) {
            for (int offsetX = -horizontalRadius; offsetX <= horizontalRadius; offsetX++) {
                int checkX = x + offsetX;
                int checkZ = z + offsetZ;
                int color = colorAt(map, checkX, checkZ);
                if ((color == WHITE_CHECK_COLOR || color == RED_CHECK_COLOR) && isCheckmarkGlyph(map, checkX, checkZ, color)) {
                    return DungeonMapClearState.CLEARED;
                }
            }
        }
        return DungeonMapClearState.UNCLEARED;
    }

    private static boolean isCheckmarkGlyph(MapItemSavedData map, int centerX, int centerZ, int checkColor) {
        if (colorAt(map, centerX, centerZ) != checkColor) {
            return false;
        }

        Queue<MapPixel> toCheck = new ArrayDeque<>();
        Set<MapPixel> component = new HashSet<>();
        MapPixel start = new MapPixel(centerX, centerZ);
        toCheck.add(start);
        component.add(start);

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean touchesSearchEdge = false;

        while (!toCheck.isEmpty()) {
            MapPixel pixel = toCheck.remove();
            minX = Math.min(minX, pixel.x());
            maxX = Math.max(maxX, pixel.x());
            minZ = Math.min(minZ, pixel.z());
            maxZ = Math.max(maxZ, pixel.z());
            if (Math.abs(pixel.x() - centerX) == CHECKMARK_SCAN_RADIUS
                || Math.abs(pixel.z() - centerZ) == CHECKMARK_SCAN_RADIUS) {
                touchesSearchEdge = true;
            }

            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nextX = pixel.x() + dx;
                    int nextZ = pixel.z() + dz;
                    if (Math.abs(nextX - centerX) > CHECKMARK_SCAN_RADIUS
                        || Math.abs(nextZ - centerZ) > CHECKMARK_SCAN_RADIUS
                        || colorAt(map, nextX, nextZ) != checkColor) {
                        continue;
                    }

                    MapPixel next = new MapPixel(nextX, nextZ);
                    if (component.add(next)) {
                        toCheck.add(next);
                    }
                }
            }
        }

        int count = component.size();
        if (count < CHECKMARK_MIN_PIXELS || count > CHECKMARK_MAX_PIXELS) {
            return false;
        }
        if (touchesSearchEdge) {
            return false;
        }

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        int area = width * height;
        return width >= CHECKMARK_MIN_SPAN
            && height >= CHECKMARK_MIN_SPAN
            && count * 4 <= area * 3;
    }

    private static boolean isVisitedRoom(MapItemSavedData map, int roomX, int roomZ, int roomSize) {
        int interiorStart = Math.max(1, roomSize / 5);
        int interiorEnd = Math.max(interiorStart + 1, roomSize - interiorStart);
        int visitedPixels = 0;
        int sampledPixels = 0;
        for (int z = roomZ + interiorStart; z < roomZ + interiorEnd; z++) {
            for (int x = roomX + interiorStart; x < roomX + interiorEnd; x++) {
                int color = colorAt(map, x, z);
                if (color < 0) {
                    continue;
                }
                sampledPixels++;
                if (isVisitedRoomColor(color)) {
                    visitedPixels++;
                }
            }
        }
        return sampledPixels > 0 && visitedPixels >= Math.max(3, sampledPixels / 3);
    }

    private static boolean isVisibleRoom(MapItemSavedData map, int roomX, int roomZ, int roomSize) {
        int interiorStart = Math.max(1, roomSize / 5);
        int interiorEnd = Math.max(interiorStart + 1, roomSize - interiorStart);
        int visiblePixels = 0;
        int sampledPixels = 0;
        for (int z = roomZ + interiorStart; z < roomZ + interiorEnd; z++) {
            for (int x = roomX + interiorStart; x < roomX + interiorEnd; x++) {
                int color = colorAt(map, x, z);
                if (color < 0) {
                    continue;
                }
                sampledPixels++;
                if (isVisibleRoomColor(color)) {
                    visiblePixels++;
                }
            }
        }
        return sampledPixels > 0 && visiblePixels >= Math.max(3, sampledPixels / 4);
    }

    static boolean isTrapRoom(MapItemSavedData map, int roomX, int roomZ, int roomSize) {
        // Hypixel uses 62 for Trap but 63 (the same base color) for normal rooms.
        return isRoomColor(map, roomX, roomZ, roomSize, 62);
    }

    /** Purple (66). Unlike the room's blocks it survives the puzzle being solved - a solved Blaze scans as a new hash. */
    static boolean isPuzzleRoom(MapItemSavedData map, int roomX, int roomZ, int roomSize) {
        return isRoomColor(map, roomX, roomZ, roomSize, 66);
    }

    private static boolean isRoomColor(MapItemSavedData map, int roomX, int roomZ, int roomSize, int roomColor) {
        int roomPixels = 0;
        int sampledPixels = 0;
        for (int z = roomZ + 1; z < roomZ + roomSize - 1; z++) {
            for (int x = roomX + 1; x < roomX + roomSize - 1; x++) {
                int color = colorAt(map, x, z);
                if (color < 0) return false;
                sampledPixels++;
                if (color == roomColor) roomPixels++;
            }
        }
        // A majority tolerates the central completion glyph without trusting isolated pixels.
        return sampledPixels >= 9 && roomPixels > sampledPixels / 2;
    }

    private static boolean isVisitedRoomColor(int color) {
        if (color == RED_CHECK_COLOR || color == GREEN_CHECK_COLOR || color == WHITE_CHECK_COLOR) {
            return true;
        }

        int baseColor = color >> 2;
        int brightness = color & 3;
        if (baseColor == 0 || brightness == 0) {
            return false;
        }

        return switch (baseColor) {
            case 4, 7, 10, 13, 15, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27, 28, 30, 31, 34, 35, 36, 37, 38, 39,
                40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55 -> true;
            default -> false;
        };
    }

    private static boolean isVisibleRoomColor(int color) {
        if (isVisitedRoomColor(color)) {
            return true;
        }

        int baseColor = color >> 2;
        int brightness = color & 3;
        return baseColor != 0 && brightness > 0;
    }

    private static boolean isEntranceColor(MapItemSavedData map, int x, int z) {
        return colorAt(map, x, z) == ENTRANCE_COLOR;
    }

    private static int physicalRoomX(int roomGridX) {
        return physicalRoomCoordinate(DungeonScanUtils.worldXForScanGrid(roomGridX * 2));
    }

    private static int physicalRoomZ(int roomGridZ) {
        return physicalRoomCoordinate(DungeonScanUtils.worldZForScanGrid(roomGridZ * 2));
    }

    private static int physicalRoomCoordinate(int coordinate) {
        int shifted = (int) (coordinate + 8.5);
        return shifted - Math.floorMod(shifted, DungeonScanUtils.ROOM_SIZE_BLOCKS) - 8;
    }

    private static boolean insideMap(int x, int z) {
        return x >= 0 && z >= 0 && x < MAP_SIZE && z < MAP_SIZE;
    }

    private static int colorAt(MapItemSavedData map, int x, int z) {
        if (x < 0 || z < 0 || x >= MAP_SIZE || z >= MAP_SIZE) {
            return -1;
        }
        return map.colors[x + (z << 7)] & 0xFF;
    }

    private record MapAnchor(int mapEntranceX, int mapEntranceZ, int roomSize, int physicalEntranceX, int physicalEntranceZ) {
    }

    private record MapEntrance(int x, int z, int roomSize) {
    }

    private record DoorBridge(boolean connected, boolean roomConnection) {
    }

    private record MapDoorObservation(int openDoors, int roomConnections) {
    }

    record MapPixel(int x, int z) {
    }

    record MapRoom(int roomGridX, int roomGridZ, int mapX, int mapZ, int roomSize) {
    }
}
