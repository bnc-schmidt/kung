package com.github.beng420.kung.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;

public final class KungDebugRecorder {
    /** General ring: once full, each new line evicts the oldest. */
    private static final int MAX_EVENTS = 1000;
    private static final int MAX_SPLIT_EVENTS = 128;
    private static final int MAX_SCORE_EVENTS = 256;
    private static final int MAX_ROOM_EVENTS = 128;
    /** Everything the rings can hold at once, so asking for more lines than this changes nothing. */
    public static final int MAX_STORED_LINES = MAX_EVENTS + MAX_SPLIT_EVENTS + MAX_SCORE_EVENTS + MAX_ROOM_EVENTS;
    private static final int MAX_EVENT_LENGTH = 900;
    private static final long DEFAULT_DEDUPE_MILLIS = 15_000L;
    private static final DateTimeFormatter CLOCK_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>(MAX_EVENTS);
    /** Preserve low-volume timing boundaries when room/map traffic fills the general ring. */
    private static final ArrayDeque<String> SPLIT_EVENTS = new ArrayDeque<>(MAX_SPLIT_EVENTS);
    private static final ArrayDeque<String> SCORE_EVENTS = new ArrayDeque<>(MAX_SCORE_EVENTS);
    private static final ArrayDeque<String> ROOM_EVENTS = new ArrayDeque<>(MAX_ROOM_EVENTS);
    private static final Map<String, AreaState> AREA_STATES = new HashMap<>();

    private static long sequence;
    private static long suppressedTotal;

    private KungDebugRecorder() {
    }

    public static void event(String area, String message) {
        String cleanArea = sanitize(area);
        if (cleanArea.isBlank()) {
            cleanArea = "general";
        }
        String cleanMessage = sanitize(message);
        if (cleanMessage.length() > MAX_EVENT_LENGTH) {
            cleanMessage = cleanMessage.substring(0, MAX_EVENT_LENGTH) + "...";
        }

        long nowMillis = System.currentTimeMillis();
        synchronized (EVENTS) {
            AreaPolicy policy = policyFor(cleanArea, cleanMessage);
            AreaState state = AREA_STATES.computeIfAbsent(cleanArea, ignored -> new AreaState());
            state.seen++;

            String fingerprint = fingerprint(cleanArea, cleanMessage, policy);
            if (!policy.important()
                && fingerprint.equals(state.lastFingerprint)
                && nowMillis - state.lastRecordedAtMillis <= policy.dedupeMillis()) {
                state.duplicatesSuppressed++;
                state.suppressed++;
                suppressedTotal++;
                return;
            }
            if (!policy.important() && policy.windowLimit() > 0 && rateLimited(state, policy, nowMillis)) {
                state.rateSuppressed++;
                state.suppressed++;
                suppressedTotal++;
                return;
            }

            sequence++;
            String line = String.format(
                Locale.ROOT,
                "%06d %s [%s] %s",
                sequence,
                CLOCK_FORMAT.format(Instant.now()),
                cleanArea,
                cleanMessage
            );
            EVENTS.addLast(line);
            if (cleanArea.equals("dungeon-splits") || cleanArea.equals("necron-end")) {
                SPLIT_EVENTS.addLast(line);
                while (SPLIT_EVENTS.size() > MAX_SPLIT_EVENTS) SPLIT_EVENTS.removeFirst();
            }
            if (cleanArea.equals("score-calc") || cleanArea.equals("score-bonus") || cleanArea.equals("mimic-kill")) {
                SCORE_EVENTS.addLast(line);
                while (SCORE_EVENTS.size() > MAX_SCORE_EVENTS) SCORE_EVENTS.removeFirst();
            }
            if (initialRoomPoint(cleanArea, cleanMessage)) {
                ROOM_EVENTS.addLast(line);
                while (ROOM_EVENTS.size() > MAX_ROOM_EVENTS) ROOM_EVENTS.removeFirst();
            }
            state.recorded++;
            state.lastFingerprint = fingerprint;
            state.lastRecordedAtMillis = nowMillis;
            while (EVENTS.size() > MAX_EVENTS) {
                EVENTS.removeFirst();
            }
        }
    }

    public static void clear() {
        synchronized (EVENTS) {
            EVENTS.clear();
            SPLIT_EVENTS.clear();
            SCORE_EVENTS.clear();
            ROOM_EVENTS.clear();
            AREA_STATES.clear();
            sequence = 0L;
            suppressedTotal = 0L;
        }
        event("log", "cleared");
    }

    public static String dump() {
        return dump(MAX_STORED_LINES);
    }

    public static String dump(int maxLines) {
        int limit = Math.max(1, maxLines);
        List<String> snapshot;
        int storedCount;
        synchronized (EVENTS) {
            // Sequence keys deduplicate retained evidence still in the general ring.
            var ordered = new TreeMap<Long, String>();
            for (String line : EVENTS) ordered.put(eventSequence(line), line);
            for (String line : SPLIT_EVENTS) ordered.put(eventSequence(line), line);
            for (String line : SCORE_EVENTS) ordered.put(eventSequence(line), line);
            for (String line : ROOM_EVENTS) ordered.put(eventSequence(line), line);
            snapshot = new ArrayList<>(ordered.values());
            storedCount = snapshot.size();
            snapshot = new ArrayList<>(snapshot.subList(Math.max(0, storedCount - limit), storedCount));
        }

        StringBuilder builder = new StringBuilder(snapshot.size() * 96);
        builder.append("Kung Trace\n");
        builder.append("created=").append(Instant.now()).append('\n');
        builder.append("entries=").append(snapshot.size()).append('/').append(storedCount).append('\n');
        builder.append("storedLimit=").append(MAX_EVENTS).append('\n');
        builder.append("reservedSplitLimit=").append(MAX_SPLIT_EVENTS).append('\n');
        builder.append("reservedScoreLimit=").append(MAX_SCORE_EVENTS).append('\n');
        builder.append("reservedRoomLimit=").append(MAX_ROOM_EVENTS).append('\n');
        builder.append("suppressed=").append(suppressedTotal).append('\n');
        builder.append("focus=door-title,map-change,map-check,map-discovery,map-topology,mimic-esp,mimic-rejected,player-markers,player-slots,dungeon-state,context-state,npc-dialogue\n");
        appendAreaSummary(builder);
        builder.append('\n');
        for (String line : snapshot) builder.append(line).append('\n');
        return builder.toString();
    }

    private static long eventSequence(String line) {
        return Long.parseLong(line.substring(0, line.indexOf(' ')));
    }

    public static Path saveToFile(Minecraft client) throws IOException {
        Path gameDirectory = client == null
            ? Path.of(".")
            : client.gameDirectory.toPath();
        Path directory = new com.github.beng420.kung.runtime.KungFileLayout(gameDirectory,
            gameDirectory.resolve("config")).logDirectory();
        Files.createDirectories(directory);
        Path path = directory.resolve("kung-trace-" + FILE_FORMAT.format(Instant.now()) + ".log");
        Files.writeString(path, dump(), StandardCharsets.UTF_8);
        return path;
    }

    public static String compact(String value) {
        return sanitize(value).replace(" | ", " / ");
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\u00a7.", "")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static void appendAreaSummary(StringBuilder builder) {
        synchronized (EVENTS) {
            if (AREA_STATES.isEmpty()) {
                return;
            }

            List<String> areas = new ArrayList<>(AREA_STATES.keySet());
            Collections.sort(areas);
            builder.append("areas=");
            boolean first = true;
            for (String area : areas) {
                AreaState state = AREA_STATES.get(area);
                if (state == null || state.seen <= 0L) {
                    continue;
                }
                if (!first) {
                    builder.append("; ");
                }
                first = false;
                builder.append(area)
                    .append(" seen=").append(state.seen)
                    .append(" kept=").append(state.recorded);
                if (state.suppressed > 0L) {
                    builder.append(" suppressed=").append(state.suppressed);
                    if (state.duplicatesSuppressed > 0L) {
                        builder.append(" duplicate=").append(state.duplicatesSuppressed);
                    }
                    if (state.rateSuppressed > 0L) {
                        builder.append(" rate=").append(state.rateSuppressed);
                    }
                }
            }
            builder.append('\n');
        }
    }

    private static boolean rateLimited(AreaState state, AreaPolicy policy, long nowMillis) {
        if (state.windowStartedAtMillis <= 0L
            || nowMillis - state.windowStartedAtMillis >= policy.windowMillis()) {
            state.windowStartedAtMillis = nowMillis;
            state.windowRecorded = 0;
        }
        if (state.windowRecorded >= policy.windowLimit()) {
            return true;
        }
        state.windowRecorded++;
        return false;
    }

    private static String fingerprint(String area, String message, AreaPolicy policy) {
        String text = policy.similarDedupe()
            ? message.replaceAll("-?\\d+(?:\\.\\d+)?", "#")
            : message;
        return area + '\n' + text;
    }

    private static AreaPolicy policyFor(String area, String message) {
        boolean important = important(area, message);
        return switch (area) {
            case "packet" -> new AreaPolicy(12, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            case "message" -> new AreaPolicy(20, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "actionbar" -> new AreaPolicy(6, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            // No dedupe: repeated NPC reminders and re-sent answers are the timing evidence.
            case "npc-dialogue" -> new AreaPolicy(120, 60_000L, 0L, false, important);
            // Low-volume M7 Necron-to-dragons evidence: never rate-limited or deduped, kept in the split ring.
            case "necron-end" -> new AreaPolicy(0, 60_000L, 0L, false, important);
            case "screen" -> new AreaPolicy(12, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            case "chat-filter", "chat-command", "chat-name" ->
                new AreaPolicy(20, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "loadouts-auto-close" -> new AreaPolicy(40, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            // One state line per second; digits are normalised so only changes in which signals exist (and one sample per 15 s) are kept.
            case "frozen-blaze" -> new AreaPolicy(0, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            // Stand scans repeat every half second: digits are normalised, so one sample per 15 s and at most 40 a minute.
            case "nametag", "hitbox" -> new AreaPolicy(40, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            case "bow-draw", "rag" -> new AreaPolicy(80, 60_000L, 0L, false, false);
            // Two lines per Sack pull (at most one per 2 s): never deduped, so every send shows up as evidence.
            case "greenhouse-supply" -> new AreaPolicy(0, 60_000L, 0L, false, important);
            case "map-change" -> new AreaPolicy(120, 60_000L, 3_000L, false, important);
            case "map-check" -> new AreaPolicy(30, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "mimic-esp" -> new AreaPolicy(40, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            case "map-discovery", "map-topology" ->
                new AreaPolicy(80, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "player-markers" -> new AreaPolicy(30, 60_000L, DEFAULT_DEDUPE_MILLIS, true, important);
            case "player-slots" -> new AreaPolicy(60, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "context-state", "dungeon-state" ->
                new AreaPolicy(60, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
            case "door-title" -> new AreaPolicy(120, 60_000L, 2_000L, false, true);
            default -> new AreaPolicy(0, 60_000L, DEFAULT_DEDUPE_MILLIS, false, important);
        };
    }

    /**
     * Important lines bypass both dedupe and the rate limit, so this must stay narrow: anomalies
     * and the handful of distinctive run-lifecycle phrases.
     *
     * <p>It deliberately does NOT look at the area name. Areas are called "deaths", "crypts",
     * "score-calc" and five variants of "mimic-*", so matching topic words against the area made
     * those areas permanently unthrottled. Topic words alone are gone for the same reason: they
     * matched routine key=value fields ("secrets=4", "deaths=0") and the floor name, which pinned
     * room-sync, map-topology and mimic-esp at 100% unthrottled. Those lines are still recorded -
     * they just obey the per-area budget now, which is what the budget is for.
     */
    private static boolean important(String area, String message) {
        // The snapshot emits one first nonempty hash per cell, at most 36 per instance.
        if (initialRoomPoint(area, message)) return true;
        String haystack = message.toLowerCase(Locale.ROOT);
        return haystack.contains("error")
            || haystack.contains("anomaly")
            || haystack.contains("exception")
            || haystack.contains("failed")
            || haystack.contains("crash")
            || haystack.contains("starting in 1 second")
            || haystack.contains("run-start")
            || haystack.contains("run-finished")
            || haystack.contains("world-change")
            || haystack.contains("left dungeons")
            || haystack.contains("entered dungeons")
            || haystack.contains("entered the catacombs");
    }

    private static boolean initialRoomPoint(String area, String message) {
        return area.equals("map-change") && message.startsWith("initial-room-point ");
    }

    private record AreaPolicy(
        int windowLimit,
        long windowMillis,
        long dedupeMillis,
        boolean similarDedupe,
        boolean important
    ) {
    }

    private static final class AreaState {
        long seen;
        long recorded;
        long suppressed;
        long duplicatesSuppressed;
        long rateSuppressed;
        long windowStartedAtMillis;
        int windowRecorded;
        String lastFingerprint = "";
        long lastRecordedAtMillis = Long.MIN_VALUE;
    }
}
