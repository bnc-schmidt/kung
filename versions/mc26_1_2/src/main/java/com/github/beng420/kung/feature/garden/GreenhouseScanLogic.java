package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the layout with what is planted, as plain data. {@link GreenhouseScanner} reads the world into
 * {@link Found} entries; this decides, per expected footprint, whether the right crop stands there, a different
 * recognised crop does, something unrecognised does, or nothing does. Positions are RAW world offsets from the
 * greenhouse corner (orientation was already applied to the footprints), a stand's own x/z and a block's centre.
 *
 * <p>Only a RECOGNISED crop that differs from the expected one is ever WRONG (red). Anything we cannot name,
 * whether a stand with an unknown head or a block that is not a crop, is UNVERIFIED (a neutral outline): our name
 * tables are not checked against the live game, so a gap in them must not paint a correct greenhouse red. A
 * recognised crop in a cell the layout has no entry for is an {@link Extra} (drawn pink); soil and unrecognised
 * things there are ignored.
 */
final class GreenhouseScanLogic {
    /** What is drawn for a footprint. */
    enum State {
        /** Nothing planted: show the translucent crop (and the gold slab of a mutation spot). */
        EMPTY,
        /** The right thing stands there: show nothing. */
        CORRECT,
        /** A different recognised crop stands there: a red box until it is gone. */
        WRONG,
        /** Something we cannot name stands there: a neutral outline only. */
        UNVERIFIED
    }

    /** A planted thing; {@code crop} is null when it was not recognised. */
    record Found(String crop, double x, double z) { }

    /** A place where a crop (or a mutation) is expected: a rectangle in raw offsets. */
    record Footprint(String crop, boolean mutation, double minX, double minZ, double maxX, double maxZ) {
        /** Whether the point lies strictly inside (a cell's centre: the cell belongs to exactly one footprint). */
        boolean covers(double x, double z) {
            return x > minX && x < maxX && z > minZ && z < maxZ;
        }
    }

    /** What one scan saw at a footprint, before debouncing; {@code signature} names what was found there. */
    record Verdict(State state, String signature) { }

    /** A recognised crop in a cell where the layout has no entry: its raw cell (grid offsets) and name. */
    record Extra(int dx, int dz, String crop) { }

    /**
     * {@code shown} is what to draw, aligned with {@code footprints}; {@code raw} the latest scan's verdicts;
     * {@code planted} the recognised things per crop over the whole grid area. {@code extras} are the crops shown as
     * unplanned (seen in two consecutive scans), {@code rawExtras} the latest scan's.
     */
    record Snapshot(int layoutVersion, List<Footprint> footprints, List<State> shown, List<Verdict> raw,
                    Map<String, Integer> planted, int unknown, int total, boolean unrecognisedShare,
                    List<Extra> extras, List<Extra> rawExtras) {
        /** A snapshot without unplanned crops. */
        Snapshot(int layoutVersion, List<Footprint> footprints, List<State> shown, List<Verdict> raw,
                 Map<String, Integer> planted, int unknown, int total, boolean unrecognisedShare) {
            this(layoutVersion, footprints, shown, raw, planted, unknown, total, unrecognisedShare, List.of(),
                List.of());
        }
    }

    /** A stand exactly on a footprint's edge (a 2x2 crop's stand sits where four cells meet) belongs to both sides. */
    static final double EDGE = 0.001;
    /**
     * A stand inside no footprint still counts for a footprint it is this close to; inside a footprint it counts for
     * that one only, so a stand a hair off its cell's centre never leaks into the neighbour.
     */
    static final double SLACK = 0.25;
    /** Heads our tables cannot name are worth a warning when this many are unrecognised ... */
    static final int UNRECOGNISED_MIN = 3;
    /** ... and they are at least this share of everything planted. */
    static final int UNRECOGNISED_PERCENT = 30;

    private GreenhouseScanLogic() { }

    /** The expected rectangles of a hologram's placements: a crop fills its cell, a mutation its footprint. */
    static List<Footprint> footprints(List<Placement> placements) {
        List<Footprint> out = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            double half = placement.mutation()
                ? placement.scale() / GreenhouseHologramShapes.MUTATION_HEAD_SCALE_PER_CELL / 2 : 0.5;
            out.add(new Footprint(placement.crop(), placement.mutation(), placement.x() - half, placement.z() - half,
                placement.x() + half, placement.z() + half));
        }
        return List.copyOf(out);
    }

    /** The two mushrooms grow into each other's place; everything else must match by name. */
    static boolean sameCrop(String expected, String found) {
        if (expected.equals(found)) return true;
        return Set.of("Red Mushroom", "Brown Mushroom").containsAll(Set.of(expected, found));
    }

    /** Whether so many planted things are unrecognised that the name tables are probably out of date. */
    static boolean unrecognisedShare(int unknown, int total) {
        return unknown >= UNRECOGNISED_MIN && unknown * 100 >= total * UNRECOGNISED_PERCENT;
    }

    /** Whether the point lies in the footprint, its edges included (within {@link #EDGE}). */
    static boolean inside(Footprint footprint, Found found) {
        return found.x() >= footprint.minX() - EDGE && found.x() <= footprint.maxX() + EDGE
            && found.z() >= footprint.minZ() - EDGE && found.z() <= footprint.maxZ() + EDGE;
    }

    /** Distance from the point to the footprint's rectangle; 0 inside. */
    static double distance(Footprint footprint, Found found) {
        double dx = Math.max(Math.max(footprint.minX() - found.x(), 0), found.x() - footprint.maxX());
        double dz = Math.max(Math.max(footprint.minZ() - found.z(), 0), found.z() - footprint.maxZ());
        return Math.hypot(dx, dz);
    }

    /**
     * The footprints (by index) a planted thing counts for: the ones it stands in, which is one unless it sits on a
     * shared edge; only if it stands in none, those within {@link #SLACK}.
     */
    static List<Integer> owners(List<Footprint> expected, Found found) {
        List<Integer> owners = new ArrayList<>(1);
        for (int i = 0; i < expected.size(); i++) {
            if (inside(expected.get(i), found)) owners.add(i);
        }
        if (!owners.isEmpty()) return owners;
        for (int i = 0; i < expected.size(); i++) {
            if (distance(expected.get(i), found) <= SLACK) owners.add(i);
        }
        return owners;
    }

    /**
     * One scan against the layout. Wrong and unverified verdicts must repeat identically on the next scan before
     * they are shown (a stand is briefly absent or half-loaded while a crop is replaced); empty and correct show
     * at once. {@code previous} is the last snapshot, or null; it only counts for the same footprints.
     */
    static Snapshot evaluate(List<Footprint> expected, List<Found> found, Snapshot previous, int layoutVersion) {
        int unknown = 0;
        Map<String, Integer> planted = new TreeMap<>();
        List<List<Found>> byFootprint = new ArrayList<>(expected.size());
        for (int i = 0; i < expected.size(); i++) byFootprint.add(new ArrayList<>());
        List<Extra> rawExtras = new ArrayList<>();
        for (Found item : found) {
            if (item.crop() == null) unknown++;
            else planted.merge(item.crop(), 1, Integer::sum);
            List<Integer> owners = owners(expected, item);
            for (int index : owners) byFootprint.get(index).add(item);
            Extra extra = owners.isEmpty() ? extraOf(item) : null;
            if (extra != null && !rawExtras.contains(extra)) rawExtras.add(extra);
        }
        boolean samePrevious = previous != null && previous.footprints().equals(expected);

        List<Verdict> raw = new ArrayList<>(expected.size());
        List<State> shown = new ArrayList<>(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Verdict verdict = judge(expected.get(i), byFootprint.get(i));
            raw.add(verdict);
            shown.add(debounced(verdict, samePrevious ? previous.raw().get(i) : null,
                samePrevious ? previous.shown().get(i) : State.EMPTY));
        }
        // An unplanned crop is shown once two consecutive scans saw it (or it already was shown).
        List<Extra> shownExtras = new ArrayList<>();
        for (Extra extra : rawExtras) {
            if (previous != null && (previous.rawExtras().contains(extra) || previous.extras().contains(extra))) {
                shownExtras.add(extra);
            }
        }
        return new Snapshot(layoutVersion, List.copyOf(expected), Collections.unmodifiableList(shown),
            Collections.unmodifiableList(raw), Collections.unmodifiableMap(planted), unknown, found.size(),
            unrecognisedShare(unknown, found.size()), List.copyOf(shownExtras), List.copyOf(rawExtras));
    }

    /**
     * A recognised crop standing in no footprint, in a cell of the grid: the cell where the layout has nothing.
     * Unrecognised things there are ignored (a pet, scenery); so is anything outside the 10x10 cells.
     */
    static Extra extraOf(Found found) {
        if (found.crop() == null) return null;
        int dx = (int) Math.floor(found.x());
        int dz = (int) Math.floor(found.z());
        if (dx < 0 || dx >= GreenhouseGrid.SIZE || dz < 0 || dz >= GreenhouseGrid.SIZE) return null;
        return new Extra(dx, dz, found.crop());
    }

    private static Verdict judge(Footprint footprint, List<Found> matches) {
        List<String> names = new ArrayList<>();
        boolean recognisedWrong = false;
        for (Found item : matches) {
            if (item.crop() != null && sameCrop(footprint.crop(), item.crop())) return new Verdict(State.CORRECT, "");
            names.add(item.crop() == null ? "?" : item.crop());
            if (item.crop() != null) recognisedWrong = true;
        }
        if (names.isEmpty()) return new Verdict(State.EMPTY, "");
        Collections.sort(names);
        return new Verdict(recognisedWrong ? State.WRONG : State.UNVERIFIED, String.join(",", names));
    }

    private static State debounced(Verdict verdict, Verdict before, State shownBefore) {
        if (verdict.state() == State.EMPTY || verdict.state() == State.CORRECT) return verdict.state();
        if (shownBefore == verdict.state() || verdict.equals(before)) return verdict.state();
        return shownBefore;
    }
}
