package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bundled rarity of each Greenhouse mutation (crop names in the editor's spelling). Base crops, Dead Plants and
 * Fire have none; the cost model groups them by what they are instead.
 */
final class GreenhouseRarity {
    static final String RESOURCE = "kung-greenhouse-rarity.json";
    private static final int MAX_BYTES = 20_000;
    private static final Gson JSON = new Gson();

    enum Rarity {
        COMMON("Common"), UNCOMMON("Uncommon"), RARE("Rare"), EPIC("Epic"), LEGENDARY("Legendary");

        private final String displayName;

        Rarity(String displayName) { this.displayName = displayName; }

        String displayName() { return displayName; }

        static Optional<Rarity> byDisplayName(String name) {
            for (Rarity rarity : values()) {
                if (rarity.displayName.equals(name)) return Optional.of(rarity);
            }
            return Optional.empty();
        }
    }

    private record Data(int schema, Map<String, String> rarity) { }

    private record Loaded(Map<String, Rarity> rarities, String error) { }

    private static final class Holder {
        static final Loaded LOADED = load();
    }

    private GreenhouseRarity() { }

    /** The mutation's rarity; empty for base crops, Dead Plants, Fire and names the list does not know. */
    static Optional<Rarity> of(String crop) {
        if (crop == null) return Optional.empty();
        return Optional.ofNullable(Holder.LOADED.rarities().get(GreenhouseCrops.canonical(crop)));
    }

    /** Every rated mutation in file order; empty when the resource is unusable. */
    static Map<String, Rarity> all() { return Holder.LOADED.rarities(); }

    /** Empty string when the bundle loaded, else why it did not. */
    static String loadError() { return Holder.LOADED.error(); }

    private static Loaded load() {
        Loaded loaded;
        try (InputStream stream = GreenhouseRarity.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                loaded = new Loaded(Map.of(), "Missing bundled rarity data");
            } else {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                loaded = bytes.length > MAX_BYTES ? new Loaded(Map.of(), "Rarity data exceeds 20 KB")
                    : new Loaded(parse(new String(bytes, StandardCharsets.UTF_8)), "");
            }
        } catch (IOException | RuntimeException exception) {
            loaded = new Loaded(Map.of(), "Cannot read rarity data: " + exception.getMessage());
        }
        if (!loaded.error().isEmpty()) KungMod.LOGGER.warn("Greenhouse rarity data is not usable: {}", loaded.error());
        return loaded;
    }

    /** Validates strictly so a bad edit of the resource fails a test instead of mispricing a crop. */
    static Map<String, Rarity> parse(String text) {
        Data data = JSON.fromJson(text, Data.class);
        if (data == null || data.schema() != 1 || data.rarity() == null) {
            throw new IllegalArgumentException("Unknown rarity data schema");
        }
        Map<String, Rarity> out = new LinkedHashMap<>();
        data.rarity().forEach((crop, name) -> {
            if (!GreenhouseCrops.NAMES.contains(crop)) throw new IllegalArgumentException("Not on the crop list: " + crop);
            out.put(crop, Rarity.byDisplayName(name).orElseThrow(
                () -> new IllegalArgumentException("Unknown rarity " + name + " for " + crop)));
        });
        return Collections.unmodifiableMap(out);
    }
}
