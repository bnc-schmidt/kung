package com.github.beng420.kung.util;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.DebugConfig;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class KungRegionTest {
    private static final Instant SUMMER = Instant.parse("2026-10-23T12:00:00Z"); // CEST, EDT
    private static final Instant WINTER = Instant.parse("2026-12-01T12:00:00Z"); // CET, EST

    @Test
    public void aPickedZoneWinsAndAnythingUnreadableFallsBackToDetection() {
        assertEquals(ZoneId.of("Europe/Berlin"), KungTime.zone("Europe/Berlin"));
        assertEquals(ZoneId.of("America/New_York"), KungTime.zone(" America/New_York "));
        assertEquals(ZoneId.systemDefault(), KungTime.zone(""));
        assertEquals(ZoneId.systemDefault(), KungTime.zone(null));
        assertEquals(ZoneId.systemDefault(), KungTime.zone("Mars/Olympus_Mons")); // a typo in the config file
    }

    @Test
    public void offsetsReadAsGmtWithTheNowValidDaylightSavingOffset() {
        assertEquals("GMT+2", KungTime.gmt(ZoneId.of("Europe/Berlin"), SUMMER));
        assertEquals("GMT+1", KungTime.gmt(ZoneId.of("Europe/Berlin"), WINTER));
        assertEquals("GMT-4", KungTime.gmt(ZoneId.of("America/New_York"), SUMMER));
        assertEquals("GMT-5", KungTime.gmt(ZoneId.of("America/New_York"), WINTER));
        assertEquals("GMT+5:30", KungTime.gmt(ZoneId.of("Asia/Kolkata"), SUMMER));
        assertEquals("GMT+5:45", KungTime.gmt(ZoneId.of("Asia/Kathmandu"), SUMMER));
        assertEquals("GMT-2:30", KungTime.gmt(ZoneId.of("America/St_Johns"), SUMMER));
        assertEquals("GMT+0", KungTime.gmt(ZoneId.of("UTC"), SUMMER));
    }

    @Test
    public void everyZoneIsListedOnceUnderItsOfficialAbbreviationAndOffset() {
        Instant january = Instant.parse("2026-01-15T12:00:00Z");
        Instant july = Instant.parse("2026-07-15T12:00:00Z");
        var ids = new HashSet<String>();
        var offsets = new HashSet<String>();
        for (Instant at : List.of(january, july)) {
            var labels = new HashSet<String>();
            for (String id : KungTime.ZONES) {
                String label = KungTime.label(id, at);
                assertTrue(label, label.matches("^[A-Z]{2,5} \\(GMT[+-]\\d{1,2}(?::\\d\\d)?\\)$"));
                assertTrue(label + " twice", labels.add(label));
                assertEquals(id, KungTime.idOf(label, at));
            }
        }
        for (String id : KungTime.ZONES) {
            assertTrue(id + " twice", ids.add(id));
            // The same zone under another name would be the same pair of offsets, summer and winter.
            var rules = ZoneId.of(id).getRules();
            assertTrue(id + " duplicates another entry", offsets.add(rules.getOffset(january) + "/" + rules.getOffset(july)));
        }
        assertTrue(KungTime.ZONES.size() <= 30);
    }

    @Test
    public void namesFollowDaylightSavingAndFillInWhereJavaHasNone() {
        assertEquals("CEST (GMT+2)", KungTime.label("Europe/Berlin", SUMMER));
        assertEquals("CET (GMT+1)", KungTime.label("Europe/Berlin", WINTER));
        assertEquals("EDT (GMT-4)", KungTime.label("America/New_York", SUMMER));
        assertEquals("EST (GMT-5)", KungTime.label("America/New_York", WINTER));
        assertEquals("BST (GMT+1)", KungTime.label("Europe/London", SUMMER));
        assertEquals("GMT (GMT+0)", KungTime.label("Europe/London", WINTER));
        assertEquals("UTC (GMT+0)", KungTime.label("UTC", SUMMER));
        assertEquals("IST (GMT+5:30)", KungTime.label("Asia/Kolkata", SUMMER));
        assertEquals("NDT (GMT-2:30)", KungTime.label("America/St_Johns", SUMMER));
        // Java prints the offset for these; the official names come from a table.
        assertEquals("BRT (GMT-3)", KungTime.label("America/Sao_Paulo", SUMMER));
        assertEquals("GST (GMT+4)", KungTime.label("Asia/Dubai", SUMMER));
        assertEquals("ICT (GMT+7)", KungTime.label("Asia/Bangkok", SUMMER));
        assertEquals("NPT (GMT+5:45)", KungTime.label("Asia/Kathmandu", SUMMER));
        assertEquals("IRST (GMT+3:30)", KungTime.label("Asia/Tehran", SUMMER));
        // A zone with no official name shows only its offset.
        assertEquals("GMT+3", KungTime.named("Asia/Riyadh", SUMMER));
    }

    @Test
    public void theListRunsFromWestToEastByStandardOffset() {
        int previous = Integer.MIN_VALUE;
        for (String id : KungTime.ZONES) {
            int standard = ZoneId.of(id).getRules().getStandardOffset(SUMMER).getTotalSeconds();
            assertTrue(id + " is out of order", standard >= previous);
            previous = standard;
        }
    }

    @Test
    public void autoComesFirstAndACustomZoneFromTheConfigFileStaysSelectable() {
        List<String> plain = KungTime.options("", SUMMER);
        assertTrue(plain.getFirst().startsWith("Auto: "));
        assertEquals(KungTime.ZONES.size() + 1, plain.size());
        assertEquals(plain.getFirst(), KungTime.selected("", SUMMER));
        assertEquals(plain.getFirst(), KungTime.selected("Mars/Olympus_Mons", SUMMER)); // invalid: shows Auto

        assertEquals("CEST (GMT+2)", KungTime.selected("Europe/Berlin", SUMMER));
        assertTrue(plain.contains("CEST (GMT+2)"));
        // Not in the list, but valid: offered right after Auto under its ID, and selected.
        List<String> custom = KungTime.options("Europe/Paris", SUMMER);
        assertEquals(KungTime.ZONES.size() + 2, custom.size());
        assertEquals("Europe/Paris (GMT+2)", custom.get(1));
        assertEquals("Europe/Paris (GMT+2)", KungTime.selected("Europe/Paris", SUMMER));

        // Choosing any entry gives back the zone ID to store, and Auto gives blank.
        assertEquals("Europe/Berlin", KungTime.idOf("CEST (GMT+2)", SUMMER));
        assertEquals("Europe/Paris", KungTime.idOf("Europe/Paris (GMT+2)", SUMMER));
        assertEquals("", KungTime.idOf(plain.getFirst(), SUMMER));
        assertEquals("", KungTime.idOf(null, SUMMER));
        assertEquals("", KungTime.idOf("nonsense", SUMMER));
        for (String choice : custom) {
            assertEquals(choice, choice.startsWith("Auto") ? "" : KungTime.label(KungTime.idOf(choice, SUMMER), SUMMER), choice.startsWith("Auto") ? "" : choice);
        }
    }

    @Test
    public void theDebugConfigKeepsZoneAndLayoutAndFallsBackToAuto() {
        var debug = new DebugConfig();
        assertEquals("", debug.timeZone());
        assertEquals(KeyboardLayout.AUTO, debug.keyboardLayout());
        debug.setTimeZone("  Europe/Berlin ");
        assertEquals("Europe/Berlin", debug.timeZone());
        debug.setTimeZone(null);
        assertEquals("", debug.timeZone());
        debug.setKeyboardLayout(KeyboardLayout.QWERTZ);
        assertEquals(KeyboardLayout.QWERTZ, debug.keyboardLayout());
        debug.setKeyboardLayout(null);
        assertEquals(KeyboardLayout.AUTO, debug.keyboardLayout());
    }

    @Test
    public void qwertzSwapsYAndZAndNothingElse() {
        var layout = KeyboardLayout.QWERTZ;
        assertEquals("Z", layout.letterOn('Y'));
        assertEquals("Y", layout.letterOn('Z'));
        assertEquals("A", layout.letterOn('A'));
        assertEquals("Q", layout.letterOn('Q'));
        assertEquals(90, layout.keyLabelled("Y")); // the key labelled Y is GLFW code 90 (the US Z)
        assertEquals(89, layout.keyLabelled("Z"));
        assertEquals(87, layout.keyLabelled("W"));
        assertNull(layout.letterOn('1'));
        assertNull(layout.letterOn(';'));
    }

    @Test
    public void azertySwapsAQAndWZAndMovesM() {
        var layout = KeyboardLayout.AZERTY;
        assertEquals("Q", layout.letterOn('A'));
        assertEquals("A", layout.letterOn('Q'));
        assertEquals("Z", layout.letterOn('W'));
        assertEquals("W", layout.letterOn('Z'));
        assertEquals(",", layout.letterOn('M'));
        assertEquals("M", layout.letterOn(59)); // the US semicolon key
        assertEquals("Y", layout.letterOn('Y'));
        assertEquals(89, layout.keyLabelled("Y"));
        assertEquals(59, layout.keyLabelled("M"));
        assertEquals(81, layout.keyLabelled("A")); // the A sits where the US Q is
    }

    @Test
    public void qwertyAndAutoLeaveTheSystemInChargeOfWhatTheyDoNotKnow() {
        assertEquals("Y", KeyboardLayout.QWERTY.letterOn('Y'));
        assertEquals(89, KeyboardLayout.QWERTY.keyLabelled("Y"));
        assertNull(KeyboardLayout.QWERTY.letterOn(59));
        // Auto answers nothing: the labels come from GLFW as before.
        assertNull(KeyboardLayout.AUTO.letterOn('Y'));
        assertEquals(-1, KeyboardLayout.AUTO.keyLabelled("Y"));
        assertEquals(List.of("Auto", "QWERTY (US)", "QWERTZ (German)", "AZERTY (French)"),
            java.util.Arrays.stream(KeyboardLayout.values()).map(KeyboardLayout::label).toList());
    }
}
