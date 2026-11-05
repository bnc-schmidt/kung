package com.github.beng420.kung.config.category;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

public final class HitboxesConfig extends ConfigCategory {
    public static final int DEFAULT_COLOR = 0xFF55FF55;
    /** Mob modifiers: a Healthy or Speedy Skeleton Master is still a Skeleton Master. Not Golden - Golden Ghoul is its own mob. */
    private static final Pattern MODIFIERS = Pattern.compile(
        "^skyblock:(?:(?:healthy|speedy|fortified|stormy|flaming|healing|boomer|stealthy|runic|corrupted)_)+(?=[a-z0-9])");
    private boolean enabled;
    private boolean dragonOverallBox = true;
    private boolean dragonPartBoxes = true;
    private Map<String, Integer> entities = new LinkedHashMap<>();
    /** Per entry, in percent; an entry without a value (every one saved before these existed) draws as it always did. */
    private Map<String, Integer> lineThickness = new LinkedHashMap<>();
    private Map<String, Integer> boxSize = new LinkedHashMap<>();
    /** Entries switched off without being deleted; one missing here is on, so every config saved before this loads all-on. */
    private Set<String> disabled = new LinkedHashSet<>();

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public boolean dragonOverallBox() { return dragonOverallBox; }
    public void setDragonOverallBox(boolean value) { dragonOverallBox = value; save(); }
    public boolean dragonPartBoxes() { return dragonPartBoxes; }
    public void setDragonPartBoxes(boolean value) { dragonPartBoxes = value; save(); }
    public Map<String, Integer> entities() { return Collections.unmodifiableMap(entities); }

    public boolean active(String id) { return !disabled.contains(id); }

    public void setActive(String id, boolean active) {
        if (entities.containsKey(id) && (active ? disabled.remove(id) : disabled.add(id))) save();
    }

    /** What draws: the saved entries minus the switched-off ones. */
    public Map<String, Integer> activeEntities() {
        Map<String, Integer> on = new LinkedHashMap<>(entities);
        on.keySet().removeAll(disabled);
        return Collections.unmodifiableMap(on);
    }

    public void add(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        if (key != null && entities.putIfAbsent(key.toString(), DEFAULT_COLOR) == null) save();
    }

    public void remove(String id) {
        lineThickness.remove(id);
        boxSize.remove(id);
        disabled.remove(id);
        if (entities.remove(id) != null) save();
    }

    public int lineThickness(String id) { return lineThickness.getOrDefault(id, 100); }
    public void setLineThickness(String id, int percent) { put(lineThickness, id, Math.clamp(percent, 25, 400)); }
    public int boxSize(String id) { return boxSize.getOrDefault(id, 100); }
    public void setBoxSize(String id, int percent) { put(boxSize, id, Math.clamp(percent, 50, 200)); }

    private void put(Map<String, Integer> percents, String id, int percent) {
        if (entities.containsKey(id)) {
            percents.put(id, percent);
            save();
        }
    }

    public void setColor(String id, int color) {
        if (entities.containsKey(id)) {
            entities.put(id, color | 0xFF000000);
            save();
        }
    }

    @Override
    public void normalize() {
        if (entities == null) entities = new LinkedHashMap<>();
        Map<String, Integer> valid = new LinkedHashMap<>();
        entities.forEach((id, color) -> {
            Identifier key = id == null ? null : Identifier.tryParse(id);
            if (key != null && color != null) valid.putIfAbsent(withoutModifiers(key.toString()), color | 0xFF000000);
        });
        entities = valid;
        lineThickness = percents(lineThickness, 25, 400);
        boxSize = percents(boxSize, 50, 200);
        Set<String> off = new LinkedHashSet<>();
        if (disabled != null) disabled.stream().filter(entities::containsKey).forEach(off::add);
        disabled = off;
    }

    private Map<String, Integer> percents(Map<String, Integer> saved, int min, int max) {
        Map<String, Integer> valid = new LinkedHashMap<>();
        if (saved != null) saved.forEach((id, percent) -> {
            if (percent != null && entities.containsKey(id)) valid.put(id, Math.clamp(percent, min, max));
        });
        return valid;
    }

    /** "skyblock:healthy_skeleton_master" -> "skyblock:skeleton_master"; other ids stay. */
    public static String withoutModifiers(String id) { return MODIFIERS.matcher(id).replaceFirst("skyblock:"); }
}
