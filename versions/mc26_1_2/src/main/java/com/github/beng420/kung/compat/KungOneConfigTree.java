package com.github.beng420.kung.compat;

import com.github.beng420.kung.config.KungSettings;
import com.github.beng420.kung.config.SettingEntry;
import com.github.beng420.kung.config.SettingKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.polyfrost.oneconfig.api.config.v1.Properties;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.config.v1.Tree;
import org.polyfrost.oneconfig.api.config.v1.Visualizer;
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind;

/** Native controls use Kung's validated setters; OneConfig never owns a second copy of the config. */
final class KungOneConfigTree {
    private final Tree tree = new Tree("kung", "Kung", "Hypixel SkyBlock quality-of-life settings.", null);
    private final BooleanSupplier writesAllowed;
    private final List<Runnable> refreshers = new ArrayList<>();

    KungOneConfigTree(List<KungSettings.CategoryEntry> categories, Runnable save, BooleanSupplier writesAllowed) {
        this.writesAllowed = writesAllowed;
        tree.addMetadata("icon_path", "assets/kung/icon.png");
        tree.addMetadata("custom_save", (Runnable) () -> { if (writesAllowed.getAsBoolean()) save.run(); });
        for (var category : categories) {
            for (var feature : category.features()) {
                String featureName = feature.actionOnly() ? "Updates" : feature.name();
                String prefix = id(category.name()) + "__" + id(featureName);
                Property<?> master;
                if (feature.actionOnly()) {
                    master = button(prefix + "__action", "Install Update", "", "Install", () -> {
                        if (feature.clickable()) feature.toggle().run();
                    });
                    refreshers.add(() -> master.addMetadata("text", feature.name()));
                } else {
                    master = property(prefix + "__enabled", "Enabled", String.join("\n", feature.tooltip()),
                        feature::enabled, enabled -> {
                            if (enabled != feature.enabled() && feature.clickable()) feature.toggle().run();
                        }, Boolean.class, Visualizer.SwitchVisualizer.class);
                }
                master.addDisplayCondition(() -> feature.clickable() ? Property.Display.SHOWN : Property.Display.DISABLED);
                put(master, category.name(), featureName);
                addSettings(feature.settings(), prefix, "", category.name(), featureName, () -> true, () -> true);
            }
        }
        refresh();
    }

    Tree tree() { return tree; }

    void applyDefaults(KungOneConfigTree defaults) {
        for (var entry : tree.map.entrySet()) {
            if (!(entry.getValue() instanceof Property<?> property) || property.type == void.class) continue;
            var defaultNode = defaults.tree.get(entry.getKey());
            if (defaultNode instanceof Property<?> defaultProperty && defaultProperty.get() != null) {
                property.addMetadata("default", defaultProperty.get());
            }
        }
    }

    void refresh() {
        for (Runnable refresh : refreshers) refresh.run();
        for (var node : tree.map.values()) {
            if (node instanceof Property<?> property) property.revaluateDisplay();
        }
    }

    private void addSettings(List<SettingEntry> settings, String prefix, String parentLabel,
                             String category, String feature, BooleanSupplier available, BooleanSupplier shown) {
        for (int index = 0; index < settings.size(); index++) {
            SettingEntry setting = settings.get(index);
            // A hidden parent hides its whole branch, e.g. Core Parts and its toggles while Marker is Skeleton.
            BooleanSupplier visible = () -> shown.getAsBoolean() && setting.visible();
            String label = parentLabel.isEmpty() ? setting.label() : parentLabel + " / " + setting.label();
            String key = prefix + "__" + (setting.kind() == SettingKind.LABEL ? "info_" + index : id(setting.label()));
            // Repeated rows (every bow threshold has a "Ticks" slider) keep their label but not their key.
            if (tree.map.containsKey(key)) key += "__" + index;
            String description = String.join("\n", setting.tooltip());
            Property<?> property = switch (setting.kind()) {
                case TOGGLE -> property(key, label, description, setting.booleanSupplier()::getAsBoolean,
                    value -> { if (value != setting.booleanSupplier().getAsBoolean()) setting.toggle().run(); },
                    Boolean.class, Visualizer.SwitchVisualizer.class);
                case STEPPER, SLIDER -> number(key, label, description, setting, feature);
                case CHOICE -> property(key, label, description, setting.intSupplier()::getAsInt,
                    setting.intConsumer()::accept, Integer.class, Visualizer.DropdownVisualizer.class);
                case TEXT -> property(key, label, description, setting.textSupplier(),
                    value -> setting.setText(value == null ? "" : value), String.class, Visualizer.TextVisualizer.class);
                case KEYBIND -> keybind(key, label, description, setting);
                case BUTTON, COLOR_REMOVE, COLOR -> button(key, label, description, setting.choiceSupplier().get(), setting.toggle());
                case LABEL -> property(key, "Information", description, setting.labelSupplier(),
                    value -> { }, String.class, null);
                case GROUP -> null;
            };
            if (property != null) {
                if (setting.kind() == SettingKind.CHOICE) {
                    property.addMetadata("options", setting.choices().toArray(String[]::new));
                }
                // The button shows the color's #RRGGBB, which changes when the popup saves.
                if (setting.kind() == SettingKind.COLOR) {
                    refreshers.add(() -> property.addMetadata("text", setting.choiceSupplier().get()));
                }
                property.addDisplayCondition(() -> !visible.getAsBoolean() ? Property.Display.HIDDEN
                    : available.getAsBoolean() ? Property.Display.SHOWN : Property.Display.DISABLED);
                put(property, category, feature);
            }
            if (setting.reset() != null) {
                var reset = button(key + "__reset", label + " / Reset", setting.reset().hint(), "Reset", setting.reset().action());
                reset.addDisplayCondition(() -> !visible.getAsBoolean() || !setting.resettable() ? Property.Display.HIDDEN
                    : available.getAsBoolean() ? Property.Display.SHOWN : Property.Display.DISABLED);
                put(reset, category, feature);
            }
            if (setting.kind() == SettingKind.COLOR_REMOVE) {
                put(button(key + "__remove", label + " / Remove", description, "Remove", setting.cycleChoice()), category, feature);
            }
            if (!setting.children().isEmpty()) {
                BooleanSupplier childAvailable = setting.kind() == SettingKind.TOGGLE
                    ? () -> available.getAsBoolean() && setting.booleanSupplier().getAsBoolean() : available;
                addSettings(setting.children(), key, label, category, feature, childAvailable, visible);
            }
        }
    }

    private Property<?> number(String key, String label, String description, SettingEntry setting, String feature) {
        double divisor = setting.label().equals("Title Time") ? 10.0 : 1.0;
        String unit = setting.label().equals("Title Time") ? "s"
            : divisor > 1.0 ? "x"
            : setting.label().contains("Alpha")
                ? "%" : "";
        String shownLabel = unit.isEmpty() ? label : label + " (" + unit + ")";
        Property<?> number;
        if (divisor > 1.0) {
            number = property(key, shownLabel, description, () -> setting.intSupplier().getAsInt() / divisor,
                value -> setting.intConsumer().accept(snap(setting, (int) Math.round(value * divisor))),
                Double.class, Visualizer.SliderVisualizer.class);
        } else {
            number = property(key, shownLabel, description, setting.intSupplier()::getAsInt,
                value -> setting.intConsumer().accept(snap(setting, value)), Integer.class,
                setting.kind() == SettingKind.SLIDER ? Visualizer.SliderVisualizer.class : Visualizer.NumberVisualizer.class);
        }
        number.addMetadata("min", (float) (setting.min() / divisor));
        number.addMetadata("max", (float) (setting.max() / divisor));
        number.addMetadata("step", (float) (setting.step() / divisor));
        return number;
    }

    private static int snap(SettingEntry setting, int value) {
        int clamped = Math.clamp(value, setting.min(), setting.max());
        return Math.clamp(setting.min() + Math.round((float) (clamped - setting.min()) / setting.step()) * setting.step(),
            setting.min(), setting.max());
    }

    private Property<OneConfigKeybind> keybind(String key, String label, String description, SettingEntry setting) {
        var bridge = new KungOneConfigKeybind(setting);
        var property = property(key, label, description, bridge, bridge, OneConfigKeybind.class,
            Visualizer.KeybindVisualizer.class);
        property.addMetadata("singleKey", true);
        // Kung handles these only in the loadout menu; they must not become global OneConfig key actions.
        property.addMetadata("oc_no_mc_mirror", true);
        return property;
    }

    private Property<Void> button(String id, String title, String description, String text, Runnable action) {
        Property<Void> property = Properties.dummy(id, title, description);
        property.addMetadata("visualizer", Visualizer.ButtonVisualizer.class);
        property.addMetadata("text", text);
        property.addMetadata("runnable", (Runnable) () -> {
            if (writesAllowed.getAsBoolean()) { action.run(); refresh(); }
        });
        return property;
    }

    private <T> Property<T> property(String id, String title, String description, Supplier<T> getter,
                                      Consumer<T> setter, Class<T> type, Class<?> visualizer) {
        Property<T> property = Properties.functional(getter, value -> {
            if (writesAllowed.getAsBoolean() && !Objects.equals(getter.get(), value)) {
                setter.accept(value);
                refresh();
            }
        }, id, title, description, type);
        if (visualizer != null) property.addMetadata("visualizer", visualizer);
        return property;
    }

    private void put(Property<?> property, String category, String feature) {
        property.addMetadata("category", category);
        property.addMetadata("subcategory", feature);
        property.addMetadata("searchTags", List.of("Kung", category, feature));
        if (tree.map.containsKey(property.getID())) throw new IllegalArgumentException("Duplicate Kung setting: " + property.getID());
        tree.put(property);
    }

    private static String id(String label) {
        // Escape every non-ASCII identifier character, including '_' itself, so file-name groups cannot collide.
        StringBuilder result = new StringBuilder();
        label.codePoints().forEach(code -> {
            if (code >= 'a' && code <= 'z' || code >= 'A' && code <= 'Z' || code >= '0' && code <= '9') result.appendCodePoint(code);
            else result.append('_').append(Integer.toHexString(code)).append('_');
        });
        return result.toString();
    }
}
