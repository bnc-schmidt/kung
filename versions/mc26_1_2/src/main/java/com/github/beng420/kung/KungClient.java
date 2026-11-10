package com.github.beng420.kung;

import com.github.beng420.kung.command.KungCommands;
import com.github.beng420.kung.compat.KungOneConfigBridge;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.FeatureRegistry;
import com.github.beng420.kung.feature.dungeon.BloodRushHelperFeature;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.feature.screen.ScreenTracker;
import com.github.beng420.kung.runtime.AppServices;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.skyblock.HypixelFriendTracker;
import com.github.beng420.kung.skyblock.HypixelGuildTracker;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import com.github.beng420.kung.skyblock.JerryPerkpocalypse;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import com.github.beng420.kung.update.KungUpdater;
import com.github.beng420.kung.util.CatacombsRecentXpTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class KungClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        for (String warning : KungPaths.fileLayout().migrateLegacyDirectories()) {
            KungMod.LOGGER.warn("Kung storage migration: {}", warning);
        }
        KungUpdater.INSTANCE.initializeInstallation();
        DungeonStateTracker dungeonStateTracker = new DungeonStateTracker();
        AppServices services = AppServices.create(KungConfig.INSTANCE, dungeonStateTracker);
        KungConfig.INSTANCE.load();
        HypixelInstanceTracker.initializeClient();
        HypixelPartyTracker.initializeClient();
        HypixelGuildTracker.initializeClient();
        HypixelFriendTracker.initializeClient();
        SkyBlockMayorTracker.initializeClient();
        JerryPerkpocalypse.initializeClient();
        CatacombsRecentXpTracker.initializeClient();
        dungeonStateTracker.initializeClient();
        ScreenTracker.initializeClient();
        if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.initialize(services);
        FeatureRegistry.initializeClient(services);
        KungOneConfigBridge.initialize(dungeonStateTracker);
        KungCommands.register(services);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            KungUpdater.INSTANCE.releaseNotes().flushAcknowledgement();
            FeatureRegistry.shutdown();
            if (!KungBuild.MODRINTH) BloodRushHelperFeature.INSTANCE.shutdown();
        });
        KungUpdater.INSTANCE.initializeClientNotifications();
        KungUpdater.INSTANCE.checkForUpdatesAsync();
        KungMod.LOGGER.info("Kung client entrypoint ready.");
    }
}
