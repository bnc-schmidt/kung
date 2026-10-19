package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class DungeonStateTracker {
    private static final int MAX_MULTI_LEARN_COMPONENTS = 4;
    private static final long STATS_OBSERVE_INTERVAL_TICKS = 5;
    private static final long CLEAR_STATE_OBSERVE_INTERVAL_TICKS = 5;
    private static final long LIVE_ROOM_SYNC_INTERVAL_TICKS = 10;
    private static final long RUN_SUMMARY_DELAY_TICKS = 20;
    private static final long LIFECYCLE_DUPLICATE_WINDOW_TICKS = 20;
    private static final long LIFECYCLE_MESSAGE_WINDOW_TICKS = 80;
    private static final int DEBUG_ROOM_SIZE = 19;
    private static final int DEBUG_DOOR_SIZE = 6;
    private static final int DEBUG_CELL_GAP = 1;
    private static final int PLAYER_IDENTITY_MATCH_DISTANCE = 12;
    private static final int MAX_BABY_ZOMBIE_DEATHS = 8;
    private final DungeonRoomRepository roomRepository;
    private final DungeonScanRecorder scanRecorder = new DungeonScanRecorder();
    private final DungeonRunStats runStats = new DungeonRunStats();
    /** The latest baby-zombie deaths: a Mimic can die between two Mimic ESP scans, so the ESP matches these to its chests. */
    private final List<BabyZombieDeath> babyZombieDeaths = new ArrayList<>();
    /**
     * Hypixel's score report is a chat block that ends in a dashed line, and the run-end lines arrive
     * inside it. Printing a split line right away landed it in the middle, on every floor. From the
     * first run-end line on, Kung's lines wait for the closing dashed line, or a fallback if it never comes.
     */
    private final List<DungeonSplitMessages.Notice> heldSplitNotices = new ArrayList<>();
    private long splitReportDueTick = Long.MIN_VALUE;
    private static final long SPLIT_REPORT_FALLBACK_TICKS = 20;
    private static final long SPLIT_REPORT_END_DELAY_TICKS = 5;
    private static final Pattern SPLIT_REPORT_END = Pattern.compile("^-{20,}$");
    private final DungeonSplitTracker splitTracker = new DungeonSplitTracker(
        phase -> {
            if (this.splitReportDueTick == Long.MIN_VALUE) DungeonSplitMessages.send(phase);
            else this.heldSplitNotices.addAll(0, DungeonSplitMessages.notices(phase));
        },
        notices -> this.heldSplitNotices.addAll(notices));
    private boolean dungeonInstanceActive;
    // Commit scan readiness only after instance setup/reset reaches its existing boundary.
    private boolean scanReady;
    private boolean inDungeonArea;
    private boolean mapVisibleArea;
    private long dungeonTick;
    private long cachedRenderPlanRevision = Long.MIN_VALUE;
    private long cachedRenderPlanCatalogRevision = Long.MIN_VALUE;
    private DungeonLiveMapWriter.MatchRenderPlan cachedRenderPlan;
    private long lastStatsObserveTick = Long.MIN_VALUE;
    private long lastClearStateObserveTick = Long.MIN_VALUE;
    private long lastLiveRoomSyncTick = Long.MIN_VALUE;
    private long pendingRunSummaryTick = Long.MIN_VALUE;
    private long pendingRunStartSignalTick = Long.MIN_VALUE;
    private long lastRunStartSignalTick = Long.MIN_VALUE;
    private long lastRunFinishedSignalTick = Long.MIN_VALUE;
    private long lastLeftDungeonsMessageTick = Long.MIN_VALUE;
    private long observedInstanceEpoch = Long.MIN_VALUE;
    private String lastLoggedDungeonStateKey = "";
    private String lastLoggedMapTopologyState = "";
    private String lastLoggedMapTopologySummary = "";
    private String lastLoggedLiveRoomSyncState = "";
    private final Set<String> loggedMapDiscoveryLines = new HashSet<>();
    private long lastRunErrorMessageTick = Long.MIN_VALUE;
    private boolean runSummarySent;
    private boolean runStartMessageSent;
    private boolean runEndMessageSent;
    private boolean realRunStarted;
    private DungeonScanUtils.GridPosition instanceEntrance;

    void seedScanEntrance(DungeonMapSnapshot snapshot) {
        if (instanceEntrance != null) {
            snapshot.observeStartRoom(instanceEntrance.gridX(), instanceEntrance.gridZ());
        }
    }

    public DungeonStateTracker() {
        this(KnownDungeonRoomRepository.INSTANCE);
    }

    public DungeonStateTracker(DungeonRoomRepository roomRepository) {
        this.roomRepository = java.util.Objects.requireNonNull(roomRepository, "roomRepository");
    }

    boolean canScanDungeon() {
        return scanReady;
    }

    public DungeonMapSnapshot mapSnapshot() {
        return scanRecorder.mapSnapshot();
    }

    public String runTimestamp() {
        return scanRecorder.runTimestamp();
    }

    public boolean isRecording() {
        return scanRecorder.isRecording();
    }

    boolean shouldFastScanDoors() {
        if (KungBuild.MODRINTH) return false;
        return BloodRushHelperFeature.INSTANCE.shouldFastScanDoors(dungeonTick);
    }

    boolean realRunStarted() {
        return realRunStarted;
    }

    public boolean isInDungeonArea() {
        return mapVisibleArea;
    }

    public long dungeonTick() {
        return dungeonTick;
    }

    public DungeonRunStats runStats() {
        return runStats;
    }

    boolean canFilterDungeonChat(Minecraft client) {
        return canProcessDungeonRunMessage(client);
    }

    public DungeonSplitTracker splitTracker() {
        return splitTracker;
    }

    public DungeonLiveMapWriter.MatchRenderPlan renderPlan() {
        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        snapshot.observeTabPuzzles(runStats.puzzleNames());
        long revision = snapshot.revision();
        long catalogRevision = roomRepository.revision();
        if (cachedRenderPlan == null
            || cachedRenderPlanRevision != revision
            || cachedRenderPlanCatalogRevision != catalogRevision) {
            DungeonLiveMapWriter.MatchRenderPlan renderPlan = DungeonLiveMapWriter.MatchRenderPlan.from(
                snapshot,
                roomRepository
            );
            if (autoLearnStableHashes(snapshot, renderPlan)) {
                renderPlan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, roomRepository);
                catalogRevision = roomRepository.revision();
            }
            cachedRenderPlan = renderPlan;
            cachedRenderPlanRevision = revision;
            cachedRenderPlanCatalogRevision = catalogRevision;
            List<String> topology = renderPlan.topologyDebugLines(snapshot);
            logMapTopology(topology);
            logMapDiscovery(snapshot, topology);
        }
        return cachedRenderPlan;
    }

    private void logMapTopology(List<String> lines) {
        if (!dungeonInstanceActive) {
            return;
        }
        String state = String.join("\n", lines);
        if (state.equals(lastLoggedMapTopologyState)) {
            return;
        }
        lastLoggedMapTopologyState = state;
        for (String line : lines) {
            if (!line.startsWith("summary ")) {
                continue;
            }
            if (line.equals(lastLoggedMapTopologySummary)) {
                return;
            }
            lastLoggedMapTopologySummary = line;
            KungDebugRecorder.event("map-topology", line);
            return;
        }
    }

    private void logMapDiscovery(DungeonMapSnapshot snapshot, List<String> lines) {
        if (!dungeonInstanceActive) {
            return;
        }
        for (String line : lines) {
            if (line.startsWith("summary ")) {
                continue;
            }
            if (!loggedMapDiscoveryLines.add(line)) {
                continue;
            }
            KungDebugRecorder.event("map-discovery", "tick="
                + dungeonTick
                + " scan="
                + snapshot.lastScanNumber()
                + " revision="
                + snapshot.revision()
                + " "
                + line);
        }
    }

    private boolean autoLearnStableHashes(
        DungeonMapSnapshot snapshot,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan
    ) {
        boolean learnedAny = false;
        for (DungeonKnownRoomCatalog.MatchedRoom match : renderPlan.matches()) {
            try {
                DungeonKnownRoomCatalog.AutoLearnResult result =
                    roomRepository.autoLearnStableHashes(match, snapshot);
                if (result.learned()) {
                    learnedAny = true;
                    KungMod.LOGGER.info(
                        "Auto-learned {} stable dungeon room hashes for {}.",
                        result.componentCount(),
                        result.roomName()
                    );
                }
            } catch (IOException | IllegalArgumentException exception) {
                KungMod.LOGGER.warn("Failed to auto-learn stable dungeon room hashes.", exception);
            }
        }
        return learnedAny;
    }

    public List<String> debugRoomSummary() {
        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        return DungeonRoomDebugFormatter.summaryLines(runTimestamp(), snapshot, renderPlan());
    }

    public List<String> debugMapDecorations(Minecraft client) {
        MapItemSavedData map = DungeonMapItems.mapData(client);
        if (map == null || client.player == null) {
            return List.of("No dungeon map found in the inventory, offhand, or selected slot.");
        }

        List<String> lines = new ArrayList<>();
        int decorationCount = 0;
        for (MapDecoration ignored : map.getDecorations()) {
            decorationCount++;
        }
        lines.add("MapDecorations count=" + decorationCount);
        for (MapDecoration decoration : map.getDecorations()) {
            String name = DungeonMapCheckmarkReader.decorationName(decoration);
            DungeonMapCheckmarkReader.MapPixel pixel = DungeonMapCheckmarkReader.mapPixelForDecoration(decoration);
            DungeonMapCheckmarkReader.MapPixel overlayPixel =
                DungeonMapCheckmarkReader.overlayPixelForDecoration(decoration, client, scanRecorder.mapSnapshot(), 19, 6, 1);
            DungeonMapCheckmarkReader.MapRoom room =
                DungeonMapCheckmarkReader.roomFromDecoration(decoration, client, scanRecorder.mapSnapshot());
            boolean strictParty = isStrictPartyMapDecoration(client, decoration, name);
            lines.add("type=" + DungeonMapCheckmarkReader.decorationTypeName(decoration)
                + " name=" + (name.isEmpty() ? "<empty>" : name)
                + " raw=" + decoration.x() + "," + decoration.y()
                + " pixel=" + pixel.x() + "," + pixel.z()
                + " overlay=" + (overlayPixel == null ? "none" : overlayPixel.x() + "," + overlayPixel.z())
                + " rot=" + decoration.rot()
                + " room=" + (room == null ? "none" : room.roomGridX() + "," + room.roomGridZ())
                + " accepted=" + strictParty);
        }
        return lines;
    }

    public RoomDataResult ultraDebug(Minecraft client) {
        StringBuilder debug = new StringBuilder(8192);
        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = renderPlan();

        appendLine(debug, "Kung Dungeon Ultra Debug");
        appendLine(debug, "createdAtMillis=" + System.currentTimeMillis());
        appendLine(debug, "state dungeonInstanceActive=" + dungeonInstanceActive
            + " inDungeonArea=" + inDungeonArea
            + " mapVisibleArea=" + mapVisibleArea
            + " stateInDungeon=" + scanReady
            + " recording=" + scanRecorder.isRecording()
            + " dungeonTick=" + dungeonTick);
        appendLine(debug, "config enabled=" + KungConfig.get().dungeon.enabled()
            + " playerTracking=" + KungConfig.get().dungeon.playerTrackingEnabled()
            + " debugMessagesEnabled=" + KungConfig.get().debug.enabled()
            + " dungeonDebugMessages=" + KungConfig.get().debug.dungeonMessages()
            + " roomCrypts=" + KungConfig.get().dungeon.debugRoomCrypts()
            + " roomDebug=" + KungConfig.get().dungeon.debugRoomMatches()
            + " scale=" + KungConfig.get().dungeon.scale()
            + " unopenedAlpha=" + KungConfig.get().dungeon.unopenedRoomAlpha());

        if (client.player == null || client.level == null) {
            appendLine(debug, "client=no-player-or-world");
            return RoomDataResult.found("Ultra debug copied: no world or player.", debug.toString());
        }

        DungeonScanUtils.GridPosition selfGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        appendLine(debug, "self name=" + client.player.getName().getString()
            + " uuid=" + client.player.getUUID()
            + " block=" + client.player.blockPosition().getX()
            + "," + client.player.blockPosition().getY()
            + "," + client.player.blockPosition().getZ()
            + " roomGrid=" + selfGrid.gridX() + "," + selfGrid.gridZ()
            + " yaw=" + client.player.getYRot());

        appendSnapshotDebug(debug, snapshot);
        appendRunStatsDebug(debug, client);
        appendLoadedPlayersDebug(debug, client);
        appendMapDecorationDebug(debug, client, snapshot);
        appendRenderPlanDebug(debug, plan);

        RoomDataResult roomData = currentRoomData(client);
        appendLine(debug, "currentRoomData found=" + roomData.found() + " message=" + roomData.message());
        if (roomData.found()) {
            appendLine(debug, "currentRoomDataClipboard=" + roomData.clipboardText());
        }

        return RoomDataResult.found("Ultra debug copied to the clipboard.", debug.toString());
    }

    private void appendSnapshotDebug(StringBuilder debug, DungeonMapSnapshot snapshot) {
        appendLine(debug, "");
        appendLine(debug, "[snapshot]");
        appendLine(debug, "runTimestamp=" + runTimestamp()
            + " revision=" + snapshot.revision()
            + " lastScan=" + snapshot.lastScanNumber()
            + " lastTimestamp=" + snapshot.lastTimestamp()
            + " observedRawCells=" + snapshot.observedRoomCount()
            + " playerGrid=" + snapshot.playerGridX() + "," + snapshot.playerGridZ()
            + " startRoom=" + gridKeyText(snapshot.startRoom()));
        appendLine(debug, "mapPlayerRooms=" + gridKeysText(snapshot.mapPlayerRooms()));
        appendLine(debug, "traversedDoors=" + gridKeysText(snapshot.traversedDoors()));
        appendLine(debug, "openedLockedDoors=" + gridKeysText(snapshot.openedLockedDoors()));

        StringBuilder roomStates = new StringBuilder();
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                DungeonMapSnapshot.ObservedPoint point = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
                if (point == null
                    && !snapshot.isVisitedRoom(roomGridX, roomGridZ)
                    && !snapshot.isClearedRoom(roomGridX, roomGridZ)
                    && !snapshot.isCompletedRoom(roomGridX, roomGridZ)) {
                    continue;
                }
                if (!roomStates.isEmpty()) {
                    roomStates.append(' ');
                }
                roomStates.append(roomGridX).append(',').append(roomGridZ)
                    .append(":hash=")
                    .append(point == null ? "none" : point.point().coreHash() + "/" + point.point().stableCoreHash())
                    .append(":v=").append(snapshot.isVisitedRoom(roomGridX, roomGridZ))
                    .append(":c=").append(snapshot.isClearedRoom(roomGridX, roomGridZ))
                    .append(":done=").append(snapshot.isCompletedRoom(roomGridX, roomGridZ));
            }
        }
        appendLine(debug, "rooms=" + roomStates);
    }

    private void appendRunStatsDebug(StringBuilder debug, Minecraft client) {
        appendLine(debug, "");
        appendLine(debug, "[runStats]");
        appendLine(debug, "floor=" + runStats.floor()
            + " masterMode=" + runStats.masterMode()
            + " playersKnown=" + runStats.dungeonPlayerCount()
            + " partyKnown=" + runStats.partyPlayerCount()
            + " score=" + runStats.score()
            + " secrets=" + runStats.secretsFound()
            + "/" + runStats.secretsAvailable()
            + " totalSecrets=" + runStats.secretsTotalAvailable()
            + " derivedTotalSecrets=" + runStats.derivedSecretsTotalAvailable()
            + " secretsPercent=" + runStats.secretsPercent()
            + " deaths=" + runStats.deaths()
            + " crypts=" + runStats.cryptsOpened()
            + "/" + runStats.cryptsAvailable());
        int index = 0;
        for (DungeonPlayerStats stats : runStats.dungeonPlayersInOrder()) {
            appendLine(debug, "orderedPlayer[" + index++ + "] name=" + stats.name()
                + " uuid=" + stats.uuid()
                + " class=" + stats.dungeonClass()
                + " knownParty=" + runStats.isKnownPartyPlayer(stats.name())
                + " roomGrid=" + stats.roomGridX() + "," + stats.roomGridZ()
                + " secrets=" + stats.secretsFound()
                + " rooms=" + stats.soloRoomsCleared() + "-" + stats.roomsCleared()
                + " lastSeenTick=" + stats.lastSeenTick()
                + " loaded=" + (DungeonScanUtils.playerByUuid(client, stats.uuid()) != null));
        }
        for (Map.Entry<UUID, DungeonPlayerStats> entry : runStats.players().entrySet()) {
            DungeonPlayerStats stats = entry.getValue();
            appendLine(debug, "statsPlayer name=" + stats.name()
                + " uuid=" + entry.getKey()
                + " class=" + stats.dungeonClass()
                + " knownDungeon=" + runStats.isKnownDungeonPlayer(stats.name())
                + " roomGrid=" + stats.roomGridX() + "," + stats.roomGridZ()
                + " secrets=" + stats.secretsFound()
                + " rooms=" + stats.soloRoomsCleared() + "-" + stats.roomsCleared());
        }
    }

    private void appendLoadedPlayersDebug(StringBuilder debug, Minecraft client) {
        appendLine(debug, "");
        appendLine(debug, "[loadedPlayers]");
        int index = 0;
        for (AbstractClientPlayer player : client.level.players()) {
            DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(player.blockPosition());
            appendLine(debug, "loaded[" + index++ + "] name=" + player.getName().getString()
                + " uuid=" + player.getUUID()
                + " invisible=" + player.isInvisible()
                + " tabInfo=" + (client.getConnection() != null && client.getConnection().getPlayerInfo(player.getUUID()) != null)
                + " knownDungeon=" + runStats.isKnownDungeonPlayer(player.getName().getString())
                + " knownParty=" + runStats.isKnownPartyPlayer(player.getName().getString())
                + " block=" + player.blockPosition().getX()
                + "," + player.blockPosition().getY()
                + "," + player.blockPosition().getZ()
                + " roomGrid=" + grid.gridX() + "," + grid.gridZ()
                + " overlayFromWorld=" + mapPixelForWorldX(player.getX()) + "," + mapPixelForWorldZ(player.getZ())
                + " class=" + classText(player.getUUID()));
        }
    }

    private void appendMapDecorationDebug(StringBuilder debug, Minecraft client, DungeonMapSnapshot snapshot) {
        appendLine(debug, "");
        appendLine(debug, "[mapDecorations]");
        MapItemSavedData map = DungeonMapItems.mapData(client);
        if (map == null) {
            appendLine(debug, "map=none");
            return;
        }

        int decorationCount = 0;
        for (MapDecoration ignored : map.getDecorations()) {
            decorationCount++;
        }
        appendLine(debug, "count=" + decorationCount);

        int decorationIndex = 0;
        int partyPlayerIndex = 1;
        int drawableCount = 0;
        Set<UUID> resolvedPlayerUuids = new HashSet<>();
        for (MapDecoration decoration : map.getDecorations()) {
            String name = DungeonMapCheckmarkReader.decorationName(decoration);
            boolean selfDecoration = isSelfDecoration(client, decoration, name);

            DungeonMapCheckmarkReader.MapPixel mapPixel = DungeonMapCheckmarkReader.mapPixelForDecoration(decoration);
            DungeonMapCheckmarkReader.MapPixel anchoredOverlay =
                DungeonMapCheckmarkReader.overlayPixelForDecoration(
                    decoration,
                    client,
                    snapshot,
                    DEBUG_ROOM_SIZE,
                    DEBUG_DOOR_SIZE,
                    DEBUG_CELL_GAP
                );
            DungeonMapCheckmarkReader.MapPixel rawOverlay = rawOverlayPixel(decoration);
            DungeonMapCheckmarkReader.MapPixel renderOverlay = anchoredOverlay != null ? anchoredOverlay : rawOverlay;
            UUID resolvedUuid = resolveDecorationUuid(
                client,
                decoration,
                name,
                partyPlayerIndex,
                renderOverlay,
                resolvedPlayerUuids
            );
            if (!selfDecoration) {
                partyPlayerIndex++;
            }
            DungeonMapCheckmarkReader.MapRoom room =
                DungeonMapCheckmarkReader.roomFromDecoration(decoration, client, snapshot);
            AbstractClientPlayer loaded = DungeonScanUtils.playerByUuid(client, resolvedUuid);
            boolean playerDecoration = DungeonMapCheckmarkReader.isPlayerDecoration(decoration);
            boolean drawable = playerDecoration && (anchoredOverlay != null || rawOverlay != null || loaded != null);
            if (drawable) {
                drawableCount++;
                if (resolvedUuid != null) {
                    resolvedPlayerUuids.add(resolvedUuid);
                }
            }

            appendLine(debug, "decoration[" + decorationIndex++ + "] type=" + DungeonMapCheckmarkReader.decorationTypeName(decoration)
                + " playerDecoration=" + playerDecoration
                + " name=" + (name.isEmpty() ? "<empty>" : name)
                + " self=" + selfDecoration
                + " raw=" + decoration.x() + "," + decoration.y()
                + " mapPixel=" + mapPixel.x() + "," + mapPixel.z()
                + " anchoredOverlay=" + pixelText(anchoredOverlay)
                + " rawOverlay=" + pixelText(rawOverlay)
                + " renderOverlay=" + (loaded == null ? pixelText(renderOverlay) : mapPixelForWorldX(loaded.getX()) + "," + mapPixelForWorldZ(loaded.getZ()))
                + " rot=" + decoration.rot()
                + " room=" + (room == null ? "none" : room.roomGridX() + "," + room.roomGridZ())
                + " oldAccepted=" + isStrictPartyMapDecoration(client, decoration, name)
                + " drawableNow=" + drawable
                + " resolvedUuid=" + uuidText(resolvedUuid)
                + " resolvedClass=" + classText(resolvedUuid)
                + " resolvedLoaded=" + (loaded != null)
                + " partyIndexUsed=" + (selfDecoration ? "self" : partyPlayerIndex - 1));
        }
        appendLine(debug, "drawablePlayerDecorations=" + drawableCount);
    }

    private void appendRenderPlanDebug(StringBuilder debug, DungeonLiveMapWriter.MatchRenderPlan plan) {
        appendLine(debug, "");
        appendLine(debug, "[renderPlan]");
        appendLine(debug, "matches=" + plan.matches().size()
            + " hints=" + plan.hints().size()
            + " externalDoors=" + plan.externalDoors().size()
            + " rooms=" + plan.observedRoomCount()
            + " visited=" + plan.visitedRoomCount()
            + " cleared=" + plan.clearedRoomCount()
            + " completed=" + plan.completedRoomCount()
            + " rawVisitedCells=" + plan.visitedRooms().size()
            + " rawClearedCells=" + plan.clearedRooms().size()
            + " rawCompletedCells=" + plan.completedRooms().size());
        for (String line : DungeonRoomDebugFormatter.summaryLines(runTimestamp(), scanRecorder.mapSnapshot(), plan)) {
            appendLine(debug, line);
        }
    }

    private boolean isStrictPartyMapDecoration(Minecraft client, MapDecoration decoration, String name) {
        if (!DungeonMapCheckmarkReader.isPlayerDecoration(decoration)) {
            return false;
        }
        if (name.isEmpty()) {
            return true;
        }
        return !name.isEmpty()
            && (name.equalsIgnoreCase(client.player.getName().getString()) || runStats.isKnownDungeonPlayer(name));
    }

    private UUID resolveDecorationUuid(
        Minecraft client,
        MapDecoration decoration,
        String name,
        int partyPlayerIndex,
        DungeonMapCheckmarkReader.MapPixel markerPixel,
        Set<UUID> assignedUuids
    ) {
        if (!name.isEmpty()) {
            if (client.player != null && name.equalsIgnoreCase(client.player.getName().getString())) {
                return client.player.getUUID();
            }
            return runStats.dungeonPlayerUuid(name);
        }
        if (isSelfDecoration(client, decoration, name)) {
            return client.player.getUUID();
        }
        UUID nearestLoadedPlayerUuid = nearestLoadedDungeonPlayerUuid(client, markerPixel, assignedUuids);
        if (nearestLoadedPlayerUuid != null) {
            return nearestLoadedPlayerUuid;
        }
        List<DungeonPlayerStats> players = runStats.dungeonPlayersInOrder();
        while (partyPlayerIndex < players.size()) {
            DungeonPlayerStats stats = players.get(partyPlayerIndex++);
            if (stats != null
                && !stats.uuid().equals(client.player.getUUID())
                && !assignedUuids.contains(stats.uuid())) {
                return stats.uuid();
            }
        }
        return null;
    }

    private UUID nearestLoadedDungeonPlayerUuid(
        Minecraft client,
        DungeonMapCheckmarkReader.MapPixel markerPixel,
        Set<UUID> assignedUuids
    ) {
        if (client.level == null || client.player == null || markerPixel == null) {
            return null;
        }

        UUID bestUuid = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AbstractClientPlayer player : client.level.players()) {
            UUID uuid = player.getUUID();
            if (uuid.equals(client.player.getUUID())
                || assignedUuids.contains(uuid)
                || player.isInvisible()
                || !runStats.isKnownDungeonPlayer(player.getName().getString())) {
                continue;
            }
            if (client.getConnection() == null || client.getConnection().getPlayerInfo(uuid) == null) {
                continue;
            }

            int dx = mapPixelForWorldX(player.getX()) - markerPixel.x();
            int dz = mapPixelForWorldZ(player.getZ()) - markerPixel.z();
            int distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestUuid = uuid;
            }
        }

        return bestDistance <= PLAYER_IDENTITY_MATCH_DISTANCE * PLAYER_IDENTITY_MATCH_DISTANCE ? bestUuid : null;
    }

    private static boolean isSelfDecoration(Minecraft client, MapDecoration decoration, String name) {
        return decoration.type().equals(MapDecorationTypes.FRAME)
            || (!name.isEmpty()
                && client.player != null
                && name.equalsIgnoreCase(client.player.getName().getString()));
    }

    private String classText(UUID uuid) {
        DungeonPlayerStats stats = uuid == null ? null : runStats.playerStats(uuid);
        return stats == null ? "UNKNOWN(no-stats)" : stats.dungeonClass().name();
    }

    private static DungeonMapCheckmarkReader.MapPixel rawOverlayPixel(MapDecoration decoration) {
        int mapX = (decoration.x() >> 1) + 64;
        int mapZ = (decoration.y() >> 1) + 64;
        return new DungeonMapCheckmarkReader.MapPixel(
            Math.clamp(Math.round(mapX * debugGridPixelSize() / 127.0F), 0, debugGridPixelSize()),
            Math.clamp(Math.round(mapZ * debugGridPixelSize() / 127.0F), 0, debugGridPixelSize())
        );
    }

    private static int mapPixelForWorldX(double worldX) {
        double roomProgress = (worldX - DungeonScanUtils.START_X) / DungeonScanUtils.ROOM_SIZE_BLOCKS;
        return Math.clamp(
            (int) Math.round(DEBUG_ROOM_SIZE / 2.0 + roomProgress * debugPixelsPerRoom()),
            0,
            debugGridPixelSize()
        );
    }

    private static int mapPixelForWorldZ(double worldZ) {
        double roomProgress = (worldZ - DungeonScanUtils.START_Z) / DungeonScanUtils.ROOM_SIZE_BLOCKS;
        return Math.clamp(
            (int) Math.round(DEBUG_ROOM_SIZE / 2.0 + roomProgress * debugPixelsPerRoom()),
            0,
            debugGridPixelSize()
        );
    }

    private static int debugGridPixelSize() {
        return debugScanGridToPixel(DungeonScanUtils.SCAN_GRID_SIZE);
    }

    private static int debugPixelsPerRoom() {
        return DEBUG_ROOM_SIZE + DEBUG_DOOR_SIZE + DEBUG_CELL_GAP * 2;
    }

    private static int debugScanGridToPixel(int gridPosition) {
        int pixel = 0;
        for (int index = 0; index < gridPosition; index++) {
            pixel += (index & 1) == 0 ? DEBUG_ROOM_SIZE : DEBUG_DOOR_SIZE;
            pixel += DEBUG_CELL_GAP;
        }
        return pixel;
    }

    private static String gridKeysText(Set<DungeonMapSnapshot.GridKey> keys) {
        List<String> parts = new ArrayList<>();
        for (DungeonMapSnapshot.GridKey key : keys) {
            parts.add(gridKeyText(key));
        }
        parts.sort(String::compareTo);
        return String.join(" ", parts);
    }

    private static String gridKeyText(DungeonMapSnapshot.GridKey key) {
        return key == null ? "none" : key.gridX() + "," + key.gridZ();
    }

    private static String pixelText(DungeonMapCheckmarkReader.MapPixel pixel) {
        return pixel == null ? "none" : pixel.x() + "," + pixel.z();
    }

    private static String uuidText(UUID uuid) {
        return uuid == null ? "none" : uuid.toString();
    }

    private static void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    public void initializeClient() {
        new DungeonEventRouter(this).register();
        scanRecorder.initializeClient(this);
    }

    void observeEntityDeath(Entity entity) {
        if (!isInDungeonArea()) {
            return;
        }
        if (splitTracker.inNecronEndWindow()) {
            KungDebugRecorder.event("necron-end", "death split=" + splitTracker.currentSplitName() + " " + entity.getType()
                + " name=\"" + entity.getName().getString() + "\" position=" + entity.position());
        }
        if (!KungBuild.MODRINTH && entity instanceof Zombie zombie && zombie.isBaby()) {
            if (babyZombieDeaths.size() >= MAX_BABY_ZOMBIE_DEATHS) babyZombieDeaths.removeFirst();
            babyZombieDeaths.add(new BabyZombieDeath(entity.blockPosition(), dungeonTick));
        }
        runStats.observeEntityDeath(Minecraft.getInstance(), entity);
    }

    List<BabyZombieDeath> babyZombieDeaths() {
        return babyZombieDeaths;
    }

    record BabyZombieDeath(BlockPos pos, long tick) {
    }

    void observeSkyblockerBonus(DungeonBonusContribution bonus) {
        if (realRunStarted && canProcessDungeonRunMessage(Minecraft.getInstance())
            && DungeonWorkload.current().players()) {
            runStats.observeSkyblockerBonus(bonus);
        }
    }

    void serverTick() {
        if (dungeonInstanceActive) {
            splitTracker.serverTick(System.nanoTime() / 1_000_000L);
            if (!KungBuild.MODRINTH && realRunStarted) {
                BloodRushHelperFeature.INSTANCE.serverTick();
            }
        }
    }

    void synchronizeInstance(Minecraft client) {
        HypixelInstanceTracker context = HypixelInstanceTracker.INSTANCE;
        if (observedInstanceEpoch != context.instanceEpoch()) {
            endDungeonInstance(client);
            observedInstanceEpoch = context.instanceEpoch();
            instanceEntrance = null;
            pendingRunStartSignalTick = Long.MIN_VALUE;
            lastRunStartSignalTick = Long.MIN_VALUE;
            lastRunFinishedSignalTick = Long.MIN_VALUE;
            scanRecorder.stopRecording();
        }
        boolean inCatacombs = canAcceptDungeonLifecycleSignal(client);
        if (inCatacombs && !dungeonInstanceActive) startDungeonInstance(client);
        else if (!inCatacombs) endDungeonInstance(client);
        if (inCatacombs) configureDungeonFloor();
        inDungeonArea = inCatacombs;
        mapVisibleArea = inCatacombs;
        scanReady = inCatacombs;
    }

    private void configureDungeonFloor() {
        var floor = HypixelInstanceTracker.INSTANCE.dungeonFloor();
        if (!floor.known()) return;
        runStats.configureForFloor(floor.floor(), floor.masterMode());
        splitTracker.configureKnownFloor(floor.floor(), floor.masterMode());
    }

    void observeGameMessage(Component message, boolean overlay) {
        try {
            observeDungeonMessage(Minecraft.getInstance(), message.getString(), overlay);
        } catch (RuntimeException | LinkageError exception) {
            KungMod.LOGGER.warn("Failed to process dungeon game message.", exception);
            reportRunError(Minecraft.getInstance(), "Game message", exception);
        }
    }

    void observeChatMessage(Component message) {
        try {
            observeDungeonMessage(Minecraft.getInstance(), message.getString(), false, DungeonDeathTracker.MessageSource.CHAT);
        } catch (RuntimeException | LinkageError exception) {
            KungMod.LOGGER.warn("Failed to process dungeon chat message.", exception);
            reportRunError(Minecraft.getInstance(), "Chat message", exception);
        }
    }

    private void observeDungeonMessage(Minecraft client, String text, boolean overlay) {
        observeDungeonMessage(client, text, overlay, overlay
            ? DungeonDeathTracker.MessageSource.ACTIONBAR : DungeonDeathTracker.MessageSource.SYSTEM);
    }

    private void observeDungeonMessage(Minecraft client, String text, boolean overlay, DungeonDeathTracker.MessageSource source) {
        // Before canProcessDungeonRunMessage: an enrage is a chat line and needs neither an active
        // dungeon instance nor a lifecycle signal. Behind that gate it failed silently.
        if (!overlay) {
            WishAlert.observeMessage(client, text, com.github.beng420.kung.config.KungConfig.get().dungeon,
                runStats.selfDungeonClass());
        }
        observeRunStartSignal(client, text);
        if (!canProcessDungeonRunMessage(client)) {
            return;
        }
        DungeonWorkload workload = DungeonWorkload.current();
        if (workload.players()) runStats.observeMessage(client, text, dungeonTick, source);
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.observeMessage(client, text, realRunStarted, this);
        if (overlay && workload.rooms()) {
            runStats.observeRoomSecretOverlay(client, text, renderPlan());
        }
        if (source == DungeonDeathTracker.MessageSource.SYSTEM) {
            var floor = HypixelDungeonFloor.fromLine(text);
            splitTracker.configureKnownFloor(floor.floor(), floor.masterMode());
        }
        if (!overlay) traceNecronEnd(text);
        observeSplitReport(text, dungeonTick);
        splitTracker.observeMessage(text, dungeonTick);
        observeRunFinishedSignal(text);
    }

    /** Opens the report on its first run-end line (before the split tracker answers it) and ends the wait at the dashed line. */
    void observeSplitReport(String text, long tick) {
        String clean = DungeonLifecycleSignals.clean(text);
        if (splitReportDueTick == Long.MIN_VALUE) {
            if (DungeonLifecycleSignals.isRunFinished(clean)) splitReportDueTick = tick + SPLIT_REPORT_FALLBACK_TICKS;
        } else if (SPLIT_REPORT_END.matcher(clean).matches()) {
            splitReportDueTick = Math.min(splitReportDueTick, tick + SPLIT_REPORT_END_DELAY_TICKS);
        }
    }

    /** The held lines once their report is through, phase line first; empty while it is still going. */
    List<DungeonSplitMessages.Notice> dueSplitNotices(long tick) {
        if (splitReportDueTick == Long.MIN_VALUE || tick < splitReportDueTick) return List.of();
        splitReportDueTick = Long.MIN_VALUE;
        List<DungeonSplitMessages.Notice> due = List.copyOf(heldSplitNotices);
        heldSplitNotices.clear();
        return due;
    }

    /** Hypixel changed the Necron-death dialogue: until the first dragon, keep every line that could mark it. */
    private void traceNecronEnd(String text) {
        if (!splitTracker.inNecronEndWindow()) return;
        String clean = DungeonLifecycleSignals.clean(text);
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("necron") || lower.contains("relic")
            || clean.startsWith("[BOSS] Wither King:") || clean.startsWith("[BOSS] The Wither King:")) {
            KungDebugRecorder.event("necron-end", "chat split=" + splitTracker.currentSplitName() + " text=\"" + clean + "\"");
        }
    }

    public LearnRoomTypeResult learnCurrentRoomType(Minecraft client, RoomType roomType) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        if (!currentRoomCore.valid()) {
            return LearnRoomTypeResult.failed(currentRoomCore.message());
        }

        try {
            DungeonRoomClassifier.learnRoomType(currentRoomCore.coreHash(), roomType);
            cachedRenderPlan = null;
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to learn dungeon room type.", exception);
            return LearnRoomTypeResult.failed("Could not save the room type. See latest.log.");
        }

        return LearnRoomTypeResult.learned(
            currentRoomCore.coreHash(),
            currentRoomCore.roomGridX(),
            currentRoomCore.roomGridZ(),
            roomType
        );
    }

    public LearnRoomResult learnCurrentRoom(Minecraft client, String name, RoomType roomType, int secrets, int crypts) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        return learnRoomCore(currentRoomCore, name, roomType, secrets, crypts);
    }

    public LearnRoomResult learnCurrentMultiRoom(Minecraft client, String name, RoomType roomType, int secrets, int crypts) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        if (!currentRoomCore.valid()) {
            return LearnRoomResult.failed(currentRoomCore.message());
        }

        List<DungeonKnownRoomCatalog.LearnedRoom> roomComponents =
            connectedLearnedRoomComponents(currentRoomCore, name, roomType, secrets, crypts);
        if (roomComponents.size() <= 1) {
            return LearnRoomResult.failed(
                "Only one matching cell was found. Use regular learn, or load and enter more parts of the room."
            );
        }
        if (roomComponents.size() > MAX_MULTI_LEARN_COMPONENTS) {
            return LearnRoomResult.failed(
                "Multicell learn cancelled: "
                    + roomComponents.size()
                    + " cells found; the maximum allowed is "
                    + MAX_MULTI_LEARN_COMPONENTS
                    + "."
            );
        }

        List<DungeonKnownRoomCatalog.LearnedRoom> syncComponents = new ArrayList<>(roomComponents);
        try {
            for (DungeonKnownRoomCatalog.LearnedRoom component : roomComponents) {
                DungeonRoomClassifier.learnRoomType(component.coreHash(), roomType);
            }
            DungeonKnownRoomCatalog.appendAll(roomComponents);
            for (DungeonKnownRoomCatalog.LearnedRoom component : roomComponents) {
                for (DungeonKnownRoomCatalog.LearnedRoom initialHash : initialLearnedRoomComponents(
                    component,
                    name,
                    roomType,
                    secrets,
                    crypts
                )) {
                    DungeonKnownRoomCatalog.append(initialHash);
                    syncComponents.add(initialHash);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to learn multicell dungeon room.", exception);
            return LearnRoomResult.failed("Could not save the multicell room. See latest.log.");
        }

        pushRoomSyncReport(name, roomType, secrets, crypts, syncComponents);
        return LearnRoomResult.learnedMulti(
            name,
            currentRoomCore.coreHash(),
            currentRoomCore.roomGridX(),
            currentRoomCore.roomGridZ(),
            roomType,
            secrets,
            crypts,
            roomComponents.size()
        );
    }

    public LearnRoomResult learnRoomAt(
        Minecraft client,
        BlockPos targetPosition,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        CurrentRoomCore targetRoomCore = roomCoreAt(client, targetPosition);
        return learnRoomCore(targetRoomCore, name, roomType, secrets, crypts);
    }

    private LearnRoomResult learnRoomCore(
        CurrentRoomCore currentRoomCore,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        if (!currentRoomCore.valid()) {
            return LearnRoomResult.failed(currentRoomCore.message());
        }

        List<DungeonKnownRoomCatalog.LearnedRoom> components = learnedRoomComponentsWithInitialHash(
            currentRoomCore,
            name,
            roomType,
            secrets,
            crypts
        );
        try {
            DungeonRoomClassifier.learnRoomType(currentRoomCore.coreHash(), roomType);
            for (DungeonKnownRoomCatalog.LearnedRoom component : components) {
                DungeonKnownRoomCatalog.append(component);
            }
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to learn dungeon room.", exception);
            return LearnRoomResult.failed("Could not save the room. See latest.log.");
        }

        pushRoomSyncReport(name, roomType, secrets, crypts, components);
        return LearnRoomResult.learned(
            name,
            currentRoomCore.coreHash(),
            currentRoomCore.roomGridX(),
            currentRoomCore.roomGridZ(),
            roomType,
            secrets,
            crypts
        );
    }

    private List<DungeonKnownRoomCatalog.LearnedRoom> learnedRoomComponentsWithInitialHash(
        CurrentRoomCore currentRoomCore,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        DungeonKnownRoomCatalog.LearnedRoom current = learnedRoomComponent(
            System.currentTimeMillis(),
            name,
            roomType,
            secrets,
            crypts,
            currentRoomCore.coreHash(),
            currentRoomCore.stableCoreHash(),
            currentRoomCore.roomGridX(),
            currentRoomCore.roomGridZ()
        );
        List<DungeonKnownRoomCatalog.LearnedRoom> components = new ArrayList<>();
        components.add(current);
        components.addAll(initialLearnedRoomComponents(current, name, roomType, secrets, crypts));
        return components;
    }

    private List<DungeonKnownRoomCatalog.LearnedRoom> initialLearnedRoomComponents(
        DungeonKnownRoomCatalog.LearnedRoom current,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        DungeonMapSnapshot.ObservedPoint initialPoint =
            scanRecorder.mapSnapshot().initialRoomPointAt(current.roomGridX(), current.roomGridZ());
        if (initialPoint == null
            || initialPoint.point().kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(initialPoint.point().coreHash())
            || sameHash(current, initialPoint.point())) {
            return List.of();
        }
        return List.of(learnedRoomComponent(
            current.order() + 1,
            name,
            roomType,
            secrets,
            crypts,
            initialPoint.point().coreHash(),
            initialPoint.point().stableCoreHash(),
            current.roomGridX(),
            current.roomGridZ()
        ));
    }

    private static DungeonKnownRoomCatalog.LearnedRoom learnedRoomComponent(
        long order,
        String name,
        RoomType roomType,
        int secrets,
        int crypts,
        int coreHash,
        int stableCoreHash,
        int roomGridX,
        int roomGridZ
    ) {
        return new DungeonKnownRoomCatalog.LearnedRoom(
            order,
            name,
            roomType,
            secrets,
            crypts,
            coreHash,
            stableCoreHash,
            roomGridX,
            roomGridZ
        );
    }

    private static boolean sameHash(DungeonKnownRoomCatalog.LearnedRoom current, DungeonScanPoint point) {
        return current.coreHash() == point.coreHash()
            && current.stableCoreHash() == point.stableCoreHash();
    }

    private List<DungeonKnownRoomCatalog.LearnedRoom> connectedLearnedRoomComponents(
        CurrentRoomCore currentRoomCore,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        List<DungeonKnownRoomCatalog.LearnedRoom> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[] { currentRoomCore.roomGridX(), currentRoomCore.roomGridZ() });
        visited.add(currentRoomCore.roomGridX() + "," + currentRoomCore.roomGridZ());
        long order = System.currentTimeMillis();

        for (int index = 0; index < queue.size(); index++) {
            int roomGridX = queue.get(index)[0];
            int roomGridZ = queue.get(index)[1];
            DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
            CurrentRoomCore roomCore = observedPoint == null
                ? (roomGridX == currentRoomCore.roomGridX() && roomGridZ == currentRoomCore.roomGridZ()
                    ? currentRoomCore
                    : CurrentRoomCore.failed("Not loaded."))
                : CurrentRoomCore.valid(
                    observedPoint.point().coreHash(),
                    observedPoint.point().stableCoreHash(),
                    roomGridX,
                    roomGridZ
                );
            if (!roomCore.valid()
                || DungeonRoomClassifier.isEmptyCore(roomCore.coreHash())
                || isBlockedMultiLearnRoom(snapshot, roomGridX, roomGridZ, roomCore.coreHash())
                || hasConflictingKnownHint(roomCore.coreHash(), roomCore.stableCoreHash(), name, roomType, secrets)) {
                continue;
            }

            components.add(learnedRoomComponent(
                order++,
                name,
                roomType,
                secrets,
                crypts,
                roomCore.coreHash(),
                roomCore.stableCoreHash(),
                roomGridX,
                roomGridZ
            ));

            addConnectedMultiLearnNeighbor(snapshot, queue, visited, roomGridX, roomGridZ, 1, 0, name, roomType, secrets);
            addConnectedMultiLearnNeighbor(snapshot, queue, visited, roomGridX, roomGridZ, -1, 0, name, roomType, secrets);
            addConnectedMultiLearnNeighbor(snapshot, queue, visited, roomGridX, roomGridZ, 0, 1, name, roomType, secrets);
            addConnectedMultiLearnNeighbor(snapshot, queue, visited, roomGridX, roomGridZ, 0, -1, name, roomType, secrets);
        }

        return components;
    }

    private static void addConnectedMultiLearnNeighbor(
        DungeonMapSnapshot snapshot,
        List<int[]> queue,
        Set<String> visited,
        int roomGridX,
        int roomGridZ,
        int dx,
        int dz,
        String name,
        RoomType roomType,
        int secrets
    ) {
        int nextRoomGridX = roomGridX + dx;
        int nextRoomGridZ = roomGridZ + dz;
        String key = nextRoomGridX + "," + nextRoomGridZ;
        if (visited.contains(key) || !DungeonScanUtils.isValidRoomGrid(nextRoomGridX, nextRoomGridZ)) {
            return;
        }

        DungeonMapSnapshot.ObservedPoint doorPoint = snapshot.pointAt(roomGridX * 2 + dx, roomGridZ * 2 + dz);
        if (doorPoint != null
            && doorPoint.point().kind() == DungeonScanPointKind.DOOR
            && doorPoint.point().doorKind().visible()) {
            return;
        }

        DungeonMapSnapshot.ObservedPoint neighbor = snapshot.pointAt(nextRoomGridX * 2, nextRoomGridZ * 2);
        if (neighbor == null
            || neighbor.point().kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(neighbor.point().coreHash())
            || isBlockedMultiLearnRoom(snapshot, nextRoomGridX, nextRoomGridZ, neighbor.point().coreHash())
            || hasConflictingKnownHint(
                neighbor.point().coreHash(),
                neighbor.point().stableCoreHash(),
                name,
                roomType,
                secrets
            )) {
            return;
        }

        visited.add(key);
        queue.add(new int[] { nextRoomGridX, nextRoomGridZ });
    }

    private static boolean isBlockedMultiLearnRoom(
        DungeonMapSnapshot snapshot,
        int roomGridX,
        int roomGridZ,
        int coreHash
    ) {
        return snapshot.isStartRoom(roomGridX * 2, roomGridZ * 2)
            || DungeonRoomClassifier.classifyRoom(coreHash) == RoomType.START;
    }

    private static boolean hasConflictingKnownHint(
        int coreHash,
        int stableCoreHash,
        String name,
        RoomType roomType,
        int secrets
    ) {
        DungeonKnownRoomCatalog.KnownCoreHint hint = KnownDungeonRoomRepository.INSTANCE.knownCoreHint(coreHash);
        if (hint == null && stableCoreHash != 0) {
            hint = KnownDungeonRoomRepository.INSTANCE.knownCoreHint(stableCoreHash);
        }
        return hint != null
            && (!hint.name().equalsIgnoreCase(name)
                || hint.type() != roomType
                || hint.secrets() != secrets);
    }

    public LearnRoomResult updateCurrentRoomPrince(Minecraft client, boolean prince) {
        if (client == null || client.level == null || client.player == null) {
            return LearnRoomResult.failed("You are not currently in a world.");
        }
        if (!isInDungeonArea() || !isInsideDungeonGrid(client)) {
            return LearnRoomResult.failed("Stand in a Catacombs room to update its Prince setting.");
        }
        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        DungeonKnownRoomCatalog.MatchedRoom match = renderPlan().matchContaining(roomGrid.gridX(), roomGrid.gridZ());
        if (match == null) {
            return LearnRoomResult.failed("The current room has not been uniquely identified yet.");
        }
        var room = match.template();
        try {
            DungeonKnownRoomCatalog.updateRoomPrince(room.name(), room.type(), room.secrets(), prince);
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Failed to update dungeon room Prince setting.", exception);
            return LearnRoomResult.failed("Could not save the Prince setting for this room. See latest.log.");
        } catch (IllegalArgumentException exception) {
            return LearnRoomResult.failed("The current room has not been uniquely identified yet.");
        }
        return new LearnRoomResult(true,
            "Prince for \"" + room.name() + "\" (" + room.type() + "): " + prince + ".",
            room.name(), 0, roomGrid.gridX(), roomGrid.gridZ(), room.type(), room.secrets(), room.crypts());
    }

    public LearnRoomResult updateCurrentRoomCrypts(Minecraft client, int crypts) {
        if (client.level == null || client.player == null) {
            return LearnRoomResult.failed("You are not currently in a world.");
        }

        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        DungeonKnownRoomCatalog.MatchedRoom match = renderPlan().matchContaining(roomGrid.gridX(), roomGrid.gridZ());
        if (match == null) {
            return LearnRoomResult.failed("The current room has not been uniquely identified yet.");
        }

        try {
            DungeonKnownRoomCatalog.updateRoomCrypts(
                match.template().name(),
                match.template().type(),
                match.template().secrets(),
                crypts
            );
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to update dungeon room crypts.", exception);
            return LearnRoomResult.failed("Could not save crypts for this room. See latest.log.");
        }

        return LearnRoomResult.learned(
            match.template().name(),
            0,
            roomGrid.gridX(),
            roomGrid.gridZ(),
            match.template().type(),
            match.template().secrets(),
            crypts
        );
    }

    public RoomDataResult currentRoomData(Minecraft client) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        if (!currentRoomCore.valid()) {
            return RoomDataResult.failed(currentRoomCore.message());
        }

        DungeonKnownRoomCatalog.KnownCoreHint coreHint = roomRepository.knownCoreHint(currentRoomCore.coreHash());
        DungeonKnownRoomCatalog.KnownCoreHint stableHint = currentRoomCore.stableCoreHash() == 0
            ? null
            : roomRepository.knownCoreHint(currentRoomCore.stableCoreHash());
        DungeonKnownRoomCatalog.MatchedRoom match =
            renderPlan().matchContaining(currentRoomCore.roomGridX(), currentRoomCore.roomGridZ());
        DungeonMapSnapshot.ObservedPoint initialPoint =
            scanRecorder.mapSnapshot().initialRoomPointAt(currentRoomCore.roomGridX(), currentRoomCore.roomGridZ());

        String clipboardText = "roomGrid=" + currentRoomCore.roomGridX() + "," + currentRoomCore.roomGridZ()
            + " coreHash=" + currentRoomCore.coreHash()
            + " stableCoreHash=" + currentRoomCore.stableCoreHash()
            + " initial=" + formatInitialPoint(initialPoint)
            + " coreHint=" + formatHint(coreHint)
            + " stableHint=" + formatHint(stableHint)
            + " match=" + formatMatch(match);

        return RoomDataResult.found("Room data copied: " + clipboardText, clipboardText);
    }

    public RoomDataResult exportCurrentRoom(
        Minecraft client,
        String name,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        if (!currentRoomCore.valid()) {
            return RoomDataResult.failed(currentRoomCore.message());
        }

        List<DungeonKnownRoomCatalog.LearnedRoom> components =
            connectedLearnedRoomComponents(currentRoomCore, name, roomType, secrets, crypts);
        if (components.isEmpty()) {
            return RoomDataResult.failed(
                "Room export cancelled: no matching room cell found for \"" + name + "\"."
            );
        }
        if (components.size() > MAX_MULTI_LEARN_COMPONENTS) {
            return RoomDataResult.failed(
                "Room export cancelled: "
                    + components.size()
                    + " cells found; the maximum allowed is "
                    + MAX_MULTI_LEARN_COMPONENTS
                    + "."
            );
        }

        components = new ArrayList<>(components);
        components.sort((first, second) -> {
            int comparison = Integer.compare(first.roomGridZ(), second.roomGridZ());
            if (comparison != 0) {
                return comparison;
            }
            return Integer.compare(first.roomGridX(), second.roomGridX());
        });

        int minRoomGridX = components.stream().mapToInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridX).min().orElse(0);
        int minRoomGridZ = components.stream().mapToInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridZ).min().orElse(0);
        List<ExportCell> exportCells = new ArrayList<>();
        int hashCount = 0;
        for (DungeonKnownRoomCatalog.LearnedRoom component : components) {
            List<ExportHash> hashes = new ArrayList<>();
            addExportHash(hashes, "current", component.coreHash(), component.stableCoreHash());
            for (DungeonKnownRoomCatalog.LearnedRoom initial : initialLearnedRoomComponents(
                component,
                name,
                roomType,
                secrets,
                crypts
            )) {
                addExportHash(hashes, "initial", initial.coreHash(), initial.stableCoreHash());
            }
            hashCount += hashes.size();
            exportCells.add(new ExportCell(
                component.roomGridX() - minRoomGridX,
                component.roomGridZ() - minRoomGridZ,
                component.roomGridX(),
                component.roomGridZ(),
                hashes
            ));
        }

        String clipboardText = roomExportJson(name, roomType, secrets, crypts, exportCells);
        return RoomDataResult.found(
            "Room export copied: \""
                + name
                + "\" "
                + roomType.name()
                + " secrets="
                + secrets
                + (crypts > 0 ? " crypts=" + crypts : "")
                + " cells="
                + exportCells.size()
                + " hashes="
                + hashCount
                + ".",
            clipboardText
        );
    }

    private static void pushRoomSyncReport(
        String name,
        RoomType roomType,
        int secrets,
        int crypts,
        List<DungeonKnownRoomCatalog.LearnedRoom> components
    ) {
        if (components.isEmpty()) {
            return;
        }
        DungeonRoomDataSyncClient.INSTANCE.pushRoomReportAsync(
            roomExportJsonFromLearnedComponents(name, roomType, secrets, crypts, components)
        );
    }

    private static String roomExportJsonFromLearnedComponents(
        String name,
        RoomType roomType,
        int secrets,
        int crypts,
        List<DungeonKnownRoomCatalog.LearnedRoom> components
    ) {
        List<DungeonKnownRoomCatalog.LearnedRoom> sorted = new ArrayList<>(components);
        sorted.sort((first, second) -> {
            int comparison = Integer.compare(first.roomGridZ(), second.roomGridZ());
            if (comparison != 0) {
                return comparison;
            }
            return Integer.compare(first.roomGridX(), second.roomGridX());
        });

        int minRoomGridX = sorted.stream().mapToInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridX).min().orElse(0);
        int minRoomGridZ = sorted.stream().mapToInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridZ).min().orElse(0);
        List<ExportCell> exportCells = new ArrayList<>();
        for (DungeonKnownRoomCatalog.LearnedRoom component : sorted) {
            ExportCell exportCell = exportCellAt(exportCells, component.roomGridX(), component.roomGridZ());
            if (exportCell == null) {
                exportCell = new ExportCell(
                    component.roomGridX() - minRoomGridX,
                    component.roomGridZ() - minRoomGridZ,
                    component.roomGridX(),
                    component.roomGridZ(),
                    new ArrayList<>()
                );
                exportCells.add(exportCell);
            }
            addExportHash(exportCell.hashes(), "learn", component.coreHash(), component.stableCoreHash());
        }
        return roomExportJson(name, roomType, secrets, crypts, exportCells);
    }

    private static ExportCell exportCellAt(List<ExportCell> cells, int roomGridX, int roomGridZ) {
        for (ExportCell cell : cells) {
            if (cell.roomGridX() == roomGridX && cell.roomGridZ() == roomGridZ) {
                return cell;
            }
        }
        return null;
    }

    private static void addExportHash(List<ExportHash> hashes, String source, int coreHash, int stableCoreHash) {
        for (ExportHash hash : hashes) {
            if (hash.coreHash() == coreHash && hash.stableCoreHash() == stableCoreHash) {
                return;
            }
        }
        hashes.add(new ExportHash(source, coreHash, stableCoreHash));
    }

    private static String roomExportJson(
        String name,
        RoomType roomType,
        int secrets,
        int crypts,
        List<ExportCell> cells
    ) {
        StringBuilder json = new StringBuilder();
        json.append('{')
            .append("\"schema\":1,")
            .append("\"kind\":\"kung-room-export\",")
            .append("\"name\":");
        appendJsonString(json, name);
        json.append(',')
            .append("\"type\":\"").append(roomType.name()).append("\",")
            .append("\"secrets\":").append(secrets).append(',')
            .append("\"crypts\":").append(crypts).append(',')
            .append("\"cellCount\":").append(cells.size()).append(',')
            .append("\"createdAt\":").append(System.currentTimeMillis()).append(',')
            .append("\"cells\":[");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ExportCell cell = cells.get(index);
            json.append('{')
                .append("\"dx\":").append(cell.dx()).append(',')
                .append("\"dz\":").append(cell.dz()).append(',')
                .append("\"roomGridX\":").append(cell.roomGridX()).append(',')
                .append("\"roomGridZ\":").append(cell.roomGridZ()).append(',')
                .append("\"hashes\":[");
            for (int hashIndex = 0; hashIndex < cell.hashes().size(); hashIndex++) {
                if (hashIndex > 0) {
                    json.append(',');
                }
                ExportHash hash = cell.hashes().get(hashIndex);
                json.append('{')
                    .append("\"source\":\"").append(hash.source()).append("\",")
                    .append("\"coreHash\":").append(hash.coreHash()).append(',')
                    .append("\"stableCoreHash\":").append(hash.stableCoreHash())
                    .append('}');
            }
            json.append("]}");
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static String formatInitialPoint(DungeonMapSnapshot.ObservedPoint initialPoint) {
        if (initialPoint == null || initialPoint.point().kind() != DungeonScanPointKind.ROOM) {
            return "none";
        }
        return initialPoint.point().coreHash() + "/" + initialPoint.point().stableCoreHash();
    }

    private static String formatHint(DungeonKnownRoomCatalog.KnownCoreHint hint) {
        if (hint == null) {
            return "unknown";
        }
        return hint.name() + "|" + hint.type().name() + "|secrets=" + hint.secrets() + "|crypts=" + hint.crypts();
    }

    private static String formatMatch(DungeonKnownRoomCatalog.MatchedRoom match) {
        if (match == null) {
            return "none";
        }
        return match.template().name()
            + "|"
            + match.template().type().name()
            + "|secrets="
            + match.template().secrets()
            + "|crypts="
            + match.template().crypts()
            + "|cells="
            + match.components().size();
    }

    private CurrentRoomCore currentRoomCore(Minecraft client) {
        if (client.level == null || client.player == null) {
            return CurrentRoomCore.failed("You are not currently in a world.");
        }

        return roomCoreAt(client, client.player.blockPosition());
    }

    private CurrentRoomCore roomCoreAt(Minecraft client, BlockPos position) {
        if (client.level == null || client.player == null) {
            return CurrentRoomCore.failed("You are not currently in a world.");
        }

        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(position);
        int scanGridX = roomGrid.gridX() * 2;
        int scanGridZ = roomGrid.gridZ() * 2;

        if (!isValidScanRoom(scanGridX, scanGridZ)) {
            return CurrentRoomCore.failed(
                "Your position is outside the known dungeon grid: "
                    + roomGrid.gridX() + "," + roomGrid.gridZ()
            );
        }

        int worldX = DungeonScanUtils.worldXForScanGrid(scanGridX);
        int worldZ = DungeonScanUtils.worldZForScanGrid(scanGridZ);
        if (!DungeonScanUtils.isChunkLoaded(client.level, worldX, worldZ)) {
            CurrentRoomCore snapshotCore = snapshotRoomCore(roomGrid.gridX(), roomGrid.gridZ());
            return snapshotCore.valid()
                ? snapshotCore
                : CurrentRoomCore.failed("The center of this room is not loaded yet.");
        }

        int coreHash = DungeonScanUtils.getCoreHash(client.level, worldX, worldZ);
        if (DungeonRoomClassifier.isEmptyCore(coreHash)) {
            CurrentRoomCore snapshotCore = snapshotRoomCore(roomGrid.gridX(), roomGrid.gridZ());
            return snapshotCore.valid()
                ? snapshotCore
                : CurrentRoomCore.failed("The current room appears empty to the scanner.");
        }
        int stableCoreHash = DungeonScanUtils.getStableCoreHash(client.level, worldX, worldZ);

        return CurrentRoomCore.valid(
            coreHash,
            stableCoreHash,
            roomGrid.gridX(),
            roomGrid.gridZ()
        );
    }

    private CurrentRoomCore snapshotRoomCore(int roomGridX, int roomGridZ) {
        DungeonMapSnapshot.ObservedPoint observedPoint =
            scanRecorder.mapSnapshot().pointAt(roomGridX * 2, roomGridZ * 2);
        if (observedPoint == null
            || observedPoint.point().kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(observedPoint.point().coreHash())) {
            return CurrentRoomCore.failed("No loaded room hash is available in the snapshot.");
        }

        return CurrentRoomCore.valid(
            observedPoint.point().coreHash(),
            observedPoint.point().stableCoreHash(),
            roomGridX,
            roomGridZ
        );
    }

    void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (RuntimeException | LinkageError exception) {
            reportRunError(client, "Tick", exception);
            KungMod.LOGGER.warn("Failed to tick dungeon state tracker.", exception);
        }
    }

    private void tickUnsafe(Minecraft client) {
        dungeonTick++;
        synchronizeInstance(client);
        if (!hasClientWorld(client)) return;
        boolean insideDungeonGrid = isInsideDungeonGrid(client);
        if (instanceEntrance == null && dungeonInstanceActive && insideDungeonGrid) {
            instanceEntrance = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        }

        DungeonWorkload workload = DungeonWorkload.current();
        if (dungeonInstanceActive && workload.players()) {
            if (shouldObserve(lastStatsObserveTick, STATS_OBSERVE_INTERVAL_TICKS)) {
                lastStatsObserveTick = dungeonTick;
                runStats.observePlayers(client, dungeonTick);
            }
        }
        if (mapVisibleArea && workload.rooms()) {
            observeClearStates(client);
            syncLiveRooms(client);
            if (!KungBuild.MODRINTH) {
                BloodRushHelperFeature.INSTANCE.observeProgress(client, this);
                BloodRushHelperFeature.INSTANCE.maybeShowDoorTitle(client, this);
            }
        }
        DungeonSplitMessages.send(dueSplitNotices(dungeonTick));
        if (pendingRunSummaryTick != Long.MIN_VALUE && dungeonTick >= pendingRunSummaryTick) {
            sendRunSummaryOnce(client);
        }
        scanReady = dungeonInstanceActive;
        logDungeonState();
    }

    public void debugShowDoorTitle(Minecraft client, int doorCount) {
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.debugShowDoorTitle(client, doorCount);
    }

    public void debugScheduleDoorTitle(int doorCount) {
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.debugScheduleDoorTitle(doorCount, dungeonTick);
    }

    private boolean shouldObserve(long lastObserveTick, long intervalTicks) {
        return lastObserveTick == Long.MIN_VALUE || dungeonTick - lastObserveTick >= intervalTicks;
    }

    private void syncLiveRooms(Minecraft client) {
        if (!DungeonWorkload.current().roomSync() || !DungeonRoomDataSyncClient.INSTANCE.active()) {
            return;
        }
        if (!shouldObserve(lastLiveRoomSyncTick, LIVE_ROOM_SYNC_INTERVAL_TICKS)) {
            return;
        }
        lastLiveRoomSyncTick = dungeonTick;
        String runKey = liveRoomSyncKey();
        if (runKey.isBlank()) {
            logLiveRoomSyncState("waiting run-key server="
                + HypixelInstanceTracker.INSTANCE.serverId()
                + " active="
                + DungeonRoomDataSyncClient.INSTANCE.active()
                + " upload="
                + KungConfig.get().dungeon.roomSyncUploadEnabled());
            return;
        }

        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = renderPlan();
        List<DungeonRoomDataSyncClient.LiveRoomReport> localRooms = liveRoomReports(snapshot, plan, runStats);
        List<DungeonRoomDataSyncClient.LiveDoorReport> localDoors = liveDoorReports(snapshot, plan);
        List<DungeonRoomDataSyncClient.LivePlayerReport> localPlayers = runStats.livePlayerReports(client);
        logLiveRoomSyncState("send runKey="
            + runKey
            + " active="
            + DungeonRoomDataSyncClient.INSTANCE.active()
            + " upload="
            + KungConfig.get().dungeon.roomSyncUploadEnabled()
            + " localRooms="
            + localRooms.size()
            + " localDoors="
            + localDoors.size()
            + " localPlayers="
            + localPlayers.size());
        DungeonRoomDataSyncClient.INSTANCE.syncLiveRoomsAsync(client, runKey, localRooms, localDoors, localPlayers, remoteSnapshot -> {
            if (!runKey.equals(liveRoomSyncKey())) {
                KungDebugRecorder.event("room-sync", "discard remote oldRunKey="
                    + runKey
                    + " currentRunKey="
                    + liveRoomSyncKey());
                return;
            }
            KungDebugRecorder.event("room-sync", "merge remote runKey="
                + runKey
                + " rooms="
                + remoteSnapshot.rooms().size()
                + " doors="
                + remoteSnapshot.doors().size()
                + " players="
                + remoteSnapshot.players().size());
            DungeonMapSnapshot liveSnapshot = scanRecorder.mapSnapshot();
            liveSnapshot.replaceRemoteLiveData(
                remoteLiveRooms(remoteSnapshot.rooms()),
                remoteLiveDoors(remoteSnapshot.doors())
            );
            runStats.mergeRemoteLivePlayers(remoteSnapshot.players());
        });
    }

    private void logLiveRoomSyncState(String stateText) {
        if (!stateText.equals(lastLoggedLiveRoomSyncState)) {
            lastLoggedLiveRoomSyncState = stateText;
            KungDebugRecorder.event("room-sync", stateText);
        }
    }

    private String liveRoomSyncKey() {
        String serverId = HypixelInstanceTracker.INSTANCE.serverId();
        if (serverId == null || serverId.isBlank()) {
            return "";
        }
        return "catacombs:" + serverId.toLowerCase(java.util.Locale.ROOT);
    }

    private static List<DungeonRoomDataSyncClient.LiveRoomReport> liveRoomReports(
        DungeonMapSnapshot snapshot,
        DungeonLiveMapWriter.MatchRenderPlan plan,
        DungeonRunStats runStats
    ) {
        Map<DungeonLiveMapWriter.CellKey, DungeonRoomDataSyncClient.LiveRoomReport> reports = new HashMap<>();
        for (DungeonKnownRoomCatalog.MatchedRoom match : plan.matches()) {
            int matchedSecretsFound = runStats == null ? 0 : runStats.matchedRoomSecretsFound(match);
            boolean completed = false;
            for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                completed = completed || plan.isCompletedRoom(component.roomGridX(), component.roomGridZ());
            }
            if (completed) {
                matchedSecretsFound = Math.max(matchedSecretsFound, match.template().secrets());
            }
            for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                DungeonLiveMapWriter.CellKey cell =
                    new DungeonLiveMapWriter.CellKey(component.roomGridX(), component.roomGridZ());
                putLiveRoomReport(
                    reports,
                    cell,
                    match.template().name(),
                    match.template().type(),
                    match.template().secrets(),
                    match.template().crypts(),
                    plan,
                    runStats,
                    matchedSecretsFound
                );
            }
        }

        for (Map.Entry<DungeonLiveMapWriter.CellKey, DungeonKnownRoomCatalog.KnownCoreHint> entry
            : plan.hints().entrySet()) {
            DungeonLiveMapWriter.CellKey cell = entry.getKey();
            if (!hasLocalRoomPoint(snapshot, cell)) {
                continue;
            }
            DungeonKnownRoomCatalog.KnownCoreHint hint = entry.getValue();
            putLiveRoomReport(
                reports,
                cell,
                hint.name(),
                hint.type(),
                hint.secrets(),
                hint.crypts(),
                plan,
                runStats,
                -1
            );
        }
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                DungeonLiveMapWriter.CellKey cell = new DungeonLiveMapWriter.CellKey(roomGridX, roomGridZ);
                if (reports.containsKey(cell) || !hasLocalRoomPoint(snapshot, cell)) {
                    continue;
                }
                putLiveRoomReport(
                    reports,
                    cell,
                    "",
                    RoomType.UNKNOWN,
                    0,
                    0,
                    plan,
                    runStats,
                    -1
                );
            }
        }
        return List.copyOf(reports.values());
    }

    private static boolean hasLocalRoomPoint(DungeonMapSnapshot snapshot, DungeonLiveMapWriter.CellKey cell) {
        DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(cell.x() * 2, cell.z() * 2);
        return observedPoint != null
            && observedPoint.point().kind() == DungeonScanPointKind.ROOM
            && !DungeonRoomClassifier.isEmptyCore(observedPoint.point().coreHash());
    }

    private static List<DungeonRoomDataSyncClient.LiveDoorReport> liveDoorReports(
        DungeonMapSnapshot snapshot,
        DungeonLiveMapWriter.MatchRenderPlan plan
    ) {
        Map<DungeonLiveMapWriter.CellKey, DungeonRoomDataSyncClient.LiveDoorReport> reports = new HashMap<>();
        long updatedAt = System.currentTimeMillis();
        for (Map.Entry<DungeonLiveMapWriter.CellKey, DungeonLiveMapWriter.DoorRenderInfo> entry
            : plan.externalDoors().entrySet()) {
            DungeonLiveMapWriter.CellKey cell = entry.getKey();
            if (!hasLocalDoorEvidence(snapshot, cell)) {
                continue;
            }
            DungeonLiveMapWriter.DoorRenderInfo door = entry.getValue();
            reports.put(cell, new DungeonRoomDataSyncClient.LiveDoorReport(
                cell.x(),
                cell.z(),
                door.kind(),
                door.targetType(),
                door.targetVisited(),
                "",
                updatedAt
            ));
        }
        return List.copyOf(reports.values());
    }

    private static boolean hasLocalDoorEvidence(DungeonMapSnapshot snapshot, DungeonLiveMapWriter.CellKey cell) {
        DungeonMapSnapshot.GridKey gridKey = new DungeonMapSnapshot.GridKey(cell.x(), cell.z());
        if (snapshot.traversedDoors().contains(gridKey) || snapshot.openedLockedDoors().contains(gridKey)) {
            return true;
        }
        DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(cell.x(), cell.z());
        return observedPoint != null
            && observedPoint.point().kind() == DungeonScanPointKind.DOOR
            && observedPoint.point().doorKind().visible();
    }

    private static void putLiveRoomReport(
        Map<DungeonLiveMapWriter.CellKey, DungeonRoomDataSyncClient.LiveRoomReport> reports,
        DungeonLiveMapWriter.CellKey cell,
        String name,
        RoomType type,
        int secrets,
        int crypts,
        DungeonLiveMapWriter.MatchRenderPlan plan,
        DungeonRunStats runStats,
        int roomSecretsFoundOverride
    ) {
        int roomSecretsMax = Math.max(0, secrets);
        int roomSecretsFound = roomSecretsFoundOverride >= 0
            ? roomSecretsFoundOverride
            : runStats == null ? 0 : runStats.roomSecretsFound(cell.x(), cell.z());
        if (plan.isCompletedRoom(cell.x(), cell.z())) {
            roomSecretsFound = roomSecretsMax;
        }
        reports.put(cell, new DungeonRoomDataSyncClient.LiveRoomReport(
            cell.x(),
            cell.z(),
            name == null ? "" : name,
            type == null ? RoomType.UNKNOWN : type,
            Math.max(0, secrets),
            Math.max(0, crypts),
            Math.clamp(roomSecretsFound, 0, roomSecretsMax),
            roomSecretsMax,
            plan.isVisitedRoom(cell.x(), cell.z()),
            plan.isClearedRoom(cell.x(), cell.z()),
            plan.isCompletedRoom(cell.x(), cell.z()),
            "",
            System.currentTimeMillis()
        ));
    }

    private static List<DungeonMapSnapshot.RemoteRoom> remoteLiveRooms(
        List<DungeonRoomDataSyncClient.LiveRoomReport> remoteRooms
    ) {
        List<DungeonMapSnapshot.RemoteRoom> rooms = new ArrayList<>();
        for (DungeonRoomDataSyncClient.LiveRoomReport room : remoteRooms) {
            if (room == null || room.source() == null || room.source().isBlank()) {
                continue;
            }
            rooms.add(new DungeonMapSnapshot.RemoteRoom(
                room.roomGridX(),
                room.roomGridZ(),
                room.name(),
                room.type(),
                room.secrets(),
                room.crypts(),
                room.roomSecretsFound(),
                room.roomSecretsMax(),
                room.visited(),
                room.cleared(),
                room.completed(),
                room.source(),
                room.updatedAtMillis()
            ));
        }
        return List.copyOf(rooms);
    }

    private static List<DungeonMapSnapshot.RemoteDoor> remoteLiveDoors(
        List<DungeonRoomDataSyncClient.LiveDoorReport> remoteDoors
    ) {
        List<DungeonMapSnapshot.RemoteDoor> doors = new ArrayList<>();
        for (DungeonRoomDataSyncClient.LiveDoorReport door : remoteDoors) {
            if (door == null || door.source() == null || door.source().isBlank()) {
                continue;
            }
            doors.add(new DungeonMapSnapshot.RemoteDoor(
                door.scanGridX(),
                door.scanGridZ(),
                door.kind(),
                door.targetType(),
                door.targetVisited(),
                door.source(),
                door.updatedAtMillis()
            ));
        }
        return List.copyOf(doors);
    }

    private void startDungeonInstance(Minecraft client) {
        KungMod.LOGGER.info("Dungeon instance detected. Starting instance tracking.");
        KungDebugRecorder.event("dungeon", "start instance");
        dungeonInstanceActive = true;
        instanceEntrance = isInsideDungeonGrid(client)
            ? DungeonScanUtils.getRoomGridPosition(client.player.blockPosition()) : null;
        cachedRenderPlan = null;
        cachedRenderPlanRevision = Long.MIN_VALUE;
        cachedRenderPlanCatalogRevision = Long.MIN_VALUE;
        lastLoggedMapTopologyState = "";
        lastLoggedMapTopologySummary = "";
        lastLoggedLiveRoomSyncState = "";
        loggedMapDiscoveryLines.clear();
        lastStatsObserveTick = Long.MIN_VALUE;
        lastClearStateObserveTick = Long.MIN_VALUE;
        lastLiveRoomSyncTick = Long.MIN_VALUE;
        runStats.reset();
        babyZombieDeaths.clear();
        runStats.startRun(dungeonTick);
        splitTracker.reset();
        configureDungeonFloor();
        pendingRunSummaryTick = Long.MIN_VALUE;
        runSummarySent = false;
        runStartMessageSent = false;
        runEndMessageSent = false;
        realRunStarted = false;
        if (consumePendingRunStartSignal()) {
            startSplitRun();
            realRunStarted = true;
            if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.scheduleInitial(dungeonTick);
            scanRecorder.restartRecording();
            sendRunStartedMessage(client);
        }
        scanReady = true;
        scanRecorder.scanNow(client, this);
    }

    private void restartDungeonRun(Minecraft client) {
        if (!dungeonInstanceActive) {
            KungDebugRecorder.event("dungeon", "restart requested without active instance");
            startDungeonInstance(client);
            startSplitRun();
            if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.scheduleInitial(dungeonTick);
            realRunStarted = true;
            scanRecorder.restartRecording();
            sendRunStartedMessage(client);
            return;
        }

        KungMod.LOGGER.info("Dungeon run start detected. Restarting run tracking.");
        KungDebugRecorder.event("dungeon", "restart run");
        cachedRenderPlan = null;
        cachedRenderPlanRevision = Long.MIN_VALUE;
        cachedRenderPlanCatalogRevision = Long.MIN_VALUE;
        lastLoggedMapTopologyState = "";
        lastLoggedMapTopologySummary = "";
        lastLoggedLiveRoomSyncState = "";
        loggedMapDiscoveryLines.clear();
        lastStatsObserveTick = Long.MIN_VALUE;
        lastClearStateObserveTick = Long.MIN_VALUE;
        lastLiveRoomSyncTick = Long.MIN_VALUE;
        pendingRunSummaryTick = Long.MIN_VALUE;
        pendingRunStartSignalTick = Long.MIN_VALUE;
        runSummarySent = false;
        boolean preparedRun = !realRunStarted;
        realRunStarted = true;
        int floor = runStats.floor();
        boolean masterMode = runStats.masterMode();
        if (preparedRun) runStats.resetForCountdown();
        else runStats.reset();
        runStats.startRun(dungeonTick);
        runStats.configureForFloor(floor, masterMode);
        startSplitRun();
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.scheduleInitial(dungeonTick);
        // The countdown belongs to the same instance. Keep pre-run cores and door evidence;
        // the bounded scanner will pick up changed columns after the run starts.
        sendRunStartedMessage(client);
        scanReady = true;
        scanRecorder.scanNow(client, this);
    }

    private void startSplitRun() {
        // Run statistics also infer a floor from shared boss names, without knowing its mode.
        var floor = HypixelInstanceTracker.INSTANCE.dungeonFloor();
        splitTracker.startRun(dungeonTick, floor.floor(), floor.masterMode());
    }

    private void endDungeonInstance(Minecraft client) {
        if (!dungeonInstanceActive) {
            return;
        }

        KungMod.LOGGER.info("Dungeon instance ended. Resetting instance tracking.");
        KungDebugRecorder.event("dungeon", "end instance pendingSummary="
            + (pendingRunSummaryTick != Long.MIN_VALUE)
            + " realRunStarted="
            + realRunStarted);
        boolean shouldSendSummary = pendingRunSummaryTick != Long.MIN_VALUE;
        runStats.stopRun(dungeonTick);
        if (shouldSendSummary) {
            sendRunSummaryOnce(client, false);
        } else {
            sendLeftDungeonsMessage(client);
        }
        splitTracker.stopRun();
        scanRecorder.stopRecording();
        instanceEntrance = null;
        inDungeonArea = false;
        mapVisibleArea = false;
        runStats.reset();
        babyZombieDeaths.clear();
        dungeonInstanceActive = false;
        pendingRunSummaryTick = Long.MIN_VALUE;
        runSummarySent = false;
        runStartMessageSent = false;
        runEndMessageSent = false;
        realRunStarted = false;
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.clear(dungeonTick);
        cachedRenderPlan = null;
        cachedRenderPlanRevision = Long.MIN_VALUE;
        cachedRenderPlanCatalogRevision = Long.MIN_VALUE;
        lastLoggedMapTopologyState = "";
        lastLoggedMapTopologySummary = "";
        lastLoggedLiveRoomSyncState = "";
        loggedMapDiscoveryLines.clear();
        lastStatsObserveTick = Long.MIN_VALUE;
        lastClearStateObserveTick = Long.MIN_VALUE;
        scanReady = false;
    }

    private void observeRunStartSignal(Minecraft client, String text) {
        if (!isRunStartSignal(text) || duplicateLifecycleSignal(lastRunStartSignalTick)) {
            return;
        }
        lastRunStartSignalTick = dungeonTick;
        boolean canAccept = canAcceptDungeonLifecycleSignal(client);
        KungDebugRecorder.event("dungeon", "run-start signal canAccept="
            + canAccept
            + " active="
            + dungeonInstanceActive);
        if (!canAccept) {
            pendingRunStartSignalTick = dungeonTick;
            KungDebugRecorder.event("dungeon", "run-start signal pending");
            return;
        }
        restartDungeonRun(client);
    }

    private void observeRunFinishedSignal(String text) {
        if (!isRunFinishedSignal(text) || duplicateLifecycleSignal(lastRunFinishedSignalTick)) {
            return;
        }
        if (!dungeonInstanceActive || !realRunStarted || !canAcceptDungeonLifecycleSignal(Minecraft.getInstance())) {
            return;
        }
        lastRunFinishedSignalTick = dungeonTick;
        KungDebugRecorder.event("dungeon", "run-finished signal");
        sendRunEndedMessage(Minecraft.getInstance());
        runStats.stopRun(dungeonTick);
        splitTracker.stopRun();
        if (!runSummarySent) {
            pendingRunSummaryTick = dungeonTick + RUN_SUMMARY_DELAY_TICKS;
        }
    }

    private void sendRunSummaryOnce(Minecraft client) {
        sendRunSummaryOnce(client, true);
    }

    private void sendRunSummaryOnce(Minecraft client, boolean allowApiWait) {
        if (runSummarySent || !KungConfig.get().dungeon.playerTrackingEnabled()) {
            runSummarySent = true;
            pendingRunSummaryTick = Long.MIN_VALUE;
            return;
        }
        if (allowApiWait && client != null && client.level != null && client.player != null) {
            runStats.observePlayers(client, dungeonTick);
            if (mapVisibleArea) {
                observeClearStates(client);
            }
        }
        runStats.stopRun(dungeonTick);
        if (allowApiWait && !runStats.prepareRunSecretDeltas(client, dungeonTick)) {
            pendingRunSummaryTick = dungeonTick + 1;
            return;
        }
        runStats.sendRunSummary(client, renderPlan());
        runSummarySent = true;
        pendingRunSummaryTick = Long.MIN_VALUE;
    }

    private void sendRunStartedMessage(Minecraft client) {
        if (runStartMessageSent || client == null || client.player == null) {
            return;
        }
        if (!KungConfig.get().debug.dungeonMessagesEnabled()) {
            runStartMessageSent = true;
            runEndMessageSent = false;
            return;
        }
        client.player.sendSystemMessage(KungMessages.debug("Dungeon", "Run started"));
        runStartMessageSent = true;
        runEndMessageSent = false;
    }

    private void sendRunEndedMessage(Minecraft client) {
        if (runEndMessageSent || client == null || client.player == null) {
            return;
        }
        if (!KungConfig.get().debug.dungeonMessagesEnabled()) {
            runEndMessageSent = true;
            return;
        }
        client.player.sendSystemMessage(KungMessages.debug("Dungeon", "Run ended"));
        runEndMessageSent = true;
    }

    private void sendLeftDungeonsMessage(Minecraft client) {
        if (runEndMessageSent || client == null || client.player == null) {
            return;
        }
        if (!KungConfig.get().debug.dungeonMessagesEnabled()
            || (lastLeftDungeonsMessageTick != Long.MIN_VALUE
                && dungeonTick - lastLeftDungeonsMessageTick < LIFECYCLE_MESSAGE_WINDOW_TICKS)) {
            runEndMessageSent = true;
            return;
        }
        lastLeftDungeonsMessageTick = dungeonTick;
        client.player.sendSystemMessage(KungMessages.debug("Dungeon", "Left dungeons"));
        runEndMessageSent = true;
    }

    public void reportRunError(Minecraft client, String area, Throwable throwable) {
        if (!isInDungeonArea() || client == null || client.player == null || throwable == null) {
            return;
        }
        if (lastRunErrorMessageTick != Long.MIN_VALUE && dungeonTick - lastRunErrorMessageTick < 40) {
            return;
        }
        lastRunErrorMessageTick = dungeonTick;
        client.player.sendSystemMessage(KungMessages.error(
            "Error",
            area + ": " + throwable.getClass().getSimpleName() + " - " + safeErrorMessage(throwable)
        ));
    }

    private static String blankDiagnostic(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String safeErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "no message";
        }
        return message.length() > 140 ? message.substring(0, 140) + "..." : message;
    }

    private boolean duplicateLifecycleSignal(long lastSignalTick) {
        return lastSignalTick != Long.MIN_VALUE
            && dungeonTick - lastSignalTick <= LIFECYCLE_DUPLICATE_WINDOW_TICKS;
    }

    private static boolean isRunStartSignal(String text) {
        return DungeonLifecycleSignals.isRunStart(text);
    }

    private static boolean isRunFinishedSignal(String text) {
        return DungeonLifecycleSignals.isRunFinished(text);
    }

    private static boolean hasClientWorld(Minecraft client) {
        return client != null && client.level != null && client.player != null;
    }

    private static boolean isInsideDungeonGrid(Minecraft client) {
        if (!hasClientWorld(client) || !HypixelInstanceTracker.INSTANCE.positionKnown()) return false;
        int min = DungeonScanUtils.START_X - DungeonScanUtils.ROOM_SIZE_BLOCKS / 2;
        int max = DungeonScanUtils.START_X
            + DungeonScanUtils.ROOM_SIZE_BLOCKS * (DungeonScanUtils.SCAN_GRID_SIZE / 2)
            + DungeonScanUtils.ROOM_SIZE_BLOCKS / 2;
        int x = client.player.blockPosition().getX();
        int z = client.player.blockPosition().getZ();
        return x >= min && x <= max && z >= min && z <= max;
    }

    private static boolean canAcceptDungeonLifecycleSignal(Minecraft client) {
        return hasClientWorld(client) && HypixelInstanceTracker.INSTANCE.catacombs();
    }

    private boolean canProcessDungeonRunMessage(Minecraft client) {
        return dungeonInstanceActive && canAcceptDungeonLifecycleSignal(client);
    }

    private boolean consumePendingRunStartSignal() {
        if (pendingRunStartSignalTick == Long.MIN_VALUE
            || dungeonTick - pendingRunStartSignalTick > LIFECYCLE_MESSAGE_WINDOW_TICKS) {
            pendingRunStartSignalTick = Long.MIN_VALUE;
            return false;
        }
        pendingRunStartSignalTick = Long.MIN_VALUE;
        return true;
    }

    private void logDungeonState() {
        HypixelInstanceTracker context = HypixelInstanceTracker.INSTANCE;
        String stateText = "epoch=" + context.instanceEpoch()
            + " active=" + dungeonInstanceActive + " realRunStarted=" + realRunStarted
            + " inArea=" + inDungeonArea + " mapVisible=" + mapVisibleArea
            + " server=" + blankDiagnostic(context.serverId())
            + " instance=" + blankDiagnostic(context.instanceLine());
        if (!stateText.equals(lastLoggedDungeonStateKey)) {
            lastLoggedDungeonStateKey = stateText;
            KungDebugRecorder.event("dungeon-state", stateText);
        }
    }

    private void observeClearStates(Minecraft client) {
        if (!shouldObserve(lastClearStateObserveTick, CLEAR_STATE_OBSERVE_INTERVAL_TICKS)) {
            return;
        }

        DungeonMapCheckmarkReader.observe(client, scanRecorder.mapSnapshot());
        runStats.observeClearStates(client, renderPlan());
        lastClearStateObserveTick = dungeonTick;
    }

    private static boolean isValidScanRoom(int scanGridX, int scanGridZ) {
        return scanGridX >= 0
            && scanGridZ >= 0
            && scanGridX < DungeonScanUtils.SCAN_GRID_SIZE
            && scanGridZ < DungeonScanUtils.SCAN_GRID_SIZE;
    }

    public record LearnRoomTypeResult(
        boolean learned,
        String message,
        int coreHash,
        int roomGridX,
        int roomGridZ,
        RoomType roomType
    ) {
        static LearnRoomTypeResult learned(int coreHash, int roomGridX, int roomGridZ, RoomType roomType) {
            return new LearnRoomTypeResult(
                true,
                "Learned: " + coreHash + "=" + roomType.name()
                    + " at roomGrid " + roomGridX + "," + roomGridZ + ".",
                coreHash,
                roomGridX,
                roomGridZ,
                roomType
            );
        }

        static LearnRoomTypeResult failed(String message) {
            return new LearnRoomTypeResult(false, message, 0, 0, 0, RoomType.UNKNOWN);
        }
    }

    public record LearnRoomResult(
        boolean learned,
        String message,
        String name,
        int coreHash,
        int roomGridX,
        int roomGridZ,
        RoomType roomType,
        int secrets,
        int crypts
    ) {
        static LearnRoomResult learned(
            String name,
            int coreHash,
            int roomGridX,
            int roomGridZ,
            RoomType roomType,
            int secrets,
            int crypts
        ) {
            return new LearnRoomResult(
                true,
                "Room saved: \"" + name + "\" " + roomType.name()
                    + " secrets=" + secrets
                    + (crypts > 0 ? " crypts=" + crypts : "")
                    + " hash=" + coreHash
                    + " roomGrid=" + roomGridX + "," + roomGridZ + ".",
                name,
                coreHash,
                roomGridX,
                roomGridZ,
                roomType,
                secrets,
                crypts
            );
        }

        static LearnRoomResult learnedMulti(
            String name,
            int coreHash,
            int roomGridX,
            int roomGridZ,
            RoomType roomType,
            int secrets,
            int crypts,
            int componentCount
        ) {
            return new LearnRoomResult(
                true,
                "Multicell room saved: \"" + name + "\" " + roomType.name()
                    + " secrets=" + secrets
                    + (crypts > 0 ? " crypts=" + crypts : "")
                    + " cells=" + componentCount
                    + " startHash=" + coreHash
                    + " roomGrid=" + roomGridX + "," + roomGridZ + ".",
                name,
                coreHash,
                roomGridX,
                roomGridZ,
                roomType,
                secrets,
                crypts
            );
        }

        static LearnRoomResult failed(String message) {
            return new LearnRoomResult(false, message, "", 0, 0, 0, RoomType.UNKNOWN, 0, 0);
        }
    }

    public record RoomDataResult(boolean found, String message, String clipboardText) {
        static RoomDataResult found(String message, String clipboardText) {
            return new RoomDataResult(true, message, clipboardText);
        }

        static RoomDataResult failed(String message) {
            return new RoomDataResult(false, message, "");
        }
    }

    private record ExportCell(int dx, int dz, int roomGridX, int roomGridZ, List<ExportHash> hashes) {
    }

    private record ExportHash(String source, int coreHash, int stableCoreHash) {
    }

    private record CurrentRoomCore(
        boolean valid,
        String message,
        int coreHash,
        int stableCoreHash,
        int roomGridX,
        int roomGridZ
    ) {
        static CurrentRoomCore valid(
            int coreHash,
            int stableCoreHash,
            int roomGridX,
            int roomGridZ
        ) {
            return new CurrentRoomCore(
                true,
                "",
                coreHash,
                stableCoreHash,
                roomGridX,
                roomGridZ
            );
        }

        static CurrentRoomCore failed(String message) {
            return new CurrentRoomCore(false, message, 0, 0, 0, 0);
        }
    }
}
