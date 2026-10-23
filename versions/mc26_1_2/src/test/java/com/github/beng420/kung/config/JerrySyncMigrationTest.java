package com.github.beng420.kung.config;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class JerrySyncMigrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path file() {
        return temp.getRoot().toPath().resolve("kung.json");
    }

    private static KungConfig load(Path file) {
        KungConfig config = new KungConfig(file);
        config.load();
        return config;
    }

    @Test public void aNewConfigStartsWithJerrySyncOnAndTheRoomSyncServicesOff() {
        KungConfig config = load(file());
        assertTrue(config.dungeon.jerrySyncEnabled());
        assertFalse(config.dungeon.roomSyncEnabled());
        assertFalse(config.dungeon.roomSyncUploadEnabled());
    }

    @Test public void aConfigFromBeforeSwitchesItOnOnceAndWritesThatBack() throws Exception {
        // 0.6.1 and 0.6.2 saved the old default: off.
        Files.writeString(file(), "{\"dungeonMap\":{\"jerrySyncEnabled\":false,\"roomSyncEnabled\":false}}");
        KungConfig config = load(file());
        assertTrue(config.dungeon.jerrySyncEnabled());
        assertFalse("other services are left alone", config.dungeon.roomSyncEnabled());
        String saved = Files.readString(file());
        assertTrue(saved, saved.contains("\"jerrySyncEnabled\": true"));
        assertTrue(saved, saved.contains("\"jerrySyncDefaultedOn\": true"));
    }

    @Test public void whoTurnsItOffAfterwardsKeepsItOffAcrossRestarts() throws Exception {
        Files.writeString(file(), "{\"dungeonMap\":{\"jerrySyncEnabled\":false}}");
        KungConfig first = load(file());
        assertTrue(first.dungeon.jerrySyncEnabled());
        first.dungeon.setJerrySyncEnabled(false);
        KungConfig second = load(file());
        assertFalse(second.dungeon.jerrySyncEnabled());
        assertFalse(load(file()).dungeon.jerrySyncEnabled());
    }
}
