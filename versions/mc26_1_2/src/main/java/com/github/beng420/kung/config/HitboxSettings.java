package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.HitboxesConfig;
import com.github.beng420.kung.feature.misc.HitboxesFeature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;

/** Stable row objects until membership changes preserve hover and click ownership. */
final class HitboxSettings implements Supplier<List<SettingEntry>> {
    private final HitboxesConfig config;
    private List<String> ids = List.of();
    private List<SettingEntry> rows = List.of();

    HitboxSettings(HitboxesConfig config) { this.config = config; }

    @Override
    public List<SettingEntry> get() {
        List<String> selected = List.copyOf(config.entities().keySet());
        if (!rows.isEmpty() && selected.equals(ids)) return rows;
        ids = selected;
        List<SettingEntry> next = new ArrayList<>();
        next.add(SettingEntry.button("Add Hitbox", "+", () -> open(null)));
        for (String id : ids) {
            List<SettingEntry> children = new ArrayList<>();
            String expand = "Expand for line thickness and box size.";
            if (id.equals("minecraft:ender_dragon")) {
                children.add(SettingEntry.toggle("Overall Box", config::dragonOverallBox,
                        config::setDragonOverallBox)
                    .withTooltip("Show the dragon's large overall bounding box."));
                children.add(SettingEntry.toggle("Body Part Boxes", config::dragonPartBoxes,
                        config::setDragonPartBoxes)
                    .withTooltip("Show the smaller boxes for the head, body, wings and tail."));
                expand = "Expand for independent overall and body-part boxes, line thickness and box size.";
            }
            children.add(SettingEntry.slider("Line Thickness (%)", () -> config.lineThickness(id),
                    percent -> config.setLineThickness(id, percent), 25, 400, 25)
                .withTooltip("100% = the normal outline; 400% = four times as thick.",
                    "Thicker lines stay visible through water and at a distance."));
            children.add(SettingEntry.slider("Box Size (%)", () -> config.boxSize(id),
                    percent -> config.setBoxSize(id, percent), 50, 200, 10)
                .withTooltip("50% = x0.5, 100% = normal, 200% = x2.0, around the box's center.",
                    "Only the drawn outline changes; the real hitbox stays the same."));
            next.add(SettingEntry.colorRemove(name(id), () -> config.entities().getOrDefault(id, HitboxesConfig.DEFAULT_COLOR),
                    () -> open(id), () -> config.remove(id))
                .withChildren(children).withTooltip(id, expand, "Click the color to edit; click - to remove."));
        }
        return rows = List.copyOf(next);
    }

    private void open(String id) {
        Minecraft client = Minecraft.getInstance();
        Screen parent = McCompat.screen(client);
        // The optional native menu delegates these compound controls to the same complete editor.
        if (!(parent instanceof KungConfigScreen)) {
            parent = KungConfigScreen.fromParent(parent, "Hitboxes");
            McCompat.setScreen(client, parent);
        }
        McCompat.setScreen(client, new HitboxEditorScreen(parent, config, id));
    }

    /** What was around the player in the last minute first, then every vanilla entity type. */
    static List<String> addableIds() {
        Set<String> ids = new LinkedHashSet<>(HitboxesFeature.recentIds());
        ids.addAll(entityIds());
        return List.copyOf(ids);
    }

    static List<String> entityIds() {
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Object::toString)
            .sorted(Comparator.comparing(HitboxSettings::name).thenComparing(Comparator.naturalOrder())).toList();
    }

    static List<String> search(List<String> all, Set<String> selected, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        return all.stream().filter(id -> !selected.contains(id))
            .filter(id -> name(id).toLowerCase(Locale.ROOT).contains(needle)
                || id.toLowerCase(Locale.ROOT).replace('_', ' ').contains(needle)).toList();
    }

    /**
     * A typed item name also becomes an item entry (boxes that item where an armor stand holds it,
     * e.g. a thrown Bonemerang), and any typed name a SkyBlock mob. Both last, so Enter still picks
     * a vanilla entity first.
     */
    static List<String> withTypedEntries(List<String> matches, Set<String> selected, String query) {
        List<String> result = new ArrayList<>(matches);
        String path = query.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]+", "_");
        var item = path.isEmpty() ? null : net.minecraft.resources.Identifier.tryParse("minecraft:" + path);
        if (item != null && BuiltInRegistries.ITEM.containsKey(item)) {
            String id = HitboxesFeature.ITEM + path;
            if (!result.contains(id) && !selected.contains(id)) result.add(id);
        }
        String typed = HitboxesFeature.skyBlockId(query);
        if (typed != null && !result.contains(typed) && !selected.contains(typed)) result.add(typed);
        return List.copyOf(result);
    }

    static String name(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        return name + (namespace.equals("minecraft") ? "" : " (" + namespace + ")");
    }
}
