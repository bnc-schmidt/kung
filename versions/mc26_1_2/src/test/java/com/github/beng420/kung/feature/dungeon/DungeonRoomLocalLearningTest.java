package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.runtime.KungFileLayout;
import com.github.beng420.kung.runtime.KungPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DungeonRoomLocalLearningTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void laterLearnDoesNotEraseEarlierLearnWhileLocalRecognitionIsDisabled() throws Exception {
        var previousConfig = KungConfig.get().dungeon;
        var layoutField = KungPaths.class.getDeclaredField("resolvedLayout");
        layoutField.setAccessible(true);
        var previousLayout = layoutField.get(null);
        var root = temporary.getRoot().toPath();
        try {
            layoutField.set(null, new KungFileLayout(root, root.resolve("config")));
            KungConfig.get().dungeon = new DungeonConfig();
            KungConfig.get().dungeon.onChange(() -> {});
            DungeonKnownRoomCatalog.reload();
            assertFalse(KungConfig.get().dungeon.localRoomDataEnabled());
            long time = 1789766765125L;
            var current = new DungeonKnownRoomCatalog.LearnedRoom(time,
                "Local learning test", RoomType.PUZZLE, 1, 0, 123456789, 987654321, 0, 5);
            var initial = new DungeonKnownRoomCatalog.LearnedRoom(time + 1,
                current.name(), current.type(), 1, 0, 123456788, 987654320, 0, 5);
            DungeonKnownRoomCatalog.append(current);
            DungeonKnownRoomCatalog.append(initial);
            var file = KungPaths.dungeonDataDirectory().resolve("known-rooms.json");
            String saved = Files.readString(file);
            assertTrue("The current hash must survive saving the initial hash", saved.contains("123456789"));
            assertTrue(saved.contains("123456788"));
            assertNull("Saving must not enable local recognition", DungeonKnownRoomCatalog.knownCoreHint(current.coreHash()));
            DungeonKnownRoomCatalog.updateRoomCrypts(current.name(), current.type(), 1, 2);
            assertTrue(DungeonKnownRoomCatalog.deleteRoom("Quiz").deleted());
            saved = Files.readString(file);
            assertTrue("Other edits must preserve local observations too", saved.contains("123456789"));
            assertTrue(saved.contains("123456788"));
            KungConfig.get().dungeon.setLocalRoomDataEnabled(true);
            DungeonKnownRoomCatalog.reload();
            assertEquals(current.name(), DungeonKnownRoomCatalog.knownCoreHint(current.coreHash()).name());
            assertEquals(2, DungeonKnownRoomCatalog.knownCoreHint(current.coreHash()).crypts());
            assertEquals(initial.name(), DungeonKnownRoomCatalog.knownCoreHint(initial.coreHash()).name());
        } finally {
            KungConfig.get().dungeon = previousConfig;
            layoutField.set(null, previousLayout);
            DungeonKnownRoomCatalog.reload();
        }
    }

    @Test public void undoRemovesOnlyTheLatestLearnBurst() throws Exception {
        var previousConfig = KungConfig.get().dungeon;
        var layoutField = KungPaths.class.getDeclaredField("resolvedLayout");
        layoutField.setAccessible(true);
        var previousLayout = layoutField.get(null);
        var root = temporary.getRoot().toPath();
        try {
            layoutField.set(null, new KungFileLayout(root, root.resolve("config")));
            KungConfig.get().dungeon = new DungeonConfig();
            KungConfig.get().dungeon.onChange(() -> {});
            DungeonKnownRoomCatalog.reload();
            long time = System.currentTimeMillis() + 86_400_000L; // Later than any learn timestamp in the bundled data.
            DungeonKnownRoomCatalog.append(learned("Undo test A", RoomType.PUZZLE, time, 111, 0));
            DungeonKnownRoomCatalog.append(learned("Undo test B", RoomType.NORMAL, time + 20_000, 211, 0));
            DungeonKnownRoomCatalog.appendAll(List.of(
                learned("Undo test B", RoomType.NORMAL, time + 40_000, 311, 0),
                learned("Undo test B", RoomType.NORMAL, time + 40_001, 321, 1)));
            var file = KungPaths.dungeonDataDirectory().resolve("known-rooms.json");

            var burst = DungeonKnownRoomCatalog.undoLast();
            assertTrue(burst.undone());
            assertTrue(burst.message(), burst.message().contains("Undo test B") && burst.message().contains("hashes=2"));
            var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertTrue(hasHash(saved, 111) && hasHash(saved, 211));
            assertFalse(hasHash(saved, 311) || hasHash(saved, 321));

            var single = DungeonKnownRoomCatalog.undoLast();
            assertTrue(single.message(), single.message().contains("Undo test B") && single.message().contains("hashes=1"));
            saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertTrue(hasHash(saved, 111));
            assertFalse(hasHash(saved, 211));
            assertTrue("An emptied room must be removed", saved.getAsJsonArray("rooms").asList().stream()
                .noneMatch(room -> room.getAsJsonObject().get("name").getAsString().equals("Undo test B")));

            assertTrue(DungeonKnownRoomCatalog.undoLast().message().contains("Undo test A"));
            assertFalse(hasHash(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), 111));
        } finally {
            KungConfig.get().dungeon = previousConfig;
            layoutField.set(null, previousLayout);
            DungeonKnownRoomCatalog.reload();
        }
    }

    private static DungeonKnownRoomCatalog.LearnedRoom learned(String name, RoomType type, long order, int core, int cellX) {
        return new DungeonKnownRoomCatalog.LearnedRoom(order, name, type, 1, 0, core, core + 1, cellX, 5);
    }

    private static boolean hasHash(JsonObject root, int core) {
        return root.getAsJsonArray("rooms").asList().stream()
            .flatMap(room -> room.getAsJsonObject().getAsJsonArray("variants").asList().stream())
            .flatMap(variant -> variant.getAsJsonObject().getAsJsonArray("components").asList().stream())
            .flatMap(component -> component.getAsJsonObject().getAsJsonArray("hashes").asList().stream())
            .anyMatch(hash -> hash.getAsJsonObject().get("core").getAsInt() == core);
    }
}
