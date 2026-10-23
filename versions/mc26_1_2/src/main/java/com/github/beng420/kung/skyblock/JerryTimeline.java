package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.skyblock.JerryPerkpocalypse.Schedule;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jerry's term laid out in time: 124 real hours (one SkyBlock year, 5d 4h) cut into 6 h slots, each holding the
 * mayor the schedule says he copies then. 124 h is 20 slots and 4 h, so the term ends inside a 21st slot: the first
 * three mayors of the rotation come back four times (the third only for those last 4 h), the other three three times.
 */
final class JerryTimeline {
    private static final long SB_EPOCH_MILLIS = 1_560_275_700_000L;
    private static final long SB_YEAR_MILLIS = 446_400_000L;
    /** The election's winner takes office on Late Spring 27: 88 SkyBlock days of 20 minutes into the year. */
    private static final long TAKE_OFFICE_OFFSET_MILLIS = 88L * 1_200_000L;
    /** Readings come from a countdown in minutes, so a slot grid a few minutes off the term start still counts as aligned. */
    private static final long SNAP_MILLIS = 5L * 60_000L;
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private JerryTimeline() { }

    /** One stretch with one copied mayor; {@code mayor} is null while the schedule does not know it. */
    record Slot(long startMillis, long endMillis, String mayor) {
        boolean contains(long nowMillis) {
            return startMillis <= nowMillis && nowMillis < endMillis;
        }
    }

    /**
     * When the term of the mayor elected in {@code electionYear} (the year of the vote, as the API gives it) starts
     * and ends: Late Spring 27 of the next and of the year after. Checked against the scoreboard's "Jerry (3d, 5h)".
     */
    static long termStartMillis(long electionYear) {
        return yearStartMillis(electionYear + 1) + TAKE_OFFICE_OFFSET_MILLIS;
    }

    static long termEndMillis(long electionYear) {
        return yearStartMillis(electionYear + 2) + TAKE_OFFICE_OFFSET_MILLIS;
    }

    private static long yearStartMillis(long year) {
        return SB_EPOCH_MILLIS + (year - 1) * SB_YEAR_MILLIS;
    }

    /** Every slot of the term; empty while the schedule knows nothing of this term. */
    static List<Slot> slots(Schedule schedule, long electionYear) {
        if (schedule.known() == 0 || schedule.year() != electionYear) return List.of();
        long start = termStartMillis(electionYear);
        long end = termEndMillis(electionYear);
        long offset = Math.floorMod(start - schedule.anchorMillis(), JerryPerkpocalypse.SLOT_MILLIS);
        boolean aligned = offset <= SNAP_MILLIS || JerryPerkpocalypse.SLOT_MILLIS - offset <= SNAP_MILLIS;
        long grid = aligned ? start : schedule.anchorMillis();
        List<Slot> slots = new ArrayList<>();
        for (long from = start; from < end; ) {
            long to = Math.min(grid + (Math.floorDiv(from - grid, JerryPerkpocalypse.SLOT_MILLIS) + 1) * JerryPerkpocalypse.SLOT_MILLIS, end);
            // The middle of the slot, far from its edges, so a minute of error in the anchor cannot pick the neighbour.
            slots.add(new Slot(from, to, schedule.mayorAt(from + (to - from) / 2)));
            from = to;
        }
        return List.copyOf(slots);
    }

    /** The slot that holds {@code nowMillis}, or -1 outside the term. */
    static int indexAt(List<Slot> slots, long nowMillis) {
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).contains(nowMillis)) return index;
        }
        return -1;
    }

    /** All slots of one mayor, in time order. */
    static List<Slot> termsOf(List<Slot> slots, String mayor) {
        return slots.stream().filter(slot -> mayor.equals(slot.mayor())).toList();
    }

    /** The mayors still to come from slot {@code index} on, at most one rotation (6) and never past the term's end. */
    static List<String> rotation(List<Slot> slots, int index) {
        List<String> mayors = new ArrayList<>();
        for (int step = index; step < slots.size() && step < index + JerryPerkpocalypse.SLOTS; step++) {
            mayors.add(slots.get(step).mayor());
        }
        return mayors;
    }

    /** "Fri 23 Oct 17:15 - 23:15", or with the end's date when it falls on another day. */
    static String span(Slot slot, ZoneId zone) {
        var start = Instant.ofEpochMilli(slot.startMillis()).atZone(zone);
        var end = Instant.ofEpochMilli(slot.endMillis()).atZone(zone);
        return FULL.format(start) + " - " + (start.toLocalDate().equals(end.toLocalDate()) ? CLOCK : FULL).format(end);
    }
}
