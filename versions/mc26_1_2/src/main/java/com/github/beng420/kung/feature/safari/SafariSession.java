package com.github.beng420.kung.feature.safari;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client-thread, memory-only uniques; the shared packet epoch owns their lifetime. */
public final class SafariSession {
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[\\d{1,2}:\\d{2}(?::\\d{2})?]\\s*");
    private static final Pattern REPEATS = Pattern.compile("\\s*(?:\\([x×]?\\d+\\)|\\[[x×]?\\d+])$");
    private static final Pattern CAPTURE = Pattern.compile(
        "^CAPTURE! You caught (?:a|an) (.+?) and gained .+ Shards?!$");
    private static final Pattern HIDEYHO = Pattern.compile(
        "^CAPTURE! You found (?:the )?(?:SPARKLING )?Hideyho, and as a reward (?:he|it) gave you .+ Hideyho Shards?!$");
    private static final Pattern SHARE = Pattern.compile(
        "^LOOT SHARE! You received .+ Shards? from [A-Za-z0-9_]{1,16} (?:catching|finding) (?:(?:an?|the) )?(.+)!$");
    private static final Pattern ENTRY = Pattern.compile(
        "^(?:\\[(?:VIP|MVP)(?:\\+{1,2})?]\\s*)?([A-Za-z0-9_]{1,16}) entered Critter Safari!$");
    private static final Pattern HEAD_START = Pattern.compile("^HEAD START! You started with an? [A-Za-z]+ Gem!$");

    private final Set<String> caught = new HashSet<>();
    private Set<String> snapshot = Set.of();
    private long epoch = Long.MIN_VALUE;
    private String location = "";
    private SafariCritters.Region region;
    private boolean active;
    private boolean finished;

    public void select(long nextEpoch, String nextLocation) {
        if (epoch != nextEpoch) {
            reset();
            epoch = nextEpoch;
        }
        String cleaned = HypixelLocation.clean(nextLocation);
        if (cleaned.equals(location)) return;
        location = cleaned;
        // Leaving a biome keeps the last one, so the open zone does not jump between biomes.
        SafariCritters.Region biome = regionOf(location);
        if (biome != null) region = biome;
        // Missing location rows and walking between biomes never reset a run.
        if (!location.isBlank()) active = !finished && safariLocation(location);
    }

    public boolean observeMessage(String raw, String selfName) {
        String line = clean(raw);
        if (line.equals("SAFARI REWARD SUMMARY")) {
            if (active) {
                active = false;
                finished = true;
            }
            return false;
        }
        Matcher entry = ENTRY.matcher(line);
        boolean ownEntry = entry.matches() && entry.group(1).equals(selfName);
        boolean start = ownEntry || HEAD_START.matcher(line).matches()
            || line.equals("[NPC] Safari Manager: Looks good to me. Have fun out there!");
        if (start && !finished && (location.isBlank() || safariLocation(location))) active = true;
        if (!active) return false;
        String critter = capturedCritter(line);
        if (critter == null || !caught.add(critter)) return false;
        snapshot = Set.copyOf(caught);
        return true;
    }

    public boolean active() { return active; }
    public Set<String> caught() { return snapshot; }
    /** The biome the player stood in last this run, or null before the first one. */
    public SafariCritters.Region region() { return region; }

    public void reset() {
        caught.clear();
        snapshot = Set.of();
        epoch = Long.MIN_VALUE;
        location = "";
        region = null;
        active = false;
        finished = false;
    }

    static boolean safariLocation(String name) {
        return name.equalsIgnoreCase("Safari") || name.equalsIgnoreCase("Critter Safari") || regionOf(name) != null;
    }

    static SafariCritters.Region regionOf(String name) {
        for (SafariCritters.Region region : SafariCritters.Region.values()) {
            if (name.equalsIgnoreCase(region.title() + " Biome")) return region;
        }
        return null;
    }

    static String capturedCritter(String raw) {
        String line = clean(raw);
        Matcher capture = CAPTURE.matcher(line);
        if (capture.matches()) return SafariCritters.canonical(capture.group(1));
        if (HIDEYHO.matcher(line).matches()) return "Hideyho";
        Matcher share = SHARE.matcher(line);
        return share.matches() ? SafariCritters.canonical(share.group(1)) : null;
    }

    static String clean(String raw) {
        // Match whole server messages: player quotes and shard floor drops cannot award uniques.
        if (raw == null || raw.length() > 1_024) return "";
        String line = HypixelLocation.clean(raw);
        line = TIMESTAMP.matcher(line).replaceFirst("");
        return REPEATS.matcher(line).replaceFirst("").strip();
    }
}
