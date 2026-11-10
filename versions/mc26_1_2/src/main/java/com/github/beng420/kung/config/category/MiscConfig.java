package com.github.beng420.kung.config.category;

import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.util.KeyCodes;
import com.github.beng420.kung.util.KeyboardLayout;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@JsonAdapter(MiscConfig.CommandKeys.class)
public final class MiscConfig extends ConfigCategory {
    private static final Pattern SACK_KEY_SYMBOL = Pattern.compile("^[^\\p{L}\\p{N}]+");
    private static String layoutPeekChatKeybind;

    private boolean lobbyHopHelperEnabled = false;
    private boolean lobbyHopTitleEnabled = true;
    private boolean lobbyHopChatEnabled = true;
    private boolean bowDrawIndicatorEnabled = false;
    private int bowDrawIndicatorX = 6;
    private int bowDrawIndicatorY = 160;
    private int bowDrawIndicatorScale = 100;
    private List<BowDrawThreshold> bowDrawThresholds = defaultBowDrawThresholds();
    private boolean bowDrawDragonTicksEnabled = true;
    private boolean frozenBlazeHudEnabled = false;
    private int frozenBlazeHudX = 6;
    private int frozenBlazeHudY = 210;
    private int frozenBlazeHudScale = 100;
    private boolean frozenBlazeAlarmEnabled = false;
    private boolean ragTimerEnabled = false;
    private boolean ragTimerEffectEnabled = false;
    private boolean ragTimerCooldownEnabled = false;
    /** From the right-click, so the effect's 200 ticks and the 60 of the cast come out of it. */
    private int ragTimerCooldownTicks = 400;
    private int ragTimerX = 6;
    private int ragTimerY = 270;
    private int ragTimerScale = 100;
    private boolean storageHudEnabled = false;
    private boolean storageHudBackground = false;
    private int storageHudX = 6;
    private int storageHudY = 60;
    private int storageHudScale = 100;
    private boolean performanceHudEnabled = false;
    private int performanceHudX = 6;
    private int performanceHudY = 120;
    private int performanceHudScale = 100;
    private int performanceRefreshTenths = 5;
    private boolean performanceGraphTps = false;
    private boolean performanceGraphFps = false;
    private boolean performanceGraphPing = false;
    private boolean sackTrackerEnabled = false;
    private String sackTrackerKeybind = "";
    private boolean sackTrackerInMenus = true;
    private boolean sackTrackerGoalSound = true;
    private boolean sackTrackerGoalTitle = true;
    private boolean sackTrackerApiResync = true;
    private int sackTrackerX = 6;
    private int sackTrackerY = 240;
    private int sackTrackerScale = 100;
    /** Before profiles existed this was the one shared list; now it only waits for the first profile to take it over. */
    private List<SackItem> sackTrackerItems = new ArrayList<>();
    private SackProfiles sackTrackerProfiles = new SackProfiles();
    private boolean peekChatEnabled = false;
    /** Null until the player picks a key, so the default can follow the keyboard layout. */
    private String peekChatKeybind;
    private boolean peekChatScrollEnabled = true;
    private int peekChatScrollLines = 7;
    /** Whether the peek key also shows the chat while an inventory or other menu is open. */
    private boolean peekChatInMenus = false;
    private boolean chatChannelsEnabled = false;
    /** Names of the {@code ChatChannels.Channel} kinds the player hid; a kind added later starts shown. */
    private Set<String> chatChannelsHidden = new LinkedHashSet<>();
    /** Whether a /chat switch (typed or a tab click's) also changes the tab; off, only a click on a tab does. */
    private boolean chatChannelsFollowChat = false;
    /** The {@code ChatChannels.Send} Hypixel was last seen in, by enum name, so a restart still has it. */
    private String chatSend = "ALL";
    private boolean currentChatDisplayEnabled = false;
    private int currentChatDisplayX = 6;
    private int currentChatDisplayY = 90;
    private int currentChatDisplayScale = 100;
    private String menuFont = "Inter";
    private boolean hypixelApiEnabled = false;
    private String hypixelApiKey = "";

    private boolean chatCommandsEnabled = false;
    private boolean chatEmotesEnabled = false;
    private boolean chatEmotesReplaceWords = false;
    private List<ChatEmote> customChatEmotes = new ArrayList<>();

    /** Every chat command switch, by saved key: not Gson fields, {@link CommandKeys} writes them flat. */
    private final transient Map<String, Boolean> commandFlags = new HashMap<>();

    // Loadouts & Superpairs
    private boolean loadoutsAutoCloseEnabled = false;
    private boolean loadoutsCloseOnlyOnChange = false;
    private String[] loadoutKeybinds = defaultLoadoutKeybinds();
    private boolean superpairsHelperEnabled = false;
    private boolean superpairsHelperDebugEnabled = false;
    private boolean superpairsKeepCardsVisible = true;
    private boolean superpairsPairColors = true;
    private boolean superpairsPairLetters = true;
    private boolean superpairsMarkBestClick = true;
    private boolean superpairsMarkPairs = true;
    /** The Chronomatron and Ultrasequencer add-on solvers, which the Superpairs Helper switch also turns on. */
    private boolean experimentSolversEnabled = true;
    /** Full flavor only, like the other automation: claims the known pairs after Auto Click Main Game is done. */
    private boolean superpairsAutoClaimEnabled = false;
    /** Full flavor only: the Modrinth jar has no row and no feature reading it. */
    private boolean skipNpcDialogueEnabled = false;
    private int superpairsHelperX = 6;
    private int superpairsHelperY = 34;
    private int superpairsHelperScale = 75;
    /** Full flavor only: the Modrinth jar has no row and no code reading these three. */
    private boolean experimentAutoClickEnabled = false;
    private boolean superpairsAutoRevealEnabled = false;
    private int experimentAutoClickDelayMillis = 250;
    /** The add-on round after which the buffs are maxed (0 = read it from the Stakes menu): a chat hint, and the auto mode closes the window. */
    private int experimentMaxBuffsRound = 0;
    /** Full flavor only: the Modrinth jar has no row and no feature reading these two. */
    private boolean autoHarpEnabled = false;
    private int autoHarpDelayMillis = 60;

    // Custom Sounds
    private boolean customSoundsEnabled = false;
    private String customArrowHitSounds = "kung_arrow_ping.wav";
    private int customArrowHitVolumeTenths = 10;
    private int customArrowHitPitchHundredths = 100;
    private String customWitherShieldExpireSounds = "kung_wither_fade.wav";
    private int customWitherShieldExpireVolumeTenths = 10;
    private int customWitherShieldExpirePitchHundredths = 100;

    public boolean lobbyHopHelperEnabled() { return lobbyHopHelperEnabled; }
    public boolean bowDrawIndicatorEnabled() { return bowDrawIndicatorEnabled; }
    public void setBowDrawIndicatorEnabled(boolean value) { bowDrawIndicatorEnabled = value; save(); }
    public int bowDrawIndicatorX() { return bowDrawIndicatorX; }
    public void setBowDrawIndicatorX(int value) { bowDrawIndicatorX = value; save(); }
    public int bowDrawIndicatorY() { return bowDrawIndicatorY; }
    public void setBowDrawIndicatorY(int value) { bowDrawIndicatorY = value; save(); }
    public int bowDrawIndicatorScale() { return bowDrawIndicatorScale; }
    public void setBowDrawIndicatorScale(int value) { bowDrawIndicatorScale = Math.clamp(value, 25, 300); save(); }
    public List<BowDrawThreshold> bowDrawThresholds() { return Collections.unmodifiableList(bowDrawThresholds); }
    public boolean bowDrawDragonTicksEnabled() { return bowDrawDragonTicksEnabled; }
    public void setBowDrawDragonTicksEnabled(boolean value) { bowDrawDragonTicksEnabled = value; save(); }
    public boolean frozenBlazeHudEnabled() { return frozenBlazeHudEnabled; }
    public void setFrozenBlazeHudEnabled(boolean value) { frozenBlazeHudEnabled = value; save(); }
    public int frozenBlazeHudX() { return frozenBlazeHudX; }
    public void setFrozenBlazeHudX(int value) { frozenBlazeHudX = value; save(); }
    public int frozenBlazeHudY() { return frozenBlazeHudY; }
    public void setFrozenBlazeHudY(int value) { frozenBlazeHudY = value; save(); }
    public int frozenBlazeHudScale() { return frozenBlazeHudScale; }
    public void setFrozenBlazeHudScale(int value) { frozenBlazeHudScale = Math.clamp(value, 25, 300); save(); }
    public boolean frozenBlazeAlarmEnabled() { return frozenBlazeAlarmEnabled; }
    public void setFrozenBlazeAlarmEnabled(boolean value) { frozenBlazeAlarmEnabled = value; save(); }
    public boolean ragTimerEnabled() { return ragTimerEnabled; }
    public void setRagTimerEnabled(boolean value) { ragTimerEnabled = value; save(); }
    public boolean ragTimerEffectEnabled() { return ragTimerEffectEnabled; }
    public void setRagTimerEffectEnabled(boolean value) { ragTimerEffectEnabled = value; save(); }
    public boolean ragTimerCooldownEnabled() { return ragTimerCooldownEnabled; }
    public void setRagTimerCooldownEnabled(boolean value) { ragTimerCooldownEnabled = value; save(); }
    public int ragTimerCooldownTicks() { return Math.clamp(ragTimerCooldownTicks, 60, 400); }
    public void setRagTimerCooldownTicks(int value) { ragTimerCooldownTicks = Math.clamp(value, 60, 400); save(); }
    public int ragTimerX() { return ragTimerX; }
    public void setRagTimerX(int value) { ragTimerX = value; save(); }
    public int ragTimerY() { return ragTimerY; }
    public void setRagTimerY(int value) { ragTimerY = value; save(); }
    public int ragTimerScale() { return ragTimerScale; }
    public void setRagTimerScale(int value) { ragTimerScale = Math.clamp(value, 25, 300); save(); }
    public boolean storageHudEnabled() { return storageHudEnabled; }
    public void setStorageHudEnabled(boolean value) { storageHudEnabled = value; save(); }
    public boolean storageHudBackground() { return storageHudBackground; }
    public void setStorageHudBackground(boolean value) { storageHudBackground = value; save(); }
    public int storageHudX() { return storageHudX; }
    public void setStorageHudX(int value) { storageHudX = value; save(); }
    public int storageHudY() { return storageHudY; }
    public void setStorageHudY(int value) { storageHudY = value; save(); }
    public int storageHudScale() { return storageHudScale; }
    public void setStorageHudScale(int value) { storageHudScale = Math.clamp(value, 25, 300); save(); }
    public boolean performanceHudEnabled() { return performanceHudEnabled; }
    public void setPerformanceHudEnabled(boolean value) { performanceHudEnabled = value; save(); }
    public int performanceHudX() { return performanceHudX; }
    public void setPerformanceHudX(int value) { performanceHudX = value; save(); }
    public int performanceHudY() { return performanceHudY; }
    public void setPerformanceHudY(int value) { performanceHudY = value; save(); }
    public int performanceHudScale() { return performanceHudScale; }
    public void setPerformanceHudScale(int value) { performanceHudScale = Math.clamp(value, 25, 300); save(); }
    public int performanceRefreshTenths() { return performanceRefreshTenths; }
    public void setPerformanceRefreshTenths(int value) { performanceRefreshTenths = Math.clamp(value, 1, 10); save(); }
    public boolean performanceGraphTps() { return performanceGraphTps; }
    public void setPerformanceGraphTps(boolean value) { performanceGraphTps = value; save(); }
    public boolean performanceGraphFps() { return performanceGraphFps; }
    public void setPerformanceGraphFps(boolean value) { performanceGraphFps = value; save(); }
    public boolean performanceGraphPing() { return performanceGraphPing; }
    public void setPerformanceGraphPing(boolean value) { performanceGraphPing = value; save(); }
    public boolean sackTrackerEnabled() { return sackTrackerEnabled; }
    public void setSackTrackerEnabled(boolean value) { sackTrackerEnabled = value; save(); }
    public String sackTrackerKeybind() { return sackTrackerKeybind == null ? "" : sackTrackerKeybind; }
    public void setSackTrackerKeybind(String value) { sackTrackerKeybind = value == null ? "" : value; save(); }
    public boolean sackTrackerInMenus() { return sackTrackerInMenus; }
    public void setSackTrackerInMenus(boolean value) { sackTrackerInMenus = value; save(); }
    public boolean sackTrackerGoalSound() { return sackTrackerGoalSound; }
    public void setSackTrackerGoalSound(boolean value) { sackTrackerGoalSound = value; save(); }
    public boolean sackTrackerGoalTitle() { return sackTrackerGoalTitle; }
    public void setSackTrackerGoalTitle(boolean value) { sackTrackerGoalTitle = value; save(); }
    public boolean sackTrackerApiResync() { return sackTrackerApiResync; }
    public void setSackTrackerApiResync(boolean value) { sackTrackerApiResync = value; save(); }
    public int sackTrackerX() { return sackTrackerX; }
    public void setSackTrackerX(int value) { sackTrackerX = value; save(); }
    public int sackTrackerY() { return sackTrackerY; }
    public void setSackTrackerY(int value) { sackTrackerY = value; save(); }
    public int sackTrackerScale() { return sackTrackerScale; }
    public void setSackTrackerScale(int value) { sackTrackerScale = Math.clamp(value, 25, 300); save(); }
    /**
     * The items the player tracks, in the profile the player is on; the old shared list while no profile is known
     * yet. Watched items (see {@link #watchSackTrackerItems}) are not among them: the display and the settings never show them.
     */
    public List<SackItem> sackTrackerItems() {
        List<SackItem> items = activeSackItems();
        return items.stream().anyMatch(SackItem::hidden) ? items.stream().filter(item -> !item.hidden()).toList()
            : Collections.unmodifiableList(items);
    }

    /** Tracked and watched items: everything the sack reads and [Sacks] messages have to keep current. */
    public List<SackItem> sackTrackerAllItems() { return Collections.unmodifiableList(activeSackItems()); }
    public boolean sackTrackerProfileKnown() { return sackTrackerProfiles.known(); }
    public String sackTrackerProfileName() { return sackTrackerProfiles.name(); }
    public void leaveSackTrackerProfile() { sackTrackerProfiles.leave(); }

    /** Reads a chat line for profile switches; true when the player is on another profile now. */
    public boolean observeSackTrackerProfile(String text, String account) {
        boolean changed = sackTrackerProfiles.observe(text, account, sackTrackerItems);
        if (changed && sackTrackerProfiles.known()) save();
        return changed;
    }

    private List<SackItem> activeSackItems() {
        var items = sackTrackerProfiles.items();
        return items == null ? sackTrackerItems : items;
    }
    public boolean peekChatEnabled() { return peekChatEnabled; }
    public void setPeekChatEnabled(boolean value) { peekChatEnabled = value; save(); }
    public String peekChatKeybind() { return peekChatKeybind == null ? defaultPeekChatKeybind() : peekChatKeybind; }
    public void setPeekChatKeybind(String value) { peekChatKeybind = value == null ? "" : value; save(); }
    public boolean peekChatScrollEnabled() { return peekChatScrollEnabled; }
    public void setPeekChatScrollEnabled(boolean value) { peekChatScrollEnabled = value; save(); }
    public int peekChatScrollLines() { return Math.clamp(peekChatScrollLines, 1, 30); }
    public void setPeekChatScrollLines(int value) { peekChatScrollLines = Math.clamp(value, 1, 30); save(); }
    public boolean peekChatInMenus() { return peekChatInMenus; }
    public void setPeekChatInMenus(boolean value) { peekChatInMenus = value; save(); }
    public boolean chatChannelsEnabled() { return chatChannelsEnabled; }
    public void setChatChannelsEnabled(boolean value) { chatChannelsEnabled = value; save(); }
    public boolean chatChannelsFollowChat() { return chatChannelsFollowChat; }
    public void setChatChannelsFollowChat(boolean value) { chatChannelsFollowChat = value; save(); }
    public String chatSend() { return chatSend; }
    public void setChatSend(String value) {
        if (value.equals(chatSend)) return;
        chatSend = value;
        save();
    }
    public boolean currentChatDisplayEnabled() { return currentChatDisplayEnabled; }
    public void setCurrentChatDisplayEnabled(boolean value) { currentChatDisplayEnabled = value; save(); }
    public int currentChatDisplayX() { return currentChatDisplayX; }
    public void setCurrentChatDisplayX(int value) { currentChatDisplayX = value; save(); }
    public int currentChatDisplayY() { return currentChatDisplayY; }
    public void setCurrentChatDisplayY(int value) { currentChatDisplayY = value; save(); }
    public int currentChatDisplayScale() { return currentChatDisplayScale; }
    public void setCurrentChatDisplayScale(int value) { currentChatDisplayScale = Math.clamp(value, 25, 300); save(); }
    public boolean chatChannelHidden(String channel) { return chatChannelsHidden != null && chatChannelsHidden.contains(channel); }
    public void setChatChannelHidden(String channel, boolean hidden) {
        if (chatChannelsHidden == null) chatChannelsHidden = new LinkedHashSet<>();
        if (hidden) chatChannelsHidden.add(channel);
        else chatChannelsHidden.remove(channel);
        save();
    }

    public SackItem sackTrackerItem(String name) {
        String key = sackKey(name);
        for (SackItem item : activeSackItems()) if (sackKey(item.name()).equals(key)) return item;
        return null;
    }

    /** An item's name without the symbol some names start with (a gemstone's "☠ Onyx"): menus and chat disagree on it. */
    public static String sackKey(String name) {
        return name == null ? "" : SACK_KEY_SYMBOL.matcher(name.strip()).replaceFirst("");
    }

    /**
     * Tracks the item, or stops tracking it when it already is; true when it is tracked now. A watched item becomes
     * tracked and shown, keeping the amount already read.
     */
    public boolean toggleSackTrackerItem(String name, int color, long amount) {
        SackItem existing = sackTrackerItem(name);
        if (existing != null && existing.hidden) {
            existing.hidden = false;
            existing.color = color;
            if (amount >= 0) existing.amount = amount;
            save();
            return true;
        }
        if (existing != null) activeSackItems().remove(existing);
        else activeSackItems().add(new SackItem(name, color, amount, 0));
        save();
        return existing == null;
    }

    /**
     * Has the sack reads and [Sacks] messages keep these items current without showing them (the Greenhouse planner
     * reads them); items that are already tracked or watched stay as they are. Only for a known profile: the old
     * shared list belongs to no profile.
     */
    public void watchSackTrackerItems(Collection<String> names) {
        if (!sackTrackerProfiles.known()) return;
        boolean added = false;
        for (String name : names) {
            if (sackTrackerItem(name) != null) continue;
            SackItem item = new SackItem(name, 0xFFFFFF, -1, 0);
            item.hidden = true;
            activeSackItems().add(item);
            added = true;
        }
        if (added) save();
    }

    public void setSackTrackerAmount(SackItem item, long amount) {
        if (!activeSackItems().contains(item) || item.amount == Math.max(0, amount)) return;
        item.amount = Math.max(0, amount);
        save();
    }

    /** The SkyBlock item id a sack menu showed for this row: the key of its count in the API. */
    public void setSackTrackerId(SackItem item, String id) {
        if (!activeSackItems().contains(item) || id == null || id.isBlank() || id.equals(item.id())) return;
        item.id = id;
        save();
    }

    public void setSackTrackerGoal(SackItem item, long goal) {
        if (!activeSackItems().contains(item) || item.goal == Math.max(0, goal)) return;
        item.goal = Math.max(0, goal);
        save();
    }

    public void removeSackTrackerItem(SackItem item) {
        if (activeSackItems().remove(item)) save();
    }

    /** Stops tracking everything the player tracks; watched items keep being read. */
    public void clearSackTrackerItems() {
        if (activeSackItems().removeIf(item -> !item.hidden())) save();
    }

    /** Identity stays stable while editing, like ChatEmote, so rows keep owning their entry. */
    public static final class SackItem {
        private String name = "";
        private int color = 0xFFFFFF;
        private long amount;
        private long goal;
        /** The SkyBlock item id, read from a sack menu; empty until one was open. */
        private String id = "";
        /** Watched, not tracked: read like a tracked item but never shown (see {@link #watchSackTrackerItems}). */
        private boolean hidden;

        private SackItem() { }

        public SackItem(String name, int color, long amount, long goal) {
            this.name = name;
            this.color = color;
            this.amount = amount;
            this.goal = goal;
        }

        public String name() { return name == null ? "" : name; }
        public int color() { return color; }
        public long amount() { return amount; }
        public long goal() { return goal; }
        public String id() { return id == null ? "" : id; }
        public boolean hidden() { return hidden; }
    }

    public void addBowDrawThreshold() {
        if (bowDrawThresholds.size() >= 20) return;
        var used = bowDrawThresholds.stream().map(BowDrawThreshold::ticks).toList();
        int ticks = 10;
        for (int candidate = 1; used.contains(ticks); candidate++) {
            ticks = candidate;
        }
        bowDrawThresholds.add(new BowDrawThreshold(ticks));
        save();
    }

    public void setBowDrawThreshold(BowDrawThreshold entry, int ticks) {
        if (!bowDrawThresholds.contains(entry)) return;
        entry.ticks = Math.clamp(ticks, 0, 20);
        save();
    }

    public void setBowDrawThresholdColor(BowDrawThreshold entry, int color) {
        if (!bowDrawThresholds.contains(entry)) return;
        entry.color = color | 0xFF000000;
        save();
    }

    public void removeBowDrawThreshold(BowDrawThreshold entry) {
        if (bowDrawThresholds.remove(entry)) save();
    }

    private static List<BowDrawThreshold> defaultBowDrawThresholds() {
        return new ArrayList<>(List.of(new BowDrawThreshold(3), new BowDrawThreshold(5), new BowDrawThreshold(8)));
    }

    /** Identity stays stable while editing a value, so list/native controls keep their row ownership. */
    public static final class BowDrawThreshold {
        private int ticks;
        /** 0 in configs from before colors existed: those lines stay white. */
        private int color;
        private BowDrawThreshold(int ticks) { this.ticks = ticks; }
        public int ticks() { return ticks; }
        public int color() { return color == 0 ? 0xFFFFFFFF : color | 0xFF000000; }
    }
    public void setLobbyHopHelperEnabled(boolean value) { lobbyHopHelperEnabled = value; save(); }
    public boolean lobbyHopTitleEnabled() { return lobbyHopTitleEnabled; }
    public void setLobbyHopTitleEnabled(boolean value) { lobbyHopTitleEnabled = value; save(); }
    public boolean lobbyHopChatEnabled() { return lobbyHopChatEnabled; }
    public void setLobbyHopChatEnabled(boolean value) { lobbyHopChatEnabled = value; save(); }
    public boolean hypixelApiEnabled() { return hypixelApiEnabled; }
    public void setHypixelApiEnabled(boolean value) { hypixelApiEnabled = value; save(); }
    public boolean directHypixelApiEnabled() { return hypixelApiEnabled && !hypixelApiKey().isBlank(); }
    /** A font file in the font folder, or the bundled Inter; see KungFonts. */
    public String menuFont() { return menuFont == null || menuFont.isBlank() ? "Inter" : menuFont; }
    public void setMenuFont(String value) {
        menuFont = value == null || value.isBlank() ? "Inter" : value;
        save();
    }

    public String hypixelApiKey() { return hypixelApiKey == null ? "" : hypixelApiKey; }
    public void setHypixelApiKey(String value) { hypixelApiKey = value == null ? "" : value.trim(); save(); }
    public boolean chatCommandsEnabled() { return chatCommandsEnabled; }
    public void setChatCommandsEnabled(boolean value) { chatCommandsEnabled = value; save(); }
    public boolean chatEmotesEnabled() { return chatEmotesEnabled; }
    public void setChatEmotesEnabled(boolean value) { chatEmotesEnabled = value; save(); }
    public boolean chatEmotesReplaceWords() { return chatEmotesReplaceWords; }
    public void setChatEmotesReplaceWords(boolean value) { chatEmotesReplaceWords = value; save(); }
    public List<ChatEmote> customChatEmotes() { return Collections.unmodifiableList(customChatEmotes); }

    public void addCustomChatEmote() {
        customChatEmotes.add(new ChatEmote());
        save();
    }

    public void setChatEmoteShortcut(ChatEmote emote, String shortcut) {
        if (!customChatEmotes.contains(emote)) return;
        emote.shortcut = shortcut == null ? "" : shortcut.strip().replaceAll("^:+|:+$", "").strip();
        save();
    }

    public void setChatEmoteText(ChatEmote emote, String text) {
        if (!customChatEmotes.contains(emote)) return;
        emote.emote = text == null ? "" : text.strip();
        save();
    }

    public void removeCustomChatEmote(ChatEmote emote) {
        if (customChatEmotes.remove(emote)) save();
    }

    /** Identity stays stable while editing, like BowDrawThreshold, so rows keep owning their entry. */
    public static final class ChatEmote {
        private String shortcut = "";
        private String emote = "";
        /** The word between the colons; configs from before this stored the colons too. */
        public String shortcut() { return shortcut == null ? "" : shortcut.replaceAll("^:+|:+$", ""); }
        public String emote() { return emote == null ? "" : emote; }
    }
    public boolean specialMayorsChatCommandsEnabled() { return flag(SPECIAL_MAYORS); }
    public void setSpecialMayorsChatCommandsEnabled(boolean value) { setFlag(SPECIAL_MAYORS, value); }
    public boolean yearlyEventsChatCommandsEnabled() { return flag(YEARLY_EVENTS); }
    public void setYearlyEventsChatCommandsEnabled(boolean value) { setFlag(YEARLY_EVENTS, value); }
    public boolean defaultChannelEnabled(CommandChannel channel) { return flag(defaultKey(channel)); }
    public void setDefaultChannelEnabled(CommandChannel channel, boolean value) { setFlag(defaultKey(channel), value); }
    public boolean commandEnabled(ChatCommandSetting command) { return flag(command.prefix + "ChatCommandEnabled"); }
    public void setCommandEnabled(ChatCommandSetting command, boolean value) {
        setFlag(command.prefix + "ChatCommandEnabled", value);
    }
    public boolean useDefaultChannels(ChatCommandSetting command) { return flag(useDefaultKey(command)); }
    public void setUseDefaultChannels(ChatCommandSetting command, boolean value) { setFlag(useDefaultKey(command), value); }

    /** Whether the command answers in the channel: the default channel's switch while it follows the defaults. */
    public boolean channelEnabled(ChatCommandSetting command, CommandChannel channel) {
        return useDefaultChannels(command) ? defaultChannelEnabled(channel) : flag(channelKey(command, channel));
    }

    /** The command's own switch for the channel; it counts once the command stops following the defaults. */
    public void setChannelEnabled(ChatCommandSetting command, CommandChannel channel, boolean value) {
        setFlag(channelKey(command, channel), value);
    }

    /** Every command follows the default channels again; the defaults and all switches stay as they are. */
    public void useDefaultChannelsEverywhere() {
        for (ChatCommandSetting command : ChatCommandSetting.values()) commandFlags.put(useDefaultKey(command), true);
        save();
    }

    /** The chat commands with settings of their own; {@code prefix} starts their saved keys, as in c50PartyCommandsEnabled. */
    public enum ChatCommandSetting {
        C50("c50"), CA50("ca50"), TPS("tps"), JERRY("jerry"), SCORP("scorp"), DERPY("derpy"),
        YOTP("yotp"), YOTW("yotw"), YOTS("yots"), CLASS_LEVEL("classLevel");

        private final String prefix;

        ChatCommandSetting(String prefix) { this.prefix = prefix; }
    }

    /** A channel a command answers in; {@code key} is part of the saved keys, {@code defaultOn} their default. */
    public enum CommandChannel {
        PARTY("Party", true), GUILD("Guild", true), ALL_CHAT("AllChat", false), PRIVATE("Private", false), COOP("Coop", true);

        private final String key;
        private final boolean defaultOn;

        CommandChannel(String key, boolean defaultOn) {
            this.key = key;
            this.defaultOn = defaultOn;
        }
    }

    private static final String SPECIAL_MAYORS = "specialMayorsChatCommandsEnabled";
    private static final String YEARLY_EVENTS = "yearlyEventsChatCommandsEnabled";
    /** Every chat command switch with its default, in the order the file lists them. */
    private static final Map<String, Boolean> COMMAND_DEFAULTS = commandDefaults();

    private static Map<String, Boolean> commandDefaults() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        for (ChatCommandSetting command : ChatCommandSetting.values()) {
            defaults.put(command.prefix + "ChatCommandEnabled", true);
            for (CommandChannel channel : CommandChannel.values()) defaults.put(channelKey(command, channel), channel.defaultOn);
        }
        defaults.put(SPECIAL_MAYORS, true);
        defaults.put(YEARLY_EVENTS, true);
        for (CommandChannel channel : CommandChannel.values()) defaults.put(defaultKey(channel), channel.defaultOn);
        for (ChatCommandSetting command : ChatCommandSetting.values()) defaults.put(useDefaultKey(command), true);
        return defaults;
    }

    private static String channelKey(ChatCommandSetting command, CommandChannel channel) {
        return command.prefix + channel.key + "CommandsEnabled";
    }

    private static String defaultKey(CommandChannel channel) { return "default" + channel.key + "CommandsEnabled"; }
    private static String useDefaultKey(ChatCommandSetting command) { return command.prefix + "UseDefaultChannels"; }
    private boolean flag(String key) { return commandFlags.getOrDefault(key, COMMAND_DEFAULTS.get(key)); }
    private void setFlag(String key, boolean value) {
        commandFlags.put(key, value);
        save();
    }

    private void writeCommandFlags(JsonObject json) {
        COMMAND_DEFAULTS.keySet().forEach(key -> json.addProperty(key, flag(key)));
    }

    /** Takes the flat keys from the file, then the older shared "cata" settings and the pre-default channels. */
    private void readCommandFlags(JsonObject json) {
        COMMAND_DEFAULTS.keySet().forEach(key -> {
            if (has(json, key)) commandFlags.put(key, json.get(key).getAsBoolean());
        });
        // The old shared "cata"/"ca50" key fills whichever of the "c50"/"ca50" keys the file leaves unset.
        for (String suffix : new String[] {"ChatCommandEnabled", "PartyCommandsEnabled", "GuildCommandsEnabled",
            "AllChatCommandsEnabled", "PrivateCommandsEnabled"}) {
            for (String legacy : new String[] {"cata" + suffix, "ca50" + suffix}) {
                if (!has(json, legacy)) continue;
                boolean value = json.get(legacy).getAsBoolean();
                if (!has(json, "c50" + suffix)) commandFlags.put("c50" + suffix, value);
                if (!has(json, "ca50" + suffix)) commandFlags.put("ca50" + suffix, value);
            }
        }
        // A file from before the shared defaults keeps what it changed: a command whose own channels differ from the
        // shipped ones stays on them instead of silently following the defaults. Co-op was never compared.
        for (ChatCommandSetting command : ChatCommandSetting.values()) {
            if (has(json, useDefaultKey(command))) continue;
            for (CommandChannel channel : CommandChannel.values()) {
                if (channel != CommandChannel.COOP && flag(channelKey(command, channel)) != channel.defaultOn) {
                    commandFlags.put(useDefaultKey(command), false);
                }
            }
        }
    }

    private static boolean has(JsonObject json, String key) { return json.has(key) && !json.get(key).isJsonNull(); }

    /** Reads and writes the chat command switches flat in the "misc" object, under the names the fields had. */
    public static final class CommandKeys implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != MiscConfig.class) return null;
            TypeAdapter<MiscConfig> fields = gson.getDelegateAdapter(this, (TypeToken<MiscConfig>) type);
            TypeAdapter<JsonElement> tree = gson.getAdapter(JsonElement.class);
            return (TypeAdapter<T>) new TypeAdapter<MiscConfig>() {
                @Override
                public void write(JsonWriter out, MiscConfig misc) throws IOException {
                    JsonObject written = new JsonObject();
                    boolean flagsWritten = false;
                    for (var entry : fields.toJsonTree(misc).getAsJsonObject().entrySet()) {
                        written.add(entry.getKey(), entry.getValue());
                        if (entry.getKey().equals("customChatEmotes")) {
                            misc.writeCommandFlags(written);
                            flagsWritten = true;
                        }
                    }
                    if (!flagsWritten) misc.writeCommandFlags(written);
                    tree.write(out, written);
                }

                @Override
                public MiscConfig read(JsonReader in) throws IOException {
                    JsonElement json = tree.read(in);
                    MiscConfig misc = fields.fromJsonTree(json);
                    if (misc != null && json.isJsonObject()) misc.readCommandFlags(json.getAsJsonObject());
                    return misc;
                }
            }.nullSafe();
        }
    }
    public boolean loadoutsAutoCloseEnabled() { return loadoutsAutoCloseEnabled; }
    public void setLoadoutsAutoCloseEnabled(boolean value) { loadoutsAutoCloseEnabled = value; save(); }
    public boolean loadoutsCloseOnlyOnChange() { return loadoutsCloseOnlyOnChange; }
    public void setLoadoutsCloseOnlyOnChange(boolean value) { loadoutsCloseOnlyOnChange = value; save(); }
    public boolean superpairsHelperEnabled() { return superpairsHelperEnabled; }
    public void setSuperpairsHelperEnabled(boolean value) { superpairsHelperEnabled = value; save(); }
    public boolean superpairsPairColors() { return superpairsPairColors; }
    public void setSuperpairsPairColors(boolean value) { superpairsPairColors = value; save(); }
    public boolean superpairsPairLetters() { return superpairsPairLetters; }
    public void setSuperpairsPairLetters(boolean value) { superpairsPairLetters = value; save(); }
    public boolean superpairsMarkPairs() { return superpairsMarkPairs; }
    public void setSuperpairsMarkPairs(boolean value) { superpairsMarkPairs = value; save(); }
    public boolean experimentSolversEnabled() { return experimentSolversEnabled; }
    public void setExperimentSolversEnabled(boolean value) { experimentSolversEnabled = value; save(); }
    public boolean superpairsAutoClaimEnabled() { return superpairsAutoClaimEnabled; }
    public void setSuperpairsAutoClaimEnabled(boolean value) { superpairsAutoClaimEnabled = value; save(); }
    public boolean superpairsMarkBestClick() { return superpairsMarkBestClick; }
    public void setSuperpairsMarkBestClick(boolean value) { superpairsMarkBestClick = value; save(); }
    public boolean superpairsKeepCardsVisible() { return superpairsKeepCardsVisible; }
    public void setSuperpairsKeepCardsVisible(boolean value) { superpairsKeepCardsVisible = value; save(); }
    public boolean superpairsHelperDebugEnabled() { return superpairsHelperDebugEnabled; }
    public void setSuperpairsHelperDebugEnabled(boolean value) { superpairsHelperDebugEnabled = value; save(); }
    public boolean experimentAutoClickEnabled() { return experimentAutoClickEnabled; }
    public void setExperimentAutoClickEnabled(boolean value) { experimentAutoClickEnabled = value; save(); }
    public boolean superpairsAutoRevealEnabled() { return superpairsAutoRevealEnabled; }
    public void setSuperpairsAutoRevealEnabled(boolean value) { superpairsAutoRevealEnabled = value; save(); }
    public int experimentAutoClickDelayMillis() { return Math.clamp(experimentAutoClickDelayMillis, 100, 1000); }
    public void setExperimentAutoClickDelayMillis(int value) { experimentAutoClickDelayMillis = Math.clamp(value, 100, 1000); save(); }
    public int experimentMaxBuffsRound() { return Math.clamp(experimentMaxBuffsRound, 0, 30); }
    public void setExperimentMaxBuffsRound(int value) { experimentMaxBuffsRound = Math.clamp(value, 0, 30); save(); }
    public boolean autoHarpEnabled() { return autoHarpEnabled; }
    public void setAutoHarpEnabled(boolean value) { autoHarpEnabled = value; save(); }
    public int autoHarpDelayMillis() { return Math.clamp(autoHarpDelayMillis, 30, 300); }
    public void setAutoHarpDelayMillis(int value) { autoHarpDelayMillis = Math.clamp(value, 30, 300); save(); }
    public boolean skipNpcDialogueEnabled() { return skipNpcDialogueEnabled; }
    public void setSkipNpcDialogueEnabled(boolean value) { skipNpcDialogueEnabled = value; save(); }
    public int superpairsHelperX() { return superpairsHelperX; }
    public void setSuperpairsHelperX(int value) { superpairsHelperX = value; save(); }
    public int superpairsHelperY() { return superpairsHelperY; }
    public void setSuperpairsHelperY(int value) { superpairsHelperY = value; save(); }
    public int superpairsHelperScale() { return superpairsHelperScale; }
    public boolean customSoundsEnabled() { return customSoundsEnabled; }
    public void setCustomSoundsEnabled(boolean value) { customSoundsEnabled = value; save(); }
    public String customArrowHitSounds() { return customArrowHitSounds == null ? "" : customArrowHitSounds; }
    public void setCustomArrowHitSounds(String value) { customArrowHitSounds = normalizeCustomSoundList(value); save(); }
    public int customArrowHitVolumeTenths() { return customArrowHitVolumeTenths; }
    public void setCustomArrowHitVolumeTenths(int value) { customArrowHitVolumeTenths = Math.clamp(value, 0, 50); save(); }
    public int customArrowHitPitchHundredths() { return customArrowHitPitchHundredths; }
    public void setCustomArrowHitPitchHundredths(int value) { customArrowHitPitchHundredths = Math.clamp(value, 25, 300); save(); }
    public String customWitherShieldExpireSounds() {
        return customWitherShieldExpireSounds == null ? "" : customWitherShieldExpireSounds;
    }
    public void setCustomWitherShieldExpireSounds(String value) {
        customWitherShieldExpireSounds = normalizeCustomSoundList(value);
        save();
    }
    public int customWitherShieldExpireVolumeTenths() { return customWitherShieldExpireVolumeTenths; }
    public void setCustomWitherShieldExpireVolumeTenths(int value) {
        customWitherShieldExpireVolumeTenths = Math.clamp(value, 0, 50);
        save();
    }
    public int customWitherShieldExpirePitchHundredths() { return customWitherShieldExpirePitchHundredths; }
    public void setCustomWitherShieldExpirePitchHundredths(int value) {
        customWitherShieldExpirePitchHundredths = Math.clamp(value, 25, 300);
        save();
    }

    /** The key labelled Y on this keyboard: GLFW numbers keys by US position, so a German Y is 90 (Z), not 89. */
    private static String defaultPeekChatKeybind() {
        int picked = KeyboardLayout.current().keyLabelled("Y");
        if (picked >= 0) return "key:" + picked + ":0";
        if (layoutPeekChatKeybind == null) {
            try {
                for (int key = KeyCodes.A; key <= KeyCodes.Z && layoutPeekChatKeybind == null; key++) {
                    if ("y".equals(McInput.keyName(key))) layoutPeekChatKeybind = "key:" + key + ":0";
                }
            } catch (Throwable noGlfw) { /* tests run without GLFW */ }
        }
        return layoutPeekChatKeybind == null ? "key:89:0" : layoutPeekChatKeybind;
    }

    /** A newly picked keyboard layout changes which key is labelled Y. */
    public static void forgetLayoutKeybind() {
        layoutPeekChatKeybind = null;
    }

    private static String[] defaultLoadoutKeybinds() {
        String[] defaults = new String[12];
        for (int i = 0; i < 9; i++) defaults[i] = "key:" + (49 + i) + ":0";
        defaults[9] = "key:48:0";
        defaults[10] = "key:45:0";
        defaults[11] = "key:61:0";
        return defaults;
    }

    public String loadoutKeybind(int index) {
        if (index < 0 || index >= loadoutKeybinds.length) return "";
        String keybind = loadoutKeybinds[index];
        return keybind == null ? "" : keybind;
    }

    public void setLoadoutKeybind(int index, String keybind) {
        if (index < 0 || index >= loadoutKeybinds.length) return;
        loadoutKeybinds[index] = keybind == null ? "" : keybind;
        save();
    }

    public void setSuperpairsHelperScale(int scale) {
        this.superpairsHelperScale = Math.clamp(scale, 25, 300);
        save();
    }

    @Override
    public void normalize() {
        setHypixelApiKey(hypixelApiKey());
        setSuperpairsHelperScale(superpairsHelperScale());
        setCustomArrowHitSounds(customArrowHitSounds());
        setCustomWitherShieldExpireSounds(customWitherShieldExpireSounds());
        setCustomArrowHitVolumeTenths(customArrowHitVolumeTenths());
        setCustomArrowHitPitchHundredths(customArrowHitPitchHundredths());
        setCustomWitherShieldExpireVolumeTenths(customWitherShieldExpireVolumeTenths());
        setCustomWitherShieldExpirePitchHundredths(customWitherShieldExpirePitchHundredths());
        bowDrawIndicatorScale = Math.clamp(bowDrawIndicatorScale, 25, 300);
        frozenBlazeHudScale = Math.clamp(frozenBlazeHudScale, 25, 300);
        ragTimerScale = Math.clamp(ragTimerScale, 25, 300);
        storageHudScale = Math.clamp(storageHudScale, 25, 300);
        performanceHudScale = Math.clamp(performanceHudScale, 25, 300);
        currentChatDisplayScale = Math.clamp(currentChatDisplayScale, 25, 300);
        performanceRefreshTenths = Math.clamp(performanceRefreshTenths, 1, 10);
        sackTrackerScale = Math.clamp(sackTrackerScale, 25, 300);
        sackTrackerItems = sackTrackerItems == null ? new ArrayList<>()
            : new ArrayList<>(sackTrackerItems.stream().filter(item -> item != null && !item.name().isBlank()).toList());
        if (sackTrackerProfiles == null) sackTrackerProfiles = new SackProfiles();
        sackTrackerProfiles.sanitize();
        if (bowDrawThresholds == null) bowDrawThresholds = defaultBowDrawThresholds();
        customChatEmotes = customChatEmotes == null ? new ArrayList<>()
            : new ArrayList<>(customChatEmotes.stream().filter(java.util.Objects::nonNull).toList());
        bowDrawThresholds = new ArrayList<>(bowDrawThresholds.stream().filter(java.util.Objects::nonNull).limit(20).toList());
        for (var threshold : bowDrawThresholds) threshold.ticks = Math.clamp(threshold.ticks, 0, 20);
        if (loadoutKeybinds == null) {
            loadoutKeybinds = defaultLoadoutKeybinds();
        } else {
            String[] normalized = defaultLoadoutKeybinds();
            for (int index = 0; index < Math.min(loadoutKeybinds.length, normalized.length); index++) {
                if (loadoutKeybinds[index] != null) normalized[index] = loadoutKeybinds[index];
            }
            loadoutKeybinds = normalized;
        }
    }

    private static String normalizeCustomSoundList(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder();
        for (String part : value.replace('\n', ',').replace(';', ',').split(",")) {
            String name = normalizeCustomSoundName(part);
            if (name.isBlank()) continue;
            if (!result.isEmpty()) result.append(", ");
            result.append(name);
        }
        return result.toString();
    }

    private static String normalizeCustomSoundName(String value) {
        if (value == null) return "";
        String name = value.trim().replace('\\', '/');
        if (name.length() >= 2 && (name.startsWith("\"") && name.endsWith("\"")
            || name.startsWith("'") && name.endsWith("'"))) {
            name = name.substring(1, name.length() - 1).trim();
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.length() >= 2 && (name.startsWith("\"") && name.endsWith("\"")
            || name.startsWith("'") && name.endsWith("'"))) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return name.equals(".") || name.equals("..") ? "" : name;
    }
}
