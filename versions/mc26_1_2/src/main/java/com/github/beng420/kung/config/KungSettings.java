package com.github.beng420.kung.config;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig.DragonAimMode;
import com.github.beng420.kung.config.category.DungeonConfig.DragonDebuffScope;
import com.github.beng420.kung.config.category.DungeonConfig.DragonMarkerMode;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPart;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPrioSplit;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPrioUnit;
import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import com.github.beng420.kung.config.category.GreenhouseConfig;
import com.github.beng420.kung.config.category.SplitsConfig.PredictionMode;
import com.github.beng420.kung.config.category.SplitsConfig.PredictionSource;
import com.github.beng420.kung.config.category.SplitsConfig.TimeFormat;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog;
import com.github.beng420.kung.feature.dungeon.DungeonRoomClassifier;
import com.github.beng420.kung.feature.dungeon.DungeonRoomDataSyncClient;
import com.github.beng420.kung.feature.misc.CustomSoundsFeature;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.github.beng420.kung.ui.KungFonts;
import com.github.beng420.kung.update.KungUpdater;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

/** Shared settings and callbacks; persistence remains owned by KungConfig. */
public final class KungSettings {
    private static final URI HYPIXEL_DEVELOPER_DASHBOARD = URI.create("https://developer.hypixel.net/dashboard");

    private KungSettings() { }

    public static List<CategoryEntry> categories(Runnable openChangelog) {
        return categories(KungConfig.get(), openChangelog);
    }

    public static List<CategoryEntry> defaults() {
        return categories(KungConfig.read(new StringReader("{}")), () -> { });
    }

    static List<CategoryEntry> categories(KungConfig config, Runnable openChangelog) {
        return categories(config, openChangelog, KungDeveloperAccess.allowed());
    }

    static List<CategoryEntry> categories(KungConfig config, Runnable openChangelog, boolean developer) {
        List<CategoryEntry> all = allCategories(config, openChangelog, developer);
        if (developer) return all;
        // forDeveloper() puts a star on the row; for everyone else the row is simply not there.
        return all.stream()
            .map(category -> new CategoryEntry(category.name(),
                category.features().stream().filter(feature -> !feature.developerOnly()).toList()))
            .toList();
    }

    private static List<CategoryEntry> allCategories(KungConfig config, Runnable openChangelog, boolean developer) {
        return List.of(
            new CategoryEntry("Dungeon", present(
                new FeatureEntry("Dungeon Map", config.dungeon::enabled,
                    config.dungeon::setEnabled,
                    dungeonMapSettings(config, developer)
                ).withTooltip("Live dungeon map with room states, secrets, score and player positions.",
                    "Move and resize in /kung hud."),
                new FeatureEntry("Ice Spray Highlight", config.dungeon::iceSprayHighlightEnabled,
                    config.dungeon::setIceSprayHighlightEnabled, List.of(
                        SettingEntry.slider("Box Size (%)", config.dungeon::iceSprayBoxSize,
                            config.dungeon::setIceSprayBoxSize, 100, 200, 5)
                            .withTooltip("100% = normal size; 200% = twice the width, height and depth.",
                                "Expands the highlight around the mob's center.")
                    ))
                    .withTooltip("Light-blue mob boxes with 20% fill for observed Ice Spray effects.",
                        "Includes other players' sprays. Overlapping or missing ice markers stay unknown."),
                new FeatureEntry("Wither Dragons", config.dungeon::witherDragonsEnabled,
                    config.dungeon::setWitherDragonsEnabled,
                    witherDragonSettings(config, developer))
                    .withTooltip("Everything for the M7 Wither Dragons in one place."),
                new FeatureEntry("Wish Alert", config.dungeon::wishAlertEnabled,
                    config.dungeon::setWishAlertEnabled,
                    wishAlertSettings(config, developer))
                    .withTooltip("Healer only: alerts when your ultimate is ready and a boss enrages",
                        "or a teammate drops below the Low HP threshold.",
                        "Teammate HP comes from the sidebar; 100% is the most HP seen this run.",
                        "Stays silent while Wish is on cooldown."),
                new FeatureEntry("Colored F7/M7 Pillars", config.dungeon::coloredPillarsEnabled,
                    config.dungeon::setColoredPillarsEnabled, List.of(
                        SettingEntry.choice("Material", PillarMaterial.choices(), config.dungeon::pillarMaterial,
                            config.dungeon::setPillarMaterial, PillarMaterial::label)
                    ))
                    .withTooltip("Show Storm's crush pillars in their matching colors in F7 and M7."),
                new FeatureEntry("Run Reports", config.dungeon::playerTrackingEnabled,
                    config.dungeon::setPlayerTrackingEnabled,
                    List.of(
                        SettingEntry.toggle("Run Stats", config.dungeon::runStatsReportEnabled,
                            config.dungeon::setRunStatsReportEnabled)
                            .withTooltip("Rooms, score, secrets and crypts of the whole run."),
                        SettingEntry.toggle("Player Stats", config.dungeon::playerStatsReportEnabled,
                            config.dungeon::setPlayerStatsReportEnabled)
                            .withTooltip("Secrets, rooms and deaths per player.")
                    )
                ).withTooltip("Local chat reports when a run ends, a second after Hypixel's own summary."),
                new FeatureEntry("Death Messages", config.dungeon::deathMessagesEnabled,
                    config.dungeon::setDeathMessagesEnabled,
                    List.of(
                        SettingEntry.group("Announce To Party").withChildren(List.of(
                            SettingEntry.toggle("Share Total", config.dungeon::deathMessagesShareTotalEnabled,
                                config.dungeon::setDeathMessagesShareTotalEnabled),
                            SettingEntry.toggle("Share Individual", config.dungeon::deathMessagesShareIndividualEnabled,
                                config.dungeon::setDeathMessagesShareIndividualEnabled)
                        ))
                    )
                ),
                new FeatureEntry("Extra Score Messages", config.dungeon::extraScoreMessagesEnabled,
                    config.dungeon::setExtraScoreMessagesEnabled,
                    List.of(
                        SettingEntry.toggle("Mimic", config.dungeon::mimicMessageEnabled,
                            config.dungeon::setMimicMessageEnabled),
                        SettingEntry.toggle("Prince", config.dungeon::princeMessageEnabled,
                            config.dungeon::setPrinceMessageEnabled),
                        SettingEntry.toggle("Bat", config.dungeon::batMessageEnabled,
                            config.dungeon::setBatMessageEnabled)
                    )
                ),
                new FeatureEntry(
                    "Crypt Messages",
                    () -> config.dungeon.cryptProgressPartyMessageEnabled()
                        || config.dungeon.fiveCryptTitleEnabled()
                        || config.dungeon.fiveCryptPartyMessageEnabled(),
                    (Runnable) null,
                    List.of(
                        SettingEntry.toggle("Announce Progress", config.dungeon::cryptProgressPartyMessageEnabled,
                            config.dungeon::setCryptProgressPartyMessageEnabled),
                        SettingEntry.toggle("5 Crypt Title", config.dungeon::fiveCryptTitleEnabled,
                            config.dungeon::setFiveCryptTitleEnabled),
                        SettingEntry.toggle("5 Crypts Msg", config.dungeon::fiveCryptPartyMessageEnabled,
                            config.dungeon::setFiveCryptPartyMessageEnabled),
                        SettingEntry.text("Crypt Msg", config.dungeon::fiveCryptPartyMessage,
                            config.dungeon::setFiveCryptPartyMessage)
                    )
                ),
                KungBuild.MODRINTH ? null : new FeatureEntry("Blood rush helper", config.bloodRush::enabled,
                    config.bloodRush::setEnabled,
                    List.of(
                        SettingEntry.slider("Title Time", config.bloodRush::titleDurationTenths,
                            config.bloodRush::setTitleDurationTenths, 1, 50, 1)
                    )
                ),
                new FeatureEntry("Dungeon Chat Filter", config.chatFilter::enabled,
                    config.chatFilter::setEnabled,
                    List.of(
                        SettingEntry.toggle("Blessings", config.chatFilter::blessings,
                            config.chatFilter::setBlessings),
                        SettingEntry.toggle("Loot Spam", config.chatFilter::lootSpam,
                            config.chatFilter::setLootSpam),
                        SettingEntry.toggle("Watcher", config.chatFilter::watcher,
                            config.chatFilter::setWatcher),
                        SettingEntry.toggle("Boss Messages", config.chatFilter::bossMessages,
                            config.chatFilter::setBossMessages).withChildren(List.of(
                            SettingEntry.toggle("Bonzo", config.chatFilter::bonzo,
                                config.chatFilter::setBonzo),
                            SettingEntry.toggle("Scarf", config.chatFilter::scarf,
                                config.chatFilter::setScarf),
                            SettingEntry.toggle("Professor", config.chatFilter::professor,
                                config.chatFilter::setProfessor),
                            SettingEntry.toggle("Thorn", config.chatFilter::thorn,
                                config.chatFilter::setThorn),
                            SettingEntry.toggle("Livid", config.chatFilter::livid,
                                config.chatFilter::setLivid),
                            SettingEntry.toggle("Sadan", config.chatFilter::sadan,
                                config.chatFilter::setSadan),
                            SettingEntry.toggle("Maxor", config.chatFilter::maxor,
                                config.chatFilter::setMaxor),
                            SettingEntry.toggle("Storm", config.chatFilter::storm,
                                config.chatFilter::setStorm),
                            SettingEntry.toggle("Goldor", config.chatFilter::goldor,
                                config.chatFilter::setGoldor),
                            SettingEntry.toggle("Necron", config.chatFilter::necron,
                                config.chatFilter::setNecron),
                            SettingEntry.toggle("Wither King", config.chatFilter::witherKing,
                                config.chatFilter::setWitherKing)
                        ))
                    )
                ),
                new FeatureEntry("Splits Overlay", config.splits::enabled,
                    config.splits::setEnabled,
                    List.of(
                        SettingEntry.choice("Format", TimeFormat.values(), config.splits::format,
                            config.splits::setFormat, TimeFormat::label),
                        SettingEntry.toggle("Time Prediction", config.splits::timePrediction,
                            config.splits::setTimePrediction).withChildren(List.of(
                                SettingEntry.choice("Update", PredictionMode.values(), config.splits::predictionMode,
                                    config.splits::setPredictionMode, PredictionMode::label),
                                SettingEntry.choice("Source", PredictionSource.values(), config.splits::predictionSource,
                                    config.splits::setPredictionSource, PredictionSource::label)
                                    .withTooltip("PB uses personal best splits. AVG uses the last 20 finished runs on this floor. Reset AVG in /kung splits.")
                            )),
                        SettingEntry.toggle("Time Lost", config.splits::timeLost,
                            config.splits::setTimeLost),
                        SettingEntry.toggle("Paste In Chat", config.splits::runEndChat,
                            config.splits::setRunEndChat)
                            .withTooltip("Print all splits in your local chat when the run ends.")
                    )
                )
            )),
            new CategoryEntry("Garden", List.of(
                new FeatureEntry("6th Visitor Alarm", config.visitorAlarm::enabled,
                    config.visitorAlarm::setEnabled,
                    List.of(
                        SettingEntry.slider("Volume (%)", config.visitorAlarm::volume,
                            config.visitorAlarm::setVolume, 1, 100, 1)
                    )
                ).withTooltip("Rings when 5 visitors wait and the timer for the 6th has run out.",
                    "Accepting or declining a visitor, or muting it in chat, stops it."),
                new FeatureEntry("Feast Progress", config.feast::enabled,
                    config.feast::setEnabled,
                    List.of(
                        SettingEntry.toggle("Show in Hub Farm", config.feast::showInHubFarm,
                            config.feast::setShowInHubFarm)
                            .withTooltip("Also show Feast Progress in the Hub farm area during an active Feast."),
                        SettingEntry.text("Kernel Timeout (s)", () -> Integer.toString(config.feast.kernelTimeoutSeconds()),
                            config.feast::setKernelTimeoutText)
                            .withTooltip("Seconds without harvesting before Avg Kernels/h pauses.",
                                "Range: 10-300 seconds. Default: 60 seconds.")
                    )
                ).withTooltip("Open the Feast menu to sync milestone progress.",
                    "Kernels sync from the scoreboard or Grand Bakery.",
                    "Avg Kernels/h is saved per profile and stays visible after pauses or restarts.",
                    "Every 5 farming minutes: 80% previous rate + 20% measured rate.",
                    "Without a saved rate, the first estimate takes 5 farming minutes."),
                // Settings only, no master toggle: the hologram is user-triggered (/kung greenhouse), so there is
                // nothing to switch on first and the "toggles default off" rule has nothing to apply to.
                new FeatureEntry("Greenhouse Hologram", () -> false, (Runnable) null, greenhouseHologramSettings(config))
                    .withTooltip("Look and toggle key of the Greenhouse hologram (/kung greenhouse).",
                        "Blue: still to place. Red: wrong crop. Pink: a crop where the layout has nothing.",
                        "Orange: right block, wrong state (dirt instead of farmland).")
            )),
            new CategoryEntry("Hunting", List.of(
                new FeatureEntry("Safari Uniques", config.safari::enabled,
                    config.safari::setEnabled,
                    List.of()
                ).withTooltip("Red: uniques you are still missing. Gray: caught this run.",
                    "Loot Share catches count too.")
            )),
            new CategoryEntry("Slayer", List.of(
                new FeatureEntry("Tarantula Helper", config.slayer::tarantulaHelperEnabled,
                    config.slayer::setTarantulaHelperEnabled,
                    List.of(
                        SettingEntry.toggle("Egg Sac Prediction", config.slayer::eggSacPredictionEnabled,
                            config.slayer::setEggSacPredictionEnabled),
                        SettingEntry.toggle("Egg Sac Boxes", config.slayer::eggSacBoxesEnabled,
                            config.slayer::setEggSacBoxesEnabled)
                            .withTooltip("Boxes the spawned sacs themselves. They carry no name of their own,",
                                "so the Hitboxes feature cannot find them; Kung locates them by their timer."),
                        SettingEntry.toggle("Egg Sac Spawn Timer", config.slayer::eggSacCountdownEnabled,
                            config.slayer::setEggSacCountdownEnabled)
                            .withTooltip("Counts down to the sacs from the moment the phase starts,",
                                "then counts up while they are overdue. Move it in /kung hud.")
                    )
                )
            )),
            new CategoryEntry("Util", present(
                new FeatureEntry("Bow Draw Indicator", config.misc::bowDrawIndicatorEnabled,
                    config.misc::setBowDrawIndicatorEnabled, new BowDrawThresholdSettings(config.misc))
                    .withTooltip("Shows estimated bow power while drawing on Hypixel, paced by server ticks.",
                        "Add or remove custom tick markers below. 3t: minimum shot. 20t: full power.",
                        "Last Draw keeps the stopped value visible for 0.2 seconds between shots.",
                        "Power rises between markers; network delay can shift the estimate.",
                        "Move and resize in /kung hud. Shortbows do not charge."),
                new FeatureEntry("Frozen Blaze HUD", config.misc::frozenBlazeHudEnabled,
                    config.misc::setFrozenBlazeHudEnabled,
                    List.of(
                        SettingEntry.toggle("Alarm", config.misc::frozenBlazeAlarmEnabled,
                            config.misc::setFrozenBlazeAlarmEnabled)
                            .withTooltip("Plays a sound and shows a title when the aura turns off.",
                                "Fires once per shutdown; walk and look around to rearm it.")
                    ))
                    .withTooltip("Shows whether the (Frozen) Blaze Armor aura is active while wearing the full set.",
                        "The aura stops after 30 seconds without walking or turning the camera; it needs both.",
                        "Move and resize in /kung hud."),
                new FeatureEntry("Hitboxes", config.hitboxes::enabled,
                    config.hitboxes::setEnabled, new HitboxSettings(config.hitboxes))
                    .withTooltip("Show colored hitboxes for selected entity types.", "Right-click to add entities and edit their colors.",
                        "SkyBlock mobs: pick a name loaded nearby, or type any name and choose the (skyblock) entry.",
                        "It matches the mob's nametag, so Zealot also covers Special Zealot.",
                        "Thrown items such as a Bonemerang: type the item (bone) and choose the (item) entry."),
                new FeatureEntry("Lobby Hop Helper", config.misc::lobbyHopHelperEnabled,
                    config.misc::setLobbyHopHelperEnabled,
                    List.of(
                        SettingEntry.toggle("Title", config.misc::lobbyHopTitleEnabled,
                            config.misc::setLobbyHopTitleEnabled),
                        SettingEntry.toggle("Chat Message", config.misc::lobbyHopChatEnabled,
                            config.misc::setLobbyHopChatEnabled)
                    )
                ).withTooltip("Warns when you join a lobby you were in before, and how long ago you left it."),
                new FeatureEntry("Performance", config.misc::performanceHudEnabled,
                    config.misc::setPerformanceHudEnabled,
                    List.of(
                        SettingEntry.slider("Refresh Time", config.misc::performanceRefreshTenths,
                            config.misc::setPerformanceRefreshTenths, 1, 10, 1),
                        SettingEntry.toggle("Graph TPS", config.misc::performanceGraphTps,
                            config.misc::setPerformanceGraphTps),
                        SettingEntry.toggle("Graph FPS", config.misc::performanceGraphFps,
                            config.misc::setPerformanceGraphFps),
                        SettingEntry.toggle("Graph Ping", config.misc::performanceGraphPing,
                            config.misc::setPerformanceGraphPing)
                    ))
                    .withTooltip("Shows TPS, FPS and ping. TPS is measured from the server's own tick packets,",
                        "so a bad ping does not look like server lag.",
                        "Each graph draws the last 30 seconds of that stat; pick any combination.",
                        "Move and resize in /kung hud."),
                new FeatureEntry("Sack Tracker", config.misc::sackTrackerEnabled,
                    config.misc::setSackTrackerEnabled, new SackTrackerSettings(config.misc))
                    .withTooltip("Tracks sack items and how many are left to a goal.",
                        "Set the Track Key, then press it over an item in a sack.",
                        "Counts follow SkyBlock's [Sacks] messages; keep them on in /settings.",
                        "Move and resize in /kung hud."),
                KungBuild.MODRINTH ? null : new FeatureEntry("Skip NPC Dialogue", config.misc::skipNpcDialogueEnabled,
                    config.misc::setSkipNpcDialogueEnabled, List.of())
                    .withTooltip("Answers NPC dialogue prompts: a single option, or a known choice.",
                        "Clicks the NPC a \"Talk to\", \"Check on\" or \"Give\" quest objective names once it is in reach.",
                        "Never clicks in menus, sends chat or moves you. Off in dungeons and Kuudra.",
                        "Automates gameplay, which Hypixel's rules forbid. Use at your own risk."),
                new FeatureEntry("Hypixel API", config.misc::hypixelApiEnabled,
                    config.misc::setHypixelApiEnabled,
                    List.of(
                        SettingEntry.dynamicLabel(HypixelSkyBlockProfileClient.INSTANCE::statusMessage),
                        SettingEntry.text("API Key", config.misc::hypixelApiKey,
                            config.misc::setHypixelApiKey)
                            .withTooltip("Create one at developer.hypixel.net; it looks like a UUID."),
                        SettingEntry.button("Get API Key", "Open", KungSettings::openHypixelDeveloperDashboard)
                            .withTooltip("Opens the Hypixel developer dashboard in your browser."),
                        SettingEntry.button("Test Key", "Run", KungSettings::testHypixelKey)
                            .withTooltip("Asks Hypixel once with your key and shows the answer above.")
                    )
                ),
                new FeatureEntry("Chat Commands", config.misc::chatCommandsEnabled,
                    config.misc::setChatCommandsEnabled,
                    List.of(
                        SettingEntry.toggle("!c50", config.misc::c50ChatCommandEnabled,
                            config.misc::setC50ChatCommandEnabled).withChildren(List.of(
                            SettingEntry.toggle("Party", config.misc::c50PartyCommandsEnabled,
                                config.misc::setC50PartyCommandsEnabled),
                            SettingEntry.toggle("Guild", config.misc::c50GuildCommandsEnabled,
                                config.misc::setC50GuildCommandsEnabled),
                            SettingEntry.toggle("All Chat", config.misc::c50AllChatCommandsEnabled,
                                config.misc::setC50AllChatCommandsEnabled),
                            SettingEntry.toggle("DMs", config.misc::c50PrivateCommandsEnabled,
                                config.misc::setC50PrivateCommandsEnabled)
                        )),
                        SettingEntry.toggle("!ca50", config.misc::ca50ChatCommandEnabled,
                            config.misc::setCa50ChatCommandEnabled).withChildren(List.of(
                            SettingEntry.toggle("Party", config.misc::ca50PartyCommandsEnabled,
                                config.misc::setCa50PartyCommandsEnabled),
                            SettingEntry.toggle("Guild", config.misc::ca50GuildCommandsEnabled,
                                config.misc::setCa50GuildCommandsEnabled),
                            SettingEntry.toggle("All Chat", config.misc::ca50AllChatCommandsEnabled,
                                config.misc::setCa50AllChatCommandsEnabled),
                            SettingEntry.toggle("DMs", config.misc::ca50PrivateCommandsEnabled,
                                config.misc::setCa50PrivateCommandsEnabled)
                        )),
                        SettingEntry.toggle("!tps", config.misc::tpsChatCommandEnabled,
                            config.misc::setTpsChatCommandEnabled).withChildren(List.of(
                            SettingEntry.toggle("Party", config.misc::tpsPartyCommandsEnabled,
                                config.misc::setTpsPartyCommandsEnabled),
                            SettingEntry.toggle("Guild", config.misc::tpsGuildCommandsEnabled,
                                config.misc::setTpsGuildCommandsEnabled),
                            SettingEntry.toggle("All Chat", config.misc::tpsAllChatCommandsEnabled,
                                config.misc::setTpsAllChatCommandsEnabled),
                            SettingEntry.toggle("DMs", config.misc::tpsPrivateCommandsEnabled,
                                config.misc::setTpsPrivateCommandsEnabled)
                        ))
                    )
                ),
                new FeatureEntry("Chat Emotes", config.misc::chatEmotesEnabled,
                    config.misc::setChatEmotesEnabled,
                    new ChatEmoteSettings(config.misc)
                ).withTooltip("Replaces shortcuts in chat and commands with an emote.",
                    "Built in: :iman: and :ironman: become \u2672. Add your own below."),
                new FeatureEntry("Custom Sounds", config.misc::customSoundsEnabled,
                    config.misc::setCustomSoundsEnabled,
                    List.of(
                        SettingEntry.button("Sound Settings", "Open", () -> openSoundSettings(config))
                            .withTooltip("Pick a sound for each event from the folder's files, and set its volume and pitch."),
                        SettingEntry.button("Sound Folder", "Open", CustomSoundsFeature::openSoundsFolder)
                            .withTooltip("Drop .wav, .mp3 or .aiff files in here:", CustomSoundsFeature.soundsFolder().toString())
                    )
                ).withTooltip("Plays your own sound files on an arrow hit and when the Wither shield ends."),
                menuFontEntry(config),
                new FeatureEntry("Loadouts Auto Close", config.misc::loadoutsAutoCloseEnabled,
                    config.misc::setLoadoutsAutoCloseEnabled,
                    loadoutAutoCloseSettings(config)
                ),
                new FeatureEntry("Superpairs Helper", config.misc::superpairsHelperEnabled,
                    config.misc::setSuperpairsHelperEnabled,
                    List.of(
                        SettingEntry.toggle("Debug", config.misc::superpairsHelperDebugEnabled,
                            config.misc::setSuperpairsHelperDebugEnabled)
                            .withTooltip("Adds packet details to /kung log, without extra overlay text.")
                    )
                ).withTooltip("Lists enchantments first; individual Enchanting XP rewards are hidden.",
                    "Unseen pairs have neither card revealed. Known singles are excluded.",
                    "Up to marks an upper bound because hidden fields may contain bonuses.",
                    "1/2 and 2/2 mean cards seen, not confirmed claimed rewards.")
            )),
            new CategoryEntry("Debug", List.of(
                new FeatureEntry(
                    // The Modrinth jar never checks GitHub; the Modrinth App updates it.
                    KungBuild.MODRINTH ? () -> "Updates: Modrinth App" : KungUpdater.INSTANCE::buttonLabel,
                    KungUpdater.INSTANCE::isUpdateAvailable,
                    KungUpdater.INSTANCE::canInstallUpdate,
                    () -> KungUpdater.INSTANCE.installLatestAsync(Minecraft.getInstance()),
                    present(
                        SettingEntry.dynamicLabel(() -> "Installed: v" + KungUpdater.currentVersion()),
                        KungBuild.MODRINTH ? null : SettingEntry.dynamicLabel(KungUpdater.INSTANCE::latestVersionLabel),
                        SettingEntry.button("Changelogs", "Open", openChangelog)
                    )
                ).pinnedOpen().withTooltip(KungBuild.MODRINTH
                    ? new String[] {"Updates come through the Modrinth App."}
                    : new String[] {"Download and verify the latest compatible update from GitHub.",
                        "Close Minecraft to apply it automatically, then start the game again."}),
                new FeatureEntry("Debug Messages", config.debug::enabled,
                    config.debug::setEnabled,
                    List.of(
                        SettingEntry.toggle("Context", config.debug::contextMessages,
                            config.debug::setContextMessages),
                        SettingEntry.toggle("Dungeon", config.debug::dungeonMessages,
                            config.debug::setDungeonMessages),
                        SettingEntry.toggle("Interface", config.debug::interfaceMessages,
                            config.debug::setInterfaceMessages),
                        SettingEntry.toggle("Tarantula", config.debug::tarantulaMessages,
                            config.debug::setTarantulaMessages).withChildren(List.of(
                            SettingEntry.toggle("Slayer Spawned", config.slayer::tarantulaDebugSlayerSpawned,
                                config.slayer::setTarantulaDebugSlayerSpawned),
                            SettingEntry.toggle("Slayer Pos", config.slayer::tarantulaDebugSlayerPosition,
                                config.slayer::setTarantulaDebugSlayerPosition),
                            SettingEntry.toggle("Phase Change", config.slayer::tarantulaDebugSlayerPhaseChange,
                                config.slayer::setTarantulaDebugSlayerPhaseChange),
                            SettingEntry.toggle("Slayer Dead", config.slayer::tarantulaDebugSlayerDead,
                                config.slayer::setTarantulaDebugSlayerDead),
                            SettingEntry.toggle("Egg Sac Start", config.slayer::tarantulaDebugEggSacPhaseStart,
                                config.slayer::setTarantulaDebugEggSacPhaseStart),
                            SettingEntry.toggle("Egg Sac Done", config.slayer::tarantulaDebugEggSacPhaseDone,
                                config.slayer::setTarantulaDebugEggSacPhaseDone)
                        ))
                    )
                ),
                new FeatureEntry("Room Sync", config.dungeon::roomSyncEnabled,
                    () -> toggleRoomSync(config),
                    List.of(
                        SettingEntry.dynamicLabel(() -> "Status: " + DungeonRoomDataSyncClient.INSTANCE.menuStatus()),
                        SettingEntry.text("Server", config.dungeon::roomSyncServerUrl,
                            config.dungeon::setRoomSyncServerUrl),
                        SettingEntry.toggle("Upload", config.dungeon::roomSyncUploadEnabled,
                            config.dungeon::setRoomSyncUploadEnabled),
                        SettingEntry.button("Pull", "Rooms",
                            () -> DungeonRoomDataSyncClient.INSTANCE.pullAsync(Minecraft.getInstance())),
                        SettingEntry.button("Ping", "Check",
                            () -> DungeonRoomDataSyncClient.INSTANCE.pingAsync(Minecraft.getInstance()))
                    )
                )
            ))
        );
    }

    private static List<SettingEntry> greenhouseHologramSettings(KungConfig config) {
        var hologram = config.greenhouse;
        return List.of(
            SettingEntry.slider("Opacity (%)", hologram::hologramOpacityPercent, hologram::setHologramOpacityPercent,
                    Math.round(GreenhouseConfig.MIN_OPACITY * 100F), Math.round(GreenhouseConfig.MAX_OPACITY * 100F), 1)
                .withTooltip("How solid the hologram is. 100% is fully opaque."),
            SettingEntry.color("To Place Color", hologram::colorToPlace,
                    () -> editColor("To Place Color", hologram.colorToPlace(), hologram::setColorToPlace))
                .withTooltip("Tint and outline of what the layout wants but is not placed yet."),
            SettingEntry.color("Wrong Crop Color", hologram::colorWrong,
                    () -> editColor("Wrong Crop Color", hologram.colorWrong(), hologram::setColorWrong))
                .withTooltip("A different crop stands where the layout wants another one."),
            SettingEntry.color("Extra Crop Color", hologram::colorExtra,
                    () -> editColor("Extra Crop Color", hologram.colorExtra(), hologram::setColorExtra))
                .withTooltip("A crop stands where the layout has nothing."),
            SettingEntry.color("Wrong State Color", hologram::colorWrongState,
                    () -> editColor("Wrong State Color", hologram.colorWrongState(), hologram::setColorWrongState))
                .withTooltip("The right block in the wrong state, such as dirt where farmland belongs."),
            SettingEntry.toggle("Hide When Complete", hologram::hideWhenComplete, hologram::setHideWhenComplete)
                .withTooltip("Hides the whole hologram and prints a short chat note",
                    "once every crop is placed correctly."),
            SettingEntry.keybind("Toggle Key",
                    () -> LoadoutsAutoCloseFeature.keybindDisplay(hologram.toggleKeybind()),
                    hologram::toggleKeybind, hologram::setToggleKeybind)
                .withTooltip("Shows or hides the hologram while you play. Default: K.",
                    "Click, then press a key or mouse button; Esc or Backspace unbinds it.")
        );
    }

    private static void editColor(String title, int current, IntConsumer save) {
        Minecraft client = Minecraft.getInstance();
        Screen parent = McCompat.screen(client);
        // The optional native menu delegates the color to the same popup the Kung menu uses.
        if (!(parent instanceof KungConfigScreen)) {
            parent = KungConfigScreen.fromParent(parent, "Greenhouse Hologram");
            McCompat.setScreen(client, parent);
        }
        McCompat.setScreen(client, new HitboxEditorScreen(parent, title, current, save));
    }

    /** Fonts are built while the game runs, so the list and the switch both take effect at once. */
    private static FeatureEntry menuFontEntry(KungConfig config) {
        return new FeatureEntry("Menu Font", () -> false, (Runnable) null, List.of(
            SettingEntry.choice("Font", KungFonts::available, config.misc::menuFont, config.misc::setMenuFont)
                .withTooltip("Inter is Kung's bundled font, Minecraft is the game's own.",
                    "Every .ttf in the font folder shows up here as well."),
            SettingEntry.button("Font Folder", "Open", KungFonts::openFolder)
                .withTooltip("Drop .ttf files in here:", KungFonts.folder().toString()),
            SettingEntry.choice("Google Font", () -> KungFonts.LIBRARY, KungFonts::libraryChoice,
                KungFonts::selectLibraryChoice),
            SettingEntry.button("Download", "Get", KungFonts::download)
                .withTooltip("Downloads the family above into the font folder and switches to it."),
            SettingEntry.dynamicLabel(KungFonts::status)
        )).withTooltip("The font Kung's own menus draw with; the game's own text stays untouched.");
    }

    /** List.of without the entries a build flavor leaves out (null). */
    @SafeVarargs
    private static <T> List<T> present(T... entries) {
        return java.util.Arrays.stream(entries).filter(java.util.Objects::nonNull).toList();
    }

    private static List<SettingEntry> dungeonMapSettings(KungConfig config, boolean developer) {
        // The Modrinth map draws entered rooms only: nothing unopened to dim, and no Mimic ESP.
        List<SettingEntry> settings = new ArrayList<>(present(
            KungBuild.MODRINTH ? null : SettingEntry.slider("Unopened Alpha", config.dungeon::unopenedRoomAlpha,
                config.dungeon::setUnopenedRoomAlpha, 0, 100, 1)
                .withTooltip("How dark rooms you have not opened yet are drawn."),
            SettingEntry.toggle("Boss Map", config.dungeon::showInBoss,
                config.dungeon::setShowInBoss),
            SettingEntry.toggle("Prince Icons", config.dungeon::princeIconsEnabled,
                config.dungeon::setPrinceIconsEnabled),
            KungBuild.MODRINTH ? null : SettingEntry.toggle("Mimic ESP", config.dungeon::mimicEspEnabled,
                config.dungeon::setMimicEspEnabled),
            SettingEntry.toggle("Force Paul", config.dungeon::forcePaulScoreEnabled,
                config.dungeon::setForcePaulScoreEnabled),
            SettingEntry.toggle("Room Debug", config.dungeon::debugRoomMatches,
                config.dungeon::setDebugRoomMatches),
            SettingEntry.toggle("Room Crypts", config.dungeon::debugRoomCrypts,
                config.dungeon::setDebugRoomCrypts)));
        if (developer) {
            settings.add(SettingEntry.toggle("Local Data", config.dungeon::localRoomDataEnabled,
                () -> toggleLocalRoomData(config)).forDeveloper());
        }
        return List.copyOf(settings);
    }

    private static List<SettingEntry> wishAlertSettings(KungConfig config, boolean developer) {
        SettingEntry lowHealth = SettingEntry.slider("Low HP (%)", config.dungeon::wishAlertLowHealthPercent,
            config.dungeon::setWishAlertLowHealthPercent, 0, 100, 1)
            .withTooltip("Alert when a teammate drops below this share of their HP. 0 turns it off.");
        if (!developer) return List.of(lowHealth);
        return List.of(lowHealth,
            SettingEntry.toggle("Ignore Class And Cooldown", config.dungeon::devWishAlertAnyClassEnabled,
                config.dungeon::setDevWishAlertAnyClassEnabled)
                .withTooltip("Fires on any class with Wish still on cooldown, for testing.",
                    "Beng114 only; a copied config does nothing for anyone else.").forDeveloper()
        );
    }

    private static List<SettingEntry> witherDragonSettings(KungConfig config, boolean developer) {
        List<SettingEntry> settings = new ArrayList<>(List.of(
            SettingEntry.toggle("Debuff Tracker", config.dungeon::dragonDebuffTrackerEnabled,
                config.dungeon::setDragonDebuffTrackerEnabled)
                .withChildren(List.of(
                    SettingEntry.toggle("HUD", config.dungeon::dragonDebuffHudEnabled,
                        config.dungeon::setDragonDebuffHudEnabled),
                    SettingEntry.toggle("Chat", config.dungeon::dragonDebuffChatEnabled,
                        config.dungeon::setDragonDebuffChatEnabled)))
                .withTooltip("Compact dragon times, early arrows and Ice Spray hits in the HUD and local chat.",
                    "Right-click to turn the HUD or the chat report off on its own.",
                    "Move and scale the HUD with /kung hud.",
                    "Hover the chat report for first/fifth hits, rate and hit ticks.",
                    "Arrow feedback counts for the first 40 server ticks at your nearest spawning statue.",
                    "Other dragons show -- for arrows. Each respawn starts fresh."),
            SettingEntry.choice("Track", DragonDebuffScope.values(), config.dungeon::dragonDebuffScope,
                config.dungeon::setDragonDebuffScope, DragonDebuffScope::label)
                .withTooltip("All Dragons: show every dragon in the HUD and chat.",
                    "Nearest Statue: choose the closest statue among each wave's spawning dragons.",
                    "Uses your position at the first spawn and keeps that target until the next wave.")
                .withVisibleWhen(config.dungeon::dragonDebuffTrackerEnabled),
            SettingEntry.toggle("Dragon Prio", config.dungeon::dragonPrioEnabled,
                config.dungeon::setDragonPrioEnabled)
                .withTooltip("Shows a spawn countdown (ticks or milliseconds, see Prio Unit) and a line from your crosshair to the spawn of your dragon.",
                    "The Bers split (Berserker + Mage) takes the first dragon, the Arch split (Archer + Healer + Tank) the second.",
                    "Every later dragon, including respawns, is shown to both splits.",
                    "Archer is always Arch and Berserker always Bers; Mage, Healer and Tank are set below.",
                    "While your class is not known yet you see every dragon.",
                    "Move and scale the countdown with /kung hud; the line follows Line Thickness."),
            SettingEntry.choice("Mage Split", DragonPrioSplit.values(), config.dungeon::dragonPrioMage,
                config.dungeon::setDragonPrioMage, DragonPrioSplit::label)
                .withVisibleWhen(config.dungeon::dragonPrioEnabled),
            SettingEntry.choice("Healer Split", DragonPrioSplit.values(), config.dungeon::dragonPrioHealer,
                config.dungeon::setDragonPrioHealer, DragonPrioSplit::label)
                .withVisibleWhen(config.dungeon::dragonPrioEnabled),
            SettingEntry.choice("Tank Split", DragonPrioSplit.values(), config.dungeon::dragonPrioTank,
                config.dungeon::setDragonPrioTank, DragonPrioSplit::label)
                .withVisibleWhen(config.dungeon::dragonPrioEnabled),
            SettingEntry.choice("Prio Unit", DragonPrioUnit.values(), config.dungeon::dragonPrioUnit,
                config.dungeon::setDragonPrioUnit, DragonPrioUnit::label)
                .withVisibleWhen(config.dungeon::dragonPrioEnabled),
            SettingEntry.toggle("Spawn Markers", config.dungeon::dragonSpawnMarkersEnabled,
                config.dungeon::setDragonSpawnMarkersEnabled),
            SettingEntry.choice("Marker", DragonMarkerMode.values(),
                config.dungeon::dragonMarkerMode, config.dungeon::setDragonMarkerMode,
                DragonMarkerMode::label)
                .withTooltip("Core: one small cube per enabled Core Part.",
                    "Skeleton: the dragon's part hitboxes, in that statue's colour.",
                    "Skeleton previews the spawn pose at the anchor before the dragon exists.")
                .withVisibleWhen(config.dungeon::dragonSpawnMarkersEnabled),
            SettingEntry.group("Core Parts").withChildren(java.util.Arrays.stream(DragonPart.values())
                .map(part -> SettingEntry.toggle(part.label(), () -> config.dungeon.dragonCorePart(part),
                    () -> config.dungeon.toggleDragonCorePart(part))).toList())
                .withTooltip("Which hitbox centres get a core cube; any number can be on at once.")
                .withVisibleWhen(() -> config.dungeon.dragonSpawnMarkersEnabled()
                    && config.dungeon.dragonMarkerMode() == DragonMarkerMode.CORE)));
        if (developer) settings.add(
            SettingEntry.choice("Aim Point", DragonAimMode.values(),
                config.dungeon::dragonAimMode, config.dungeon::setDragonAimMode, DragonAimMode::label)
                .withTooltip("Where to stand and shoot, from the burst until shortly after spawn.",
                    "Auto: Terminator for Archer/Berserker, Last Breath for everyone else.",
                    "Last Breath: a square on the floor under where the dragon's body will be - stand there -",
                    "and a dot ten blocks out: crosshair on it, draw to the white tick on the Bow Draw bar.",
                    "Bar, for where you stand: cyan the least draw that reaches the body, red the most that",
                    "still misses every block on the way (the statue above you). Orange square: none gets there.",
                    "Terminator: aims at the neck; gray lands before spawn, white on the dragon, red too late.",
                    "Dragons spawn facing one way and snap to their flight within a few ticks; where they go",
                    "comes from one recorded flight per statue, until then from the spawn pose.",
                    "Your arrows around each spawn go to m7-dragon-shots.jsonl: spot, draw, timing, result.")
                .forDeveloper());
        settings.addAll(List.of(
            SettingEntry.toggle("Flight Paths", config.dungeon::dragonFlightPathsEnabled,
                config.dungeon::setDragonFlightPathsEnabled)
                .withTooltip("Draws each dragon's flight from spawn until it leaves its statue range,",
                    "plus the last recorded flights of the same part, dimmed. Recording goes on while hidden.",
                    "Purple teleports between random points, so it has none."),
            SettingEntry.choice("Path Part", DragonPart.values(),
                config.dungeon::dragonTrailPart, config.dungeon::setDragonTrailPart,
                DragonPart::label)
                .withTooltip("Which dragon hitbox part the flight path follows and records.",
                    "Each part keeps its own recorded paths; a switch records from the next run.")
                .withVisibleWhen(config.dungeon::dragonFlightPathsEnabled),
            SettingEntry.slider("Line Thickness (%)", config.dungeon::dragonLineThickness,
                config.dungeon::setDragonLineThickness, 25, 400, 25)
                .withVisibleWhen(config.dungeon::dragonFlightPathsEnabled),
            SettingEntry.toggle("Statue Boxes", config.dungeon::dragonStatueBoxesEnabled,
                config.dungeon::setDragonStatueBoxesEnabled)
                .withTooltip("Shows estimated dragon counting areas at the statues, edged in the statue's colour.",
                    "While its dragon is up the box is lightly filled: green if a kill right now",
                    "would count (origin inside the estimate), red if not."),
            SettingEntry.toggle("Count Notifications", config.dungeon::dragonCountNotificationsEnabled,
                config.dungeon::setDragonCountNotificationsEnabled)
                .withTooltip("Local notifications for server-confirmed dragon counts.")));
        if (developer) settings.add(
            SettingEntry.toggle("Developer Diagnostics", config.dungeon::devDragonDiagnosticsEnabled,
                config.dungeon::setDevDragonDiagnosticsEnabled)
                .withTooltip("Extra local dragon capture diagnostics for Beng114 only.")
                .forDeveloper());
        return List.copyOf(settings);
    }

    private static void toggleRoomSync(KungConfig config) {
        config.dungeon.setRoomSyncEnabled(!config.dungeon.roomSyncEnabled());
        DungeonKnownRoomCatalog.reload();
    }

    private static List<SettingEntry> loadoutAutoCloseSettings(KungConfig config) {
        List<SettingEntry> settings = new ArrayList<>();
        settings.add(SettingEntry.toggle("Close Only On Change", config.misc::loadoutsCloseOnlyOnChange,
            config.misc::setLoadoutsCloseOnlyOnChange));
        for (int index = 0; index < 12; index++) {
            int loadoutIndex = index;
            settings.add(SettingEntry.keybind(
                "Loadout " + (index + 1),
                () -> LoadoutsAutoCloseFeature.keybindDisplay(config.misc.loadoutKeybind(loadoutIndex)),
                () -> config.misc.loadoutKeybind(loadoutIndex),
                keybind -> config.misc.setLoadoutKeybind(loadoutIndex, keybind)
            ));
        }
        return List.copyOf(settings);
    }

    private static void openHypixelDeveloperDashboard() {
        ConfirmLinkScreen.confirmLinkNow(McCompat.screen(Minecraft.getInstance()), HYPIXEL_DEVELOPER_DASHBOARD);
    }

    /** The status line above only means something once a real request has answered. */
    private static void testHypixelKey() {
        var player = Minecraft.getInstance().player;
        HypixelSkyBlockProfileClient.INSTANCE.verifyKey(player == null ? "" : player.getUUID().toString().replace("-", ""));
    }

    private static void openSoundSettings(KungConfig config) {
        Minecraft client = Minecraft.getInstance();
        // From the native menu too: back to wherever the player came from.
        McCompat.setScreen(client, new CustomSoundsScreen(McCompat.screen(client), config.misc));
    }

    private static void toggleLocalRoomData(KungConfig config) {
        config.dungeon.setLocalRoomDataEnabled(!config.dungeon.localRoomDataEnabled());
        DungeonRoomClassifier.reload();
        DungeonKnownRoomCatalog.reload();
    }

    public record CategoryEntry(String name, List<FeatureEntry> features) { }

    public record FeatureEntry(
        Supplier<String> nameSupplier,
        BooleanSupplier enabledSupplier,
        BooleanSupplier clickableSupplier,
        Runnable toggle,
        Supplier<List<SettingEntry>> settingsSupplier,
        boolean alwaysExpanded,
        List<String> tooltip,
        boolean actionOnly,
        boolean developerOnly
    ) {
        public FeatureEntry(Supplier<String> name, BooleanSupplier enabled, BooleanSupplier clickable, Runnable action,
                            List<SettingEntry> settings, boolean alwaysExpanded, List<String> tooltip, boolean actionOnly) {
            this(name, enabled, clickable, action, () -> settings, alwaysExpanded, tooltip, actionOnly, false);
        }

        FeatureEntry(Supplier<String> name, BooleanSupplier enabled, BooleanSupplier clickable, Runnable action,
                     List<SettingEntry> settings) {
            this(name, enabled, clickable, action, () -> settings, false, List.of(), true, false);
        }

        FeatureEntry(String name, BooleanSupplier enabled, Runnable toggle, List<SettingEntry> settings) {
            this(() -> name, enabled, () -> toggle != null, toggle, settings, false, List.of(), false);
        }

        FeatureEntry(String name, BooleanSupplier enabled, Runnable toggle, Supplier<List<SettingEntry>> settings) {
            this(() -> name, enabled, () -> toggle != null, toggle, settings, false, List.of(), false, false);
        }

        /** A feature whose click flips its value through {@code set}, e.g. {@code (n, cfg::on, cfg::setOn, ...)}. */
        FeatureEntry(String name, BooleanSupplier enabled, Consumer<Boolean> set, List<SettingEntry> settings) {
            this(name, enabled, () -> set.accept(!enabled.getAsBoolean()), settings);
        }

        FeatureEntry(String name, BooleanSupplier enabled, Consumer<Boolean> set, Supplier<List<SettingEntry>> settings) {
            this(name, enabled, () -> set.accept(!enabled.getAsBoolean()), settings);
        }

        FeatureEntry pinnedOpen() {
            return new FeatureEntry(nameSupplier, enabledSupplier, clickableSupplier, toggle, settingsSupplier, true, tooltip, actionOnly, developerOnly);
        }

        FeatureEntry withTooltip(String... lines) {
            return new FeatureEntry(nameSupplier, enabledSupplier, clickableSupplier, toggle, settingsSupplier, alwaysExpanded, List.of(lines), actionOnly, developerOnly);
        }

        FeatureEntry forDeveloper() {
            return new FeatureEntry(nameSupplier, enabledSupplier, clickableSupplier, toggle, settingsSupplier, alwaysExpanded, tooltip, actionOnly, true);
        }

        public List<SettingEntry> settings() { return settingsSupplier.get(); }

        public String name() {
            return nameSupplier.get();
        }

        public boolean enabled() {
            return enabledSupplier != null && enabledSupplier.getAsBoolean();
        }

        public boolean clickable() {
            return toggle != null && clickableSupplier != null && clickableSupplier.getAsBoolean();
        }
    }
}
