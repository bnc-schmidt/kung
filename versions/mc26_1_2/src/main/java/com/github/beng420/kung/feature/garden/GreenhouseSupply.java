package com.github.beng420.kung.feature.garden;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What picking a hologram cell should do about the item to plant: switch to it, pull the missing amount from the
 * Sacks with {@code /gfs}, or nothing. Plain Java with no game classes (only {@link #useScanner} and
 * {@link #soilsToFetch} name the hologram, and only when called); {@link GreenhouseSupplyRunner} carries a plan out.
 *
 * <p>Amounts are plants ({@code required}, {@code planted}, {@code inInventory}, {@code missing}) except
 * {@link Plan#amount()}, which counts items: a Pumpkin yields 4 Pumpkin Seeds stems, so 6 missing stems pull 2.
 * {@code planted} means cells of the layout that already hold the right plant ("placed correctly"), not plants
 * standing anywhere: a plant on a cell the layout does not want leaves that cell, and the crop, still needed.
 */
final class GreenhouseSupply {
    /** 36 inventory slots of full stacks; one {@code /gfs} can never move more than the inventory holds. */
    static final int MAX_AMOUNT = 36 * 64;

    static final String TARGET_SPOT = "This is a mutation spot, nothing to plant";

    enum Action {
        /** Enough is planted or held already: only switch to the item. */
        SWITCH,
        /** Pull {@link Plan#amount()} items with {@link Plan#command()}. */
        PULL,
        /** Something is missing but no Sack holds it: tell the player to fetch it. */
        NOT_SACKABLE,
        /** A mutation spot: nothing is planted from an item there. */
        TARGET_SPOT,
        UNKNOWN_CROP
    }

    /**
     * {@code item} is null only for {@link Action#UNKNOWN_CROP}. {@code clamped}: {@code amount} was cut to
     * {@link #MAX_AMOUNT}.
     */
    record Plan(Action action, String crop, GreenhouseItems.Item item, int required, int planted, int inInventory,
                int missing, int amount, boolean clamped, String message) {
        /** The command for {@link Action#PULL}, without the leading slash the connection adds itself. */
        String command() { return "gfs " + item.gfs() + " " + amount; }
    }

    /** Planted plants of a crop in the greenhouse; a scanner of the plot plugs in through {@link #setPlantedSource}. */
    @FunctionalInterface
    interface PlantedSource {
        int planted(String crop);
    }

    /** {@code amount} of {@code item} moved from the Sacks, as the game words it. */
    record Moved(int amount, String item) { }

    private static final List<String> MUSHROOMS = List.of("Red Mushroom", "Brown Mushroom");
    private static final PlantedSource NONE = crop -> 0;
    private static volatile PlantedSource plantedSource = NONE;
    private static final Pattern COLOR_CODES = Pattern.compile("§.");
    private static final Pattern MOVED = Pattern.compile("Moved ([\\d,]+) (.+?) from your Sacks to your inventory");
    private static final Pattern NO_ITEM = Pattern.compile("You have no (.+?) in your Sacks!");

    private GreenhouseSupply() { }

    /** Until a scanner is plugged in nothing counts as placed. Null restores that default. */
    static void setPlantedSource(PlantedSource source) { plantedSource = source == null ? NONE : source; }

    /**
     * Counts the layout's cells that already hold the right plant at the last plot scan
     * ({@link GreenhouseHologram#correctlyPlaced}), by the planner's crop name and in plants: one per cell, so a
     * Pumpkin Seeds stem is 1 here whatever it was planted from. The runner plugs this in with its first pick.
     */
    static void useScanner() { setPlantedSource(GreenhouseHologram::correctlyPlaced); }

    /**
     * Cells of the crop that are placed correctly, looked up by the planner's spelling of its name. The two
     * mushrooms count together, as {@link #required} does: the plot scan accepts either on a mushroom cell.
     */
    static int planted(String crop) {
        int total = 0;
        for (String name : interchangeable(GreenhouseCrops.canonical(crop == null ? "" : crop))) {
            total += Math.max(0, plantedSource.planted(name));
        }
        return total;
    }

    /**
     * Ingredient cells of the crop in the shown layout; target cells are mutations that grow, not planted. Red and
     * Brown Mushroom cells count together (see {@link #interchangeable}).
     */
    static int required(GreenhouseGrid grid, String crop) {
        List<String> wanted = interchangeable(GreenhouseCrops.canonical(crop));
        int count = 0;
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && cell.flag() != GreenhouseGrid.TARGET && wanted.contains(cell.name())) count++;
            }
        }
        return count;
    }

    /**
     * The crops one planted thing may stand in for. The plot scan (GreenhouseScanLogic.sameCrop) shows Brown
     * Mushroom on a Red Mushroom cell as correct, so planted and required are pooled over both; the inventory is
     * still counted for the picked crop's own item.
     */
    private static List<String> interchangeable(String crop) {
        return MUSHROOMS.contains(crop) ? MUSHROOMS : List.of(crop);
    }

    /** The game and {@code /gfs} spell a variant "INK_SACK:3"; the NEU repository says "INK_SACK-3". */
    static String normalizeId(String id) { return id == null ? "" : id.trim().replace('-', ':'); }

    static Plan plan(String crop, int flag, int required, int planted, Map<String, Integer> inventory) {
        return plan(GreenhouseItems.find(crop), crop, flag, required, planted, inventory);
    }

    /**
     * @param flag the picked cell's flag; a target cell (0) plans nothing
     * @param required ingredient cells of the crop in the hologram ({@link #required})
     * @param inventory counts of the player's items by {@link #normalizeId normalized} SkyBlock id
     */
    static Plan plan(Optional<GreenhouseItems.Item> found, String crop, int flag, int required, int planted,
                     Map<String, Integer> inventory) {
        String name = GreenhouseCrops.canonical(crop == null ? "" : crop);
        if (flag == GreenhouseGrid.TARGET) return plain(Action.TARGET_SPOT, name, null, TARGET_SPOT);
        if (found.isEmpty()) return plain(Action.UNKNOWN_CROP, name, null, unknown("crop", name));
        GreenhouseItems.Item item = found.get();
        int inInventory = plantsHeld(item, inventory);
        int missing = Math.max(0, required - planted - inInventory);
        String counts = required + " needed, " + planted + " placed correctly, " + inInventory + " in inventory";
        return finish(item, name, required, planted, inInventory, missing, counts,
            nothingToFetch(name, required, planted, counts));
    }

    /**
     * Plants the inventory can plant for the item: its own count times {@code plantsPerItem}, plus the items that
     * plant it one to one ({@link GreenhouseItems.Item#holdFirst}, the Melon Seeds beside the Melon Slice).
     */
    static int plantsHeld(GreenhouseItems.Item item, Map<String, Integer> inventory) {
        int plants = item.id() == null ? 0 : inventory.getOrDefault(normalizeId(item.id()), 0) * item.plantsPerItem();
        for (String other : item.holdFirst()) plants += inventory.getOrDefault(normalizeId(other), 0);
        return plants;
    }

    /**
     * What picking a soil ghost should do: {@code needed} is the number of ghosts in the latest scan that need this
     * soil. Soil blocks are single items (1 per block, nothing is placed already: a placed one is no ghost), so
     * the flow is the crops' without the planted count. Farmland is fetched as Dirt ({@link GreenhouseItems#soil}),
     * so a Farmland pick and a Dirt pick plan the same item; {@code needed} comes from {@link #soilNeeded}.
     */
    static Plan planSoil(GreenhouseSoils.Soil soil, int needed, Map<String, Integer> inventory) {
        Optional<GreenhouseItems.Item> found = GreenhouseItems.soil(soil);
        if (found.isEmpty()) return plain(Action.UNKNOWN_CROP, soil.displayName(), null, unknown("soil", soil.displayName()));
        GreenhouseItems.Item item = found.get();
        String name = item.crop();
        if (item.id() == null) {
            return new Plan(Action.NOT_SACKABLE, name, item, needed, 0, 0, needed, 0, false,
                name + ": " + (item.note() == null ? "no item to hold" : item.note()));
        }
        int held = inventory.getOrDefault(normalizeId(item.id()), 0);
        int missing = Math.max(0, needed - held);
        String counts = needed + " needed, " + held + " in inventory";
        return finish(item, name, needed, 0, held, missing, counts,
            "Nothing to fetch: your inventory already holds enough " + name + " (" + counts + ")");
    }

    /**
     * How many soil markers (ghosts, and any other kind of soil marker that names the soil its spot should have)
     * need the block that {@code requested} is fetched as: Farmland and Dirt spots both need Dirt.
     */
    static int soilNeeded(GreenhouseSoils.Soil requested, Iterable<GreenhouseSoils.Soil> expected) {
        GreenhouseSoils.Soil wanted = GreenhouseItems.itemSoil(requested);
        int count = 0;
        for (GreenhouseSoils.Soil soil : expected) {
            if (GreenhouseItems.itemSoil(soil) == wanted) count++;
        }
        return count;
    }

    /**
     * The soils of the ghosts that need an item, for {@link #soilNeeded}. A WRONG_STATE ghost needs one only where
     * dirt belongs (farmland stands there: Dirt has to be placed); where farmland belongs the dirt already stands
     * there and only needs tilling.
     */
    static List<GreenhouseSoils.Soil> soilsToFetch(List<GreenhouseHologram.SoilGhost> ghosts) {
        return ghosts.stream()
            .filter(ghost -> ghost.kind() != GreenhouseSoilCheck.GhostKind.WRONG_STATE
                || ghost.soil() == GreenhouseSoils.Soil.DIRT)
            .map(GreenhouseHologram.SoilGhost::soil).toList();
    }

    /** The shared tail of a plan: switch only, fetch manually, or pull {@code missing} (in plants) from the Sacks. */
    private static Plan finish(GreenhouseItems.Item item, String name, int required, int planted, int inInventory,
                               int missing, String counts, String nothing) {
        if (missing == 0) {
            return new Plan(Action.SWITCH, name, item, required, planted, inInventory, 0, 0, false, nothing);
        }
        if (!item.sackable()) {
            String note = item.note() == null ? "" : " (" + item.note() + ")";
            return new Plan(Action.NOT_SACKABLE, name, item, required, planted, inInventory, missing, 0, false,
                "Fetch " + name + " manually" + note + ": " + missing + " more needed");
        }
        int units = Math.ceilDiv(missing, item.plantsPerItem());
        boolean clamped = units > MAX_AMOUNT;
        int amount = Math.min(units, MAX_AMOUNT);
        String forPlants = item.unit().equals(name) ? "" : " for " + missing + " " + name;
        return new Plan(Action.PULL, name, item, required, planted, inInventory, missing, amount, clamped,
            "Pulling " + amount + " " + item.unit() + " from Sacks" + forPlants + " (" + counts + ")"
                + (clamped ? ", capped at " + MAX_AMOUNT : ""));
    }

    private static String unknown(String kind, String name) {
        return unknown(kind, name, GreenhouseItems.loadError());
    }

    /** "Unknown crop X", plus why the item data is unusable when it is (a broken section empties its lookups). */
    static String unknown(String kind, String name, String loadError) {
        return "Unknown " + kind + " " + name + (loadError.isEmpty() ? "" : " (item data: " + loadError + ")");
    }

    /** Why nothing is pulled: the layout is done, the crop is not part of it, or the inventory already covers it. */
    private static String nothingToFetch(String crop, int required, int placed, String counts) {
        if (required == 0) return "No " + crop + " needed in this layout";
        if (placed >= required) return "All " + required + " " + crop + " are already placed correctly.";
        return "Nothing to fetch: your inventory already holds enough " + crop + " (" + counts + ")";
    }

    private static Plan plain(Action action, String crop, GreenhouseItems.Item item, String message) {
        return new Plan(action, crop, item, 0, 0, 0, 0, 0, false, message);
    }

    /** The text without Minecraft colour codes ("§a"), which chat lines can still carry. */
    static String stripColors(String text) { return text == null ? "" : COLOR_CODES.matcher(text).replaceAll(""); }

    /** "Moved 5 Carrot from your Sacks to your inventory." (the amount can be below what was asked). */
    static Optional<Moved> parseMoved(String chat) {
        Matcher matcher = MOVED.matcher(stripColors(chat));
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(new Moved(Integer.parseInt(matcher.group(1).replace(",", "")), matcher.group(2)));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    /** "You have no Carrot in your Sacks!": the item name, empty for any other line. */
    static Optional<String> parseNoItem(String chat) {
        Matcher matcher = NO_ITEM.matcher(stripColors(chat));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
