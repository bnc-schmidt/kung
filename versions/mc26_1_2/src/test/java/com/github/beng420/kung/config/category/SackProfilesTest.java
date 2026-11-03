package com.github.beng420.kung.config.category;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;

public final class SackProfilesTest {
    private static final String ACCOUNT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ACCOUNT = "22222222-2222-2222-2222-222222222222";
    private static final String COCONUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MANGO = "b11dbaaf-8ae1-4478-8272-7a4e66fcea77";
    private static final Gson GSON = new Gson();

    /** What Hypixel sends on every SkyBlock join: the profile's name, then its id. */
    private static void join(MiscConfig config, String account, String name, String id) {
        config.observeSackTrackerProfile("You are playing on profile: " + name, account);
        config.observeSackTrackerProfile("Profile ID: " + id, account);
    }

    private static MiscConfig reload(MiscConfig config) {
        MiscConfig loaded = GSON.fromJson(GSON.toJson(config), MiscConfig.class);
        loaded.normalize();
        return loaded;
    }

    private static List<String> names(MiscConfig config) {
        return config.sackTrackerItems().stream().map(MiscConfig.SackItem::name).toList();
    }

    @Test
    public void unknownProfileHasNothingToTrackAndAFreshOneStartsEmpty() {
        MiscConfig config = new MiscConfig();
        assertFalse(config.sackTrackerProfileKnown());
        assertEquals("", config.sackTrackerProfileName());

        join(config, ACCOUNT, "Coconut", COCONUT);
        assertTrue(config.sackTrackerProfileKnown());
        assertEquals("Coconut", config.sackTrackerProfileName());
        assertTrue(config.sackTrackerItems().isEmpty());
    }

    @Test
    public void twoProfilesKeepSeparateCountsAndSwitchingBackRestoresThem() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);
        config.setSackTrackerGoal(config.sackTrackerItem("Wheat"), 500);

        join(config, ACCOUNT, "Mango", MANGO);
        assertEquals("Mango", config.sackTrackerProfileName());
        assertTrue(config.sackTrackerItems().isEmpty());
        config.toggleSackTrackerItem("Carrot", 0xFFFFFF, 7);
        config.setSackTrackerAmount(config.sackTrackerItem("Carrot"), 9);

        join(config, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(config));
        assertEquals(100, config.sackTrackerItem("Wheat").amount());
        assertEquals(500, config.sackTrackerItem("Wheat").goal());

        join(config, ACCOUNT, "Mango", MANGO);
        assertEquals(List.of("Carrot"), names(config));
        assertEquals(9, config.sackTrackerItem("Carrot").amount());

        config.clearSackTrackerItems(); // the reset only empties the profile the player is on
        join(config, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(config));
    }

    @Test
    public void accountsKeepSeparateStatesForTheSameProfile() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);

        join(config, OTHER_ACCOUNT, "Coconut", COCONUT); // a co-op partner shares the profile id, not the tracker
        assertTrue(config.sackTrackerItems().isEmpty());

        join(config, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(config));
    }

    @Test
    public void theIdRepeatedAtEveryWorldChangeChangesNothing() {
        MiscConfig config = new MiscConfig();
        assertTrue(config.observeSackTrackerProfile("Profile ID: " + COCONUT, ACCOUNT));
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);
        assertFalse(config.observeSackTrackerProfile("Profile ID: " + COCONUT, ACCOUNT));
        assertEquals(List.of("Wheat"), names(config));
    }

    @Test
    public void theOldSingleStateMovesToTheFirstProfileSeen() {
        MiscConfig config = GSON.fromJson(
            "{\"sackTrackerItems\":[{\"name\":\"Wheat\",\"color\":16777215,\"amount\":5,\"goal\":10}]}", MiscConfig.class);
        assertEquals(List.of("Wheat"), names(config)); // visible, though not counted, until a profile is known

        join(config, ACCOUNT, "Coconut", COCONUT);
        assertEquals(5, config.sackTrackerItem("Wheat").amount());
        assertEquals(10, config.sackTrackerItem("Wheat").goal());

        join(config, ACCOUNT, "Mango", MANGO);
        assertTrue(config.sackTrackerItems().isEmpty()); // only the first profile takes it over

        MiscConfig reloaded = reload(config);
        assertTrue(reloaded.sackTrackerItems().isEmpty()); // the old list is gone, not copied
        join(reloaded, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(reloaded));
        join(reloaded, ACCOUNT, "Mango", MANGO);
        assertTrue(reloaded.sackTrackerItems().isEmpty());
    }

    @Test
    public void everyProfileSurvivesSavingAndLoading() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);
        join(config, ACCOUNT, "Mango", MANGO);
        config.toggleSackTrackerItem("Carrot", 0x55FF55, 3);

        MiscConfig reloaded = reload(config);
        assertFalse(reloaded.sackTrackerProfileKnown()); // the profile is read again after a restart
        join(reloaded, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(reloaded));
        assertEquals(100, reloaded.sackTrackerItem("Wheat").amount());
        join(reloaded, ACCOUNT, "Mango", MANGO);
        assertEquals(List.of("Carrot"), names(reloaded));
        assertEquals(0x55FF55, reloaded.sackTrackerItem("Carrot").color());
    }

    @Test
    public void noProfileIsActiveAfterASwitchLineOrLeavingUntilTheNextId() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        assertTrue(config.observeSackTrackerProfile("Switching to profile Mango...", ACCOUNT));
        assertFalse(config.sackTrackerProfileKnown());
        assertEquals("", config.sackTrackerProfileName());

        join(config, ACCOUNT, "Mango", MANGO);
        assertEquals("Mango", config.sackTrackerProfileName());

        config.leaveSackTrackerProfile(); // leaving the server
        assertFalse(config.sackTrackerProfileKnown());
        join(config, ACCOUNT, "Mango", MANGO);
        assertTrue(config.sackTrackerProfileKnown());
    }

    @Test
    public void onlyTheRealSystemLinesCount() {
        MiscConfig config = new MiscConfig();
        // A player cannot type a line that starts with "Profile ID:", but may quote one.
        assertFalse(config.observeSackTrackerProfile("[MVP+] Bob: Profile ID: " + COCONUT, ACCOUNT));
        assertFalse(config.observeSackTrackerProfile("Profile ID: not-a-uuid", ACCOUNT));
        assertFalse(config.observeSackTrackerProfile("Profile ID: " + COCONUT, null)); // no account to key it by
        assertFalse(config.sackTrackerProfileKnown());

        // The name ignores the suffix and a chat timestamp; the id decides the profile.
        config.observeSackTrackerProfile("[18:04:39] You are playing on profile: Mango (Co-op)", ACCOUNT);
        assertTrue(config.observeSackTrackerProfile("[18:04:39] Profile ID: " + MANGO, ACCOUNT));
        assertEquals("Mango", config.sackTrackerProfileName());
    }

    @Test
    public void theOldestProfilesGoWhenThereAreTooMany() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "First", COCONUT);
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);
        for (int index = 0; index < 64; index++) {
            join(config, ACCOUNT, "Other", String.format("00000000-0000-0000-0000-%012d", index));
        }
        join(config, ACCOUNT, "First", COCONUT);
        assertTrue(config.sackTrackerItems().isEmpty());
    }

    @Test
    public void brokenSavedProfilesAreCleanedUp() {
        MiscConfig config = GSON.fromJson("{\"sackTrackerProfiles\":{\"profiles\":{\"a:b\":null,"
            + "\"" + ACCOUNT + ":" + COCONUT + "\":{\"name\":null,\"items\":[null,{\"name\":\"\",\"amount\":1},"
            + "{\"name\":\"Wheat\",\"color\":1,\"amount\":2,\"goal\":0}]}}}}", MiscConfig.class);
        config.normalize();
        join(config, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(config));
    }

    @Test
    public void watchedItemsAreReadButNeverListedAndNeedAKnownProfile() {
        MiscConfig config = new MiscConfig();
        config.watchSackTrackerItems(List.of("Carrot"));
        assertTrue(config.sackTrackerAllItems().isEmpty()); // no profile: the old shared list stays the player's

        join(config, ACCOUNT, "Coconut", COCONUT);
        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 100);
        config.watchSackTrackerItems(List.of("Carrot", "Wheat", "Carrot"));

        assertEquals(List.of("Wheat"), names(config)); // the display and the settings list only what is tracked
        assertEquals(2, config.sackTrackerAllItems().size());
        MiscConfig.SackItem carrot = config.sackTrackerItem("Carrot");
        assertTrue(carrot.hidden());
        assertEquals(-1, carrot.amount()); // unknown until its sack is read
        assertFalse(config.sackTrackerItem("Wheat").hidden()); // tracking wins over watching

        config.setSackTrackerAmount(carrot, 30);
        assertEquals(30, config.sackTrackerItem("Carrot").amount());
    }

    @Test
    public void trackingAWatchedItemShowsItWithItsAmountAndTheResetKeepsWatchedOnes() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        config.watchSackTrackerItems(List.of("Carrot", "Potato"));
        config.setSackTrackerAmount(config.sackTrackerItem("Carrot"), 30);

        assertTrue(config.toggleSackTrackerItem("Carrot", 0x55FF55, -1)); // tracked now, not untracked
        assertEquals(List.of("Carrot"), names(config));
        assertEquals(30, config.sackTrackerItem("Carrot").amount());
        assertEquals(0x55FF55, config.sackTrackerItem("Carrot").color());

        assertFalse(config.toggleSackTrackerItem("Carrot", 0x55FF55, -1)); // pressing again removes it
        assertNull(config.sackTrackerItem("Carrot"));

        config.toggleSackTrackerItem("Wheat", 0xFFFFFF, 5);
        config.clearSackTrackerItems();
        assertTrue(config.sackTrackerItems().isEmpty());
        assertNotNull(config.sackTrackerItem("Potato")); // still read, still hidden
    }

    @Test
    public void watchedItemsSurviveSavingAndLoadingAsWatched() {
        MiscConfig config = new MiscConfig();
        join(config, ACCOUNT, "Coconut", COCONUT);
        config.watchSackTrackerItems(List.of("Carrot"));
        config.setSackTrackerAmount(config.sackTrackerItem("Carrot"), 30);

        MiscConfig reloaded = reload(config);
        join(reloaded, ACCOUNT, "Coconut", COCONUT);
        assertTrue(reloaded.sackTrackerItems().isEmpty());
        assertTrue(reloaded.sackTrackerItem("Carrot").hidden());
        assertEquals(30, reloaded.sackTrackerItem("Carrot").amount());

        // A config saved before watching existed has no flag: its items are all tracked.
        MiscConfig old = GSON.fromJson("{\"sackTrackerProfiles\":{\"profiles\":{\"" + ACCOUNT + ":" + COCONUT
            + "\":{\"name\":\"Coconut\",\"items\":[{\"name\":\"Wheat\",\"color\":1,\"amount\":2,\"goal\":0}]}}}}",
            MiscConfig.class);
        old.normalize();
        join(old, ACCOUNT, "Coconut", COCONUT);
        assertEquals(List.of("Wheat"), names(old));
    }
}
