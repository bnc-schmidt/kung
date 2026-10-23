package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import com.github.beng420.kung.skyblock.JerryPerkpocalypse.Schedule;
import com.github.beng420.kung.skyblock.JerryTimeline.Slot;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.After;
import org.junit.Test;

public class JerryTimelineTest {
    private static final long HOUR = 3_600_000L;
    private static final long YEAR = 520;
    private static final long START = JerryTimeline.termStartMillis(YEAR);
    private static final long END = JerryTimeline.termEndMillis(YEAR);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final List<String> ORDER = List.of("Aatrox", "Cole", "Diana", "Finnegan", "Marina", "Paul");

    /** A schedule that knows all six mayors, the grid starting with the term. */
    private static Schedule full() {
        Schedule schedule = Schedule.NONE;
        for (int slot = 0; slot < 6; slot++) schedule = schedule.observe(YEAR, ORDER.get(slot), START + slot * 6 * HOUR);
        return schedule;
    }

    private static List<String> lines(List<Component> components) {
        return components.stream().map(JerryTimelineTest::body).toList();
    }

    /** The text without the "[Kung Jerry] " prefix. */
    private static String body(Component component) {
        String text = component.getString();
        return text.substring(text.indexOf("] ") + 2);
    }

    private record Part(String text, Integer color, Style style) { }

    private static List<Part> parts(Component component) {
        List<Part> parts = new ArrayList<>();
        component.visit((style, text) -> {
            parts.add(new Part(text, style.getColor() == null ? null : style.getColor().getValue(), style));
            return java.util.Optional.<Void>empty();
        }, Style.EMPTY);
        return parts;
    }

    private static Part part(Component component, String text) {
        return parts(component).stream().filter(part -> part.text().equals(text)).findFirst().orElseThrow();
    }

    @Test
    public void theTermRunsFiveDaysAndFourHoursFromLateSpring27() {
        // Checked against the scoreboard of 2026-10-22: "Jerry (3d, 5h)" left, so the end is the 25th at 19:15 UTC.
        assertEquals(Instant.parse("2026-10-20T15:15:00Z").toEpochMilli(), START);
        assertEquals(Instant.parse("2026-10-25T19:15:00Z").toEpochMilli(), END);
        assertEquals(124 * HOUR, END - START);
    }

    @Test
    public void twentyOneSlotsGiveTheFirstThreeMayorsFourTermsAndTheRestThree() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        assertEquals(21, slots.size());
        assertEquals(START, slots.getFirst().startMillis());
        assertEquals(END, slots.getLast().endMillis());
        // The term ends inside the 21st slot: 4 hours instead of 6.
        assertEquals(4 * HOUR, slots.getLast().endMillis() - slots.getLast().startMillis());
        for (Slot slot : slots.subList(0, 20)) assertEquals(6 * HOUR, slot.endMillis() - slot.startMillis());
        assertEquals(Map.of("Aatrox", 4, "Cole", 4, "Diana", 4, "Finnegan", 3, "Marina", 3, "Paul", 3),
            Map.of(ORDER.get(0), JerryTimeline.termsOf(slots, "Aatrox").size(), ORDER.get(1), JerryTimeline.termsOf(slots, "Cole").size(),
                ORDER.get(2), JerryTimeline.termsOf(slots, "Diana").size(), ORDER.get(3), JerryTimeline.termsOf(slots, "Finnegan").size(),
                ORDER.get(4), JerryTimeline.termsOf(slots, "Marina").size(), ORDER.get(5), JerryTimeline.termsOf(slots, "Paul").size()));
        // Only the third mayor's last term is the short one.
        assertEquals("Diana", slots.getLast().mayor());
        assertEquals(3, JerryTimeline.termsOf(slots, "Diana").stream().filter(slot -> slot.endMillis() - slot.startMillis() == 6 * HOUR).count());
    }

    @Test
    public void theRotationShownStopsWhereJerrysTermEnds() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        assertEquals(ORDER, JerryTimeline.rotation(slots, 0));
        assertEquals(List.of("Marina", "Paul", "Aatrox", "Cole", "Diana", "Finnegan"), JerryTimeline.rotation(slots, 4));
        // Four slots left: only these four, not a whole rotation.
        assertEquals(List.of("Paul", "Aatrox", "Cole", "Diana"), JerryTimeline.rotation(slots, 17));
        // Third to last: this mayor and the two after it.
        assertEquals(List.of("Aatrox", "Cole", "Diana"), JerryTimeline.rotation(slots, 18));
        assertEquals(List.of("Diana"), JerryTimeline.rotation(slots, 20));
        assertEquals(18, JerryTimeline.indexAt(slots, START + 18 * 6 * HOUR + HOUR));
        assertEquals(20, JerryTimeline.indexAt(slots, END - 1));
        assertEquals(-1, JerryTimeline.indexAt(slots, END));
        assertEquals(-1, JerryTimeline.indexAt(slots, START - 1));
    }

    @Test
    public void unknownMayorsStayUnknownAndNothingIsKnownWithoutReadings() {
        Schedule one = Schedule.NONE.observe(YEAR, "Aatrox", START);
        List<Slot> slots = JerryTimeline.slots(one, YEAR);
        assertEquals("Aatrox", slots.get(0).mayor());
        assertNull(slots.get(1).mayor());
        assertEquals("Aatrox", slots.get(6).mayor());
        assertEquals(java.util.Arrays.asList("Aatrox", null, null), JerryTimeline.rotation(slots, 18).subList(0, 3).stream()
            .map(mayor -> mayor).toList());
        assertTrue(JerryTimeline.slots(Schedule.NONE, YEAR).isEmpty());
        assertTrue(JerryTimeline.slots(one, YEAR + 8).isEmpty()); // another term
    }

    @Test
    public void aGridAFewMinutesOffTheTermStartStillStartsWithTheTerm() {
        // Readings are rounded to the minute: 3 minutes late is still the same grid.
        Schedule late = Schedule.NONE.observe(YEAR, "Aatrox", START + 3 * 60_000L);
        List<Slot> slots = JerryTimeline.slots(late, YEAR);
        assertEquals(21, slots.size());
        assertEquals(START + 6 * HOUR, slots.get(1).startMillis());
        // A grid an hour off keeps its own boundaries: the term starts inside a slot, so the first one is short.
        List<Slot> shifted = JerryTimeline.slots(Schedule.NONE.observe(YEAR, "Aatrox", START + HOUR), YEAR);
        assertEquals(START + HOUR, shifted.get(0).endMillis());
        assertEquals(22, shifted.size());
    }

    @Test
    public void spansShowTheWeekdayDateAndTimeInTheGivenZone() {
        Slot slot = new Slot(Instant.parse("2026-10-20T15:15:00Z").toEpochMilli(), Instant.parse("2026-10-20T21:15:00Z").toEpochMilli(), "Aatrox");
        assertEquals("Tue 20 Oct 15:15 - 21:15", JerryTimeline.span(slot, UTC));
        assertEquals("Tue 20 Oct 17:15 - 23:15", JerryTimeline.span(slot, ZoneId.of("Europe/Berlin"))); // CEST
        assertEquals("Tue 20 Oct 11:15 - 17:15", JerryTimeline.span(slot, ZoneId.of("America/New_York"))); // EDT
        Slot overnight = new Slot(Instant.parse("2026-10-20T21:15:00Z").toEpochMilli(), Instant.parse("2026-10-21T03:15:00Z").toEpochMilli(), "Cole");
        assertEquals("Tue 20 Oct 21:15 - Wed 21 Oct 03:15", JerryTimeline.span(overnight, UTC));
    }

    @Test
    public void statusListsTheCopiedMayorAndTheRotationOnTheirOwnLines() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        long now = START + 2 * HOUR;
        List<Component> status = JerryMessages.status(true, "Jerry", YEAR, slots, now, "ok, the server knows 6/6 slots", UTC);
        assertEquals(List.of("Jerry is in office (election year 520).", "Copying now: Aatrox, switches in 4h 0m.",
            "Rotation: Aatrox > Cole > Diana > Finnegan > Marina > Paul", "Server: ok, the server knows 6/6 slots."), lines(status));
        // No menu reading and no Paul line any more.
        assertFalse(String.join("|", lines(status)).contains("Menu reading"));
        assertFalse(String.join("|", lines(status)).contains("Paul score"));

        // The mayor copied now is yellow, the ones to come white.
        assertEquals(Integer.valueOf(JerryMessages.ACTIVE), part(status.get(1), "Aatrox").color());
        assertEquals(Integer.valueOf(JerryMessages.ACTIVE), part(status.get(2), "Aatrox").color());
        assertEquals(Integer.valueOf(JerryMessages.OPEN), part(status.get(2), "Cole").color());
    }

    @Test
    public void theRotationOfTheLastSlotsEndsWithJerrysTerm() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        List<Component> third = JerryMessages.status(true, "Jerry", YEAR, slots, START + 18 * 6 * HOUR + HOUR, "ok", UTC);
        assertEquals("Rotation: Aatrox > Cole > Diana", body(third.get(2)));
        List<Component> last = JerryMessages.status(true, "Jerry", YEAR, slots, END - 3 * HOUR, "ok", UTC);
        assertEquals("Copying now: Diana, until Jerry leaves office in 3h 0m.", body(last.get(1)));
        assertEquals("Rotation: Diana", body(last.get(2)));
    }

    @Test
    public void withoutDataOrOutsideOfficeTheStatusSaysSo() {
        List<Component> unknown = JerryMessages.status(true, "Jerry", YEAR, List.of(), START, "off", UTC);
        assertEquals("Copying now: unknown, click Jerry in the Hub.", body(unknown.get(1)));
        assertEquals("Rotation: unknown yet.", body(unknown.get(2)));
        assertEquals(List.of("Jerry is not in office (mayor: Paul)."), lines(JerryMessages.status(false, "Paul", YEAR, List.of(), START, "off", UTC)));
        assertEquals(List.of("Jerry is not in office (mayor: unknown)."), lines(JerryMessages.status(false, "", YEAR, List.of(), START, "off", UTC)));
    }

    @After
    public void releaseShift() {
        ShiftText.shiftHeld = () -> false;
    }

    private static List<String> hoverLines(Component hover) {
        return List.of(hover.getString().split("\n"));
    }

    /** The colours of the term lines: everything between the header and the footer line. */
    private static List<Integer> termColors(Component hover) {
        List<Part> lines = parts(hover).stream().filter(part -> !part.text().equals("\n")).toList();
        return lines.subList(1, lines.size() - 1).stream().map(Part::color).toList();
    }

    @Test
    public void hoveringAMayorListsItsTermsAsTimeToGoAndWithShiftAsDates() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        long now = START + 7 * 6 * HOUR + HOUR; // inside Cole's second term
        Component cole = JerryMessages.mayor("Cole", JerryMessages.OPEN, slots, now, UTC);
        Component hover = ((HoverEvent.ShowText) cole.getStyle().getHoverEvent()).value();

        ShiftText.shiftHeld = () -> false;
        assertEquals(List.of("Cole's terms", "ended 1d 7h ago", "now, ends in 5h 0m", "in 1d 11h", "in 2d 23h",
            "Hold Shift for exact times"), hoverLines(hover));
        // Over terms dark grey, the running one green, the open ones white.
        assertEquals(List.of(JerryMessages.ENDED, JerryMessages.RUNNING, JerryMessages.OPEN, JerryMessages.OPEN), termColors(hover));
        assertEquals(0x555555, JerryMessages.ENDED);

        // The same component, read again with Shift held: weekday, date and time in the zone.
        ShiftText.shiftHeld = () -> true;
        assertEquals(List.of("Cole's terms (UTC)",
            "Tue 20 Oct 21:15 - Wed 21 Oct 03:15",
            "Thu 22 Oct 09:15 - 15:15",
            "Fri 23 Oct 21:15 - Sat 24 Oct 03:15",
            "Sun 25 Oct 09:15 - 15:15",
            "Release Shift for relative times"), hoverLines(hover));
        assertEquals(List.of(JerryMessages.ENDED, JerryMessages.RUNNING, JerryMessages.OPEN, JerryMessages.OPEN), termColors(hover));

        // A mayor that is not copied now has no green term.
        ShiftText.shiftHeld = () -> false;
        Component aatrox = JerryMessages.mayor("Aatrox", JerryMessages.OPEN, slots, now, UTC);
        Component aatroxHover = ((HoverEvent.ShowText) aatrox.getStyle().getHoverEvent()).value();
        assertEquals(List.of(JerryMessages.ENDED, JerryMessages.ENDED, JerryMessages.OPEN, JerryMessages.OPEN), termColors(aatroxHover));
        // The last, shorter term of the third mayor ends with Jerry's.
        ShiftText.shiftHeld = () -> true;
        Component diana = JerryMessages.mayor("Diana", JerryMessages.OPEN, slots, now, UTC);
        assertTrue(hoverLines(((HoverEvent.ShowText) diana.getStyle().getHoverEvent()).value()).contains("Sun 25 Oct 15:15 - 19:15"));
        // An unknown mayor has nothing to hover.
        assertNull(JerryMessages.mayor(null, JerryMessages.OPEN, slots, now, UTC).getStyle().getHoverEvent());
        assertEquals("?", JerryMessages.mayor(null, JerryMessages.OPEN, slots, now, UTC).getString());
    }

    @Test
    public void relativeTimesUseTheTwoLargestUnits() {
        assertEquals("2d 23h", JerryMessages.relative(71 * HOUR + 59 * 60_000L));
        assertEquals("1d 0h", JerryMessages.relative(24 * HOUR));
        assertEquals("5h 15m", JerryMessages.relative(5 * HOUR + 15 * 60_000L));
        assertEquals("40m", JerryMessages.relative(40 * 60_000L));
        assertEquals("<1m", JerryMessages.relative(59_000L));
        assertEquals("<1m", JerryMessages.relative(-5L));
    }

    @Test
    public void theMayorCopiedNowIsGreenAndTheKnownSlotCountTurnsGreenAtSixOfSix() {
        assertEquals(0x55FF55, JerryMessages.ACTIVE);
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        List<Component> all = JerryMessages.status(true, "Jerry", YEAR, slots, START + HOUR, "ok, the server knows 6/6 slots", UTC);
        assertEquals(Integer.valueOf(JerryMessages.RUNNING), part(all.get(3), "6/6").color());
        List<Component> some = JerryMessages.status(true, "Jerry", YEAR, slots, START + HOUR, "ok, the server knows 3/6 slots", UTC);
        assertEquals(Integer.valueOf(JerryMessages.PARTIAL), part(some.get(3), "3/6").color());
        assertEquals(0xFFFF55, JerryMessages.PARTIAL);
        assertEquals("Server: ok, the server knows 3/6 slots.", body(some.get(3)));
        // Text without a count stays plain.
        assertEquals("Server: off (turn on Jerry Mayor).", body(JerryMessages.status(true, "Jerry", YEAR, slots, START + HOUR,
            "off (turn on Jerry Mayor)", UTC).get(3)));
    }

    @Test
    public void theChatReplyIsPlainTextWithTheRotation() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        assertEquals("Jerry rotation: Aatrox (switches in 4h 0m) > Cole > Diana > Finnegan > Marina > Paul",
            JerryMessages.rotationText(slots, START + 2 * HOUR));
        assertEquals("Jerry rotation: Aatrox (switches in 5h 0m) > Cole > Diana", JerryMessages.rotationText(slots, START + 18 * 6 * HOUR + HOUR));
        assertEquals("Jerry rotation: Diana (Jerry leaves in 3h 0m)", JerryMessages.rotationText(slots, END - 3 * HOUR));
        assertEquals("Jerry is in office, but I do not know his rotation yet.", JerryMessages.rotationText(List.of(), START));
        // What a chat sender makes of it: "[Kung] " in front, well below the 256 character limit.
        assertTrue(("[Kung] " + JerryMessages.rotationText(slots, START)).startsWith(JerryMessages.ROTATION_MARKER));
        assertTrue(JerryMessages.rotationText(slots, START).length() < 120);
    }

    @Test
    public void anIncomingReplyGetsHoverTextsOnTheMayorsOnly() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        long now = START + 2 * HOUR;
        // "Party > [MVP+] Paul: [Kung] Jerry rotation: ..." with the sender's own colours.
        Component message = Component.literal("Party > ").withColor(0x5555FF)
            .append(Component.literal("[MVP+] Paul").withColor(0x55FFFF))
            .append(Component.literal(": [Kung] Jerry rotation: Aatrox (switches in 4h 0m) > Cole > Marina").withColor(0xFFFFFF));
        Component decorated = JerryMessages.decorateRotation(message, slots, now, UTC);
        // The words stay exactly as they were.
        assertEquals(message.getString(), decorated.getString());
        for (String mayor : List.of("Aatrox", "Cole", "Marina")) {
            Part part = part(decorated, mayor);
            assertNotNull(mayor, part.style().getHoverEvent());
            assertTrue(((HoverEvent.ShowText) part.style().getHoverEvent()).value().getString().startsWith(mayor + "'s terms"));
            assertEquals(Integer.valueOf(0xFFFFFF), part.color()); // the sender's colour is kept
        }
        // The sender is called Paul too: his name is before the marker and gets nothing.
        assertNull(part(decorated, "[MVP+] Paul").style().getHoverEvent());
        assertEquals(Integer.valueOf(0x55FFFF), part(decorated, "[MVP+] Paul").color());
    }

    @Test
    public void aRepliesNameThatIsNoMayorOrWithoutScheduleStaysUntouched() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        Component other = Component.literal("Party > Paul: !jerry Aatrox");
        assertSame(other, JerryMessages.decorateRotation(other, slots, START, UTC));
        Component reply = Component.literal("Party > Beng114: [Kung] Jerry rotation: Aatrox (switches in 4h 0m) > Cole");
        assertSame(reply, JerryMessages.decorateRotation(reply, List.of(), START, UTC)); // we know nothing: no hover to offer
        // An unknown slot ("?") has nothing to hover; known ones around it do.
        Component partial = JerryMessages.decorateRotation(Component.literal("[Kung] Jerry rotation: Aatrox > ? > Cole"), slots, START, UTC);
        assertEquals("[Kung] Jerry rotation: Aatrox > ? > Cole", partial.getString());
        assertNotNull(part(partial, "Cole").style().getHoverEvent());
        assertNull(part(partial, " > ? > ").style().getHoverEvent());
    }

    @Test
    public void theSwitchMessageNamesBothMayorsAndPointsToTheCommand() {
        List<Slot> slots = JerryTimeline.slots(full(), YEAR);
        long now = START + 6 * HOUR + 60_000L;
        Component message = JerryMessages.switched("Aatrox", "Cole", slots, now, UTC);
        assertEquals("Mayor changed: Aatrox > Cole. Do /kung jerry for more information about the mayor rotation.", body(message));
        assertEquals(Integer.valueOf(JerryMessages.PAST), part(message, "Aatrox").color());
        assertEquals(Integer.valueOf(JerryMessages.ACTIVE), part(message, "Cole").color());
        assertNotNull(part(message, "Aatrox").style().getHoverEvent());
        assertNotNull(part(message, "Cole").style().getHoverEvent());
        assertEquals(new ClickEvent.RunCommand("/kung jerry"), part(message, "/kung jerry").style().getClickEvent());
        assertEquals("Mayor changed: ? > Cole. Do /kung jerry for more information about the mayor rotation.",
            body(JerryMessages.switched(null, "Cole", slots, now, UTC)));
    }
}
