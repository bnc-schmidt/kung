package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.config.KungSettings.CategoryEntry;
import com.github.beng420.kung.config.KungSettings.FeatureEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pins what the config files and menus look like from outside: the saved kung.json keys and values, and the
 * settings tree (labels, tooltips, order, defaults). Refactors of the config classes must leave both untouched.
 * A mismatch writes the actual text to {@code golden-actual/} next to the test working directory.
 */
public final class ConfigGoldenTest {
    private static final String[] INPUTS = {"dev", "legacy", "chat-matrix", "chat-legacy", "chat-legacy-ca50"};
    /** Keys of fields nobody read; the snapshots ignore them so the cleanup may drop the field. */
    private static final List<String> RETIRED = List.of("dungeonMap.showHeader", "slayer.tarantulaDebugEggSacLearning");

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final List<String> mismatches = new ArrayList<>();

    @After
    public void everySnapshotMatched() {
        assertEquals("snapshots that differ (actual text is in golden-actual/)", List.of(), mismatches);
    }

    @Test
    public void defaultsSaveTheSameKeysAndValues() throws IOException {
        Path file = temporary.getRoot().toPath().resolve("defaults.json");
        new KungConfig(file).load();
        assertGolden("defaults.saved.txt", flatten(Files.readString(file)));
    }

    @Test
    public void loadedFilesSaveTheSameKeysAndValues() throws IOException {
        for (String input : INPUTS) {
            Path file = temporary.getRoot().toPath().resolve(input + "-in.json");
            Files.writeString(file, resource(input + ".json"));
            KungConfig config = new KungConfig(file);
            config.load();
            config.save();
            assertGolden(input + ".saved.txt", flatten(Files.readString(file)));
        }
    }

    @Test
    public void retiredKeysLoadFromOldFilesAndAreNotSavedAgain() throws IOException {
        Path file = temporary.getRoot().toPath().resolve("retired.json");
        Files.writeString(file, resource("legacy.json"));
        KungConfig config = new KungConfig(file);
        config.load();
        config.save();
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (String key : RETIRED) {
            String[] parts = key.split("\\.");
            assertEquals(key + " is gone from the saved file", false, saved.getAsJsonObject(parts[0]).has(parts[1]));
        }
    }

    @Test
    public void settingsTreeKeepsLabelsTooltipsOrderAndValues() throws IOException {
        Assume.assumeFalse("snapshot is of the full flavor", KungBuild.MODRINTH);
        assertGolden("defaults.tree.txt", tree(KungConfig.read(new StringReader("{}"))));
        for (String input : INPUTS) {
            assertGolden(input + ".tree.txt", tree(KungConfig.read(new StringReader(resource(input + ".json")))));
        }
    }

    @Test
    public void everySettingWritesItsOwnKey() throws IOException {
        Assume.assumeFalse("snapshot is of the full flavor", KungBuild.MODRINTH);
        for (boolean high : new boolean[] {true, false}) {
            Path file = temporary.getRoot().toPath().resolve("flip-" + high + ".json");
            KungConfig config = new KungConfig(file);
            List<String> problems = new ArrayList<>();
            for (CategoryEntry category : KungSettings.categories(config, () -> { }, true)) {
                for (FeatureEntry feature : category.features()) {
                    String path = category.name() + "/" + feature.name();
                    attempt(problems, path, () -> {
                        if (feature.clickable() && !feature.actionOnly()) feature.toggle().run();
                    });
                    flipAll(feature.settings(), path, high, problems);
                }
            }
            config.save();
            Collections.sort(problems);
            assertGolden("flip-" + (high ? "high" : "low") + ".saved.txt",
                flatten(Files.readString(file)) + "\n" + String.join("\n", problems));
        }
    }

    private static void flipAll(List<SettingEntry> settings, String parent, boolean high, List<String> problems) {
        for (SettingEntry setting : settings) {
            String path = parent + "/" + setting.label();
            attempt(problems, path, () -> {
                switch (setting.kind()) {
                    case TOGGLE -> setting.toggle().run();
                    case SLIDER -> setting.intConsumer().accept(high ? setting.max() : setting.min());
                    case CHOICE -> {
                        if (high) setting.cycleChoice().run();
                    }
                    case TEXT -> setting.setText(high ? " golden text, 7 " : "5");
                    case KEYBIND -> setting.setText(high ? "key:70:0" : "mouse:3");
                    default -> { }
                }
            });
            flipAll(setting.children(), path, high, problems);
        }
    }

    private static void attempt(List<String> problems, String path, Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            problems.add("! " + path + " -> " + thrown.getClass().getSimpleName());
        }
    }

    private static String tree(KungConfig config) {
        StringBuilder out = new StringBuilder();
        for (CategoryEntry category : KungSettings.categories(config, () -> { }, true)) {
            out.append("# ").append(category.name()).append('\n');
            for (FeatureEntry feature : category.features()) {
                out.append("  * ").append(feature.name())
                    .append(" enabled=").append(feature.enabled())
                    .append(" clickable=").append(feature.clickable())
                    .append(" actionOnly=").append(feature.actionOnly())
                    .append(" pinned=").append(feature.alwaysExpanded())
                    .append(" dev=").append(feature.developerOnly())
                    .append(" tip=").append(feature.tooltip()).append('\n');
                rows(feature.settings(), "    ", out);
            }
        }
        return out.toString();
    }

    private static void rows(List<SettingEntry> settings, String indent, StringBuilder out) {
        for (SettingEntry row : settings) {
            out.append(indent).append(row.kind()).append(' ').append(row.label())
                .append(" visible=").append(guard(row::visible))
                .append(" dev=").append(row.developerOnly())
                .append(" value=").append(value(row))
                .append(" tip=").append(row.tooltip());
            if (row.rowToggle() != null) {
                out.append(" rowToggle=").append(row.rowToggle().label())
                    .append('=').append(guard(() -> row.rowToggle().booleanSupplier().getAsBoolean()))
                    .append(' ').append(row.rowToggle().tooltip());
            }
            out.append(" resettable=").append(guard(row::resettable)).append('\n');
            rows(row.children(), indent + "  ", out);
        }
    }

    private static String value(SettingEntry row) {
        return guard(() -> switch (row.kind()) {
            case TOGGLE -> String.valueOf(row.booleanSupplier().getAsBoolean());
            case SLIDER -> row.intSupplier().getAsInt() + " [" + row.min() + ".." + row.max() + " step " + row.step()
                + "] " + row.sliderValue();
            case CHOICE -> row.choiceSupplier().get() + " of " + row.choices();
            case TEXT, KEYBIND -> row.textValue() + (row.rawKeybindSupplier() == null ? ""
                : " raw=" + row.rawKeybindSupplier().get());
            case COLOR, COLOR_REMOVE, BUTTON -> row.choiceSupplier().get();
            default -> "";
        });
    }

    private static String guard(Supplier<?> supplier) {
        try {
            return String.valueOf(supplier.get());
        } catch (Throwable thrown) {
            return "!" + thrown.getClass().getSimpleName();
        }
    }

    /** One sorted "path=value" line per leaf, so the snapshot ignores key order but sees every key and value. */
    private static String flatten(String json) {
        List<String> lines = new ArrayList<>();
        flatten("", JsonParser.parseString(json), lines);
        lines.removeIf(line -> RETIRED.stream().anyMatch(key -> line.startsWith(key + "=")));
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static void flatten(String path, JsonElement node, List<String> lines) {
        if (node instanceof JsonObject object) {
            if (object.isEmpty()) lines.add(path + "={}");
            object.entrySet().forEach(entry -> flatten(path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                entry.getValue(), lines));
        } else if (node instanceof JsonArray array) {
            if (array.isEmpty()) lines.add(path + "=[]");
            for (int index = 0; index < array.size(); index++) flatten(path + "[" + index + "]", array.get(index), lines);
        } else {
            lines.add(path + "=" + node);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = ConfigGoldenTest.class.getResourceAsStream("/config/golden/" + name)) {
            if (stream == null) throw new IOException("missing test resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertGolden(String name, String actual) throws IOException {
        // Folder tooltips name the test working directory: a release worktree sits elsewhere than the main checkout.
        String work = Path.of("").toAbsolutePath().toString();
        String text = actual.replace(work, "<test-run>");
        if (!text.endsWith("\n")) text += "\n";
        String expected = null;
        try {
            expected = resource(name).replace("\r\n", "\n");
        } catch (IOException missing) { /* first run: the actual text is written below */ }
        if (text.equals(expected)) return;
        Path out = Path.of("golden-actual", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
        mismatches.add(name);
    }
}
