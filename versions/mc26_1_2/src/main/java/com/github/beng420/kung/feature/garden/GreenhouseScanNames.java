package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tells what a planted thing in the greenhouse is, from plain data: the name of an armor stand's head item, the skin
 * hash on its profile, or a block id. Plain Java so the tables are unit-tested; they were gathered from public
 * knowledge of the game and are UNVERIFIED against the live greenhouse: a head that matches nothing is UNKNOWN,
 * which {@link GreenhouseScanLogic} treats with care, and {@code /kung greenhouse dump} shows what the game really
 * names its heads.
 */
final class GreenhouseScanNames {
    enum Kind { CROP, UNKNOWN, IGNORED }

    /** {@code crop} is the planner's crop name for {@link Kind#CROP}, else null. */
    record Resolution(Kind kind, String crop) {
        static final Resolution UNKNOWN = new Resolution(Kind.UNKNOWN, null);
        static final Resolution IGNORED = new Resolution(Kind.IGNORED, null);
    }

    /**
     * Fragments of the head item's name, as the game spells them internally, lower case without separators, to the
     * planner's crop. Matching is "contains", longest fragment first, so "coco" cannot steal "cocoa" and short
     * fragments like "fire" only win when nothing longer fits.
     */
    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("ashwreath", "Ashwreath"), Map.entry("choconut", "Choconut"), Map.entry("dustgrain", "Dustgrain"),
        Map.entry("gloomgourd", "Gloomgourd"), Map.entry("lonelilly", "Lonelily"), Map.entry("lonelily", "Lonelily"),
        Map.entry("scourroot", "Scourroot"), Map.entry("shadevine", "Shadevine"), Map.entry("veilshroom", "Veilshroom"),
        Map.entry("witherbloom", "Witherbloom"), Map.entry("chocoberry", "Chocoberry"),
        Map.entry("cindershade", "Cindershade"), Map.entry("coalroot", "Coalroot"), Map.entry("creambloom", "Creambloom"),
        Map.entry("duskbloom", "Duskbloom"), Map.entry("thornshade", "Thornshade"), Map.entry("blastberry", "Blastberry"),
        Map.entry("cheesebite", "Cheesebite"), Map.entry("chloronite", "Chloronite"),
        Map.entry("donoteatshroom", "Do-not-eat-shroom"), Map.entry("fleshtrap", "Fleshtrap"),
        Map.entry("magicjellybean", "Magic Jellybean"), Map.entry("noctilume", "Noctilume"),
        Map.entry("snoozlingflower", "Snoozling"), Map.entry("snoozling", "Snoozling"), Map.entry("soggybud", "Soggybud"),
        Map.entry("chorusfruit", "Chorus Fruit"), Map.entry("plantboy", "PlantBoy Advance"),
        Map.entry("puffercloud", "Puffercloud"), Map.entry("shellfruit", "Shellfruit"),
        Map.entry("startlevine", "Startlevine"), Map.entry("stoplightpetal", "Stoplight Petal"),
        Map.entry("thunderling", "Thunderling"), Map.entry("turtlellini", "Turtlellini"), Map.entry("zombud", "Zombud"),
        Map.entry("allinaloe", "All-in Aloe"), Map.entry("devourer", "Devourer"), Map.entry("glasscorn", "Glasscorn"),
        Map.entry("godseed", "Godseed"), Map.entry("jerryseed", "Jerryseed"),
        Map.entry("fertilizedjerryseed", "Fertilized Jerryseed"), Map.entry("jerryflower", "Jerryflower"),
        Map.entry("phantomleaf", "Phantomleaf"), Map.entry("timestalk", "Timestalk"), Map.entry("coco", "Cocoa Beans"),
        Map.entry("wildrose", "Wild Rose"), Map.entry("moonflower", "Moonflower"), Map.entry("sunflower", "Sunflower"),
        Map.entry("cactus", "Cactus"), Map.entry("melon", "Melon Seeds"), Map.entry("pumpkin", "Pumpkin Seeds"),
        Map.entry("brownmushroom", "Brown Mushroom"), Map.entry("redmushroom", "Red Mushroom"),
        Map.entry("wheat", "Wheat Seeds"), Map.entry("carrot", "Carrot"), Map.entry("potato", "Potato"),
        Map.entry("netherwart", "Nether Wart"), Map.entry("sugarcane", "Sugar Cane"), Map.entry("deadplant", "Dead Plants"),
        Map.entry("fire", "Fire"), Map.entry("helianthus", "Helianthus"),
        Map.entry("condensedhelianthus", "Condensed Helianthus"), Map.entry("etherealvine", "Ethereal Vine"),
        Map.entry("rosedragonegg", "Rose Dragon Egg"), Map.entry("fermento", "Fermento"),
        Map.entry("squash", "Squash"), Map.entry("cropie", "Cropie"));

    /**
     * Not plants: decoration parts of the big crops and the garden's pests (a "Firefly" must not become Fire). Pest
     * skins are also recognised by hash; these names catch the ones the hash list lacks.
     */
    private static final Set<String> IGNORED_NAMES = Set.of("plantboyroots", "godseedpillar", "firefly", "dragonfly",
        "fieldmouse", "earthworm", "lunarmoth", "prayingmantis", "mosquito", "locust", "cricket", "beetle", "slug",
        "moth");

    /**
     * Skins the planner's icon table lacks or spells differently from the live game (Zombud, Jerryflower). They are
     * exact, so they win over the name table: a live Jerryflower is named "jerryseed", which is also the name of the
     * separate Jerryseed crop.
     */
    private static final Map<String, String> ALIASES = Map.of(
        "b2c4994b7a1c45231b623b8245c117382b267c8856c57cffa2d808c241027a51", "Zombud",
        "d6a223c5610763ed43cecf187ff1cb061eb1049eca0596ab424379f9106890fb", "Jerryflower");

    /** Pest heads that sit in the garden; one hash is 63 characters long. */
    private static final Set<String> PEST_HASHES = Set.of(
        "70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c",
        "a24c69f96ce556221e195c8ef2bfad71ebf7f95f5ae914a484a8d0ec21672674",
        "254aff4c0b2dce3a672349cc0ee9e6f3a9deebe4b3556e84611eca250a7821bf",
        "6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8",
        "fd40aa50905235b628e7379eb31fa45cd41b503f09721db3c437fcee39207dfc",
        "f379e09252817314bd0b694f7d53b48af2c7fa8499109802a41bb294d2f93e3e",
        "4ce79e90adf34718f313ec24d6c6135b69b3788c618498446ccc83ca640c0b14",
        "3e52782d7f2aaee8af5ba2928fec7885e94879334c2296bc9e7e2dbc5418e58f",
        "9d90e777826a52461368e26d1b2e19bfa1ba582d602483e545f4124d0f731842",
        "4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9",
        "bee4fcc5aac27bdbb0be210df08b05bca5aebc89bf2821f608a55fd2cf0434be",
        "be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff",
        "52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe",
        "65485c4b34e5b5470be94de100e61f7816f81bc5a11dfdf0eccf890172da5d0a",
        "1e04bb6367caa4e88f5fd0ee80f0745d137a6060223dbbc42a16471fdf64bb83",
        "a8abb471db0ab78703011979dc8b40798a941f3a4dec3ec61cbeec2af8cffe8",
        "7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba");

    /**
     * The crops that are plain blocks in the world, by block id. Some of these may be heads in the live game;
     * listing them only means that, if they are blocks, a correct planting is not taken for something unknown.
     */
    private static final Map<String, String> BLOCKS = Map.ofEntries(
        Map.entry("minecraft:wheat", "Wheat Seeds"), Map.entry("minecraft:carrots", "Carrot"),
        Map.entry("minecraft:potatoes", "Potato"), Map.entry("minecraft:nether_wart", "Nether Wart"),
        Map.entry("minecraft:sugar_cane", "Sugar Cane"), Map.entry("minecraft:dead_bush", "Dead Plants"),
        Map.entry("minecraft:fire", "Fire"), Map.entry("minecraft:vine", "Ethereal Vine"),
        Map.entry("minecraft:cactus", "Cactus"), Map.entry("minecraft:cocoa", "Cocoa Beans"),
        Map.entry("minecraft:melon_stem", "Melon Seeds"), Map.entry("minecraft:attached_melon_stem", "Melon Seeds"),
        Map.entry("minecraft:pumpkin_stem", "Pumpkin Seeds"),
        Map.entry("minecraft:attached_pumpkin_stem", "Pumpkin Seeds"),
        Map.entry("minecraft:red_mushroom", "Red Mushroom"), Map.entry("minecraft:brown_mushroom", "Brown Mushroom"),
        Map.entry("minecraft:rose_bush", "Wild Rose"), Map.entry("minecraft:sunflower", "Sunflower"),
        Map.entry("minecraft:blue_orchid", "Moonflower"));

    /** Name fragments, longest first, so the first match is the most specific one. */
    private static final List<Map.Entry<String, String>> BY_LENGTH = new ArrayList<>(NAMES.entrySet());

    static {
        BY_LENGTH.sort(Comparator.<Map.Entry<String, String>>comparingInt(entry -> -entry.getKey().length())
            .thenComparing(Map.Entry::getKey));
    }

    private static final class Hashes {
        static final Map<String, String> BY_HASH = build();

        private static Map<String, String> build() {
            Map<String, String> out = new HashMap<>();
            GreenhouseIcons.table().forEach((crop, icon) -> {
                if (icon.head() != null) out.putIfAbsent(icon.head(), crop);
            });
            out.putAll(ALIASES);
            return Map.copyOf(out);
        }
    }

    private GreenhouseScanNames() { }

    /**
     * What a head is. A pest skin is ignored first (its item name could contain a crop word such as "fire");
     * then an ignored name (decoration part or pest); then the exact live skins (aliases); then the name table;
     * then the planner's skin hashes; else unknown.
     *
     * @param itemName   the head item's name as the game shows it, may be null
     * @param textureHash the skin url's last path segment (see {@link GreenhouseDumpFormat#texture}), may be null
     */
    static Resolution resolve(String itemName, String textureHash) {
        if (textureHash != null && PEST_HASHES.contains(textureHash)) return Resolution.IGNORED;
        String normalized = normalize(itemName);
        for (String ignored : IGNORED_NAMES) {
            if (normalized.contains(ignored)) return Resolution.IGNORED;
        }
        String alias = textureHash == null ? null : ALIASES.get(textureHash);
        if (alias != null) return new Resolution(Kind.CROP, alias);
        for (Map.Entry<String, String> entry : BY_LENGTH) {
            if (normalized.contains(entry.getKey())) return new Resolution(Kind.CROP, entry.getValue());
        }
        if (textureHash != null) {
            String crop = Hashes.BY_HASH.get(textureHash);
            if (crop != null) return new Resolution(Kind.CROP, crop);
        }
        return Resolution.UNKNOWN;
    }

    /** The crop a block id is, or null. */
    static String cropOfBlock(String blockId) {
        return blockId == null ? null : BLOCKS.get(blockId);
    }

    /** Lower case letters and digits only: "Snoozling Flower", "snoozling_flower" and "snoozlingFlower" agree. */
    static String normalize(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') {
                i++; // colour code and its letter
            } else if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
