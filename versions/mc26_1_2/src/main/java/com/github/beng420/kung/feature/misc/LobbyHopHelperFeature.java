package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

public final class LobbyHopHelperFeature extends ConfigurableFeature<MiscConfig> {
    public static final LobbyHopHelperFeature INSTANCE = new LobbyHopHelperFeature();
    private static final int MAX_HISTORY_SIZE = 128;
    /** Hypixel recycles server ids, so a lobby left longer ago than this is treated as unknown. */
    private static final long LOBBY_MEMORY_MILLIS = Duration.ofHours(2).toMillis();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final LinkedHashMap<String, Visit> visits = new LinkedHashMap<>();
    private static String currentLobbyId = "";
    private static boolean wasEnabled;

    private LobbyHopHelperFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(LobbyHopHelperFeature::tick);
    }

    @Override
    public boolean isEnabled() {
        return config().lobbyHopHelperEnabled();
    }

    private static void tick(Minecraft client) {
        boolean enabled = INSTANCE.isEnabled();
        if (!enabled) {
            if (wasEnabled) {
                clearHistory();
            }
            wasEnabled = false;
            return;
        }

        if (!wasEnabled) {
            clearHistory();
            wasEnabled = true;
        }

        if (client.level == null
            || client.player == null
            || !HypixelInstanceTracker.INSTANCE.tracking()) {
            currentLobbyId = "";
            return;
        }

        String lobbyId = HypixelInstanceTracker.INSTANCE.serverId();
        long nowMillis = System.nanoTime() / 1_000_000L;
        Visit previous = recordVisit(lobbyId, nowMillis);
        if (previous != null) {
            alert(client, lobbyId, nowMillis, previous);
        }
    }

    static void clearHistory() {
        visits.clear();
        currentLobbyId = "";
    }

    record Visit(long arrivedMillis, long lastSeenMillis) { }

    /** Returns the previous stay on arrival, or null when no revisit should be announced. */
    static Visit recordVisit(String lobbyId, long nowMillis) {
        if (lobbyId.isBlank()) return null;
        boolean arrived = !lobbyId.equals(currentLobbyId);
        if (arrived) {
            visits.values().removeIf(visit -> nowMillis - visit.lastSeenMillis() > LOBBY_MEMORY_MILLIS);
        }
        // Refresh while present so a long stay does not count toward time spent away.
        Visit previous = visits.get(lobbyId);
        visits.put(lobbyId, new Visit(arrived || previous == null ? nowMillis : previous.arrivedMillis(), nowMillis));
        if (!arrived) return null;
        currentLobbyId = lobbyId;
        if (visits.size() > MAX_HISTORY_SIZE) {
            visits.pollFirstEntry();
        }
        return previous;
    }

    static String formatAgo(long elapsedMillis) {
        long seconds = Math.max(0L, elapsedMillis) / 1_000L;
        return (seconds >= 60L ? seconds / 60L + "m" : "") + seconds % 60L + "s ago";
    }

    /** Monotonic millis back to wall-clock time for display. */
    private static String clock(long nowMillis, long thenMillis) {
        return LocalTime.now().minus(Duration.ofMillis(nowMillis - thenMillis)).format(CLOCK);
    }

    /** Whether a lobby left {@code agoMillis} ago still gets the title and the sound: only when that is less than the warn time. */
    static boolean loud(long agoMillis, int warnSeconds) { return agoMillis < warnSeconds * 1_000L; }

    private static void alert(Minecraft client, String lobbyId, long nowMillis, Visit previous) {
        long agoMillis = nowMillis - previous.lastSeenMillis();
        String ago = formatAgo(agoMillis);
        boolean loud = loud(agoMillis, INSTANCE.config().lobbyHopWarnSeconds());
        if (loud && INSTANCE.config().lobbyHopTitleEnabled()) {
            McCompat.setSubtitle(client, Component.literal(lobbyId + " - " + ago));
            McCompat.setTitle(client, Component.literal("Swap Lobbies"));
        }
        if (INSTANCE.config().lobbyHopChatEnabled()) {
            KungMessages.send(client, KungMessages.Type.WARNING, "",
                "You've been on lobby " + lobbyId + " before! " + ago + ". ("
                    + clock(nowMillis, previous.arrivedMillis()) + " - " + clock(nowMillis, previous.lastSeenMillis()) + ")");
        }
        if (loud) client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.35F, 1.0F));
    }
}
