package com.github.beng420.kung.skyblock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import com.github.beng420.kung.util.KungDebugRecorder;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.Test;

public final class SkyBlockSidebarTest {
    @Test
    public void syntheticOwnersDoNotSplitLocationOrServerText() {
        Fixture fixture = new Fixture();
        fixture.teamLine("team_date", "🎁", 15, "§709/10/26 mini", "§724BS");
        fixture.teamLine("team_location", "⚽", 10, " §7⏣ §cThe Cata", "§ccombs (M7)");

        List<String> lines = SkyBlockSidebar.lines(fixture.scoreboard, Set.of("⚽", "🎁"));

        assertEquals(List.of("SKYBLOCK", "09/10/26 mini24BS", "⏣ The Catacombs (M7)"),
            lines.stream().map(HypixelLocation::clean).toList());
        assertEquals(HypixelLocation.Kind.CATACOMBS, HypixelLocation.parse(lines).kind());
        assertEquals("mini24bs", HypixelLocation.serverId(lines));
        assertFalse(lines.stream().anyMatch(line -> line.contains("⚽") || line.contains("🎁")));
    }

    @Test
    public void ownersNotUpdatedInThisWorldCannotOverrideFreshDungeonEvidence() {
        Fixture fixture = new Fixture();
        fixture.teamLine("old_area", "🍫", 14, "§7⏣ §bDungeon ", "§bHub");
        fixture.teamLine("old_server", "🎁", 15, "§709/10/26 mini", "§7149C");
        fixture.teamLine("new_area", "⚽", 9, "§7⏣ §cThe Cata", "§ccombs (E)");
        fixture.teamLine("new_server", "🎉", 12, "§709/10/26 mini", "§7112H");

        // The old rows really are present; only current-world packet ownership makes them ineligible.
        assertEquals(HypixelLocation.Kind.DUNGEON_HUB,
            HypixelLocation.parse(SkyBlockSidebar.lines(fixture.scoreboard, null)).kind());
        List<String> fresh = SkyBlockSidebar.lines(fixture.scoreboard, Set.of("⚽", "🎉"));
        assertEquals(HypixelLocation.Kind.CATACOMBS, HypixelLocation.parse(fresh).kind());
        assertEquals("mini112h", HypixelLocation.serverId(fresh));
        assertEquals(3, fresh.size());

        List<String> unconfirmed = SkyBlockSidebar.lines(fixture.scoreboard, Set.of());
        assertEquals(List.of("SKYBLOCK"), unconfirmed);
        assertEquals(HypixelLocation.UNKNOWN, HypixelLocation.parse(unconfirmed));
        assertEquals("", HypixelLocation.serverId(unconfirmed));
    }

    @Test
    public void directDisplayNamesWorkWithoutTeamsAndHiddenScoresStayHidden() {
        Fixture fixture = new Fixture();
        fixture.scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("location"), fixture.objective)
            .display(Component.literal("⏣ Kuudra's Hollow (T5)"));
        fixture.teamLine("hidden", "#not-a-sidebar-line", 20, "⏣ Dungeon ", "Hub");

        List<String> lines = SkyBlockSidebar.lines(fixture.scoreboard, null);
        assertEquals(List.of("SKYBLOCK", "⏣ Kuudra's Hollow (T5)"), lines);
        assertEquals(HypixelLocation.Kind.KUUDRA, HypixelLocation.parse(lines).kind());
    }

    @Test
    public void freshScoreValueCannotReviveTeamTextFromBeforeTheTransfer() {
        Fixture fixture = new Fixture();
        fixture.teamLine("reused", "old-owner", 10, "⏣ Dungeon ", "Hub");
        // Respawn may preserve the vanilla scoreboard. A new numeric score is not a new location field.
        List<String> beforeTeamPacket = SkyBlockSidebar.lines(fixture.scoreboard, Set.of("old-owner"), Set.of());
        assertEquals(HypixelLocation.UNKNOWN, HypixelLocation.parse(beforeTeamPacket));
        PlayerTeam team = fixture.scoreboard.getPlayerTeam("reused");
        team.setPlayerPrefix(Component.literal("⏣ The Cata"));
        team.setPlayerSuffix(Component.literal("combs (M7)"));
        List<String> afterTeamPacket = SkyBlockSidebar.lines(fixture.scoreboard, Set.of("old-owner"), Set.of("reused"));
        assertEquals(HypixelLocation.Kind.CATACOMBS, HypixelLocation.parse(afterTeamPacket).kind());
    }

    @Test
    public void bossSidebarRewriteDoesNotCreateAnInstanceBetweenItsPackets() {
        Fixture fixture = new Fixture();
        fixture.teamLine("date", "date-owner", 15, "09/10/26 m61", "e");
        fixture.teamLine("location", "location-owner", 10, "The Catacombs ", "(F1)");
        SkyBlockSidebar.Pending pending = new SkyBlockSidebar.Pending();
        pending.team("date", Set.of("date-owner"), true);
        pending.team("location", Set.of("location-owner"), true);
        HypixelInstanceState state = new HypixelInstanceState();
        List<String> initial = pending.poll(fixture.scoreboard);
        state.observe(HypixelLocation.parse(initial), HypixelLocation.serverId(initial));
        long epoch = state.epoch();

        // Exact trace regression: m61e temporarily reads as m61 during the boss HUD rewrite.
        PlayerTeam date = fixture.scoreboard.getPlayerTeam("date");
        date.setPlayerSuffix(Component.empty());
        pending.team("date", Set.of("date-owner"), true);
        date.setPlayerSuffix(Component.literal("e"));
        pending.team("date", Set.of("date-owner"), true);
        List<String> completedBatch = pending.poll(fixture.scoreboard);
        state.observe(HypixelLocation.parse(completedBatch), HypixelLocation.serverId(completedBatch));
        assertTrue(state.catacombs());
        assertEquals(epoch, state.epoch());
        assertEquals("m61e", state.serverId());
        assertNull(pending.poll(fixture.scoreboard));

        // A complete new server row still invalidates the old instance once it stays on the sidebar.
        date.setPlayerPrefix(Component.literal("09/10/26 m183"));
        date.setPlayerSuffix(Component.literal("q"));
        pending.team("date", Set.of("date-owner"), true);
        List<String> transfer = pending.poll(fixture.scoreboard);
        assertTrue(state.holdServerChange(HypixelLocation.serverId(transfer)));
        for (int tick = 1; tick < HypixelInstanceState.SERVER_CHANGE_CONFIRM_TICKS; tick++) {
            assertEquals("", state.confirmedServerChange(transfer));
        }
        String confirmed = state.confirmedServerChange(transfer);
        assertEquals("m183q", confirmed);
        state.observe(HypixelLocation.parse(transfer), confirmed);
        assertFalse(state.catacombs());
        assertEquals(epoch + 1, state.epoch());
        pending.reset();
        assertNull(pending.poll(fixture.scoreboard));
    }

    @Test
    public void serverRowFlickerAcrossTwoPollsIsNotATransfer() {
        Fixture fixture = new Fixture();
        fixture.teamLine("date", "date-owner", 15, "09/10/26 m96", "cq");
        fixture.teamLine("location", "location-owner", 10, "The Catacombs ", "(M7)");
        SkyBlockSidebar.Pending pending = new SkyBlockSidebar.Pending();
        pending.team("date", Set.of("date-owner"), true);
        pending.team("location", Set.of("location-owner"), true);
        HypixelInstanceState state = new HypixelInstanceState();
        List<String> initial = pending.poll(fixture.scoreboard);
        state.observe(HypixelLocation.parse(initial), HypixelLocation.serverId(initial));
        long epoch = state.epoch();

        // Trace regression: m96cq -> m96h -> m96cq within two client ticks after the Wither King's line.
        PlayerTeam date = fixture.scoreboard.getPlayerTeam("date");
        date.setPlayerSuffix(Component.literal("h"));
        pending.team("date", Set.of("date-owner"), true);
        List<String> flicker = pending.poll(fixture.scoreboard);
        assertEquals("m96h", HypixelLocation.serverId(flicker));
        assertTrue(state.holdServerChange("m96h"));
        assertEquals("", state.confirmedServerChange(flicker));

        date.setPlayerSuffix(Component.literal("cq"));
        pending.team("date", Set.of("date-owner"), true);
        List<String> restored = pending.poll(fixture.scoreboard);
        assertFalse(state.holdServerChange(HypixelLocation.serverId(restored)));
        state.observe(HypixelLocation.parse(restored), HypixelLocation.serverId(restored));
        for (int tick = 0; tick < 2 * HypixelInstanceState.SERVER_CHANGE_CONFIRM_TICKS; tick++) {
            assertEquals("", state.confirmedServerChange(restored));
        }
        assertTrue(state.catacombs());
        assertEquals(epoch, state.epoch());
        assertEquals("m96cq", state.serverId());
    }

    @Test
    public void startingToHoldADifferentServerIdIsTracedOnce() {
        KungDebugRecorder.clear();
        HypixelInstanceState state = new HypixelInstanceState();
        state.observe(HypixelLocation.parse(List.of("The Catacombs (M7)")), "m96cq");
        assertTrue(state.holdServerChange("m96h"));
        assertTrue(state.holdServerChange("m96h"));

        String trace = KungDebugRecorder.dump();
        assertEquals(1, trace.split("server-hold m96cq -> m96h", -1).length - 1);
    }

    @Test
    public void committedServerChangeReadsTheLiveRowsAgainInsteadOfLosingTheLocation() {
        Fixture fixture = new Fixture();
        fixture.teamLine("date", "date-owner", 15, "09/10/26 m96", "cq");
        fixture.teamLine("location", "location-owner", 10, "The Catacombs ", "(M7)");
        // Left over from before the transfer: never touched in this world, so never eligible.
        fixture.teamLine("stale", "stale-owner", 14, "⏣ Dungeon ", "Hub");
        HypixelInstanceTracker tracker = new HypixelInstanceTracker();
        tracker.pendingSidebar.team("date", Set.of("date-owner"), true);
        tracker.pendingSidebar.team("location", Set.of("location-owner"), true);
        // First learn the session of m96cq from the live rows, as tick() and the packets do.
        tracker.commitServerChange("m96cq", fixture.scoreboard, List.of());
        long epoch = tracker.instanceEpoch();
        assertEquals(HypixelLocation.Kind.CATACOMBS, tracker.location().kind());

        // Trace regression: the server row stays m96h past SERVER_CHANGE_CONFIRM_TICKS without a world
        // packet. Hypixel never re-sends the static Catacombs row, but it is still on the board.
        fixture.scoreboard.getPlayerTeam("date").setPlayerSuffix(Component.literal("h"));
        PlayerInfo tabRow = new PlayerInfo(new GameProfile(UUID.randomUUID(), "tab-row"), false);
        tabRow.setTabListDisplayName(Component.literal("Area: The Catacombs (M7)"));
        tracker.commitServerChange("m96h", fixture.scoreboard, List.of(tabRow));

        assertEquals(epoch + 1, tracker.instanceEpoch());
        assertEquals("m96h", tracker.serverId());
        assertEquals(HypixelLocation.Kind.CATACOMBS, tracker.location().kind());
        assertEquals(new HypixelDungeonFloor(7, true), tracker.dungeonFloor());
        assertTrue(tracker.sidebarLines().stream().anyMatch(line -> line.contains("(M7)")));
        assertTrue(tracker.visibleLines().contains("Area: The Catacombs (M7)"));
        assertFalse(tracker.sidebarLines().stream().anyMatch(line -> line.contains("Hub")));
    }

    private static final class Fixture {
        final Scoreboard scoreboard = new Scoreboard();
        final Objective objective = scoreboard.addObjective("SBScoreboard", ObjectiveCriteria.DUMMY,
            Component.literal("SKYBLOCK"), ObjectiveCriteria.RenderType.INTEGER, false, null);

        Fixture() {
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }

        void teamLine(String teamName, String owner, int score, String prefix, String suffix) {
            PlayerTeam team = scoreboard.addPlayerTeam(teamName);
            team.setPlayerPrefix(Component.literal(prefix));
            team.setPlayerSuffix(Component.literal(suffix));
            scoreboard.addPlayerToTeam(owner, team);
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), objective).set(score);
        }
    }
}
