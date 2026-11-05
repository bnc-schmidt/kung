package com.github.beng420.kung.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runtime writes stay below config/kung or logs/kung; resolving paths never creates them. */
public record KungFileLayout(Path gameDirectory, Path configDirectory) {
    public KungFileLayout {
        gameDirectory = gameDirectory.toAbsolutePath().normalize();
        configDirectory = configDirectory.toAbsolutePath().normalize();
    }

    public Path settingsDirectory() { return configDirectory.resolve("kung"); }
    public Path dungeonDataDirectory() { return settingsDirectory().resolve("dungeon-data"); }
    public Path slayerDataDirectory() { return settingsDirectory().resolve("slayer-data"); }
    public Path soundsDirectory() { return settingsDirectory().resolve("custom-sounds"); }
    public Path fontsDirectory() { return settingsDirectory().resolve("fonts"); }
    public Path updateDirectory() { return settingsDirectory().resolve("updates"); }
    public Path logDirectory() { return gameDirectory.resolve("logs/kung"); }

    public List<String> migrateLegacyDirectories() {
        var warnings = new ArrayList<String>();
        migrateLegacy("kung-dungeon-scans", dungeonDataDirectory(), warnings);
        migrateLegacy("kung-custom-sounds", soundsDirectory(), warnings);
        migrateLegacy("kung-debug", logDirectory(), warnings);
        migrateLegacy("kung-updates", updateDirectory(), warnings);
        return List.copyOf(warnings);
    }

    private void migrateLegacy(String name, Path destination, List<String> warnings) {
        Path source = gameDirectory.resolve(name).normalize();
        // Both roots are explicit, and a custom config directory must not nest inside the source.
        if (!source.getParent().equals(gameDirectory) || destination.startsWith(source)
            || !(destination.startsWith(settingsDirectory()) || destination.equals(logDirectory()))) {
            warnings.add("Cannot relocate " + source + " into " + destination);
            return;
        }
        try {
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected a directory; preserved " + source);
            }
            migrate(source, destination, settingsDirectory().resolve("legacy").resolve(name));
        } catch (IOException exception) {
            warnings.add("Could not finish relocating " + name + ": " + exception.getMessage());
        }
    }

    private static void migrate(Path source, Path destination, Path conflict) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(destination)) {
            throw new IOException("Symbolic link preserved: " + source);
        }
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination);
            return;
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
            && Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.newDirectoryStream(source)) {
                for (Path entry : entries) {
                    migrate(entry, destination.resolve(entry.getFileName()), conflict.resolve(entry.getFileName()));
                }
            }
            Files.delete(source); // Only succeeds after every child was preserved elsewhere.
        } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
            && Files.mismatch(source, destination) == -1L) {
            Files.delete(source);
        } else {
            // Keep the current destination and preserve different old content without overwriting it.
            Files.createDirectories(conflict.getParent());
            Path available = conflict;
            for (int index = 1; Files.exists(available, LinkOption.NOFOLLOW_LINKS); index++) {
                available = conflict.resolveSibling(conflict.getFileName() + "." + index);
            }
            Files.move(source, available);
        }
    }
}
