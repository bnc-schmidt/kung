package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class SuperpairsBoardTest {
    @Test
    public void changingGlassPromptsNeverBecomeBonuses() {
        SuperpairsBoard board = fullBoard();
        long seededRevision = board.revision();
        for (String prompt : new String[] { "?", "Click any button!", "Click a second button!", "Next button is instantly rewarded!" }) {
            assertEquals(SuperpairsBoard.Kind.MARKER, board.observe(54, 10, "minecraft:cyan_stained_glass", prompt, 1));
        }
        assertEquals(seededRevision, board.revision());
        assertEquals(28, board.boardSlotCount());
        assertEquals(0, board.bonusSlotCount());
        assertEquals(28, board.summary().unknownFields());
    }

    @Test
    public void loggedInstantFindRevealsOnlyOneBonusAndBothRewardSlots() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 11, "pink_dye", "147k Enchanting Exp", 1);
        board.observe(54, 12, "player_head", "Titanic Experience Bottle", 1);
        board.observe(54, 13, "purple_dye", "32k Enchanting Exp", 1);
        board.observe(54, 10, "cyan_stained_glass", "Click a second button!", 1);
        board.observe(54, 10, "cyan_stained_glass", "Next button is instantly rewarded!", 1);
        board.observe(54, 14, "diamond", "Instant Find", 1);
        board.observe(54, 15, "cyan_stained_glass", "Next button is instantly rewarded!", 1);
        board.observe(54, 15, "bone_meal", "133k Enchanting Exp", 1);
        board.observe(54, 28, "bone_meal", "133k Enchanting Exp", 1);
        assertEquals(1, board.bonusSlotCount());
        assertEquals(5, rewardCount(board));
        assertEquals(1, board.summary().knownPairs());
        assertEquals(3, board.summary().singleCards());
        assertEquals(22, board.summary().unknownFields());
        assertEquals(13, board.summary().maximumTotalPairs());
    }

    @Test
    public void hiddenOrEmptySlotsKeepTheirRewardsAndBoardMembership() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 11, "pink_dye", "147k Enchanting Exp", 1);
        long revealedRevision = board.revision();
        board.observe(54, 11, "cyan_stained_glass", "Click any button!", 1);
        board.observe(54, 11, "air", "", 0);
        board.observe(54, 12, "gray_stained_glass_pane", " ", 1);
        assertEquals(28, board.boardSlotCount());
        assertEquals(1, board.cards().size());
        assertEquals("147k Enchanting Exp", board.cards().getFirst().label());
        assertEquals(revealedRevision, board.revision());
    }

    @Test
    public void duplicatePacketsDoNotInventMatchingCardsAndCachedSummariesStayStable() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 11, "pink_dye", "147k Enchanting Exp", 1);
        var cards = board.cards();
        var summary = board.summary();
        long revision = board.revision();
        board.observe(54, 11, "minecraft:pink_dye", "147k Enchanting Exp", 1);
        assertEquals(revision, board.revision());
        assertSame(cards, board.cards());
        assertSame(summary, board.summary());
        assertEquals(0, summary.knownPairs());
        assertEquals(1, summary.singleCards());
    }

    @Test
    public void fourEqualRewardsAreTwoPairsAndFiveLeaveOneSingle() {
        SuperpairsBoard board = fullBoard();
        for (int slot = 10; slot <= 13; slot++) {
            board.observe(54, slot, "pink_dye", "147k Enchanting Exp", 1);
        }
        assertEquals(4, board.cards().getFirst().count());
        assertEquals(2, board.summary().knownPairs());
        assertEquals(0, board.summary().singleCards());
        assertTrue(board.cards().getFirst().complete());
        board.observe(54, 14, "pink_dye", "147k Enchanting Exp", 1);
        assertEquals(5, board.cards().getFirst().count());
        assertEquals(2, board.summary().knownPairs());
        assertEquals(1, board.summary().singleCards());
        assertFalse(board.cards().getFirst().complete());
    }

    @Test
    public void differentRewardAmountsOrEnchantmentsStaySeparate() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 10, "enchanted_book", "§9Growth VI", 1);
        board.observe(54, 11, "enchanted_book", "Growth VII", 1);
        board.observe(54, 12, "experience_bottle", "Experience Bottle", 1);
        board.observe(54, 13, "experience_bottle", "Experience Bottle", 2);
        board.observe(54, 14, "minecraft:enchanted_book", "Growth  VI", 1);
        assertEquals(4, board.cards().size());
        assertEquals(1, board.summary().knownPairs());
        assertEquals(3, board.summary().singleCards());
    }

    @Test
    public void onlySupportedInteriorSlotsCanContributeCards() {
        SuperpairsBoard board = new SuperpairsBoard();
        for (int slot : new int[] { -1, 0, 4, 9, 17, 18, 26, 27, 35, 36, 44, 45, 49, 53, 54, 64 }) {
            assertEquals(SuperpairsBoard.Kind.IGNORED, board.observe(54, slot, "diamond", "Instant Find", 1));
        }
        assertEquals(SuperpairsBoard.Kind.IGNORED, board.observe(27, 10, "pink_dye", "147k Enchanting Exp", 1));
        assertEquals(0, board.boardSlotCount());
        board.observe(54, 10, "gray_stained_glass_pane", " ", 1);
        board.observe(54, 11, "clock", "Timer: 88s", 64);
        assertEquals(0, board.boardSlotCount());
        board.observe(54, 37, "cyan_stained_glass", "?", 1);
        board.observe(54, 43, "cyan_stained_glass", "?", 1);
        assertEquals(2, board.boardSlotCount());
    }

    @Test
    public void concealedBonusesKeepTotalUncertainAndNewBoardResetClearsEverything() {
        SuperpairsBoard board = new SuperpairsBoard();
        board.observe(54, 10, "cyan_stained_glass", "?", 1);
        board.observe(54, 11, "cyan_stained_glass", "?", 1);
        board.observe(54, 12, "cyan_stained_glass", "?", 1);
        board.observe(54, 13, "cyan_stained_glass", "?", 1);
        board.observe(54, 10, "pink_dye", "147k Enchanting Exp", 1);
        board.observe(54, 11, "pink_dye", "147k Enchanting Exp", 1);
        assertEquals(2, board.summary().maximumTotalPairs());
        board.observe(54, 12, "diamond", "Instant Find", 1);
        board.observe(54, 13, "diamond", "Extra Clicks", 1);
        assertEquals(1, board.summary().maximumTotalPairs());
        board.reset();
        assertEquals(0, board.boardSlotCount());
        assertEquals(0, board.bonusSlotCount());
        assertTrue(board.cards().isEmpty());
        assertEquals(0, board.summary().knownPairs());
    }

    @Test
    public void completelyUnseenPairsExcludePartnersOfKnownSinglesAndMayBeBonuses() {
        SuperpairsBoard board = new SuperpairsBoard();
        for (int slot = 10; slot <= 13; slot++) board.observe(54, slot, "cyan_stained_glass", "?", 1);
        board.observe(54, 10, "enchanted_book", "Growth VI", 1);
        assertEquals(3, board.summary().unknownFields());
        assertEquals(1, board.summary().maximumUnseenPairs());
        assertEquals("Unseen pairs: up to 1", SuperpairsHelperFeature.panelLines(board).getFirst());
        board.observe(54, 11, "diamond", "Instant Find", 1);
        assertEquals(2, board.summary().unknownFields());
        assertEquals(0, board.summary().maximumUnseenPairs());
        assertEquals("Unseen pairs: 0", SuperpairsHelperFeature.panelLines(board).getFirst());
        board.reset();
        assertEquals(List.of("Unseen pairs: ?"), SuperpairsHelperFeature.panelLines(board));
    }

    @Test
    public void xpRowsAreHiddenButKeepDistinctPairIdentitiesAndBooksComeFirst() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 10, "bone_meal", "139k Enchanting Exp", 1);
        board.observe(54, 11, "light_blue_dye", "139k Enchanting Exp", 1);
        board.observe(54, 12, "bone_meal", "139k Enchanting Exp", 1);
        board.observe(54, 13, "player_head", "Titanic Experience Bottle", 1);
        board.observe(54, 14, "diamond_sword", "Smite VI", 1);
        board.observe(54, 15, "diamond_sword", "Smite VI", 1);
        board.observe(54, 16, "enchanted_book", "Growth VII", 1);
        assertEquals(2, board.summary().knownPairs());
        assertEquals(3, board.summary().singleCards());
        assertEquals(9, board.summary().maximumUnseenPairs());
        assertEquals(5, board.cards().size());
        assertEquals(List.of("Unseen pairs: up to 9", "Growth VII  1/2", "Smite VI  2/2",
            "Titanic Experience Bottle  1/2"), SuperpairsHelperFeature.panelLines(board));
    }

    @Test
    public void bookNamesComeFromEnchantmentLoreEvenWithEquipmentIcons() {
        for (String icon : List.of("minecraft:diamond_sword", "bow", "diamond_boots", "enchanted_book")) {
            assertEquals("Growth VI", SuperpairsBoard.rewardLabel(icon, "§fEnchanted Book",
                List.of("", "§9Growth VI", "Grants Health.")));
            assertEquals("Growth VII", SuperpairsBoard.rewardLabel(icon, "Enchanted Book",
                List.of("", "§9Growth VII")));
        }
        assertEquals("Enchanted Book", SuperpairsBoard.rewardLabel("diamond_sword", "Enchanted Book", List.of()));
        assertEquals("Titanic Experience Bottle", SuperpairsBoard.rewardLabel("player_head",
            "Titanic Experience Bottle", List.of("Sharpness VI")));
    }

    @Test
    public void suppliedMetaphysicalTraceEndsWithNoUnseenPairsAndOnlyTwoRewardRows() {
        SuperpairsBoard board = fullBoard();
        // All 28 reveal records from the user's 2026-09-17 00:18 UTC trace, in order.
        for (String row : """
            10|magenta_dye|144k Enchanting Exp
            11|player_head|Titanic Experience Bottle
            12|green_dye|42k Enchanting Exp
            13|orange_dye|33k Enchanting Exp
            14|cocoa_beans|40k Enchanting Exp
            15|bone_meal|139k Enchanting Exp
            16|orange_dye|33k Enchanting Exp
            25|magenta_dye|144k Enchanting Exp
            34|lime_dye|148k Enchanting Exp
            43|light_blue_dye|139k Enchanting Exp
            42|red_dye|40k Enchanting Exp
            41|lapis_lazuli|150k Enchanting Exp
            40|diamond|Instant Find
            33|purple_dye|27k Enchanting Exp
            39|purple_dye|27k Enchanting Exp
            38|bone_meal|139k Enchanting Exp
            37|lime_dye|148k Enchanting Exp
            28|red_dye|40k Enchanting Exp
            19|player_head|Titanic Experience Bottle
            20|diamond|Instant Find
            21|yellow_dye|45k Enchanting Exp
            30|yellow_dye|45k Enchanting Exp
            22|green_dye|42k Enchanting Exp
            23|cocoa_beans|40k Enchanting Exp
            24|lapis_lazuli|150k Enchanting Exp
            29|diamond_sword|Enchanted Book
            31|light_blue_dye|139k Enchanting Exp
            32|diamond_sword|Enchanted Book
            """.lines().toList()) {
            String[] fields = row.split("\\|");
            int slot = Integer.parseInt(fields[0]);
            board.observe(54, slot, fields[1], fields[2], 1);
            if (slot == 24) assertEquals(1, board.summary().maximumUnseenPairs());
            if (slot == 29) assertEquals(0, board.summary().maximumUnseenPairs());
        }
        assertEquals(28, board.boardSlotCount());
        assertEquals(2, board.bonusSlotCount());
        assertEquals(13, board.summary().knownPairs());
        assertEquals(0, board.summary().unknownFields());
        assertEquals(List.of("Unseen pairs: 0", "Enchanted Book  2/2", "Titanic Experience Bottle  2/2"),
            SuperpairsHelperFeature.panelLines(board));
    }

    @Test
    public void suppliedClickBonusTraceKeepsOrangePairUnseenUntilItsFirstReveal() {
        SuperpairsBoard board = fullBoard();
        // All reveals from the 2026-09-17 00:34 UTC trace; +3 Clicks has no partner.
        for (String row : """
            10|yellow_dye|25k Enchanting Exp
            11|cyan_dye|150k Enchanting Exp
            12|lime_dye|48k Enchanting Exp
            13|light_blue_dye|147k Enchanting Exp
            14|player_head|Grand Experience Bottle
            15|cocoa_beans|42k Enchanting Exp
            16|purple_dye|134k Enchanting Exp
            25|purple_dye|134k Enchanting Exp
            34|lime_dye|48k Enchanting Exp
            43|lapis_lazuli|41k Enchanting Exp
            42|diamond|Instant Find
            33|yellow_dye|25k Enchanting Exp
            24|lapis_lazuli|41k Enchanting Exp
            23|red_dye|35k Enchanting Exp
            21|red_dye|35k Enchanting Exp
            22|cyan_dye|150k Enchanting Exp
            20|green_dye|48k Enchanting Exp
            19|feather|Gained +3 Clicks
            28|pink_dye|50k Enchanting Exp
            29|cocoa_beans|42k Enchanting Exp
            30|player_head|Grand Experience Bottle
            31|green_dye|48k Enchanting Exp
            32|magenta_dye|130k Enchanting Exp
            41|orange_dye|130k Enchanting Exp
            37|orange_dye|130k Enchanting Exp
            38|pink_dye|50k Enchanting Exp
            39|magenta_dye|130k Enchanting Exp
            40|light_blue_dye|147k Enchanting Exp
            """.lines().toList()) {
            String[] fields = row.split("\\|");
            int slot = Integer.parseInt(fields[0]);
            board.observe(54, slot, fields[1], fields[2], 1);
            if (slot == 32) {
                assertEquals(5, board.summary().unknownFields());
                assertEquals(1, board.summary().maximumUnseenPairs());
                assertEquals(3, board.summary().singleCards());
                assertEquals(List.of("Unseen pairs: up to 1", "Grand Experience Bottle  2/2"),
                    SuperpairsHelperFeature.panelLines(board));
            }
            if (slot == 41) assertEquals(0, board.summary().maximumUnseenPairs());
        }
        assertEquals(2, board.bonusSlotCount());
        assertEquals(26, rewardCount(board));
        assertEquals(13, board.summary().knownPairs());
        assertEquals(0, board.summary().singleCards());
        assertEquals(0, board.summary().unknownFields());
        assertEquals(List.of("Unseen pairs: 0", "Grand Experience Bottle  2/2"),
            SuperpairsHelperFeature.panelLines(board));
    }

    @Test
    public void gainedClicksAreBonusesWithoutTreatingEveryFeatherOrGlassPromptAsOne() {
        for (String name : List.of("Gained +1 Click", "Gained +2 Clicks", "§aGained +3 Clicks!")) {
            assertEquals(SuperpairsBoard.Kind.BONUS, SuperpairsBoard.classify("minecraft:feather", name));
            assertEquals(SuperpairsBoard.Kind.MARKER, SuperpairsBoard.classify("cyan_stained_glass", name));
        }
        assertEquals(SuperpairsBoard.Kind.REWARD, SuperpairsBoard.classify("feather", "Feather"));
        assertEquals(SuperpairsBoard.Kind.IGNORED, SuperpairsBoard.classify("bookshelf", "Remaining Clicks: 3"));
    }

    private static int rewardCount(SuperpairsBoard board) {
        return board.cards().stream().mapToInt(SuperpairsBoard.CardCount::count).sum();
    }

    private static SuperpairsBoard fullBoard() {
        SuperpairsBoard board = new SuperpairsBoard();
        for (int slot = 0; slot < 54; slot++) {
            if (SuperpairsBoard.isBoardSlot(54, slot)) {
                board.observe(54, slot, "cyan_stained_glass", "?", 1);
            }
        }
        return board;
    }

    @Test
    public void panelOnTheRightHalfKeepsItsTextAndOnlyMovesLeftWhenItWouldLeaveTheScreen() {
        // Reported layout: x=495 at 75% on a 960-wide GUI. Lines were cut to 80 px there.
        int width = SuperpairsHelperFeature.maxTextWidth(960, 0.75F);
        assertTrue(width >= 150);
        assertEquals(495, SuperpairsHelperFeature.panelX(495, 960, 150, 0.75F));
        // Too far right for its text: it moves left until the border meets the screen edge.
        int x = SuperpairsHelperFeature.panelX(900, 960, 150, 0.75F);
        assertEquals(960, x + Math.round((150 + 6) * 0.75F));
        assertEquals(Math.round(6 * 0.75F), SuperpairsHelperFeature.panelX(-50, 960, 150, 0.75F));
    }
}
