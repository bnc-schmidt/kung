package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.lang.reflect.Field;
import java.text.NumberFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.KungDebugRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;

public final class DungeonRunStats {
    private static final int MAX_DUNGEON_PLAYERS = 5;
    private static final int TARGET_CRYPTS = 5;
    private static final long ROOM_CLEAR_PLAYER_STALE_TICKS = 40;
    private static final long RUN_SECRET_FINAL_TIMEOUT_TICKS = 60;
    private static final long SCORE_CALC_LOG_INTERVAL_MILLIS = 1_000L;
    // The parenthesized number is Hypixel's own running dungeon score (wiki: "to the right of 'Cleared:'").
    private static final Pattern CLEARED_PATTERN = Pattern.compile("^Cleared:\\s*(\\d+(?:\\.\\d+)?)%(?:\\s*\\((\\d+)\\))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOOR_NAME_PATTERN = Pattern.compile(
        "^\\[BOSS] (The Professor|Bonzo|Scarf|Thorn|Livid|Sadan|Maxor|Storm|Goldor|Necron):",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_PATTERN = Pattern.compile("(?:(\\d+)m)?\\s*(\\d+)s");
    private static final Pattern SCORE_TEXT_PATTERN = Pattern.compile("^\\s*Score\\s*:?\\s*(\\d{1,3})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_SCORE_TEXT_PATTERN =
        Pattern.compile("^\\s*Team\\s+Score\\s*:?\\s*(\\d{1,3})(?:\\s*\\([A-Z+]+\\))?(?:\\s*\\(NEW RECORD!\\))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOM_SECRETS_PATTERN = Pattern.compile("\\b(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s+Secrets\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETED_ROOMS_PATTERN = Pattern.compile("\\s*Completed Rooms:\\s*(\\d+)(?:\\s*/\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENED_ROOMS_PATTERN = Pattern.compile("\\s*Opened Rooms:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYPTS_TAB_PATTERN = Pattern.compile("\\s*Crypts:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYPT_PROGRESS_PATTERN = Pattern.compile(
        ".*(?:\\b(?:opened|destroyed|blew up|blown up)\\b.*\\bcrypt\\b|\\bcrypt\\b.*\\b(?:opened|destroyed|blew up|blown up)\\b).*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PLAYER_TAB_PATTERN = Pattern.compile("(?:\\[\\d+]\\s+)?(?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})\\b.*\\((?:Archer|Berserk(?:er)?|Mage|Healer|Tank)\\b.*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern MIMIC_KILL_PATTERN = Pattern.compile(".*?(?:Mimic dead!?|Mimic Killed!?|\\$SKYTILS-DUNGEON-SCORE-MIMIC\\$)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRINCE_KILL_PATTERN = Pattern.compile(".*?(?:Prince dead!?|Prince Killed!?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAT_KILL_PATTERN = Pattern.compile(".*?(?:Bat dead!?|Bat Killed!?|BatScore Killed!?|Bat Score Killed!?)$", Pattern.CASE_INSENSITIVE);
    private static final String HYPIXEL_PRINCE_KILL_MESSAGE = "A Prince falls. +1 Bonus Score";
    private static final String HYPIXEL_BAT_KILL_MESSAGE = "A Bat has been slain. +1 Bonus Score";
    private static final Pattern PLAYER_CLASS_PATTERN =
        Pattern.compile("\\b(Archer|Berserk(?:er)?|Mage|Healer|Tank)\\b", Pattern.CASE_INSENSITIVE);
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private static Field tabHeaderField;
    private static Field tabFooterField;

    private final Map<UUID, DungeonPlayerStats> players = new HashMap<>();
    private final WishAlert.LowHealth wishLowHealth = new WishAlert.LowHealth();
    private final Map<String, UUID> playerNames = new HashMap<>();
    private final Map<String, DungeonClass> playerClasses = new HashMap<>();
    private final Set<String> dungeonPlayerNames = new HashSet<>();
    private final List<UUID> dungeonPlayerOrder = new ArrayList<>();
    private final UUID[] dungeonPlayerSlots = new UUID[MAX_DUNGEON_PLAYERS];
    private final Map<UUID, RoomKey> playerRooms = new HashMap<>();
    private final Map<RoomKey, UUID> lastPlayerInRoom = new HashMap<>();
    private final DungeonRoomClearAttribution roomClearAttribution = new DungeonRoomClearAttribution();
    private final DungeonDeathTracker deathTracker = new DungeonDeathTracker();
    private final DungeonPuzzleProgress puzzles = new DungeonPuzzleProgress();
    private final Map<RoomKey, Integer> roomSecretsFound = new HashMap<>();
    private final Map<RoomKey, Long> lastRoomPresenceTick = new HashMap<>();
    private final Map<Integer, Integer> derivedSecretsTotalHistogram = new HashMap<>();
    private final Set<String> debugClearedRooms = new HashSet<>();
    private final Set<String> debugCompletedRooms = new HashSet<>();
    private final DungeonApiEnrichment apiEnrichment = new DungeonApiEnrichment();
    private int secretsFound;
    private boolean serverSecretsFoundObserved;
    private int secretsAvailable = -1;
    private int secretsTotalAvailable = -1;
    private int derivedSecretsTotalAvailable = -1;
    private double secretsPercent = -1.0;
    private double clearedPercent = -1.0;
    private int completedRooms;
    private int openedRooms;
    private int totalRoomsFromTab;
    private int deaths;
    private int cryptsOpened;
    private int cryptsAvailable = -1;
    private int serverScore = -1;
    private String serverScoreSource = "";
    private int sidebarScore = -1;
    private String lastLoggedClearedLine = "";
    private int estimatedScore = -1;
    private int floor;
    private boolean masterMode;
    private long runStartTick;
    private long elapsedSeconds = -1L;
    private boolean mimicKilled;
    private boolean princeKilled;
    private boolean batScoreKilled;
    private boolean bloodRoomCompleted;
    private boolean sendingRunSummary;
    private boolean mimicMessageSent;
    private boolean princeMessageSent;
    private boolean batMessageSent;
    private boolean fiveCryptMessageSent;
    private boolean fiveCryptTitleShown;
    private final DungeonAnnouncements announcements = new DungeonAnnouncements();
    private long currentObserveTick;
    private String lastLoggedDungeonSlotState = "";
    private String lastLoggedScoreCalcState = "";
    private long lastLoggedScoreCalcMillis = Long.MIN_VALUE;
    private UUID selfUuid;
    private String selfName = "";
    private DungeonLiveMapWriter.MatchRenderPlan cachedSecretPlan;
    private DungeonLiveMapWriter.MatchRenderPlan cachedScorePlan;
    private ScoreMapProgress cachedScoreMapProgress = new ScoreMapProgress(0, 0, 0, 0, 0);
    private int cachedCatalogSecretTotal = -1;

    public void reset() {
        reset(false);
    }

    /** The countdown starts this already prepared instance; keep its roster and API baselines. */
    public void resetForCountdown() {
        reset(true);
    }

    private void reset(boolean keepPreparation) {
        wishLowHealth.reset();
        if (keepPreparation) {
            players.values().forEach(DungeonPlayerStats::resetRunCounters);
            // A genuinely completed run must never donate its final API response to another run.
            if (apiEnrichment.finalFetchStarted()) apiEnrichment.reset();
        } else {
            players.clear();
            playerNames.clear();
            playerClasses.clear();
            dungeonPlayerNames.clear();
            dungeonPlayerOrder.clear();
            Arrays.fill(dungeonPlayerSlots, null);
            selfUuid = null;
            selfName = "";
            apiEnrichment.reset();
        }
        playerRooms.clear();
        lastPlayerInRoom.clear();
        roomClearAttribution.reset();
        deathTracker.reset();
        cachedSecretPlan = null;
        cachedScorePlan = null;
        cachedScoreMapProgress = new ScoreMapProgress(0, 0, 0, 0, 0);
        cachedCatalogSecretTotal = -1;
        roomSecretsFound.clear();
        lastRoomPresenceTick.clear();
        derivedSecretsTotalHistogram.clear();
        debugClearedRooms.clear();
        debugCompletedRooms.clear();
        secretsFound = 0;
        serverSecretsFoundObserved = false;
        secretsAvailable = -1;
        secretsTotalAvailable = -1;
        derivedSecretsTotalAvailable = -1;
        secretsPercent = -1.0;
        clearedPercent = -1.0;
        completedRooms = 0;
        openedRooms = 0;
        totalRoomsFromTab = 0;
        deaths = 0;
        puzzles.reset();
        cryptsOpened = 0;
        cryptsAvailable = -1;
        serverScore = -1;
        serverScoreSource = "";
        sidebarScore = -1;
        lastLoggedClearedLine = "";
        estimatedScore = -1;
        if (!keepPreparation) {
            floor = 0;
            masterMode = false;
        }
        runStartTick = 0L;
        elapsedSeconds = -1L;
        mimicKilled = false;
        princeKilled = false;
        batScoreKilled = false;
        bloodRoomCompleted = false;
        mimicMessageSent = false;
        princeMessageSent = false;
        batMessageSent = false;
        fiveCryptMessageSent = false;
        fiveCryptTitleShown = false;
        announcements.reset();
        sendingRunSummary = false;
        currentObserveTick = 0L;
        lastLoggedDungeonSlotState = "";
        lastLoggedScoreCalcState = "";
        lastLoggedScoreCalcMillis = Long.MIN_VALUE;
    }

    public void startRun(long nowTick) {
        runStartTick = nowTick;
        elapsedSeconds = 0L;
    }

    public void configureForFloor(int floor, boolean masterMode) {
        if (floor < 0 || floor > 7) return;
        this.floor = floor;
        this.masterMode = masterMode;
    }

    void observeFloorMetadata(String line) {
        HypixelDungeonFloor metadata = HypixelDungeonFloor.fromLine(line);
        if (metadata.known()) {
            configureForFloor(metadata.floor(), metadata.masterMode());
            return;
        }
        if (floor <= 0) {
            Matcher boss = FLOOR_NAME_PATTERN.matcher(line);
            if (boss.find()) floor = floorForBossName(boss.group(1));
        }
    }

    public void stopRun(long nowTick) {
        if (runStartTick > 0L) {
            elapsedSeconds = Math.max(elapsedSeconds, ticksToSeconds(nowTick - runStartTick));
        }
    }

    public boolean prepareRunSecretDeltas(Minecraft client, long nowTick) {
        if (client == null
            || client.player == null
            || !HypixelSkyBlockProfileClient.secretsSourceAvailable()) {
            return true;
        }
        if (apiEnrichment.finalFetchStarted()) {
            return apiEnrichment.finalFetchCompleteOrTimedOut(nowTick, RUN_SECRET_FINAL_TIMEOUT_TICKS);
        }

        apiEnrichment.startFinalFetch(nowTick);
        Set<String> names = new HashSet<>();
        for (DungeonPlayerStats stats : summaryPlayers(client, client.player.getUUID())) {
            if (isPlayerName(stats.name())) {
                names.add(stats.name());
            }
        }
        if (names.isEmpty()) {
            return true;
        }

        for (String name : names) {
            requestRunSecretFinal(client, name);
        }
        return !apiEnrichment.hasPendingFinalFetches();
    }

    public void observePlayers(Minecraft client, long nowTick) {
        if (client.level == null || client.player == null) {
            return;
        }

        rememberSelf(client.player.getUUID(), client.player.getName().getString());
        currentObserveTick = nowTick;
        announcements.flush(client);
        observeScoreboard(client);
        observeTabList(client);
        registerPlayerName(client.player.getName().getString(), client.player.getUUID());
        observePlayerRoom(client.player.getUUID(), client.player.blockPosition(), nowTick);
        for (UUID uuid : knownTrackedPlayerUuids()) {
            if (uuid.equals(client.player.getUUID())) {
                continue;
            }
            AbstractClientPlayer player = DungeonScanUtils.playerByUuid(client, uuid);
            if (player != null) {
                observePlayer(client, player, nowTick);
            }
        }
        if (runStartTick > 0L) {
            elapsedSeconds = Math.max(0L, ticksToSeconds(nowTick - runStartTick));
        }
        updateEstimatedScore();
    }

    public int secretsFound() {
        return secretsFound;
    }

    public int secretsAvailable() {
        return secretsAvailable;
    }

    public int secretsTotalAvailable() {
        return secretsTotalAvailable;
    }

    public int derivedSecretsTotalAvailable() {
        return derivedSecretsTotalAvailable;
    }

    public double secretsPercent() {
        return secretsPercent;
    }

    public double clearedPercent() {
        return clearedPercent;
    }

    public int deaths() {
        return deaths;
    }

    public int cryptsOpened() {
        return cryptsOpened;
    }

    public int cryptsAvailable() {
        return cryptsAvailable;
    }

    public int score() {
        int observed = observedScore();
        return observed >= 0 ? observed : estimatedScore;
    }

    public int score(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int estimatedSecretsAvailable) {
        int observed = observedScore();
        return observed >= 0 ? observed : estimatedScore(renderPlan, estimatedSecretsAvailable);
    }

    /**
     * Hypixel's final Team Score, else its sidebar score once every room is credited. Before that the
     * sidebar counts only finished rooms, so the estimate's Blood/boss forecast stays the better number.
     */
    private int observedScore() {
        if (serverScore >= 0) return serverScore;
        int totalRooms = scoreRoomCellTotal(null);
        return totalRooms > 0 && completedRooms >= totalRooms ? sidebarScore : -1;
    }

    private String observedScoreSource() {
        return serverScore >= 0 ? serverScoreSource : observedScore() >= 0 ? "sidebar" : "";
    }

    public int estimatedScore() {
        return estimatedScore;
    }

    public int sPlusSecretTarget(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int estimatedSecretsAvailable) {
        int totalSecrets = bestSecretsAvailable(estimatedSecretsAvailable);
        if (totalSecrets <= 0) {
            return -1;
        }

        int scoreWithoutSecrets = projectedSPlusSkillScore() + 60 + speedScore() + bonusScore() + paulScoreBonus();
        int neededSecretScore = Math.clamp(300 - scoreWithoutSecrets, 0, 40);
        if (neededSecretScore <= 0) {
            return displayedSecretsFound(totalSecrets);
        }

        int requiredSecrets = (int) Math.ceil(
            neededSecretScore * requiredSecretsPercent() * totalSecrets / 4000.0
        );
        return Math.clamp(requiredSecrets, 0, totalSecrets);
    }

    public int sPlusSecretsRemaining(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int estimatedSecretsAvailable) {
        int target = sPlusSecretTarget(renderPlan, estimatedSecretsAvailable);
        if (target < 0) {
            return -1;
        }
        int totalSecrets = bestSecretsAvailable(estimatedSecretsAvailable);
        int found = displayedSecretsFound(totalSecrets);
        int remaining = found < 0 ? -1 : Math.max(0, target - found);
        if (shouldLogScoreCalculation()) {
            logScoreCalculation(renderPlan, estimatedSecretsAvailable, totalSecrets, found, target, remaining);
        }
        return remaining;
    }

    private boolean shouldLogScoreCalculation() {
        long nowMillis = System.currentTimeMillis();
        if (lastLoggedScoreCalcMillis != Long.MIN_VALUE
            && nowMillis - lastLoggedScoreCalcMillis < SCORE_CALC_LOG_INTERVAL_MILLIS) {
            return false;
        }
        lastLoggedScoreCalcMillis = nowMillis;
        return true;
    }

    private void logScoreCalculation(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        int estimatedSecretsAvailable,
        int totalSecrets,
        int found,
        int target,
        int remaining
    ) {
        int skillRoomScore = roomProgressScore(renderPlan, 80.0);
        int deathPenalty = deathPenalty();
        int unfinishedPuzzles = unfinishedPuzzles(renderPlan);
        int puzzlePenalty = unfinishedPuzzles * 10;
        int skill = skillScore(renderPlan);
        int exploreRoom = exploreRoomScore(renderPlan);
        int secret = secretScore(totalSecrets);
        int explore = exploreScore(renderPlan, totalSecrets);
        int speed = speedScore();
        int bonus = bonusScore();
        int paul = paulScoreBonus();
        SkyBlockMayorTracker mayorTracker = SkyBlockMayorTracker.INSTANCE;
        int rawEstimate = skill + explore + speed + bonus + paul;
        int bestCompleted = bestCompletedRooms(renderPlan);
        int projectedCompletedCells = projectedCompletedRoomCells(renderPlan);
        int scoreRoomCells = scoreRoomCellTotal(renderPlan);
        int mapCompleted = mapCompletedRooms(renderPlan);
        int mapVisited = mapVisitedRooms(renderPlan);
        int mapObserved = observedRoomCount(renderPlan);
        int mapCells = roomCellCount(renderPlan);
        int tabCompletedOwners = tabCompletedRoomOwners(renderPlan);
        int scoreCompleted = scoreCompletedRooms(renderPlan);
        int bestOpened = bestOpenedRooms(renderPlan);
        int totalRooms = totalRoomsEstimate(renderPlan);
        int unfinishedPuzzleCells = unfinishedPuzzleRoomCells(renderPlan);
        String state = "found=" + found
            + " target=" + target
            + " remaining=" + remaining
            + " total=" + totalSecrets
            + " serverFound=" + serverSecretsFoundObserved
            + " available=" + secretsAvailable
            + " totalAvailable=" + secretsTotalAvailable
            + " derivedTotal=" + derivedSecretsTotalAvailable
            + " totalSource=" + secretsTotalSource(totalSecrets, estimatedSecretsAvailable)
            + " percent=" + secretsPercent
            + " score=" + score(renderPlan, totalSecrets)
            + " scoreSource=" + (observedScore() >= 0 ? observedScoreSource() : "estimate")
            + " observedHypixelScore=" + observedScore()
            + " observedHypixelScoreSource=" + blank(observedScoreSource())
            + " sidebarScore=" + sidebarScore
            + " estimatedScore=" + rawEstimate
            + " skill=" + skill
            + "(room=" + skillRoomScore
            + ",deathPenalty=" + deathPenalty
            + ",puzzlePenalty=" + puzzlePenalty
            + ") explore=" + explore
            + "(room=" + exploreRoom
            + ",secret=" + secret
            + ") speed=" + speed
            + " bonus=" + bonus
            + " paul=" + paul
            + " paulActive=" + paulScoreBonusActive()
            + " paulForce=" + KungConfig.get().dungeon.forcePaulScoreEnabled()
            + " paulMayor=" + mayorTracker.paulScoreBonusActive()
            + " mayor=" + blank(mayorTracker.mayorName())
            + " minister=" + blank(mayorTracker.ministerName())
            + " jerryCopies=" + blank(mayorTracker.jerryCopiedMayor())
            + " mayorStatus=" + mayorTracker.status()
            + " rooms=completed:" + bestCompleted
            + ",scoreCompleted:" + scoreCompleted
            + ",projectedCells:" + projectedCompletedCells
            + ",scoreCells:" + scoreRoomCells
            + ",opened:" + bestOpened
            + ",total:" + totalRooms
            + ",mapObserved:" + mapObserved
            + ",mapCells:" + mapCells
            + ",mapVisited:" + mapVisited
            + ",mapCompleted:" + mapCompleted
            + ",tabCompletedOwners:" + tabCompletedOwners
            + ",tabCompleted:" + completedRooms
            + ",tabOpened:" + openedRooms
            + ",tabTotal:" + totalRoomsFromTab
            + ",clearedPercent:" + clearedPercent
            + ",bloodCompleted:" + bloodRoomCompleted
            + ",unfinishedPuzzleCells:" + unfinishedPuzzleCells
            + ",unfinishedClearCells:" + unfinishedClearRoomCells(renderPlan)
            + " unfinishedPuzzles=" + unfinishedPuzzles
            + " failedPuzzles=" + puzzles.failed()
            + " crypts=" + cryptsOpened + "/" + cryptsAvailable
            + " mimic=" + mimicKilled
            + " prince=" + princeKilled
            + " bat=" + batScoreKilled;
        if (!state.equals(lastLoggedScoreCalcState)) {
            lastLoggedScoreCalcState = state;
            KungDebugRecorder.event("score-calc", state);
        }
    }

    public boolean mimicKilled() {
        return mimicKilled;
    }

    public boolean princeKilled() {
        return princeKilled;
    }

    public boolean batScoreKilled() {
        return batScoreKilled;
    }

    public int floor() {
        return floor;
    }

    public boolean masterMode() {
        return masterMode;
    }

    public Map<UUID, DungeonPlayerStats> players() {
        return Map.copyOf(players);
    }

    public DungeonPlayerStats playerStats(UUID uuid) {
        return players.get(uuid);
    }

    /** The local player's class, or UNKNOWN before the roster has resolved it. */
    public DungeonClass selfDungeonClass() {
        return selfUuid == null ? DungeonClass.UNKNOWN : dungeonClass(selfUuid);
    }

    public DungeonClass dungeonClass(UUID uuid) {
        DungeonPlayerStats stats = playerStats(uuid);
        if (stats != null && stats.dungeonClass() != DungeonClass.UNKNOWN) {
            return stats.dungeonClass();
        }
        if (stats != null) {
            DungeonClass namedClass = dungeonClass(stats.name());
            if (namedClass != DungeonClass.UNKNOWN) {
                return namedClass;
            }
        }
        return dungeonClass(trackedPlayerName(uuid));
    }

    public DungeonClass dungeonClass(String name) {
        if (!isPlayerName(name)) {
            return DungeonClass.UNKNOWN;
        }
        return playerClasses.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), DungeonClass.UNKNOWN);
    }

    public void observeClearStates(Minecraft client, DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (renderPlan == null) {
            return;
        }

        for (DungeonKnownRoomCatalog.MatchedRoom match : renderPlan.matches()) {
            Set<Integer> roomCells = match.components().stream()
                .map(component -> clearCell(component.roomGridX(), component.roomGridZ()))
                .collect(java.util.stream.Collectors.toSet());
            if (!isClearableRoom(match.template().type())) {
                if (roomClearAttribution.exclude(roomCells)) updateRoomClearBounds();
                continue;
            }
            boolean cleared = false;
            boolean completed = false;
            for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                cleared = cleared || renderPlan.isClearedRoom(component.roomGridX(), component.roomGridZ());
                completed = completed || renderPlan.isCompletedRoom(component.roomGridX(), component.roomGridZ());
            }
            if (cleared) {
                String roomKey = roomKeyFor(match);
                recordClearedRoom(
                    roomCells,
                    playersForClearedRoom(match.components()),
                    lastPlayerForClearedRoom(match.components())
                );
                sendRoomDebugOnce(client, debugClearedRooms, roomKey, "Room cleared: " + match.template().name());
            }
            if (completed) {
                String roomKey = roomKeyFor(match);
                recordMatchedRoomSecretCount(client, match, match.template().secrets(), match.template().secrets(), false);
                sendRoomDebugOnce(client, debugCompletedRooms, roomKey, "Room completed: " + match.template().name());
            }
        }

        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                if (!isClearableRoom(renderPlan.roomTypeAt(roomGridX, roomGridZ))) {
                    if (roomClearAttribution.exclude(Set.of(clearCell(roomGridX, roomGridZ)))) updateRoomClearBounds();
                    continue;
                }
                if (!renderPlan.isClearedRoom(roomGridX, roomGridZ)
                    || renderPlan.isMatchedRoomCell(roomGridX, roomGridZ)) {
                    continue;
                }

                RoomKey room = new RoomKey(roomGridX, roomGridZ);
                String roomKey = "cell:" + roomGridX + "," + roomGridZ;
                recordClearedRoom(Set.of(clearCell(roomGridX, roomGridZ)), playersForClearedCell(room), lastPlayerInRoom.get(room));
                sendRoomDebugOnce(client, debugClearedRooms, roomKey, "Room cleared: " + roomNameFor(room, null));
            }
        }
    }

    public void observeRoomSecretOverlay(
        Minecraft client,
        String rawMessage,
        DungeonLiveMapWriter.MatchRenderPlan renderPlan
    ) {
        if (client == null || client.player == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        String plainMessage = rawMessage.replaceAll("§.", "");
        Matcher matcher = ROOM_SECRETS_PATTERN.matcher(plainMessage);
        if (!matcher.find()) {
            return;
        }

        int found = Integer.parseInt(matcher.group(1));
        int max = Integer.parseInt(matcher.group(2));
        if (max <= 0 || found < 0 || found > max) {
            return;
        }

        RoomKey currentRoom = currentPlayerRoom(client);
        if (currentRoom == null) {
            return;
        }

        DungeonKnownRoomCatalog.MatchedRoom match = matchedRoomAt(renderPlan, currentRoom);
        if (match != null) {
            recordMatchedRoomSecretCount(client, match, found, max, true);
            return;
        }

        int previous = roomSecretsFound.getOrDefault(currentRoom, 0);
        int next = found;
        roomSecretsFound.put(currentRoom, next);
        if (next != previous) {
            debug(client, "Secret in " + roomNameFor(currentRoom, renderPlan) + ": " + next + "/" + max);
        }
    }

    public void sendRunSummary(Minecraft client, DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (!KungConfig.get().dungeon.playerTrackingEnabled() || client == null || client.gui == null) {
            return;
        }

        if (client.player != null) rememberSelf(client.player.getUUID(), client.player.getName().getString());
        sendRunSummary(renderPlan, client.font::width, McCompat.chat(client)::addClientSystemMessage);
    }

    void sendRunSummary(DungeonLiveMapWriter.MatchRenderPlan renderPlan, ToIntFunction<String> textWidth,
                        Consumer<Component> output) {
        // Player Stats owns the entire chat summary, including the shared totals.
        if (!KungConfig.get().dungeon.playerTrackingEnabled()) return;
        List<DungeonPlayerStats> sortedPlayers = summaryPlayers();
        int availableSecrets = bestSecretsAvailable(catalogSecretsAvailable(renderPlan));
        int totalFoundSecrets = partySecretsFound(sortedPlayers, availableSecrets);
        KungDebugRecorder.event("run-statistics", "summary partySecrets=" + totalFoundSecrets
            + " api=" + (HypixelSkyBlockProfileClient.secretsSourceAvailable() ? "configured" : "disabled-or-missing-key")
            + " players=" + sortedPlayers.stream().map(stats -> stats.name() + ":"
                + (stats.hasSecretsFound() ? stats.secretsFound() : "?") + ":" + stats.secretsSource()).toList());

        sendingRunSummary = true;
        try {
            if (KungConfig.get().dungeon.runStatsReportEnabled()) {
                output.accept(KungMessages.info("Run Stats"));
                output.accept(KungMessages.detail(roomProgressSummary(renderPlan)));
                output.accept(KungMessages.detail(
                    "Score " + score(renderPlan, 0)
                        + " | Secrets " + unknownDash(totalFoundSecrets) + "/" + unknownPositive(availableSecrets)
                        + " | Crypts " + cryptsOpened + "/" + unknownDash(cryptsAvailable)
                ));
                output.accept(KungMessages.detail("Party Secrets: " + unknownDash(totalFoundSecrets)));
            }
            if (KungConfig.get().dungeon.playerStatsReportEnabled()) {
                output.accept(KungMessages.info("Player Stats"));
                String availability = personalSecretAvailability(sortedPlayers, HypixelSkyBlockProfileClient.secretsSourceAvailable());
                if (!availability.isEmpty()) output.accept(KungMessages.detail(availability));
                DungeonRunSummaryLayout.lines(sortedPlayers.stream().map(stats -> new DungeonRunSummaryLayout.Row(
                    stats.name(), dungeonClass(stats.uuid()).argb(), playerStatsCells(stats, totalFoundSecrets))).toList(),
                    textWidth).forEach(output);
            }
        } finally {
            sendingRunSummary = false;
        }
    }

    void rememberSelf(UUID uuid, String name) {
        if (uuid == null || !isPlayerName(name) || !admitDungeonPlayerName(name)) return;
        selfUuid = uuid;
        selfName = name;
        registerPlayerName(name, uuid);
        rememberDungeonPlayerOrder(uuid);
    }

    List<DungeonPlayerStats> summaryPlayers() {
        return summaryPlayers(null, selfUuid);
    }

    private List<DungeonPlayerStats> summaryPlayers(Minecraft client, UUID selfUuid) {
        Map<UUID, DungeonPlayerStats> result = new LinkedHashMap<>();
        if (client != null && client.player != null) {
            addSummaryPlayer(result, selfUuid, client.player.getName().getString());
        } else if (selfUuid != null) {
            addSummaryPlayer(result, selfUuid, selfName);
        }
        // The run roster is independent of the global party cache and remains
        // valid after a player leaves, a boss teleport or the result banner.
        for (UUID uuid : dungeonPlayerOrder) {
            addSummaryPlayer(result, uuid, trackedPlayerName(uuid));
        }

        List<DungeonPlayerStats> sorted = new ArrayList<>(result.values());
        sorted.sort(Comparator
            .comparing((DungeonPlayerStats stats) -> selfUuid == null || !stats.uuid().equals(selfUuid))
            .thenComparing(DungeonPlayerStats::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    private void addSummaryPlayer(Map<UUID, DungeonPlayerStats> playersByUuid, UUID uuid, String fallbackName) {
        if (uuid == null || !isPlayerName(fallbackName) || !isKnownDungeonPlayer(fallbackName)
            || !uuid.equals(dungeonPlayerUuid(fallbackName))) {
            return;
        }
        DungeonPlayerStats stats = playerStats(uuid, fallbackName);
        stats.setName(fallbackName);
        playersByUuid.putIfAbsent(uuid, stats);
    }

    static String playerStatsSummaryLine(DungeonPlayerStats stats, int totalFoundSecrets) {
        return stats.name() + " - " + playerStatsDetails(stats, totalFoundSecrets);
    }

    static String personalSecretAvailability(List<DungeonPlayerStats> players, boolean apiConfigured) {
        long known = players.stream().filter(DungeonPlayerStats::hasSecretsFound).count();
        if (known == players.size()) return "";
        return "Personal secrets: " + known + "/" + players.size() + " available. "
            + (apiConfigured ? "Missing player data was not received." : "No Hypixel API key or Kung server set.");
    }

    private static String playerStatsDetails(DungeonPlayerStats stats, int totalFoundSecrets) {
        return playerStatsCells(stats, totalFoundSecrets).stream()
            .map(DungeonRunSummaryLayout.Cell::text).collect(java.util.stream.Collectors.joining());
    }

    /** The details line in pieces: every number is a column that the layout right-aligns across the players. */
    private static List<DungeonRunSummaryLayout.Cell> playerStatsCells(DungeonPlayerStats stats, int totalFoundSecrets) {
        var cells = new ArrayList<DungeonRunSummaryLayout.Cell>();
        cells.add(DungeonRunSummaryLayout.Cell.number("secrets", stats.hasSecretsFound() ? Integer.toString(stats.secretsFound()) : "?"));
        cells.add(DungeonRunSummaryLayout.Cell.text("/" + unknownDash(totalFoundSecrets) + " Secrets"));
        if (stats.totalSecretsFound() >= 0) {
            cells.add(DungeonRunSummaryLayout.Cell.text(" | "));
            cells.add(DungeonRunSummaryLayout.Cell.number("lifetime", INTEGER_FORMAT.format(stats.totalSecretsFound())));
            cells.add(DungeonRunSummaryLayout.Cell.text(" Total Secrets"));
        }
        cells.add(DungeonRunSummaryLayout.Cell.text(" | "));
        cells.add(DungeonRunSummaryLayout.Cell.number("solo", Integer.toString(stats.soloRoomsCleared())));
        cells.add(DungeonRunSummaryLayout.Cell.text("-"));
        cells.add(DungeonRunSummaryLayout.Cell.number("rooms", Integer.toString(stats.roomsCleared())));
        cells.add(DungeonRunSummaryLayout.Cell.text(" Rooms | "));
        cells.add(DungeonRunSummaryLayout.Cell.number("deaths", Integer.toString(stats.deaths())));
        cells.add(DungeonRunSummaryLayout.Cell.text(" Deaths" + (stats.bonusMarkers().isEmpty() ? "" : " | " + stats.bonusMarkers())));
        return cells;
    }

    int partySecretsFound(List<DungeonPlayerStats> summaryPlayers) {
        return partySecretsFound(summaryPlayers, bestSecretsAvailable(0));
    }

    private int partySecretsFound(List<DungeonPlayerStats> summaryPlayers, int total) {
        if (serverSecretsFoundObserved) return secretsFound;
        int derived = DungeonSecretCounts.fromPercent(secretsPercent, total);
        if (derived >= 0) return derived;
        if (!summaryPlayers.isEmpty() && summaryPlayers.stream().allMatch(DungeonPlayerStats::hasSecretsFound)) {
            return summaryPlayers.stream().mapToInt(DungeonPlayerStats::secretsFound).sum();
        }
        return -1;
    }

    public int roomSecretsFound(int roomGridX, int roomGridZ) {
        return roomSecretsFound.getOrDefault(new RoomKey(roomGridX, roomGridZ), 0);
    }

    public int matchedRoomSecretsFound(DungeonKnownRoomCatalog.MatchedRoom match) {
        return secretsFoundFor(match);
    }

    public List<DungeonRoomDataSyncClient.LivePlayerReport> livePlayerReports(Minecraft client) {
        UUID selfUuid = client != null && client.player != null ? client.player.getUUID() : null;
        List<DungeonRoomDataSyncClient.LivePlayerReport> reports = new ArrayList<>();
        for (DungeonPlayerStats stats : summaryPlayers(client, selfUuid)) {
            if (!stats.uuid().equals(selfUuid) || !stats.hasSecretsFound()
                || !stats.hasOwnRunSecrets()) {
                continue;
            }
            reports.add(new DungeonRoomDataSyncClient.LivePlayerReport(
                stats.name(),
                Math.max(0, stats.secretsFound()),
                Math.max(0, stats.deaths()),
                "",
                System.currentTimeMillis(),
                stats.secretsSource()
            ));
        }
        return List.copyOf(reports);
    }

    public void mergeRemoteLivePlayers(List<DungeonRoomDataSyncClient.LivePlayerReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        for (DungeonRoomDataSyncClient.LivePlayerReport report : reports) {
            if (report == null || !isKnownTrackedPlayer(report.name()) || report.secretsFound() < 0
                || !report.hasPersonalSecretSource()
                || !report.name().equalsIgnoreCase(report.source()) || report.updatedAtMillis() <= 0) {
                continue;
            }
            UUID uuid = trackedPlayerUuid(report.name());
            if (uuid == null || uuid.equals(selfUuid)) {
                continue;
            }
            DungeonPlayerStats stats = playerStats(uuid, report.name());
            stats.setName(report.name());
            stats.setSyncedSecretsFound(report.secretsFound(), report.updatedAtMillis());
            deathTracker.observePlayerTotal(uuid, report.deaths());
            stats.setDeaths(deathTracker.playerDeaths(uuid));
            deaths = deathTracker.totalDeaths();
        }
    }

    public void observeMapPlayerRoom(int roomGridX, int roomGridZ, long nowTick) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }
        lastRoomPresenceTick.put(new RoomKey(roomGridX, roomGridZ), nowTick);
    }

    public void observeMapPlayerRoom(String name, int roomGridX, int roomGridZ, long nowTick) {
        observeMapPlayerRoom(roomGridX, roomGridZ, nowTick);
        if (name == null || name.isBlank() || !isKnownTrackedPlayer(name)) {
            return;
        }

        UUID uuid = registerDungeonPlayerName(name);
        if (uuid == null) {
            return;
        }

        RoomKey room = new RoomKey(roomGridX, roomGridZ);
        playerRooms.put(uuid, room);
        lastPlayerInRoom.put(room, uuid);
        DungeonPlayerStats stats = playerStats(uuid, name);
        stats.observeRoom(roomGridX, roomGridZ, nowTick);
    }

    public boolean isKnownDungeonPlayer(String name) {
        return name != null && dungeonPlayerNames.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean isKnownPartyPlayer(String name) {
        return HypixelPartyTracker.INSTANCE.isKnownPartyPlayer(name);
    }

    public boolean isKnownTrackedPlayer(String name) {
        return isKnownDungeonPlayer(name);
    }

    public int partyPlayerCount() {
        return HypixelPartyTracker.INSTANCE.partyPlayerCount();
    }

    public int dungeonPlayerCount() {
        return dungeonPlayerNames.size();
    }

    public Set<UUID> knownDungeonPlayerUuids() {
        Set<UUID> uuids = new HashSet<>();
        for (String name : dungeonPlayerNames) {
            UUID uuid = playerNames.get(name);
            if (uuid != null) {
                uuids.add(uuid);
            }
        }
        return Set.copyOf(uuids);
    }

    public Set<UUID> knownTrackedPlayerUuids() {
        return knownDungeonPlayerUuids();
    }

    public List<DungeonPlayerSlot> dungeonPlayerSlots(Minecraft client) {
        List<DungeonPlayerSlot> slots = new ArrayList<>(MAX_DUNGEON_PLAYERS);
        for (int index = 0; index < dungeonPlayerSlots.length; index++) {
            UUID uuid = dungeonPlayerSlots[index];
            if (uuid == null) {
                continue;
            }
            String name = trackedPlayerName(uuid);
            slots.add(new DungeonPlayerSlot(index, uuid, name, dungeonClass(uuid)));
        }
        if (!slots.isEmpty()) {
            return List.copyOf(slots);
        }

        if (client != null && client.player != null) {
            UUID selfUuid = client.player.getUUID();
            slots.add(new DungeonPlayerSlot(0, selfUuid, client.player.getName().getString(), dungeonClass(selfUuid)));
        }
        return List.copyOf(slots);
    }

    public UUID dungeonPlayerUuid(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return playerNames.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public UUID trackedPlayerUuid(String name) {
        if (name == null || name.isBlank() || !isKnownTrackedPlayer(name)) {
            return null;
        }
        UUID dungeonUuid = playerNames.get(name.toLowerCase(java.util.Locale.ROOT));
        if (dungeonUuid != null) {
            return dungeonUuid;
        }
        UUID partyUuid = HypixelPartyTracker.INSTANCE.partyPlayerUuid(name);
        if (partyUuid != null) {
            return partyUuid;
        }
        return null;
    }

    public String trackedPlayerName(UUID uuid) {
        if (uuid == null) {
            return "";
        }
        DungeonPlayerStats stats = players.get(uuid);
        if (stats != null && isPlayerName(stats.name())) {
            return stats.name();
        }
        for (Map.Entry<String, UUID> entry : playerNames.entrySet()) {
            if (entry.getValue().equals(uuid)) {
                return entry.getKey();
            }
        }
        return HypixelPartyTracker.INSTANCE.partyPlayerName(uuid);
    }

    public List<DungeonPlayerStats> dungeonPlayersInOrder() {
        return dungeonPlayerOrder.stream()
            .map(players::get)
            .filter(stats -> stats != null && isKnownDungeonPlayer(stats.name()))
            .toList();
    }

    public void observeMessage(Minecraft client, String rawMessage) {
        observeMessage(client, rawMessage, Long.MIN_VALUE);
    }

    public void observeMessage(Minecraft client, String rawMessage, long nowTick) {
        observeMessage(client, rawMessage, nowTick, DungeonDeathTracker.MessageSource.SYSTEM);
    }

    public void observeMessage(Minecraft client, String rawMessage, long nowTick, DungeonDeathTracker.MessageSource source) {
        if (rawMessage == null || rawMessage.isBlank() || sendingRunSummary) return;
        String message = DungeonLifecycleSignals.clean(rawMessage);
        if (isGeneratedRunSummaryLine(message) || source == DungeonDeathTracker.MessageSource.ACTIONBAR) return;
        // Bonus assists may be social messages, but personal counters never come from quoted chat.
        observeScoreKillMessage(client, message);
        if (source != DungeonDeathTracker.MessageSource.SYSTEM) return;
        observeStatLine(client, message);
        if (isCryptProgressMessage(message)) updateCryptsOpened(client, cryptsOpened + 1);
        var death = deathTracker.observeMessage(message, source, selfUuid, this::trackedPlayerUuid, nowTick);
        if (death != null) {
            DungeonPlayerStats stats = playerStats(death.playerId(), playerName(death.playerId()));
            stats.setDeaths(death.playerDeaths());
            deaths = deathTracker.totalDeaths();
            KungDebugRecorder.event("deaths", "event player=" + stats.name() + " playerDeaths=" + stats.deaths()
                + " total=" + deaths + " unattributed=" + deathTracker.unattributedDeaths());
            announcements.announceDeath(client, KungConfig.get().dungeon, stats, deaths);
        }
        if (message.equals("[BOSS] The Watcher: You have proven yourself. You may pass.")) bloodRoomCompleted = true;
        updateEstimatedScore();
    }

    public void observeEntityDeath(Minecraft client, Entity entity) {
        if (entity == null) {
            return;
        }
        boolean mimic = isMimicEntity(entity);
        if (entity instanceof Zombie zombie && zombie.isBaby()) {
            KungDebugRecorder.event("mimic-kill", "death-packet id=" + entity.getId()
                + " pos=" + entity.blockPosition().toShortString() + " floor=" + floor
                + " mimicCandidate=" + mimic + " alreadyKilled=" + mimicKilled
                + " armor=" + java.util.Arrays.stream(EquipmentSlot.values())
                    .filter(slot -> slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR)
                    .filter(slot -> !zombie.getItemBySlot(slot).isEmpty())
                    .map(slot -> slot.name() + ":" + BuiltInRegistries.ITEM.getKey(zombie.getItemBySlot(slot).getItem()))
                    .toList());
        }
        if (mimic) {
            var damage = ((Zombie) entity).getLastDamageSource();
            if (damage != null && damage.getEntity() instanceof net.minecraft.world.entity.player.Player killer
                && isKnownStatsUuid(killer.getUUID())) {
                recordBonusContributor(killer.getUUID(), DungeonBonusContribution.MIMIC);
            }
            markMimicKilled(client, true, "entity-death");
            updateEstimatedScore();
        }
    }

    public void observeMimicEspKill(Minecraft client) {
        observeMimicEspKill(client, "esp");
    }

    void observeMimicEspKill(Minecraft client, String source) {
        markMimicKilled(client, true, source);
        updateEstimatedScore();
    }

    private void observePlayer(Minecraft client, AbstractClientPlayer player, long nowTick) {
        boolean self = player == client.player || player.getUUID().equals(client.player.getUUID());
        if (!self) {
            if (client.getConnection() == null || client.getConnection().getPlayerInfo(player.getUUID()) == null) {
                return;
            }
            if (!isPlayerName(player.getName().getString())) {
                return;
            }
            if (!isKnownTrackedPlayer(player.getName().getString())) {
                return;
            }
        }

        registerPlayerName(player.getName().getString(), player.getUUID());
        observePlayerRoom(player.getUUID(), player.blockPosition(), nowTick);
    }

    private void observeScoreboard(Minecraft client) {
        List<String> lines = client == null || client.level == null
            ? List.of()
            : HypixelInstanceTracker.INSTANCE.sidebarLines();
        for (String line : lines) {
            observeScoreboardLine(client, line);
        }
        WishAlert.observeSidebar(client, lines, KungConfig.get().dungeon, selfDungeonClass(), wishLowHealth);
    }

    void observeScoreboardLine(Minecraft client, String raw) {
        String line = DungeonLifecycleSignals.clean(raw);
        if (line.isBlank()) return;
        observeStatLine(client, line);
        int totalDeaths = DungeonDeathTracker.teamTotal(line);
        if (totalDeaths >= 0) observeTotalDeaths(totalDeaths);
        Matcher crypts = CRYPTS_TAB_PATTERN.matcher(line);
        if (crypts.matches()) updateCryptsOpened(client, Integer.parseInt(crypts.group(1)));
        Matcher cleared = CLEARED_PATTERN.matcher(line);
        boolean clearedMatched = cleared.matches();
        if (clearedMatched) {
            clearedPercent = Double.parseDouble(cleared.group(1));
            if (cleared.group(2) != null) sidebarScore = Integer.parseInt(cleared.group(2));
        }
        if (line.regionMatches(true, 0, "Cleared:", 0, 8) && !line.equals(lastLoggedClearedLine)) {
            // Proves the live sidebar format in the next trace, matched or not.
            lastLoggedClearedLine = line;
            KungDebugRecorder.event("score-calc", "sidebar-cleared matched=" + clearedMatched + " line=\"" + line + "\"");
        }
        if (line.startsWith("Time: ")) {
            long elapsed = parseElapsedSeconds(line);
            if (elapsed >= 0) elapsedSeconds = elapsed;
        }
    }

    private void observeTabList(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }

        List<UUID> currentSlots = new ArrayList<>(MAX_DUNGEON_PLAYERS);
        if (client.player != null) {
            HypixelPartyTracker.INSTANCE.observeOnlinePlayer(client.player.getName().getString(), client.player.getUUID());
            registerDungeonPlayerName(client, client.player.getName().getString(), client.player.getUUID());
        }
        observeTabOverlayText(client);
        for (PlayerInfo info : sortedTabPlayers(client)) {
            String profileName = info.getProfile().name();
            HypixelPartyTracker.INSTANCE.observeOnlinePlayer(profileName, info.getProfile().id());
            if (isPlayerName(profileName)) {
                registerPlayerName(profileName, info.getProfile().id());
            }
            String line = info.getTabListDisplayName() != null
                ? info.getTabListDisplayName().getString()
                : profileName;
            UUID dungeonPlayerUuid = observeTabLine(client, line, info.getProfile().id());
            if (dungeonPlayerUuid != null && currentSlots.size() < MAX_DUNGEON_PLAYERS) {
                currentSlots.add(dungeonPlayerUuid);
            }
        }
        updateDungeonPlayerSlots(client, currentSlots);
    }

    private static List<PlayerInfo> sortedTabPlayers(Minecraft client) {
        List<PlayerInfo> players = new ArrayList<>(client.getConnection().getOnlinePlayers());
        players.sort(Comparator
            .comparingInt(PlayerInfo::getTabListOrder)
            .thenComparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    UUID observeTabLine(Minecraft client, String line, UUID uuid) {
        line = DungeonLifecycleSignals.clean(line);
        int partySecrets = DungeonSecretCounts.partyFound(line);
        if (partySecrets >= 0) {
            observeSecretsTotal(client, partySecrets);
            return null;
        }
        int teamDeaths = DungeonDeathTracker.teamTotal(line);
        if (teamDeaths >= 0) {
            observeTotalDeaths(teamDeaths);
            return null;
        }
        double partyPercent = DungeonSecretCounts.percent(line);
        if (partyPercent >= 0) {
            observeSecretsPercent(partyPercent);
            return null;
        }

        Matcher completedMatcher = COMPLETED_ROOMS_PATTERN.matcher(line);
        if (completedMatcher.matches()) {
            completedRooms = Integer.parseInt(completedMatcher.group(1));
            if (completedMatcher.group(2) != null) {
                totalRoomsFromTab = Integer.parseInt(completedMatcher.group(2));
            }
            return null;
        }

        Matcher openedMatcher = OPENED_ROOMS_PATTERN.matcher(line);
        if (openedMatcher.matches()) {
            openedRooms = Integer.parseInt(openedMatcher.group(1));
            return null;
        }

        if (puzzles.observe(line)) return null;

        Matcher cryptsMatcher = CRYPTS_TAB_PATTERN.matcher(line);
        if (cryptsMatcher.matches()) {
            updateCryptsOpened(client, Integer.parseInt(cryptsMatcher.group(1)));
            return null;
        }

        Matcher playerMatcher = PLAYER_TAB_PATTERN.matcher(line);
        if (playerMatcher.matches()) {
            UUID playerUuid = registerDungeonPlayerName(client, playerMatcher.group("name"), uuid);
            observePlayerStatLine(line, playerUuid);
            return playerUuid;
        }

        observeStatLine(client, line);
        return null;
    }

    private void updateDungeonPlayerSlots(Minecraft client, List<UUID> currentSlots) {
        Arrays.fill(dungeonPlayerSlots, null);
        int targetIndex = 0;
        Set<UUID> seen = new HashSet<>();
        if (client != null && client.player != null) {
            UUID selfUuid = client.player.getUUID();
            currentSlots.removeIf(selfUuid::equals);
            dungeonPlayerSlots[targetIndex++] = selfUuid;
            seen.add(selfUuid);
        }
        for (UUID uuid : currentSlots) {
            if (uuid == null || targetIndex >= dungeonPlayerSlots.length) {
                break;
            }
            if (!seen.add(uuid)) {
                continue;
            }
            dungeonPlayerSlots[targetIndex++] = uuid;
        }
        logDungeonPlayerSlots(currentSlots);
    }

    private void observeTabOverlayText(Minecraft client) {
        PlayerTabOverlay tabList = McCompat.tabList(client);
        observeComponentLines(client, readTabComponent(tabList, true));
        observeComponentLines(client, readTabComponent(tabList, false));
    }

    private void observeComponentLines(Minecraft client, Component component) {
        if (component == null) {
            return;
        }

        for (String line : component.getString().split("\\R")) {
            if (!line.isBlank()) {
                observeTabLine(client, line.replaceAll("\\s+", " ").trim(), null);
            }
        }
    }

    private static Component readTabComponent(PlayerTabOverlay tabList, boolean header) {
        try {
            Field field = header ? tabHeaderField : tabFooterField;
            if (field == null) {
                field = PlayerTabOverlay.class.getDeclaredField(header ? "header" : "footer");
                field.setAccessible(true);
                if (header) {
                    tabHeaderField = field;
                } else {
                    tabFooterField = field;
                }
            }
            Object value = field.get(tabList);
            return value instanceof Component component ? component : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    void observeStatLine(Minecraft client, String raw) {
        String line = DungeonLifecycleSignals.clean(raw);
        observeFloorMetadata(line);
        observeServerScoreLine(line);
        var fraction = DungeonSecretCounts.party(line);
        if (fraction != null) {
            observeSecretsTotal(client, fraction.found());
            if (fraction.total() >= 0) observeServerSecretsAvailable(fraction.total());
        }
    }

    private void observeServerScoreLine(String line) {
        Matcher teamScoreMatcher = TEAM_SCORE_TEXT_PATTERN.matcher(line);
        if (teamScoreMatcher.matches()) {
            serverScore = Integer.parseInt(teamScoreMatcher.group(1));
            serverScoreSource = "team";
            return;
        }

        Matcher scoreMatcher = SCORE_TEXT_PATTERN.matcher(line);
        if (scoreMatcher.matches() && !serverScoreSource.equals("team")) {
            serverScore = Integer.parseInt(scoreMatcher.group(1));
            serverScoreSource = "score";
        }
    }

    private void observeSecretsPercent(double observedPercent) {
        if (observedPercent < 0.0) {
            return;
        }
        secretsPercent = Math.max(secretsPercent, observedPercent);
        updateDerivedSecretsTotalFromPercent();
    }

    private void updateDerivedSecretsTotalFromPercent() {
        if (!serverSecretsFoundObserved || secretsFound <= 0 || secretsPercent <= 0.0) {
            return;
        }

        int candidate = (int) Math.floor(secretsFound * 100.0 / secretsPercent + 0.5);
        if (candidate < secretsFound || candidate <= 0 || candidate > 250) {
            return;
        }

        double reconstructedPercent = 100.0 * secretsFound / candidate;
        if (Math.abs(reconstructedPercent - secretsPercent) > 0.15) {
            return;
        }

        derivedSecretsTotalHistogram.merge(candidate, 1, Integer::sum);
        derivedSecretsTotalAvailable = derivedSecretsTotalHistogram.entrySet().stream()
            .max(Comparator
                .comparingInt((Map.Entry<Integer, Integer> entry) -> entry.getValue())
                .thenComparingInt(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(derivedSecretsTotalAvailable);
    }

    private void observeServerSecretsAvailable(int observedAvailable) {
        if (observedAvailable < 0) {
            return;
        }
        secretsAvailable = observedAvailable;
        if (secretsTotalAvailable > 0 && secretsTotalAvailable != observedAvailable) {
            secretsTotalAvailable = -1;
        }
        updateDerivedSecretsTotalFromPercent();
    }

    private void observeSecretsTotal(Minecraft client, int observedTotal) {
        if (observedTotal < 0) return;
        // A server snapshot replaces a previous observation; it never credits the current room/player.
        serverSecretsFoundObserved = true;
        secretsFound = observedTotal;
        updateDerivedSecretsTotalFromPercent();
    }

    private void recordMatchedRoomSecretCount(
        Minecraft client,
        DungeonKnownRoomCatalog.MatchedRoom match,
        int found,
        int fallbackMax,
        boolean debugSecret
    ) {
        int max = match.template().secrets() > 0 ? match.template().secrets() : fallbackMax;
        int next = Math.clamp(found, 0, Math.max(0, max));
        int previous = secretsFoundFor(match);
        RoomKey targetRoom = firstComponentRoom(match);
        if (targetRoom == null || (next < previous && !debugSecret)) {
            return;
        }

        for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
            roomSecretsFound.remove(new RoomKey(component.roomGridX(), component.roomGridZ()));
        }
        roomSecretsFound.put(targetRoom, next);

        if (debugSecret && next != previous) {
            debug(client, "Secret in " + match.template().name() + ": " + next + "/" + max);
        }
    }

    private int secretsFoundFor(DungeonKnownRoomCatalog.MatchedRoom match) {
        int total = 0;
        for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
            total += roomSecretsFound.getOrDefault(new RoomKey(component.roomGridX(), component.roomGridZ()), 0);
        }
        return Math.min(total, Math.max(0, match.template().secrets()));
    }

    private static RoomKey firstComponentRoom(DungeonKnownRoomCatalog.MatchedRoom match) {
        if (match.components().isEmpty()) {
            return null;
        }

        DungeonKnownRoomCatalog.MatchedComponent component = match.components().get(0);
        return new RoomKey(component.roomGridX(), component.roomGridZ());
    }

    private static DungeonKnownRoomCatalog.MatchedRoom matchedRoomAt(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        RoomKey room
    ) {
        if (renderPlan == null) {
            return null;
        }

        return renderPlan.matchContaining(room.roomGridX(), room.roomGridZ());
    }

    private static RoomKey currentPlayerRoom(Minecraft client) {
        if (client == null || client.player == null) {
            return null;
        }

        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        if (!DungeonScanUtils.isValidRoomGrid(grid.gridX(), grid.gridZ())) {
            return null;
        }
        return new RoomKey(grid.gridX(), grid.gridZ());
    }

    private static void sendRoomDebugOnce(
        Minecraft client,
        Set<String> seenRooms,
        String roomKey,
        String message
    ) {
        if (seenRooms.add(roomKey)) {
            debug(client, message);
        }
    }

    private static String roomNameFor(RoomKey room, DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        DungeonKnownRoomCatalog.MatchedRoom match = matchedRoomAt(renderPlan, room);
        if (match != null) {
            return match.template().name();
        }
        return "room " + room.roomGridX() + "," + room.roomGridZ();
    }

    private static void debug(Minecraft client, String message) {
        if (KungConfig.get().debug.dungeonMessagesEnabled() && client != null && client.player != null) {
            client.player.sendSystemMessage(KungMessages.debug("Dungeon", message));
        }
    }

    private void observePlayerStatLine(String line, UUID playerUuid) {
        // The class comes from the same identified party row. Nearby rows are not this player's stats.
        if (playerUuid == null) return;
        DungeonClass dungeonClass = dungeonClassFromLine(line);
        if (dungeonClass != DungeonClass.UNKNOWN) setDungeonClass(playerUuid, dungeonClass);
    }

    private static DungeonClass dungeonClassFromLine(String line) {
        Matcher matcher = PLAYER_CLASS_PATTERN.matcher(line);
        if (!matcher.find()) {
            return DungeonClass.UNKNOWN;
        }
        return switch (matcher.group(1).toLowerCase(java.util.Locale.ROOT)) {
            case "archer" -> DungeonClass.ARCHER;
            case "berserk", "berserker" -> DungeonClass.BERSERKER;
            case "mage" -> DungeonClass.MAGE;
            case "healer" -> DungeonClass.HEALER;
            case "tank" -> DungeonClass.TANK;
            default -> DungeonClass.UNKNOWN;
        };
    }



    private UUID registerDungeonPlayerName(String name) {
        return registerDungeonPlayerName(null, name, null);
    }

    private boolean admitDungeonPlayerName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (dungeonPlayerNames.contains(key)) return true;
        if (dungeonPlayerNames.size() >= MAX_DUNGEON_PLAYERS) {
            KungDebugRecorder.event("run-statistics", "roster ignored extra class row player=" + name);
            return false;
        }
        dungeonPlayerNames.add(key);
        return true;
    }

    private UUID registerDungeonPlayerName(Minecraft client, String name, UUID uuid) {
        if (!isPlayerName(name) || !admitDungeonPlayerName(name)) {
            return null;
        }

        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        UUID loadedUuid = loadedPlayerUuidByName(client, name);
        UUID observedPartyUuid = HypixelPartyTracker.INSTANCE.observedOnlinePlayerUuid(name);
        UUID partyUuid = HypixelPartyTracker.INSTANCE.partyPlayerUuid(name);
        UUID rememberedUuid = playerNames.get(lowerName);
        UUID playerUuid = loadedUuid;
        String source = "loaded-name";
        if (playerUuid == null) {
            playerUuid = observedPartyUuid;
            source = "party-observed";
        }
        if (playerUuid == null) {
            playerUuid = rememberedUuid;
            source = "remembered";
        }
        if (playerUuid == null) {
            playerUuid = partyUuid;
            source = "party";
        }
        if (playerUuid == null) {
            playerUuid = uuid;
            source = "tab";
        }
        if (playerUuid == null) {
            playerUuid = syntheticUuid(name);
            source = "synthetic";
        }
        if (uuid != null && !uuid.equals(playerUuid)) {
            KungDebugRecorder.event("player-slots", "name=" + name
                + " source=" + source
                + " uuid=" + shortUuid(playerUuid)
                + " tabUuid=" + shortUuid(uuid));
        }
        registerPlayerName(name, playerUuid);
        rememberDungeonPlayerOrder(playerUuid);
        requestTotalSecrets(client, name);
        requestRunSecretBaseline(client, name);
        return playerUuid;
    }

    private UUID loadedPlayerUuidByName(Minecraft client, String name) {
        if (client == null || client.level == null || client.getConnection() == null || !isPlayerName(name)) {
            return null;
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (player == null || player.isInvisible() || !name.equalsIgnoreCase(player.getName().getString())) {
                continue;
            }
            if (client.getConnection().getPlayerInfo(player.getUUID()) != null) {
                return player.getUUID();
            }
        }
        return null;
    }

    private void logDungeonPlayerSlots(List<UUID> tabSlots) {
        String state = "tab=" + describeUuids(tabSlots) + " slots=" + describeDungeonSlots();
        if (!state.equals(lastLoggedDungeonSlotState)) {
            lastLoggedDungeonSlotState = state;
            KungDebugRecorder.event("player-slots", state);
        }
    }

    private String describeDungeonSlots() {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < dungeonPlayerSlots.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            UUID uuid = dungeonPlayerSlots[index];
            builder.append(index).append(':');
            if (uuid == null) {
                builder.append("<empty>");
            } else {
                builder.append(trackedPlayerName(uuid))
                    .append(':')
                    .append(shortUuid(uuid))
                    .append(':')
                    .append(dungeonClass(uuid));
            }
        }
        return builder.append(']').toString();
    }

    private String describeUuids(List<UUID> uuids) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < uuids.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            UUID uuid = uuids.get(index);
            builder.append(trackedPlayerName(uuid)).append(':').append(shortUuid(uuid));
        }
        return builder.append(']').toString();
    }

    private void registerPlayerName(String name, UUID uuid) {
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        UUID previousUuid = playerNames.put(lowerName, uuid);
        DungeonPlayerStats stats = playerStats(uuid, name);
        stats.setName(name);
        DungeonClass rememberedClass = playerClasses.get(lowerName);
        if (rememberedClass != null && rememberedClass != DungeonClass.UNKNOWN && stats.dungeonClass() == DungeonClass.UNKNOWN) {
            stats.setDungeonClass(rememberedClass);
        }
        if (previousUuid != null && !previousUuid.equals(uuid)) {
            roomClearAttribution.remapPlayer(previousUuid, uuid);
            deathTracker.remapPlayer(previousUuid, uuid);
            deaths = deathTracker.totalDeaths();
            DungeonPlayerStats previousStats = players.remove(previousUuid);
            if (previousStats != null) {
                stats.merge(previousStats);
            }
            updateRoomClearBounds();
            replaceDungeonPlayerOrder(previousUuid, uuid);
            RoomKey previousRoom = playerRooms.remove(previousUuid);
            if (previousRoom != null) {
                playerRooms.put(uuid, previousRoom);
                lastPlayerInRoom.put(previousRoom, uuid);
            }
        }
    }

    private void setDungeonClass(UUID uuid, DungeonClass dungeonClass) {
        if (uuid == null || dungeonClass == null || dungeonClass == DungeonClass.UNKNOWN) {
            return;
        }
        DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
        stats.setDungeonClass(dungeonClass);
        if (isPlayerName(stats.name())) {
            playerClasses.put(stats.name().toLowerCase(java.util.Locale.ROOT), dungeonClass);
        }
    }

    private void requestTotalSecrets(Minecraft client, String name) {
        if (client == null
            || client.player == null
            || !HypixelSkyBlockProfileClient.secretsSourceAvailable()
            || !isPlayerName(name)) {
            return;
        }
        String requestedName = name.toLowerCase(java.util.Locale.ROOT);
        apiEnrichment.requestTotal(client, name, result -> {
            UUID uuid = playerNames.get(requestedName);
            if (uuid == null && isPlayerName(result.name())) {
                uuid = playerNames.get(result.name().toLowerCase(java.util.Locale.ROOT));
            }
            if (uuid == null) {
                return;
            }
            DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
            stats.setTotalSecretsFound(result.secretsFound());
            KungDebugRecorder.event("player-stats", "total-secrets player=" + stats.name()
                + " total=" + result.secretsFound());
        });
    }

    private void requestRunSecretBaseline(Minecraft client, String name) {
        if (client == null
            || client.player == null
            || !HypixelSkyBlockProfileClient.secretsSourceAvailable()
            || !isPlayerName(name)) {
            return;
        }
        String requestedName = name.toLowerCase(java.util.Locale.ROOT);
        apiEnrichment.requestBaseline(client, name, result -> {
            String resultName = isPlayerName(result.name()) ? result.name() : name;
            String key = resultName.toLowerCase(java.util.Locale.ROOT);
            UUID uuid = playerNames.get(key);
            if (uuid == null) {
                uuid = playerNames.get(requestedName);
            }
            if (uuid == null) {
                return;
            }
            DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
            stats.setTotalSecretsFound(result.secretsFound());
            stats.setRunSecretBaseline(result.secretsFound());
            KungDebugRecorder.event("player-stats", "run-secret baseline player=" + stats.name()
                + " total=" + result.secretsFound());
        });
    }

    private void requestRunSecretFinal(Minecraft client, String name) {
        if (client == null
            || client.player == null
            || !HypixelSkyBlockProfileClient.secretsSourceAvailable()
            || !isPlayerName(name)) {
            return;
        }
        String requestedName = name.toLowerCase(java.util.Locale.ROOT);
        apiEnrichment.requestFinal(client, name, (result, delta) -> {
                String resultName = isPlayerName(result.name()) ? result.name() : name;
                String key = resultName.toLowerCase(java.util.Locale.ROOT);
                UUID uuid = playerNames.get(key);
                if (uuid == null) {
                    uuid = playerNames.get(requestedName);
                }
                if (uuid == null) {
                    return;
                }
                DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
                stats.setTotalSecretsFound(result.secretsFound());
                if (delta >= 0) {
                    stats.setApiRunSecretsFound(delta);
                    KungDebugRecorder.event("player-stats", "run-secret delta player=" + stats.name()
                        + " final=" + result.secretsFound()
                        + " delta=" + delta);
                }
        });
    }

    private void rememberDungeonPlayerOrder(UUID uuid) {
        if (uuid != null && !dungeonPlayerOrder.contains(uuid)) {
            dungeonPlayerOrder.add(uuid);
        }
    }

    private void replaceDungeonPlayerOrder(UUID previousUuid, UUID uuid) {
        for (int index = 0; index < dungeonPlayerSlots.length; index++) {
            if (previousUuid.equals(dungeonPlayerSlots[index])) dungeonPlayerSlots[index] = uuid;
        }
        for (int index = 0; index < dungeonPlayerOrder.size(); index++) {
            if (dungeonPlayerOrder.get(index).equals(previousUuid)) {
                dungeonPlayerOrder.set(index, uuid);
            }
        }
        for (int index = dungeonPlayerOrder.size() - 1; index >= 0; index--) {
            if (dungeonPlayerOrder.get(index).equals(uuid) && dungeonPlayerOrder.indexOf(uuid) != index) {
                dungeonPlayerOrder.remove(index);
            }
        }
    }

    private void observePlayerRoom(UUID uuid, net.minecraft.core.BlockPos position, long nowTick) {
        DungeonScanUtils.GridPosition grid = DungeonScanUtils.getRoomGridPosition(position);
        if (!DungeonScanUtils.isValidRoomGrid(grid.gridX(), grid.gridZ())) {
            return;
        }

        RoomKey room = new RoomKey(grid.gridX(), grid.gridZ());
        playerRooms.put(uuid, room);
        lastPlayerInRoom.put(room, uuid);
        DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
        stats.observeRoom(grid.gridX(), grid.gridZ(), nowTick);
    }

    private Set<UUID> playersForClearedRoom(List<DungeonKnownRoomCatalog.MatchedComponent> components) {
        Set<RoomKey> rooms = new HashSet<>();
        for (DungeonKnownRoomCatalog.MatchedComponent component : components) {
            rooms.add(new RoomKey(component.roomGridX(), component.roomGridZ()));
        }

        Set<UUID> playerUuids = new HashSet<>();
        for (Map.Entry<UUID, RoomKey> entry : playerRooms.entrySet()) {
            if (!rooms.contains(entry.getValue())) {
                continue;
            }
            DungeonPlayerStats stats = players.get(entry.getKey());
            if (stats == null
                || currentObserveTick > 0L
                    && stats.lastSeenTick() > 0L
                    && currentObserveTick - stats.lastSeenTick() > ROOM_CLEAR_PLAYER_STALE_TICKS) {
                continue;
            }
            playerUuids.add(entry.getKey());
        }
        return playerUuids;
    }

    private UUID lastPlayerForClearedRoom(List<DungeonKnownRoomCatalog.MatchedComponent> components) {
        for (DungeonKnownRoomCatalog.MatchedComponent component : components) {
            UUID uuid = lastPlayerInRoom.get(new RoomKey(component.roomGridX(), component.roomGridZ()));
            if (uuid != null) {
                return uuid;
            }
        }
        return null;
    }

    private Set<UUID> playersForClearedCell(RoomKey room) {
        Set<UUID> playerUuids = new HashSet<>();
        for (Map.Entry<UUID, RoomKey> entry : playerRooms.entrySet()) {
            if (!room.equals(entry.getValue())) {
                continue;
            }
            DungeonPlayerStats stats = players.get(entry.getKey());
            if (stats == null
                || currentObserveTick > 0L
                    && stats.lastSeenTick() > 0L
                    && currentObserveTick - stats.lastSeenTick() > ROOM_CLEAR_PLAYER_STALE_TICKS) {
                continue;
            }
            playerUuids.add(entry.getKey());
        }
        return playerUuids;
    }

    private void recordClearedRoom(Set<Integer> cells, Set<UUID> playerUuids, UUID fallbackPlayerUuid) {
        Set<UUID> knownPlayers = new HashSet<>();
        for (UUID playerUuid : playerUuids) {
            if (isKnownStatsUuid(playerUuid)) {
                knownPlayers.add(playerUuid);
            }
        }
        UUID fallback = isKnownStatsUuid(fallbackPlayerUuid) ? fallbackPlayerUuid : null;
        if (roomClearAttribution.observe(cells, knownPlayers, fallback)) updateRoomClearBounds();
    }

    private void updateRoomClearBounds() {
        Map<UUID, DungeonRoomClearAttribution.Bounds> bounds = roomClearAttribution.bounds();
        for (DungeonPlayerStats stats : players.values()) {
            var value = bounds.getOrDefault(stats.uuid(), new DungeonRoomClearAttribution.Bounds(0, 0));
            stats.setRoomClearBounds(value.minimum(), value.maximum());
        }
    }

    private static int clearCell(int x, int z) { return z * 6 + x; }

    private static boolean isClearableRoom(RoomType type) {
        return DungeonRoomProgress.isClearable(type);
    }

    private static String roomKeyFor(DungeonKnownRoomCatalog.MatchedRoom match) {
        DungeonKnownRoomCatalog.RoomBounds bounds = match.bounds();
        return "match:" + bounds.minX() + "," + bounds.minZ() + ":" + bounds.maxX() + "," + bounds.maxZ()
            + ":" + match.components().size();
    }

    private DungeonPlayerStats playerStats(UUID uuid, String fallbackName) {
        return players.computeIfAbsent(uuid, ignored -> new DungeonPlayerStats(uuid, fallbackName));
    }

    private boolean isKnownStatsUuid(UUID uuid) {
        DungeonPlayerStats stats = players.get(uuid);
        return stats != null && isPlayerName(stats.name()) && isKnownTrackedPlayer(stats.name());
    }

    private String playerName(UUID uuid) {
        DungeonPlayerStats stats = players.get(uuid);
        return stats == null ? "Unknown" : stats.name();
    }

    private static UUID syntheticUuid(String name) {
        return UUID.nameUUIDFromBytes(("kung:dungeon-player:" + name.toLowerCase(java.util.Locale.ROOT))
            .getBytes(StandardCharsets.UTF_8));
    }

    private static String shortUuid(UUID uuid) {
        return uuid == null ? "null" : uuid.toString().substring(0, 8);
    }

    private static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches() && !isIgnoredNpcName(name);
    }

    private static boolean isIgnoredNpcName(String name) {
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        return lowerName.equals("mort")
            || lowerName.equals("tomioka")
            || lowerName.equals("duncan")
            || lowerName.equals("zodd")
            || lowerName.equals("trinity");
    }

    private void updateEstimatedScore() {
        estimatedScore = estimatedScore(null, bestSecretsAvailable(0));
    }

    private int estimatedScore(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int estimatedSecretsAvailable) {
        return skillScore(renderPlan)
            + exploreScore(renderPlan, estimatedSecretsAvailable)
            + speedScore()
            + bonusScore()
            + paulScoreBonus();
    }

    private int skillScore(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int completedRoomScore = roomProgressScore(renderPlan, 80.0);
        return DungeonScoreCalculator.skillScore(completedRoomScore, deaths, unfinishedPuzzles(renderPlan));
    }

    private int projectedSPlusSkillScore() {
        return DungeonScoreCalculator.projectedSkillScore(deaths, puzzles.failed());
    }

    private int deathPenalty() {
        return DungeonScoreCalculator.deathPenalty(deaths);
    }

    private int exploreScore(DungeonLiveMapWriter.MatchRenderPlan renderPlan, int estimatedSecretsAvailable) {
        return Math.clamp(exploreRoomScore(renderPlan) + secretScore(estimatedSecretsAvailable), 0, 100);
    }

    private int exploreRoomScore(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        return roomProgressScore(renderPlan, 60.0);
    }

    private int roomProgressScore(DungeonLiveMapWriter.MatchRenderPlan renderPlan, double maxScore) {
        int scoreRoomCells = scoreRoomCellTotal(renderPlan);
        int projectedCompletedCells = projectedCompletedRoomCells(renderPlan);
        if (scoreRoomCells > 0) {
            return Math.clamp((int) Math.floor(maxScore * projectedCompletedCells / scoreRoomCells), 0, (int) maxScore);
        }
        return clearedPercent >= 0.0
            ? Math.clamp((int) Math.floor(maxScore * clearedPercent / 100.0), 0, (int) maxScore) : 0;
    }

    private int secretScore(int estimatedSecretsAvailable) {
        int totalSecrets = bestSecretsAvailable(estimatedSecretsAvailable);
        double required = requiredSecretsPercent();
        if (secretsPercent >= 0.0) {
            return DungeonScoreCalculator.secretScoreFromPercent(secretsPercent, required);
        }
        if (totalSecrets > 0) {
            return DungeonScoreCalculator.secretScoreFromCount(
                displayedSecretsFound(totalSecrets),
                totalSecrets,
                required
            );
        }
        return 0;
    }

    private int bonusScore() {
        return DungeonScoreCalculator.bonusScore(
            cryptsOpened,
            mimicKilled,
            hasMimic() && secretsPercent >= 100.0,
            princeKilled,
            batScoreKilled
        );
    }

    private int paulScoreBonus() {
        return paulScoreBonusActive() ? 10 : 0;
    }

    private boolean paulScoreBonusActive() {
        return KungConfig.get().dungeon.forcePaulScoreEnabled()
            || SkyBlockMayorTracker.INSTANCE.paulScoreBonusActive();
    }

    private int totalRoomsEstimate(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int mapObservedRooms = observedRoomCount(renderPlan);
        if (mapObservedRooms > 0) {
            return Math.max(mapObservedRooms, Math.max(mapVisitedRooms(renderPlan), mapCompletedRooms(renderPlan)));
        }

        int minimumObservedRooms = Math.max(openedRooms, completedRooms);
        if (totalRoomsFromTab > 0) {
            return Math.max(totalRoomsFromTab, minimumObservedRooms);
        }
        int completed = completedRooms;
        if (completed <= 0 || clearedPercent <= 0.0) {
            return minimumObservedRooms;
        }
        int derivedTotal = (int) Math.floor(completed / (clearedPercent / 100.0) + 0.4);
        return Math.max(derivedTotal, minimumObservedRooms);
    }

    String roomProgressSummary(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        var progress = DungeonRoomProgress.from(renderPlan);
        return "Rooms cleared: " + progress.cleared() + "/" + unknownPositive(progress.total())
            + " | Opened " + progress.opened();
    }

    private int bestCompletedRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (renderPlan == null) {
            return completedRooms;
        }
        return Math.max(mapCompletedRooms(renderPlan), tabCompletedRoomOwners(renderPlan));
    }

    private int bestOpenedRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int mapObservedRooms = observedRoomCount(renderPlan);
        if (mapObservedRooms > 0) {
            return Math.min(
                Math.max(mapVisitedRooms(renderPlan), bestCompletedRooms(renderPlan)),
                mapObservedRooms
            );
        }
        return Math.max(openedRooms, completedRooms);
    }

    private static String unknownDash(int value) {
        return value >= 0 ? Integer.toString(value) : "?";
    }

    private static String unknownPositive(int value) {
        return value > 0 ? Integer.toString(value) : "?";
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }



    private int scoreCompletedRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int completed = bestCompletedRooms(renderPlan);
        int totalRooms = totalRoomsEstimate(renderPlan);
        return totalRooms > 0 ? Math.min(completed, totalRooms) : completed;
    }

    private int projectedCompletedRoomCells(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int totalCells = scoreRoomCellTotal(renderPlan);
        if (totalCells <= 0) {
            return 0;
        }

        int completedCells = Math.max(completedRooms, scoreMapProgress(renderPlan).clearedCells());
        if (completedCells <= 0) {
            return 0;
        }

        int projected = completedCells + (floor > 0 ? 1 : 0);
        if (!bloodRoomCompleted) {
            projected++;
        }
        // Blood/boss credit cannot pay for an unfinished clear room elsewhere on the map, but the map
        // can never take back rooms the tab already counted as completed (completedRooms <= totalCells).
        int cappedTotal = Math.max(0, totalCells - unfinishedClearRoomCells(renderPlan));
        return Math.clamp(projected, 0, Math.max(cappedTotal, completedRooms));
    }

    private static boolean scoreCellCleared(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonLiveMapWriter.CellKey cell) {
        // The map reader also represents a failed puzzle's red cross as CLEARED.
        return plan.completedRooms().contains(cell)
            || (plan.roomTypes().getOrDefault(cell, RoomType.UNKNOWN) != RoomType.PUZZLE
                && plan.clearedRooms().contains(cell));
    }

    private int unfinishedClearRoomCells(DungeonLiveMapWriter.MatchRenderPlan plan) {
        var progress = scoreMapProgress(plan);
        // If every puzzle is solved, tab can confirm stale map cells. Partial counts cannot
        // identify which still-open map puzzle was solved and must not clear the wrong room.
        int tabAhead = unfinishedPuzzles(plan) == 0 ? Math.max(0, puzzles.completed() - progress.completedPuzzles()) : 0;
        return Math.max(0, progress.unfinishedClearCells() - Math.min(progress.unfinishedPuzzleCells(), tabAhead));
    }

    /** The run's puzzle names as the tab lists them, lower case. */
    java.util.Set<String> puzzleNames() {
        return puzzles.names();
    }

    private int unfinishedPuzzles(DungeonLiveMapWriter.MatchRenderPlan plan) {
        var progress = scoreMapProgress(plan);
        return puzzles.unfinished(progress.puzzleRooms(), progress.completedPuzzles());
    }

    private int unfinishedPuzzleRoomCells(DungeonLiveMapWriter.MatchRenderPlan plan) {
        return scoreMapProgress(plan).unfinishedPuzzleCells();
    }

    private ScoreMapProgress scoreMapProgress(DungeonLiveMapWriter.MatchRenderPlan plan) {
        if (plan == cachedScorePlan) return cachedScoreMapProgress;
        int cleared = 0;
        int unfinished = 0;
        int unfinishedPuzzleCells = 0;
        Set<DungeonLiveMapWriter.CellKey> puzzleCells = new HashSet<>();
        Set<DungeonLiveMapWriter.CellKey> completedPuzzleCells = new HashSet<>();
        if (plan != null) {
            for (var cell : plan.roomOwners().keySet()) {
                if (scoreCellCleared(plan, cell)) cleared++;
                else if (DungeonRoomProgress.isClearable(plan.roomTypes().getOrDefault(cell, RoomType.UNKNOWN))) unfinished++;
            }
            for (var entry : plan.roomTypes().entrySet()) {
                if (entry.getValue() != RoomType.PUZZLE) continue;
                puzzleCells.add(entry.getKey());
                if (plan.completedRooms().contains(entry.getKey())) completedPuzzleCells.add(entry.getKey());
                else unfinishedPuzzleCells++;
            }
        }
        // The footer asks for both skill and exploration repeatedly; recount only on a new plan.
        cachedScorePlan = plan;
        cachedScoreMapProgress = new ScoreMapProgress(cleared, unfinished, unfinishedPuzzleCells,
            plan == null ? 0 : plan.ownerCount(puzzleCells), plan == null ? 0 : plan.ownerCount(completedPuzzleCells));
        return cachedScoreMapProgress;
    }

    private record ScoreMapProgress(int clearedCells, int unfinishedClearCells, int unfinishedPuzzleCells,
                                    int puzzleRooms, int completedPuzzles) { }

    private int scoreRoomCellTotal(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (totalRoomsFromTab > 0) {
            return Math.max(totalRoomsFromTab, Math.max(openedRooms, completedRooms));
        }
        // Cleared is rounded by Hypixel. Derive its denominator instead of treating the
        // displayed percentage as completed room points or assuming the partial map is complete.
        if (completedRooms > 0 && clearedPercent > 0.0) {
            int derived = (int) Math.floor(completedRooms * 100.0 / clearedPercent + 0.4);
            return Math.max(derived, Math.max(openedRooms, completedRooms));
        }
        int mapCells = roomCellCount(renderPlan);
        return mapCells > 0 ? Math.max(mapCells, Math.max(openedRooms, completedRooms)) : 0;
    }

    private int mapCompletedRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        return mapRoomCount(renderPlan, renderPlan == null ? null : renderPlan.completedRooms());
    }

    private int mapVisitedRooms(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        return mapRoomCount(renderPlan, renderPlan == null ? null : renderPlan.visitedRooms());
    }

    private int tabCompletedRoomOwners(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        int mapObservedRooms = observedRoomCount(renderPlan);
        int mapRoomCells = roomCellCount(renderPlan);
        if (completedRooms <= 0 || mapObservedRooms <= 0 || mapRoomCells <= 0) {
            return 0;
        }
        if (completedRooms >= mapRoomCells) {
            return mapObservedRooms;
        }
        if (mapRoomCells >= 24 && completedRooms >= mapRoomCells - 1) {
            return Math.max(0, mapObservedRooms - 1);
        }
        return 0;
    }

    private double requiredSecretsPercent() {
        return DungeonScoreCalculator.requiredSecretsPercent(floor, masterMode);
    }

    private int speedScore() {
        long seconds = elapsedSeconds >= 0L
            ? elapsedSeconds
            : runStartTick > 0L ? ticksToSeconds(0L) : 0L;
        return DungeonScoreCalculator.speedScore(seconds, speedGraceSeconds());
    }

    private long speedGraceSeconds() {
        return DungeonScoreCalculator.speedGraceSeconds(floor, masterMode);
    }

    boolean hasMimic() {
        return floor == 6 || floor == 7;
    }

    public int bestSecretsAvailable(int estimatedSecretsAvailable) {
        int observedServerTotal = Math.max(secretsTotalAvailable, secretsAvailable);
        int observedTabTotal = Math.max(observedServerTotal, derivedSecretsTotalAvailable);
        if (observedTabTotal > 0) {
            return observedTabTotal;
        }
        return Math.max(0, estimatedSecretsAvailable);
    }

    private String secretsTotalSource(int totalSecrets, int estimatedSecretsAvailable) {
        if (totalSecrets > 0 && totalSecrets == Math.max(secretsTotalAvailable, secretsAvailable)) {
            return secretsTotalAvailable > 0 ? "server-total" : "server-fraction";
        }
        if (totalSecrets > 0 && totalSecrets == derivedSecretsTotalAvailable) {
            return "derived-percent";
        }
        if (estimatedSecretsAvailable > 0) {
            return "room-estimate";
        }
        return "unknown";
    }

    public int displayedSecretsFound(int total) {
        if (serverSecretsFoundObserved) return secretsFound;
        return DungeonSecretCounts.fromPercent(secretsPercent, total);
    }

    int catalogSecretsAvailable(DungeonLiveMapWriter.MatchRenderPlan plan) {
        if (plan != cachedSecretPlan) {
            cachedSecretPlan = plan;
            cachedCatalogSecretTotal = estimatedSecretTotal(plan);
        }
        return cachedCatalogSecretTotal;
    }

    static int estimatedSecretTotal(DungeonLiveMapWriter.MatchRenderPlan plan) {
        if (plan == null || plan.roomOwners().isEmpty()) return -1;
        Map<String, Integer> totals = new HashMap<>();
        for (var match : plan.matches()) {
            for (var component : match.components()) {
                String owner = plan.roomOwners().get(new DungeonLiveMapWriter.CellKey(component.roomGridX(), component.roomGridZ()));
                if (owner != null) totals.putIfAbsent(owner, match.template().secrets());
            }
        }
        plan.hints().forEach((cell, hint) -> {
            String owner = plan.roomOwners().get(cell);
            if (owner != null) totals.putIfAbsent(owner, hint.secrets());
        });
        for (var entry : plan.roomOwners().entrySet()) {
            if (!isClearableRoom(plan.roomTypes().getOrDefault(entry.getKey(), RoomType.UNKNOWN))) {
                totals.put(entry.getValue(), 0);
            } else if (!totals.containsKey(entry.getValue())) return -1;
        }
        return totals.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int observedRoomCount(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (renderPlan == null) {
            return 0;
        }
        return renderPlan.observedRoomCount();
    }

    private static int roomCellCount(DungeonLiveMapWriter.MatchRenderPlan renderPlan) {
        if (renderPlan == null) {
            return 0;
        }
        return renderPlan.roomCellCount();
    }

    private static int mapRoomCount(
        DungeonLiveMapWriter.MatchRenderPlan renderPlan,
        Set<DungeonLiveMapWriter.CellKey> cells
    ) {
        if (renderPlan == null || cells == null || cells.isEmpty()) {
            return 0;
        }
        return renderPlan.ownerCount(cells);
    }

    private void observeTotalDeaths(int observedDeaths) {
        deathTracker.observeTeamTotal(observedDeaths);
        deaths = deathTracker.totalDeaths();
    }

    private static boolean isGeneratedRunSummaryLine(String message) {
        return message.startsWith("[Kung")
            || message.contains("Run Stats")
            || message.contains("Player Stats")
            || message.startsWith("Rooms cleared:")
            || (message.startsWith("Score ") && message.contains(" | Secrets ") && message.contains(" | Crypts "))
            || message.contains(": Attributed Rooms ");
    }

    void observeSkyblockerBonus(DungeonBonusContribution bonus) {
        // Skyblocker's validated sync reports bypass chat; they do not identify the killer.
        boolean alreadyKnown = switch (bonus) {
            case MIMIC -> mimicKilled;
            case PRINCE -> princeKilled;
            case BAT -> batScoreKilled;
        };
        if (alreadyKnown) return;
        switch (bonus) {
            case MIMIC -> markMimicKilled(null, false, "skyblocker");
            case PRINCE -> markPrinceKilled(null, false);
            case BAT -> markBatScoreKilled(null, false);
        }
        KungDebugRecorder.event("score-bonus", "source=skyblocker bonus=" + bonus);
        updateEstimatedScore();
    }

    private void observeScoreKillMessage(Minecraft client, String message) {
        boolean recognized = false;
        var claim = DungeonBonusContribution.namedClaim(message);
        if (claim != null) {
            UUID uuid = trackedPlayerUuid(claim.name());
            if (uuid != null) {
                recognized = true;
                recordBonusContributor(uuid, claim.bonus());
                switch (claim.bonus()) {
                    case PRINCE -> markPrinceKilled(client, false);
                    case MIMIC -> markMimicKilled(client, false, "chat");
                    case BAT -> markBatScoreKilled(client, false);
                }
            }
        }
        if (MIMIC_KILL_PATTERN.matcher(message).matches()) {
            recognized = true;
            markMimicKilled(client, false, "chat");
        }
        if (PRINCE_KILL_PATTERN.matcher(message).matches() || message.equals(HYPIXEL_PRINCE_KILL_MESSAGE)) {
            recognized = true;
            markPrinceKilled(client, message.equals(HYPIXEL_PRINCE_KILL_MESSAGE));
        }
        if (BAT_KILL_PATTERN.matcher(message).matches() || message.equals(HYPIXEL_BAT_KILL_MESSAGE)) {
            recognized = true;
            markBatScoreKilled(client, message.equals(HYPIXEL_BAT_KILL_MESSAGE));
        }
        // Keep overlapping inputs as evidence, even when another source set the flag first.
        if (recognized) {
            KungDebugRecorder.event("score-bonus", "prince=" + princeKilled + " bat=" + batScoreKilled
                + " mimic=" + mimicKilled
                + " message=\"" + KungDebugRecorder.compact(message) + "\"");
        }
    }

    private void recordBonusContributor(UUID uuid, DungeonBonusContribution bonus) {
        DungeonPlayerStats stats = playerStats(uuid, playerName(uuid));
        if (stats.bonusMarkers().contains(bonus.marker())) return;
        stats.addBonus(bonus);
        KungDebugRecorder.event("player-stats", "bonus contributor=" + stats.name() + " bonus=" + bonus.marker());
    }

    private void markMimicKilled(Minecraft client, boolean announce, String source) {
        var config = KungConfig.get().dungeon;
        String suppressed = mimicKilled ? "already-killed" : !announce ? "reported-kill"
            : !config.extraScoreMessagesEnabled() ? "extra-score-messages-off"
            : !config.mimicMessageEnabled() ? "mimic-message-off" : "none";
        KungDebugRecorder.event("mimic-kill", "evidence source=" + source + " first=" + !mimicKilled
            + " extraScoreMessages=" + config.extraScoreMessagesEnabled()
            + " mimicMessage=" + config.mimicMessageEnabled() + " suppressed=" + suppressed);
        if (mimicKilled) {
            return;
        }
        mimicKilled = true;
        announce &= config.extraScoreMessagesEnabled() && config.mimicMessageEnabled();
        if (announce && client != null && client.player != null) {
            client.player.sendSystemMessage(KungMessages.info("Dungeon", "mimic killed"));
        }
        if (announce && !mimicMessageSent) {
            announcements.sendPartyAfterCooldown(client, "Mimic dead!");
            mimicMessageSent = true;
        }
    }

    private void markPrinceKilled(Minecraft client, boolean announce) {
        if (princeKilled) {
            return;
        }
        princeKilled = true;
        var config = KungConfig.get().dungeon;
        announce &= config.extraScoreMessagesEnabled() && config.princeMessageEnabled();
        if (announce && !princeMessageSent) {
            announcements.sendPartyAfterCooldown(client, "Prince dead!");
            princeMessageSent = true;
        }
    }

    private void markBatScoreKilled(Minecraft client, boolean announce) {
        if (batScoreKilled) {
            return;
        }
        batScoreKilled = true;
        var config = KungConfig.get().dungeon;
        announce &= config.extraScoreMessagesEnabled() && config.batMessageEnabled();
        if (announce && !batMessageSent) {
            announcements.sendPartyAfterCooldown(client, "Bat dead!");
            batMessageSent = true;
        }
    }

    private void updateCryptsOpened(Minecraft client, int value) {
        int previous = cryptsOpened;
        cryptsOpened = Math.max(cryptsOpened, value);
        if (cryptsOpened > previous) {
            KungDebugRecorder.event("crypts", "opened=" + cryptsOpened + " previous=" + previous);
            maybeAnnounceCryptProgress(client);
            maybeShowFiveCryptTitle(client);
            maybeAnnounceFiveCrypts(client);
        }
    }

    private static boolean isCryptProgressMessage(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("party >")
            || lower.contains("guild >")
            || lower.contains("from ")
            || lower.contains("to ")
            || lower.contains("[skyblocker]")
            || lower.contains("[kung]")
            || lower.contains("wither skull")
            || lower.contains("hitting you")
            || lower.contains("damage")) {
            return false;
        }
        return CRYPT_PROGRESS_PATTERN.matcher(message).matches();
    }

    private void maybeAnnounceFiveCrypts(Minecraft client) {
        if (fiveCryptMessageSent
            || cryptsOpened < TARGET_CRYPTS
            || !KungConfig.get().dungeon.fiveCryptPartyMessageEnabled()) {
            return;
        }
        fiveCryptMessageSent = true;
        announcements.sendPartyAfterCooldown(client, KungConfig.get().dungeon.fiveCryptPartyMessage());
    }

    private void maybeAnnounceCryptProgress(Minecraft client) {
        if (!KungConfig.get().dungeon.cryptProgressPartyMessageEnabled()
            || cryptsOpened <= 0
            || cryptsOpened >= TARGET_CRYPTS) {
            return;
        }

        int remaining = Math.max(0, TARGET_CRYPTS - cryptsOpened);
        announcements.sendPartyAfterCooldown(client, "CRYPT! " + remaining + " crypts to go!");
    }

    private void maybeShowFiveCryptTitle(Minecraft client) {
        if (fiveCryptTitleShown
            || cryptsOpened < TARGET_CRYPTS
            || !KungConfig.get().dungeon.fiveCryptTitleEnabled()) {
            return;
        }
        fiveCryptTitleShown = true;
        DungeonAnnouncements.showFiveCryptTitle(client);
    }

    private boolean isMimicEntity(Entity entity) {
        if (!hasMimic() || !(entity instanceof Zombie zombie) || !zombie.isBaby()) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR.slots()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack armor = zombie.getItemBySlot(slot);
            if (!armor.isEmpty()) {
                return false;
            }
        }
        return true;
    }



    private static int floorForBossName(String bossName) {
        String lower = bossName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("bonzo")) {
            return 1;
        }
        if (lower.contains("scarf")) {
            return 2;
        }
        if (lower.contains("professor")) {
            return 3;
        }
        if (lower.contains("thorn")) {
            return 4;
        }
        if (lower.contains("livid")) {
            return 5;
        }
        if (lower.contains("sadan")) {
            return 6;
        }
        return 7;
    }

    private static long parseElapsedSeconds(String value) {
        Matcher matcher = TIME_PATTERN.matcher(value.toLowerCase(java.util.Locale.ROOT));
        if (!matcher.find()) {
            return -1L;
        }

        long minutes = matcher.group(1) == null ? 0L : Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        return minutes * 60L + seconds;
    }

    private static long ticksToSeconds(long ticks) {
        return Math.max(0L, ticks / 20L);
    }

    private record RoomKey(int roomGridX, int roomGridZ) {
    }


    public enum DungeonClass {
        UNKNOWN(0xFFE9EDF2),
        ARCHER(0xFFFF2D32),
        BERSERKER(0xFFFF8000),
        MAGE(0xFF62DFFF),
        HEALER(0xFFB040FF),
        TANK(0xFF34A853);

        private final int argb;

        DungeonClass(int argb) {
            this.argb = argb;
        }

        /** Class colour shared by the dungeon map markers and the run summary. */
        public int argb() {
            return argb;
        }
    }

    public record DungeonPlayerSlot(int index, UUID uuid, String name, DungeonClass dungeonClass) {
    }
}
