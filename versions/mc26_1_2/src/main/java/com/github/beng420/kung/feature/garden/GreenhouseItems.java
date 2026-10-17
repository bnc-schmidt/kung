package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Bundled SkyBlock item ids per Greenhouse crop (names in the editor's spelling): which item is held to plant
 * the crop and which id {@code /gfs} takes to pull it from the Sacks. Ids use ':' for variants ("INK_SACK:3"),
 * the spelling the game and {@code /gfs} use, not the '-' of the NEU repository.
 */
final class GreenhouseItems {
    static final String RESOURCE = "kung-greenhouse-items.json";
    private static final int MAX_BYTES = 100_000;
    private static final Pattern ID = Pattern.compile("[A-Z0-9_]+(?::[0-9]+)?");
    private static final Gson JSON = new Gson();

    /**
     * {@code id}: the item matched in the inventory, null when the crop has no item to hold. {@code gfs}: the Sack
     * id, null when the item is not in any Sack. {@code unit}: what the player sees pulled ("Melon Slice" for
     * Melon Seeds), the crop name otherwise. {@code plantsPerItem}: plants one item yields (Pumpkin gives 4 stems).
     * {@code chatNames}: other spellings the game's Sack answers may use for the item ("Seeds" for Wheat Seeds).
     */
    record Item(String crop, String id, String gfs, String unit, int plantsPerItem, List<String> chatNames,
                String note) {
        boolean sackable() { return gfs != null; }

        /**
         * Whether a Sack answer names this item: its crop name, its unit or a listed chat name, compared the way
         * the crop list compares names. Another item's answer ("Wheat" while Wheat Seeds are pending) is not ours.
         */
        boolean answersTo(String chatName) {
            if (chatName == null) return false;
            String wanted = GreenhouseCrops.normalize(chatName);
            return wanted.equals(GreenhouseCrops.normalize(crop)) || wanted.equals(GreenhouseCrops.normalize(unit))
                || chatNames.stream().anyMatch(name -> wanted.equals(GreenhouseCrops.normalize(name)));
        }
    }

    private record Row(String id, String gfs, String item, Integer plantsPerItem, List<String> names, String note) { }

    private record Data(int schema, Map<String, Row> crops, Map<String, Row> soils) { }

    /**
     * Both sections of the resource. They load independently: a broken {@code soils} section empties only the soils
     * (and the other way round), and {@code error} names each section that failed.
     */
    record Bundle(Map<String, Item> items, Map<String, Item> soils, String error) {
        static Bundle read(String text) {
            Map<String, Item> crops = Map.of();
            Map<String, Item> soils = Map.of();
            List<String> errors = new ArrayList<>();
            try {
                crops = parse(text);
            } catch (RuntimeException failure) {
                errors.add("crops: " + failure.getMessage());
            }
            try {
                soils = parseSoils(text);
            } catch (RuntimeException failure) {
                errors.add("soils: " + failure.getMessage());
            }
            return new Bundle(crops, soils, String.join("; ", errors));
        }
    }

    private static final class Holder {
        static final Bundle LOADED = load();
    }

    private GreenhouseItems() { }

    /** Every bundled crop in file order; empty when the resource is unusable. */
    static List<Item> all() { return List.copyOf(Holder.LOADED.items().values()); }

    /** The soil blocks that are items (Farmland is fetched as Dirt, see {@link #soil}), in file order. */
    static List<Item> soils() { return List.copyOf(Holder.LOADED.soils().values()); }

    /**
     * The item that is this soil block; empty for {@code NONE}. Its {@code crop()} is the item's display name.
     * Farmland is no item: the greenhouse tills placed Dirt by itself, so a Farmland spot is fetched as Dirt.
     */
    static Optional<Item> soil(GreenhouseSoils.Soil soil) {
        return Optional.ofNullable(Holder.LOADED.soils().get(itemSoil(soil).displayName()));
    }

    /** The soil whose item stands in for this one: Dirt for Farmland, else the soil itself. */
    static GreenhouseSoils.Soil itemSoil(GreenhouseSoils.Soil soil) {
        return soil == GreenhouseSoils.Soil.FARMLAND ? GreenhouseSoils.Soil.DIRT : soil;
    }

    /** Empty string when the bundle loaded, else why it did not. */
    static String loadError() { return Holder.LOADED.error(); }

    /** Name lookup with the crop list's loose matching ("do not eat shroom" finds Do-not-eat-shroom). */
    static Optional<Item> find(String crop) {
        if (crop == null) return Optional.empty();
        return Optional.ofNullable(Holder.LOADED.items().get(GreenhouseCrops.canonical(crop)));
    }

    private static Bundle load() {
        Bundle bundle;
        try (InputStream stream = GreenhouseItems.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                bundle = new Bundle(Map.of(), Map.of(), "Missing bundled item data");
            } else {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                bundle = bytes.length > MAX_BYTES ? new Bundle(Map.of(), Map.of(), "Item data exceeds 100 KB")
                    : Bundle.read(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException exception) {
            bundle = new Bundle(Map.of(), Map.of(), "Cannot read item data: " + exception.getMessage());
        }
        if (!bundle.error().isEmpty()) KungMod.LOGGER.warn("Greenhouse item data is not fully usable: {}", bundle.error());
        return bundle;
    }

    /** Validates strictly so a bad edit of the resource fails a test instead of pulling the wrong item. */
    static Map<String, Item> parse(String text) {
        Data data = read(text);
        if (data.crops() == null) throw new IllegalArgumentException("Unknown item data schema");
        return items(data.crops(), name -> GreenhouseCrops.NAMES.contains(name), "crop list");
    }

    /** The {@code soils} section: keys are the display names of {@link GreenhouseSoils.Soil} values. */
    static Map<String, Item> parseSoils(String text) {
        Data data = read(text);
        return items(data.soils() == null ? Map.of() : data.soils(), GreenhouseItems::isSoilName, "soil list");
    }

    private static boolean isSoilName(String name) {
        for (GreenhouseSoils.Soil soil : GreenhouseSoils.Soil.values()) {
            if (soil != GreenhouseSoils.Soil.NONE && soil.displayName().equals(name)) return true;
        }
        return false;
    }

    private static Data read(String text) {
        Data data = JSON.fromJson(text, Data.class);
        if (data == null || data.schema() != 1) throw new IllegalArgumentException("Unknown item data schema");
        return data;
    }

    private static Map<String, Item> items(Map<String, Row> rows, Predicate<String> known, String list) {
        Map<String, Item> out = new LinkedHashMap<>();
        rows.forEach((crop, row) -> {
            if (!known.test(crop)) throw new IllegalArgumentException("Not on the " + list + ": " + crop);
            if (row == null) throw new IllegalArgumentException("No item data for " + crop);
            if (row.id() != null && !ID.matcher(row.id()).matches()) {
                throw new IllegalArgumentException("Bad item id " + row.id() + " for " + crop);
            }
            if (row.gfs() != null && !ID.matcher(row.gfs()).matches()) {
                throw new IllegalArgumentException("Bad Sack id " + row.gfs() + " for " + crop);
            }
            if (row.gfs() != null && row.id() == null) throw new IllegalArgumentException("Sack id without item id: " + crop);
            int per = row.plantsPerItem() == null ? 1 : row.plantsPerItem();
            if (per < 1 || per > 64) throw new IllegalArgumentException("Bad plants per item for " + crop);
            List<String> names = row.names() == null ? List.of() : List.copyOf(row.names());
            if (names.stream().anyMatch(name -> name == null || name.isBlank())) {
                throw new IllegalArgumentException("Blank chat name for " + crop);
            }
            out.put(crop, new Item(crop, row.id(), row.gfs(), row.item() == null ? crop : row.item(), per, names,
                row.note()));
        });
        return Collections.unmodifiableMap(out);
    }
}
