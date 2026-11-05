package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class FeastMilestoneClaimsTest {
    private static final String TED = "[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.";
    private static final FeastContext.Event EVENT = new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:513");

    @Test
    public void suppliedTraceIdentifiesTheClaimableFourthTierDespiteTheMissingThirdTier() throws Exception {
        try (var stream = getClass().getResourceAsStream("/feast/grand-feast-225657.json")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var items = List.of(new Gson().fromJson(reader, FeastProgress.MenuItem[].class));
                assertEquals(8, items.size());
                assertNull(FeastProgress.inspectMenu("Grand Feast", items).snapshot());
                assertEquals(new FeastMilestoneClaims.Milestone(1, 25, false), read(items.get(0), false));
                assertEquals(new FeastMilestoneClaims.Milestone(2, 50, false), read(items.get(1), false));
                assertEquals(new FeastMilestoneClaims.Milestone(4, 100, true), read(items.get(2), false));
                for (int index = 3; index < items.size(); index++) assertNull(read(items.get(index), false));
            }
        }
    }

    @Test
    public void readsCompletedRomanAndArabicTiersWithClaimTextOrGlint() {
        var claimed = item("IV", "150/150 Donations", "100", false);
        assertEquals(new FeastMilestoneClaims.Milestone(4, 100, false), read(claimed, false));
        assertEquals(new FeastMilestoneClaims.Milestone(4, 100, true), read(claimed, true));
        var claimable = item("4", "§a150§f/§e150 Donations", "100", true);
        assertEquals(new FeastMilestoneClaims.Milestone(4, 100, true), read(claimable, false));
        var spaced = new FeastProgress.MenuItem("§aFeast Milestone IX", List.of(
            "750/750 Donations", "Rewards:", "- Kernels\u00a0x1,234"));
        assertEquals(new FeastMilestoneClaims.Milestone(9, 1_234, false), read(spaced, false));
    }

    @Test
    public void rejectsUnrelatedIncompleteAndMalformedRewards() {
        var valid = item("IV", "150/150 Donations", "100", true);
        assertNull(FeastMilestoneClaims.read("Harvest Feast", valid, true));
        assertNull(FeastMilestoneClaims.read("Grand Bakery", valid, true));
        assertNull(read(item("V", "150/250 Donations", "125", true), true));
        assertNull(read(item("10", "150/150 Donations", "100", true), true));
        assertNull(read(item("IV", "0/0 Donations", "100", true), true));
        assertNull(read(item("IV", "", "100", true), true));
        for (String amount : List.of("0", "-1", "1,23", "1.2k", "1000001", "99999999999999999")) {
            assertNull(amount, read(item("IV", "150/150 Donations", amount, true), true));
        }
        assertNull(read(new FeastProgress.MenuItem("Feast Milestone IV",
            List.of("150/150 Donations", "Cost:", "- Kernels x100")), true));
        assertNull(read(new FeastProgress.MenuItem("Feast Milestone IV",
            List.of("150/150 Donations", "Rewards:", "- Kernels x100", "- Kernels x100")), true));
    }

    @Test
    public void clickAndTemporaryMissingTierDoNotCreditUntilTheServerRemovesClaimability() {
        var kernels = balance(179);
        assertNull(kernels.observeMilestone(tier(3, 75, false), 0));
        assertTrue(kernels.beginMilestoneClaim(tier(3, 75, true), 100));
        assertEquals(Long.valueOf(179), kernels.balance());
        assertNull(kernels.observeMilestone(null, 200)); // The supplied trace briefly has only eight tiers.
        assertNull(kernels.observeMilestone(tier(3, 75, true), 300));
        assertNull(kernels.observeMilestone(tier(3, 100, false), 400));
        assertNotNull(kernels.observeMilestone(tier(3, 75, false), 500));
        assertEquals(Long.valueOf(254), kernels.balance());
        assertEquals(75, kernels.pendingGains());
        assertTrue(kernels.beginMilestoneClaim(tier(4, 100, true), 600));
        kernels.closeMenu(); // A newly opened Grand Feast container can confirm the same click.
        assertNotNull(kernels.observeMilestone(tier(4, 100, false), 800));
        assertEquals(Long.valueOf(354), kernels.balance());
    }

    @Test
    public void duplicateClicksPacketsAndReopenedMenusCreditEachTierOnce() {
        var kernels = balance(100);
        assertTrue(kernels.beginMilestoneClaim(tier(1, 25, true), 0));
        assertFalse(kernels.beginMilestoneClaim(tier(1, 25, true), 1));
        assertNotNull(kernels.observeMilestone(tier(1, 25, false), 10));
        assertNull(kernels.observeMilestone(tier(1, 25, false), 11));
        kernels.closeMenu();
        assertFalse(kernels.beginMilestoneClaim(tier(1, 25, false), 20));
        assertFalse(kernels.beginMilestoneClaim(tier(1, 25, true), 21));
        assertEquals(Long.valueOf(125), kernels.balance());
    }

    @Test
    public void overlappingClicksShareTheirBaselineWhenSidebarArrivesBeforeConfirmation() {
        for (boolean reverse : List.of(false, true)) {
            var kernels = balance(100);
            kernels.beginMilestoneClaim(tier(3, 75, true), 0);
            kernels.observeSidebar(List.of("Kernels: 175"));
            kernels.beginMilestoneClaim(tier(4, 100, true), 1);
            var first = tier(reverse ? 4 : 3, reverse ? 100 : 75, false);
            var second = tier(reverse ? 3 : 4, reverse ? 75 : 100, false);
            assertNotNull(kernels.observeMilestone(first, 2));
            assertNotNull(kernels.observeMilestone(second, 3));
            assertEquals(Long.valueOf(275), kernels.balance());
            assertEquals(100, kernels.pendingGains());
            assertFalse(kernels.observeSidebar(List.of("Kernels: 275")));
            assertEquals(0, kernels.pendingGains());
        }
    }

    @Test
    public void sidebarCanIncludeAllRewardsBeforeTheConfirmationPackets() {
        var kernels = balance(100);
        kernels.beginMilestoneClaim(tier(1, 25, true), 0);
        kernels.beginMilestoneClaim(tier(2, 50, true), 1);
        kernels.observeSidebar(List.of("Kernels: 175"));
        kernels.observeMilestone(tier(1, 25, false), 2);
        kernels.observeMilestone(tier(2, 50, false), 3);
        assertEquals(Long.valueOf(175), kernels.balance());
        assertEquals(0, kernels.pendingGains());
    }

    @Test
    public void donationsDuringPendingClaimsAndLaggingSidebarPreserveEveryGain() {
        var kernels = balance(100);
        kernels.beginMilestoneClaim(tier(1, 25, true), 0);
        kernels.observeMessage(TED);
        kernels.beginMilestoneClaim(tier(2, 50, true), 1);
        kernels.observeMilestone(tier(1, 25, false), 2);
        kernels.observeMilestone(tier(2, 50, false), 3);
        assertEquals(Long.valueOf(176), kernels.balance());
        assertEquals(76, kernels.pendingGains());
        assertFalse(kernels.observeSidebar(List.of("Kernels: 126")));
        assertEquals(Long.valueOf(176), kernels.balance());
        assertEquals(50, kernels.pendingGains());
        assertFalse(kernels.observeSidebar(List.of("Kernels: 176")));
        assertEquals(0, kernels.pendingGains());
    }

    @Test
    public void unconfirmedExpiredClicksDoNotCreditButCanBeRetried() {
        var kernels = balance(100);
        kernels.beginMilestoneClaim(tier(1, 25, true), 0);
        assertNull(kernels.observeMilestone(tier(1, 25, false), 15_000));
        assertEquals(Long.valueOf(100), kernels.balance());
        assertTrue(kernels.beginMilestoneClaim(tier(1, 25, true), 15_001));
        assertNotNull(kernels.observeMilestone(tier(1, 25, false), 15_002));
        assertEquals(Long.valueOf(125), kernels.balance());
    }

    @Test
    public void freshMenuTotalSettlesClaimsAndSpendingWithoutLaterRecredit() {
        var kernels = balance(100);
        kernels.beginMilestoneClaim(tier(1, 25, true), 0);
        kernels.observeMenu(new Object(), 75L);
        assertNull(kernels.observeMilestone(tier(1, 25, false), 1));
        assertEquals(Long.valueOf(75), kernels.balance());
        assertEquals(0, kernels.pendingGains());
    }

    @Test
    public void profileWorldResetAndNewEventCannotConfirmAnOldClick() {
        for (int change = 0; change < 4; change++) {
            var kernels = balance(100);
            kernels.selectFeast(EVENT);
            kernels.beginMilestoneClaim(tier(1, 25, true), 0);
            switch (change) {
                case 0 -> kernels.invalidate();
                case 1 -> kernels.worldChanged();
                case 2 -> kernels.reset();
                case 3 -> kernels.selectFeast(new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:514"));
            }
            assertNull(kernels.observeMilestone(tier(1, 25, false), 1));
            assertEquals(0, kernels.pendingGains());
        }
        var kernels = balance(100);
        kernels.selectFeast(EVENT);
        kernels.beginMilestoneClaim(tier(1, 25, true), 0);
        kernels.observeMilestone(tier(1, 25, false), 1);
        kernels.selectFeast(null); // Temporary missing mayor/date evidence is not a new Feast.
        kernels.selectFeast(EVENT);
        assertFalse(kernels.beginMilestoneClaim(tier(1, 25, true), 2));
        kernels.selectFeast(new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:514"));
        assertTrue(kernels.beginMilestoneClaim(tier(1, 25, true), 3));
    }

    @Test
    public void missingBaselineStaysUnknownAndLargeBalancesDoNotOverflow() {
        var unknown = new FeastKernels();
        unknown.beginMilestoneClaim(tier(1, 25, true), 0);
        assertNotNull(unknown.observeMilestone(tier(1, 25, false), 1));
        assertNull(unknown.balance());
        assertEquals(0, unknown.pendingGains());
        var maximum = balance(Long.MAX_VALUE - 10);
        maximum.beginMilestoneClaim(tier(1, 25, true), 0);
        maximum.observeMilestone(tier(1, 25, false), 1);
        assertEquals(Long.valueOf(Long.MAX_VALUE), maximum.balance());
        assertEquals(10, maximum.pendingGains());
    }

    private static FeastKernels balance(long amount) {
        var kernels = new FeastKernels();
        kernels.observeMenu(new Object(), amount);
        return kernels;
    }

    private static FeastMilestoneClaims.Milestone tier(int number, int amount, boolean claimable) {
        return new FeastMilestoneClaims.Milestone(number, amount, claimable);
    }

    private static FeastMilestoneClaims.Milestone read(FeastProgress.MenuItem item, boolean glint) {
        return FeastMilestoneClaims.read("§6Grand Feast", item, glint);
    }

    private static FeastProgress.MenuItem item(String tier, String progress, String reward, boolean claimable) {
        return new FeastProgress.MenuItem("Feast Milestone " + tier, List.of(
            "Farm crops that are in-season to", "earn Seasoning! It'll automatically",
            "get donated to the Communal Stew.", "", "Progress: 100%", progress, "", "Rewards:",
            "- Feast Flask x2", "- Kernels x" + reward, "- Carnival Ticket x5", "", claimable ? "Click to claim!" : ""));
    }
}
