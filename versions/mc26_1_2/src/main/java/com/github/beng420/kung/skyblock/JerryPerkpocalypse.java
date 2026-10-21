package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.DungeonRoomDataSyncClient;
import com.github.beng420.kung.feature.dungeon.KungServerSession;
import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

/**
 * Mayor Jerry's Perkpocalypse: every 6 hours he copies one other mayor, in an order that differs per term and
 * repeats after 6 slots. The only source is the hub's "Mayor Jerry" menu, which lists the copied mayor's perks
 * and a countdown. Each reading fills one slot of the term's cycle; the Kung server pools readings so players
 * who never opened the menu still learn the other slots.
 */
public final class JerryPerkpocalypse {
    public static final JerryPerkpocalypse INSTANCE = new JerryPerkpocalypse();

    static final long SLOT_MILLIS = 6L * 60L * 60L * 1000L;
    static final int SLOTS = 6;
    private static final String MENU_TITLE = "Mayor Jerry";
    private static final String HEADER = "Perkpocalypse Perks:";
    private static final Pattern NEXT = Pattern.compile("^Next set of perks in (.*?)!?$");
    private static final Pattern UNIT = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final long PULL_INTERVAL_MILLIS = 5L * 60L * 1000L;
    private static final long UNKNOWN_PULL_INTERVAL_MILLIS = 60L * 1000L;
    private static final long REMINDER_INTERVAL_MILLIS = 5L * 60L * 1000L;
    private static final long JOIN_WARNING_DELAY_MILLIS = 20L * 1000L;
    /** Time for the server pull to answer before the first reminder of an unknown slot. */
    private static final long REMINDER_GRACE_MILLIS = 30L * 1000L;
    private static final Map<String, String> MAYOR_BY_PERK = new HashMap<>();

    static {
        // Diaz left the rotation in 2024; Foxy and the specials never joined it.
        for (String[] row : new String[][] {
            {"Aatrox", "SLASHED Pricing", "Slayer XP Buff", "Pathfinder"},
            {"Cole", "Prospection", "Mining XP Buff", "Mining Fiesta", "Molten Forge"},
            {"Diana", "Huntress' Intuition", "Mythological Ritual", "Pet XP Buff", "Sharing is Caring"},
            {"Finnegan", "Grand Feast", "GOATed", "Blooming Business", "Pest Eradicator", "Pelt-pocalypse"},
            {"Marina", "Fishing XP Buff", "Luck of the Sea 2.0", "Fishing Festival", "Double Trouble"},
            {"Paul", "Marauder", "EZPZ", "Benediction"}}) {
            for (int i = 1; i < row.length; i++) MAYOR_BY_PERK.put(row[i], row[0]);
        }
    }

    private Schedule schedule = Schedule.NONE;
    private int readMenuId = -1;
    private long nextPullMillis;
    private long unknownSinceMillis;
    private long skyBlockSinceMillis;
    private boolean joinWarned;
    private long nextReminderMillis;
    private Reading lastReading;
    private long lastReadAtMillis;
    private volatile String syncStatus = "no contact yet";

    private JerryPerkpocalypse() {
    }

    public static void initializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick(client));
    }

    /** The mayor Jerry copies right now, or "" when it is not known (yet). */
    public synchronized String copiedMayor(long electionYear, long nowMillis) {
        String mayor = schedule.year() == electionYear ? schedule.mayorAt(nowMillis) : null;
        return mayor == null ? "" : mayor;
    }

    /** What the menu says, or null unless it lists a known mayor's perks and a countdown. */
    static Reading parse(List<String> lore, long nowMillis) {
        int header = headerIndex(lore);
        if (header < 0) return null;
        String mayor = null;
        long remaining = -1;
        for (int i = header + 1; i < lore.size(); i++) {
            String line = lore.get(i).strip();
            if (mayor == null) mayor = MAYOR_BY_PERK.get(line);
            Matcher next = NEXT.matcher(line);
            if (next.matches()) remaining = countdownMillis(next.group(1));
        }
        return mayor == null || remaining <= 0 ? null : new Reading(mayor, nowMillis + remaining);
    }

    static boolean hasHeader(List<String> lore) {
        return headerIndex(lore) >= 0;
    }

    private static int headerIndex(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (HEADER.equals(lore.get(i).strip())) return i;
        }
        return -1;
    }

    /** "1d 2h 3m 4s" in any subset; -1 when no unit is found. */
    private static long countdownMillis(String text) {
        long total = -1;
        Matcher unit = UNIT.matcher(text);
        while (unit.find()) {
            long amount = Long.parseLong(unit.group(1));
            long millis = switch (unit.group(2)) {
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
            total = Math.max(total, 0) + amount * millis;
        }
        return total;
    }

    private void tick(Minecraft client) {
        SkyBlockMayorTracker tracker = SkyBlockMayorTracker.INSTANCE;
        long year = tracker.electionYear();
        long now = System.currentTimeMillis();
        if (client.player == null) {
            joinWarned = false;
            skyBlockSinceMillis = 0;
        } else {
            warnOnJoin(client, tracker.jerryInOffice(), now);
        }
        if (client.player == null || !tracker.jerryInOffice() || year < 0) {
            unknownSinceMillis = 0;
            return;
        }
        boolean unknown = copiedMayor(year, now).isEmpty();
        if (!unknown) {
            unknownSinceMillis = 0;
        } else if (unknownSinceMillis == 0) {
            unknownSinceMillis = now;
            nextPullMillis = 0; // a new slot or term: ask the server right away
        }
        if (now >= nextPullMillis && syncActive() && needsPull(year)) {
            nextPullMillis = now + (unknown ? UNKNOWN_PULL_INTERVAL_MILLIS : PULL_INTERVAL_MILLIS);
            send(HttpRequest.newBuilder(DungeonRoomDataSyncClient.endpoint("jerry?year=" + year)).GET(), year);
        }
        readMenu(client, year, now);
        remind(client, unknown, now);
    }

    /** Once per connection, a while after joining SkyBlock: Paul's score bonus is only reliable with Jerry sync on. */
    private void warnOnJoin(Minecraft client, boolean jerryInOffice, long now) {
        HypixelInstanceTracker instance = HypixelInstanceTracker.INSTANCE;
        if (!instance.tracking() || instance.location().kind() == HypixelLocation.Kind.UNKNOWN) {
            skyBlockSinceMillis = 0;
            return;
        }
        if (skyBlockSinceMillis == 0) skyBlockSinceMillis = now;
        var dungeon = KungConfig.get().dungeon;
        if (!shouldWarnOnJoin(joinWarned, now - skyBlockSinceMillis, jerryInOffice, instance.catacombs(),
            dungeon.jerrySyncEnabled(), dungeon.jerryReminderEnabled())) {
            return;
        }
        joinWarned = true;
        KungMessages.send(client, KungMessages.Type.WARNING, "Jerry",
            "Jerry is mayor and Jerry Mayor sync is off, so Paul score tracking may be inaccurate. "
                + "Turn it on under Server Sync in the settings.");
    }

    static boolean shouldWarnOnJoin(boolean alreadyWarned, long inSkyBlockMillis, boolean jerryInOffice,
                                    boolean inCatacombs, boolean syncOn, boolean remindersOn) {
        return !alreadyWarned && inSkyBlockMillis >= JOIN_WARNING_DELAY_MILLIS && jerryInOffice && !inCatacombs
            && !syncOn && remindersOn;
    }

    /** While the copied mayor is unknown, nudge the player every few minutes to open Jerry's menu. */
    private void remind(Minecraft client, boolean unknown, long now) {
        if (!unknown || now < nextReminderMillis || now - unknownSinceMillis < REMINDER_GRACE_MILLIS
            || !KungConfig.get().dungeon.jerryReminderEnabled()
            || !HypixelInstanceTracker.INSTANCE.tracking() || HypixelInstanceTracker.INSTANCE.catacombs()) {
            return;
        }
        nextReminderMillis = now + REMINDER_INTERVAL_MILLIS;
        KungMessages.send(client, KungMessages.Type.WARNING, "Jerry",
            "Kung does not know which mayor Jerry copies right now. Click Jerry in the Hub once.");
    }

    /** The lines of /kung jerry: what Kung believes, next to what the menu said, to compare by eye. */
    public synchronized List<String> statusLines(long now) {
        SkyBlockMayorTracker tracker = SkyBlockMayorTracker.INSTANCE;
        List<String> lines = new ArrayList<>();
        if (!tracker.jerryInOffice()) {
            lines.add("Jerry is not in office (mayor: " + (tracker.mayorName().isBlank() ? "unknown" : tracker.mayorName()) + ").");
            return lines;
        }
        long year = tracker.electionYear();
        String copied = copiedMayor(year, now);
        lines.add("Jerry is in office (election year " + year + "). Copying now: "
            + (copied.isEmpty() ? "unknown, click Jerry in the Hub"
                : copied + ", switches in " + duration(schedule.nextSwitchMillis(now) - now)) + ".");
        lines.add("Cycle from now (" + schedule.known() + "/" + SLOTS + " known): "
            + String.join(" > ", schedule.cycleFrom(now)));
        lines.add(lastReading == null ? "Menu reading: none this session."
            : "Menu reading " + duration(now - lastReadAtMillis) + " ago: " + lastReading.mayor()
                + ", next set of perks in " + duration(lastReading.switchAtMillis() - lastReadAtMillis) + ".");
        lines.add("Server: " + (syncActive() ? syncStatus : "off (turn on Jerry Mayor under Server Sync in the settings)")
            + ". Paul score bonus: " + (tracker.paulScoreBonusActive() ? "yes" : "no") + ".");
        return lines;
    }

    /** "4h 22m"; under a minute "<1m". */
    static String duration(long millis) {
        long minutes = Math.max(millis, 0) / 60_000L;
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes == 0 ? "<1m" : minutes + "m";
    }

    /** Opt-in on its own: the room sync toggles do not matter, nothing is sent until "Jerry Mayor" is on. */
    private static boolean syncActive() {
        return KungConfig.get().dungeon.jerrySyncEnabled() && DungeonRoomDataSyncClient.INSTANCE.configured();
    }

    private synchronized boolean needsPull(long year) {
        return schedule.year() != year || schedule.known() < SLOTS;
    }

    private void readMenu(Minecraft client, long year, long now) {
        var server = client.getCurrentServer();
        if (server == null || !HypixelLocation.isHypixelAddress(server.ip)
            || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
            || !MENU_TITLE.equals(screen.getTitle().getString().strip())
            || screen.getMenu() != client.player.containerMenu) {
            readMenuId = -1;
            return;
        }
        var menu = screen.getMenu();
        int topSlots = Math.min(menu.slots.size() - 36, 54);
        for (int index = 0; readMenuId != menu.containerId && index < topSlots; index++) {
            var lore = menu.slots.get(index).getItem().get(DataComponents.LORE);
            if (lore == null) continue;
            List<String> lines = lore.lines().stream().limit(80).map(Component::getString).toList();
            if (!hasHeader(lines)) continue;
            readMenuId = menu.containerId;
            Reading reading = parse(lines, now);
            if (reading == null) {
                KungDebugRecorder.event("mayor", "jerry menu unreadable lore=" + KungDebugRecorder.compact(String.join(" | ", lines)));
            } else {
                observe(year, reading);
            }
        }
    }

    private void observe(long year, Reading reading) {
        long slotStart = reading.switchAtMillis() - SLOT_MILLIS;
        synchronized (this) {
            lastReading = reading;
            lastReadAtMillis = System.currentTimeMillis();
            schedule = schedule.observe(year, reading.mayor(), slotStart);
            KungDebugRecorder.event("mayor", "jerry copies=" + reading.mayor() + " switchAt=" + reading.switchAtMillis()
                + " year=" + year + " known=" + schedule.known());
        }
        if (syncActive()) {
            JsonObject body = new JsonObject();
            body.addProperty("year", year);
            body.addProperty("mayor", reading.mayor());
            body.addProperty("slotStart", slotStart);
            send(HttpRequest.newBuilder(DungeonRoomDataSyncClient.endpoint("jerry/report"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), year);
        }
    }

    /** Both routes answer with the pooled schedule; keep it when it knows at least as much as ours. */
    private void send(HttpRequest.Builder request, long year) {
        KungServerSession.INSTANCE.send(request
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "Kung-Jerry"))
            .thenAccept(response -> adopt(response, year))
            .exceptionally(throwable -> {
                syncStatus = "failed (" + (throwable.getCause() != null ? throwable.getCause() : throwable).getMessage() + ")";
                KungMod.LOGGER.warn("Jerry mayor sync failed.", throwable);
                return null;
            });
    }

    private synchronized void adopt(HttpResponse<String> response, long year) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            syncStatus = "HTTP " + response.statusCode();
            return;
        }
        try {
            Schedule pooled = Schedule.fromJson(JsonParser.parseString(response.body()).getAsJsonObject());
            if (pooled.year() == year && (schedule.year() != year || pooled.known() >= schedule.known())) {
                schedule = pooled;
            }
            syncStatus = "ok, the server knows " + pooled.known() + "/" + SLOTS + " slots";
        } catch (RuntimeException exception) {
            syncStatus = "unreadable answer";
            KungMod.LOGGER.warn("Jerry mayor sync answered garbage.", exception);
        }
    }

    record Reading(String mayor, long switchAtMillis) {
    }

    /**
     * The term's cycle: slot k starts at anchor + k * 6 h and holds positions[k mod 6]. Readings give the
     * slot start from a minute-resolution countdown, so they are rounded to the nearest slot.
     */
    record Schedule(long year, long anchorMillis, List<String> positions) {
        static final Schedule NONE = new Schedule(-1, 0, Collections.nCopies(SLOTS, null));

        Schedule observe(long electionYear, String mayor, long slotStartMillis) {
            Schedule base = electionYear == year && known() > 0
                ? this : new Schedule(electionYear, slotStartMillis, NONE.positions);
            int slot = (int) Math.floorMod(Math.round((double) (slotStartMillis - base.anchorMillis) / SLOT_MILLIS), (long) SLOTS);
            List<String> next = new ArrayList<>(base.positions);
            next.replaceAll(existing -> mayor.equals(existing) ? null : existing);
            next.set(slot, mayor);
            return new Schedule(electionYear, base.anchorMillis, Collections.unmodifiableList(next));
        }

        String mayorAt(long nowMillis) {
            if (known() == 0) return null;
            return positions.get((int) Math.floorMod(Math.floorDiv(nowMillis - anchorMillis, SLOT_MILLIS), (long) SLOTS));
        }

        /** Start of the next slot, i.e. when the copied mayor changes. */
        long nextSwitchMillis(long nowMillis) {
            return anchorMillis + (Math.floorDiv(nowMillis - anchorMillis, SLOT_MILLIS) + 1) * SLOT_MILLIS;
        }

        /** The positions in the order they come up, starting with the slot running at nowMillis; "?" when unknown. */
        List<String> cycleFrom(long nowMillis) {
            int current = (int) Math.floorMod(Math.floorDiv(nowMillis - anchorMillis, SLOT_MILLIS), (long) SLOTS);
            List<String> order = new ArrayList<>();
            for (int step = 0; step < SLOTS; step++) {
                String mayor = positions.get((current + step) % SLOTS);
                order.add(mayor == null ? "?" : mayor);
            }
            return order;
        }

        int known() {
            return (int) positions.stream().filter(java.util.Objects::nonNull).count();
        }

        /** The server's {"year", "anchor", "positions"}; anything else is an empty schedule. */
        static Schedule fromJson(JsonObject json) {
            JsonElement positions = json.get("positions");
            if (!json.has("year") || !json.has("anchor") || positions == null || !positions.isJsonArray()
                || positions.getAsJsonArray().size() != SLOTS) {
                return NONE;
            }
            List<String> mayors = new ArrayList<>();
            for (JsonElement mayor : (JsonArray) positions) {
                mayors.add(mayor.isJsonPrimitive() ? mayor.getAsString() : null);
            }
            return new Schedule(json.get("year").getAsLong(), json.get("anchor").getAsLong(),
                Collections.unmodifiableList(mayors));
        }
    }
}
