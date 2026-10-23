package com.github.beng420.kung.util;

import com.github.beng420.kung.config.KungConfig;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The time zone for times shown to the player (Jerry's terms, election dates): the one picked under Debug, else the
 * one Java detects. Detection can be wrong (a launcher or a container running on UTC), so it can be overridden.
 */
public final class KungTime {
    /**
     * One entry per distinct zone (offset in summer and in winter), west to east, named by its official abbreviation
     * with the offset now in brackets: "CEST (GMT+2)". A zone ID is behind each, so daylight saving follows by itself.
     * Any other ID typed into the config file works too.
     */
    static final List<String> ZONES = List.of(
        "Pacific/Honolulu", "America/Anchorage", "America/Los_Angeles", "America/Denver", "America/Chicago",
        "America/New_York", "America/Halifax", "America/St_Johns", "America/Sao_Paulo", "UTC", "Europe/London",
        "Europe/Berlin", "Europe/Athens", "Africa/Johannesburg", "Europe/Moscow", "Asia/Tehran", "Asia/Dubai",
        "Asia/Karachi", "Asia/Kolkata", "Asia/Kathmandu", "Asia/Bangkok", "Asia/Shanghai", "Asia/Tokyo",
        "Australia/Adelaide", "Australia/Sydney", "Pacific/Auckland");
    /** Official names Java's English zone names lack (it prints the offset instead). */
    private static final Map<String, String> ABBREVIATIONS = Map.of(
        "America/Sao_Paulo", "BRT", "Asia/Tehran", "IRST", "Asia/Dubai", "GST", "Asia/Kathmandu", "NPT", "Asia/Bangkok", "ICT");
    private static final String AUTO = "Auto";
    private static final DateTimeFormatter ABBREVIATION = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    private KungTime() { }

    /** The zone for every time shown to the player. */
    public static ZoneId zone() {
        return zone(KungConfig.get().debug.timeZone());
    }

    /** The picked zone, or the detected one when nothing (valid) is picked. */
    static ZoneId zone(String picked) {
        if (picked != null && !picked.isBlank()) {
            try {
                return ZoneId.of(picked.strip());
            } catch (DateTimeException invalid) {
                // A hand-edited config with a typo falls back to detection instead of breaking every message.
            }
        }
        return ZoneId.systemDefault();
    }

    /** "GMT+2", "GMT-3", "GMT+5:30", "GMT+0". */
    static String gmt(ZoneId zone, Instant at) {
        int seconds = zone.getRules().getOffset(at).getTotalSeconds();
        int minutes = Math.abs(seconds) / 60;
        String sign = seconds < 0 ? "-" : "+";
        return "GMT" + sign + minutes / 60 + (minutes % 60 == 0 ? "" : ":" + String.format(Locale.ROOT, "%02d", minutes % 60));
    }

    /** The official abbreviation in use at {@code at} ("CEST" in summer, "CET" in winter), or null when there is none. */
    static String abbreviation(String id, Instant at) {
        String override = ABBREVIATIONS.get(id);
        if (override != null) return override;
        String name = ABBREVIATION.format(ZonedDateTime.ofInstant(at, ZoneId.of(id)));
        return name.chars().allMatch(Character::isLetter) ? name : null;
    }

    /** "CEST (GMT+2)", or just "GMT+2" for a zone without an official abbreviation. */
    static String named(String id, Instant at) {
        String abbreviation = abbreviation(id, at);
        String gmt = gmt(ZoneId.of(id), at);
        return abbreviation == null ? gmt : abbreviation + " (" + gmt + ")";
    }

    /** What one choice reads: the abbreviation for the listed zones, the ID for any other. */
    static String label(String id, Instant at) {
        return ZONES.contains(id) ? named(id, at) : id + " (" + gmt(ZoneId.of(id), at) + ")";
    }

    /** The choices of the settings screen: Auto first (with what it detects), then the current custom zone, then the list. */
    public static List<String> options() {
        return options(KungConfig.get().debug.timeZone(), Instant.now());
    }

    static List<String> options(String picked, Instant at) {
        List<String> options = new ArrayList<>();
        options.add(AUTO + ": " + named(ZoneId.systemDefault().getId(), at));
        String current = picked == null ? "" : picked.strip();
        if (!current.isEmpty() && validId(current) && !ZONES.contains(current)) options.add(label(current, at));
        for (String id : ZONES) options.add(label(id, at));
        return options;
    }

    /** The choice that stands for the picked zone, as listed by {@link #options()}. */
    public static String selected() {
        return selected(KungConfig.get().debug.timeZone(), Instant.now());
    }

    static String selected(String picked, Instant at) {
        String current = picked == null ? "" : picked.strip();
        List<String> options = options(picked, at);
        return current.isEmpty() || !validId(current) ? options.getFirst() : label(current, at);
    }

    /** The zone ID a choice stands for: blank for Auto. */
    public static String idOf(String choice) {
        return idOf(choice, Instant.now());
    }

    static String idOf(String choice, Instant at) {
        if (choice == null || choice.startsWith(AUTO)) return "";
        for (String id : ZONES) {
            if (label(id, at).equals(choice)) return id;
        }
        int end = choice.indexOf(" (");
        String head = end < 0 ? choice : choice.substring(0, end);
        return validId(head) ? head : "";
    }

    private static boolean validId(String id) {
        try {
            ZoneId.of(id);
            return true;
        } catch (DateTimeException invalid) {
            return false;
        }
    }
}
