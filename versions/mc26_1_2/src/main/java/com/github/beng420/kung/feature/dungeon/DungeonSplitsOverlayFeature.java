package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.ui.HudPose;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Compact colored phase labels and aligned times, following Odin's splits HUD. */
public final class DungeonSplitsOverlayFeature extends ConfigurableFeature<SplitsConfig> {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "dungeon_splits_overlay");
    private static final int WIDTH = 300;
    private static final int LOSS_COLUMN_WIDTH = 60;
    private static final int ROW_HEIGHT = 10;
    private static final int PADDING = 2;
    static final int TEXT = 0xFFE9EDF2;
    static final int MUTED = 0xFF858B95;
    private static final int ACTIVE = 0xFFFFF176;
    static final int LOST_TIME = 0xFFFF5555;
    private final DungeonStateTracker dungeonStateTracker;

    public DungeonSplitsOverlayFeature(DungeonStateTracker dungeonStateTracker) {
        super(config -> config.splits);
        this.dungeonStateTracker = java.util.Objects.requireNonNull(dungeonStateTracker);
    }

    @Override
    protected void onInitialize() {
        // Match the map's layer: container backgrounds dim both HUDs, and F1 hides both.
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID,
            (graphics, deltaTracker) -> render(graphics));
    }

    @Override
    public boolean isEnabled() {
        return config().enabled();
    }

    private void render(GuiGraphicsExtractor graphics) {
        if (KungHudEditorState.externalEditing()) return;
        SplitsConfig config = config();
        DungeonSplitTracker tracker = dungeonStateTracker.splitTracker();
        boolean editing = McCompat.screen(Minecraft.getInstance()) instanceof KungHudEditorScreen;
        if (!config.enabled() || !isVisible(editing, dungeonStateTracker.isInDungeonArea(), tracker)) return;
        draw(graphics, config, tracker, editing);
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, SplitsConfig config, DungeonSplitTracker tracker) {
        draw(graphics, config, tracker, true);
    }

    private static void draw(GuiGraphicsExtractor graphics, SplitsConfig config, DungeonSplitTracker tracker, boolean editing) {
        boolean example = editing && !tracker.started();
        String[] names = displayedNames(tracker);
        List<DungeonSplitTracker.CompletedSplit> completed = tracker.completedSplits();
        var timings = tracker.currentTimings();
        float scale = config.scale() / 100.0F;
        HudPose.begin(graphics, config.x(), config.y(), scale);
        try {
            int y = PADDING;
            for (int index = 0; index < names.length; index++) {
                String name = names[index];
                DungeonSplitTracker.CompletedSplit split = phaseSnapshot(tracker, name);
                boolean current = tracker.hasCurrentSplit() && name.equals(tracker.currentSplitName());
                long wall = -1L;
                long server = -1L;
                if (example) {
                    wall = 12_340L + index * 731L;
                    server = 12_000L + index * 700L;
                } else if (split != null) {
                    wall = split.splitDurationMillis();
                    server = split.serverSplitDurationMillis();
                } else if (current) {
                    wall = timings.splitMillis();
                    server = timings.serverSplitMillis();
                }
                drawRow(graphics, y, name, wall, server, current ? ACTIVE : TEXT,
                    wall >= 0L ? phaseColor(name) : MUTED, config, example || split != null);
                y += ROW_HEIGHT;
            }
            y += 2;
            DungeonSplitTracker.CompletedSplit portal = completedSplit("Portal Entry", completed);
            boolean inClear = tracker.started() && isClearSplit(tracker.currentSplitName());
            drawRow(graphics, y, "Boss Entry",
                example ? 110_000L : portal != null ? portal.totalDurationMillis() : inClear ? timings.totalMillis() : -1L,
                example ? 107_000L : portal != null ? portal.serverTotalDurationMillis() : inClear ? timings.serverTotalMillis() : -1L,
                TEXT, phaseColor("Boss Entry"), config, false);
            y += ROW_HEIGHT;
            long totalWall = example ? 360_000L : tracker.started() ? timings.totalMillis() : -1L;
            long totalServer = example ? 352_000L : tracker.started() ? timings.serverTotalMillis() : -1L;
            drawRow(graphics, y, "Total", totalWall, totalServer,
                tracker.running() ? ACTIVE : TEXT, phaseColor("Total"), config, false);
            Minecraft client = Minecraft.getInstance();
            if (config.timePrediction()) {
                y += ROW_HEIGHT;
                long prediction = example ? 362_670L : tracker.predictedFinishMillis(timings.totalMillis());
                String predictionText = formatDurationMillis(prediction, config.format());
                graphics.text(client.font, "Predicted", PADDING, y, TEXT, true);
                graphics.text(client.font, predictionText,
                    WIDTH - PADDING - LOSS_COLUMN_WIDTH - client.font.width(predictionText),
                    y, prediction < 0L ? MUTED : ACTIVE, true);
            }
            if (config.timeLost()) {
                y += ROW_HEIGHT;
                graphics.text(client.font, "Time Lost", PADDING, y, LOST_TIME, true);
                long loss = example ? lostTimeMillis(totalWall, totalServer) : settledTotalLostTimeMillis(tracker);
                String lossText = formatLostTimeMillis(loss);
                graphics.text(client.font, lossText, WIDTH - PADDING - LOSS_COLUMN_WIDTH - client.font.width(lossText),
                    y, loss < 0L ? MUTED : LOST_TIME, true);
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    public static KungHudLayout.Bounds overlayBounds(SplitsConfig config, DungeonSplitTracker tracker) {
        float scale = config.scale() / 100.0F;
        int summaryRows = 2 + (config.timeLost() ? 1 : 0) + (config.timePrediction() ? 1 : 0);
        int height = PADDING * 2 + (displayedNames(tracker).length + summaryRows) * ROW_HEIGHT + 2;
        int width = config.timeLost() ? WIDTH : WIDTH - LOSS_COLUMN_WIDTH;
        return new KungHudLayout.Bounds(config.x(), config.y(), Math.round(width * scale), Math.round(height * scale));
    }

    static boolean isVisible(boolean editing, boolean inDungeon, DungeonSplitTracker tracker) {
        return editing || inDungeon && tracker.started();
    }

    static String[] displayedNames(DungeonSplitTracker tracker) {
        return tracker.started() || tracker.hasKnownFloor()
            ? tracker.splitNames() : DungeonSplitTracker.defaultSplitNames();
    }

    static DungeonSplitTracker.CompletedSplit phaseSnapshot(DungeonSplitTracker tracker, String name) {
        var completed = completedSplit(name, tracker.completedSplits());
        if (completed != null) return completed;
        var stopped = tracker.stoppedCurrentSplit();
        return stopped != null && name.equals(stopped.name()) ? stopped : null;
    }

    private static boolean isClearSplit(String name) {
        return name.equals("Blood Open") || name.equals("Blood Clear") || name.equals("Portal Entry");
    }

    static DungeonSplitTracker.CompletedSplit completedSplit(
        String name, List<DungeonSplitTracker.CompletedSplit> completed
    ) {
        for (DungeonSplitTracker.CompletedSplit split : completed) {
            if (split.name().equals(name)) return split;
        }
        return null;
    }

    private static void drawRow(
        GuiGraphicsExtractor graphics, int y, String name, long wallMillis, long serverMillis,
        int timeColor, int labelColor, SplitsConfig config, boolean settled
    ) {
        Minecraft client = Minecraft.getInstance();
        graphics.text(client.font, name, PADDING, y, labelColor, true);
        String realTime = formatDurationMillis(wallMillis, config.format());
        String tickTime = " (" + formatDurationMillis(serverMillis, config.format()) + ")";
        int ticksWidth = client.font.width(tickTime);
        int timeRight = WIDTH - PADDING - LOSS_COLUMN_WIDTH;
        int wallX = timeRight - ticksWidth - client.font.width(realTime);
        graphics.text(client.font, realTime, wallX, y, wallMillis < 0L ? MUTED : timeColor, true);
        graphics.text(client.font, tickTime, timeRight - ticksWidth, y, MUTED, true);
        String loss = config.timeLost() && settled ? lostTimeSuffix(wallMillis, serverMillis) : "";
        if (!loss.isEmpty()) graphics.text(client.font, loss, timeRight + 6, y, LOST_TIME, true);
    }

    static long lostTimeMillis(long wallMillis, long serverMillis) {
        if (wallMillis < 0L || serverMillis < 0L) return -1L;
        return Math.max(0L, wallMillis - serverMillis);
    }

    /** A running phase never changes the displayed loss; use the last completed boundary. */
    static long settledTotalLostTimeMillis(DungeonSplitTracker tracker) {
        if (!tracker.started()) return -1L;
        if (!tracker.running()) {
            return lostTimeMillis(tracker.currentTotalDurationMillis(), tracker.currentTotalServerDurationMillis());
        }
        List<DungeonSplitTracker.CompletedSplit> completed = tracker.completedSplits();
        if (completed.isEmpty()) return -1L;
        var boundary = completed.getLast();
        return lostTimeMillis(boundary.totalDurationMillis(), boundary.serverTotalDurationMillis());
    }

    static String lostTimeSuffix(long wallMillis, long serverMillis) {
        long loss = lostTimeMillis(wallMillis, serverMillis);
        // Only show a loss that rounds to a non-zero tenth, avoiding a misleading red -0.0s.
        return loss < 50L ? "" : formatLostTimeMillis(loss);
    }

    static String formatLostTimeMillis(long millis) {
        if (millis < 0L) return "--";
        long tenths = millis / 100L + (millis % 100L >= 50L ? 1L : 0L);
        return (tenths > 0L ? "-" : "") + tenths / 10L + "." + tenths % 10L + "s";
    }

    static String formatDurationMillis(long millis, SplitsConfig.TimeFormat format) {
        if (millis < 0L) return "--";
        long hundredths = millis / 10L;
        long minutes = format == SplitsConfig.TimeFormat.SECONDS ? 0L : hundredths / 6_000L;
        long seconds = format == SplitsConfig.TimeFormat.SECONDS ? hundredths / 100L : hundredths / 100L % 60L;
        long fraction = hundredths % 100L;
        return (minutes > 0L ? minutes + "m " : "")
            + seconds + "." + (fraction < 10L ? "0" : "") + fraction + "s";
    }

    static int phaseColor(String name) {
        return switch (name) {
            case "Boss Entry" -> 0xFF7777FF;
            case "Total" -> 0xFF55FFFF;
            case "Blood Open" -> 0xFF55AA55;
            case "Blood Clear" -> 0xFF55FFFF;
            case "Portal Entry" -> 0xFFFF55FF;
            case "Maxor", "Relics" -> 0xFFBB66FF;
            case "Storm" -> 0xFF55BBBB;
            case "Terminals", "Dragons" -> 0xFFFFAA00;
            case "Goldor" -> 0xFFAAAAAA;
            case "Necron", "Bonzo Phase 1", "Scarf's Minions", "Guardians", "Terracottas" -> 0xFFFF5555;
            case "The Professor", "Giants" -> 0xFF55FF55;
            default -> 0xFFFF7777;
        };
    }
}
