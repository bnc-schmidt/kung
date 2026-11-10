package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.message.KungMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Split notifications use local Kung system messages, independent of prediction visibility. */
final class DungeonSplitMessages {
    private DungeonSplitMessages() { }

    static void send(DungeonSplitTracker.PhaseMessage phase) {
        send(notices(phase));
    }

    static void send(List<Notice> notices) {
        Minecraft client = Minecraft.getInstance();
        for (Notice notice : notices) {
            KungMessages.send(client, notice.component());
        }
    }

    static List<Notice> summary(DungeonSplitTracker tracker, int floor, boolean masterMode, SplitsConfig config) {
        List<Notice> result = new ArrayList<>();
        // " | <pb>" after every line: the same row of the best recorded run, "--" where that run has none.
        Map<String, Long> pbRun = config.includePbRun() ? tracker.pbRun() : null;
        result.add(new Notice(KungMessages.Type.INFO, floorPrefix(floor, masterMode) + "Run Splits (server time in parentheses"
            + (pbRun == null ? ")" : ", best run after |)")));
        for (String name : tracker.splitNames()) {
            var split = DungeonSplitsOverlayFeature.phaseSnapshot(tracker, name);
            long wall = split == null ? -1L : split.splitDurationMillis();
            boolean unfinished = split != null && split == tracker.stoppedCurrentSplit();
            result.add(summaryTime(name, wall, split == null ? -1L : split.serverSplitDurationMillis(), config,
                unfinished, pbText(pbRun, name, config), delta(pbRun, name, wall, !unfinished), pbMillis(pbRun, name)));
        }
        var portal = DungeonSplitsOverlayFeature.completedSplit("Portal Entry", tracker.completedSplits());
        long bossWall = portal == null ? -1L : portal.totalDurationMillis();
        result.add(summaryTime("Boss Entry", bossWall, portal == null ? -1L : portal.serverTotalDurationMillis(), config, false,
            pbText(pbRun, SplitsConfig.BEST_RUN_BOSS_ENTRY, config),
            delta(pbRun, SplitsConfig.BEST_RUN_BOSS_ENTRY, bossWall, true), pbMillis(pbRun, SplitsConfig.BEST_RUN_BOSS_ENTRY)));
        long totalWall = tracker.currentTotalDurationMillis();
        result.add(summaryTime("Total", totalWall, tracker.currentTotalServerDurationMillis(), config, false,
            pbText(pbRun, SplitsConfig.BEST_RUN_TOTAL, config),
            delta(pbRun, SplitsConfig.BEST_RUN_TOTAL, totalWall, DungeonSplitsOverlayFeature.finishedAllPhases(tracker)),
            pbMillis(pbRun, SplitsConfig.BEST_RUN_TOTAL)));
        if (config.timeLost()) {
            long loss = DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker);
            result.add(new Notice(colored("Time Lost: ", DungeonSplitsOverlayFeature.LOST_TIME)
                .append(colored(DungeonSplitsOverlayFeature.formatLostTimeMillis(loss),
                    loss < 0L ? DungeonSplitsOverlayFeature.MUTED : DungeonSplitsOverlayFeature.LOST_TIME))));
        }
        return List.copyOf(result);
    }

    /** " | 1m 2.34s" for a row of the best run, or nothing while the best run is not included. */
    private static String pbText(Map<String, Long> pbRun, String row, SplitsConfig config) {
        if (pbRun == null) return "";
        return " | " + DungeonSplitsOverlayFeature.formatDurationMillis(pbRun.getOrDefault(row, -1L), config.format());
    }

    private static long pbMillis(Map<String, Long> pbRun, String row) {
        return pbRun == null ? -1L : pbRun.getOrDefault(row, -1L);
    }

    /** This run's time minus the best run's for a row, or NO_DELTA while the best run is not included or has none. */
    private static long delta(Map<String, Long> pbRun, String row, long wallMillis, boolean settled) {
        return pbRun == null ? DungeonSplitsOverlayFeature.NO_DELTA
            : DungeonSplitsOverlayFeature.deltaMillis(wallMillis, pbRun.getOrDefault(row, -1L), settled);
    }

    /** The chat line for a run that beat the floor's best: the new time and the one it replaced. */
    static Notice bestRunNotice(int floor, boolean masterMode, long totalMillis, long previousMillis,
                                SplitsConfig.TimeFormat format) {
        String previous = previousMillis > 0L
            ? " (Previous: " + DungeonSplitsOverlayFeature.formatDurationMillis(previousMillis, format) + ")"
            : " (first recorded run)";
        return new Notice(KungMessages.Type.SUCCESS, floorPrefix(floor, masterMode) + "new PB run recorded: "
            + DungeonSplitsOverlayFeature.formatDurationMillis(totalMillis, format) + previous);
    }

    private static Notice summaryTime(String name, long wallMillis, long serverMillis, SplitsConfig config,
                                      boolean unfinished, String pb, long delta, long pbMillis) {
        int labelColor = wallMillis >= 0L || name.equals("Boss Entry") || name.equals("Total")
            ? DungeonSplitsOverlayFeature.phaseColor(name) : DungeonSplitsOverlayFeature.MUTED;
        var body = colored(name + (unfinished ? " (unfinished)" : "") + ": ", labelColor)
            .append(colored(DungeonSplitsOverlayFeature.formatDurationMillis(wallMillis, config.format()),
                wallMillis < 0L ? DungeonSplitsOverlayFeature.MUTED : DungeonSplitsOverlayFeature.TEXT))
            .append(colored(" (" + DungeonSplitsOverlayFeature.formatDurationMillis(serverMillis, config.format()) + ")",
                DungeonSplitsOverlayFeature.MUTED));
        String loss = config.timeLost() ? DungeonSplitsOverlayFeature.lostTimeSuffix(wallMillis, serverMillis) : "";
        if (!loss.isEmpty()) body.append(colored(" " + loss, DungeonSplitsOverlayFeature.LOST_TIME));
        if (!pb.isEmpty()) body.append(colored(pb, DungeonSplitsOverlayFeature.MUTED));
        if (delta != DungeonSplitsOverlayFeature.NO_DELTA) {
            body.append(colored(" (" + DungeonSplitsOverlayFeature.formatDeltaMillis(delta) + ")",
                DungeonSplitsOverlayFeature.deltaColor(delta, pbMillis)));
        }
        return new Notice(body);
    }

    /** "M5 ", "Entrance " or nothing while the floor is unknown. */
    private static String floorPrefix(int floor, boolean masterMode) {
        return floor < 0 ? "" : DungeonPbTitle.floorLabel(floor, masterMode) + " ";
    }

    private static MutableComponent colored(String text, int argb) {
        return Component.literal(text).withColor(argb & 0xFFFFFF);
    }

    static List<Notice> notices(DungeonSplitTracker.PhaseMessage phase) {
        String result = floorPrefix(phase.floor(), phase.masterMode()) + phase.phase() + ": "
            + DungeonSplitsOverlayFeature.formatDurationMillis(phase.durationMillis(), phase.format());
        long best = phase.personalBest() ? phase.durationMillis() : phase.previousBestMillis();
        Notice time = new Notice(KungMessages.Type.INFO, result + " (PB: "
            + DungeonSplitsOverlayFeature.formatDurationMillis(best, phase.format()) + ")");
        return phase.personalBest()
            ? List.of(time, new Notice(KungMessages.Type.SUCCESS, result + " (Previous PB: "
                + DungeonSplitsOverlayFeature.formatDurationMillis(phase.previousBestMillis(), phase.format()) + ")"))
            : List.of(time);
    }

    record Notice(KungMessages.Type type, String result, Component styledBody) {
        Notice(KungMessages.Type type, String result) {
            this(type, result, null);
        }

        Notice(Component styledBody) {
            this(KungMessages.Type.INFO, styledBody.getString(), styledBody);
        }

        String text() {
            return type == KungMessages.Type.SUCCESS ? "PERSONAL BEST! " + result : result;
        }

        Component component() {
            if (styledBody != null) return KungMessages.component(type, "Splits", "").copy().append(styledBody);
            if (type != KungMessages.Type.SUCCESS) return KungMessages.component(type, "Splits", result);
            return KungMessages.component(type, "Splits", "").copy()
                .append(Component.literal("PERSONAL BEST!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                .append(Component.literal(" " + result).withStyle(ChatFormatting.GREEN));
        }
    }
}
