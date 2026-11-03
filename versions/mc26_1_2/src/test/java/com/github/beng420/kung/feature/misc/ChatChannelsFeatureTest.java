package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.misc.ChatChannels.Channel;
import com.github.beng420.kung.feature.misc.ChatChannelsFeature.Slot;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ChatChannelsFeatureTest {
    /** The default font: about 6 pixels per letter. */
    private static int width(String text) { return text.length() * 6; }

    private static List<Slot> tabs(int screenWidth) {
        return ChatChannelsFeature.tabs(screenWidth, 240, ChatChannelsFeatureTest::width);
    }

    @Test
    public void theTabsAreAllPartyGuildCoopAndDmsInOneRowWithoutOverlap() {
        List<Slot> tabs = tabs(427);
        assertEquals(Arrays.asList(null, Channel.PARTY, Channel.GUILD, Channel.COOP, Channel.DIRECT),
            tabs.stream().map(Slot::channel).toList());
        for (int i = 1; i < tabs.size(); i++) {
            assertEquals(tabs.getFirst().y(), tabs.get(i).y());
            assertTrue(tabs.get(i - 1).x() + tabs.get(i - 1).width() < tabs.get(i).x());
        }
    }

    @Test
    public void theTabsSitAtTheRightAndLeaveChatPatchesSearchAtTheLeftFree() {
        for (int screenWidth : new int[] {427, 640, 854}) {
            List<Slot> tabs = tabs(screenWidth);
            Slot last = tabs.getLast();
            assertEquals(screenWidth - 2, last.x() + last.width());
            // Chat Patches' open search field ends at 22 + a quarter of the width, on the same row.
            assertTrue("width " + screenWidth, tabs.getFirst().x() > 22 + screenWidth / 4);
        }
    }

    @Test
    public void theFlyoutHasOneCheckPerKindStackedDirectlyOverTheAllTab() {
        Slot all = tabs(427).getFirst();
        List<Slot> rows = ChatChannelsFeature.flyout(all, ChatChannelsFeatureTest::width);
        assertEquals(List.of(Channel.values()), rows.stream().map(Slot::channel).toList());
        assertEquals("the lowest check touches the tab, so the pointer can move up into it", all.y(),
            rows.getFirst().y() + rows.getFirst().height());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).y(), rows.get(i).y() + rows.get(i).height());
            assertEquals(all.x(), rows.get(i).x());
            assertTrue(rows.get(i).width() >= all.width());
        }
    }

    @Test
    public void aSwitchKeepsYouAtTheNearestShownMessageOlderOnATie() {
        // Index 0 is the newest message. Shown: 2 and 5.
        assertEquals(2, ChatChannelsFeature.nearestVisible(2, 8, index -> index == 2 || index == 5));
        assertEquals("one older than 3 and one newer: the older", 4,
            ChatChannelsFeature.nearestVisible(3, 8, index -> index == 2 || index == 4));
        assertEquals(5, ChatChannelsFeature.nearestVisible(7, 8, index -> index == 2 || index == 5));
        assertEquals("nothing older is left", 2, ChatChannelsFeature.nearestVisible(6, 8, index -> index == 2));
        assertEquals(-1, ChatChannelsFeature.nearestVisible(3, 8, index -> false));
        assertEquals(-1, ChatChannelsFeature.nearestVisible(0, 0, index -> true));
    }

    /** Messages are told apart by identity, as the chat does; the strings only name them. */
    private static final String M3 = new String("m3");
    private static final String M2 = new String("m2");
    private static final String M1 = new String("m1");
    private static final List<String> ALL = List.of(M3, M2, M1);
    /** The lines, newest first: m3 has two, m2 one, m1 three. */
    private static final List<String> LINES = List.of(M3, M3, M2, M1, M1, M1);

    private static java.util.Map<String, int[]> placed(String... shown) {
        java.util.Map<String, int[]> placed = new java.util.IdentityHashMap<>();
        int next = 0;
        for (String message : ALL) {
            if (!Arrays.asList(shown).contains(message)) continue;
            int count = LINES.stream().filter(line -> line == message).toList().size();
            placed.put(message, new int[] {next, count});
            next += count;
        }
        return placed;
    }

    @Test
    public void switchingAChannelKeepsTheLineAtTheBottomOfThePage() {
        // Scrolled 4 lines up: the page ends on the second line of m1, counted from its newest line.
        var anchor = ChatChannelsFeature.anchorAt(LINES.size(), 4, LINES::get);
        assertEquals(M1, anchor.message());
        assertEquals(1, anchor.line());
        // Hiding m2 pulls m1 up one line: it now starts at line 2, so its second line is line 3.
        assertEquals(3, ChatChannelsFeature.anchoredLine(anchor, ALL, placed(M3, M1)));
        // Showing everything again puts it back where it was.
        assertEquals(4, ChatChannelsFeature.anchoredLine(anchor, ALL, placed(M3, M2, M1)));
        // Hiding m3, which is below the page, moves m1 down to the bottom edge.
        assertEquals(2, ChatChannelsFeature.anchoredLine(anchor, ALL, placed(M2, M1)));
    }

    @Test
    public void aHiddenAnchorFallsBackToTheNearestShownMessage() {
        var anchor = ChatChannelsFeature.anchorAt(LINES.size(), 4, LINES::get);
        // m1 hidden: nothing is older, so the newer m2 takes over, at its first line.
        assertEquals(2, ChatChannelsFeature.anchoredLine(anchor, ALL, placed(M3, M2)));
        var middle = ChatChannelsFeature.anchorAt(LINES.size(), 2, LINES::get);
        assertEquals(M2, middle.message());
        // m2 hidden: m1 (older) wins over m3 (newer), at its first line.
        assertEquals(2, ChatChannelsFeature.anchoredLine(middle, ALL, placed(M3, M1)));
        assertEquals("every message hidden", -1, ChatChannelsFeature.anchoredLine(middle, ALL, placed()));
    }

    @Test
    public void atTheBottomOrPastTheEndThereIsNothingToKeep() {
        assertNull(ChatChannelsFeature.anchorAt(LINES.size(), 0, LINES::get));
        assertNull(ChatChannelsFeature.anchorAt(LINES.size(), LINES.size(), LINES::get));
        assertEquals(-1, ChatChannelsFeature.anchoredLine(null, ALL, placed(M3, M2, M1)));
    }

    @Test
    public void aClickCountsOnlyInsideASlot() {
        Slot slot = tabs(427).getFirst();
        assertTrue(slot.contains(slot.x(), slot.y()));
        assertFalse(slot.contains(slot.x() - 1, slot.y()));
        assertFalse(slot.contains(slot.x(), slot.y() + slot.height()));
    }

    @Test
    public void everyTabHasItsChannelAndViewAndDmsHaveNoChannelToSendTo() {
        assertEquals(ChatChannels.Send.ALL, ChatChannels.sendFor(null));
        assertEquals(ChatChannels.Send.PARTY, ChatChannels.sendFor(Channel.PARTY));
        assertEquals(ChatChannels.Send.GUILD, ChatChannels.sendFor(Channel.GUILD));
        assertEquals(ChatChannels.Send.COOP, ChatChannels.sendFor(Channel.COOP));
        assertNull(ChatChannels.sendFor(Channel.DIRECT));
        for (Channel tab : new Channel[] {Channel.PARTY, Channel.GUILD, Channel.COOP}) {
            assertEquals("a switch to a tab's channel leads back to that tab", tab, ChatChannels.sendFor(tab).channel());
        }
        assertEquals("Officer lines are Guild lines", Channel.GUILD, ChatChannels.Send.OFFICER.channel());
        assertNull(ChatChannels.Send.ALL.channel());
    }
}
