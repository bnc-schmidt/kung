package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonStats;
import com.github.beng420.kung.util.CatacombsAverageCalculator.FloorStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The PERSONAL BEST! screen title: for a split that beat its saved best, and for a whole run Hypixel calls a new
 * record. Splits know their old best; the old record comes from the API once and from Hypixel's own lines after.
 */
final class DungeonPbTitle {
    private static final int FADE_IN = 0;
    private static final int STAY = 40;
    private static final int FADE_OUT = 10;
    private static final int OLD = 0xFFFF55;
    private static final int ARROW = 0x55FFFF;
    private static final int NEW = 0x55FF55;

    private DungeonPbTitle() { }

    /** A finished split: a title when it beat a best that existed (the first time sets one, nothing was broken). */
    static void phase(DungeonSplitTracker.PhaseMessage phase, SplitsConfig settings) {
        if (!settings.pbTitle() || !phase.personalBest() || phase.previousBestMillis() <= 0L) return;
        show(phase.phase(), phase.previousBestMillis(), phase.durationMillis(), phase.format());
    }

    /** Hypixel's "Defeated ... (NEW RECORD!)" line: remembers the record, and shows the title when it is on. */
    static void floorFinish(String message, int floor, boolean masterMode, SplitsConfig settings) {
        DungeonLifecycleSignals.FloorFinish finish = DungeonLifecycleSignals.parseFloorFinish(message);
        if (finish == null || !finish.newRecord() || floor < 0
            || !DungeonLifecycleSignals.isVictoryForFloor(message, floor, masterMode)) {
            return;
        }
        long previous = settings.floorRecordMillis(floor, masterMode);
        settings.recordFloorTime(floor, masterMode, finish.millis());
        if (settings.pbTitle()) {
            show(floorLabel(floor, masterMode), previous > finish.millis() ? previous : -1L, finish.millis(), settings.format());
        }
    }

    static String floorLabel(int floor, boolean masterMode) {
        return floor == 0 ? "Entrance" : (masterMode ? "M" : "F") + floor;
    }

    /** "Blood Open: 27.37s → 25.93s"; without a known old time just the new one. */
    static Component subtitle(String name, long oldMillis, long newMillis, SplitsConfig.TimeFormat format) {
        MutableComponent line = Component.literal(name + ": ").withStyle(ChatFormatting.WHITE);
        if (oldMillis > 0L) {
            line.append(Component.literal(DungeonSplitsOverlayFeature.formatDurationMillis(oldMillis, format)).withColor(OLD))
                .append(Component.literal(" → ").withColor(ARROW));
        }
        return line.append(Component.literal(DungeonSplitsOverlayFeature.formatDurationMillis(newMillis, format)).withColor(NEW));
    }

    private static void show(String name, long oldMillis, long newMillis, SplitsConfig.TimeFormat format) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui == null) return;
        McCompat.setTitleTimes(client, FADE_IN, STAY, FADE_OUT);
        McCompat.setSubtitle(client, subtitle(name, oldMillis, newMillis, format));
        McCompat.setTitle(client, Component.literal("PERSONAL BEST!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    }

    /** The floors' record times of an API lookup, as floor / master / milliseconds; the best of the S and S+ times. */
    static List<Record> recordsFrom(DungeonStats stats) {
        List<Record> records = new ArrayList<>();
        if (stats == null || !stats.available()) return records;
        for (int floor = 0; floor <= 7; floor++) {
            add(records, floor, false, stats.catacombs());
            if (floor > 0) add(records, floor, true, stats.master());
        }
        return records;
    }

    private static void add(List<Record> records, int floor, boolean master, FloorStats stats) {
        if (stats == null) return;
        long best = Long.MAX_VALUE;
        for (int time : new int[] {stats.value(stats.sPlus(), String.valueOf(floor)), stats.value(stats.s(), String.valueOf(floor))}) {
            if (time > 0) best = Math.min(best, time);
        }
        if (best != Long.MAX_VALUE) records.add(new Record(floor, master, best));
    }

    record Record(int floor, boolean master, long millis) { }
}
