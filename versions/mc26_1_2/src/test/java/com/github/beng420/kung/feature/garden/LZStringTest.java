package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Random;
import org.junit.Test;

public final class LZStringTest {
    private static String roundTrip(String text) {
        return LZString.decompressFromEncodedURIComponent(GreenhouseLinkEncoder.compress(text));
    }

    @Test
    public void matchesOutputOfTheReferenceImplementation() {
        // Captured from the lz-string reference algorithm, so links stay readable by the website.
        assertEquals("Q", GreenhouseLinkEncoder.compress(""));
        assertEquals("IZA", GreenhouseLinkEncoder.compress("a"));
        assertEquals("BYUwNmD2AEoTcrQO6QE5gCZA", GreenhouseLinkEncoder.compress("hello hello hello world"));
        assertEquals("NrAMBoIIgdQCwKYEMAuACAyghATAzlOAIwC64Yx4UAwkgE50D2KhoJJQA",
            GreenhouseLinkEncoder.compress("[[0,0,\"Wheat Seeds\",1],[0,1,\"Carrot\",0]]"));
        assertEquals("CeGoJLRyQ", GreenhouseLinkEncoder.compress("ä€中"));
    }

    @Test
    public void roundTripsEmptyAsciiUnicodeAndLongInput() {
        assertEquals("", roundTrip(""));
        assertEquals("a", roundTrip("a"));
        assertEquals("hello hello hello world", roundTrip("hello hello hello world"));
        String unicode = "äöüß € 中文 🌱 \u0000 plus+minus-dollar$";
        assertEquals(unicode, roundTrip(unicode));
        assertEquals("a".repeat(5000), roundTrip("a".repeat(5000)));
        Random random = new Random(7);
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 30_000; i++) long_.append((char) random.nextInt(0x3000));
        assertEquals(long_.toString(), roundTrip(long_.toString()));
    }

    @Test
    public void outputOnlyUsesTheUriSafeAlphabet() {
        String compressed = GreenhouseLinkEncoder.compress("[[1,2,\"Do-not-eat-shroom\",0]] € & = ?");
        assertEquals(compressed.replaceAll("[A-Za-z0-9+$-]", ""), "");
    }

    @Test
    public void spacesStandInForPlusSigns() {
        String json = "[[0,0,\"Cactus\",1],[0,1,\"Cactus\",1],[0,2,\"Cactus\",1]]";
        String compressed = GreenhouseLinkEncoder.compress(json);
        assertEquals("NrAMBoIIgYQQwMYBcCuBnK4CMBdcZtxZFUNs8CAmI+ZdTXHIA", compressed);
        assertEquals(json, LZString.decompressFromEncodedURIComponent(compressed.replace('+', ' ')));
    }

    @Test
    public void rejectsInputThatIsNotACompleteStream() {
        assertNull(LZString.decompressFromEncodedURIComponent(""));
        assertNull(LZString.decompressFromEncodedURIComponent("not a layout!"));
        assertNull(LZString.decompressFromEncodedURIComponent("NrAMBoIIgdQC.wKYEM"));
        String compressed = GreenhouseLinkEncoder.compress("[[0,0,\"Wheat Seeds\",1],[0,1,\"Carrot\",0]]");
        assertNull(LZString.decompressFromEncodedURIComponent(compressed.substring(0, compressed.length() / 2)));
    }
}
