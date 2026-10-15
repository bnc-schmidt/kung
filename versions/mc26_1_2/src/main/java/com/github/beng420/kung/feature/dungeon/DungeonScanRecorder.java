package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.KungMod;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class DungeonScanRecorder {
    private static final int NORMAL_BATCH_SIZE = 8;
    private static final int DISCOVERY_BATCH_SIZE = 24;
    private static final long CHECKMARK_INTERVAL_TICKS = 5;
    private static final int DOOR_SAMPLE_Y = 69;
    private static final DateTimeFormatter FILE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DungeonScan scanner = new DungeonScan();
    private final DungeonScanSchedule schedule = new DungeonScanSchedule(scanner.scanPointCount());
    private final DungeonMapSnapshot mapSnapshot = new DungeonMapSnapshot();
    private long lastScanTick;
    private long lastDoorScanTick = Long.MIN_VALUE;
    private String runTimestamp;
    private int scanNumber;
    private boolean recording;
    private long lastCheckmarkTick = Long.MIN_VALUE;
    private PlayerScanContext previousPlayer;

    public void initializeClient(DungeonStateTracker tracker) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client, tracker));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopRecording());
    }

    public DungeonMapSnapshot mapSnapshot() {
        return mapSnapshot;
    }

    public String runTimestamp() {
        return runTimestamp;
    }

    public boolean isRecording() {
        return recording;
    }

    public void scanNow(Minecraft client, DungeonStateTracker tracker) {
        if (!DungeonWorkload.current().rooms()
            || client.level == null || client.player == null || !tracker.canScanDungeon()) {
            return;
        }

        if (!recording) {
            startRecording();
        }
        if (!com.github.beng420.kung.skyblock.HypixelInstanceTracker.INSTANCE.positionKnown()) return;
        tracker.seedScanEntrance(mapSnapshot);

        if (!observePlayer(PlayerScanContext.from(client))) {
            return;
        }

        observeVisibleTeammateRooms(client, tracker.runStats());
        observeMapState(client, tracker.runStats(), tracker.dungeonTick());
        lastScanTick = tracker.dungeonTick();
        scanBatch(client, tracker, true);
    }

    public void restartRecording() {
        if (!DungeonWorkload.current().rooms()) {
            stopRecording();
            return;
        }
        recording = false;
        startRecording();
    }

    private void tick(Minecraft client, DungeonStateTracker tracker) {
        if (!DungeonWorkload.current().rooms() || client.level == null || client.player == null
            || !tracker.canScanDungeon()) {
            stopRecording();
            return;
        }

        startRecording();
        if (!com.github.beng420.kung.skyblock.HypixelInstanceTracker.INSTANCE.positionKnown()) return;
        tracker.seedScanEntrance(mapSnapshot);
        if (!observePlayer(PlayerScanContext.from(client))) {
            return;
        }

        observeVisibleTeammateRooms(client, tracker.runStats());
        long nowTick = tracker.dungeonTick();
        observeMapState(client, tracker.runStats(), nowTick);

        if (nowTick == lastScanTick) {
            return;
        }

        lastScanTick = nowTick;
        if (DungeonScanSchedule.due(nowTick, lastDoorScanTick, 2)) {
            lastDoorScanTick = nowTick;
            List<DungeonScanPoint> doors = scanner.scanLockedDoors(client.level, mapSnapshot);
            if (!doors.isEmpty()) recordScan(client, tracker.runStats(), doors);
        }
        scanBatch(client, tracker, tracker.shouldFastScanDoors());
    }

    /** Records the player's room; false while the player is outside the dungeon grid. */
    private boolean observePlayer(PlayerScanContext player) {
        // Spirit Leap, AOTV, Etherwarp and pearls change rooms without walking through the door
        // between them. The previous room is forgotten even outside the grid, so a jump from
        // there cannot leave a stale room to pair with the next one.
        if (previousPlayer != null && player.jumpedFrom(previousPlayer)) {
            mapSnapshot.forgetPreviousPlayerRoom();
        }
        previousPlayer = player;
        if (!DungeonScanUtils.isValidRoomGrid(player.gridX(), player.gridZ())) {
            return false;
        }
        mapSnapshot.observePlayerGrid(player.gridX(), player.gridZ());
        return true;
    }

    private void scanBatch(Minecraft client, DungeonStateTracker tracker, boolean fast) {
        long started = System.nanoTime();
        List<DungeonScanPoint> points = scanner.scanBatch(client.level, mapSnapshot, schedule.cursor(),
            fast ? DISCOVERY_BATCH_SIZE : NORMAL_BATCH_SIZE);
        schedule.advance(points.size());
        recordScan(client, tracker.runStats(), points);
        DungeonTimings.record("room-scan", started);
    }

    private void startRecording() {
        if (recording) {
            return;
        }

        recording = true;
        runTimestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        scanNumber = 0;
        lastScanTick = 0;
        lastDoorScanTick = Long.MIN_VALUE;
        schedule.reset();
        lastCheckmarkTick = Long.MIN_VALUE;
        mapSnapshot.reset();
    }

    void stopRecording() {
        if (!recording) {
            return;
        }

        recording = false;
        runTimestamp = null;
        scanNumber = 0;
        lastScanTick = 0;
        lastCheckmarkTick = Long.MIN_VALUE;
        mapSnapshot.reset();
        KungMod.LOGGER.info("Dungeon scan recording stopped.");
    }

    private void recordScan(Minecraft client, DungeonRunStats stats, List<DungeonScanPoint> points) {
        int currentScan = scanNumber++;
        long timestamp = System.currentTimeMillis();
        PlayerScanContext playerContext = PlayerScanContext.from(client);

        mapSnapshot.addScan(currentScan, timestamp, playerContext.gridX(), playerContext.gridZ(), points);
        observeVisibleTeammateRooms(client, stats);
    }

    private void observeMapState(Minecraft client, DungeonRunStats stats, long nowTick) {
        if (!DungeonScanSchedule.due(nowTick, lastCheckmarkTick, CHECKMARK_INTERVAL_TICKS)) {
            return;
        }
        lastCheckmarkTick = nowTick;
        observeMapPlayerRooms(client, stats, nowTick);
    }

    private void observeMapPlayerRooms(Minecraft client, DungeonRunStats stats, long nowTick) {
        MapItemSavedData mapData = DungeonMapItems.mapData(client);
        if (mapData == null || client.player == null) {
            return;
        }

        List<DungeonRunStats.DungeonPlayerSlot> slots = stats.dungeonPlayerSlots(client);
        Set<UUID> assignedSlotUuids = new HashSet<>();
        int teammateSlotCursor = 1;
        for (MapDecoration decoration : mapData.getDecorations()) {
            if (!DungeonMapCheckmarkReader.isPlayerDecoration(decoration)) {
                continue;
            }
            String name = DungeonMapCheckmarkReader.decorationName(decoration);
            if (name.isEmpty()) {
                SlotNameResult slotName = resolvedDecorationPlayerName(
                    decoration,
                    slots,
                    assignedSlotUuids,
                    teammateSlotCursor
                );
                teammateSlotCursor = slotName.nextTeammateSlotCursor();
                name = slotName.name();
            }

            DungeonMapCheckmarkReader.MapRoom room =
                DungeonMapCheckmarkReader.roomFromDecoration(decoration, client, mapSnapshot);
            if (room == null) {
                continue;
            }
            if (!isAcceptedMapPlayerDecoration(client, stats, name, room.roomGridX(), room.roomGridZ())) {
                continue;
            }

            mapSnapshot.observeMapPlayerRoom(room.roomGridX(), room.roomGridZ());
            if (name.isEmpty()) {
                stats.observeMapPlayerRoom(room.roomGridX(), room.roomGridZ(), nowTick);
            } else {
                stats.observeMapPlayerRoom(name, room.roomGridX(), room.roomGridZ(), nowTick);
            }
        }
    }

    static int doorSampleY() {
        return DOOR_SAMPLE_Y;
    }

    private void observeVisibleTeammateRooms(Minecraft client, DungeonRunStats stats) {
        if (!KungConfig.get().dungeon.playerTrackingEnabled()) {
            return;
        }
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }

        for (java.util.UUID uuid : stats.knownTrackedPlayerUuids()) {
            if (uuid.equals(client.player.getUUID())) {
                continue;
            }
            AbstractClientPlayer player = DungeonScanUtils.playerByUuid(client, uuid);
            if (player == null) {
                continue;
            }
            if (player == client.player || player.isInvisible()) {
                continue;
            }

            DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(player.blockPosition());
            mapSnapshot.observeVisitedRoom(grid.gridX(), grid.gridZ());
        }
    }

    private static SlotNameResult resolvedDecorationPlayerName(
        MapDecoration decoration,
        List<DungeonRunStats.DungeonPlayerSlot> slots,
        Set<UUID> assignedSlotUuids,
        int teammateSlotCursor
    ) {
        if (decoration.type().equals(MapDecorationTypes.FRAME)) {
            DungeonRunStats.DungeonPlayerSlot slot = playerSlotAt(slots, 0);
            if (slot != null && slot.uuid() != null && assignedSlotUuids.add(slot.uuid())) {
                return new SlotNameResult(slot.name(), teammateSlotCursor);
            }
            return new SlotNameResult("", teammateSlotCursor);
        }

        if (!decoration.type().equals(MapDecorationTypes.BLUE_MARKER)) {
            return new SlotNameResult("", teammateSlotCursor);
        }

        while (teammateSlotCursor < 5) {
            DungeonRunStats.DungeonPlayerSlot slot = playerSlotAt(slots, teammateSlotCursor++);
            if (slot == null || slot.uuid() == null || !assignedSlotUuids.add(slot.uuid())) {
                continue;
            }
            return new SlotNameResult(slot.name(), teammateSlotCursor);
        }
        return new SlotNameResult("", teammateSlotCursor);
    }

    private static DungeonRunStats.DungeonPlayerSlot playerSlotAt(
        List<DungeonRunStats.DungeonPlayerSlot> slots,
        int index
    ) {
        for (DungeonRunStats.DungeonPlayerSlot slot : slots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isAcceptedMapPlayerDecoration(
        Minecraft client,
        DungeonRunStats stats,
        String name,
        int roomGridX,
        int roomGridZ
    ) {
        if (!name.isEmpty()) {
            return name.equalsIgnoreCase(client.player.getName().getString()) || stats.isKnownTrackedPlayer(name);
        }
        DungeonScanUtils.GridPosition selfGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        if (selfGrid.gridX() == roomGridX && selfGrid.gridZ() == roomGridZ) {
            return true;
        }
        if (client.level == null || client.getConnection() == null) {
            return false;
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (player == client.player || player.isInvisible() || !stats.isKnownTrackedPlayer(player.getName().getString())) {
                continue;
            }
            if (client.getConnection().getPlayerInfo(player.getUUID()) == null) {
                continue;
            }
            DungeonScanUtils.GridPosition playerGrid = DungeonScanUtils.getRoomGridPosition(player.blockPosition());
            if (playerGrid.gridX() == roomGridX && playerGrid.gridZ() == roomGridZ) {
                return true;
            }
        }
        return false;
    }

    record PlayerScanContext(int blockX, int blockY, int blockZ, int gridX, int gridZ) {
        /**
         * Blocks a player can walk in one tick, with margin: legitimate walking is about 1.4-1.8.
         * More is a teleport (Spirit Leap, a short AOTV or etherwarp hop next to a wall) or a position
         * correction. A false jump costs one skipped traversal; a missed one draws a door through a wall.
         */
        static final int MAX_WALK_BLOCKS_PER_TICK = 3;

        static PlayerScanContext from(Minecraft client) {
            BlockPos position = client.player.blockPosition();
            DungeonScanUtils.GridPosition gridPosition = DungeonScanUtils.getRoomGridPosition(position);
            return new PlayerScanContext(
                position.getX(),
                position.getY(),
                position.getZ(),
                gridPosition.gridX(),
                gridPosition.gridZ()
            );
        }

        boolean jumpedFrom(PlayerScanContext earlier) {
            int dx = blockX - earlier.blockX;
            int dz = blockZ - earlier.blockZ;
            return dx * dx + dz * dz > MAX_WALK_BLOCKS_PER_TICK * MAX_WALK_BLOCKS_PER_TICK;
        }
    }

    private record SlotNameResult(String name, int nextTeammateSlotCursor) {
    }
}
