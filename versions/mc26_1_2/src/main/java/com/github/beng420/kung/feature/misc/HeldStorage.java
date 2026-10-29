package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.Set;

/** The items whose storage the HUD shows: each has its own SkyBlock id, storage screen and way to use blocks up. */
enum HeldStorage {
    /** Uses up as many blocks as its chat line says. */
    BUILDERS_RULER("BUILDERS_RULER", "Builder's Ruler", Set.of()),
    /** Plants a row and takes one seed per soil block of it, with no chat line. */
    BASKET_OF_SEEDS("BASKET_OF_SEEDS", "Basket of Seeds", Set.of("minecraft:farmland", "minecraft:grass_block",
        "minecraft:dirt", "minecraft:sand", "minecraft:red_sand", "minecraft:jungle_log")),
    /** The item still carries the old "Pouch" name in its id. */
    NETHER_WART_BASKET("NETHER_WART_POUCH", "Nether Wart Basket", Set.of("minecraft:soul_sand"));

    private final String itemId;
    private final String name;
    private final Set<String> soil;

    HeldStorage(String itemId, String name, Set<String> soil) {
        this.itemId = itemId;
        this.name = name;
        this.soil = soil;
    }

    /** The storage a SkyBlock item id belongs to, or null for any other item. */
    static HeldStorage of(String skyblockId) {
        for (HeldStorage storage : values()) if (storage.itemId.equals(skyblockId)) return storage;
        return null;
    }

    /** True when a screen with this title is the item's storage: its known name or the name of the held item. */
    boolean opens(String screenTitle, String heldName) {
        String title = normalize(screenTitle);
        return title.equalsIgnoreCase(normalize(name)) || !title.isEmpty() && title.equalsIgnoreCase(normalize(heldName));
    }

    boolean plantsRows() { return !soil.isEmpty(); }

    /** Whether the item plants on this block, by registry id ("minecraft:farmland"). */
    boolean plantsOn(String blockId) { return soil.contains(blockId); }

    private static String normalize(String text) { return HypixelLocation.clean(text).replace((char) 0x2019, '\''); }
}
