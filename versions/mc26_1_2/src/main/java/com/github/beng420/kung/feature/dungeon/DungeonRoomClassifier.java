package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class DungeonRoomClassifier {
    public static final int EMPTY_CORE_HASH = -318865360;
    private static final String BUNDLED_ROOM_TYPES_RESOURCE = "/kung-dungeon-scans/known-room-types.properties";

    private static Map<Integer, RoomType> knownRoomTypes;

    private DungeonRoomClassifier() {
    }

    public static RoomType classifyRoom(int coreHash) {
        if (isEmptyCore(coreHash)) {
            return RoomType.UNKNOWN;
        }
        return knownRoomTypes().getOrDefault(coreHash, RoomType.NORMAL);
    }

    public static boolean isEmptyCore(int coreHash) {
        return coreHash == 0 || coreHash == EMPTY_CORE_HASH;
    }

    public static void reload() {
        knownRoomTypes = null;
    }

    public static void learnRoomType(int coreHash, RoomType roomType) throws IOException {
        if (isEmptyCore(coreHash)) {
            throw new IllegalArgumentException("Cannot learn an empty dungeon room core.");
        }

        Path file = knownRoomTypesFile();

        Properties properties = readProperties(file);

        properties.setProperty(Integer.toString(coreHash), roomType.name());
        writeRoomTypes(file, properties);
        knownRoomTypes().put(coreHash, roomType);
    }

    public static void removeRoomTypes(Collection<Integer> coreHashes) throws IOException {
        if (coreHashes == null || coreHashes.isEmpty()) {
            return;
        }

        if (DungeonRoomProject.enabled()) {
            DungeonRoomProject.validateProject(KungConfig.get().dungeon.roomDataProjectDirectory());
        }

        Path file = knownRoomTypesFile();
        Properties properties = readProperties(file);
        boolean changed = false;
        for (int coreHash : coreHashes) {
            changed = properties.remove(Integer.toString(coreHash)) != null || changed;
        }
        if (changed) writeRoomTypes(file, properties);
        for (int coreHash : coreHashes) knownRoomTypes().remove(coreHash);
    }

    private static Map<Integer, RoomType> knownRoomTypes() {
        if (knownRoomTypes == null) {
            knownRoomTypes = loadKnownRoomTypes();
        }
        return knownRoomTypes;
    }

    private static Map<Integer, RoomType> loadKnownRoomTypes() {
        boolean project = DungeonRoomProject.enabled();
        Map<Integer, RoomType> roomTypes = new HashMap<>();
        if (!project) {
            loadPropertiesInto(roomTypes, bundledProperties());
            if (!KungConfig.get().dungeon.localRoomDataEnabled()) return roomTypes;
        }
        Path file = knownRoomTypesFile();

        try {
            loadPropertiesInto(roomTypes, readProperties(file));
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Failed to load known dungeon room types from {}.", file.toAbsolutePath(), exception);
        }

        return roomTypes;
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static Properties bundledProperties() {
        Properties properties = new Properties();
        try (InputStream stream = DungeonRoomClassifier.class.getResourceAsStream(BUNDLED_ROOM_TYPES_RESOURCE)) {
            if (stream == null) {
                return properties;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Failed to load bundled dungeon room types.", exception);
        }
        return properties;
    }

    private static void loadPropertiesInto(Map<Integer, RoomType> roomTypes, Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            int coreHash = Integer.parseInt(key.trim());
            RoomType roomType = RoomType.valueOf(properties.getProperty(key).trim().toUpperCase());
            // Old profiles saved rare rooms as NORMAL before RARE existed.
            roomTypes.merge(coreHash, roomType,
                (previous, next) -> previous == RoomType.RARE && next == RoomType.NORMAL ? previous : next);
        }
    }

    private static void writeRoomTypes(Path file, Properties properties) throws IOException {
        if (DungeonRoomProject.enabled()) {
            DungeonRoomProject.validateProject(KungConfig.get().dungeon.roomDataProjectDirectory());
        }
        StringWriter writer = new StringWriter();
        properties.store(writer,
            "Kung dungeon scan room type hints. Format: coreHash=ROOM_TYPE. Types: START, NORMAL, RARE, YELLOW, PUZZLE, BLOOD, FAIRY, TRAP, UNKNOWN.");
        DungeonRoomProject.write(file, writer.toString());
    }

    private static Path knownRoomTypesFile() {
        return DungeonRoomProject.dataDirectory().resolve("known-room-types.properties");
    }
}
