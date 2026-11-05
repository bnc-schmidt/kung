package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.message.KungMessages;
import java.util.ArrayList;
import java.util.List;
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
        result.add(new Notice(KungMessages.Type.INFO, floorPrefix(floor, masterMode) + "Run Splits (server time in parentheses)"));
        for (String name : tracker.splitNames()) {
            var split = DungeonSplitsOverlayFeature.phaseSnapshot(tracker, name);
            result.add(summaryTime(name, split == null ? -1L : split.splitDurationMillis(),
                split == null ? -1L : split.serverSplitDurationMillis(), config,
                split != null && split == tracker.stoppedCurrentSplit()));
        }
        var portal = DungeonSplitsOverlayFeature.completedSplit("Portal Entry", tracker.completedSplits());
        result.add(summaryTime("Boss Entry", portal == null ? -1L : portal.totalDurationMillis(),
            portal == null ? -1L : portal.serverTotalDurationMillis(), config, false));
        result.add(summaryTime("Total", tracker.currentTotalDurationMillis(), tracker.currentTotalServerDurationMillis(), config, false));
        if (config.timeLost()) {
            long loss = DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker);
            result.add(new Notice(colored("Time Lost: ", DungeonSplitsOverlayFeature.LOST_TIME)
                .append(colored(DungeonSplitsOverlayFeature.formatLostTimeMillis(loss),
                    loss < 0L ? DungeonSplitsOverlayFeature.MUTED : DungeonSplitsOverlayFeature.LOST_TIME))));
        }
        return List.copyOf(result);
    }

    private static Notice summaryTime(String name, long wallMillis, long serverMillis, SplitsConfig config, boolean unfinished) {
        int labelColor = wallMillis >= 0L || name.equals("Boss Entry") || name.equals("Total")
            ? DungeonSplitsOverlayFeature.phaseColor(name) : DungeonSplitsOverlayFeature.MUTED;
        var body = colored(name + (unfinished ? " (unfinished)" : "") + ": ", labelColor)
            .append(colored(DungeonSplitsOverlayFeature.formatDurationMillis(wallMillis, config.format()),
                wallMillis < 0L ? DungeonSplitsOverlayFeature.MUTED : DungeonSplitsOverlayFeature.TEXT))
            .append(colored(" (" + DungeonSplitsOverlayFeature.formatDurationMillis(serverMillis, config.format()) + ")",
                DungeonSplitsOverlayFeature.MUTED));
        String loss = config.timeLost() ? DungeonSplitsOverlayFeature.lostTimeSuffix(wallMillis, serverMillis) : "";
        if (!loss.isEmpty()) body.append(colored(" " + loss, DungeonSplitsOverlayFeature.LOST_TIME));
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
