package com.github.beng420.kung.config.category;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.google.gson.annotations.SerializedName;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class DungeonConfig extends ConfigCategory {
    private boolean enabled = false;
    private int x = 8;
    private int y = 8;
    private int scale = 100;
    private int textScale = 100;
    private int unopenedRoomAlpha = 12;
    private boolean showHeader = true;
    private boolean showInBoss = true;
    private boolean princeIconsEnabled = true;
    private Map<String, Boolean> princeRoomOverrides = new HashMap<>();
    private boolean mimicEspEnabled = false;
    private boolean iceSprayHighlightEnabled = false;
    private int iceSprayBoxSize = 100;
    /** Pre-merge master of M7 Dragon Debuff; only read to seed witherDragonsEnabled. */
    private boolean dragonDebuffEnabled = false;
    private boolean dragonDebuffTrackerEnabled = true;
    private boolean dragonDebuffHudEnabled = true;
    private boolean dragonDebuffChatEnabled = true;
    private DragonDebuffScope dragonDebuffScope = DragonDebuffScope.ALL_DRAGONS;
    private int dragonDebuffX = 8;
    private int dragonDebuffY = 230;
    private int dragonDebuffScale = 85;
    private boolean dragonPrioEnabled = false;
    // Archer is always Arch and Berserker always Bers; the other three classes pick a split.
    private DragonPrioSplit dragonPrioMage = DragonPrioSplit.BERS;
    private DragonPrioSplit dragonPrioHealer = DragonPrioSplit.ARCH;
    private DragonPrioSplit dragonPrioTank = DragonPrioSplit.ARCH;
    private DragonPrioUnit dragonPrioUnit = DragonPrioUnit.TICKS;
    private boolean dragonPrioHudEnabled = true;
    private int dragonPrioX = 232;
    private int dragonPrioY = 150;
    private int dragonPrioScale = 200;
    /** Pre-merge master of M7 Dragon Helper; only read to seed witherDragonsEnabled. */
    private boolean m7DragonHelperEnabled = false;
    /** Null until toggled, so a config from before the merge keeps whichever dragon feature it had on. */
    private Boolean witherDragonsEnabled;
    private boolean dragonFlightPathsEnabled = true;
    private int dragonLineThickness = 100;
    // New keys in 0.5.0: every config had the old defaults (on, Neck, Box Centre) written out,
    // though only the developer could see these rows. The old keys are dropped on the next save.
    @SerializedName("dragonSpawnMarkers")
    private boolean dragonSpawnMarkersEnabled = false;
    private boolean dragonStatueBoxesEnabled = true;
    private boolean dragonCountNotificationsEnabled = true;
    private boolean dragonHpEnabled = false;
    private boolean devDragonDiagnosticsEnabled = false;
    private boolean coloredPillarsEnabled = false;
    private boolean wishAlertEnabled = false;
    private int wishAlertLowHealthPercent = 20;
    private DragonMarkerMode dragonMarkerMode = DragonMarkerMode.CORE;
    @SerializedName("dragonPathPart")
    private DragonPart dragonTrailPart = DragonPart.BODY;
    @SerializedName("dragonMarkerCoreParts")
    private Set<DragonPart> dragonCoreParts = EnumSet.of(DragonPart.BODY);
    private DragonAimMode dragonAimMode = DragonAimMode.AUTO;
    private boolean devWishAlertAnyClassEnabled = false;
    private PillarMaterial pillarMaterial = PillarMaterial.WOOL;
    private boolean forcePaulScoreEnabled = false;
    private boolean jerryReminderEnabled = true;
    private boolean playerTrackingEnabled = false;
    private boolean runStatsReportEnabled = true;
    private boolean playerStatsReportEnabled = true;
    private boolean deathMessagesEnabled = false;
    private boolean deathMessagesShareTotalEnabled = false;
    private boolean deathMessagesShareIndividualEnabled = false;
    private boolean extraScoreMessagesEnabled = false;
    private boolean mimicMessageEnabled = true;
    private boolean princeMessageEnabled = true;
    private boolean batMessageEnabled = true;
    private boolean debugRoomCrypts = false;
    private boolean debugRoomMatches = false;
    private boolean localRoomDataEnabled = false;
    private String roomDataProjectDirectory = "";
    private boolean fiveCryptPartyMessageEnabled = false;
    private String fiveCryptPartyMessage = "We got all 5 crypts (✿◠‿◠)";
    private boolean cryptProgressPartyMessageEnabled = false;
    private boolean fiveCryptTitleEnabled = false;
    private boolean quizReminderEnabled = false;
    private String quizReminderMessage = DEFAULT_QUIZ_REMINDER_MESSAGE;
    private int quizReminderDelaySeconds = 10;
    private boolean quizReminderRepeatEnabled = false;
    private int quizReminderRepeatSeconds = 0;
    private boolean quizReminderPartyEnabled = false;

    // Room Sync Settings
    private boolean roomSyncEnabled = false;
    private boolean roomSyncUploadEnabled = false;
    private boolean jerrySyncEnabled = true;
    /** Set once the switch has been turned on for existing configs (it was off by default until 0.6.3). */
    private boolean jerrySyncDefaultedOn = false;
    // New key when the built-in server arrived: every config had "" or a LAN address written out.
    // Blank still means no server; the old key is dropped on the next save.
    @SerializedName("kungServerUrl")
    private String roomSyncServerUrl = "https://kung.bengcoding.org";

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public int x() { return x; }
    public void setX(int value) { x = value; save(); }
    public int y() { return y; }
    public void setY(int value) { y = value; save(); }
    public int scale() { return scale; }
    public int textScale() { return textScale; }
    public int unopenedRoomAlpha() { return unopenedRoomAlpha; }
    public boolean showHeader() { return showHeader; }
    public void setShowHeader(boolean value) { showHeader = value; save(); }
    public boolean showInBoss() { return showInBoss; }
    public void setShowInBoss(boolean value) { showInBoss = value; save(); }
    public boolean princeIconsEnabled() { return princeIconsEnabled; }
    public void setPrinceIconsEnabled(boolean value) { princeIconsEnabled = value; save(); }
    public Boolean roomPrinceOverride(String roomKey) {
        return princeRoomOverrides == null ? null : princeRoomOverrides.get(roomKey);
    }
    public void setRoomPrinceOverride(String roomKey, boolean prince) {
        if (princeRoomOverrides == null) princeRoomOverrides = new HashMap<>();
        princeRoomOverrides.put(roomKey, prince);
        save();
    }
    public boolean mimicEspEnabled() { return mimicEspEnabled; }
    public void setMimicEspEnabled(boolean value) { mimicEspEnabled = value; save(); }
    public boolean iceSprayHighlightEnabled() { return iceSprayHighlightEnabled; }
    public void setIceSprayHighlightEnabled(boolean value) { iceSprayHighlightEnabled = value; save(); }
    public int iceSprayBoxSize() { return Math.clamp(iceSprayBoxSize, 100, 200); }
    public void setIceSprayBoxSize(int value) { iceSprayBoxSize = Math.clamp(value, 100, 200); save(); }
    public boolean witherDragonsEnabled() {
        return witherDragonsEnabled != null ? witherDragonsEnabled : dragonDebuffEnabled || m7DragonHelperEnabled;
    }
    public void setWitherDragonsEnabled(boolean value) { witherDragonsEnabled = value; save(); }
    public boolean dragonDebuffTrackerEnabled() { return dragonDebuffTrackerEnabled; }
    public void setDragonDebuffTrackerEnabled(boolean value) { dragonDebuffTrackerEnabled = value; save(); }
    /** Debuff tracking in effect: the Wither Dragons master plus its own switch. */
    public boolean dragonDebuffEnabled() { return witherDragonsEnabled() && dragonDebuffTrackerEnabled; }
    public boolean dragonDebuffHudEnabled() { return dragonDebuffHudEnabled; }
    public void setDragonDebuffHudEnabled(boolean value) { dragonDebuffHudEnabled = value; save(); }
    public boolean dragonDebuffChatEnabled() { return dragonDebuffChatEnabled; }
    public void setDragonDebuffChatEnabled(boolean value) { dragonDebuffChatEnabled = value; save(); }
    public boolean dragonDebuffHudShown() { return dragonDebuffEnabled() && dragonDebuffHudEnabled; }
    /** The HUD editor's switch: turning the HUD on also turns the tracker and master on, or nothing would show. */
    public void setDragonDebuffHudShown(boolean value) {
        dragonDebuffHudEnabled = value;
        if (value) {
            dragonDebuffTrackerEnabled = true;
            witherDragonsEnabled = true;
        }
        save();
    }
    public DragonDebuffScope dragonDebuffScope() { return dragonDebuffScope == null ? DragonDebuffScope.ALL_DRAGONS : dragonDebuffScope; }
    public void setDragonDebuffScope(DragonDebuffScope value) { dragonDebuffScope = value == null ? DragonDebuffScope.ALL_DRAGONS : value; save(); }
    public int dragonDebuffX() { return dragonDebuffX; }
    public int dragonDebuffY() { return dragonDebuffY; }
    public int dragonDebuffScale() { return Math.clamp(dragonDebuffScale, 25, 300); }
    public void setDragonDebuffX(int value) { dragonDebuffX = value; save(); }
    public void setDragonDebuffY(int value) { dragonDebuffY = value; save(); }
    public void setDragonDebuffScale(int value) { dragonDebuffScale = Math.clamp(value, 25, 300); save(); }
    public boolean dragonPrioEnabled() { return dragonPrioEnabled; }
    public void setDragonPrioEnabled(boolean value) { dragonPrioEnabled = value; save(); }
    public DragonPrioSplit dragonPrioMage() { return dragonPrioMage == null ? DragonPrioSplit.BERS : dragonPrioMage; }
    public void setDragonPrioMage(DragonPrioSplit value) { dragonPrioMage = value == null ? DragonPrioSplit.BERS : value; save(); }
    public DragonPrioSplit dragonPrioHealer() { return dragonPrioHealer == null ? DragonPrioSplit.ARCH : dragonPrioHealer; }
    public void setDragonPrioHealer(DragonPrioSplit value) { dragonPrioHealer = value == null ? DragonPrioSplit.ARCH : value; save(); }
    public DragonPrioSplit dragonPrioTank() { return dragonPrioTank == null ? DragonPrioSplit.ARCH : dragonPrioTank; }
    public void setDragonPrioTank(DragonPrioSplit value) { dragonPrioTank = value == null ? DragonPrioSplit.ARCH : value; save(); }
    public DragonPrioUnit dragonPrioUnit() { return dragonPrioUnit == null ? DragonPrioUnit.TICKS : dragonPrioUnit; }
    public void setDragonPrioUnit(DragonPrioUnit value) { dragonPrioUnit = value == null ? DragonPrioUnit.TICKS : value; save(); }
    public boolean dragonPrioHudShown() { return witherDragonsEnabled() && dragonPrioEnabled && dragonPrioHudEnabled; }
    /** The HUD editor's switch: turning the countdown on also turns Dragon Prio and the master on, or nothing would show. */
    public void setDragonPrioHudShown(boolean value) {
        dragonPrioHudEnabled = value;
        if (value) {
            dragonPrioEnabled = true;
            witherDragonsEnabled = true;
        }
        save();
    }
    public int dragonPrioX() { return dragonPrioX; }
    public int dragonPrioY() { return dragonPrioY; }
    public int dragonPrioScale() { return Math.clamp(dragonPrioScale, 25, 300); }
    public void setDragonPrioX(int value) { dragonPrioX = value; save(); }
    public void setDragonPrioY(int value) { dragonPrioY = value; save(); }
    public void setDragonPrioScale(int value) { dragonPrioScale = Math.clamp(value, 25, 300); save(); }
    /** The helper has no switch of its own any more; Wither Dragons turns it on. */
    public boolean m7DragonHelperEnabled() { return witherDragonsEnabled(); }
    public boolean dragonFlightPathsEnabled() { return dragonFlightPathsEnabled; }
    public void setDragonFlightPathsEnabled(boolean value) { dragonFlightPathsEnabled = value; save(); }
    public int dragonLineThickness() { return Math.clamp(dragonLineThickness, 25, 400); }
    public void setDragonLineThickness(int value) { dragonLineThickness = Math.clamp(value, 25, 400); save(); }
    public boolean dragonSpawnMarkersEnabled() { return dragonSpawnMarkersEnabled; }
    public void setDragonSpawnMarkersEnabled(boolean value) { dragonSpawnMarkersEnabled = value; save(); }
    public boolean dragonStatueBoxesEnabled() { return dragonStatueBoxesEnabled; }
    public void setDragonStatueBoxesEnabled(boolean value) { dragonStatueBoxesEnabled = value; save(); }
    public boolean dragonCountNotificationsEnabled() { return dragonCountNotificationsEnabled; }
    public void setDragonCountNotificationsEnabled(boolean value) { dragonCountNotificationsEnabled = value; save(); }
    public boolean dragonHpEnabled() { return dragonHpEnabled; }
    public void setDragonHpEnabled(boolean value) { dragonHpEnabled = value; save(); }
    public boolean devDragonDiagnosticsEnabled() { return devDragonDiagnosticsEnabled && KungDeveloperAccess.allowed(); }
    public void setDevDragonDiagnosticsEnabled(boolean value) { devDragonDiagnosticsEnabled = value; save(); }
    public DragonMarkerMode dragonMarkerMode() { return dragonMarkerMode; }
    public void setDragonMarkerMode(DragonMarkerMode value) {
        dragonMarkerMode = value == null ? DragonMarkerMode.CORE : value;
        save();
    }
    public DragonPart dragonTrailPart() { return dragonTrailPart == null ? DragonPart.BODY : dragonTrailPart; }
    public void setDragonTrailPart(DragonPart value) { dragonTrailPart = value; save(); }
    public DragonAimMode dragonAimMode() { return dragonAimMode == null ? DragonAimMode.AUTO : dragonAimMode; }
    public void setDragonAimMode(DragonAimMode value) { dragonAimMode = value; save(); }
    public boolean dragonCorePart(DragonPart part) { return dragonCoreParts != null && dragonCoreParts.contains(part); }
    public void toggleDragonCorePart(DragonPart part) {
        if (dragonCoreParts == null) dragonCoreParts = EnumSet.noneOf(DragonPart.class);
        if (!dragonCoreParts.remove(part)) dragonCoreParts.add(part);
        save();
    }
    public boolean wishAlertEnabled() { return wishAlertEnabled; }
    public boolean devWishAlertAnyClassEnabled() { return devWishAlertAnyClassEnabled; }
    public void setDevWishAlertAnyClassEnabled(boolean value) {
        devWishAlertAnyClassEnabled = value;
        save();
    }
    public void setWishAlertEnabled(boolean value) { wishAlertEnabled = value; save(); }
    public int wishAlertLowHealthPercent() { return Math.clamp(wishAlertLowHealthPercent, 0, 100); }
    public void setWishAlertLowHealthPercent(int value) { wishAlertLowHealthPercent = Math.clamp(value, 0, 100); save(); }
    public boolean coloredPillarsEnabled() { return coloredPillarsEnabled; }
    public void setColoredPillarsEnabled(boolean value) { coloredPillarsEnabled = value; save(); }
    public PillarMaterial pillarMaterial() {
        // A config copied from the full build may still hold Glass.
        return pillarMaterial == null || KungBuild.MODRINTH && pillarMaterial == PillarMaterial.GLASS
            ? PillarMaterial.WOOL : pillarMaterial;
    }
    public void setPillarMaterial(PillarMaterial value) { pillarMaterial = value == null ? PillarMaterial.WOOL : value; save(); }
    public boolean forcePaulScoreEnabled() { return forcePaulScoreEnabled; }
    public void setForcePaulScoreEnabled(boolean value) { forcePaulScoreEnabled = value; save(); }
    public boolean jerryReminderEnabled() { return jerryReminderEnabled; }
    public void setJerryReminderEnabled(boolean value) { jerryReminderEnabled = value; save(); }
    public boolean playerTrackingEnabled() { return playerTrackingEnabled; }
    public void setPlayerTrackingEnabled(boolean value) { playerTrackingEnabled = value; save(); }
    public boolean runStatsReportEnabled() { return runStatsReportEnabled; }
    public void setRunStatsReportEnabled(boolean value) { runStatsReportEnabled = value; save(); }
    public boolean playerStatsReportEnabled() { return playerStatsReportEnabled; }
    public void setPlayerStatsReportEnabled(boolean value) { playerStatsReportEnabled = value; save(); }
    public boolean deathMessagesEnabled() { return deathMessagesEnabled; }
    public void setDeathMessagesEnabled(boolean value) { deathMessagesEnabled = value; save(); }
    public boolean deathMessagesShareTotalEnabled() { return deathMessagesShareTotalEnabled; }
    public void setDeathMessagesShareTotalEnabled(boolean value) { deathMessagesShareTotalEnabled = value; save(); }
    public boolean deathMessagesShareIndividualEnabled() { return deathMessagesShareIndividualEnabled; }
    public void setDeathMessagesShareIndividualEnabled(boolean value) { deathMessagesShareIndividualEnabled = value; save(); }
    public boolean extraScoreMessagesEnabled() { return extraScoreMessagesEnabled; }
    public void setExtraScoreMessagesEnabled(boolean value) { extraScoreMessagesEnabled = value; save(); }
    public boolean mimicMessageEnabled() { return mimicMessageEnabled; }
    public void setMimicMessageEnabled(boolean value) { mimicMessageEnabled = value; save(); }
    public boolean princeMessageEnabled() { return princeMessageEnabled; }
    public void setPrinceMessageEnabled(boolean value) { princeMessageEnabled = value; save(); }
    public boolean batMessageEnabled() { return batMessageEnabled; }
    public void setBatMessageEnabled(boolean value) { batMessageEnabled = value; save(); }
    public boolean debugRoomCrypts() { return debugRoomCrypts; }
    public void setDebugRoomCrypts(boolean value) { debugRoomCrypts = value; save(); }
    public boolean debugRoomMatches() { return debugRoomMatches; }
    public void setDebugRoomMatches(boolean value) { debugRoomMatches = value; save(); }
    public boolean localRoomDataEnabled() { return localRoomDataEnabled; }
    public void setLocalRoomDataEnabled(boolean value) { localRoomDataEnabled = value; save(); }
    public String roomDataProjectDirectory() { return roomDataProjectDirectory == null ? "" : roomDataProjectDirectory; }
    public void setRoomDataProjectDirectory(String value) {
        roomDataProjectDirectory = value == null ? "" : value.strip();
        save();
    }
    public boolean fiveCryptPartyMessageEnabled() { return fiveCryptPartyMessageEnabled; }
    public void setFiveCryptPartyMessageEnabled(boolean value) { fiveCryptPartyMessageEnabled = value; save(); }
    public boolean cryptProgressPartyMessageEnabled() { return cryptProgressPartyMessageEnabled; }
    public void setCryptProgressPartyMessageEnabled(boolean value) { cryptProgressPartyMessageEnabled = value; save(); }
    public static final String DEFAULT_QUIZ_REMINDER_MESSAGE = "Quiz is waiting for an answer!";
    public static final int QUIZ_REMINDER_MIN_SECONDS = 5;
    public static final int QUIZ_REMINDER_MAX_SECONDS = 60;
    private static final int QUIZ_REMINDER_MAX_LENGTH = 200;

    public boolean quizReminderEnabled() { return quizReminderEnabled; }
    public void setQuizReminderEnabled(boolean value) { quizReminderEnabled = value; save(); }
    public String quizReminderMessage() {
        String message = quizReminderMessage == null ? "" : quizReminderMessage.strip();
        if (message.startsWith("[Kung]")) message = message.substring("[Kung]".length()).strip();
        if (message.length() > QUIZ_REMINDER_MAX_LENGTH) message = message.substring(0, QUIZ_REMINDER_MAX_LENGTH).strip();
        return message.isBlank() ? DEFAULT_QUIZ_REMINDER_MESSAGE : message;
    }
    public void setQuizReminderMessage(String value) { quizReminderMessage = value == null ? "" : value; save(); }
    /** Seconds a question may stay unanswered before the reminder. */
    public int quizReminderDelaySeconds() {
        return Math.clamp(quizReminderDelaySeconds, QUIZ_REMINDER_MIN_SECONDS, QUIZ_REMINDER_MAX_SECONDS);
    }
    public void setQuizReminderDelaySeconds(int value) { quizReminderDelaySeconds = value; save(); }
    public boolean quizReminderRepeatEnabled() { return quizReminderRepeatEnabled; }
    public void setQuizReminderRepeatEnabled(boolean value) { quizReminderRepeatEnabled = value; save(); }
    /** Seconds between repeats; 0 (the default) repeats at the delay. */
    public int quizReminderRepeatSeconds() {
        return quizReminderRepeatSeconds <= 0 ? quizReminderDelaySeconds()
            : Math.clamp(quizReminderRepeatSeconds, QUIZ_REMINDER_MIN_SECONDS, QUIZ_REMINDER_MAX_SECONDS);
    }
    /** The raw slider value: 0 means "same as the delay". */
    public int quizReminderRepeatSetting() { return quizReminderRepeatSeconds <= 0 ? 0 : quizReminderRepeatSeconds(); }
    public void setQuizReminderRepeatSeconds(int value) { quizReminderRepeatSeconds = value; save(); }
    public boolean quizReminderPartyEnabled() { return quizReminderPartyEnabled; }
    public void setQuizReminderPartyEnabled(boolean value) { quizReminderPartyEnabled = value; save(); }

    public boolean fiveCryptTitleEnabled() { return fiveCryptTitleEnabled; }
    public void setFiveCryptTitleEnabled(boolean value) { fiveCryptTitleEnabled = value; save(); }
    public boolean roomSyncEnabled() { return roomSyncEnabled; }
    public void setRoomSyncEnabled(boolean value) { roomSyncEnabled = value; save(); }
    public boolean roomSyncUploadEnabled() { return roomSyncUploadEnabled; }
    public void setRoomSyncUploadEnabled(boolean value) { roomSyncUploadEnabled = value; save(); }
    /**
     * Jerry Mayor sync became opt-out in 0.6.3: a config written before it, which holds the old default (off), gets it
     * switched on once. Whoever turns it off afterwards keeps it off. Returns true when it changed something to save.
     */
    public boolean migrateJerrySyncDefault() {
        if (jerrySyncDefaultedOn) return false;
        jerrySyncDefaultedOn = true;
        jerrySyncEnabled = true;
        return true;
    }

    public boolean jerrySyncEnabled() { return jerrySyncEnabled; }
    public void setJerrySyncEnabled(boolean value) { jerrySyncEnabled = value; save(); }
    public String roomSyncServerUrl() { return roomSyncServerUrl == null ? "" : roomSyncServerUrl; }

    public void setScale(int scale) {
        this.scale = Math.clamp(scale, 25, 300);
        save();
    }

    public void setTextScale(int textScale) {
        this.textScale = Math.clamp(textScale, 50, 200);
        save();
    }

    public void setUnopenedRoomAlpha(int alpha) {
        this.unopenedRoomAlpha = Math.clamp(alpha, 0, 100);
        save();
    }

    public String fiveCryptPartyMessage() {
        return (fiveCryptPartyMessage == null || fiveCryptPartyMessage.isBlank())
            ? "We got all 5 crypts (✿◠‿◠)"
            : fiveCryptPartyMessage;
    }

    public void setFiveCryptPartyMessage(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.startsWith("[Kung]")) {
            normalized = normalized.substring("[Kung]".length()).strip();
        }
        this.fiveCryptPartyMessage = normalized.isBlank() ? "We got all 5 crypts (✿◠‿◠)" : normalized;
        save();
    }

    public void setRoomSyncServerUrl(String serverUrl) {
        this.roomSyncServerUrl = normalizeRoomSyncServerUrl(serverUrl);
        save();
    }

    public enum PillarMaterial {
        WOOL("Wool"), GLASS("Glass"), TERRACOTTA("Terracotta");

        private final String label;
        PillarMaterial(String label) { this.label = label; }
        public String label() { return label; }

        /** See-through Glass pillars are not offered in the Modrinth build. */
        public static PillarMaterial[] choices() {
            return KungBuild.MODRINTH ? new PillarMaterial[] {WOOL, TERRACOTTA} : values();
        }
    }

    /** Which prefire aim point to show; Auto picks the weapon from the dungeon class. */
    public enum DragonAimMode {
        AUTO("Auto (Class)"), LAST_BREATH("Last Breath"), TERMINATOR("Terminator"), OFF("Off");

        private final String label;
        DragonAimMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum DragonMarkerMode {
        CORE("Core"), SKELETON("Skeleton");

        private final String label;
        DragonMarkerMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** A dragon hitbox part; part is its slot in EnderDragon.getSubEntities(), -1 for the overall box centre. */
    public enum DragonPart {
        BOX("Box Centre", -1), HEAD("Head", 0), NECK("Neck", 1), BODY("Body", 2),
        TAIL_1("Tail 1", 3), TAIL_2("Tail 2", 4), TAIL_3("Tail 3", 5), WING_1("Wing 1", 6), WING_2("Wing 2", 7);

        private final String label;
        private final int part;
        DragonPart(String label, int part) { this.label = label; this.part = part; }
        public String label() { return label; }
        public int part() { return part; }
    }

    public enum DragonDebuffScope {
        ALL_DRAGONS("All Dragons"), NEAREST_STATUE("Nearest Statue");

        private final String label;
        DragonDebuffScope(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Dragon Prio: the Bers split (Berserker + Mage) takes the first dragon, the Arch split (Archer + Healer + Tank) the second. */
    public enum DragonPrioSplit {
        BERS("Bers"), ARCH("Arch");

        private final String label;
        DragonPrioSplit(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Dragon Prio: the countdown is shown in server ticks or in milliseconds (50 per tick). */
    public enum DragonPrioUnit {
        TICKS("Ticks"), MILLISECONDS("Milliseconds");

        private final String label;
        DragonPrioUnit(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static String normalizeRoomSyncServerUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";

        boolean addedScheme = !normalized.contains("://");
        if (addedScheme) normalized = "http://" + normalized;

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (!addedScheme) return normalized;

        try {
            URI uri = new URI(normalized);
            if (uri.getHost() == null || uri.getPort() >= 0) return normalized;
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), 8765, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException exception) {
            return normalized;
        }
    }
}
