package com.github.beng420.kung.feature;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.DungeonChatFilterFeature;
import com.github.beng420.kung.feature.dungeon.ColoredPillarsFeature;
import com.github.beng420.kung.feature.dungeon.DungeonDebuffFeature;
import com.github.beng420.kung.feature.dungeon.M7DragonFeature;
import com.github.beng420.kung.feature.dungeon.DungeonMapFeature;
import com.github.beng420.kung.feature.dungeon.DungeonMimicEspFeature;
import com.github.beng420.kung.feature.dungeon.DungeonPbTitleFeature;
import com.github.beng420.kung.feature.dungeon.DungeonQuizReminderFeature;
import com.github.beng420.kung.feature.dungeon.DungeonSplitsOverlayFeature;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.feature.garden.FeastOverlayFeature;
import com.github.beng420.kung.feature.garden.GreenhouseHologram;
import com.github.beng420.kung.feature.garden.VisitorAlarmFeature;
import com.github.beng420.kung.feature.hunting.InvisibugLineFeature;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature;
import com.github.beng420.kung.feature.misc.BowDrawIndicatorFeature;
import com.github.beng420.kung.feature.misc.RagTickTimerFeature;
import com.github.beng420.kung.feature.misc.FrozenBlazeHudFeature;
import com.github.beng420.kung.feature.misc.PeekChatFeature;
import com.github.beng420.kung.feature.misc.PerformanceHudFeature;
import com.github.beng420.kung.feature.misc.SackTrackerFeature;
import com.github.beng420.kung.feature.misc.ChatEmotesFeature;
import com.github.beng420.kung.feature.misc.CustomSoundsFeature;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.feature.misc.LobbyHopHelperFeature;
import com.github.beng420.kung.feature.misc.NpcDialogueSkipFeature;
import com.github.beng420.kung.feature.misc.HitboxesFeature;
import com.github.beng420.kung.feature.misc.SuperpairsHelperFeature;
import com.github.beng420.kung.feature.slayer.BlazeSlayerFeature;
import com.github.beng420.kung.feature.slayer.TarantulaHelperFeature;
import com.github.beng420.kung.feature.safari.SafariOverlayFeature;
import com.github.beng420.kung.runtime.AppServices;
import java.util.ArrayList;
import java.util.List;

public final class FeatureRegistry {
    private static final List<Feature> FEATURES = new ArrayList<>();
    private static boolean bootstrapped;

    private FeatureRegistry() {
    }

    public static void initializeClient(AppServices services) {
        if (bootstrapped) {
            return;
        }

        DungeonStateTracker tracker = services.dungeonStateTracker();
        register(new DungeonMapFeature(tracker));
        if (!KungBuild.MODRINTH) register(new DungeonMimicEspFeature(tracker));
        register(new DungeonChatFilterFeature(tracker));
        register(new DungeonSplitsOverlayFeature(tracker));
        register(DungeonDebuffFeature.INSTANCE);
        register(DungeonQuizReminderFeature.INSTANCE);
        register(DungeonPbTitleFeature.INSTANCE);
        register(ColoredPillarsFeature.INSTANCE);
        register(M7DragonFeature.INSTANCE);
        register(ChatCommandsFeature.INSTANCE);
        register(new ChatEmotesFeature());
        register(CustomSoundsFeature.INSTANCE);
        register(LoadoutsAutoCloseFeature.INSTANCE);
        register(LobbyHopHelperFeature.INSTANCE);
        register(new HitboxesFeature());
        register(SuperpairsHelperFeature.INSTANCE);
        if (!KungBuild.MODRINTH) register(NpcDialogueSkipFeature.INSTANCE);
        register(BowDrawIndicatorFeature.INSTANCE);
        register(FrozenBlazeHudFeature.INSTANCE);
        register(RagTickTimerFeature.INSTANCE);
        register(PerformanceHudFeature.INSTANCE);
        register(SackTrackerFeature.INSTANCE);
        register(PeekChatFeature.INSTANCE);
        register(TarantulaHelperFeature.INSTANCE);
        register(BlazeSlayerFeature.INSTANCE);
        register(new SafariOverlayFeature());
        register(InvisibugLineFeature.INSTANCE);
        register(FeastOverlayFeature.INSTANCE);
        register(VisitorAlarmFeature.INSTANCE);
        GreenhouseHologram.initialize();

        bootstrapped = true;
        FEATURES.forEach(feature -> feature.initialize(services));
    }

    public static void shutdown() {
        for (int index = FEATURES.size() - 1; index >= 0; index--) {
            FEATURES.get(index).shutdown();
        }
        FEATURES.clear();
        bootstrapped = false;
    }

    private static void register(Feature feature) {
        FEATURES.add(feature);
    }
}
