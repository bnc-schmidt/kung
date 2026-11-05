package com.github.beng420.kung.config;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.category.BRHelperConfig;
import com.github.beng420.kung.config.category.ConfigCategory;
import com.github.beng420.kung.config.category.DebugConfig;
import com.github.beng420.kung.config.category.DungeonChatFilterConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.FeastConfig;
import com.github.beng420.kung.config.category.GardenAlarmsConfig;
import com.github.beng420.kung.config.category.ForagingConfig;
import com.github.beng420.kung.config.category.GreenhouseConfig;
import com.github.beng420.kung.config.category.HitboxesConfig;
import com.github.beng420.kung.config.category.InvisibugConfig;
import com.github.beng420.kung.config.category.MiningConfig;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.PlotCleaningConfig;
import com.github.beng420.kung.config.category.SlayerConfig;
import com.github.beng420.kung.config.category.SafariConfig;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.config.category.WaypointsConfig;
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
import java.util.List;
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
    public GardenAlarmsConfig gardenAlarms = new GardenAlarmsConfig();
    public HitboxesConfig hitboxes = new HitboxesConfig();
    public GreenhouseConfig greenhouse = new GreenhouseConfig();
    public InvisibugConfig invisibug = new InvisibugConfig();
    public PlotCleaningConfig plotCleaning = new PlotCleaningConfig();
    public MiningConfig mining = new MiningConfig();
    public ForagingConfig foraging = new ForagingConfig();
    public WaypointsConfig waypoints = new WaypointsConfig();

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

    /** A migration changed the loaded config: write it back once loading is over. */
    private transient boolean pendingSave;

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
                    pendingSave |= loaded.pendingSave; // read() already migrated the loaded copy
                    dungeon = loaded.dungeon;
                    bloodRush = loaded.bloodRush;
                    chatFilter = loaded.chatFilter;
                    splits = loaded.splits;
                    debug = loaded.debug;
                    misc = loaded.misc;
                    slayer = loaded.slayer;
                    safari = loaded.safari;
                    feast = loaded.feast;
                    gardenAlarms = loaded.gardenAlarms;
                    hitboxes = loaded.hitboxes;
                    greenhouse = loaded.greenhouse;
                    invisibug = loaded.invisibug;
                    plotCleaning = loaded.plotCleaning;
                    mining = loaded.mining;
                    foraging = loaded.foraging;
                    waypoints = loaded.waypoints;
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
        if (loadedSuccessfully && (!exists || migrateLegacy || pendingSave)) {
            pendingSave = false;
            save();
        }
    }

    static KungConfig read(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        GreenhouseConfig.sanitize(json);
        json.entrySet().removeIf(entry -> entry.getValue().isJsonNull()); // a null section keeps its defaults
        KungConfig loaded = GSON.fromJson(json, KungConfig.class);
        loaded.loading = true;
        // Older releases stored these switches in both the feature and debug sections.
        JsonObject dungeonJson = object(json, "dungeonMap");
        JsonObject slayerJson = object(json, "slayer");
        JsonObject debugJson = object(json, "debug");
        if (!hasValue(debugJson, "dungeonMessages") && hasValue(dungeonJson, "debugMessages")) {
            loaded.debug.setDungeonMessages(dungeonJson.get("debugMessages").getAsBoolean());
        }
        if (hasValue(slayerJson, "tarantulaHelperDebugMessages")) {
            loaded.debug.setTarantulaMessages(slayerJson.get("tarantulaHelperDebugMessages").getAsBoolean());
        }
        // "6th Visitor Alarm" became the Max Visitor Alarm inside Garden Alarms.
        JsonObject visitorAlarmJson = object(json, "visitorAlarm");
        if (!hasValue(json, "gardenAlarms") && !visitorAlarmJson.isEmpty()) {
            loaded.gardenAlarms.migrateVisitorAlarm(
                hasValue(visitorAlarmJson, "enabled") && visitorAlarmJson.get("enabled").getAsBoolean(),
                hasValue(visitorAlarmJson, "volume") ? visitorAlarmJson.get("volume").getAsInt() : null);
            loaded.pendingSave = true;
        }
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

    private List<ConfigCategory> categories() {
        return List.of(dungeon, bloodRush, chatFilter, splits, debug, misc, slayer, safari, feast, gardenAlarms,
            hitboxes, greenhouse, invisibug, plotCleaning, mining, foraging, waypoints);
    }

    private void bindCategories() {
        categories().forEach(category -> category.onChange(this::save));
    }

    private void normalize() {
        if (dungeon.migrateJerrySyncDefault()) pendingSave = true;
        categories().forEach(ConfigCategory::normalize);
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject()
            ? parent.getAsJsonObject(name) : new JsonObject();
    }

    private static boolean hasValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull();
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
