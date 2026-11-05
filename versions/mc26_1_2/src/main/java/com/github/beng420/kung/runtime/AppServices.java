package com.github.beng420.kung.runtime;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import java.util.Objects;

public record AppServices(
    KungConfig config,
    DungeonStateTracker dungeonStateTracker
) {
    public AppServices {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dungeonStateTracker, "dungeonStateTracker");
    }

    public static AppServices create(KungConfig config, DungeonStateTracker dungeonStateTracker) {
        return new AppServices(config, dungeonStateTracker);
    }
}
