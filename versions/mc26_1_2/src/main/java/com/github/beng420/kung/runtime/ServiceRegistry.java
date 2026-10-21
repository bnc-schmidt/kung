package com.github.beng420.kung.runtime;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.BloodRushHelperFeature;
import com.github.beng420.kung.feature.screen.ScreenTracker;
import com.github.beng420.kung.skyblock.HypixelGuildTracker;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import com.github.beng420.kung.skyblock.JerryPerkpocalypse;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import com.github.beng420.kung.util.CatacombsRecentXpTracker;

public final class ServiceRegistry {
    private static boolean initialized;

    private ServiceRegistry() {
    }

    public static void initializeClient(AppServices services) {
        if (initialized) {
            return;
        }

        HypixelInstanceTracker.initializeClient();
        HypixelPartyTracker.initializeClient();
        HypixelGuildTracker.initializeClient();
        SkyBlockMayorTracker.initializeClient();
        JerryPerkpocalypse.initializeClient();
        CatacombsRecentXpTracker.initializeClient();
        services.dungeonStateTracker().initializeClient();
        ScreenTracker.initializeClient();
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.initialize(services);
        initialized = true;
    }

    public static void shutdown() {
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.shutdown();
        initialized = false;
    }
}
