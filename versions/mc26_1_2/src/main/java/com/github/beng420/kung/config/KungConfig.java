package com.github.beng420.kung.config;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.category.BRHelperConfig;
import com.github.beng420.kung.config.category.DebugConfig;
import com.github.beng420.kung.config.category.DungeonChatFilterConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.FeastConfig;
import com.github.beng420.kung.config.category.GreenhouseConfig;
import com.github.beng420.kung.config.category.HitboxesConfig;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.SlayerConfig;
import com.github.beng420.kung.config.category.SafariConfig;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.config.category.VisitorAlarmConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class KungConfig {
    public static final KungConfig INSTANCE = new KungConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("dungeonMap")
    public DungeonConfig dungeon = new DungeonConfig();
    @SerializedName("bloodRushHelper")
    public BRHelperConfig bloodRush = new BRHelperConfig();
    @SerializedName("dungeonChatFilter")
    public DungeonChatFilterConfig chatFilter = new DungeonChatFilterConfig();
    @SerializedName("splitsOverlay")
    public SplitsConfig splits = new SplitsConfig();
    public DebugConfig debug = new DebugConfig();
    public MiscConfig misc = new MiscConfig();
    public SlayerConfig slayer = new SlayerConfig();
    public SafariConfig safari = new SafariConfig();
    public FeastConfig feast = new FeastConfig();
    public VisitorAlarmConfig visitorAlarm = new VisitorAlarmConfig();
    public HitboxesConfig hitboxes = new HitboxesConfig();
    public GreenhouseConfig greenhouse = new GreenhouseConfig();

    private transient Path explicitConfigFile;
    private transient boolean loading;

    private KungConfig() {
        bindCategories();
    }

    KungConfig(Path configFile) {
        this();
        explicitConfigFile = configFile;
    }

    public static KungConfig get() {
        return INSTANCE;
    }

    public void load() {
        Path file = configFile();
        boolean migrateLegacy = false;
        if (explicitConfigFile == null && !Files.exists(file) && Files.exists(legacyConfigFile())) {
            file = legacyConfigFile();
            migrateLegacy = true;
        }

        boolean exists = Files.exists(file);
        boolean loadedSuccessfully = !exists;
        loading = true;
        try {
            if (exists) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    KungConfig loaded = read(reader);
                    dungeon = loaded.dungeon;
                    bloodRush = loaded.bloodRush;
                    chatFilter = loaded.chatFilter;
                    splits = loaded.splits;
                    debug = loaded.debug;
                    misc = loaded.misc;
                    slayer = loaded.slayer;
                    safari = loaded.safari;
                    feast = loaded.feast;
                    visitorAlarm = loaded.visitorAlarm;
                    hitboxes = loaded.hitboxes;
                    greenhouse = loaded.greenhouse;
                    loadedSuccessfully = true;
                }
            }
            bindCategories();
            normalize();
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Failed to load kung config.", exception);
        } finally {
            bindCategories();
            loading = false;
        }
        if (loadedSuccessfully && (!exists || migrateLegacy)) {
            save();
        }
    }

    static KungConfig read(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        GreenhouseConfig.sanitize(json);
        KungConfig loaded = GSON.fromJson(json, KungConfig.class);
        loaded.loading = true;
        loaded.ensureCategories();
        // Older releases stored these switches in both the feature and debug sections.
        JsonObject dungeonJson = object(json, "dungeonMap");
        JsonObject slayerJson = object(json, "slayer");
        JsonObject debugJson = object(json, "debug");
        JsonObject miscJson = object(json, "misc");
        if (!hasValue(debugJson, "dungeonMessages") && hasValue(dungeonJson, "debugMessages")) {
            loaded.debug.setDungeonMessages(dungeonJson.get("debugMessages").getAsBoolean());
        }
        if (hasValue(slayerJson, "tarantulaHelperDebugMessages")) {
            loaded.debug.setTarantulaMessages(slayerJson.get("tarantulaHelperDebugMessages").getAsBoolean());
        }
        migrateLegacyChatCommandSettings(loaded, miscJson);
        loaded.normalize();
        loaded.loading = false;
        return loaded;
    }

    public void save() {
        if (loading) return;
        try {
            Path file = configFile();
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Failed to save kung config.", exception);
        }
    }

    private void ensureCategories() {
        if (dungeon == null) dungeon = new DungeonConfig();
        if (bloodRush == null) bloodRush = new BRHelperConfig();
        if (chatFilter == null) chatFilter = new DungeonChatFilterConfig();
        if (splits == null) splits = new SplitsConfig();
        if (debug == null) debug = new DebugConfig();
        if (misc == null) misc = new MiscConfig();
        if (slayer == null) slayer = new SlayerConfig();
        if (safari == null) safari = new SafariConfig();
        if (feast == null) feast = new FeastConfig();
        if (visitorAlarm == null) visitorAlarm = new VisitorAlarmConfig();
        if (hitboxes == null) hitboxes = new HitboxesConfig();
        if (greenhouse == null) greenhouse = new GreenhouseConfig();
    }

    private void bindCategories() {
        dungeon.onChange(this::save);
        bloodRush.onChange(this::save);
        chatFilter.onChange(this::save);
        splits.onChange(this::save);
        debug.onChange(this::save);
        misc.onChange(this::save);
        slayer.onChange(this::save);
        safari.onChange(this::save);
        feast.onChange(this::save);
        visitorAlarm.onChange(this::save);
        hitboxes.onChange(this::save);
        greenhouse.onChange(this::save);
    }

    private void normalize() {
        ensureCategories();
        dungeon.setScale(dungeon.scale());
        dungeon.setTextScale(dungeon.textScale());
        dungeon.setUnopenedRoomAlpha(dungeon.unopenedRoomAlpha());
        dungeon.setFiveCryptPartyMessage(dungeon.fiveCryptPartyMessage());
        dungeon.setRoomSyncServerUrl(dungeon.roomSyncServerUrl());
        bloodRush.setTitleDurationTenths(bloodRush.titleDurationTenths());
        splits.setScale(splits.scale());
        safari.setScale(safari.scale());
        feast.setScale(feast.scale());
        feast.setKernelTimeoutSeconds(feast.kernelTimeoutSeconds());
        visitorAlarm.setVolume(visitorAlarm.volume());
        splits.setFormat(splits.format());
        misc.setHypixelApiKey(misc.hypixelApiKey());
        misc.setSuperpairsHelperScale(misc.superpairsHelperScale());
        misc.setCustomArrowHitSounds(misc.customArrowHitSounds());
        misc.setCustomWitherShieldExpireSounds(misc.customWitherShieldExpireSounds());
        misc.setCustomArrowHitVolumeTenths(misc.customArrowHitVolumeTenths());
        misc.setCustomArrowHitPitchHundredths(misc.customArrowHitPitchHundredths());
        misc.setCustomWitherShieldExpireVolumeTenths(misc.customWitherShieldExpireVolumeTenths());
        misc.setCustomWitherShieldExpirePitchHundredths(misc.customWitherShieldExpirePitchHundredths());
        misc.normalize();
        hitboxes.normalize();
        greenhouse.normalize();
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject()
            ? parent.getAsJsonObject(name) : new JsonObject();
    }

    private static boolean hasValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull();
    }

    private static void migrateLegacyChatCommandSettings(KungConfig loaded, JsonObject miscJson) {
        migrateLegacySharedCommandSetting(miscJson, "ChatCommandEnabled",
            loaded.misc::setC50ChatCommandEnabled, loaded.misc::setCa50ChatCommandEnabled);
        migrateLegacySharedCommandSetting(miscJson, "PartyCommandsEnabled",
            loaded.misc::setC50PartyCommandsEnabled, loaded.misc::setCa50PartyCommandsEnabled);
        migrateLegacySharedCommandSetting(miscJson, "GuildCommandsEnabled",
            loaded.misc::setC50GuildCommandsEnabled, loaded.misc::setCa50GuildCommandsEnabled);
        migrateLegacySharedCommandSetting(miscJson, "AllChatCommandsEnabled",
            loaded.misc::setC50AllChatCommandsEnabled, loaded.misc::setCa50AllChatCommandsEnabled);
        migrateLegacySharedCommandSetting(miscJson, "PrivateCommandsEnabled",
            loaded.misc::setC50PrivateCommandsEnabled, loaded.misc::setCa50PrivateCommandsEnabled);
    }

    /** The old shared "cata"/"ca50" key fills whichever of the "c50"/"ca50" keys the file leaves unset. */
    private static void migrateLegacySharedCommandSetting(
        JsonObject miscJson,
        String suffix,
        java.util.function.Consumer<Boolean> c50Setter,
        java.util.function.Consumer<Boolean> ca50Setter
    ) {
        for (String legacyName : new String[] {"cata" + suffix, "ca50" + suffix}) {
            if (!hasValue(miscJson, legacyName)) {
                continue;
            }
            boolean legacyValue = miscJson.get(legacyName).getAsBoolean();
            if (!hasValue(miscJson, "c50" + suffix)) {
                c50Setter.accept(legacyValue);
            }
            if (!hasValue(miscJson, "ca50" + suffix)) {
                ca50Setter.accept(legacyValue);
            }
        }
    }

    public static Path configDirectory() {
        return com.github.beng420.kung.runtime.KungPaths.fileLayout().settingsDirectory();
    }

    private Path configFile() {
        return explicitConfigFile == null ? configDirectory().resolve("kung.json") : explicitConfigFile;
    }

    private static Path legacyConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("kung.json");
    }
}
