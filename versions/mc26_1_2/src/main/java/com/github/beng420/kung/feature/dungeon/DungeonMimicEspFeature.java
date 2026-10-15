package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class DungeonMimicEspFeature extends ConfigurableFeature<DungeonConfig> {
    private static final Identifier WAYPOINT_HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "mimic_esp_waypoint");
    private static final long SCAN_INTERVAL_TICKS = 10L;
    private static final long OPENED_CHEST_COMBAT_WINDOW_TICKS = 20L * 25L;
    private static final int TEXT_RED = 0xFFFF4040;
    private static final int WAYPOINT_BACKGROUND = 0x99000000;
    private static final int WAYPOINT_FILL = 0xAAFF3030;
    private static final int WAYPOINT_OUTLINE = 0xFFFFF15A;
    private static final double MIMIC_ENTITY_SEARCH_RANGE = 16.0;
    // The Mimic moves a little before it dies.
    private static final double MIMIC_DEATH_RANGE = 4.0;
    // Longer than SCAN_INTERVAL_TICKS, so a death is judged by at least one scan.
    private static final long MIMIC_DEATH_WINDOW_TICKS = 40L;
    private static final Set<String> KNOWN_STATIC_TRAPPED_CHEST_ROOMS = Set.of(
        "buttons",
        "slime",
        "slimemaze"
    );
    // Hypixel's actionbar writes thousands separators: "+1,251.9 Combat (722,792,684/0) 0/3 Secrets".
    static final Pattern COMBAT_XP_PATTERN =
        Pattern.compile("^\\+[\\d,]+(?:\\.\\d+)?\\s+Combat\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIMIC_KILL_PATTERN =
        Pattern.compile(".*(?:Mimic dead!?|Mimic Killed!|\\$SKYTILS-DUNGEON-SCORE-MIMIC\\$).*", Pattern.CASE_INSENSITIVE);

    private final DungeonStateTracker tracker;
    private final DungeonMimicChestScanner chestScanner = new DungeonMimicChestScanner();
    private final DungeonMimicChestMemory chestMemory = new DungeonMimicChestMemory();
    private final DungeonMimicStaticChests staticChests = new DungeonMimicStaticChests();
    private List<BlockPos> mimicChestPositions = List.of();
    private List<BlockPos> lastKnownMimicChestPositions = List.of();
    private Set<Integer> observedMimicEntityIds = Set.of();
    private ScanStats lastScanStats = ScanStats.empty();
    private Object observedLevel;
    private long observedMapGeneration = Long.MIN_VALUE;
    private DungeonLiveMapWriter.CellKey lastPlayerRoom;
    private boolean mimicEncounterActive;
    private long mimicChestOpenedTick = Long.MIN_VALUE;
    private long lastScanTick = Long.MIN_VALUE;
    private long lastRenderLogTick = Long.MIN_VALUE;
    private String lastRenderLogState = "";
    private String lastLoggedState = "";
    private String lastCandidateLogState = "";
    private DungeonMapSnapshot.GridKey observedMimicMapRoom;
    private String mimicMapReason = "no-candidates";
    // Trace dedupe, cleared with the rest of the per-run state in clear().
    private final Set<String> rejectedLogged = new HashSet<>();
    private final Set<Long> anomalyLogged = new HashSet<>();

    public DungeonMimicEspFeature(DungeonStateTracker tracker) {
        super(config -> config.dungeon);
        this.tracker = java.util.Objects.requireNonNull(tracker);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            observeMessage(Minecraft.getInstance(), message.getString())
        );
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(Minecraft.getInstance(), message.getString())
        );
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.PLAYER_LIST,
            WAYPOINT_HUD_ID,
            this::renderWaypointHud
        );
    }

    @Override
    public boolean isEnabled() {
        DungeonConfig config = config();
        return config.enabled() && config.mimicEspEnabled();
    }

    private void tick(Minecraft client) {
        if (tracker.runStats().mimicKilled()) {
            tracker.mapSnapshot().clearMimicRooms("mimic-killed");
            clear();
            return;
        }
        if (!shouldTrack(client)) {
            clear();
            return;
        }

        if (observedLevel != client.level || observedMapGeneration != tracker.mapSnapshot().resetGeneration()) {
            clear();
            observedLevel = client.level;
            observedMapGeneration = tracker.mapSnapshot().resetGeneration();
        }

        long nowTick = tracker.dungeonTick();
        staticChests.prepare();
        chestScanner.tick(client.level, client.player.blockPosition(), nowTick);
        DungeonLiveMapWriter.CellKey playerRoom = currentPlayerRoom(client);
        boolean enteredNewRoom = playerRoom != null && !playerRoom.equals(lastPlayerRoom);
        lastPlayerRoom = playerRoom;
        if (!enteredNewRoom && lastScanTick != Long.MIN_VALUE && nowTick - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }

        lastScanTick = nowTick;
        List<BlockPos> previousChestPositions = mimicChestPositions;
        mimicChestPositions = findMimicChests(client);
        observeOpenedMimicChest(client, previousChestPositions, mimicChestPositions, nowTick);
        DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
        chestMemory.observe(mimicChestPositions, pos -> isAllowedMimicPosition(client, renderPlan, pos));
        lastKnownMimicChestPositions = chestMemory.positions();
        observeMimicRooms();
        logCandidateState(renderPlan);
        observeMimicEntityState(client);
        logState();
    }

    private void renderWaypointHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (!shouldTrack(client) || !hasCurrentObservation(client) || mimicChestPositions.isEmpty()) {
            return;
        }

        try {
            DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
            Set<String> drawnRooms = new HashSet<>();
            List<Marker> drawn = new ArrayList<>();
            for (BlockPos pos : mimicChestPositions) {
                String match = sameRoomReason(client, renderPlan, pos);
                if (match != null) {
                    String roomKey = roomKey(renderPlan, pos);
                    if (roomKey != null && !drawnRooms.add(roomKey)) {
                        continue;
                    }
                    Marker marker = drawScreenWaypoint(client, graphics, pos, match);
                    if (marker != null) drawn.add(marker);
                }
            }
            Vec3 camera = McCompat.camera(client).position();
            logAnomaly(client, renderPlan, camera, drawn);
            logRenderState(camera, sameRoomMimicChestCount(client), renderPlan, drawn);
        } catch (RuntimeException | LinkageError exception) {
            tracker.reportRunError(client, "Mimic ESP waypoint", exception);
            KungMod.LOGGER.warn("Failed to render dungeon mimic waypoint.", exception);
        }
    }

    private boolean shouldTrack(Minecraft client) {
        if (!isEnabled()
            || client.level == null
            || client.player == null
            || !tracker.isInDungeonArea()
            || tracker.runStats().mimicKilled()) {
            return false;
        }

        int floor = tracker.runStats().floor();
        return floor <= 0 || floor == 6 || floor == 7;
    }

    private boolean hasCurrentObservation(Minecraft client) {
        return observedLevel == client.level
            && observedMapGeneration == tracker.mapSnapshot().resetGeneration();
    }

    private List<BlockPos> findMimicChests(Minecraft client) {
        Set<BlockPos> result = new LinkedHashSet<>();
        DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
        forgetTrapMimicRooms(renderPlan);
        for (BlockPos pos : chestScanner.positions()) {
            if (isMimicChest(client, renderPlan, pos)) result.add(pos);
        }
        lastScanStats = new ScanStats(chestScanner.cachedChunks(), chestScanner.checkedBlockEntityPositions(),
            chestScanner.fallbackScans());
        return List.copyOf(result);
    }

    private boolean isMimicChest(
        Minecraft client,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        BlockPos pos
    ) {
        BlockState state = client.level.getBlockState(pos);
        return state.is(Blocks.TRAPPED_CHEST) && isAllowedMimicPosition(client, renderPlan, pos);
    }

    private boolean isAllowedMimicPosition(Minecraft client, DungeonLiveMapWriter.MatchRenderPlan renderPlan, BlockPos pos) {
        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(pos);
        if (!isInsideDungeonGrid(grid)) return reject(pos, "outside-grid");
        if (renderPlan.roomTypeAt(grid.gridX(), grid.gridZ()) == RoomType.TRAP) return reject(pos, "trap-room");
        var decision = staticChests.check(tracker, client.level, pos, tracker.dungeonTick());
        if (decision.fixed()) return reject(pos, "fixed-static");
        // Existing room-wide exclusions remain only for variants without confirmed positions.
        // Once a variant has a template, an additional chest in Buttons/Slime is a candidate too.
        if (!decision.hasPattern() && isKnownStaticTrappedChestRoom(renderPlan, grid.gridX(), grid.gridZ())) {
            return reject(pos, "static-room:" + canonicalRoomName(roomName(renderPlan, grid.gridX(), grid.gridZ())));
        }
        return true;
    }

    private boolean reject(BlockPos pos, String reason) {
        String line = "pos=" + xyz(pos) + " reason=" + reason;
        if (rejectedLogged.add(line)) KungDebugRecorder.event("mimic-rejected", line);
        return false;
    }

    private static boolean isInsideDungeonGrid(DungeonScanUtils.GridPosition grid) {
        return grid.gridX() >= 0
            && grid.gridZ() >= 0
            && grid.gridX() <= DungeonScanUtils.SCAN_GRID_SIZE / 2
            && grid.gridZ() <= DungeonScanUtils.SCAN_GRID_SIZE / 2;
    }

    private static DungeonLiveMapWriter.CellKey currentPlayerRoom(Minecraft client) {
        if (client.player == null) {
            return null;
        }
        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        if (!isInsideDungeonGrid(grid)) {
            return null;
        }
        return new DungeonLiveMapWriter.CellKey(grid.gridX(), grid.gridZ());
    }

    private void forgetTrapMimicRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        for (DungeonMapSnapshot.GridKey room : tracker.mapSnapshot().mimicRooms()) {
            if (renderPlan.roomTypeAt(room.gridX(), room.gridZ()) == RoomType.TRAP) {
                tracker.mapSnapshot().forgetMimicRoom(room.gridX(), room.gridZ(), "trap-room-filter");
            }
        }
    }

    private void observeMimicRooms() {
        DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
        BlockPos pos = chestMemory.mapChest(chest -> roomKey(renderPlan, chest));
        if (pos == null) {
            mimicMapReason = lastKnownMimicChestPositions.isEmpty() ? "no-candidates" : "ambiguous-candidate-rooms";
            forgetObservedMimicMapRoom("mimic-candidate-ambiguous");
            return;
        }

        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(pos);
        DungeonMapSnapshot.GridKey room = new DungeonMapSnapshot.GridKey(grid.gridX(), grid.gridZ());
        if (observedMimicMapRoom != null && !observedMimicMapRoom.equals(room)) {
            tracker.mapSnapshot().forgetMimicRoom(
                observedMimicMapRoom.gridX(),
                observedMimicMapRoom.gridZ(),
                "mimic-candidate-changed"
            );
        }
        observedMimicMapRoom = room;
        mimicMapReason = "single-candidate-room";
        tracker.mapSnapshot().observeMimicRoom(
            grid.gridX(),
            grid.gridZ(),
            "trapped-chest@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
        );
    }

    private void forgetObservedMimicMapRoom(String source) {
        if (observedMimicMapRoom == null) {
            return;
        }
        tracker.mapSnapshot().forgetMimicRoom(
            observedMimicMapRoom.gridX(),
            observedMimicMapRoom.gridZ(),
            source
        );
        observedMimicMapRoom = null;
    }

    private void observeOpenedMimicChest(
        Minecraft client,
        List<BlockPos> previousPositions,
        List<BlockPos> currentPositions,
        long nowTick
    ) {
        if (previousPositions.isEmpty() || client.level == null) {
            return;
        }
        DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
        for (BlockPos previous : previousPositions) {
            if (currentPositions.contains(previous) || !playerInSameRoom(client, renderPlan, previous)
                || !isAllowedMimicPosition(client, renderPlan, previous)) {
                continue;
            }
            if (!client.level.hasChunk(previous.getX() >> 4, previous.getZ() >> 4)
                || client.level.getBlockState(previous).is(Blocks.TRAPPED_CHEST)) {
                continue;
            }
            mimicEncounterActive = true;
            mimicChestOpenedTick = nowTick;
            KungDebugRecorder.event("mimic-esp", "mimic chest opened pos="
                + previous.getX() + "," + previous.getY() + "," + previous.getZ());
            return;
        }
    }

    private void observeMimicEntityState(Minecraft client) {
        if (client.level == null || lastKnownMimicChestPositions.isEmpty()) {
            observedMimicEntityIds = Set.of();
            return;
        }

        for (int entityId : observedMimicEntityIds) {
            Entity entity = client.level.getEntity(entityId);
            if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying()) {
                completeMimic(client, "mimic-entity-dead");
                return;
            }
        }

        long nowTick = tracker.dungeonTick();
        // Same test as observeOpenedMimicChest: a loaded chunk whose chest block is no longer trapped.
        var death = mimicDeathAtChest(tracker.runStats(), lastKnownMimicChestPositions, tracker.babyZombieDeaths(), nowTick,
            chest -> client.level.hasChunk(chest.getX() >> 4, chest.getZ() >> 4)
                && !client.level.getBlockState(chest).is(Blocks.TRAPPED_CHEST));
        if (death != null) {
            KungDebugRecorder.event("mimic-esp", "mimic death at chest pos=" + xyz(death.pos())
                + " age=" + (nowTick - death.tick()) + " opened=true");
            completeMimic(client, "mimic-death-at-chest");
            return;
        }

        Set<Integer> currentIds = new LinkedHashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (isMimicSpawnCandidate(entity)) {
                currentIds.add(entity.getId());
            }
        }

        // Entities also disappear when leaving their tracking range. That is not a kill.
        if (!currentIds.isEmpty()) mimicEncounterActive = true;
        observedMimicEntityIds = currentIds;
    }

    /**
     * A baby zombie that recently died within {@link #MIMIC_DEATH_RANGE} of an opened Mimic chest.
     * The Mimic can die within one scan of spawning and wears armor, which fails the entity test in
     * {@code DungeonRunStats}; position and time stand in for it. Only an opened chest counts, as in
     * {@link #isMimicSpawnCandidate}: a baby zombie dying next to a closed trapped chest is no Mimic.
     */
    static DungeonStateTracker.BabyZombieDeath mimicDeathAtChest(
        DungeonRunStats stats,
        List<BlockPos> chests,
        List<DungeonStateTracker.BabyZombieDeath> deaths,
        long nowTick,
        Predicate<BlockPos> opened
    ) {
        if (!stats.hasMimic() || stats.mimicKilled()) {
            return null;
        }
        for (DungeonStateTracker.BabyZombieDeath death : deaths) {
            if (nowTick - death.tick() > MIMIC_DEATH_WINDOW_TICKS) {
                continue;
            }
            for (BlockPos chest : chests) {
                if (chest.distSqr(death.pos()) <= MIMIC_DEATH_RANGE * MIMIC_DEATH_RANGE && opened.test(chest)) {
                    return death;
                }
            }
        }
        return null;
    }

    private boolean isMimicSpawnCandidate(Entity entity) {
        if (!entity.isAlive() || !nearKnownMimicChest(entity)) {
            return false;
        }
        String name = entityName(entity).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("mimic")) {
            return true;
        }
        // Dungeon baby zombies can pass a closed trapped chest. Only an observed
        // opening establishes the encounter needed to identify an unnamed Mimic.
        return mimicChestOpenedTick != Long.MIN_VALUE
            && entity instanceof Zombie zombie && zombie.isBaby();
    }

    private boolean nearKnownMimicChest(Entity entity) {
        for (BlockPos pos : lastKnownMimicChestPositions) {
            if (entity.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)
                <= MIMIC_ENTITY_SEARCH_RANGE * MIMIC_ENTITY_SEARCH_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static String entityName(Entity entity) {
        net.minecraft.network.chat.Component customName = entity.getCustomName();
        return (customName != null ? customName.getString() : entity.getName().getString())
            .replaceAll("(?:\\u00a7|\\u00c2\\u00a7).", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void observeMessage(Minecraft client, String rawMessage) {
        if (!shouldTrack(client) || rawMessage == null) {
            return;
        }

        String message = rawMessage.replaceAll("(?:\\u00a7|\\u00c2\\u00a7).", "")
            .replaceAll("\\s+", " ")
            .trim();
        if (message.isBlank()) {
            return;
        }
        if (MIMIC_KILL_PATTERN.matcher(message).matches()) {
            completeMimic(client, "chat");
            return;
        }

        // A reset can happen between ticks. Do not apply the old encounter's XP
        // window to the new instance; explicit Mimic kill chat above is fresh evidence.
        if (!hasCurrentObservation(client)) {
            return;
        }

        long nowTick = tracker.dungeonTick();
        boolean openedRecently = mimicEncounterActive
            && mimicChestOpenedTick != Long.MIN_VALUE
            && nowTick - mimicChestOpenedTick <= OPENED_CHEST_COMBAT_WINDOW_TICKS;
        if (openedRecently && COMBAT_XP_PATTERN.matcher(message).matches()) {
            DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
            if (lastKnownMimicChestPositions.stream().noneMatch(pos -> playerInSameRoom(client, renderPlan, pos))) {
                return;
            }
            KungDebugRecorder.event("mimic-esp", "mimic combat xp message=" + KungDebugRecorder.compact(message));
            completeMimic(client, "combat-xp");
        }
    }

    private void completeMimic(Minecraft client, String source) {
        if (tracker.runStats().mimicKilled()) {
            clear();
            return;
        }
        KungDebugRecorder.event("mimic-esp", "mimic complete source=" + source);
        tracker.runStats().observeMimicEspKill(client, source);
        tracker.mapSnapshot().clearMimicRooms(source);
        clear();
    }

    private static boolean playerInSameRoom(
        Minecraft client,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        BlockPos mimicPos
    ) {
        return sameRoomReason(client, renderPlan, mimicPos) != null;
    }

    /** Which test put the chest in the player's room: "cell", "owner" or "cells"; null when it is not. */
    private static String sameRoomReason(
        Minecraft client,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        BlockPos mimicPos
    ) {
        if (client.player == null) {
            return null;
        }
        DungeonScanUtils.GridPosition playerGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        DungeonScanUtils.GridPosition mimicGrid = DungeonScanUtils.getRoomGridPosition(mimicPos);
        if (!isInsideDungeonGrid(playerGrid) || !isInsideDungeonGrid(mimicGrid)) {
            return null;
        }
        DungeonLiveMapWriter.CellKey playerCell = new DungeonLiveMapWriter.CellKey(playerGrid.gridX(), playerGrid.gridZ());
        DungeonLiveMapWriter.CellKey mimicCell = new DungeonLiveMapWriter.CellKey(mimicGrid.gridX(), mimicGrid.gridZ());
        if (playerCell.equals(mimicCell)) return "cell";
        if (renderPlan.sameRoomOwner(playerCell, mimicCell)) return "owner";
        return sameRoomCells(renderPlan, mimicCell).contains(playerCell) ? "cells" : null;
    }

    private static Set<DungeonLiveMapWriter.CellKey> sameRoomCells(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        DungeonLiveMapWriter.CellKey cell
    ) {
        String owner = renderPlan.roomOwners().get(cell);
        if (owner != null) {
            Set<DungeonLiveMapWriter.CellKey> cells = new HashSet<>();
            for (java.util.Map.Entry<DungeonLiveMapWriter.CellKey, String> entry : renderPlan.roomOwners().entrySet()) {
                if (owner.equals(entry.getValue())) {
                    cells.add(entry.getKey());
                }
            }
            return cells;
        }

        DungeonKnownRoomCatalog.MatchedRoom match = matchContaining(renderPlan, cell.x(), cell.z());
        if (match == null) {
            return Set.of(cell);
        }

        Set<DungeonLiveMapWriter.CellKey> cells = new HashSet<>();
        for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
            cells.add(new DungeonLiveMapWriter.CellKey(component.roomGridX(), component.roomGridZ()));
        }
        return cells;
    }

    private static DungeonKnownRoomCatalog.MatchedRoom matchContaining(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        int roomGridX,
        int roomGridZ
    ) {
        for (DungeonKnownRoomCatalog.MatchedRoom match : renderPlan.matches()) {
            if (match.contains(roomGridX, roomGridZ)) {
                return match;
            }
        }
        return null;
    }

    private static boolean isKnownStaticTrappedChestRoom(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        int roomGridX,
        int roomGridZ
    ) {
        DungeonKnownRoomCatalog.MatchedRoom match = matchContaining(renderPlan, roomGridX, roomGridZ);
        if (match != null) {
            return KNOWN_STATIC_TRAPPED_CHEST_ROOMS.contains(canonicalRoomName(match.template().name()));
        }

        DungeonKnownRoomCatalog.KnownCoreHint hint = renderPlan.hintAt(roomGridX, roomGridZ);
        return hint != null && KNOWN_STATIC_TRAPPED_CHEST_ROOMS.contains(canonicalRoomName(hint.name()));
    }

    private static String roomKey(DungeonLiveMapWriter.MatchRenderPlan renderPlan, BlockPos pos) {
        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(pos);
        if (!isInsideDungeonGrid(grid)) {
            return null;
        }
        DungeonLiveMapWriter.CellKey cell = new DungeonLiveMapWriter.CellKey(grid.gridX(), grid.gridZ());
        String owner = renderPlan.roomOwners().get(cell);
        if (owner != null) {
            return owner;
        }
        DungeonKnownRoomCatalog.MatchedRoom match = matchContaining(renderPlan, cell.x(), cell.z());
        if (match != null) {
            return "match-room:" + canonicalRoomName(match.template().name()) + ":" + match.components().size();
        }
        return "cell:" + cell.x() + "," + cell.z();
    }

    private static String canonicalRoomName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static Marker drawScreenWaypoint(
        Minecraft client,
        GuiGraphicsExtractor graphics,
        BlockPos pos,
        String match
    ) {
        String label = "MIMIC";
        Vec3 screen = client.gameRenderer.projectPointToScreen(new Vec3(
            pos.getX() + 0.5,
            pos.getY() + 1.35,
            pos.getZ() + 0.5
        ));
        if (!Double.isFinite(screen.x)
            || !Double.isFinite(screen.y)
            || !Double.isFinite(screen.z)
            || screen.z < -1.0
            || screen.z > 1.0) {
            return null;
        }

        int screenX = (int) Math.round((screen.x + 1.0) * 0.5 * graphics.guiWidth());
        int screenY = (int) Math.round((1.0 - screen.y) * 0.5 * graphics.guiHeight());
        int markerSize = 18;
        int half = markerSize / 2;
        int labelWidth = client.font.width(label);
        int labelX = clampInt(screenX - labelWidth / 2, 2, Math.max(2, graphics.guiWidth() - labelWidth - 2));
        int markerX = clampInt(screenX - half, 2, Math.max(2, graphics.guiWidth() - markerSize - 2));
        int markerY = clampInt(screenY - half, 2, Math.max(2, graphics.guiHeight() - markerSize - client.font.lineHeight - 6));
        int labelY = markerY + markerSize + 2;

        graphics.fill(markerX - 2, markerY - 2, markerX + markerSize + 2, markerY + markerSize + 2, WAYPOINT_BACKGROUND);
        graphics.fill(markerX + 4, markerY + 4, markerX + markerSize - 4, markerY + markerSize - 4, WAYPOINT_FILL);
        graphics.outline(markerX, markerY, markerSize, markerSize, WAYPOINT_OUTLINE);
        graphics.text(client.font, label, labelX, labelY, TEXT_RED, true);
        return new Marker(pos, match, screenX, screenY, markerX != screenX - half || markerY != screenY - half);
    }

    private void clear() {
        if (mimicChestPositions.isEmpty()
            && lastKnownMimicChestPositions.isEmpty()
            && observedMimicEntityIds.isEmpty()
            && observedLevel == null
            && lastScanTick == Long.MIN_VALUE) {
            return;
        }
        tracker.mapSnapshot().clearMimicRooms("mimic-clear");
        chestScanner.clear();
        chestMemory.clear();
        staticChests.clear();
        mimicMapReason = "no-candidates";
        mimicChestPositions = List.of();
        lastKnownMimicChestPositions = List.of();
        observedMimicEntityIds = Set.of();
        observedLevel = null;
        observedMapGeneration = Long.MIN_VALUE;
        lastScanStats = ScanStats.empty();
        lastPlayerRoom = null;
        mimicEncounterActive = false;
        mimicChestOpenedTick = Long.MIN_VALUE;
        lastScanTick = Long.MIN_VALUE;
        lastRenderLogTick = Long.MIN_VALUE;
        lastRenderLogState = "";
        lastLoggedState = "";
        lastCandidateLogState = "";
        observedMimicMapRoom = null;
        rejectedLogged.clear();
        anomalyLogged.clear();
    }

    private void logRenderState(
        Vec3 camera,
        int sameRoomMarkers,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        List<Marker> drawn
    ) {
        long nowTick = tracker.dungeonTick();
        if (lastRenderLogTick != Long.MIN_VALUE && nowTick - lastRenderLogTick < 20L) {
            return;
        }
        lastRenderLogTick = nowTick;
        BlockPos first = mimicChestPositions.getFirst();
        String state = "waypoint chests=" + mimicChestPositions.size()
            + " sameRoom=" + sameRoomMarkers
            + " pos=" + xyz(first)
            + " distance=" + distance(first, camera)
            + drawnText(renderPlan, camera, drawn);
        if (state.equals(lastRenderLogState)) {
            return;
        }
        lastRenderLogState = state;
        KungDebugRecorder.event("mimic-esp", state);
    }

    /** Every frame: a second marker, or one matched by owner/cells instead of the player's own cell, is logged once per chest. */
    private void logAnomaly(
        Minecraft client,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        Vec3 camera,
        List<Marker> drawn
    ) {
        boolean multiple = drawn.size() > 1;
        boolean fresh = false;
        for (Marker marker : drawn) {
            if ((multiple || !"cell".equals(marker.match())) && anomalyLogged.add(marker.pos().asLong())) fresh = true;
        }
        if (!fresh) return;
        DungeonLiveMapWriter.CellKey playerCell = currentPlayerRoom(client);
        KungDebugRecorder.event("mimic-esp", "anomaly why=" + (multiple ? "multiple-markers" : "other-room-cell")
            + " player=" + (playerCell == null ? "none" : playerCell.x() + "," + playerCell.z())
            + drawnText(renderPlan, camera, drawn));
    }

    private static String drawnText(DungeonLiveMapWriter.MatchRenderPlan renderPlan, Vec3 camera, List<Marker> drawn) {
        String list = drawn.stream().limit(4).map(marker -> {
            var grid = DungeonScanUtils.getRoomGridPosition(marker.pos());
            return xyz(marker.pos()) + " room=" + roomName(renderPlan, grid.gridX(), grid.gridZ())
                + " cell=" + grid.gridX() + "," + grid.gridZ() + " match=" + marker.match()
                + " dist=" + distance(marker.pos(), camera) + " screen=" + marker.x() + "," + marker.y()
                + " clamped=" + marker.clamped();
        }).collect(Collectors.joining("; "));
        return " markers=" + drawn.size() + " drawn=[" + list
            + (drawn.size() > 4 ? "; +" + (drawn.size() - 4) + " more" : "") + "]";
    }

    private void logCandidateState(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        String state = "candidates=" + lastKnownMimicChestPositions.stream().map(pos -> {
            var grid = DungeonScanUtils.getRoomGridPosition(pos);
            var match = matchContaining(renderPlan, grid.gridX(), grid.gridZ());
            var hint = renderPlan.hintAt(grid.gridX(), grid.gridZ());
            String name = match != null ? match.template().name() : hint != null ? hint.name() : "unknown";
            return pos.toShortString() + " room=" + name + " cell=" + grid.gridX() + "," + grid.gridZ()
                + " loadedCandidate=" + mimicChestPositions.contains(pos);
        }).toList() + " mapReason=" + mimicMapReason;
        if (state.equals(lastCandidateLogState)) return;
        lastCandidateLogState = state;
        KungDebugRecorder.event("mimic-candidates", state);
    }

    private void logState() {
        String state = "floor=" + tracker.runStats().floor()
            + " killed=" + tracker.runStats().mimicKilled()
            + " chests=" + mimicChestPositions.size()
            + " known=" + lastKnownMimicChestPositions.size()
            + " mapReason=" + mimicMapReason
            + " mapRoom=" + (observedMimicMapRoom == null ? "none"
                : observedMimicMapRoom.gridX() + "," + observedMimicMapRoom.gridZ())
            + " entities=" + observedMimicEntityIds.size()
            + " room=" + (lastPlayerRoom == null ? "none" : lastPlayerRoom.x() + "," + lastPlayerRoom.z())
            + " active=" + mimicEncounterActive
            + " chunks=" + lastScanStats.checkedChunks()
            + " indexedPositionsThisTick=" + lastScanStats.checkedBlockEntityPositions()
            + " blockStateChunks=" + lastScanStats.blockStateScannedChunks();
        if (state.equals(lastLoggedState)) {
            return;
        }
        lastLoggedState = state;
        KungDebugRecorder.event("mimic-esp", state);
    }

    private int sameRoomMimicChestCount(Minecraft client) {
        DungeonLiveMapWriter.MatchRenderPlan renderPlan = tracker.renderPlan();
        int count = 0;
        for (BlockPos pos : mimicChestPositions) {
            if (playerInSameRoom(client, renderPlan, pos)) {
                count++;
            }
        }
        return count;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static int distance(BlockPos pos, Vec3 camera) {
        return (int) Math.round(Vec3.atCenterOf(pos).distanceTo(camera));
    }

    private static String roomName(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int roomGridX, int roomGridZ) {
        DungeonKnownRoomCatalog.MatchedRoom match = matchContaining(renderPlan, roomGridX, roomGridZ);
        if (match != null) return match.template().name();
        DungeonKnownRoomCatalog.KnownCoreHint hint = renderPlan.hintAt(roomGridX, roomGridZ);
        return hint != null ? hint.name() : "unknown";
    }

    private record Marker(BlockPos pos, String match, int x, int y, boolean clamped) {
    }

    private record ScanStats(int checkedChunks, int checkedBlockEntityPositions, int blockStateScannedChunks) {
        static ScanStats empty() {
            return new ScanStats(0, 0, 0);
        }
    }
}
