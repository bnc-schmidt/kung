package com.github.beng420.kung.feature.hunting;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Which armor stands are Invisibugs, and where the line to the nearest one ends. An Invisibug is an empty, unnamed
 * armor stand that gives off crit particles (SkyHanni finds it the same way). Its particles appear 0.8 blocks straight
 * above the stand (up to 1.7 and half a block aside while it flies); a tree being chopped gives crits too, but 2.7
 * blocks to the side of a stand. Nothing here touches Minecraft types, so tests can run it.
 */
final class InvisibugTracker {
    /**
     * A crit particle belongs to a blank stand it appears above: at most this far to the side and between these two
     * heights over it. Measured in the 2026-10-27 trace: bugs 0..0.53 aside and 0.8..1.62 above, a tree crit 2.7 aside
     * and 0.27 above.
     */
    static final double PARTICLE_MAX_SIDEWAYS = 1.0;
    static final double PARTICLE_MIN_ABOVE = 0.4;
    static final double PARTICLE_MAX_ABOVE = 2.0;
    /** A stand stays found this many client ticks after its last crit particle; nothing measured yet, see the trace. */
    static final int EXPIRE_TICKS = 100;
    /** The line points at bugs this close to the camera; the server sends no particles from farther away. */
    static final double LINE_RANGE = 32.0;

    /** The tab list names the island; the sidebar usually names only the area inside it. */
    private static final Set<String> AREAS = Set.of("moonglade marsh", "galatea");

    record Stand(int id, double x, double y, double z) {
        double distanceTo(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Where the line ends: x and y in clip space (-1..1 is the screen) and whether the target is on screen. */
    record End(float x, float y, boolean onScreen) {
    }

    private final Map<Integer, Long> lastCrit = new HashMap<>();

    /** The stand a crit particle comes from: the nearest one it appears right above, or null. */
    static Stand source(double x, double y, double z, Collection<Stand> stands) {
        Stand best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Stand stand : stands) {
            double above = y - stand.y();
            double sideways = Math.hypot(x - stand.x(), z - stand.z());
            if (sideways > PARTICLE_MAX_SIDEWAYS || above < PARTICLE_MIN_ABOVE || above > PARTICLE_MAX_ABOVE) continue;
            double distance = stand.distanceTo(x, y, z);
            if (distance < bestDistance) {
                best = stand;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The stand nearest to this point within the range, or null. */
    static Stand nearest(double x, double y, double z, Collection<Stand> stands, double range) {
        Stand best = null;
        double bestDistance = range;
        for (Stand stand : stands) {
            double distance = stand.distanceTo(x, y, z);
            if (distance <= bestDistance) {
                best = stand;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** True on Moonglade Marsh, by the sidebar's area name or the tab list's "Area:" row. */
    static boolean inMarsh(String instanceLine, List<String> visibleLines) {
        if (isArea(instanceLine)) return true;
        for (String line : visibleLines) {
            String clean = HypixelLocation.clean(line);
            if (clean.regionMatches(true, 0, "Area:", 0, 5) && isArea(clean.substring(5))) return true;
        }
        return false;
    }

    private static boolean isArea(String name) {
        return AREAS.contains(HypixelLocation.clean(name).replaceFirst("^[⏣ф\\p{Co}]+\\s*", "").toLowerCase(Locale.ROOT));
    }

    /**
     * Turns a projected point (the world-to-clip transform's x, y and w) into where the line ends. A point off screen or
     * behind the camera keeps its direction and stops on the screen's edge, so the line still says which way to turn.
     */
    static End lineEnd(float x, float y, float w) {
        boolean behind = w <= 0F;
        float scale = Math.max(Math.abs(w), 1.0E-4F);
        float ndcX = x / scale;
        float ndcY = y / scale;
        boolean onScreen = !behind && Math.abs(ndcX) <= 1F && Math.abs(ndcY) <= 1F;
        float edge = Math.max(Math.abs(ndcX), Math.abs(ndcY));
        if (onScreen) return new End(ndcX, ndcY, true);
        // A point right behind the camera has no direction; point down, where the player's feet are.
        if (edge < 1.0E-4F) return new End(0F, -1F, false);
        return new End(ndcX / edge, ndcY / edge, false);
    }

    void seen(int id, long tick) {
        lastCrit.put(id, tick);
    }

    boolean isEmpty() {
        return lastCrit.isEmpty();
    }

    boolean contains(int id) {
        return lastCrit.containsKey(id);
    }

    Set<Integer> ids() {
        return lastCrit.keySet();
    }

    void clear() {
        lastCrit.clear();
    }

    /** Drops bugs that stopped giving off crits or whose stand is gone; the keys are the dropped ids, the values the reason. */
    Map<Integer, String> prune(long tick, IntPredicate present) {
        Map<Integer, String> dropped = new HashMap<>();
        for (var entry : new ArrayList<>(lastCrit.entrySet())) {
            if (!present.test(entry.getKey())) dropped.put(entry.getKey(), "gone");
            else if (tick - entry.getValue() > EXPIRE_TICKS) dropped.put(entry.getKey(), "expired");
        }
        dropped.keySet().forEach(lastCrit::remove);
        return dropped;
    }
}
