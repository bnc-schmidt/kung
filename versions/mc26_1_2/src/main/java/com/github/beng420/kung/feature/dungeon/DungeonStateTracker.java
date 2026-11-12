package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class DungeonStateTracker {
    private static final int MAX_MULTI_LEARN_COMPONENTS = 4;
    private static final int[][] NEIGHBOR_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Export order: row by row. */
    private static final Comparator<DungeonKnownRoomCatalog.LearnedRoom> GRID_ORDER =
        Comparator.comparingInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridZ)
            .thenComparingInt(DungeonKnownRoomCatalog.LearnedRoom::roomGridX);
    private static final long STATS_OBSERVE_INTERVAL_TICKS = 5;
    private static final long CLEAR_STATE_OBSERVE_INTERVAL_TICKS = 5;
    private static final long LIVE_ROOM_SYNC_INTERVAL_TICKS = 10;
    private static final long RUN_SUMMARY_DELAY_TICKS = 20;
    private static final long LIFECYCLE_DUPLICATE_WINDOW_TICKS = 20;
    private static final long LIFECYCLE_MESSAGE_WINDOW_TICKS = 80;
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
        notices -> this.holdSplitNotices(notices));
    private boolean dungeonInstanceActive;
    // Commit scan readiness only after instance setup/reset reaches its existing boundary.
    private boolean scanReady;
    private boolean inDungeonArea;
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
    private static final long FLOOR_MISSING_LOG_MILLIS = 4_000L;
    private static final java.util.regex.Pattern FLOOR_ROW = java.util.regex.Pattern.compile("catacombs|floor|master|dungeon",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    /** When the floor was first missing during this run, 0 while it is known; {@link #traceMissingFloor} reads it. */
    private long floorMissingSinceMillis;
    private boolean floorMissingLogged;
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
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
    }

    boolean canScanDungeon() {
        return scanReady;
    }

    public DungeonMapSnapshot mapSnapshot() {
        return scanRecorder.mapSnapshot();
    }

    public boolean isRecording() {
        return scanRecorder.isRecording();
    }

    boolean shouldFastScanDoors() {
        if (KungBuild.MODRINTH) return false;
        return BloodRushHelperFeature.INSTANCE.doorTitlePending();
    }

    boolean realRunStarted() {
        return realRunStarted;
    }

    public boolean isInDungeonArea() {
        return inDungeonArea;
    }

    public long dungeonTick() {
        return dungeonTick;
    }

    public DungeonRunStats runStats() {
        return runStats;
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
        return DungeonRoomDebugFormatter.summaryLines(scanRecorder.runTimestamp(), snapshot, renderPlan());
    }

    public List<String> debugMapDecorations(Minecraft client) {
        MapItemSavedData map = DungeonMapItems.mapData(client);
        if (map == null || client.player == null) {
            return List.of("No dungeon map found in the inventory, offhand, or selected slot.");
        }

        List<String> lines = decorationLines(client, map);
        lines.addFirst("MapDecorations count=" + lines.size());
        return lines;
    }

    private List<String> decorationLines(Minecraft client, MapItemSavedData map) {
        DungeonMapSnapshot snapshot = scanRecorder.mapSnapshot();
        List<String> lines = new ArrayList<>();
        for (MapDecoration decoration : map.getDecorations()) {
            String name = DungeonMapCheckmarkReader.decorationName(decoration);
            var pixel = DungeonMapCheckmarkReader.decorationPixel(decoration);
            var overlayPixel = DungeonMapCheckmarkReader.overlayPixelForDecoration(decoration, client, snapshot);
            var room = DungeonMapCheckmarkReader.roomFromDecoration(decoration, client, snapshot);
            lines.add("type=" + DungeonMapCheckmarkReader.decorationTypeName(decoration)
                + " name=" + (name.isEmpty() ? "<empty>" : name)
                + " raw=" + decoration.x() + "," + decoration.y()
                + " pixel=" + pixel.x() + "," + pixel.z()
                + " overlay=" + (overlayPixel == null ? "none" : overlayPixel.x() + "," + overlayPixel.z())
                + " rot=" + decoration.rot()
                + " room=" + (room == null ? "none" : room.roomGridX() + "," + room.roomGridZ()));
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
            + " mapVisibleArea=" + inDungeonArea
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
        appendMapDecorationDebug(debug, client);
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
        appendLine(debug, "runTimestamp=" + scanRecorder.runTimestamp()
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
                DungeonScanPoint point = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
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
                    .append(point == null ? "none" : point.coreHash() + "/" + point.stableCoreHash())
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
            + " partyKnown=" + HypixelPartyTracker.INSTANCE.partyPlayerCount()
            + " score=" + runStats.score()
            + " secrets=" + runStats.secretsFound()
            + "/" + runStats.secretsAvailable()
            + " derivedTotalSecrets=" + runStats.derivedSecretsTotalAvailable()
            + " secretsPercent=" + runStats.secretsPercent()
            + " deaths=" + runStats.deaths()
            + " crypts=" + runStats.cryptsOpened()
            + "/?");
        int index = 0;
        for (DungeonPlayerStats stats : runStats.dungeonPlayersInOrder()) {
            appendLine(debug, "orderedPlayer[" + index++ + "] name=" + stats.name()
                + " uuid=" + stats.uuid()
                + " class=" + stats.dungeonClass()
                + " knownParty=" + HypixelPartyTracker.INSTANCE.isKnownPartyPlayer(stats.name())
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
                + " knownParty=" + HypixelPartyTracker.INSTANCE.isKnownPartyPlayer(player.getName().getString())
                + " block=" + player.blockPosition().getX()
                + "," + player.blockPosition().getY()
                + "," + player.blockPosition().getZ()
                + " roomGrid=" + grid.gridX() + "," + grid.gridZ()
                + " overlayFromWorld=" + Math.round(DungeonMapFeature.mapPixelForWorldX(player.getX())) + "," + Math.round(DungeonMapFeature.mapPixelForWorldZ(player.getZ()))
                + " class=" + classText(player.getUUID()));
        }
    }

    private void appendMapDecorationDebug(StringBuilder debug, Minecraft client) {
        appendLine(debug, "");
        appendLine(debug, "[mapDecorations]");
        MapItemSavedData map = DungeonMapItems.mapData(client);
        if (map == null) {
            appendLine(debug, "map=none");
            return;
        }
        List<String> lines = decorationLines(client, map);
        appendLine(debug, "count=" + lines.size());
        for (int index = 0; index < lines.size(); index++) {
            appendLine(debug, "decoration[" + index + "] " + lines.get(index));
        }
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
        for (String line : DungeonRoomDebugFormatter.summaryLines(scanRecorder.runTimestamp(), scanRecorder.mapSnapshot(), plan)) {
            appendLine(debug, line);
        }
    }

    private String classText(UUID uuid) {
        DungeonPlayerStats stats = uuid == null ? null : runStats.playerStats(uuid);
        return stats == null ? "UNKNOWN(no-stats)" : stats.dungeonClass().name();
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

    void observeOwnBlockClick(BlockPos pos, BlockState state) {
        if (isInDungeonArea()) runStats.observeOwnBlockClick(pos, state, System.currentTimeMillis());
    }

    void observeOwnPickup() {
        if (isInDungeonArea()) runStats.observeOwnPickup(System.currentTimeMillis());
    }

    void observeOwnUse() {
        if (isInDungeonArea()) runStats.observeOwnUse(System.currentTimeMillis());
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
        scanReady = inCatacombs;
    }

    private void configureDungeonFloor() {
        var floor = HypixelInstanceTracker.INSTANCE.dungeonFloor();
        if (!floor.known()) {
            traceMissingFloor();
            return;
        }
        floorMissingSinceMillis = 0L;
        runStats.configureForFloor(floor.floor(), floor.masterMode());
        splitTracker.configureKnownFloor(floor.floor(), floor.masterMode());
    }

    /**
     * Without a floor the splits show no PB and no floor name for the whole run. Once per run, when the floor is
     * still missing a few seconds after the run started, the trace gets the rows the floor is read from.
     */
    private void traceMissingFloor() {
        if (!realRunStarted || floorMissingLogged) return;
        long now = System.currentTimeMillis();
        if (floorMissingSinceMillis == 0L) floorMissingSinceMillis = now;
        if (now - floorMissingSinceMillis < FLOOR_MISSING_LOG_MILLIS) return;
        floorMissingLogged = true;
        var instance = HypixelInstanceTracker.INSTANCE;
        KungDebugRecorder.event("dungeon-splits", "floor-unknown after=" + (now - floorMissingSinceMillis) + "ms location="
            + instance.location() + " sidebar=" + instance.sidebarLines() + " floorRows=" + instance.visibleLines().stream()
                .filter(line -> FLOOR_ROW.matcher(line).find()).toList());
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
            WishAlert.observeMessage(client, text, KungConfig.get().dungeon,
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
        String clean = HypixelLocation.clean(text);
        if (splitReportDueTick == Long.MIN_VALUE) {
            if (DungeonLifecycleSignals.isRunFinished(clean)) splitReportDueTick = tick + SPLIT_REPORT_FALLBACK_TICKS;
        } else if (SPLIT_REPORT_END.matcher(clean).matches()) {
            splitReportDueTick = Math.min(splitReportDueTick, tick + SPLIT_REPORT_END_DELAY_TICKS);
        }
    }

    void holdSplitNotices(List<DungeonSplitMessages.Notice> notices) {
        heldSplitNotices.addAll(notices);
    }

    /**
     * The held lines once their report is through, phase line first; empty while it is still going. Lines that come
     * after the report is through (the "new PB run recorded" line follows the victory text, seconds after the score)
     * have no report to wait for, so they are due at once.
     */
    List<DungeonSplitMessages.Notice> dueSplitNotices(long tick) {
        if (splitReportDueTick != Long.MIN_VALUE && tick < splitReportDueTick) return List.of();
        splitReportDueTick = Long.MIN_VALUE;
        if (heldSplitNotices.isEmpty()) return List.of();
        List<DungeonSplitMessages.Notice> due = List.copyOf(heldSplitNotices);
        heldSplitNotices.clear();
        return due;
    }

    /** Hypixel changed the Necron-death dialogue: until the first dragon, keep every line that could mark it. */
    private void traceNecronEnd(String text) {
        if (!splitTracker.inNecronEndWindow()) return;
        String clean = HypixelLocation.clean(text);
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("necron") || lower.contains("relic")
            || clean.startsWith("[BOSS] Wither King:") || clean.startsWith("[BOSS] The Wither King:")) {
            KungDebugRecorder.event("necron-end", "chat split=" + splitTracker.currentSplitName() + " text=\"" + clean + "\"");
        }
    }

    public LearnRoomResult learnCurrentRoomType(Minecraft client, RoomType roomType) {
        CurrentRoomCore currentRoomCore = currentRoomCore(client);
        if (!currentRoomCore.valid()) {
            return LearnRoomResult.failed(currentRoomCore.message());
        }

        try {
            DungeonRoomClassifier.learnRoomType(currentRoomCore.coreHash(), roomType);
            cachedRenderPlan = null;
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to learn dungeon room type.", exception);
            return LearnRoomResult.failed("Could not save the room type. See latest.log.");
        }

        return LearnRoomResult.learnedType(
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
        DungeonKnownRoomCatalog.LearnedRoom current = new DungeonKnownRoomCatalog.LearnedRoom(
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
        DungeonScanPoint initialPoint =
            scanRecorder.mapSnapshot().initialRoomPointAt(current.roomGridX(), current.roomGridZ());
        if (initialPoint == null
            || initialPoint.kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(initialPoint.coreHash())
            || sameHash(current, initialPoint)) {
            return List.of();
        }
        return List.of(new DungeonKnownRoomCatalog.LearnedRoom(
            current.order() + 1,
            name,
            roomType,
            secrets,
            crypts,
            initialPoint.coreHash(),
            initialPoint.stableCoreHash(),
            current.roomGridX(),
            current.roomGridZ()
        ));
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
            DungeonScanPoint observedPoint = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
            CurrentRoomCore roomCore = observedPoint == null
                ? (roomGridX == currentRoomCore.roomGridX() && roomGridZ == currentRoomCore.roomGridZ()
                    ? currentRoomCore
                    : CurrentRoomCore.failed("Not loaded."))
                : CurrentRoomCore.valid(
                    observedPoint.coreHash(),
                    observedPoint.stableCoreHash(),
                    roomGridX,
                    roomGridZ
                );
            if (!roomCore.valid()
                || DungeonRoomClassifier.isEmptyCore(roomCore.coreHash())
                || isBlockedMultiLearnRoom(snapshot, roomGridX, roomGridZ, roomCore.coreHash())
                || hasConflictingKnownHint(roomCore.coreHash(), roomCore.stableCoreHash(), name, roomType, secrets)) {
                continue;
            }

            components.add(new DungeonKnownRoomCatalog.LearnedRoom(
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

            for (int[] direction : NEIGHBOR_DIRECTIONS) {
                addConnectedMultiLearnNeighbor(
                    snapshot, queue, visited, roomGridX, roomGridZ, direction[0], direction[1], name, roomType, secrets);
            }
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

        DungeonScanPoint doorPoint = snapshot.pointAt(roomGridX * 2 + dx, roomGridZ * 2 + dz);
        if (doorPoint != null
            && doorPoint.kind() == DungeonScanPointKind.DOOR
            && doorPoint.doorKind().visible()) {
            return;
        }

        DungeonScanPoint neighbor = snapshot.pointAt(nextRoomGridX * 2, nextRoomGridZ * 2);
        if (neighbor == null
            || neighbor.kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(neighbor.coreHash())
            || isBlockedMultiLearnRoom(snapshot, nextRoomGridX, nextRoomGridZ, neighbor.coreHash())
            || hasConflictingKnownHint(
                neighbor.coreHash(),
                neighbor.stableCoreHash(),
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
        return new LearnRoomResult(true, "Prince for \"" + room.name() + "\" (" + room.type() + "): " + prince + ".");
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
        DungeonScanPoint initialPoint =
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
        components.sort(GRID_ORDER);

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
        sorted.sort(GRID_ORDER);

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

    private static String formatInitialPoint(DungeonScanPoint initialPoint) {
        if (initialPoint == null || initialPoint.kind() != DungeonScanPointKind.ROOM) {
            return "none";
        }
        return initialPoint.coreHash() + "/" + initialPoint.stableCoreHash();
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
        DungeonScanPoint observedPoint =
            scanRecorder.mapSnapshot().pointAt(roomGridX * 2, roomGridZ * 2);
        if (observedPoint == null || !observedPoint.isRealRoom()) {
            return CurrentRoomCore.failed("No loaded room hash is available in the snapshot.");
        }

        return CurrentRoomCore.valid(
            observedPoint.coreHash(),
            observedPoint.stableCoreHash(),
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

        if (dungeonInstanceActive) runStats.observeWishHealth(client);
        DungeonWorkload workload = DungeonWorkload.current();
        if (dungeonInstanceActive && workload.players()) {
            if (shouldObserve(lastStatsObserveTick, STATS_OBSERVE_INTERVAL_TICKS)) {
                lastStatsObserveTick = dungeonTick;
                runStats.observePlayers(client, dungeonTick);
            }
        }
        if (inDungeonArea && workload.rooms()) {
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
        return "catacombs:" + serverId.toLowerCase(Locale.ROOT);
    }

    private static List<DungeonRoomDataSyncClient.LiveRoomReport> liveRoomReports(
        DungeonMapSnapshot snapshot,
        DungeonLiveMapWriter.MatchRenderPlan plan,
        DungeonRunStats runStats
    ) {
        Map<DungeonLiveMapWriter.CellKey, DungeonRoomDataSyncClient.LiveRoomReport> reports = new HashMap<>();
        for (DungeonKnownRoomCatalog.MatchedRoom match : plan.matches()) {
            int matchedSecretsFound = runStats.secretsFoundFor(match);
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
        DungeonScanPoint observedPoint = snapshot.pointAt(cell.x() * 2, cell.z() * 2);
        return observedPoint != null && observedPoint.isRealRoom();
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
        DungeonScanPoint observedPoint = snapshot.pointAt(cell.x(), cell.z());
        return observedPoint != null
            && observedPoint.kind() == DungeonScanPointKind.DOOR
            && observedPoint.doorKind().visible();
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
            : runStats.roomSecretsFound(cell.x(), cell.z());
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
        resetViewState();
        lastLiveRoomSyncTick = Long.MIN_VALUE;
        runStats.reset();
        babyZombieDeaths.clear();
        runStats.startRun(dungeonTick);
        splitTracker.reset();
        floorMissingSinceMillis = 0L;
        floorMissingLogged = false;
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
        resetViewState();
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

    /** Forgets the cached plan, the logged topology state and the observe ticks. */
    private void resetViewState() {
        cachedRenderPlan = null;
        cachedRenderPlanRevision = Long.MIN_VALUE;
        cachedRenderPlanCatalogRevision = Long.MIN_VALUE;
        lastLoggedMapTopologyState = "";
        lastLoggedMapTopologySummary = "";
        lastLoggedLiveRoomSyncState = "";
        loggedMapDiscoveryLines.clear();
        lastStatsObserveTick = Long.MIN_VALUE;
        lastClearStateObserveTick = Long.MIN_VALUE;
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
        runStats.reset();
        babyZombieDeaths.clear();
        dungeonInstanceActive = false;
        pendingRunSummaryTick = Long.MIN_VALUE;
        runSummarySent = false;
        runStartMessageSent = false;
        runEndMessageSent = false;
        realRunStarted = false;
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.clear(dungeonTick);
        resetViewState();
        scanReady = false;
    }

    private void observeRunStartSignal(Minecraft client, String text) {
        if (!DungeonLifecycleSignals.isRunStart(text) || duplicateLifecycleSignal(lastRunStartSignalTick)) {
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
        if (!DungeonLifecycleSignals.isRunFinished(text) || duplicateLifecycleSignal(lastRunFinishedSignalTick)) {
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
            if (inDungeonArea) {
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
        debugNote(client, "Run started");
        runStartMessageSent = true;
        runEndMessageSent = false;
    }

    private void sendRunEndedMessage(Minecraft client) {
        if (runEndMessageSent || client == null || client.player == null) {
            return;
        }
        debugNote(client, "Run ended");
        runEndMessageSent = true;
    }

    private void sendLeftDungeonsMessage(Minecraft client) {
        if (runEndMessageSent || client == null || client.player == null) {
            return;
        }
        boolean repeated = lastLeftDungeonsMessageTick != Long.MIN_VALUE
            && dungeonTick - lastLeftDungeonsMessageTick < LIFECYCLE_MESSAGE_WINDOW_TICKS;
        if (!repeated && debugNote(client, "Left dungeons")) lastLeftDungeonsMessageTick = dungeonTick;
        runEndMessageSent = true;
    }

    /** Whether the player got the note: the Dungeon debug messages must be on. */
    private static boolean debugNote(Minecraft client, String text) {
        if (!KungConfig.get().debug.dungeonMessagesEnabled()) return false;
        client.player.sendSystemMessage(KungMessages.debug("Dungeon", text));
        return true;
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

    boolean canProcessDungeonRunMessage(Minecraft client) {
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
            + " inArea=" + inDungeonArea + " mapVisible=" + inDungeonArea
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

    public record LearnRoomResult(boolean learned, String message) {
        static LearnRoomResult learnedType(int coreHash, int roomGridX, int roomGridZ, RoomType roomType) {
            return new LearnRoomResult(
                true,
                "Learned: " + coreHash + "=" + roomType.name()
                    + " at roomGrid " + roomGridX + "," + roomGridZ + "."
            );
        }

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
                    + " roomGrid=" + roomGridX + "," + roomGridZ + "."
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
                    + " roomGrid=" + roomGridX + "," + roomGridZ + "."
            );
        }

        static LearnRoomResult failed(String message) {
            return new LearnRoomResult(false, message);
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
