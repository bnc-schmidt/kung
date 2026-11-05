package com.github.beng420.kung.runtime;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungFileLayoutTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void runtimePathsAlsoResolveBeforeFabricStarts() {
        var layout = KungPaths.fileLayout();
        assertEquals(layout.configDirectory().resolve("kung/dungeon-data"), KungPaths.dungeonDataDirectory());
        assertTrue(layout.gameDirectory().isAbsolute());
    }

    @Test public void freshStartupDoesNotCreateEmptyDataLogOrUpdateDirectories() throws Exception {
        var layout = layout();
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertEquals(layout.settingsDirectory().resolve("dungeon-data"), layout.dungeonDataDirectory());
        assertEquals(layout.gameDirectory().resolve("logs/kung"), layout.logDirectory());
        for (Path path : List.of(layout.settingsDirectory(), layout.dungeonDataDirectory(), layout.soundsDirectory(),
            layout.updateDirectory(), layout.logDirectory())) assertFalse(Files.exists(path));
    }

    @Test public void legacyFoldersMoveWithAllContentsAndMigrationIsRepeatable() throws Exception {
        var layout = layout();
        List<String> oldNames = List.of("kung-dungeon-scans", "kung-custom-sounds", "kung-debug", "kung-updates");
        List<Path> destinations = List.of(layout.dungeonDataDirectory(), layout.soundsDirectory(), layout.logDirectory(), layout.updateDirectory());
        for (String name : oldNames) put(layout.gameDirectory().resolve(name).resolve("nested/data.bin"), "original " + name);
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        for (int i = 0; i < oldNames.size(); i++) {
            assertFalse(Files.exists(layout.gameDirectory().resolve(oldNames.get(i))));
            assertEquals("original " + oldNames.get(i), Files.readString(destinations.get(i).resolve("nested/data.bin")));
        }
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertFalse(Files.exists(layout.settingsDirectory().resolve("legacy")));
    }

    @Test public void differingFilesArePreservedAndExistingDestinationsAlwaysWin() throws Exception {
        var layout = layout();
        var old = layout.gameDirectory().resolve("kung-custom-sounds");
        put(old.resolve("same.wav"), "identical");
        put(layout.soundsDirectory().resolve("same.wav"), "identical");
        put(old.resolve("changed.wav"), "old user sound");
        put(layout.soundsDirectory().resolve("changed.wav"), "current user sound");
        var conflict = layout.settingsDirectory().resolve("legacy/kung-custom-sounds/changed.wav");
        put(conflict, "older preserved sound");
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertEquals("current user sound", Files.readString(layout.soundsDirectory().resolve("changed.wav")));
        assertEquals("older preserved sound", Files.readString(conflict));
        assertEquals("old user sound", Files.readString(conflict.resolveSibling("changed.wav.1")));
        assertFalse(Files.exists(conflict.resolveSibling("same.wav")));
        assertFalse(Files.exists(old));
    }

    @Test public void fileDirectoryConflictsPreserveTheWholeOldSubtree() throws Exception {
        var layout = layout();
        put(layout.gameDirectory().resolve("kung-dungeon-scans/nested/record.json"), "learned room");
        put(layout.dungeonDataDirectory().resolve("nested"), "existing file");
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertEquals("existing file", Files.readString(layout.dungeonDataDirectory().resolve("nested")));
        assertEquals("learned room", Files.readString(layout.settingsDirectory()
            .resolve("legacy/kung-dungeon-scans/nested/record.json")));
    }

    @Test public void customConfigLocationWorksButCannotBeNestedInsideTheLegacySource() throws Exception {
        var game = temp.newFolder("game").toPath();
        var config = temp.newFolder("separate-config").toPath();
        var layout = new KungFileLayout(game, config);
        put(game.resolve("kung-dungeon-scans/known-rooms.json"), "rooms");
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertEquals("rooms", Files.readString(config.resolve("kung/dungeon-data/known-rooms.json")));
        put(game.resolve("kung-dungeon-scans/known-rooms.json"), "new rooms");
        var nested = new KungFileLayout(game, game.resolve("kung-dungeon-scans/config"));
        assertFalse(nested.migrateLegacyDirectories().isEmpty());
        assertEquals("new rooms", Files.readString(game.resolve("kung-dungeon-scans/known-rooms.json")));
    }

    @Test public void pendingUpdateSourceFollowsTheMovedDownloadButUnrelatedPathsDoNot() throws Exception {
        var layout = layout();
        var oldSource = layout.gameDirectory().resolve("kung-updates/kung-new.jar");
        put(oldSource, "downloaded jar");
        assertEquals(oldSource, relocatedUpdateSource(layout, oldSource));
        assertTrue(layout.migrateLegacyDirectories().isEmpty());
        assertEquals(layout.updateDirectory().resolve("kung-new.jar"), relocatedUpdateSource(layout, oldSource));
        var otherSource = layout.gameDirectory().resolve("other/kung-new.jar");
        assertEquals(otherSource, relocatedUpdateSource(layout, otherSource));
        var escaped = layout.gameDirectory().resolve("kung-updates/../other/kung-new.jar");
        assertEquals(escaped, relocatedUpdateSource(layout, escaped));
    }

    /** Where a pending update download is after the move out of kung-updates. */
    private static Path relocatedUpdateSource(KungFileLayout layout, Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        Path legacy = layout.gameDirectory().resolve("kung-updates");
        if (!normalized.startsWith(legacy) || Files.exists(normalized)) return source;
        Path relocated = layout.updateDirectory().resolve(legacy.relativize(normalized));
        return Files.isRegularFile(relocated) ? relocated : source;
    }

    private KungFileLayout layout() throws Exception {
        var game = temp.newFolder().toPath();
        return new KungFileLayout(game, game.resolve("config"));
    }

    private static void put(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
