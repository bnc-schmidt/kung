package com.github.beng420.kung.config.category;

import com.github.beng420.kung.util.KeyboardLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.lwjgl.glfw.GLFW;

public final class MiscConfig extends ConfigCategory {
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
    private int sackTrackerX = 6;
    private int sackTrackerY = 240;
    private int sackTrackerScale = 100;
    private List<SackItem> sackTrackerItems = new ArrayList<>();
    private boolean peekChatEnabled = false;
    /** Null until the player picks a key, so the default can follow the keyboard layout. */
    private String peekChatKeybind;
    private boolean peekChatScrollEnabled = true;
    private int peekChatScrollLines = 7;
    private String menuFont = "Inter";
    private boolean hypixelApiEnabled = false;
    private String hypixelApiKey = "";

    private boolean chatCommandsEnabled = false;
    private boolean chatEmotesEnabled = false;
    private boolean chatEmotesReplaceWords = false;
    private List<ChatEmote> customChatEmotes = new ArrayList<>();

    private boolean c50ChatCommandEnabled = true;
    private boolean c50PartyCommandsEnabled = true;
    private boolean c50GuildCommandsEnabled = true;
    private boolean c50AllChatCommandsEnabled = false;
    private boolean c50PrivateCommandsEnabled = false;
    private boolean ca50ChatCommandEnabled = true;
    private boolean ca50PartyCommandsEnabled = true;
    private boolean ca50GuildCommandsEnabled = true;
    private boolean ca50AllChatCommandsEnabled = false;
    private boolean ca50PrivateCommandsEnabled = false;
    private boolean tpsChatCommandEnabled = true;
    private boolean tpsPartyCommandsEnabled = true;
    private boolean tpsGuildCommandsEnabled = true;
    private boolean tpsAllChatCommandsEnabled = false;
    private boolean tpsPrivateCommandsEnabled = false;
    private boolean jerryChatCommandEnabled = true;
    private boolean jerryPartyCommandsEnabled = true;
    private boolean jerryGuildCommandsEnabled = true;
    private boolean jerryAllChatCommandsEnabled = false;
    private boolean jerryPrivateCommandsEnabled = false;
    private boolean scorpChatCommandEnabled = true;
    private boolean scorpPartyCommandsEnabled = true;
    private boolean scorpGuildCommandsEnabled = true;
    private boolean scorpAllChatCommandsEnabled = false;
    private boolean scorpPrivateCommandsEnabled = false;
    private boolean derpyChatCommandEnabled = true;
    private boolean derpyPartyCommandsEnabled = true;
    private boolean derpyGuildCommandsEnabled = true;
    private boolean derpyAllChatCommandsEnabled = false;
    private boolean derpyPrivateCommandsEnabled = false;
    private boolean yotpChatCommandEnabled = true;
    private boolean yotpPartyCommandsEnabled = true;
    private boolean yotpGuildCommandsEnabled = true;
    private boolean yotpAllChatCommandsEnabled = false;
    private boolean yotpPrivateCommandsEnabled = false;
    private boolean yotwChatCommandEnabled = true;
    private boolean yotwPartyCommandsEnabled = true;
    private boolean yotwGuildCommandsEnabled = true;
    private boolean yotwAllChatCommandsEnabled = false;
    private boolean yotwPrivateCommandsEnabled = false;
    private boolean yotsChatCommandEnabled = true;
    private boolean yotsPartyCommandsEnabled = true;
    private boolean yotsGuildCommandsEnabled = true;
    private boolean yotsAllChatCommandsEnabled = false;
    private boolean yotsPrivateCommandsEnabled = false;
    private boolean classLevelChatCommandEnabled = true;
    private boolean classLevelPartyCommandsEnabled = true;
    private boolean classLevelGuildCommandsEnabled = true;
    private boolean classLevelAllChatCommandsEnabled = false;
    private boolean classLevelPrivateCommandsEnabled = false;
    private boolean specialMayorsChatCommandsEnabled = true;
    private boolean yearlyEventsChatCommandsEnabled = true;
    // The channels every command answers in, unless its own "UseDefaultChannels" is off.
    private boolean defaultPartyCommandsEnabled = true;
    private boolean defaultGuildCommandsEnabled = true;
    private boolean defaultAllChatCommandsEnabled = false;
    private boolean defaultPrivateCommandsEnabled = false;
    private boolean c50UseDefaultChannels = true;
    private boolean ca50UseDefaultChannels = true;
    private boolean tpsUseDefaultChannels = true;
    private boolean jerryUseDefaultChannels = true;
    private boolean scorpUseDefaultChannels = true;
    private boolean derpyUseDefaultChannels = true;
    private boolean yotpUseDefaultChannels = true;
    private boolean yotwUseDefaultChannels = true;
    private boolean yotsUseDefaultChannels = true;
    private boolean classLevelUseDefaultChannels = true;

    // Loadouts & Superpairs
    private boolean loadoutsAutoCloseEnabled = false;
    private boolean loadoutsCloseOnlyOnChange = false;
    private String[] loadoutKeybinds = defaultLoadoutKeybinds();
    private boolean superpairsHelperEnabled = false;
    private boolean superpairsHelperDebugEnabled = false;
    /** Full flavor only: the Modrinth jar has no row and no feature reading it. */
    private boolean skipNpcDialogueEnabled = false;
    private int superpairsHelperX = 6;
    private int superpairsHelperY = 34;
    private int superpairsHelperScale = 75;

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
    public int sackTrackerX() { return sackTrackerX; }
    public void setSackTrackerX(int value) { sackTrackerX = value; save(); }
    public int sackTrackerY() { return sackTrackerY; }
    public void setSackTrackerY(int value) { sackTrackerY = value; save(); }
    public int sackTrackerScale() { return sackTrackerScale; }
    public void setSackTrackerScale(int value) { sackTrackerScale = Math.clamp(value, 25, 300); save(); }
    public List<SackItem> sackTrackerItems() { return Collections.unmodifiableList(sackTrackerItems); }
    public boolean peekChatEnabled() { return peekChatEnabled; }
    public void setPeekChatEnabled(boolean value) { peekChatEnabled = value; save(); }
    public String peekChatKeybind() { return peekChatKeybind == null ? defaultPeekChatKeybind() : peekChatKeybind; }
    public void setPeekChatKeybind(String value) { peekChatKeybind = value == null ? "" : value; save(); }
    public boolean peekChatScrollEnabled() { return peekChatScrollEnabled; }
    public void setPeekChatScrollEnabled(boolean value) { peekChatScrollEnabled = value; save(); }
    public int peekChatScrollLines() { return Math.clamp(peekChatScrollLines, 1, 30); }
    public void setPeekChatScrollLines(int value) { peekChatScrollLines = Math.clamp(value, 1, 30); save(); }

    public SackItem sackTrackerItem(String name) {
        String key = sackKey(name);
        for (SackItem item : sackTrackerItems) if (sackKey(item.name()).equals(key)) return item;
        return null;
    }

    /** An item's name without the symbol some names start with (a gemstone's "☠ Onyx"): menus and chat disagree on it. */
    public static String sackKey(String name) {
        return name == null ? "" : name.strip().replaceFirst("^[^\\p{L}\\p{N}]+", "");
    }

    /** Tracks the item, or stops tracking it when it already is; true when it is tracked now. */
    public boolean toggleSackTrackerItem(String name, int color, long amount) {
        SackItem existing = sackTrackerItem(name);
        if (existing != null) sackTrackerItems.remove(existing);
        else sackTrackerItems.add(new SackItem(name, color, amount, 0));
        save();
        return existing == null;
    }

    public void setSackTrackerAmount(SackItem item, long amount) {
        if (!sackTrackerItems.contains(item) || item.amount == Math.max(0, amount)) return;
        item.amount = Math.max(0, amount);
        save();
    }

    public void setSackTrackerGoal(SackItem item, long goal) {
        if (!sackTrackerItems.contains(item) || item.goal == Math.max(0, goal)) return;
        item.goal = Math.max(0, goal);
        save();
    }

    public void removeSackTrackerItem(SackItem item) {
        if (sackTrackerItems.remove(item)) save();
    }

    public void clearSackTrackerItems() {
        if (sackTrackerItems.isEmpty()) return;
        sackTrackerItems.clear();
        save();
    }

    /** Identity stays stable while editing, like ChatEmote, so rows keep owning their entry. */
    public static final class SackItem {
        private String name = "";
        private int color = 0xFFFFFF;
        private long amount;
        private long goal;

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
    public boolean c50ChatCommandEnabled() { return c50ChatCommandEnabled; }
    public void setC50ChatCommandEnabled(boolean value) { c50ChatCommandEnabled = value; save(); }
    public boolean c50PartyCommandsEnabled() { return c50UseDefaultChannels ? defaultPartyCommandsEnabled : c50PartyCommandsEnabled; }
    public void setC50PartyCommandsEnabled(boolean value) { c50PartyCommandsEnabled = value; save(); }
    public boolean c50GuildCommandsEnabled() { return c50UseDefaultChannels ? defaultGuildCommandsEnabled : c50GuildCommandsEnabled; }
    public void setC50GuildCommandsEnabled(boolean value) { c50GuildCommandsEnabled = value; save(); }
    public boolean c50AllChatCommandsEnabled() { return c50UseDefaultChannels ? defaultAllChatCommandsEnabled : c50AllChatCommandsEnabled; }
    public void setC50AllChatCommandsEnabled(boolean value) { c50AllChatCommandsEnabled = value; save(); }
    public boolean c50PrivateCommandsEnabled() { return c50UseDefaultChannels ? defaultPrivateCommandsEnabled : c50PrivateCommandsEnabled; }
    public void setC50PrivateCommandsEnabled(boolean value) { c50PrivateCommandsEnabled = value; save(); }
    public boolean ca50ChatCommandEnabled() { return ca50ChatCommandEnabled; }
    public void setCa50ChatCommandEnabled(boolean value) { ca50ChatCommandEnabled = value; save(); }
    public boolean ca50PartyCommandsEnabled() { return ca50UseDefaultChannels ? defaultPartyCommandsEnabled : ca50PartyCommandsEnabled; }
    public void setCa50PartyCommandsEnabled(boolean value) { ca50PartyCommandsEnabled = value; save(); }
    public boolean ca50GuildCommandsEnabled() { return ca50UseDefaultChannels ? defaultGuildCommandsEnabled : ca50GuildCommandsEnabled; }
    public void setCa50GuildCommandsEnabled(boolean value) { ca50GuildCommandsEnabled = value; save(); }
    public boolean ca50AllChatCommandsEnabled() { return ca50UseDefaultChannels ? defaultAllChatCommandsEnabled : ca50AllChatCommandsEnabled; }
    public void setCa50AllChatCommandsEnabled(boolean value) { ca50AllChatCommandsEnabled = value; save(); }
    public boolean ca50PrivateCommandsEnabled() { return ca50UseDefaultChannels ? defaultPrivateCommandsEnabled : ca50PrivateCommandsEnabled; }
    public void setCa50PrivateCommandsEnabled(boolean value) { ca50PrivateCommandsEnabled = value; save(); }
    public boolean tpsChatCommandEnabled() { return tpsChatCommandEnabled; }
    public void setTpsChatCommandEnabled(boolean value) { tpsChatCommandEnabled = value; save(); }
    public boolean tpsPartyCommandsEnabled() { return tpsUseDefaultChannels ? defaultPartyCommandsEnabled : tpsPartyCommandsEnabled; }
    public void setTpsPartyCommandsEnabled(boolean value) { tpsPartyCommandsEnabled = value; save(); }
    public boolean tpsGuildCommandsEnabled() { return tpsUseDefaultChannels ? defaultGuildCommandsEnabled : tpsGuildCommandsEnabled; }
    public void setTpsGuildCommandsEnabled(boolean value) { tpsGuildCommandsEnabled = value; save(); }
    public boolean tpsAllChatCommandsEnabled() { return tpsUseDefaultChannels ? defaultAllChatCommandsEnabled : tpsAllChatCommandsEnabled; }
    public void setTpsAllChatCommandsEnabled(boolean value) { tpsAllChatCommandsEnabled = value; save(); }
    public boolean tpsPrivateCommandsEnabled() { return tpsUseDefaultChannels ? defaultPrivateCommandsEnabled : tpsPrivateCommandsEnabled; }
    public void setTpsPrivateCommandsEnabled(boolean value) { tpsPrivateCommandsEnabled = value; save(); }
    public boolean jerryChatCommandEnabled() { return jerryChatCommandEnabled; }
    public void setJerryChatCommandEnabled(boolean value) { jerryChatCommandEnabled = value; save(); }
    public boolean jerryPartyCommandsEnabled() { return jerryUseDefaultChannels ? defaultPartyCommandsEnabled : jerryPartyCommandsEnabled; }
    public void setJerryPartyCommandsEnabled(boolean value) { jerryPartyCommandsEnabled = value; save(); }
    public boolean jerryGuildCommandsEnabled() { return jerryUseDefaultChannels ? defaultGuildCommandsEnabled : jerryGuildCommandsEnabled; }
    public void setJerryGuildCommandsEnabled(boolean value) { jerryGuildCommandsEnabled = value; save(); }
    public boolean jerryAllChatCommandsEnabled() { return jerryUseDefaultChannels ? defaultAllChatCommandsEnabled : jerryAllChatCommandsEnabled; }
    public void setJerryAllChatCommandsEnabled(boolean value) { jerryAllChatCommandsEnabled = value; save(); }
    public boolean jerryPrivateCommandsEnabled() { return jerryUseDefaultChannels ? defaultPrivateCommandsEnabled : jerryPrivateCommandsEnabled; }
    public void setJerryPrivateCommandsEnabled(boolean value) { jerryPrivateCommandsEnabled = value; save(); }
    public boolean scorpChatCommandEnabled() { return scorpChatCommandEnabled; }
    public void setScorpChatCommandEnabled(boolean value) { scorpChatCommandEnabled = value; save(); }
    public boolean scorpPartyCommandsEnabled() { return scorpUseDefaultChannels ? defaultPartyCommandsEnabled : scorpPartyCommandsEnabled; }
    public void setScorpPartyCommandsEnabled(boolean value) { scorpPartyCommandsEnabled = value; save(); }
    public boolean scorpGuildCommandsEnabled() { return scorpUseDefaultChannels ? defaultGuildCommandsEnabled : scorpGuildCommandsEnabled; }
    public void setScorpGuildCommandsEnabled(boolean value) { scorpGuildCommandsEnabled = value; save(); }
    public boolean scorpAllChatCommandsEnabled() { return scorpUseDefaultChannels ? defaultAllChatCommandsEnabled : scorpAllChatCommandsEnabled; }
    public void setScorpAllChatCommandsEnabled(boolean value) { scorpAllChatCommandsEnabled = value; save(); }
    public boolean scorpPrivateCommandsEnabled() { return scorpUseDefaultChannels ? defaultPrivateCommandsEnabled : scorpPrivateCommandsEnabled; }
    public void setScorpPrivateCommandsEnabled(boolean value) { scorpPrivateCommandsEnabled = value; save(); }
    public boolean derpyChatCommandEnabled() { return derpyChatCommandEnabled; }
    public void setDerpyChatCommandEnabled(boolean value) { derpyChatCommandEnabled = value; save(); }
    public boolean derpyPartyCommandsEnabled() { return derpyUseDefaultChannels ? defaultPartyCommandsEnabled : derpyPartyCommandsEnabled; }
    public void setDerpyPartyCommandsEnabled(boolean value) { derpyPartyCommandsEnabled = value; save(); }
    public boolean derpyGuildCommandsEnabled() { return derpyUseDefaultChannels ? defaultGuildCommandsEnabled : derpyGuildCommandsEnabled; }
    public void setDerpyGuildCommandsEnabled(boolean value) { derpyGuildCommandsEnabled = value; save(); }
    public boolean derpyAllChatCommandsEnabled() { return derpyUseDefaultChannels ? defaultAllChatCommandsEnabled : derpyAllChatCommandsEnabled; }
    public void setDerpyAllChatCommandsEnabled(boolean value) { derpyAllChatCommandsEnabled = value; save(); }
    public boolean derpyPrivateCommandsEnabled() { return derpyUseDefaultChannels ? defaultPrivateCommandsEnabled : derpyPrivateCommandsEnabled; }
    public void setDerpyPrivateCommandsEnabled(boolean value) { derpyPrivateCommandsEnabled = value; save(); }
    public boolean classLevelChatCommandEnabled() { return classLevelChatCommandEnabled; }
    public void setClassLevelChatCommandEnabled(boolean value) { classLevelChatCommandEnabled = value; save(); }
    public boolean classLevelPartyCommandsEnabled() { return classLevelUseDefaultChannels ? defaultPartyCommandsEnabled : classLevelPartyCommandsEnabled; }
    public void setClassLevelPartyCommandsEnabled(boolean value) { classLevelPartyCommandsEnabled = value; save(); }
    public boolean classLevelGuildCommandsEnabled() { return classLevelUseDefaultChannels ? defaultGuildCommandsEnabled : classLevelGuildCommandsEnabled; }
    public void setClassLevelGuildCommandsEnabled(boolean value) { classLevelGuildCommandsEnabled = value; save(); }
    public boolean classLevelAllChatCommandsEnabled() { return classLevelUseDefaultChannels ? defaultAllChatCommandsEnabled : classLevelAllChatCommandsEnabled; }
    public void setClassLevelAllChatCommandsEnabled(boolean value) { classLevelAllChatCommandsEnabled = value; save(); }
    public boolean classLevelPrivateCommandsEnabled() { return classLevelUseDefaultChannels ? defaultPrivateCommandsEnabled : classLevelPrivateCommandsEnabled; }
    public void setClassLevelPrivateCommandsEnabled(boolean value) { classLevelPrivateCommandsEnabled = value; save(); }
    public boolean yotpChatCommandEnabled() { return yotpChatCommandEnabled; }
    public void setYotpChatCommandEnabled(boolean value) { yotpChatCommandEnabled = value; save(); }
    public boolean yotpPartyCommandsEnabled() { return yotpUseDefaultChannels ? defaultPartyCommandsEnabled : yotpPartyCommandsEnabled; }
    public void setYotpPartyCommandsEnabled(boolean value) { yotpPartyCommandsEnabled = value; save(); }
    public boolean yotpGuildCommandsEnabled() { return yotpUseDefaultChannels ? defaultGuildCommandsEnabled : yotpGuildCommandsEnabled; }
    public void setYotpGuildCommandsEnabled(boolean value) { yotpGuildCommandsEnabled = value; save(); }
    public boolean yotpAllChatCommandsEnabled() { return yotpUseDefaultChannels ? defaultAllChatCommandsEnabled : yotpAllChatCommandsEnabled; }
    public void setYotpAllChatCommandsEnabled(boolean value) { yotpAllChatCommandsEnabled = value; save(); }
    public boolean yotpPrivateCommandsEnabled() { return yotpUseDefaultChannels ? defaultPrivateCommandsEnabled : yotpPrivateCommandsEnabled; }
    public void setYotpPrivateCommandsEnabled(boolean value) { yotpPrivateCommandsEnabled = value; save(); }
    public boolean yotwChatCommandEnabled() { return yotwChatCommandEnabled; }
    public void setYotwChatCommandEnabled(boolean value) { yotwChatCommandEnabled = value; save(); }
    public boolean yotwPartyCommandsEnabled() { return yotwUseDefaultChannels ? defaultPartyCommandsEnabled : yotwPartyCommandsEnabled; }
    public void setYotwPartyCommandsEnabled(boolean value) { yotwPartyCommandsEnabled = value; save(); }
    public boolean yotwGuildCommandsEnabled() { return yotwUseDefaultChannels ? defaultGuildCommandsEnabled : yotwGuildCommandsEnabled; }
    public void setYotwGuildCommandsEnabled(boolean value) { yotwGuildCommandsEnabled = value; save(); }
    public boolean yotwAllChatCommandsEnabled() { return yotwUseDefaultChannels ? defaultAllChatCommandsEnabled : yotwAllChatCommandsEnabled; }
    public void setYotwAllChatCommandsEnabled(boolean value) { yotwAllChatCommandsEnabled = value; save(); }
    public boolean yotwPrivateCommandsEnabled() { return yotwUseDefaultChannels ? defaultPrivateCommandsEnabled : yotwPrivateCommandsEnabled; }
    public void setYotwPrivateCommandsEnabled(boolean value) { yotwPrivateCommandsEnabled = value; save(); }
    public boolean yotsChatCommandEnabled() { return yotsChatCommandEnabled; }
    public void setYotsChatCommandEnabled(boolean value) { yotsChatCommandEnabled = value; save(); }
    public boolean yotsPartyCommandsEnabled() { return yotsUseDefaultChannels ? defaultPartyCommandsEnabled : yotsPartyCommandsEnabled; }
    public void setYotsPartyCommandsEnabled(boolean value) { yotsPartyCommandsEnabled = value; save(); }
    public boolean yotsGuildCommandsEnabled() { return yotsUseDefaultChannels ? defaultGuildCommandsEnabled : yotsGuildCommandsEnabled; }
    public void setYotsGuildCommandsEnabled(boolean value) { yotsGuildCommandsEnabled = value; save(); }
    public boolean yotsAllChatCommandsEnabled() { return yotsUseDefaultChannels ? defaultAllChatCommandsEnabled : yotsAllChatCommandsEnabled; }
    public void setYotsAllChatCommandsEnabled(boolean value) { yotsAllChatCommandsEnabled = value; save(); }
    public boolean yotsPrivateCommandsEnabled() { return yotsUseDefaultChannels ? defaultPrivateCommandsEnabled : yotsPrivateCommandsEnabled; }
    public void setYotsPrivateCommandsEnabled(boolean value) { yotsPrivateCommandsEnabled = value; save(); }
    public boolean cataChatCommandEnabled() { return c50ChatCommandEnabled && ca50ChatCommandEnabled; }
    public boolean cataPartyCommandsEnabled() { return c50PartyCommandsEnabled() && ca50PartyCommandsEnabled(); }
    public boolean cataGuildCommandsEnabled() { return c50GuildCommandsEnabled() && ca50GuildCommandsEnabled(); }
    public boolean specialMayorsChatCommandsEnabled() { return specialMayorsChatCommandsEnabled; }
    public void setSpecialMayorsChatCommandsEnabled(boolean value) { specialMayorsChatCommandsEnabled = value; save(); }
    public boolean yearlyEventsChatCommandsEnabled() { return yearlyEventsChatCommandsEnabled; }
    public void setYearlyEventsChatCommandsEnabled(boolean value) { yearlyEventsChatCommandsEnabled = value; save(); }
    public boolean defaultPartyCommandsEnabled() { return defaultPartyCommandsEnabled; }
    public void setDefaultPartyCommandsEnabled(boolean value) { defaultPartyCommandsEnabled = value; save(); }
    public boolean defaultGuildCommandsEnabled() { return defaultGuildCommandsEnabled; }
    public void setDefaultGuildCommandsEnabled(boolean value) { defaultGuildCommandsEnabled = value; save(); }
    public boolean defaultAllChatCommandsEnabled() { return defaultAllChatCommandsEnabled; }
    public void setDefaultAllChatCommandsEnabled(boolean value) { defaultAllChatCommandsEnabled = value; save(); }
    public boolean defaultPrivateCommandsEnabled() { return defaultPrivateCommandsEnabled; }
    public void setDefaultPrivateCommandsEnabled(boolean value) { defaultPrivateCommandsEnabled = value; save(); }
    public boolean c50UseDefaultChannels() { return c50UseDefaultChannels; }
    public void setC50UseDefaultChannels(boolean value) { c50UseDefaultChannels = value; save(); }
    public boolean ca50UseDefaultChannels() { return ca50UseDefaultChannels; }
    public void setCa50UseDefaultChannels(boolean value) { ca50UseDefaultChannels = value; save(); }
    public boolean tpsUseDefaultChannels() { return tpsUseDefaultChannels; }
    public void setTpsUseDefaultChannels(boolean value) { tpsUseDefaultChannels = value; save(); }
    public boolean jerryUseDefaultChannels() { return jerryUseDefaultChannels; }
    public void setJerryUseDefaultChannels(boolean value) { jerryUseDefaultChannels = value; save(); }
    public boolean scorpUseDefaultChannels() { return scorpUseDefaultChannels; }
    public void setScorpUseDefaultChannels(boolean value) { scorpUseDefaultChannels = value; save(); }
    public boolean derpyUseDefaultChannels() { return derpyUseDefaultChannels; }
    public void setDerpyUseDefaultChannels(boolean value) { derpyUseDefaultChannels = value; save(); }
    public boolean yotpUseDefaultChannels() { return yotpUseDefaultChannels; }
    public void setYotpUseDefaultChannels(boolean value) { yotpUseDefaultChannels = value; save(); }
    public boolean yotwUseDefaultChannels() { return yotwUseDefaultChannels; }
    public void setYotwUseDefaultChannels(boolean value) { yotwUseDefaultChannels = value; save(); }
    public boolean yotsUseDefaultChannels() { return yotsUseDefaultChannels; }
    public void setYotsUseDefaultChannels(boolean value) { yotsUseDefaultChannels = value; save(); }
    public boolean classLevelUseDefaultChannels() { return classLevelUseDefaultChannels; }
    public void setClassLevelUseDefaultChannels(boolean value) { classLevelUseDefaultChannels = value; save(); }

    /** Every command follows the default channels again; the defaults and all switches stay as they are. */
    public void useDefaultChannelsEverywhere() {
        c50UseDefaultChannels = ca50UseDefaultChannels = tpsUseDefaultChannels = true;
        jerryUseDefaultChannels = scorpUseDefaultChannels = derpyUseDefaultChannels = true;
        yotpUseDefaultChannels = yotwUseDefaultChannels = yotsUseDefaultChannels = classLevelUseDefaultChannels = true;
        save();
    }

    /**
     * A file from before the shared defaults keeps what it changed: a command whose own channels differ from the
     * shipped ones stays on them instead of silently following the defaults. {@code fileHasKey} says which
     * settings the file stored.
     */
    public void keepOwnChannelsWhereChanged(Predicate<String> fileHasKey) {
        if (!fileHasKey.test("c50UseDefaultChannels") && changed(c50PartyCommandsEnabled, c50GuildCommandsEnabled, c50AllChatCommandsEnabled, c50PrivateCommandsEnabled)) c50UseDefaultChannels = false;
        if (!fileHasKey.test("ca50UseDefaultChannels") && changed(ca50PartyCommandsEnabled, ca50GuildCommandsEnabled, ca50AllChatCommandsEnabled, ca50PrivateCommandsEnabled)) ca50UseDefaultChannels = false;
        if (!fileHasKey.test("tpsUseDefaultChannels") && changed(tpsPartyCommandsEnabled, tpsGuildCommandsEnabled, tpsAllChatCommandsEnabled, tpsPrivateCommandsEnabled)) tpsUseDefaultChannels = false;
        if (!fileHasKey.test("jerryUseDefaultChannels") && changed(jerryPartyCommandsEnabled, jerryGuildCommandsEnabled, jerryAllChatCommandsEnabled, jerryPrivateCommandsEnabled)) jerryUseDefaultChannels = false;
        if (!fileHasKey.test("scorpUseDefaultChannels") && changed(scorpPartyCommandsEnabled, scorpGuildCommandsEnabled, scorpAllChatCommandsEnabled, scorpPrivateCommandsEnabled)) scorpUseDefaultChannels = false;
        if (!fileHasKey.test("derpyUseDefaultChannels") && changed(derpyPartyCommandsEnabled, derpyGuildCommandsEnabled, derpyAllChatCommandsEnabled, derpyPrivateCommandsEnabled)) derpyUseDefaultChannels = false;
        if (!fileHasKey.test("yotpUseDefaultChannels") && changed(yotpPartyCommandsEnabled, yotpGuildCommandsEnabled, yotpAllChatCommandsEnabled, yotpPrivateCommandsEnabled)) yotpUseDefaultChannels = false;
        if (!fileHasKey.test("yotwUseDefaultChannels") && changed(yotwPartyCommandsEnabled, yotwGuildCommandsEnabled, yotwAllChatCommandsEnabled, yotwPrivateCommandsEnabled)) yotwUseDefaultChannels = false;
        if (!fileHasKey.test("yotsUseDefaultChannels") && changed(yotsPartyCommandsEnabled, yotsGuildCommandsEnabled, yotsAllChatCommandsEnabled, yotsPrivateCommandsEnabled)) yotsUseDefaultChannels = false;
        if (!fileHasKey.test("classLevelUseDefaultChannels") && changed(classLevelPartyCommandsEnabled, classLevelGuildCommandsEnabled, classLevelAllChatCommandsEnabled, classLevelPrivateCommandsEnabled)) classLevelUseDefaultChannels = false;
    }

    private static boolean changed(boolean party, boolean guild, boolean allChat, boolean direct) {
        return !party || !guild || allChat || direct;
    }
    public boolean loadoutsAutoCloseEnabled() { return loadoutsAutoCloseEnabled; }
    public void setLoadoutsAutoCloseEnabled(boolean value) { loadoutsAutoCloseEnabled = value; save(); }
    public boolean loadoutsCloseOnlyOnChange() { return loadoutsCloseOnlyOnChange; }
    public void setLoadoutsCloseOnlyOnChange(boolean value) { loadoutsCloseOnlyOnChange = value; save(); }
    public boolean superpairsHelperEnabled() { return superpairsHelperEnabled; }
    public void setSuperpairsHelperEnabled(boolean value) { superpairsHelperEnabled = value; save(); }
    public boolean superpairsHelperDebugEnabled() { return superpairsHelperDebugEnabled; }
    public void setSuperpairsHelperDebugEnabled(boolean value) { superpairsHelperDebugEnabled = value; save(); }
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
                for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z && layoutPeekChatKeybind == null; key++) {
                    if ("y".equals(GLFW.glfwGetKeyName(key, 0))) layoutPeekChatKeybind = "key:" + key + ":0";
                }
            } catch (Throwable noGlfw) { /* tests run without GLFW */ }
        }
        return layoutPeekChatKeybind == null ? "key:89:0" : layoutPeekChatKeybind;
    }

    /** A newly picked keyboard layout changes which key is labelled Y. */
    public static void forgetLayoutKeybind() {
        layoutPeekChatKeybind = null;
    }

    public static String[] defaultLoadoutKeybinds() {
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

    public int loadoutKeybindCount() {
        return loadoutKeybinds.length;
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

    public void normalize() {
        bowDrawIndicatorScale = Math.clamp(bowDrawIndicatorScale, 25, 300);
        frozenBlazeHudScale = Math.clamp(frozenBlazeHudScale, 25, 300);
        ragTimerScale = Math.clamp(ragTimerScale, 25, 300);
        performanceHudScale = Math.clamp(performanceHudScale, 25, 300);
        performanceRefreshTenths = Math.clamp(performanceRefreshTenths, 1, 10);
        sackTrackerScale = Math.clamp(sackTrackerScale, 25, 300);
        sackTrackerItems = sackTrackerItems == null ? new ArrayList<>()
            : new ArrayList<>(sackTrackerItems.stream().filter(item -> item != null && !item.name().isBlank()).toList());
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
