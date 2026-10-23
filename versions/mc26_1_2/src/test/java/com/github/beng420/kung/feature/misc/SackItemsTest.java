package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SackItemsTest {
    @Test
    public void knowsSackItemsByTheirSkyBlockId() {
        assertTrue(SackItems.contains("ENCHANTED_WHEAT"));
        assertTrue(SackItems.contains("COBBLESTONE"));
        // Hypixel writes damage values with a colon, the bundled list with a dash.
        assertTrue(SackItems.contains("INK_SACK:3"));
        assertTrue(SackItems.contains("INK_SACK-3"));
    }

    @Test
    public void everythingElseIsNoSackItem() {
        assertFalse(SackItems.contains("CRAFTING_TABLE"));
        assertFalse(SackItems.contains("HYPERION"));
        assertFalse(SackItems.contains(""));
        assertFalse(SackItems.contains(null));
    }

    @Test
    public void anItemTrackedOutsideItsSackShowsAQuestionMarkUntilTheSackIsRead() {
        var items = java.util.List.of(
            new com.github.beng420.kung.config.category.MiscConfig.SackItem("Wheat", 0xFFFFFF, -1, 1000),
            new com.github.beng420.kung.config.category.MiscConfig.SackItem("Carrot", 0xFFFFFF, 5, 1000),
            new com.github.beng420.kung.config.category.MiscConfig.SackItem("Cactus", 0xFFFFFF, -1, 0));
        var lines = SackTrackerFeature.lines(items, false).stream().map(line -> line.getString()).toList();
        assertEquals(java.util.List.of("Sack Tracker", "Wheat: ?/1,000", "Carrot: 5/1,000", "Cactus: ?"), lines);
        assertEquals("?", SackTrackerFeature.amountText(-1));
        assertEquals("1,234", SackTrackerFeature.amountText(1234));
    }

    @Test
    public void gemItemsNameTheirGemAndTheQualityTheMessagesCount() {
        var plain = SackGemstones.parseName("Onyx Gemstones");
        assertEquals("Onyx", plain.gem());
        assertNull(plain.quality());
        assertEquals("", plain.symbol());
        var shown = SackGemstones.parseName("☠ Flawed Onyx Gemstone");
        assertEquals("☠", shown.symbol());
        assertEquals("Flawed", shown.quality());
        assertEquals("Onyx", shown.gem());
        // [Sacks] messages name "<symbol> <quality> <gem> Gemstone".
        assertEquals("☠ Rough Onyx Gemstone", shown.trackedName("Rough"));
        assertEquals("Fine Onyx Gemstone", plain.trackedName("Fine"));
        assertNull(SackGemstones.parseName("Enchanted Wheat"));
        assertNull(SackGemstones.parseName("Gemstone Grinder"));
        assertNull(SackGemstones.parseName(""));
    }

    @Test
    public void theFilterButtonAndTheAmountLinesDecideWhichQualityIsMeant() {
        // Lines as SkyHanni documents them for the Gemstones Sack.
        assertEquals("Rough", SackGemstones.filterQuality(List.of("Click to change!", " No filter", "▶ Rough", " Flawed")));
        assertEquals("Fine", SackGemstones.filterQuality(List.of("▶ Fine")));
        assertNull(SackGemstones.filterQuality(List.of("▶ No filter", " Rough", " Flawed")));
        assertNull(SackGemstones.filterQuality(List.of("Nothing here")));

        var unfiltered = List.of("Mined Onyx", " Rough: 78,999 (78,999)", " Flawed: 604 (48,320)", " Fine: 35 (224,000)");
        assertEquals(java.util.Map.of("Rough", 78_999L, "Flawed", 604L, "Fine", 35L), SackGemstones.amounts(unfiltered, null));
        // With a filter the item shows one "Amount" for that quality.
        assertEquals(java.util.Map.of("Flawed", 5_968L), SackGemstones.amounts(List.of(" Amount: 5,968"), "Flawed"));
        assertEquals(java.util.Map.of("Rough", 12L), SackGemstones.amounts(List.of(" Amount: 12"), null));
        assertTrue(SackGemstones.amounts(List.of("Stored: 5/10", " Mystery: 4"), null).isEmpty());

        var gem = SackGemstones.parseName("Onyx Gemstones");
        assertEquals("Rough", SackGemstones.trackedQuality(gem, null)); // no filter: the Rough count
        assertEquals("Fine", SackGemstones.trackedQuality(gem, "Fine"));
        assertEquals("Flawed", SackGemstones.trackedQuality(SackGemstones.parseName("Flawed Onyx Gemstone"), "Fine")); // the name wins
    }

    @Test
    public void withoutAFilterTheWholeGemIsTheStoredTotalInRoughGems() {
        // Lore of "Onyx Gemstones" as shown in game (tooltip screenshot, no filter).
        var lore = List.of("Gemstones", "", "Rough: 62,504 (62,504)", "Flawed: 10,919 (873,520)", "Fine: 639 (4,089,600)",
            "", "Stored: 5,025,624/8.4M");
        assertEquals(Long.valueOf(5_025_624L), SackTrackerFeature.stored(lore));
        // The listed qualities add up to the same number: a Flawed is 80 Rough gems, a Fine 80 * 80.
        var amounts = SackGemstones.amounts(lore, null);
        assertEquals(java.util.Map.of("Rough", 62_504L, "Flawed", 10_919L, "Fine", 639L), amounts);
        assertEquals(5_025_624L, SackGemstones.roughTotal(amounts));
        assertEquals(1L, SackGemstones.multiplier("Rough"));
        assertEquals(80L, SackGemstones.multiplier("Flawed"));
        assertEquals(6_400L, SackGemstones.multiplier("Fine"));
        // A tracked "Onyx Gemstones" matches the messages' "Flawed Onyx Gemstone" by gem, and "+311 Flawed" is 24,880 Rough.
        var message = SackGemstones.parseName("☠ Flawed Onyx Gemstone");
        assertEquals("Onyx Gemstones", message.gem() + " Gemstones");
        assertEquals(24_880L, 311 * SackGemstones.multiplier(message.quality()));
        var config = new com.github.beng420.kung.config.category.MiscConfig();
        config.toggleSackTrackerItem("☠ Onyx Gemstones", 0xFFFFFF, 5_025_624L);
        assertNotNull(config.sackTrackerItem(message.gem() + " Gemstones"));
    }

    @Test
    public void aTrackedRowIsFoundWithOrWithoutTheSymbolOfItsName() {
        var config = new com.github.beng420.kung.config.category.MiscConfig();
        assertTrue(config.toggleSackTrackerItem("Flawed Onyx Gemstone", 0xFFFFFF, 604));
        // The [Sacks] message names it with the gem's symbol in front.
        assertNotNull(config.sackTrackerItem("☠ Flawed Onyx Gemstone"));
        assertNull(config.sackTrackerItem("☠ Rough Onyx Gemstone"));
        assertFalse(config.toggleSackTrackerItem("☠ Flawed Onyx Gemstone", 0xFFFFFF, 604)); // pressing again untracks
        assertTrue(config.sackTrackerItems().isEmpty());
        assertEquals("Onyx", com.github.beng420.kung.config.category.MiscConfig.sackKey("☠ Onyx"));
        assertEquals("Wheat", com.github.beng420.kung.config.category.MiscConfig.sackKey("Wheat"));
        assertEquals("", com.github.beng420.kung.config.category.MiscConfig.sackKey(null));
    }

    @Test
    public void aFrameWithOneTooltipNamesTheHoveredItem() {
        var tooltip = new HoveredTooltip<String>(String::equals);
        assertNull(tooltip.hovered());
        tooltip.beginFrame();
        tooltip.observe("Wheat");
        tooltip.observe("Wheat");
        assertEquals("Wheat", tooltip.hovered());
    }

    @Test
    public void manyTooltipsInOneFrameNameNothing() {
        var tooltip = new HoveredTooltip<String>(String::equals);
        tooltip.beginFrame();
        tooltip.observe("Wheat");
        tooltip.observe("Carrot");
        tooltip.observe("Wheat");
        assertNull(tooltip.hovered());
        // The next frame starts clean.
        tooltip.beginFrame();
        tooltip.observe("Carrot");
        assertEquals("Carrot", tooltip.hovered());
    }

    @Test
    public void aFrameWithoutTooltipForgetsTheLastHoveredItem() {
        var tooltip = new HoveredTooltip<String>(String::equals);
        tooltip.beginFrame();
        tooltip.observe("Wheat");
        tooltip.beginFrame();
        assertNull(tooltip.hovered());
    }
}
