package com.github.beng420.kung.feature.garden;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeastProgressTest {
    // Fixture boundaries between the supplied first/second/last screenshots are deliberately
    // synthetic: the implementation must use the menu, never assume these intermediate goals.
    private static final List<Integer> GRAND = List.of(5, 25, 75, 150, 250, 325, 475, 625, 750);
    private static final List<Integer> HARVEST = List.of(5, 25, 75, 150, 250);

    @Test
    public void readsRomanMilestonesFromLiveTrace() throws IOException {
        var result = FeastProgress.inspectMenu("Grand Feast", liveGrandMenu());
        assertEquals("synchronized", result.reason());
        var value = result.snapshot();
        assertNotNull(value);
        assertEquals(25, value.donations());
        assertEquals(750, value.goal());
        assertEquals(50, value.toNext());
        assertEquals(List.of(5, 25, 75, 150, 250, 350, 450, 550, 750), value.goals());
    }

    @Test
    public void acceptsRomanHarvestMilestones() throws IOException {
        var value = readMenu("Harvest Feast", liveGrandMenu().subList(0, 5));
        assertNotNull(value);
        assertEquals(25, value.donations());
        assertEquals(250, value.goal());
        assertEquals(50, value.toNext());
    }

    @Test
    public void rejectsDuplicateTierAcrossRomanAndArabicNames() throws IOException {
        var items = new ArrayList<>(liveGrandMenu());
        items.add(item(2, 25, 25));
        var result = FeastProgress.inspectMenu("Grand Feast", items);
        assertNull(result.snapshot());
        assertEquals("unexpected-or-duplicate-tier:2", result.reason());
    }

    @Test
    public void rejectsInvalidRomanTierNamesWithoutThrowing() throws IOException {
        var items = new ArrayList<>(liveGrandMenu());
        var first = items.getFirst();
        for (String numeral : List.of("IIII", "IIX", "VX", "X", "0")) {
            items.set(0, new FeastProgress.MenuItem("Feast Milestone " + numeral, first.lore()));
            assertNull(numeral, readMenu("Grand Feast", items));
        }
    }

    @Test
    public void readsCappedCompletedTiersAndUncappedUpcomingTiersFromScreenshots() {
        var value = readMenu("§dGrand Feast", menu(5, GRAND));
        assertNotNull(value);
        assertEquals(5, value.donations());
        assertEquals(750, value.goal());
        assertEquals(20, value.toNext());
        assertEquals(GRAND, value.goals());
    }

    @Test
    public void followsMenuGoalsAndCalculatesNextTierInsteadOfRemainingToMaximum() {
        var value = readMenu("Grand Feast", menu(234, GRAND));
        assertEquals(16, value.toNext());
        assertEquals(75, readMenu("Harvest Feast", menu(75, HARVEST)).toNext());
    }

    @Test
    public void acceptsBarsTouchingTheNumberAndUnicodeTooltipSpacing() {
        var items = menu(5, GRAND);
        items.set(8, new FeastProgress.MenuItem("§cFeast\u00a0Milestone\u00a09",
            List.of("§a|||||§f||||||||§e5/750\u00a0Donations")));
        var result = readMenu("Grand Feast", items);
        assertNotNull(result);
        assertEquals(5, result.donations());
        assertEquals(20, result.toNext());
    }

    @Test
    public void diagnosticsIdentifyTheTierWithoutDonationLore() {
        var items = menu(5, GRAND);
        items.set(0, new FeastProgress.MenuItem("Feast Milestone 1", List.of("Loading...")));
        var result = FeastProgress.inspectMenu("Grand Feast", items);
        assertNull(result.snapshot());
        assertEquals("incomplete-tiers:8/9 missing-progress=[1]", result.reason());
    }

    @Test
    public void countsTheSeasoningTextRecordedAt173658() {
        var progress = synced(27, GRAND);
        assertTrue(progress.donate("[17:36:58] RARE CROP! Seasoning (+80\uE02B) (automatically donated)"));
        assertEquals(28, progress.snapshot().donations());
        assertEquals(47, progress.snapshot().toNext());
    }

    @Test
    public void countsBothOverbloomFormatsWithOrWithoutDonationPunctuation() {
        for (String boost : List.of("80\uE02B", "148%")) {
            for (String punctuation : List.of("", "!")) {
                String message = "RARE CROP! Seasoning (+" + boost + ") (automatically donated" + punctuation + ")";
                assertEquals(message, 1, FeastProgress.donationAmount(message));
                assertEquals(2, FeastProgress.donationAmount(message.replace("Seasoning", "2x Seasoning")));
                assertEquals(3, FeastProgress.donationAmount(message.replace("Seasoning", "Seasoning x3")));
            }
        }
    }

    @Test
    public void timestampSupportDoesNotCountPlayerQuotesOrIncompleteDrops() {
        for (String message : List.of(
            "[17:36:58] Guild > Friend: RARE CROP! Seasoning (+80\uE02B) (automatically donated)",
            "[17:36:58] RARE CROP! Warty (+148\uE02B)",
            "[17:36:58] RARE CROP! Seasoning (+80\uE02B)",
            "[17:36:58] [NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.",
            "RARE CROP! 2x Seasoning x3 (+80\uE02B) (automatically donated)")) {
            assertEquals(message, 0, FeastProgress.donationAmount(message));
        }
    }

    @Test
    public void countsOnlySeasoningAndDoesNotCountOverbloomOrTedAgain() {
        var progress = synced(5, GRAND);
        assertTrue(progress.donate("§6RARE CROP! §aSeasoning §e(+148%) §7(automatically donated!)"));
        assertEquals(6, progress.snapshot().donations());
        for (String message : List.of(
            "[NPC] Feast Chef Ted: You've hit a new Feast Donation Tier!",
            "[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.",
            "RARE CROP! Cropie (+148%) (automatically donated!)",
            "RARE CROP! Seasoning (+148%)",
            "Party > Friend: RARE CROP! Seasoning (+148%) (automatically donated!)",
            "RARE CROP! 2x Seasoning x3 (+148%) (automatically donated!)")) {
            assertFalse(message, progress.donate(message));
        }
        assertEquals(6, progress.snapshot().donations());
    }

    @Test
    public void explicitDonationQuantitiesAreCountedAndCompletionClamps() {
        var progress = synced(746, GRAND);
        assertTrue(progress.donate("RARE CROP! 2x Seasoning (+148%) (automatically donated!)"));
        assertEquals(748, progress.snapshot().donations());
        assertTrue(progress.donate("RARE CROP! Seasoning x3 (+148%) (automatically donated!)"));
        assertEquals(750, progress.snapshot().donations());
        assertEquals(0, progress.snapshot().toNext());
        assertTrue(progress.snapshot().complete());
    }

    @Test
    public void unsyncedStateNeverPretendsSessionDropsAreTheEventTotal() {
        var progress = new FeastProgress();
        assertFalse(progress.donate("RARE CROP! Seasoning (+148%) (automatically donated!)"));
        assertNull(progress.snapshot());
        progress.synchronize(readMenu("Grand Feast", menu(234, GRAND)));
        progress.reset();
        assertNull(progress.snapshot());
    }

    @Test
    public void ignoresOtherMenusAndPartialOrInconsistentPacketBatches() {
        assertNull(readMenu("Grand Bakery", menu(5, GRAND)));
        assertNull(readMenu("Grand Feast Rewards", menu(5, GRAND)));
        assertNull(readMenu("Harvest Feast", menu(5, GRAND)));
        assertNull(readMenu("Grand Feast", menu(5, GRAND).subList(0, 8)));
        var mixed = menu(5, GRAND);
        mixed.set(8, item(9, 6, 750));
        assertNull(readMenu("Grand Feast", mixed));
        var duplicate = menu(5, GRAND);
        duplicate.add(item(2, 5, 25));
        assertNull(readMenu("Grand Feast", duplicate));
    }

    @Test
    public void rejectsBrokenGoalsAndOverflowWithoutThrowing() {
        for (String line : List.of("-1/750 Donations", "1/0 Donations", "751/750 Donations",
            "1/999999999999999999999 Donations", "99999999999999999999/750 Donations")) {
            var items = menu(5, GRAND);
            items.set(8, new FeastProgress.MenuItem("Feast Milestone 9", List.of(line)));
            assertNull(line, readMenu("Grand Feast", items));
        }
        var items = menu(5, GRAND);
        items.set(8, item(9, 5, 600));
        assertNull(readMenu("Grand Feast", items));
    }

    @Test
    public void acceptsFormattedProgressBarsAndThousandSeparators() {
        var goals = List.of(5, 25, 75, 150, 250, 325, 475, 625, 1_000);
        var items = menu(750, goals);
        items.set(8, new FeastProgress.MenuItem("§cFeast Milestone 9",
            List.of("Progress: 75%", "§a━━━━━━━━§f━━━━ §e750/1,000 Donations")));
        var result = readMenu("Grand Feast", items);
        assertEquals(1_000, result.goal());
        assertEquals(250, result.toNext());
    }

    // Unmodified item names and lore from the 2026-09-14 17:24:32 user trace.
    static List<FeastProgress.MenuItem> liveGrandMenu() throws IOException {
        try (var stream = FeastProgressTest.class.getResourceAsStream("/feast/grand-feast-172432.json")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return List.of(new Gson().fromJson(reader, FeastProgress.MenuItem[].class));
            }
        }
    }

    private static FeastProgress.Snapshot readMenu(String title, List<FeastProgress.MenuItem> items) {
        return FeastProgress.inspectMenu(title, items).snapshot();
    }

    private static FeastProgress synced(int total, List<Integer> goals) {
        var progress = new FeastProgress();
        progress.synchronize(readMenu("Grand Feast", menu(total, goals)));
        return progress;
    }

    static List<FeastProgress.MenuItem> menu(int total, List<Integer> goals) {
        var items = new ArrayList<FeastProgress.MenuItem>();
        for (int index = 0; index < goals.size(); index++) {
            items.add(item(index + 1, Math.min(total, goals.get(index)), goals.get(index)));
        }
        return items;
    }

    private static FeastProgress.MenuItem item(int tier, int current, int goal) {
        return new FeastProgress.MenuItem("§eFeast Milestone " + tier,
            List.of("Farm crops that are in-season to", "earn Seasoning!", "Progress: 0.7%",
                "§a━━━━━━━━§f━━━━ §e" + current + "/" + goal + " Donations", "Rewards:"));
    }
}
