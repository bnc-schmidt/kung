package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.config.KungConfig;

/** The shared lifecycle stays active; expensive consumers opt into their data sources. */
record DungeonWorkload(boolean rooms, boolean players, boolean roomSync) {
    static DungeonWorkload current() { return from(KungConfig.get()); }

    static DungeonWorkload from(KungConfig config) {
        var dungeon = config.dungeon;
        boolean sync = dungeon.roomSyncEnabled();
        boolean rooms = dungeon.enabled() || !KungBuild.MODRINTH && config.bloodRush.enabled()
            || dungeon.playerTrackingEnabled() || sync;
        boolean players = rooms || config.splits.enabled() || dungeon.secretsOverlayEnabled() || dungeon.wishAlertEnabled()
            || dungeon.deathMessagesEnabled() || dungeon.fiveCryptPartyMessageEnabled()
            || dungeon.extraScoreMessagesEnabled()
            || dungeon.cryptProgressPartyMessageEnabled() || dungeon.fiveCryptTitleEnabled()
            || dungeon.witherDragonsEnabled() && dungeon.dragonPrioEnabled();
        return new DungeonWorkload(rooms, players, sync);
    }
}
