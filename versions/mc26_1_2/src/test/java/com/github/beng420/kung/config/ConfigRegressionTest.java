package com.github.beng420.kung.config;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.SlayerConfig;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.runtime.AppServices;
import com.github.beng420.kung.config.category.MiscConfig.ChatCommandSetting;
import com.github.beng420.kung.config.category.MiscConfig.CommandChannel;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.Test;

public final class ConfigRegressionTest {
    private static KungConfig read(String json) {
        return KungConfig.read(new StringReader(json));
    }

    @Test
    public void defaultsAndLegacySettings() {
        KungConfig defaults = read("{}");
        equal(100, defaults.dungeon.scale(), "missing map uses defaults");
        equal(100, defaults.dungeon.textScale(), "existing configs keep their map text size");
        equal(false, defaults.dungeon.mimicEspEnabled(), "mimic esp defaults off");
        equal(false, defaults.dungeon.extraScoreMessagesEnabled(), "extra score messages default off for existing configs too");
        equal(true, defaults.dungeon.mimicMessageEnabled(), "mimic subsetting defaults on");
        equal(true, defaults.dungeon.princeMessageEnabled(), "prince subsetting defaults on");
        equal(true, defaults.dungeon.batMessageEnabled(), "bat subsetting defaults on");
        equal(85, defaults.splits.scale(), "missing splits use defaults");
        equal(false, defaults.splits.enabled(), "splits master stays disabled by default");
        equal(SplitsConfig.TimeFormat.MINUTES, defaults.splits.format(), "existing splits retain minute format");
        equal(true, defaults.splits.timeLost(), "existing splits retain time loss display");
        equal(true, defaults.misc.channelEnabled(ChatCommandSetting.C50, CommandChannel.PARTY), "missing command channels use defaults");
        equal(true, defaults.misc.commandEnabled(ChatCommandSetting.C50), "c50 command defaults on");
        equal(true, defaults.misc.commandEnabled(ChatCommandSetting.CA50), "ca50 command defaults on");
        equal(true, defaults.misc.commandEnabled(ChatCommandSetting.TPS), "tps command defaults on");
        equal(false, defaults.misc.chatEmotesEnabled(), "chat emotes default off for existing configs too");
        equal(false, defaults.misc.loadoutsCloseOnlyOnChange(), "loadouts change-only close defaults off");
        equal(true, read("{\"misc\":{\"sackTrackerEnabled\":true}}").misc.sackTrackerInMenus(),
            "sack tracker shows in menus by default, also for configs saved before the setting");
        KungConfig legacy = read("""
            {
              "dungeonMap": {"enabled": true, "x": 42, "debugMessages": false, "unopenedRoomAlpha": 0},
              "bloodRushHelper": {"enabled": true, "titleDurationTenths": 9},
              "splitsOverlay": {"x": 99},
              "dungeonChatFilter": {"blessings": false, "necron": false},
              "misc": {"ca50ChatCommandEnabled": false, "ca50GuildCommandsEnabled": false},
              "debug": {"tarantulaMessages": true},
              "slayer": {"tarantulaHelperDebugMessages": false, "eggSacPredictionEnabled": true}
            }
            """);
        equal(true, legacy.dungeon.enabled(), "map enabled retained");
        equal(42, legacy.dungeon.x(), "map position retained");
        equal(0, legacy.dungeon.unopenedRoomAlpha(), "zero transparency retained");
        equal(false, legacy.debug.dungeonMessages(), "legacy dungeon debug migrated");
        equal(false, legacy.debug.tarantulaMessages(), "legacy slayer debug precedence retained");
        equal(true, legacy.slayer.eggSacPredictionEnabled(), "prediction retained");
        equal(false, legacy.misc.commandEnabled(ChatCommandSetting.C50), "legacy ca50 command master also migrates to c50");
        equal(false, legacy.misc.commandEnabled(ChatCommandSetting.CA50), "legacy ca50 command master migrates to ca50");
        equal(false, legacy.misc.channelEnabled(ChatCommandSetting.C50, CommandChannel.GUILD), "legacy ca50 guild channel also migrates to c50");
        equal(false, legacy.misc.channelEnabled(ChatCommandSetting.CA50, CommandChannel.GUILD), "legacy ca50 guild channel migrates to ca50");
        equal(9, legacy.bloodRush.titleDurationTenths(), "blood rush timing retained");
        equal(99, legacy.splits.x(), "splits position retained");
        equal(false, legacy.chatFilter.necron(), "boss filter retained");
        KungConfig modern = read("""
            {"debug":{"dungeonMessages":true,"tarantulaMessages":false},
             "dungeonMap":{"debugMessages":false},
             "misc":{"cataChatCommandEnabled":false}}
            """);
        equal(true, modern.debug.dungeonMessages(), "modern dungeon debug takes precedence");
        equal(false, modern.debug.tarantulaMessages(), "modern tarantula debug retained");
        equal(false, modern.misc.commandEnabled(ChatCommandSetting.C50), "modern cata alias migrates to c50");
        equal(false, modern.misc.commandEnabled(ChatCommandSetting.CA50), "modern cata alias migrates to ca50");
    }

    @Test
    public void gardenAlarmsDefaultOffAndNormalizeVolume() {
        equal(false, read("{}").gardenAlarms.enabled(), "new alarms default off");
        equal(false, read("{}").gardenAlarms.maxVisitor(), "max visitor alarm defaults off");
        equal(false, read("{}").gardenAlarms.noFfBuff(), "no FF buff alarm defaults off");
        equal(false, read("{}").gardenAlarms.noSpray(), "no spray alarm defaults off");
        equal(false, read("{\"gardenAlarms\":null}").gardenAlarms.enabled(), "null category defaults off");
        equal(70, read("{}").gardenAlarms.volume(), "audible default volume");
        KungConfig loaded = read("{\"gardenAlarms\":{\"enabled\":true,\"noSpray\":true,\"volume\":999}}");
        equal(true, loaded.gardenAlarms.enabled(), "enabled setting retained");
        equal(true, loaded.gardenAlarms.noSpray(), "child setting retained");
        equal(false, loaded.gardenAlarms.noFfBuff(), "other child setting untouched");
        equal(100, loaded.gardenAlarms.volume(), "volume upper bound");
        equal(1, read("{\"gardenAlarms\":{\"volume\":0}}").gardenAlarms.volume(), "volume lower bound");
    }

    @Test
    public void sixthVisitorAlarmMigratesToTheMaxVisitorAlarm() {
        KungConfig on = read("{\"visitorAlarm\":{\"enabled\":true,\"volume\":35}}");
        equal(true, on.gardenAlarms.enabled(), "old switch turns Garden Alarms on");
        equal(true, on.gardenAlarms.maxVisitor(), "old switch turns the Max Visitor Alarm on");
        equal(false, on.gardenAlarms.noFfBuff(), "new alarms stay off");
        equal(false, on.gardenAlarms.noSpray(), "new alarms stay off");
        equal(35, on.gardenAlarms.volume(), "old volume kept");
        KungConfig off = read("{\"visitorAlarm\":{\"enabled\":false,\"volume\":20}}");
        equal(false, off.gardenAlarms.enabled(), "old off stays off");
        equal(false, off.gardenAlarms.maxVisitor(), "old off stays off");
        equal(20, off.gardenAlarms.volume(), "old volume kept while off");
        KungConfig both = read("{\"visitorAlarm\":{\"enabled\":true},\"gardenAlarms\":{\"enabled\":false}}");
        equal(false, both.gardenAlarms.enabled(), "a saved Garden Alarms section wins over the old one");
        equal(false, both.gardenAlarms.maxVisitor(), "a saved Garden Alarms section wins over the old one");
    }

    @Test
    public void feastSettingsDefaultOffAndPreserveHudCoordinatesOnReload() {
        equal(false, read("{}").feast.enabled(), "existing configurations keep the new overlay off");
        equal(true, read("{}").feast.showInHubFarm(), "Hub farm subsetting defaults on");
        equal(60, read("{}").feast.kernelTimeoutSeconds(), "old configurations receive a one-minute timeout");
        equal(10, read("{\"feast\":{\"kernelTimeoutSeconds\":0}}").feast.kernelTimeoutSeconds(), "timeout lower bound on load");
        equal(300, read("{\"feast\":{\"kernelTimeoutSeconds\":999}}").feast.kernelTimeoutSeconds(), "timeout upper bound on load");
        equal(false, read("{\"feast\":{\"showInHubFarm\":false}}").feast.showInHubFarm(), "Hub farm can be disabled");
        equal(false, read("{\"feast\":null}").feast.enabled(), "null category uses defaults");
        KungConfig config = read("""
            {"feast":{"enabled":true,"x":321,"y":123,"scale":900},"dungeonMap":{"x":42}}
            """);
        equal(true, config.feast.enabled(), "feature enabled retained");
        equal(321, config.feast.x(), "HUD x retained");
        equal(123, config.feast.y(), "HUD y retained");
        equal(300, config.feast.scale(), "HUD scale clamped");
        equal(42, config.dungeon.x(), "existing HUD placement retained");
        equal(25, read("{\"feast\":{\"scale\":0}}").feast.scale(), "minimum HUD scale");
    }

    @Test
    public void kernelTimeoutTextAcceptsSecondsAndRetainsTheValueForInvalidInput() {
        var config = read("{}");
        var field = SettingEntry.text("Kernel Timeout (s)", () -> Integer.toString(config.feast.kernelTimeoutSeconds()),
            config.feast::setKernelTimeoutText);
        field.setText(" 75 ");
        equal("75", field.textValue(), "integer seconds accepted");
        for (String text : new String[] {"", "abc", "1.5", "999999999999999999999", null}) {
            field.setText(text);
            equal("75", field.textValue(), "invalid input keeps current timeout");
        }
        field.setText("5");
        equal("10", field.textValue(), "small input clamps to ten seconds");
        field.setText("900");
        equal("300", field.textValue(), "large input clamps to five minutes");
    }

    @Test
    public void invalidValues() {
        KungConfig config = read("""
            {
              "dungeonMap":{"scale":0,"textScale":900,"unopenedRoomAlpha":900,"kungServerUrl":" localhost/ ",
                            "roomSyncToken":"old-shared-secret","fiveCryptPartyMessage":null},
              "bloodRushHelper":{"titleDurationTenths":100},
              "splitsOverlay":{"scale":900},
              "misc":{"loadoutKeybinds":[null,"mouse:4"],"hypixelApiKey":null,
                      "superpairsHelperScale":0,"customArrowHitSounds":null,
                      "loadoutsCloseOnlyOnChange":true,
                      "customArrowHitSoundVolumeTenths":{"ping.wav":90,"bad.wav":null}},
              "debug":null,"dungeonChatFilter":null
            }
            """);
        equal(25, config.dungeon.scale(), "map scale clamped");
        equal(200, config.dungeon.textScale(), "map text scale clamped");
        equal(100, config.dungeon.unopenedRoomAlpha(), "alpha clamped");
        equal("http://localhost:8765", config.dungeon.roomSyncServerUrl(), "server normalized");
        equal("", read("{\"dungeonMap\":{\"kungServerUrl\":\"  \"}}").dungeon.roomSyncServerUrl(), "blank server means none");
        equal(50, config.bloodRush.titleDurationTenths(), "title duration clamped");
        equal(300, config.splits.scale(), "splits scale clamped");
        equal(false, config.misc.directHypixelApiEnabled(), "null API key handled");
        equal("key:61:0", config.misc.loadoutKeybind(11), "partial keybind array expanded");
        equal("key:49:0", config.misc.loadoutKeybind(0), "null keybind uses default");
        equal("mouse:4", config.misc.loadoutKeybind(1), "custom keybind retained");
        equal(true, config.misc.loadoutsCloseOnlyOnChange(), "loadouts change-only close retained");
        equal(10, config.misc.customArrowHitVolumeTenths(), "old per-sound volumes are ignored");
        equal(false, config.debug.enabled(), "null category defaulted");
        equal(true, config.chatFilter.blessings(), "null filter defaulted");
        equal("key:61:0", read("{\"misc\":{\"loadoutKeybinds\":null}}").misc.loadoutKeybind(11), "null keybind array handled");
    }

    @Test
    public void splitTimeFormatHandlesInvalidSettings() {
        for (String value : new String[] {"null", "\"unknown\""}) {
            KungConfig config = read("{\"splitsOverlay\":{\"format\":" + value + "}}");
            equal(SplitsConfig.TimeFormat.MINUTES, config.splits.format(), "invalid format falls back to Minutes");
            equal("Minutes", config.splits.format().label(), "invalid format has a safe UI label");
        }
        SplitsConfig splits = new SplitsConfig();
        splits.setFormat(SplitsConfig.TimeFormat.SECONDS);
        equal(SplitsConfig.TimeFormat.SECONDS, splits.format(), "choice selects Seconds");
        splits.setFormat(SplitsConfig.TimeFormat.MINUTES);
        equal(SplitsConfig.TimeFormat.MINUTES, splits.format(), "choice returns to Minutes");
        splits.setFormat(null);
        equal(SplitsConfig.TimeFormat.MINUTES, splits.format(), "null setter uses Minutes");
    }

    @Test
    public void persistence() throws Exception {
        Path directory = Files.createTempDirectory("kung-config-test-");
        Path file = directory.resolve("kung.json");
        try {
            // Releases up to 0.5.0 saved a shared Room Sync token; sign-in replaced it.
            // They also saved the server URL under the old key, often a LAN address.
            Files.writeString(file, "{\"dungeonMap\":{\"roomSyncToken\":\"old-shared-secret\","
                + "\"roomSyncServerUrl\":\"http://192.168.178.3:8765\"}}");
            KungConfig config = new KungConfig(file);
            config.load();
            equal("https://kung.bengcoding.org", config.dungeon.roomSyncServerUrl(), "old saved server URL gives way to the built-in one");
            config.dungeon.setX(77);
            config.dungeon.setScale(250);
            config.dungeon.setTextScale(175);
            config.dungeon.setMimicEspEnabled(true);
            config.dungeon.setExtraScoreMessagesEnabled(true);
            config.dungeon.setMimicMessageEnabled(false);
            config.dungeon.setPrinceMessageEnabled(false);
            config.dungeon.setBatMessageEnabled(false);
            config.bloodRush.setEnabled(true);
            config.chatFilter.setNecron(false);
            config.splits.setY(123);
            config.splits.setFormat(SplitsConfig.TimeFormat.SECONDS);
            config.splits.setTimeLost(false);
            config.feast.setEnabled(true);
            config.feast.setShowInHubFarm(false);
            config.feast.setKernelTimeoutText("75");
            config.feast.setX(321);
            config.feast.setY(123);
            config.feast.setScale(150);
            config.gardenAlarms.setEnabled(true);
            config.gardenAlarms.setNoSpray(true);
            config.gardenAlarms.setVolume(35);
            config.debug.setTarantulaMessages(false);
            config.slayer.setEggSacPredictionEnabled(true);
            config.misc.setLoadoutKeybind(2, "mouse:3");
            config.misc.setChatEmotesEnabled(true);
            config.misc.setCustomArrowHitSounds("\"C:/sounds/ping.wav\", 'bell.wav'");
            String saved = Files.readString(file);
            KungConfig roundTrip = read(saved);
            equal(true, roundTrip.gardenAlarms.enabled(), "garden alarms persisted");
            equal(true, roundTrip.gardenAlarms.noSpray(), "no spray alarm persisted");
            equal(35, roundTrip.gardenAlarms.volume(), "alarm volume persisted");
            equal(77, roundTrip.dungeon.x(), "map setter persists");
            equal(250, roundTrip.dungeon.scale(), "map enlargement beyond old cap persists");
            equal(175, roundTrip.dungeon.textScale(), "map text size persists independently");
            equal(true, roundTrip.dungeon.mimicEspEnabled(), "mimic esp setter persists");
            equal(true, roundTrip.dungeon.extraScoreMessagesEnabled(), "extra score master persists");
            equal(false, roundTrip.dungeon.mimicMessageEnabled(), "mimic message switch persists");
            equal(false, roundTrip.dungeon.princeMessageEnabled(), "prince message switch persists");
            equal(false, roundTrip.dungeon.batMessageEnabled(), "bat message switch persists");
            equal(true, roundTrip.bloodRush.enabled(), "blood rush setter persists");
            equal(false, roundTrip.chatFilter.necron(), "filter setter persists");
            equal(123, roundTrip.splits.y(), "splits setter persists");
            equal(SplitsConfig.TimeFormat.SECONDS, roundTrip.splits.format(), "splits Seconds format persists");
            equal(false, roundTrip.splits.timeLost(), "hidden split time loss persists");
            equal(true, roundTrip.feast.enabled(), "Feast toggle persists");
            equal(false, roundTrip.feast.showInHubFarm(), "Hub farm toggle persists");
            equal(75, roundTrip.feast.kernelTimeoutSeconds(), "timeout text persists as integer seconds");
            equal(321, roundTrip.feast.x(), "Feast x persists");
            equal(123, roundTrip.feast.y(), "Feast y persists");
            equal(150, roundTrip.feast.scale(), "Feast scale persists");
            equal(false, roundTrip.debug.tarantulaMessages(), "debug setter persists");
            equal(true, roundTrip.slayer.eggSacPredictionEnabled(), "slayer setter persists");
            equal("mouse:3", roundTrip.misc.loadoutKeybind(2), "keybind setter persists");
            equal(true, roundTrip.misc.chatEmotesEnabled(), "chat emotes switch persists");
            equal("ping.wav, bell.wav", roundTrip.misc.customArrowHitSounds(), "quoted sound paths normalized");
            equal(false, saved.contains("tarantulaHelperDebugMessages"), "duplicate legacy debug key removed");
            equal(false, saved.contains("roomSyncToken"), "old shared token is dropped on save");
            equal(false, saved.contains("roomSyncServerUrl"), "old server URL key is dropped on save");
            equal("https://kung.bengcoding.org", roundTrip.dungeon.roomSyncServerUrl(), "built-in server persists");
            equal(false, saved.contains("onChange"), "save callbacks are not serialized");
            equal(false, saved.contains("explicitConfigFile"), "test path is not serialized");
            config.load();
            config.dungeon.setX(88);
            equal(88, read(Files.readString(file)).dungeon.x(), "reloaded category saves to its owner");
            Files.writeString(file, "{broken");
            config.load();
            equal(88, config.dungeon.x(), "failed reload retains active settings");
            equal("{broken", Files.readString(file), "failed load does not overwrite source");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void featureLifecycle() {
        CountingFeature feature = new CountingFeature();
        AppServices services = AppServices.create(KungConfig.get(), new DungeonStateTracker());
        MiscConfig previous = KungConfig.get().misc;
        try {
            feature.initialize(services);
            feature.initialize(services);
            equal(1, feature.initializations, "feature registers events once");
            equal(true, feature.initialized(), "feature exposes initialized lifecycle state");
            MiscConfig replacement = new MiscConfig();
            replacement.setLobbyHopHelperEnabled(true);
            KungConfig.get().misc = replacement;
            equal(true, feature.isEnabled(), "feature follows replaced config");
            replacement.setLobbyHopHelperEnabled(false);
            equal(false, feature.isEnabled(), "feature follows live toggle");
            feature.reset();
            equal(1, feature.resets, "feature reset hook runs while initialized");
            feature.shutdown();
            equal(false, feature.initialized(), "feature shutdown clears lifecycle state");
        } finally {
            KungConfig.get().misc = previous;
        }
    }

    private static void equal(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class CountingFeature extends ConfigurableFeature<MiscConfig> {
        private int initializations;
        private int resets;

        private CountingFeature() { super(config -> config.misc); }
        @Override protected void onInitialize() { initializations++; }
        @Override protected void onReset() { resets++; }
        @Override public boolean isEnabled() { return config().lobbyHopHelperEnabled(); }
    }
}
