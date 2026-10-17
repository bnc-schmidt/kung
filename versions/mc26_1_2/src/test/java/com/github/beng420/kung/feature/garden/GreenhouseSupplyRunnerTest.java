package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSupplyRunner.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class GreenhouseSupplyRunnerTest {
    private static final int INGREDIENT = GreenhouseGrid.INGREDIENT;

    /** 36 inventory slots (0-8 hotbar, 9-35 main) as an id and a count each. */
    private static final class FakeHost implements GreenhouseSupplyRunner.Host {
        long now = 10_000;
        boolean hypixel = true;
        boolean swapWorks = true;
        final Map<String, Integer> required = new HashMap<>();
        final Map<GreenhouseSoils.Soil, Integer> soilNeeded = new HashMap<>();
        final String[] ids = new String[36];
        final int[] counts = new int[36];
        int selected;
        final List<Integer> selects = new ArrayList<>();
        final List<Integer> swaps = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<String> chats = new ArrayList<>();

        void put(int slot, String id, int count) {
            ids[slot] = id;
            counts[slot] = count;
        }

        @Override public long now() { return now; }

        @Override public boolean onHypixel() { return hypixel; }

        @Override public int required(String crop) { return required.getOrDefault(crop, 0); }

        @Override public int soilNeeded(GreenhouseSoils.Soil soil) { return soilNeeded.getOrDefault(soil, 0); }

        @Override public Map<String, Integer> inventory() {
            Map<String, Integer> total = new HashMap<>();
            for (int slot = 0; slot < 36; slot++) {
                if (ids[slot] != null) total.merge(ids[slot], counts[slot], Integer::sum);
            }
            return total;
        }

        @Override public int hotbarSlot(String id) { return find(id, 0, 9); }

        @Override public int mainSlot(String id) { return find(id, 9, 36); }

        private int find(String id, int from, int to) {
            for (int slot = from; slot < to; slot++) if (id.equals(ids[slot])) return slot;
            return -1;
        }

        @Override public void select(int hotbarSlot) {
            selected = hotbarSlot;
            selects.add(hotbarSlot);
        }

        @Override public boolean swapIntoSelected(int mainSlot) {
            swaps.add(mainSlot);
            if (!swapWorks) return false;
            String id = ids[selected];
            int count = counts[selected];
            put(selected, ids[mainSlot], counts[mainSlot]);
            put(mainSlot, id, count);
            return true;
        }

        @Override public void sendCommand(String command) { commands.add(command); }

        @Override public void chat(Level level, String message) { chats.add(level + ": " + message); }
    }

    private FakeHost host;
    private GreenhouseSupplyRunner runner;

    @Before
    public void setUp() {
        host = new FakeHost();
        runner = new GreenhouseSupplyRunner(host);
    }

    @After
    public void restorePlantedSource() {
        GreenhouseSupply.setPlantedSource(null);
        GreenhouseHologram.hide();
        GreenhouseScanner.reset();
    }

    /** A row of ingredient cells of one crop. */
    private static GreenhouseGrid row(int row, String crop, int count) {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int column = 0; column < count; column++) grid.set(row, column, new GreenhouseGrid.Cell(crop, INGREDIENT));
        return grid;
    }

    /**
     * Shows the grid as the hologram and installs a scan of it: placement i (the cells in reading order) is in
     * states[i]; stands are the recognised plants anywhere in the grid area, wherever they stand.
     */
    private static void scan(GreenhouseGrid grid, Map<String, Integer> stands, GreenhouseScanLogic.State... states) {
        GreenhouseHologram.show(grid, true);
        assertEquals(states.length, GreenhouseHologram.placements().size());
        GreenhouseScanner.setSnapshotForTest(new GreenhouseScanLogic.Snapshot(GreenhouseHologram.version(), List.of(),
            List.of(states), List.of(), stands, 0, 0, false));
    }

    private static GreenhouseScanLogic.State[] states(int correct, int others, GreenhouseScanLogic.State other) {
        GreenhouseScanLogic.State[] out = new GreenhouseScanLogic.State[correct + others];
        for (int i = 0; i < out.length; i++) out[i] = i < correct ? GreenhouseScanLogic.State.CORRECT : other;
        return out;
    }

    @Test
    public void enoughInTheHotbarSelectsItWithoutACommandOrChat() {
        host.required.put("Carrot", 5);
        host.put(3, "CARROT_ITEM", 10);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of(3), host.selects);
        assertTrue(host.commands.isEmpty());
        assertTrue(host.chats.isEmpty());
        assertFalse(runner.pendingPull());
    }

    @Test
    public void enoughOnlyInTheMainInventorySwapsIntoTheSelectedSlot() {
        host.required.put("Carrot", 5);
        host.put(0, "SOMETHING_ELSE", 1);
        host.put(20, "CARROT_ITEM", 10);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of(20), host.swaps);
        assertEquals("CARROT_ITEM", host.ids[host.selected]);
        assertEquals("SOMETHING_ELSE", host.ids[20]);
        assertTrue(host.commands.isEmpty());
    }

    @Test
    public void withoutSwapSupportThePlayerIsToldToMoveTheItem() {
        host.swapWorks = false;
        host.required.put("Carrot", 5);
        host.put(20, "CARROT_ITEM", 10);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("WARNING: Move Carrot into your hotbar to hold it"), host.chats);
        assertEquals("CARROT_ITEM", host.ids[20]);
    }

    @Test
    public void nothingToHoldAndNothingMissingSaysSo() {
        host.required.put("Carrot", 4);
        GreenhouseSupply.setPlantedSource(crop -> 4);

        runner.onPick("Carrot", INGREDIENT);

        assertTrue(host.selects.isEmpty());
        assertEquals(List.of("INFO: All 4 Carrot are already placed correctly."), host.chats);
    }

    @Test
    public void tenNeededTwoPlantedThreeCarriedPullsFiveThenSwitchesWhenTheyArrive() {
        host.required.put("Carrot", 10);
        GreenhouseSupply.setPlantedSource(crop -> 2);
        host.put(4, "CARROT_ITEM", 3);
        host.put(5, "OTHER", 1);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 5"), host.commands);
        assertTrue(runner.pendingPull());
        assertEquals(List.of("INFO: Pulling 5 Carrot from Sacks (10 needed, 2 placed correctly, 3 in inventory)"), host.chats);
        assertTrue(host.selects.isEmpty());

        runner.onChat("§aMoved §e5 §aCarrot from your Sacks to your inventory.");
        host.now += 50;
        runner.tick();
        assertTrue("items not in the inventory yet", runner.pendingPull());
        assertTrue(host.selects.isEmpty());

        host.put(4, "CARROT_ITEM", 8);
        host.now += 50;
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(4), host.selects);
        assertEquals("INFO: Pulled 5 Carrot", host.chats.get(1));
        assertEquals(1, host.commands.size());
    }

    @Test
    public void aShortMoveSwitchesOnceThatManyArrived() {
        host.required.put("Carrot", 10);
        runner.onPick("Carrot", INGREDIENT);
        runner.onChat("Moved 3 Carrot from your Sacks to your inventory.");

        host.put(1, "CARROT_ITEM", 2);
        runner.tick();
        assertTrue(runner.pendingPull());

        host.put(1, "CARROT_ITEM", 3);
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(1), host.selects);
        assertEquals("WARNING: Only 3 of 10 Carrot were in your sacks.", host.chats.get(1));
    }

    @Test
    public void withoutAMovedLineOnlyTheFullAmountSwitches() {
        host.required.put("Carrot", 4);
        runner.onPick("Carrot", INGREDIENT);

        host.put(2, "CARROT_ITEM", 3);
        runner.tick();
        assertTrue(runner.pendingPull());

        host.put(2, "CARROT_ITEM", 4);
        runner.tick();
        assertEquals(List.of(2), host.selects);
    }

    @Test
    public void itemsThatLandInTheMainInventoryAreSwappedIntoTheSelectedSlot() {
        host.required.put("Carrot", 2);
        for (int slot = 0; slot < 9; slot++) host.put(slot, "FULL_HOTBAR_" + slot, 1);
        host.selected = 6;

        runner.onPick("Carrot", INGREDIENT);
        host.put(9, "CARROT_ITEM", 2);
        runner.tick();

        assertEquals(List.of(9), host.swaps);
        assertEquals("CARROT_ITEM", host.ids[6]);
        assertEquals("FULL_HOTBAR_6", host.ids[9]);
        assertFalse(runner.pendingPull());
    }

    @Test
    public void pumpkinsAreCountedInStemsAndSwitchedWhenTheyArrive() {
        host.required.put("Pumpkin Seeds", 10);
        host.put(0, "PUMPKIN", 1);

        runner.onPick("Pumpkin Seeds", INGREDIENT);
        assertEquals(List.of("gfs PUMPKIN 2"), host.commands);

        host.put(0, "PUMPKIN", 2);
        runner.tick();
        assertTrue(runner.pendingPull());

        host.put(0, "PUMPKIN", 3);
        runner.tick();
        assertEquals(List.of(0), host.selects);
    }

    @Test
    public void aPullThatNeverArrivesExpiresWithoutSwitching() {
        host.required.put("Carrot", 3);
        runner.onPick("Carrot", INGREDIENT);

        host.now += GreenhouseSupplyRunner.PENDING_MILLIS - 1;
        runner.tick();
        assertTrue(runner.pendingPull());

        host.now += 1;
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals("WARNING: No Carrot arrived from Sacks", host.chats.get(1));
        assertTrue(host.selects.isEmpty());
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();
        assertTrue(host.selects.isEmpty());
        assertEquals(1, host.commands.size());
    }

    @Test
    public void theNoItemAnswerCancelsThePull() {
        host.required.put("Carrot", 3);
        runner.onPick("Carrot", INGREDIENT);

        runner.onChat("§cYou have no §aCarrot§c in your Sacks!");

        assertFalse(runner.pendingPull());
        assertEquals("ERROR: Your Sacks have no Carrot", host.chats.get(1));
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();
        assertTrue(host.selects.isEmpty());
    }

    @Test
    public void chatWhileNothingIsPendingIsIgnored() {
        runner.onChat("Moved 5 Carrot from your Sacks to your inventory.");
        runner.onChat("You have no Carrot in your Sacks!");
        runner.tick();

        assertTrue(host.chats.isEmpty());
        assertTrue(host.commands.isEmpty());
    }

    @Test
    public void aSecondPullInsideTheCooldownIsIgnoredWithANote() {
        host.required.put("Carrot", 3);
        host.required.put("Potato", 3);
        runner.onPick("Carrot", INGREDIENT);
        host.now += GreenhouseSupplyRunner.COOLDOWN_MILLIS - 1;

        runner.onPick("Potato", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 3"), host.commands);
        assertTrue(runner.pendingPull());
        assertEquals("WARNING: Sack pull on cooldown, press again in a moment", host.chats.get(1));

        // The ignored press changed nothing: the Carrot pull still completes.
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();
        assertEquals(List.of(0), host.selects);
    }

    @Test
    public void afterTheCooldownAPickOfAnotherCropCancelsTheOldPullAndSendsItsOwn() {
        host.required.put("Carrot", 3);
        host.required.put("Potato", 3);
        runner.onPick("Carrot", INGREDIENT);
        host.now += GreenhouseSupplyRunner.COOLDOWN_MILLIS;

        runner.onPick("Potato", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 3", "gfs POTATO_ITEM 3"), host.commands);
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();
        assertTrue("the cancelled crop is not switched to", host.selects.isEmpty());
        host.put(1, "POTATO_ITEM", 3);
        runner.tick();
        assertEquals(List.of(1), host.selects);
    }

    @Test
    public void pickingTheSameCropWhileWaitingSendsNothingMore() {
        host.required.put("Carrot", 3);
        runner.onPick("Carrot", INGREDIENT);
        host.now += 5_000;

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(1, host.commands.size());
        assertEquals("INFO: Still waiting for Carrot from Sacks", host.chats.get(1));
    }

    @Test
    public void aSwitchOnlyPickOfAnotherCropCancelsThePendingPull() {
        host.required.put("Carrot", 3);
        host.required.put("Potato", 2);
        host.put(7, "POTATO_ITEM", 9);
        runner.onPick("Carrot", INGREDIENT);

        runner.onPick("Potato", INGREDIENT);

        assertFalse(runner.pendingPull());
        assertEquals(List.of(7), host.selects);
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();
        assertEquals(List.of(7), host.selects);
    }

    @Test
    public void disconnectForgetsThePullAndTheCooldown() {
        host.required.put("Carrot", 3);
        runner.onPick("Carrot", INGREDIENT);

        runner.reset();

        assertFalse(runner.pendingPull());
        host.required.put("Potato", 1);
        runner.onPick("Potato", INGREDIENT);
        assertEquals(List.of("gfs CARROT_ITEM 3", "gfs POTATO_ITEM 1"), host.commands);
    }

    @Test
    public void aMutationSpotDoesNothingAtAll() {
        host.required.put("Carrot", 3);
        host.put(0, "ASHWREATH", 5);
        runner.onPick("Carrot", INGREDIENT);

        runner.onPick("Ashwreath", GreenhouseGrid.TARGET);

        assertTrue(host.selects.isEmpty());
        assertEquals(1, host.commands.size());
        assertTrue("the earlier pull stays pending", runner.pendingPull());
        assertEquals("INFO: This is a mutation spot, nothing to plant", host.chats.get(1));
    }

    @Test
    public void aCropNoSackHoldsSendsNoCommandAndSwitchesToWhatIsCarried() {
        host.required.put("Jerryflower", 3);
        host.put(5, "JERRYFLOWER", 1);

        runner.onPick("Jerryflower", INGREDIENT);

        assertTrue(host.commands.isEmpty());
        assertEquals(List.of(5), host.selects);
        assertEquals(List.of("WARNING: Fetch Jerryflower manually (not in any Sack): 2 more needed"), host.chats);

        host.chats.clear();
        host.required.put("Fire", 1);
        runner.onPick("Fire", INGREDIENT);
        assertTrue(host.commands.isEmpty());
        assertEquals(List.of(5), host.selects);
        assertEquals(List.of("WARNING: Fetch Fire manually (a block, no item): 1 more needed"), host.chats);
    }

    @Test
    public void anUnknownCropIsOnlyReported() {
        runner.onPick("Banana", INGREDIENT);

        assertEquals(List.of("WARNING: Unknown crop Banana"), host.chats);
        assertTrue(host.commands.isEmpty());
    }

    @Test
    public void outsideHypixelNoCommandIsSent() {
        host.hypixel = false;
        host.required.put("Carrot", 3);

        runner.onPick("Carrot", INGREDIENT);

        assertTrue(host.commands.isEmpty());
        assertFalse(runner.pendingPull());
        assertEquals(List.of("WARNING: Sacks are only available on Hypixel SkyBlock"), host.chats);
    }

    @Test
    public void anotherItemsSackAnswersAreIgnoredWhileOursIsPending() {
        host.required.put("Wheat Seeds", 5);
        runner.onPick("Wheat Seeds", INGREDIENT);
        assertEquals(List.of("gfs SEEDS 5"), host.commands);

        // A manual /gfs of Wheat, or another mod's, neither shortens nor cancels our pull.
        runner.onChat("Moved 3 Wheat from your Sacks to your inventory.");
        runner.onChat("You have no Wheat in your Sacks!");
        host.put(2, "SEEDS", 3);
        runner.tick();
        assertTrue("still waiting for all 5", runner.pendingPull());
        assertTrue(host.selects.isEmpty());

        host.put(2, "SEEDS", 5);
        runner.tick();
        assertFalse(runner.pendingPull());
        assertEquals(List.of(2), host.selects);
        assertEquals(2, host.chats.size());
    }

    @Test
    public void ourItemsAnswersAreRecognisedUnderTheGamesNames() {
        host.required.put("Wheat Seeds", 5);
        runner.onPick("Wheat Seeds", INGREDIENT);
        runner.onChat("Moved 3 Seeds from your Sacks to your inventory.");
        host.put(2, "SEEDS", 3);
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(2), host.selects);
        assertEquals("WARNING: Only 3 of 5 Wheat Seeds were in your sacks.", host.chats.get(1));
    }

    @Test
    public void aNoItemAnswerForOurItemStillCancelsUnderTheGamesName() {
        host.required.put("Do-not-eat-shroom", 2);
        runner.onPick("Do-not-eat-shroom", INGREDIENT);

        runner.onChat("You have no Do-Not-Eat-Shroom in your Sacks!");

        assertFalse(runner.pendingPull());
        assertEquals("ERROR: Your Sacks have no Do-not-eat-shroom", host.chats.get(1));
    }

    @Test
    public void aShortDeliveryWithAnUnrecognisedAnswerSwitchesWhenTheWaitEnds() {
        host.required.put("Carrot", 5);
        runner.onPick("Carrot", INGREDIENT);
        runner.onChat("Moved 2 Mystery Carrot from your Sacks to your inventory.");
        host.put(1, "CARROT_ITEM", 2);

        host.now += GreenhouseSupplyRunner.PENDING_MILLIS - 1;
        runner.tick();
        assertTrue(runner.pendingPull());

        host.now += 1;
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(1), host.selects);
        assertEquals("WARNING: Only 2 of 5 Carrot arrived from your sacks.", host.chats.get(1));
    }

    @Test
    public void tenNeededTwoPlacedCorrectlyThreeCarriedPullsFiveThroughTheScan() {
        GreenhouseSupply.useScanner();
        scan(row(0, "Carrot", 10), Map.of("Carrot", 2), states(2, 8, GreenhouseScanLogic.State.EMPTY));
        host.required.put("Carrot", 10);
        host.put(4, "CARROT_ITEM", 3);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 5"), host.commands);
        assertEquals("INFO: Pulling 5 Carrot from Sacks (10 needed, 2 placed correctly, 3 in inventory)", host.chats.get(0));
    }

    @Test
    public void plantsStandingOnTheWrongCellsDoNotCountAsPlaced() {
        GreenhouseSupply.useScanner();
        // 24 Witherbloom stand somewhere in the grid area, none on the 3 cells the layout wants them on.
        scan(row(0, "Witherbloom", 3), Map.of("Witherbloom", 24), states(0, 3, GreenhouseScanLogic.State.EMPTY));
        host.required.put("Witherbloom", 3);

        runner.onPick("Witherbloom", INGREDIENT);

        assertEquals(List.of("gfs WITHERBLOOM 3"), host.commands);
        assertEquals("INFO: Pulling 3 Witherbloom from Sacks (3 needed, 0 placed correctly, 0 in inventory)",
            host.chats.get(0));
    }

    @Test
    public void aCellWithAWrongOrUnknownPlantStillNeedsTheCrop() {
        GreenhouseSupply.useScanner();
        scan(row(0, "Carrot", 3), Map.of(), GreenhouseScanLogic.State.CORRECT, GreenhouseScanLogic.State.WRONG,
            GreenhouseScanLogic.State.UNVERIFIED);
        host.required.put("Carrot", 3);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 2"), host.commands);
    }

    @Test
    public void beforeAnyScanNothingCountsAsPlaced() {
        GreenhouseSupply.useScanner();
        GreenhouseHologram.show(row(0, "Carrot", 10), true);
        GreenhouseScanner.setSnapshotForTest(null);
        host.required.put("Carrot", 10);
        host.put(4, "CARROT_ITEM", 3);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 7"), host.commands);
    }

    @Test
    public void aScanOfAnotherLayoutCountsNothing() {
        GreenhouseSupply.useScanner();
        GreenhouseHologram.show(row(0, "Carrot", 4), true);
        GreenhouseScanner.setSnapshotForTest(new GreenhouseScanLogic.Snapshot(GreenhouseHologram.version() - 1, List.of(),
            List.of(states(4, 0, GreenhouseScanLogic.State.EMPTY)), List.of(), Map.of(), 0, 0, false));
        host.required.put("Carrot", 4);

        runner.onPick("Carrot", INGREDIENT);

        assertEquals(List.of("gfs CARROT_ITEM 4"), host.commands);
    }

    @Test
    public void placedStemsAreSubtractedInPlantsBeforeTheyBecomeItems() {
        GreenhouseSupply.useScanner();
        // 10 stems, 2 placed, 1 Pumpkin carried (4 stems): 4 missing is one Pumpkin, not ceil(8 / 4) = 2.
        scan(row(0, "Pumpkin Seeds", 10), Map.of(), states(2, 8, GreenhouseScanLogic.State.EMPTY));
        host.required.put("Pumpkin Seeds", 10);
        host.put(0, "PUMPKIN", 1);
        runner.onPick("Pumpkin Seeds", INGREDIENT);
        assertEquals(List.of("gfs PUMPKIN 1"), host.commands);

        // 5 stems, 2 placed, 1 Melon Slice carried (1 stem): 2 missing.
        host.now += GreenhouseSupplyRunner.COOLDOWN_MILLIS;
        scan(row(0, "Melon Seeds", 5), Map.of(), states(2, 3, GreenhouseScanLogic.State.EMPTY));
        host.required.put("Melon Seeds", 5);
        host.put(1, "MELON", 1);
        runner.onPick("Melon Seeds", INGREDIENT);
        assertEquals(List.of("gfs PUMPKIN 1", "gfs MELON 2"), host.commands);
    }

    @Test
    public void thePickedCellsSpellingFindsTheCropsCells() {
        GreenhouseSupply.useScanner();
        scan(row(0, "Melon Seeds", 4), Map.of(), states(3, 1, GreenhouseScanLogic.State.EMPTY));
        host.required.put("melon seeds", 4);

        runner.onPick("melon seeds", INGREDIENT);

        assertEquals(List.of("gfs MELON 1"), host.commands);
    }

    @Test
    public void mushroomCellsHoldingTheOtherMushroomCountAsPlacedForBoth() {
        GreenhouseSupply.useScanner();
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Red Mushroom", INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Red Mushroom", INGREDIENT));
        grid.set(0, 2, new GreenhouseGrid.Cell("Brown Mushroom", INGREDIENT));
        grid.set(0, 3, new GreenhouseGrid.Cell("Brown Mushroom", INGREDIENT));
        // One Red cell and one Brown cell are right (the scan accepts either mushroom on both).
        scan(grid, Map.of(), GreenhouseScanLogic.State.CORRECT, GreenhouseScanLogic.State.EMPTY,
            GreenhouseScanLogic.State.CORRECT, GreenhouseScanLogic.State.EMPTY);
        host.required.put("Red Mushroom", 4);

        runner.onPick("Red Mushroom", INGREDIENT);

        assertEquals(List.of("gfs RED_MUSHROOM 2"), host.commands);
    }

    @Test
    public void aSoilGhostPullsTheMissingBlocksAndSwitchesWhenTheyArrive() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SAND, 5);
        host.put(8, "SAND", 1);

        runner.onPickSoil(GreenhouseSoils.Soil.SAND);

        assertEquals(List.of("gfs SAND 4"), host.commands);
        assertEquals(List.of("INFO: Pulling 4 Sand from Sacks (5 needed, 1 in inventory)"), host.chats);

        runner.onChat("Moved 4 Sand from your Sacks to your inventory.");
        host.put(8, "SAND", 5);
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(8), host.selects);
        assertEquals("INFO: Pulled 4 Sand", host.chats.get(1));
    }

    @Test
    public void everySackableSoilUsesItsOwnSackId() {
        host.soilNeeded.put(GreenhouseSoils.Soil.MYCELIUM, 3);
        host.soilNeeded.put(GreenhouseSoils.Soil.END_STONE, 2);

        runner.onPickSoil(GreenhouseSoils.Soil.MYCELIUM);
        host.now += GreenhouseSupplyRunner.COOLDOWN_MILLIS;
        runner.onPickSoil(GreenhouseSoils.Soil.END_STONE);

        assertEquals(List.of("gfs MYCEL 3", "gfs ENDER_STONE 2"), host.commands);
    }

    @Test
    public void aShortSoilDeliveryIsSaidPlainly() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SAND, 5);
        runner.onPickSoil(GreenhouseSoils.Soil.SAND);
        runner.onChat("Moved 2 Sand from your Sacks to your inventory.");
        host.put(3, "SAND", 2);

        runner.tick();

        assertEquals(List.of(3), host.selects);
        assertEquals("WARNING: Only 2 of 5 Sand were in your sacks.", host.chats.get(1));
    }

    @Test
    public void aSoilNoSackHoldsIsOnlyAHintAndSwitchesToWhatIsCarried() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SOUL_SAND, 5);
        host.soilNeeded.put(GreenhouseSoils.Soil.DIRT, 2);

        runner.onPickSoil(GreenhouseSoils.Soil.SOUL_SAND);
        host.put(6, "SOUL_SAND", 3);
        runner.onPickSoil(GreenhouseSoils.Soil.SOUL_SAND);
        runner.onPickSoil(GreenhouseSoils.Soil.DIRT);

        assertTrue(host.commands.isEmpty());
        assertEquals(List.of(6), host.selects);
        assertEquals(List.of("WARNING: Fetch Soul Sand manually (not in any Sack): 5 more needed",
            "WARNING: Fetch Soul Sand manually (not in any Sack): 2 more needed",
            "WARNING: Fetch Dirt manually (not in any Sack): 2 more needed"), host.chats);
    }

    @Test
    public void aHintForAnUnsackableItemKeepsTheRunningPullsAutoSwitch() {
        host.required.put("Carrot", 3);
        host.soilNeeded.put(GreenhouseSoils.Soil.SOUL_SAND, 5);
        runner.onPick("Carrot", INGREDIENT);

        host.now += 900;
        runner.onPickSoil(GreenhouseSoils.Soil.SOUL_SAND);

        assertTrue(runner.pendingPull());
        assertEquals("WARNING: Fetch Soul Sand manually (not in any Sack): 5 more needed", host.chats.get(1));
        runner.onChat("Moved 3 Carrot from your Sacks to your inventory.");
        host.put(0, "CARROT_ITEM", 3);
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(0), host.selects);
        assertEquals("INFO: Pulled 3 Carrot", host.chats.get(2));
        // The cooldown still holds, and the pull is not repeated.
        assertEquals(List.of("gfs CARROT_ITEM 3"), host.commands);
    }

    @Test
    public void holdingTheUnsackableItemAlreadyReplacesTheRunningPullsAutoSwitch() {
        host.required.put("Carrot", 3);
        host.soilNeeded.put(GreenhouseSoils.Soil.DIRT, 2);
        host.put(4, "DIRT", 1);
        runner.onPick("Carrot", INGREDIENT);

        runner.onPickSoil(GreenhouseSoils.Soil.DIRT);

        assertEquals(List.of(4), host.selects);
        assertFalse(runner.pendingPull());
    }

    @Test
    public void aSwitchPlanWithNothingToHoldLeavesARunningPullAlone() {
        host.required.put("Carrot", 3);
        host.required.put("Potato", 2);
        runner.onPick("Carrot", INGREDIENT);
        GreenhouseSupply.setPlantedSource(crop -> 2);

        runner.onPick("Potato", INGREDIENT);

        assertTrue(runner.pendingPull());
        assertEquals("INFO: All 2 Potato are already placed correctly.", host.chats.get(1));
    }

    @Test
    public void aFarmlandSpotFetchesDirtAndHintsWhenNoSackHoldsIt() {
        host.required.put("Carrot", 3);
        host.soilNeeded.put(GreenhouseSoils.Soil.FARMLAND, 4);
        runner.onPick("Carrot", INGREDIENT);

        runner.onPickSoil(GreenhouseSoils.Soil.FARMLAND);

        assertEquals(List.of("gfs CARROT_ITEM 3"), host.commands);
        assertTrue("a hint leaves the running pull alone", runner.pendingPull());
        assertTrue(host.selects.isEmpty());
        assertEquals("WARNING: Fetch Dirt manually (not in any Sack): 4 more needed", host.chats.get(1));
    }

    @Test
    public void aFarmlandSpotSwitchesToCarriedDirt() {
        host.soilNeeded.put(GreenhouseSoils.Soil.FARMLAND, 4);
        host.put(5, "DIRT", 2);

        runner.onPickSoil(GreenhouseSoils.Soil.FARMLAND);

        assertTrue(host.commands.isEmpty());
        assertEquals(List.of(5), host.selects);
        assertEquals("WARNING: Fetch Dirt manually (not in any Sack): 2 more needed", host.chats.get(0));
    }

    @Test
    public void deadPlantsAreNowPulledLikeAnyCrop() {
        host.required.put("Dead Plants", 3);
        runner.onPick("Dead Plants", INGREDIENT);
        assertEquals(List.of("gfs DEAD_PLANT 3"), host.commands);

        runner.onChat("Moved 3 Dead Plant from your Sacks to your inventory.");
        host.put(7, "DEAD_PLANT", 3);
        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(7), host.selects);
        assertEquals("INFO: Pulled 3 Dead Plants", host.chats.get(1));
    }

    @Test
    public void enoughSoilInTheInventoryOnlySwitches() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SAND, 5);
        host.put(20, "SAND", 9);

        runner.onPickSoil(GreenhouseSoils.Soil.SAND);

        assertTrue(host.commands.isEmpty());
        assertEquals(List.of(20), host.swaps);
        assertEquals("SAND", host.ids[host.selected]);
    }

    @Test
    public void soilAnswersOfOtherItemsAreIgnoredWhileOursIsPending() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SAND, 5);
        runner.onPickSoil(GreenhouseSoils.Soil.SAND);

        runner.onChat("Moved 3 Red Sand from your Sacks to your inventory.");
        runner.onChat("You have no Mycelium in your Sacks!");
        host.put(1, "SAND", 3);
        runner.tick();
        assertTrue(runner.pendingPull());

        runner.onChat("You have no Sand in your Sacks!");
        assertFalse(runner.pendingPull());
        assertEquals("ERROR: Your Sacks have no Sand", host.chats.get(1));
    }

    @Test
    public void aSoilPullThenACropInsideTheCooldownSendsExactlyOneCommand() {
        host.soilNeeded.put(GreenhouseSoils.Soil.SAND, 5);
        host.required.put("Carrot", 3);

        runner.onPickSoil(GreenhouseSoils.Soil.SAND);
        host.now += 17;
        runner.onPick("Carrot", INGREDIENT);
        host.now += 17;
        runner.onPickSoil(GreenhouseSoils.Soil.SAND);

        assertEquals(List.of("gfs SAND 5"), host.commands);

        // After the cooldown the crop goes out and the soil wait is dropped.
        host.now += GreenhouseSupplyRunner.COOLDOWN_MILLIS;
        runner.onPick("Carrot", INGREDIENT);
        assertEquals(List.of("gfs SAND 5", "gfs CARROT_ITEM 3"), host.commands);
        host.put(0, "SAND", 5);
        runner.tick();
        assertTrue("the dropped soil pull is not switched to", host.selects.isEmpty());
    }

    @Test
    public void aShortSackReplyIsSaidPlainlyAndStillSwitches() {
        host.required.put("Thornshade", 3);
        runner.onPick("Thornshade", INGREDIENT);
        runner.onChat("Moved 2 Thornshade from your Sacks to your inventory.");
        host.put(3, "THORNSHADE", 2);

        runner.tick();

        assertFalse(runner.pendingPull());
        assertEquals(List.of(3), host.selects);
        assertEquals("WARNING: Only 2 of 3 Thornshade were in your sacks.", host.chats.get(1));
    }

    @Test
    public void aFullDeliveryIsReportedAsPulled() {
        host.required.put("Dustgrain", 3);
        runner.onPick("Dustgrain", INGREDIENT);
        runner.onChat("Moved 3 Dustgrain from your Sacks to your inventory.");
        host.put(0, "DUSTGRAIN", 3);

        runner.tick();

        assertEquals("INFO: Pulled 3 Dustgrain", host.chats.get(1));
    }

    @Test
    public void repeatedPicksOfTheSameCellSendExactlyOneCommand() {
        host.required.put("Dustgrain", 3);

        runner.onPick("Dustgrain", INGREDIENT);
        host.now += 17;
        runner.onPick("Dustgrain", INGREDIENT);
        host.now += 17;
        runner.onPick("Dustgrain", INGREDIENT);

        assertEquals(List.of("gfs DUSTGRAIN 3"), host.commands);
        assertEquals(2, host.chats.stream().filter(chat -> chat.contains("Still waiting")).count());
    }

    @Test
    public void aSecondCropMomentsLaterIsNotSentEither() {
        host.required.put("Dustgrain", 3);
        host.required.put("Thornshade", 3);

        runner.onPick("Dustgrain", INGREDIENT);
        host.now += 17;
        runner.onPick("Thornshade", INGREDIENT);

        assertEquals(List.of("gfs DUSTGRAIN 3"), host.commands);
    }
}
