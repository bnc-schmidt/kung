package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.runtime.KungPaths;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DungeonRoomProject {
    private static final String DATA_DIRECTORY = "versions/mc26_1_2/src/main/resources/kung-dungeon-scans";

    private DungeonRoomProject() {
    }

    public static boolean enabled() {
        return !KungConfig.get().dungeon.roomDataProjectDirectory().isBlank();
    }

    public static Path dataDirectory() {
        String project = KungConfig.get().dungeon.roomDataProjectDirectory();
        return project.isBlank() ? KungPaths.dungeonDataDirectory() : Path.of(project).resolve(DATA_DIRECTORY);
    }

    /** Validates the project linked in the Dungeon settings. */
    public static Path validateProject() throws IOException {
        return validateProject(KungConfig.get().dungeon.roomDataProjectDirectory());
    }

    public static Path validateProject(String root) throws IOException {
        try {
            if (root == null || root.isBlank()) throw new IOException("Specify the Kung project directory.");
            Path project = Path.of(root.strip()).toRealPath();
            if (!Files.isDirectory(project)) throw new IOException("The project path is not a directory.");
            Path roomsFile = project.resolve(DATA_DIRECTORY).resolve("known-rooms.json");
            if (!Files.isRegularFile(roomsFile) || !roomsFile.toRealPath().startsWith(project)) {
                throw new IOException("The project must contain the active mc26_1_2 known-rooms.json.");
            }
            var json = JsonParser.parseString(Files.readString(roomsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            var schema = json.get("schema");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                || schema.getAsDouble() != 1 || !json.has("rooms") || !json.get("rooms").isJsonArray()) {
                throw new IOException("The project room database must use schema 1 and a rooms array.");
            }
            return project;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Kung project or room database.", exception);
        }
    }

    public static void write(Path file, String text) throws IOException {
        if (!enabled()) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return;
        }
        // A moved/deleted checkout must fail instead of creating a replacement project tree.
        if (!Files.isDirectory(file.getParent())) throw new IOException("The project data directory is unavailable.");
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
