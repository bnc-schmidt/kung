package com.github.beng420.kung.util;

import com.github.beng420.kung.feature.garden.FeastContext;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Books the XP lines of every run into the local store and remembers the last run's class XP. */
public final class CatacombsRecentXpTracker {
    public static final CatacombsRecentXpTracker INSTANCE = new CatacombsRecentXpTracker(CatacombsLocalStore.INSTANCE);

    private static final Pattern CATACOMBS_XP_PATTERN =
        Pattern.compile("^\\+(?<xp>[\\d,]+) Catacombs Experience$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_XP_PATTERN =
        Pattern.compile("^\\+(?<xp>[\\d,]+) (?<class>Archer|Berserk|Healer|Mage|Tank) Experience(?<team> \\(Team Bonus\\))?$",
            Pattern.CASE_INSENSITIVE);
    private static final long DUPLICATE_LINE_WINDOW_MILLIS = 750L;

    private final CatacombsLocalStore store;
    private final EnumMap<DungeonClass, Double> latestClassXpPerRun = new EnumMap<>(DungeonClass.class);
    private String lastRewardLine = "";
    private long lastRewardLineMillis;
    private boolean initialized;

    CatacombsRecentXpTracker(CatacombsLocalStore store) {
        this.store = store;
    }

    public static void initializeClient() {
        INSTANCE.registerClientHooks();
    }

    /** The last run's class XP for the local player; it replaces the preset projection where present. */
    public synchronized Map<DungeonClass, Double> localClassXpPerRun(String requestedName) {
        if (!store.isLocal(requestedName)) {
            return Map.of();
        }
        EnumMap<DungeonClass, Double> values = new EnumMap<>(DungeonClass.class);
        latestClassXpPerRun.forEach((dungeonClass, xp) -> {
            if (xp > 0.0) {
                values.put(dungeonClass, xp);
            }
        });
        return Map.copyOf(values);
    }

    private void registerClientHooks() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message.getString()));
    }

    synchronized void observeMessage(String rawMessage) {
        String line = HypixelLocation.clean(rawMessage);
        if (line.isBlank() || isDuplicate(line)) {
            return;
        }

        String profileId = FeastContext.profileId(line);
        if (profileId != null) {
            store.setActiveProfile(profileId);
            KungDebugRecorder.event("ca50-local-xp", "active profile=" + profileId);
            return;
        }

        Matcher cataMatcher = CATACOMBS_XP_PATTERN.matcher(line);
        if (cataMatcher.matches()) {
            double xp = parseXp(cataMatcher.group("xp"));
            boolean booked = store.book(null, xp);
            KungDebugRecorder.event("ca50-local-xp", "observed catacombs=" + Math.round(xp) + " booked=" + booked);
            return;
        }

        Matcher classMatcher = CLASS_XP_PATTERN.matcher(line);
        if (!classMatcher.matches()) {
            return;
        }
        DungeonClass dungeonClass = classFromLabel(classMatcher.group("class"));
        if (dungeonClass == null) {
            return;
        }
        double xp = parseXp(classMatcher.group("xp"));
        boolean booked = store.book(dungeonClass, xp);
        double xpPerSelectedRun = classMatcher.group("team") == null ? xp : xp * 4.0;
        latestClassXpPerRun.put(dungeonClass, xpPerSelectedRun);
        KungDebugRecorder.event("ca50-local-xp", "observed " + dungeonClass.id() + "=" + Math.round(xp)
            + " booked=" + booked + " selectedRunXp=" + Math.round(xpPerSelectedRun));
    }

    private boolean isDuplicate(String line) {
        long now = System.currentTimeMillis();
        if (line.equals(lastRewardLine) && now - lastRewardLineMillis <= DUPLICATE_LINE_WINDOW_MILLIS) {
            return true;
        }
        lastRewardLine = line;
        lastRewardLineMillis = now;
        return false;
    }

    private static double parseXp(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(text.replace(",", ""));
    }

    private static DungeonClass classFromLabel(String label) {
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            if (dungeonClass.label().equalsIgnoreCase(label)) {
                return dungeonClass;
            }
        }
        return null;
    }
}
