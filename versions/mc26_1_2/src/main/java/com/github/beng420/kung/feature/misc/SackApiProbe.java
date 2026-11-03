package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.SackCounts;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Developer probe, switched by {@code /kung dev sackapi}: it reads your Sack counts from the Hypixel API (with your
 * own key, else through the Kung server) every 30 seconds and writes what changed, next to every [Sacks] message of the game, to the shared trace as {@code sack-api}
 * lines. The gap between a pick-up in chat and the API showing it is how far the API trails, which decides whether
 * an API resync can clear the Sack Tracker's "~". It only watches.
 */
public final class SackApiProbe {
    static final String AREA = "sack-api";
    private static final int POLL_TICKS = 600;

    /** Client thread only. */
    private static boolean on;
    private static int ticksUntilPoll;
    /** Written by the HTTP thread when a poll ends. */
    private static volatile boolean inFlight;
    private static volatile SackCounts previous;
    /** The reason of the last failed poll: the chat hears about each new one once, the trace about every one. */
    private static volatile String lastFailure;

    private SackApiProbe() { }

    /** Called once from the Sack Tracker's initialization. */
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SackApiProbe::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (on && !overlay) chat(message);
        });
    }

    public static String set(boolean enabled) {
        String source = HypixelSkyBlockProfileClient.sackSource();
        if (enabled && source.isEmpty()) {
            return "Neither your own Hypixel API key nor the Kung server is set up.";
        }
        on = enabled;
        previous = null;
        lastFailure = null;
        ticksUntilPoll = 0;
        KungDebugRecorder.event(AREA, enabled ? "probe on source=" + source : "probe off");
        return enabled
            ? "Sack API probe on (" + (HypixelSkyBlockProfileClient.SOURCE_HYPIXEL.equals(source) ? "your Hypixel key" : "the Kung server")
                + "). Farm one item for about 10 minutes, then run /kung log save and /kung dev sackapi off."
            : "Sack API probe off.";
    }

    private static void tick(Minecraft client) {
        if (!on || inFlight || client.player == null || --ticksUntilPoll > 0) return;
        ticksUntilPoll = POLL_TICKS;
        inFlight = true;
        HypixelSkyBlockProfileClient.INSTANCE.loadSackCounts(client.player.getUUID().toString())
            .whenComplete((counts, error) -> {
                inFlight = false;
                if (error == null) {
                    lastFailure = null;
                    answered(counts);
                } else {
                    failed(error);
                }
            });
    }

    /** No retry: the next poll is 30 s away, since the Kung server's limit (30 per 5 minutes) is shared with the secrets lookups. */
    private static void failed(Throwable error) {
        String reason = String.valueOf((error instanceof CompletionException && error.getCause() != null ? error.getCause() : error).getMessage());
        KungDebugRecorder.event(AREA, "poll failed: " + reason);
        if (reason.equals(lastFailure)) return;
        lastFailure = reason;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> KungMessages.send(client, KungMessages.Type.WARNING, "Sack API probe", "Poll failed: " + reason));
    }

    private static void answered(SackCounts counts) {
        SackCounts before = previous;
        previous = counts;
        if (before == null || !before.profile().equals(counts.profile()) || !before.source().equals(counts.source())) {
            KungDebugRecorder.event(AREA, "baseline source=" + counts.source() + " profile=" + counts.profile()
                + " items=" + counts.counts().size()
                + (counts.counts().isEmpty() ? " (no sacks_counts: " + counts.note() + ")" : ""));
            return;
        }
        String diff = diff(before.counts(), counts.counts());
        KungDebugRecorder.event(AREA, diff.isEmpty() ? "unchanged" : "changed " + diff);
    }

    private static void chat(Component message) {
        String text = SackTrackerFeature.plain(message);
        if (!text.startsWith("[Sacks] ")) return;
        KungDebugRecorder.event(AREA, "chat " + SackTrackerFeature.changes(SackTrackerFeature.hoverTexts(message)).entrySet().stream()
            .map(entry -> (entry.getValue() > 0 ? "+" : "") + entry.getValue() + " " + entry.getKey())
            .collect(Collectors.joining(", ")) + " (last " + SackTrackerFeature.coveredSeconds(text) + "s)");
    }

    /** "WHEAT 100->150, CARROT 0->5" for every id whose amount differs (a missing id counts as 0); empty when none does. */
    static String diff(Map<String, Long> before, Map<String, Long> after) {
        TreeSet<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        return ids.stream()
            .filter(id -> !before.getOrDefault(id, 0L).equals(after.getOrDefault(id, 0L)))
            .map(id -> id + " " + before.getOrDefault(id, 0L) + "->" + after.getOrDefault(id, 0L))
            .collect(Collectors.joining(", "));
    }
}
