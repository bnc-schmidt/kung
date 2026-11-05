package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeastContextTest {
    @Test
    public void harvestAppearsOnlyInAllThreeAutumnMonths() {
        for (String season : List.of("Spring", "Summer", "Autumn", "Winter")) {
            for (String prefix : List.of("Early ", "", "Late ")) {
                var context = new FeastContext();
                context.observe(List.of("§7" + prefix + season + " 1st"), List.of());
                assertEquals(season.equals("Autumn"), context.event(false, -1) != null);
            }
        }
    }

    @Test
    public void grandFeastOverridesAutumnAndWorksAllYearWithoutSidebarDate() {
        var context = new FeastContext();
        assertEquals(FeastProgress.Kind.GRAND, context.event(true, 513).kind());
        String previous = context.event(true, 513).key();
        for (String month : List.of("Early Spring", "Summer", "Late Autumn", "Winter")) {
            context.observe(List.of(month + " 31st"), List.of());
            assertEquals(previous, context.event(true, 513).key());
        }
        assertNotEquals(previous, context.event(true, 514).key());
    }

    @Test
    public void gardenUsesAreaFieldsWithoutMatchingMentionsOrHubFarm() {
        assertTrue(FeastContext.garden("The Garden", List.of()));
        assertTrue(FeastContext.garden("Plot - 1", List.of("§bArea: Garden")));
        assertFalse(FeastContext.garden("Farm", List.of("Visit the Garden!")));
        assertFalse(FeastContext.garden("Hub", List.of("Player: Garden", "Feast: Active")));
        assertFalse(FeastContext.garden("", List.of("Party > Friend: Area: Garden")));
    }

    @Test
    public void visibilityRequiresHypixelGardenAndActiveEvent() {
        var context = new FeastContext();
        var event = context.event(true, 513);
        assertTrue(FeastOverlayFeature.visible(true, true, false, false, event));
        assertFalse(FeastOverlayFeature.visible(false, true, false, false, event));
        assertFalse(FeastOverlayFeature.visible(true, false, false, false, event));
        assertFalse(FeastOverlayFeature.visible(true, true, false, false, null));
        assertTrue(HypixelLocation.isHypixelAddress("mc.hypixel.net:25565"));
        assertFalse(HypixelLocation.isHypixelAddress("hypixel.net.example.com"));
    }

    @Test
    public void freshDatesAndEventIdentitySurviveNormalWarpsButChangeNextAutumn() {
        var context = new FeastContext();
        context.observe(List.of("Early Autumn 1st"), List.of());
        String key = context.event(false, -1).key();
        context.worldChanged();
        assertNull(context.event(false, -1));
        assertFalse(context.hasDate());
        context.observe(List.of("Autumn 15th"), List.of());
        assertEquals(key, context.event(false, -1).key());
        context.observe(List.of("Early Winter 1st"), List.of());
        assertNull(context.event(false, -1));
        context.worldChanged();
        context.observe(List.of("Early Autumn 1st"), List.of());
        assertNotEquals(key, context.event(false, -1).key());
    }

    @Test
    public void profileChangesInvalidateButRepeatedProfileRowsDoNot() {
        var context = new FeastContext();
        assertFalse(context.observe(List.of(), List.of("Profile: Apple")));
        assertFalse(context.observe(List.of(), List.of("Profile: Apple")));
        context.worldChanged();
        assertFalse(context.observe(List.of(), List.of("Profile: Apple")));
        assertTrue(context.observe(List.of(), List.of("Profile: Banana")));
        assertTrue(FeastContext.profileMessage("You are playing on profile: Banana"));
        assertTrue(FeastContext.profileMessage("You are now playing on profile: Banana"));
        assertFalse(FeastContext.profileMessage("Party > Friend: You are playing on profile: Banana"));
    }

    @Test
    public void menuOrChatSeasonMentionsCannotStartAHarvest() {
        var context = new FeastContext();
        context.observe(List.of("Next event: Autumn 1st", "Autumn 32nd"), List.of("Autumn 1st"));
        assertNull(context.event(false, -1));
    }

    @Test
    public void hubFarmRequiresBothHubAndAnActualFarmLocationAndHonorsTheToggle() {
        for (String area : List.of("Farm", "Wheat Farm", "Farmhouse", "Communal Stew")) {
            assertTrue(FeastContext.hubFarm("Hub", List.of("§e⏣ " + area)));
            assertTrue(FeastContext.hubFarm(area, List.of("Area: Hub", "\uE001 " + area)));
        }
        assertFalse(FeastContext.hubFarm("Hub", List.of("⏣ Village")));
        assertFalse(FeastContext.hubFarm("Hub", List.of("Party > Friend: Communal Stew")));
        assertFalse(FeastContext.hubFarm("The Barn", List.of("⏣ Farm")));
        var event = new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:513");
        assertTrue(FeastOverlayFeature.visible(true, false, true, true, event));
        assertFalse(FeastOverlayFeature.visible(true, false, true, false, event));
        assertTrue(FeastOverlayFeature.visible(true, true, false, false, event));
        assertFalse(FeastOverlayFeature.visible(true, false, true, true, null));
        assertFalse(FeastOverlayFeature.visible(false, false, true, true, event));
    }

    @Test
    public void harvestIdentitySurvivesRestartButDoesNotReuseLastYearsProgress() {
        long autumn = FeastContext.YEAR_ZERO + 514 * FeastContext.YEAR_MILLIS + 200 * 1_200_000L;
        var first = new FeastContext(() -> autumn);
        first.observe(List.of("Early Autumn 15th"), List.of());
        var restarted = new FeastContext(() -> autumn + 60_000L);
        restarted.observe(List.of("Early Autumn 15th"), List.of());
        assertEquals("harvest:514", first.event(false, -1).key());
        assertEquals(first.event(false, -1), restarted.event(false, -1));
        var nextYear = new FeastContext(() -> autumn + FeastContext.YEAR_MILLIS);
        nextYear.observe(List.of("Early Autumn 15th"), List.of());
        assertNotEquals(first.event(false, -1), nextYear.event(false, -1));
        nextYear.observe(List.of("Autumn 1st, Year 600"), List.of());
        assertEquals("harvest:600", nextYear.event(false, -1).key());
    }

    @Test
    public void profileMessagesHandleTimestampsAndRejectSocialMessagesAndStaleRows() {
        var context = new FeastContext();
        context.observe(List.of(), List.of("Profile: Apple"));
        context.observeProfileMessage("[18:09:02] §aYou are playing on profile: §eCoconut");
        assertEquals("Coconut", context.profile());
        context.observe(List.of(), List.of("Profile: Apple"));
        assertEquals("Coconut", context.profile());
        context.observeProfileMessage("Party > Friend: You are playing on profile: Banana");
        assertEquals("Coconut", context.profile());
        assertEquals("9fe73204-daad-43c6-9d76-a449529e6c47",
            FeastContext.profileId("[18:09:02] Profile ID: 9fe73204-daad-43c6-9d76-a449529e6c47"));
        assertNull(FeastContext.profileId("Guild > Friend: Profile ID: 9fe73204-daad-43c6-9d76-a449529e6c47"));
    }
}
