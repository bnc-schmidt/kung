package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HypixelFriendTrackerTest {
    private static final String DASHES = "-----------------------------------------------------";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private long now = 1_000_000L;

    private HypixelFriendTracker tracker(Path file, String account) {
        return new HypixelFriendTracker(file, () -> account);
    }

    private HypixelFriendTracker tracker() throws Exception {
        return tracker(folder.newFolder().toPath().resolve("friends.json"), "uuid-1");
    }

    /** One page as Hypixel sends it: a single message, arrows on the header except at the ends of the list. */
    private void page(HypixelFriendTracker tracker, int page, int pages, String... entries) {
        String header = "                      " + (page > 1 ? "<< " : "") + "Friends (Page " + page + " of " + pages + ")"
            + (page < pages ? " >>" : "");
        tracker.observe(DASHES + "\n" + header + "\n" + String.join("\n", entries) + "\n" + DASHES, now);
    }

    @Test
    public void aFriendListPageIsReadFromOnlineAndOfflineLines() throws Exception {
        var tracker = tracker();
        // Page 1 of the real list: no "<<" in front, the rank is a colour only.
        page(tracker, 1, 20, "Blastelectro is in Limbo", "Bulczos is in SkyBlock - Private Island",
            "Cryptix008 is in SkyBlock - Crystal Hollows", "Lord_Iggi is in the Main Lobby",
            "opagangiopa is in SkyBlock - Garden", "DANCEPRO is currently offline", "4hal4 is currently offline");
        assertEquals(List.of("Blastelectro", "Bulczos", "Cryptix008", "Lord_Iggi", "opagangiopa", "DANCEPRO", "4hal4"),
            tracker.names());
    }

    @Test
    public void aPageWhoseLinesComeOneByOneIsReadToo() throws Exception {
        var tracker = tracker();
        tracker.observe(DASHES, now);
        tracker.observe("                      << Friends (Page 2 of 2)", now);
        tracker.observe("Zbynekeu is currently offline", now);
        tracker.observe("", now);
        tracker.observe(DASHES, now);
        assertEquals(List.of("Zbynekeu"), tracker.names());
    }

    @Test
    public void linesOutsideAFriendListAreNoFriends() throws Exception {
        var tracker = tracker();
        tracker.observe("Bob was killed by a Zombie", now);
        tracker.observe("Carol is now on fire", now);
        tracker.observe(DASHES, now);
        tracker.observe("Party Members (2)", now);
        tracker.observe("Dave is a leader", now);
        tracker.observe(DASHES, now);
        assertTrue(tracker.names().isEmpty());
    }

    @Test
    public void aPageWithoutItsClosingLineIsNotClosedByALaterSeparator() throws Exception {
        var tracker = tracker();
        tracker.observe("Friends (Page 1 of 1)", now);
        tracker.observe("Alice is currently offline", now);
        // The separator of some other block, long after, does not close the page.
        tracker.observe(DASHES, now + 60_000L);
        assertEquals(List.of(), tracker.names());
    }

    @Test
    public void everyPageTheTrackerSeesAddsItsNamesAtOnce() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 3, "Alice is currently offline");
        assertEquals(List.of("Alice"), tracker.names());
        page(tracker, 2, 3, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Bob_2"), tracker.names());
    }

    @Test
    public void everyPageInTurnReplacesTheListSoAFriendRemovedInTheMeantimeIsGone() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 2, "Alice is currently offline", "Old_Pal is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Old_Pal", "Bob_2"), tracker.names());

        page(tracker, 1, 2, "Alice is currently offline");
        assertEquals("one page is no full read", List.of("Alice", "Old_Pal", "Bob_2"), tracker.names());
        page(tracker, 2, 2, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Bob_2"), tracker.names());
    }

    @Test
    public void aPageOpenedTwiceKeepsTheRun() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 2, "Alice is currently offline", "Old_Pal is currently offline");
        page(tracker, 1, 2, "Alice is currently offline", "Old_Pal is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Old_Pal", "Bob_2"), tracker.names());
        page(tracker, 1, 2, "Alice is currently offline");
        page(tracker, 1, 2, "Alice is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Bob_2"), tracker.names());
    }

    @Test
    public void aSinglePageOfALongListOnlyAddsAndNeverCutsTheList() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 2, "Alice is currently offline");
        page(tracker, 2, 2, "Bob_2 is currently offline");
        page(tracker, 2, 2, "Carol is currently offline");
        assertEquals(List.of("Alice", "Bob_2", "Carol"), tracker.names());
    }

    @Test
    public void aSkippedPageBreaksTheRunSoNoPartialReadReplacesTheList() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 3, "Alice is currently offline", "Old_Pal is currently offline");
        page(tracker, 3, 3, "Bob_2 is currently offline");
        assertEquals(List.of("Alice", "Old_Pal", "Bob_2"), tracker.names());
    }

    @Test
    public void aPageThatFindsNoNameKeepsWhatWasSaved() throws Exception {
        var tracker = tracker();
        page(tracker, 1, 1, "Alice is currently offline");
        page(tracker, 1, 1, "a line of a format nobody knows");
        assertEquals(List.of("Alice"), tracker.names());
    }

    @Test
    public void aFriendWhoComesOnlineIsAddedOnce() throws Exception {
        var tracker = tracker();
        tracker.observe("Friend > Alice joined.", now);
        tracker.observe("Friend > Alice left.", now);
        tracker.observe("Friend > Bob_2 joined.", now);
        assertEquals(List.of("Alice", "Bob_2"), tracker.names());
        // Guild and party lines of the same shape are no friends.
        tracker.observe("Guild > Carol joined.", now);
        tracker.observe("[MVP+] Dave: Friend > Eve joined.", now);
        assertEquals(2, tracker.names().size());
    }

    @Test
    public void theListIsSavedPerAccountAndComesBackAfterARestart() throws Exception {
        Path file = folder.newFolder().toPath().resolve("friends.json");
        page(tracker(file, "uuid-1"), 1, 1, "Alice is currently offline");
        page(tracker(file, "uuid-2"), 1, 1, "Bob_2 is currently offline");
        assertEquals(List.of("Alice"), tracker(file, "uuid-1").names());
        assertEquals(List.of("Bob_2"), tracker(file, "uuid-2").names());
        assertEquals(List.of(), tracker(file, "uuid-3").names());
        assertTrue(Files.readString(file).contains("uuid-1"));
    }

    @Test
    public void aMissingOrBrokenFileGivesAnEmptyList() throws Exception {
        Path file = folder.newFolder().toPath().resolve("friends.json");
        assertEquals(List.of(), tracker(file, "uuid-1").names());
        Files.writeString(file, "{ this is not json");
        assertEquals(List.of(), tracker(file, "uuid-1").names());
        // A broken file is overwritten by the next page read.
        var tracker = tracker(file, "uuid-1");
        page(tracker, 1, 1, "Alice is currently offline");
        assertEquals(List.of("Alice"), tracker(file, "uuid-1").names());
    }
}
