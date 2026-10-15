package com.github.beng420.kung.runtime;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class KungPaths {
    private static volatile KungFileLayout resolvedLayout;
    private KungPaths() {
    }

    public static Path dungeonDataDirectory() {
        return fileLayout().dungeonDataDirectory();
    }

    public static KungFileLayout fileLayout() {
        KungFileLayout layout = resolvedLayout;
        if (layout != null) return layout;
        try {
            // getConfigDir touches disk; resolve it once, never in recurring room lookups.
            var loader = FabricLoader.getInstance();
            Path game = loader.getGameDir();
            layout = new KungFileLayout(game, loader.getConfigDir());
            resolvedLayout = layout;
            return layout;
        } catch (IllegalStateException exception) {
            // Pure model tests run before Fabric has initialized either directory.
            Path game = Path.of("").toAbsolutePath().normalize();
            return new KungFileLayout(game, game.resolve("config"));
        }
    }
}
