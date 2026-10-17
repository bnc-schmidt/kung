package com.github.beng420.kung.feature.garden;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bundled Greenhouse mutation recipes (wiki data, crop names in the editor's spelling).
 * A recipe is a count per ingredient crop over the ring of cells around the footprint.
 */
final class GreenhouseMutations {
    static final String RESOURCE = "kung-greenhouse-mutations.json";
    private static final int MAX_BYTES = 200_000;
    /** The solver keeps one bit per recipe in a long. */
    static final int MAX_SUPPORTED = 64;
    private static final Gson JSON = new Gson();

    /**
     * {@code needs} is an ordered crop to count map; {@code emptyRing} (Lonelily) means no crop may stand
     * in the ring at all. {@code unsupported} holds the reason a mutation has no ring recipe, else null.
     */
    record Mutation(String name, int width, int height, Map<String, Integer> needs, boolean emptyRing,
                    List<String> soil, int chancePct, String source, String unsupported) {
        boolean supported() { return unsupported == null; }

        /** All cells at Chebyshev distance 1 around the footprint: 8, 12 or 16. */
        int ringSize() { return (width + 2) * (height + 2) - width * height; }
    }

    private record Row(String name, int width, int height, Map<String, Integer> needs, Boolean emptyRing,
                       List<String> soil, Integer chancePct, String source, String unsupported) { }

    private record Data(int schema, List<Row> mutations) { }

    private record Loaded(List<Mutation> all, List<Mutation> supported, String error) {
        Loaded(List<Mutation> all, String error) {
            this(all, all.stream().filter(Mutation::supported).toList(), error);
        }
    }

    private static final class Holder {
        static final Loaded LOADED = load();
    }

    private GreenhouseMutations() { }

    /** Every bundled mutation in wiki order, specials included; empty when the resource is unusable. */
    static List<Mutation> all() { return Holder.LOADED.all(); }

    /** The mutations the solver can plan for: those with a ring recipe, in wiki order. */
    static List<Mutation> supported() { return Holder.LOADED.supported(); }

    /** Empty string when the bundle loaded, else why it did not. */
    static String loadError() { return Holder.LOADED.error(); }

    /** Name lookup with the crop list's loose matching, so "Do not eat shroom" finds Do-not-eat-shroom. */
    static Optional<Mutation> find(String name) {
        if (name == null) return Optional.empty();
        String wanted = GreenhouseCrops.normalize(name);
        return all().stream().filter(mutation -> GreenhouseCrops.normalize(mutation.name()).equals(wanted)).findFirst();
    }

    private static Loaded load() {
        try (InputStream stream = GreenhouseMutations.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) return new Loaded(List.of(), "Missing bundled mutation data");
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return new Loaded(List.of(), "Mutation data exceeds 200 KB");
            return new Loaded(parse(new String(bytes, StandardCharsets.UTF_8)), "");
        } catch (IOException | RuntimeException exception) {
            return new Loaded(List.of(), "Cannot read mutation data: " + exception.getMessage());
        }
    }

    /** Validates strictly so a bad edit of the resource fails a test instead of mis-solving. */
    static List<Mutation> parse(String text) {
        Data data = JSON.fromJson(text, Data.class);
        if (data == null || data.schema() != 1 || data.mutations() == null) {
            throw new IllegalArgumentException("Unknown mutation data schema");
        }
        Set<String> seen = new HashSet<>();
        int supported = 0;
        List<Mutation> out = new ArrayList<>();
        for (Row row : data.mutations()) {
            if (row.name() == null || !isKnown(row.name())) {
                throw new IllegalArgumentException("Mutation is not on the crop list: " + row.name());
            }
            if (!seen.add(GreenhouseCrops.normalize(row.name()))) {
                throw new IllegalArgumentException("Duplicate mutation " + row.name());
            }
            if (row.width() < 1 || row.width() > 3 || row.height() < 1 || row.height() > 3) {
                throw new IllegalArgumentException("Bad footprint for " + row.name());
            }
            boolean unsupported = row.unsupported() != null;
            Map<String, Integer> needs = new LinkedHashMap<>();
            if (!unsupported) {
                if (row.needs() == null) throw new IllegalArgumentException("No recipe for " + row.name());
                row.needs().forEach((crop, count) -> {
                    if (!isKnown(crop) || count == null || count < 1) {
                        throw new IllegalArgumentException("Bad ingredient " + crop + " for " + row.name());
                    }
                    needs.put(crop, count);
                });
                supported++;
            }
            boolean emptyRing = Boolean.TRUE.equals(row.emptyRing());
            Mutation mutation = new Mutation(row.name(), row.width(), row.height(),
                Collections.unmodifiableMap(needs), emptyRing, List.copyOf(row.soil() == null ? List.of() : row.soil()),
                row.chancePct() == null ? 0 : row.chancePct(), row.source() == null ? "" : row.source(),
                row.unsupported());
            if (!unsupported) {
                int total = needs.values().stream().mapToInt(Integer::intValue).sum();
                // Without any need and without emptyRing the recipe would match every empty cell.
                if (total > mutation.ringSize() || (total == 0) != emptyRing) {
                    throw new IllegalArgumentException("Inconsistent recipe for " + row.name());
                }
                // The chance weights the split between recipes a ring meets at once.
                if (mutation.chancePct() < 1) throw new IllegalArgumentException("No spawn chance for " + row.name());
            }
            out.add(mutation);
        }
        if (supported > MAX_SUPPORTED) throw new IllegalArgumentException("Too many supported mutations");
        return List.copyOf(out);
    }

    /** True when the crop list spells this name exactly the same way. */
    private static boolean isKnown(String name) {
        return GreenhouseCrops.canonical(name).equals(name) && GreenhouseCrops.NAMES.contains(name);
    }
}
