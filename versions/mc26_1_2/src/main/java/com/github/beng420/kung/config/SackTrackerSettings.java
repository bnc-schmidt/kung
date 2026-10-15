package com.github.beng420.kung.config;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.SackItem;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.feature.misc.SackTrackerFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The track key, then one collapsible row per tracked item; rows keep their identity until the list changes. */
final class SackTrackerSettings implements Supplier<List<SettingEntry>> {
    private final MiscConfig config;
    private List<SackItem> items = List.of();
    private List<SettingEntry> rows = List.of();

    SackTrackerSettings(MiscConfig config) { this.config = config; }

    @Override
    public List<SettingEntry> get() {
        if (!rows.isEmpty() && items.equals(config.sackTrackerItems())) return rows;
        items = List.copyOf(config.sackTrackerItems());
        var next = new ArrayList<SettingEntry>();
        next.add(SettingEntry.keybind("Track Key", () -> LoadoutsAutoCloseFeature.keybindDisplay(config.sackTrackerKeybind()),
            config::sackTrackerKeybind, config::setSackTrackerKeybind)
            .withTooltip("Press it over an item in a sack to track it, and again to stop."));
        next.add(SettingEntry.toggle("Show In Menus", config::sackTrackerInMenus,
            config::setSackTrackerInMenus)
            .withTooltip("Also shows the tracker over inventories, chat and other menus."));
        for (SackItem item : items) {
            next.add(SettingEntry.group(() -> item.name() + ": " + SackTrackerFeature.format(item.amount())
                    + (item.goal() > 0 ? "/" + SackTrackerFeature.format(item.goal()) : ""))
                .withChildren(List.of(
                    SettingEntry.text("Goal", () -> item.goal() > 0 ? String.valueOf(item.goal()) : "", value -> {
                        Long goal = SackTrackerFeature.parseAmount(value);
                        config.setSackTrackerGoal(item, goal == null ? 0 : goal);
                    }).withTooltip("How many you want; 10k and 1.5m work too. Empty shows the count alone."),
                    SettingEntry.button("Remove", "-", () -> config.removeSackTrackerItem(item))
                )));
        }
        return rows = List.copyOf(next);
    }
}
