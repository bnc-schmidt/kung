package com.github.beng420.kung.config;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.BowDrawThreshold;
import java.util.ArrayList;
import java.util.List;

/** Reuses the live list pattern from Hitboxes without recreating controls while a value changes. */
final class BowDrawThresholdSettings extends LiveRows<List<BowDrawThreshold>> {
    private static final String FEATURE = "Bow Draw Indicator";
    private final MiscConfig config;

    BowDrawThresholdSettings(MiscConfig config) { this.config = config; }

    @Override
    protected List<BowDrawThreshold> key() { return List.copyOf(config.bowDrawThresholds()); }

    @Override
    protected List<SettingEntry> build(List<BowDrawThreshold> entries) {
        var next = new ArrayList<SettingEntry>();
        next.add(SettingEntry.button("Add Threshold", "+", () -> {
            config.addBowDrawThreshold();
            showList();
        }).withTooltip("Add a marker from 0 to 20 server ticks. Maximum 20 entries."));
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            String name = "Threshold " + (index + 1);
            next.add(SettingEntry.colorRemove(name, entry::color, () -> editColor(name, entry), () -> {
                config.removeBowDrawThreshold(entry);
                showList();
            }).withTooltip("Click the color to change this marker's line; the red - removes it."));
            next.add(SettingEntry.slider("Ticks", entry::ticks, ticks -> config.setBowDrawThreshold(entry, ticks), 0, 20, 1)
                .withTooltip("3 ticks: minimum shot. 20 ticks: full power. Duplicate markers share one line."));
        }
        var dragons = com.github.beng420.kung.feature.dungeon.M7DragonFeature.INSTANCE;
        var lines = new ArrayList<SettingEntry>();
        for (int index = 0; index < dragons.drawRangeLines().size(); index++) {
            int line = index;
            lines.add(SettingEntry.dynamicLabel(() -> dragons.drawRangeLines().get(line)));
        }
        next.add(SettingEntry.toggle("M7 Dragon Ticks", config::bowDrawDragonTicksEnabled,
                config::setBowDrawDragonTicksEnabled)
            .withChildren(lines)
            .withTooltip("Live markers on the bar while an M7 dragon spawns. Needs Wither Dragons on.",
                "Kung predicts where the dragon's body will be and flies every draw from 3 to 20",
                "ticks at it from where you stand. A block on the way, like the statue above you, is a miss.",
                "Cyan: the least draw that reaches the body. Red: the most that still gets there cleanly.",
                "White: the middle of that range. No markers: nothing gets there cleanly from this spot.",
                "They follow the nearest spawning dragon and your position, from the spawn particles",
                "until shortly after the spawn. Right-click for each dragon's range at its last spawn."));
        return next;
    }

    private void editColor(String name, BowDrawThreshold entry) {
        // The optional native menu delegates the color to the same popup the Kung menu uses.
        KungConfigScreen.openOver(FEATURE, parent -> new HitboxEditorScreen(parent, name + " Color", entry.color(),
            color -> config.setBowDrawThresholdColor(entry, color)));
    }

    private static void showList() {
        KungConfigScreen.showMenu(FEATURE);
    }
}
