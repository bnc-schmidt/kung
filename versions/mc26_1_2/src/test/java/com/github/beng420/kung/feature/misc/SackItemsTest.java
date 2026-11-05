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
        var lines = SackTrackerFeature.lines(items, false, java.util.Set.of(), "").stream().map(line -> line.getString()).toList();
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
    public void rowsThatMayBeShortAfterAWorldChangeShowATilde() {
        var wheat = new com.github.beng420.kung.config.category.MiscConfig.SackItem("Wheat", 0xFFFFFF, 1_234, 0);
        var cactus = new com.github.beng420.kung.config.category.MiscConfig.SackItem("Cactus", 0xFFFFFF, 50, 100);
        var unread = new com.github.beng420.kung.config.category.MiscConfig.SackItem("Carrot", 0xFFFFFF, -1, 0);
        var items = List.of(wheat, cactus, unread);
        var text = SackTrackerFeature.lines(items, false, java.util.Set.of(wheat, unread), "").stream().map(l -> l.getString()).toList();
        // An unread row stays "?", never "~?".
        assertEquals(List.of("Sack Tracker", "Wheat: ~1,234", "Cactus: 50/100", "Carrot: ?"), text);
        assertEquals(List.of("Sack Tracker", "Wheat: 1,234", "Cactus: 50/100", "Carrot: ?"),
            SackTrackerFeature.lines(items, false, java.util.Set.of(), "").stream().map(l -> l.getString()).toList());
    }

    @Test
    public void aGoalRingsOnceWhenTheCountRisesToIt() {
        assertTrue(SackTrackerFeature.goalReached(900, 1_000, 1_000)); // exactly the goal
        assertTrue(SackTrackerFeature.goalReached(900, 5_000, 1_000)); // a big pick-up jumps over it
        assertFalse(SackTrackerFeature.goalReached(1_000, 1_100, 1_000)); // already there: no second ring
        assertFalse(SackTrackerFeature.goalReached(900, 999, 1_000));
        assertFalse(SackTrackerFeature.goalReached(-1, 5_000, 1_000)); // unread row: opening the sack is no pick-up
        assertFalse(SackTrackerFeature.goalReached(0, 50, 0)); // no goal set
    }

    @Test
    public void anApiAnswerAddsTheLostPickUpsToTheCurrentCount() {
        // The chat held 3,690 at the warp and 3,700 now; the API holds 3,704: 14 were lost, so 3,714.
        assertEquals(3_714, SackTrackerFeature.resolvedAmount(3_700, 3_704, 3_690, false));
        assertEquals(3_714, SackTrackerFeature.resolvedAmount(3_700, 3_704, 3_690, true));
        // The same count settles a row only on the last ask: an earlier one may be an older snapshot.
        assertEquals(-1, SackTrackerFeature.resolvedAmount(3_700, 3_690, 3_690, false));
        assertEquals(3_700, SackTrackerFeature.resolvedAmount(3_700, 3_690, 3_690, true));
        // Less than the count at the warp is an older snapshot, even on the last ask: the count is never lowered.
        assertEquals(-1, SackTrackerFeature.resolvedAmount(3_700, 3_000, 3_690, false));
        assertEquals(-1, SackTrackerFeature.resolvedAmount(3_700, 3_000, 3_690, true));
    }

    @Test
    public void theGoalTitleNamesTheItemInItsColor() {
        var title = SackTrackerFeature.goalTitle("Enchanted Wheat", 0x55FF55);
        assertEquals("Enchanted Wheat done", title.getString());
        assertEquals(0x55FF55, title.getStyle().getColor().getValue());
    }

    @Test
    public void aGoalCanBePastedOnlyFromAClipboardThatHoldsJustANumber() {
        assertEquals(Long.valueOf(1_500), SackTrackerFeature.pastedGoal("1500"));
        assertEquals(Long.valueOf(1_500), SackTrackerFeature.pastedGoal(" 1,500\n"));
        assertEquals(Long.valueOf(1_500_000), SackTrackerFeature.pastedGoal("1.5m"));
        assertEquals(Long.valueOf(10_000), SackTrackerFeature.pastedGoal("10k"));
        assertNull(SackTrackerFeature.pastedGoal("0")); // no goal is Ctrl+Backspace
        assertNull(SackTrackerFeature.pastedGoal("1000000000")); // above the typing limit
        assertNull(SackTrackerFeature.pastedGoal("1.5")); // no half items
        assertNull(SackTrackerFeature.pastedGoal("10 wheat"));
        assertNull(SackTrackerFeature.pastedGoal("-5"));
        assertNull(SackTrackerFeature.pastedGoal(""));
        assertNull(SackTrackerFeature.pastedGoal(null));
    }

    @Test
    public void aCursorThatMovedLessThanAPixelStillRests() {
        assertTrue(SackTrackerFeature.resting(20.4, 30.9, 20, 30)); // render path floors the position
        assertFalse(SackTrackerFeature.resting(20, 30, 22, 30));
        assertFalse(SackTrackerFeature.resting(20, 30, 20, 33));
    }

    @Test
    public void onlyRowsGrindedLatelyCanHaveUnreportedPickUps() {
        long now = 10_000_000L;
        assertFalse(SackTrackerFeature.active(null, now)); // no message yet: a relog or an island hop leaves the row exact
        assertTrue(SackTrackerFeature.active(now - 60_000L, now));
        assertTrue(SackTrackerFeature.active(now - 5 * 60_000L, now)); // the limit still counts
        assertFalse(SackTrackerFeature.active(now - 5 * 60_000L - 1, now)); // quiet for hours: nothing to lose
    }

    @Test
    public void aMessageOnlyCountsWhatTheSackDidNotShowAlready() {
        assertEquals(26, SackTrackerFeature.coveredSeconds("[Sacks] +3,278 items. (Last 26s.)"));
        assertEquals(65, SackTrackerFeature.coveredSeconds("[Sacks] +9 items. (Last 1m 5s.)"));
        assertEquals(0, SackTrackerFeature.coveredSeconds("[Sacks] +3,278 items."));
        long now = 1_000_000L;
        // The message sums the last 26 s. A sack read before that, or none, leaves the whole change to count.
        assertEquals(33_920L, SackTrackerFeature.countedShare(33_920L, now, 26, Long.MIN_VALUE));
        assertEquals(33_920L, SackTrackerFeature.countedShare(33_920L, now, 26, now - 26_000L));
        assertEquals(33_920L, SackTrackerFeature.countedShare(33_920L, now, 0, now - 5_000L)); // no interval known
        // A sack read 6.5 s ago already holds the last quarter of it.
        assertEquals(25_440L, SackTrackerFeature.countedShare(33_920L, now, 26, now - 19_500L));
        assertEquals(8_480L, SackTrackerFeature.countedShare(33_920L, now, 26, now - 6_500L));
        // Read at the very moment of the message, or later: all of it was in the sack.
        assertEquals(0L, SackTrackerFeature.countedShare(33_920L, now, 26, now));
        // Removals scale the same way.
        assertEquals(-5L, SackTrackerFeature.countedShare(-10L, now, 20, now - 10_000L));
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
