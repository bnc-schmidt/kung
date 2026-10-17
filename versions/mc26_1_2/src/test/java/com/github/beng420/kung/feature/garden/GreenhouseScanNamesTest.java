package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseScanNames.Kind;
import com.github.beng420.kung.feature.garden.GreenhouseScanNames.Resolution;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public final class GreenhouseScanNamesTest {
    private static final String ZOMBUD_LIVE = "b2c4994b7a1c45231b623b8245c117382b267c8856c57cffa2d808c241027a51";
    private static final String JERRYFLOWER_LIVE = "d6a223c5610763ed43cecf187ff1cb061eb1049eca0596ab424379f9106890fb";
    private static final String RAT_PEST_63 = "a8abb471db0ab78703011979dc8b40798a941f3a4dec3ec61cbeec2af8cffe8";
    private static final String BEETLE_PEST = "70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c";

    private static String crop(String itemName, String hash) {
        Resolution resolution = GreenhouseScanNames.resolve(itemName, hash);
        assertEquals(itemName + " / " + hash, Kind.CROP, resolution.kind());
        return resolution.crop();
    }

    @Test
    public void internalStyleNamesResolveToThePlannersCropNames() {
        assertEquals("Lonelily", crop("Lonelilly", null));
        assertEquals("Snoozling", crop("snoozlingFlower", null));
        assertEquals("Jerryseed", crop("jerryseed", null));
        assertEquals("PlantBoy Advance", crop("Plantboy", null));
        assertEquals("Do-not-eat-shroom", crop("donoteatshroom", null));
        assertEquals("All-in Aloe", crop("allinaloe", null));
        assertEquals("Chorus Fruit", crop("chorusFruit", null));
        assertEquals("Godseed", crop("godseed", null));
        assertEquals("Melon Seeds", crop("melon", null));
        assertEquals("Dead Plants", crop("deadplant", null));
    }

    @Test
    public void caseSeparatorsAndColourCodesDoNotMatter() {
        assertEquals("Snoozling", crop("Snoozling Flower", null));
        assertEquals("Snoozling", crop("snoozling_flower", null));
        assertEquals("Stoplight Petal", crop("§aStoplight Petal", null));
        assertEquals("Wheat Seeds", crop("WHEAT", null));
    }

    @Test
    public void theLongestFragmentWinsSoShortOnesStayDeterministic() {
        assertEquals("Cocoa Beans", crop("coco", null));
        assertEquals("Cocoa Beans", crop("Cocoabeans", null));
        // "coco" must not steal the mutations that start with "choco".
        assertEquals("Chocoberry", crop("Chocoberry", null));
        assertEquals("Choconut", crop("Choconut", null));
        assertEquals("Fire", crop("fire", null));
        assertEquals("Sunflower", crop("sunflower", null));
        assertEquals("Moonflower", crop("moonflower", null));
        assertEquals("Snoozling", crop("snoozlingflower", null));
    }

    @Test
    public void longerNamesAreNotEatenByTheirShortPrefixes() {
        assertEquals("Fertilized Jerryseed", crop("Fertilized Jerryseed", null));
        assertEquals("Jerryseed", crop("Jerryseed", null));
        assertEquals("Condensed Helianthus", crop("Condensed Helianthus", null));
        assertEquals("Helianthus", crop("Helianthus", null));
        assertEquals("Ethereal Vine", crop("Ethereal Vine", null));
        assertEquals("Rose Dragon Egg", crop("Rose Dragon Egg", null));
        assertEquals("Wild Rose", crop("Wild Rose", null));
    }

    @Test
    public void aLiveJerryflowerIsFoundByItsSkinEvenThoughItsNameIsJerryseed() {
        // The live game names it "jerryseed", which is the name of the separate Jerryseed crop.
        assertEquals("Jerryflower", crop("jerryseed", JERRYFLOWER_LIVE));
        assertEquals("Zombud", crop("Zombud Head", ZOMBUD_LIVE));
    }

    @Test
    public void everyCropNameResolvesToItself() {
        for (String name : GreenhouseCrops.NAMES) {
            assertEquals(name, crop(name, null));
            // The name in the other spellings the game and the site use.
            assertEquals(name, crop(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), null));
        }
    }

    @Test
    public void pestsByNameAreNotCrops() {
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("Firefly", null).kind());
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("Dragonfly", null).kind());
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("Lunar Moth", null).kind());
    }

    @Test
    public void decorationPartsAndPestsAreIgnored() {
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("PlantboyRoots", null).kind());
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("godseedPillar", null).kind());
        // A pest skin wins over a name that happens to contain a crop word.
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("Firefly", BEETLE_PEST).kind());
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("Fire Ant", RAT_PEST_63).kind());
        assertEquals(63, RAT_PEST_63.length());
    }

    @Test
    public void aSkinHashResolvesWhenTheNameDoesNot() {
        String cactusHash = GreenhouseIcons.table().get("Cactus").head();
        assertEquals("Cactus", crop("Mystery head", cactusHash));
        // Two of the table's hashes are 63 characters long.
        String coalroot = GreenhouseIcons.table().get("Coalroot").head();
        assertEquals(63, coalroot.length());
        assertEquals("Coalroot", crop("???", coalroot));
    }

    @Test
    public void theKnownDiscrepanciesHaveAliases() {
        assertEquals("Zombud", crop("???", ZOMBUD_LIVE));
        assertEquals("Jerryflower", crop("???", JERRYFLOWER_LIVE));
        // The planner's own hashes for them still resolve.
        assertEquals("Zombud", crop("???", GreenhouseIcons.table().get("Zombud").head()));
        assertEquals("Jerryflower", crop("???", GreenhouseIcons.table().get("Jerryflower").head()));
    }

    @Test
    public void everyIconTableHeadResolvesToItsOwnCrop() {
        GreenhouseIcons.table().forEach((crop, icon) -> {
            if (icon.head() == null) return;
            // The planner's "Fertilized Jerryseed" icon is the skin the live game puts on Jerryflower.
            String expected = icon.head().equals(JERRYFLOWER_LIVE) ? "Jerryflower" : crop;
            assertEquals(crop, expected, GreenhouseScanNames.resolve("???", icon.head()).crop());
        });
    }

    @Test
    public void whatNothingExplainsIsUnknown() {
        assertEquals(Resolution.UNKNOWN, GreenhouseScanNames.resolve("Banana", "deadbeef"));
        assertEquals(Resolution.UNKNOWN, GreenhouseScanNames.resolve(null, null));
        assertEquals(Resolution.UNKNOWN, GreenhouseScanNames.resolve("", ""));
        assertNull(GreenhouseScanNames.resolve("Banana", null).crop());
    }

    @Test
    public void theTextureTailIsTheHashEvenWhenItIs63Characters() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + RAT_PEST_63 + "\"}}}";
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(RAT_PEST_63, GreenhouseDumpFormat.texture(base64).orElseThrow().hash());
        assertEquals(Kind.IGNORED, GreenhouseScanNames.resolve("x", GreenhouseDumpFormat.texture(base64).orElseThrow()
            .hash()).kind());
    }

    @Test
    public void plainCropsAreBlocks() {
        assertEquals("Cactus", GreenhouseScanNames.cropOfBlock("minecraft:cactus"));
        assertEquals("Cocoa Beans", GreenhouseScanNames.cropOfBlock("minecraft:cocoa"));
        assertEquals("Melon Seeds", GreenhouseScanNames.cropOfBlock("minecraft:melon_stem"));
        assertEquals("Melon Seeds", GreenhouseScanNames.cropOfBlock("minecraft:attached_melon_stem"));
        assertEquals("Pumpkin Seeds", GreenhouseScanNames.cropOfBlock("minecraft:pumpkin_stem"));
        assertEquals("Pumpkin Seeds", GreenhouseScanNames.cropOfBlock("minecraft:attached_pumpkin_stem"));
        assertEquals("Red Mushroom", GreenhouseScanNames.cropOfBlock("minecraft:red_mushroom"));
        assertEquals("Brown Mushroom", GreenhouseScanNames.cropOfBlock("minecraft:brown_mushroom"));
        assertEquals("Wild Rose", GreenhouseScanNames.cropOfBlock("minecraft:rose_bush"));
        assertEquals("Sunflower", GreenhouseScanNames.cropOfBlock("minecraft:sunflower"));
        assertEquals("Moonflower", GreenhouseScanNames.cropOfBlock("minecraft:blue_orchid"));
        assertEquals("Wheat Seeds", GreenhouseScanNames.cropOfBlock("minecraft:wheat"));
        assertEquals("Carrot", GreenhouseScanNames.cropOfBlock("minecraft:carrots"));
        assertEquals("Potato", GreenhouseScanNames.cropOfBlock("minecraft:potatoes"));
        assertEquals("Nether Wart", GreenhouseScanNames.cropOfBlock("minecraft:nether_wart"));
        assertEquals("Sugar Cane", GreenhouseScanNames.cropOfBlock("minecraft:sugar_cane"));
        assertEquals("Dead Plants", GreenhouseScanNames.cropOfBlock("minecraft:dead_bush"));
        assertEquals("Fire", GreenhouseScanNames.cropOfBlock("minecraft:fire"));
        assertEquals("Ethereal Vine", GreenhouseScanNames.cropOfBlock("minecraft:vine"));
        assertNull(GreenhouseScanNames.cropOfBlock("minecraft:stone"));
        assertNull(GreenhouseScanNames.cropOfBlock(null));
    }

    @Test
    public void everyFragmentPointsAtAKnownCrop() {
        for (String name : new String[] {"ashwreath", "timestalk", "cropie", "squash", "fermento", "helianthus"}) {
            assertTrue(name, GreenhouseCrops.NAMES.contains(crop(name, null)));
        }
    }
}
