package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.BRHelperConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class BloodRushHelperFeature extends ConfigurableFeature<BRHelperConfig> {
    public static final BloodRushHelperFeature INSTANCE = new BloodRushHelperFeature();
    private static final long DOOR_TITLE_STABLE_TICKS = 1;

    private boolean initialTitleScheduled;
    private boolean initialTitleShown;
    private long initialTitleScheduledTick = Long.MIN_VALUE;
    private long initialTitleStableSinceTick = Long.MIN_VALUE;
    private int initialTitleCandidateCount = -1;
    private String initialTitleCandidateText = "";
    private Integer forcedDoorTitleCount;
    private final Set<DungeonLiveMapWriter.CellKey> observedOpenedDoorCells = new HashSet<>();
    private boolean progressBaselineReady;
    private int lastRemainingDoorCount = -1;
    private String lastLoggedDoorTitleState = "";
    private String lastLoggedDoorProgressState = "";
    private long serverTicksSinceRunStart;
    private int maxObservedBloodRushDoors;
    private boolean bloodRushDoneShown;
    private DungeonLiveMapWriter.MatchRenderPlan lastProgressPlan;

    private BloodRushHelperFeature() {
        super(config -> config.bloodRush);
    }

    @Override
    protected void onInitialize() {
    }

    @Override
    public boolean isEnabled() {
        return config().enabled();
    }

    boolean doorTitlePending(long dungeonTick) {
        return enabled()
            && initialTitleScheduled
            && !initialTitleShown
            && forcedDoorTitleCount == null;
    }

    boolean shouldFastScanDoors(long dungeonTick) {
        return doorTitlePending(dungeonTick);
    }

    void debugShowDoorTitle(Minecraft client, int doorCount) {
        if (!enabled()) {
            return;
        }
        displayDoorTitle(client, Math.max(0, doorCount) + " doors");
    }

    void debugScheduleDoorTitle(int doorCount, long dungeonTick) {
        if (!enabled()) {
            return;
        }
        forcedDoorTitleCount = Math.max(0, doorCount);
        scheduleInitial(dungeonTick);
    }

    void scheduleInitial(long dungeonTick) {
        if (!enabled()) {
            return;
        }
        initialTitleScheduled = true;
        initialTitleShown = false;
        initialTitleScheduledTick = dungeonTick;
        initialTitleStableSinceTick = Long.MIN_VALUE;
        initialTitleCandidateCount = -1;
        initialTitleCandidateText = "";
        lastLoggedDoorTitleState = "";
        serverTicksSinceRunStart = 0L;
        maxObservedBloodRushDoors = 0;
        bloodRushDoneShown = false;
        observedOpenedDoorCells.clear();
        progressBaselineReady = false;
        lastRemainingDoorCount = -1;
        lastProgressPlan = null;
        KungDebugRecorder.event("door-title", "scheduled initial atTick=" + dungeonTick);
    }

    void clear(long dungeonTick) {
        initialTitleScheduled = false;
        initialTitleShown = false;
        initialTitleScheduledTick = Long.MIN_VALUE;
        initialTitleStableSinceTick = Long.MIN_VALUE;
        initialTitleCandidateCount = -1;
        initialTitleCandidateText = "";
        forcedDoorTitleCount = null;
        observedOpenedDoorCells.clear();
        progressBaselineReady = false;
        lastRemainingDoorCount = -1;
        lastLoggedDoorTitleState = "";
        lastLoggedDoorProgressState = "";
        serverTicksSinceRunStart = 0L;
        maxObservedBloodRushDoors = 0;
        bloodRushDoneShown = false;
        lastProgressPlan = null;
        KungDebugRecorder.event("door-title", "cleared atTick=" + dungeonTick);
    }

    void serverTick() {
        if (enabled()) {
            serverTicksSinceRunStart++;
        }
    }

    void observeMessage(Minecraft client, String message, boolean realRunStarted, DungeonStateTracker tracker) {
        if (!enabled() || !realRunStarted || message == null) {
            return;
        }
        String clean = DungeonLifecycleSignals.clean(message).toLowerCase(Locale.ROOT);
        if ("the blood door has been opened!".equals(clean)) {
            recordBloodRushDoorObservation(tracker.renderPlan(), tracker.mapSnapshot());
            showBloodRushDone(client);
        }
    }

    void observeProgress(Minecraft client, DungeonStateTracker tracker) {
        if (!enabled() || bloodRushDoneShown || !tracker.realRunStarted()) {
            return;
        }

        DungeonMapSnapshot snapshot = tracker.mapSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = tracker.renderPlan();
        if (plan == lastProgressPlan) return;
        lastProgressPlan = plan;
        recordBloodRushDoorObservation(plan, snapshot);
        BloodRushDoorEstimate estimate = BloodRushDoorEstimate.remaining(plan, snapshot);
        if (!estimate.available()) {
            logDoorProgressState("waiting estimate=false " + doorTitleDiagnostics(tracker));
            return;
        }

        Set<DungeonLiveMapWriter.CellKey> openedDoors = plan.minimumVisibleOpenedSpecialDoorCells(snapshot);
        int remainingDoors = estimate.count();
        if (!progressBaselineReady) {
            progressBaselineReady = true;
            observedOpenedDoorCells.clear();
            observedOpenedDoorCells.addAll(openedDoors);
            lastRemainingDoorCount = remainingDoors;
            if (!openedDoors.isEmpty() && initialTitleScheduled && !initialTitleShown && remainingDoors > 0) {
                initialTitleScheduled = false;
                initialTitleShown = true;
                displayDoorTitle(client, estimate.remainingTitle(plan.bloodIsNext()));
            }
            logDoorProgressState("baseline opened="
                + openedDoors.size()
                + " remaining="
                + remainingDoors
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        Set<DungeonLiveMapWriter.CellKey> newlyOpenedDoors = new HashSet<>(openedDoors);
        newlyOpenedDoors.removeAll(observedOpenedDoorCells);

        int previousRemainingDoors = lastRemainingDoorCount;
        boolean remainingDropped = previousRemainingDoors < 0 || remainingDoors < previousRemainingDoors;
        if (newlyOpenedDoors.isEmpty() && !remainingDropped) {
            lastRemainingDoorCount = remainingDoors;
            observedOpenedDoorCells.addAll(openedDoors);
            logDoorProgressState("steady opened="
                + openedDoors.size()
                + " remaining="
                + remainingDoors
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        if (newlyOpenedDoors.isEmpty()) {
            lastRemainingDoorCount = remainingDoors;
            logDoorProgressState("path-adjusted opened=[] remaining="
                + remainingDoors
                + " previousRemaining="
                + previousRemainingDoors
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        observedOpenedDoorCells.addAll(openedDoors);
        lastRemainingDoorCount = remainingDoors;
        logDoorProgressState("door-fell opened="
            + cellKeysText(newlyOpenedDoors)
            + " remaining="
            + remainingDoors
            + " dropped="
            + remainingDropped
            + " "
            + doorTitleDiagnostics(tracker));

        if (remainingDoors <= 0) {
            if (!bloodRushDoneShown && estimate.exact() && plan.hasLockedBloodSpecialDoor()
                && !openedBloodDoor(plan, newlyOpenedDoors)) {
                displayDoorTitle(client, "Blood next");
            }
            return;
        }
        initialTitleScheduled = false;
        initialTitleShown = true;
        initialTitleCandidateCount = -1;
        initialTitleCandidateText = "";
        initialTitleStableSinceTick = Long.MIN_VALUE;
        displayDoorTitle(client, estimate.remainingTitle(plan.bloodIsNext()));
    }

    void maybeShowDoorTitle(Minecraft client, DungeonStateTracker tracker) {
        if (!enabled()) {
            return;
        }

        long dungeonTick = tracker.dungeonTick();
        if (forcedDoorTitleCount != null) {
            displayDoorTitle(client, forcedDoorTitleCount + " doors");
            forcedDoorTitleCount = null;
            initialTitleScheduled = false;
            initialTitleShown = true;
            return;
        }

        if (!initialTitleScheduled || initialTitleShown || !tracker.realRunStarted()) {
            return;
        }

        DungeonMapSnapshot snapshot = tracker.mapSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = tracker.renderPlan();
        recordBloodRushDoorObservation(plan, snapshot);
        BloodRushDoorEstimate estimate = BloodRushDoorEstimate.initial(plan, snapshot);
        if (!estimate.available()) {
            initialTitleCandidateCount = -1;
            initialTitleCandidateText = "";
            initialTitleStableSinceTick = Long.MIN_VALUE;
            logDoorTitleState("waiting estimate=false since="
                + initialTitleScheduledTick
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        String titleText = estimate.initialTitle();
        if (estimate.count() != initialTitleCandidateCount || !titleText.equals(initialTitleCandidateText)) {
            initialTitleCandidateCount = estimate.count();
            initialTitleCandidateText = titleText;
            initialTitleStableSinceTick = dungeonTick;
            logDoorTitleState("candidate count="
                + estimate.count()
                + " text=\""
                + titleText
                + "\" exact="
                + estimate.exact()
                + " tick="
                + dungeonTick
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        if (initialTitleStableSinceTick == Long.MIN_VALUE
            || dungeonTick - initialTitleStableSinceTick < DOOR_TITLE_STABLE_TICKS) {
            logDoorTitleState("confirming count="
                + estimate.count()
                + " text=\""
                + titleText
                + "\" exact="
                + estimate.exact()
                + " since="
                + initialTitleStableSinceTick
                + " "
                + doorTitleDiagnostics(tracker));
            return;
        }

        displayDoorTitle(client, titleText);
        initialTitleShown = true;
        initialTitleScheduled = false;
        initialTitleCandidateCount = -1;
        initialTitleCandidateText = "";
        initialTitleStableSinceTick = Long.MIN_VALUE;
        progressBaselineReady = true;
        observedOpenedDoorCells.clear();
        observedOpenedDoorCells.addAll(plan.minimumVisibleOpenedSpecialDoorCells(snapshot));
        lastRemainingDoorCount = BloodRushDoorEstimate.remaining(plan, snapshot).count();
        logDoorTitleState("show initial count="
            + estimate.count()
            + " text=\""
            + titleText
            + "\" exact="
            + estimate.exact()
            + " remaining="
            + lastRemainingDoorCount
            + " "
            + doorTitleDiagnostics(tracker));
    }

    private static String cellKeysText(Set<DungeonLiveMapWriter.CellKey> cells) {
        if (cells.isEmpty()) {
            return "[]";
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (DungeonLiveMapWriter.CellKey cell : cells) {
            keys.add(cell.x() + "," + cell.z());
        }
        keys.sort(String::compareTo);
        return "[" + String.join("|", keys) + "]";
    }

    private static boolean openedBloodDoor(
        DungeonLiveMapWriter.MatchRenderPlan plan,
        Set<DungeonLiveMapWriter.CellKey> openedDoors
    ) {
        for (DungeonLiveMapWriter.CellKey openedDoor : openedDoors) {
            DungeonLiveMapWriter.DoorRenderInfo door = plan.doorAt(openedDoor.x(), openedDoor.z());
            if (door != null && door.targetType() == RoomType.BLOOD) {
                return true;
            }
        }
        return false;
    }

    private void recordBloodRushDoorObservation(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonMapSnapshot snapshot) {
        if (plan == null || snapshot == null) {
            return;
        }
        int observedTotal = Math.max(
            Math.max(plan.bloodRushTotalSpecialDoorCount(), plan.knownNonStartSpecialDoorCount()),
            Math.max(plan.knownNonStartSpecialDoorCount(), plan.rawNonStartSpecialDoorCount(snapshot))
        );
        observedTotal = Math.max(observedTotal, plan.openedRawNonStartSpecialDoorCount(snapshot));
        maxObservedBloodRushDoors = Math.max(
            maxObservedBloodRushDoors,
            Math.max(observedTotal, plan.minimumVisibleBloodRushSpecialDoorCount(snapshot))
        );
    }

    private String doorTitleDiagnostics(DungeonStateTracker tracker) {
        DungeonMapSnapshot snapshot = tracker.mapSnapshot();
        DungeonLiveMapWriter.MatchRenderPlan plan = tracker.renderPlan();
        return "observedRooms="
            + plan.observedRoomCount()
            + " rawRoomCells="
            + snapshot.observedRoomCount()
            + " rawDoors="
            + snapshot.lockedSpecialDoorCount()
            + " bloodRushTotal="
            + plan.bloodRushTotalSpecialDoorCount()
            + " bloodRushLocked="
            + plan.bloodRushLockedSpecialDoorCount()
            + " knownTotal="
            + BloodRushDoorEstimate.totalKnown(plan, snapshot)
            + " knownOpened="
            + plan.minimumVisibleOpenedSpecialDoorCells(snapshot).size()
            + " bloodRushOpened="
            + plan.bloodRushOpenedSpecialDoorCells().size()
            + " minimumTotal="
            + plan.minimumVisibleBloodRushSpecialDoorCount(snapshot)
            + " minimumLocked="
            + plan.minimumVisibleBloodRushLockedSpecialDoorCount(snapshot)
            + " minimumOpened="
            + plan.minimumVisibleOpenedSpecialDoorCells(snapshot).size()
            + " mapVisibleRooms="
            + plan.mapVisibleRoomCount(snapshot)
            + " recognizedMapRooms="
            + plan.recognizedMapVisibleRoomCount(snapshot)
            + " pathVisible="
            + plan.bloodRushPathVisibleEnough(snapshot)
            + " matches="
            + plan.matches().size()
            + " hasBlood="
            + plan.hasRoomType(RoomType.BLOOD)
            + " exact="
            + BloodRushDoorEstimate.isExact(plan, snapshot)
            + " bloodRushDoors="
            + plan.bloodRushSpecialDoorSummary();
    }

    private void logDoorTitleState(String stateText) {
        if (!stateText.equals(lastLoggedDoorTitleState)) {
            lastLoggedDoorTitleState = stateText;
            KungDebugRecorder.event("door-title", stateText);
        }
    }

    private void logDoorProgressState(String stateText) {
        if (!stateText.equals(lastLoggedDoorProgressState)) {
            lastLoggedDoorProgressState = stateText;
            KungDebugRecorder.event("door-title", stateText);
        }
    }

    private void displayDoorTitle(Minecraft client, String text) {
        ChatFormatting color = "Blood next".equals(text) ? ChatFormatting.RED : ChatFormatting.GOLD;
        showDoorTitle(client, text, null, color, doorTitleStayTicks());
        sendDoorTitleChat(client, text);
    }

    private void showBloodRushDone(Minecraft client) {
        if (bloodRushDoneShown) {
            return;
        }
        bloodRushDoneShown = true;
        // Server confirmation wins over door scans that arrive after Blood has opened.
        initialTitleScheduled = false;
        String subtitle = "took "
            + formatServerTickTime(serverTicksSinceRunStart)
            + " | "
            + Math.max(0, maxObservedBloodRushDoors)
            + " doors";
        showDoorTitle(client, "Blood rush done", subtitle, ChatFormatting.RED, Math.max(20, doorTitleStayTicks()));
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(KungMessages.info("BR", "Blood rush done - " + subtitle));
        }
        KungDebugRecorder.event("door-title", "blood done subtitle=\"" + subtitle + "\" serverTicks=" + serverTicksSinceRunStart);
    }

    private static void showDoorTitle(
        Minecraft client,
        String text,
        String subtitle,
        ChatFormatting color,
        int stayTicks
    ) {
        if (client == null || client.gui == null) {
            return;
        }
        McCompat.setTitleTimes(client, 0, Math.max(1, stayTicks), 5);
        McCompat.setSubtitle(client, subtitle == null || subtitle.isBlank()
            ? Component.empty()
            : Component.literal(subtitle).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
        McCompat.setTitle(client, Component.literal(text).withStyle(color, ChatFormatting.BOLD));
    }

    private static String formatServerTickTime(long ticks) {
        long millis = Math.max(0L, ticks) * 50L;
        long seconds = millis / 1000L;
        long milliseconds = millis % 1000L;
        return String.format(Locale.ROOT, "%d.%03ds", seconds, milliseconds);
    }

    private static void sendDoorTitleChat(Minecraft client, String text) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.sendSystemMessage(KungMessages.info("BR", text));
    }

    private static boolean enabled() {
        return INSTANCE.isEnabled();
    }

    private static int doorTitleStayTicks() {
        return Math.max(1, INSTANCE.config().titleDurationTenths() * 2);
    }
}
