package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Random;
import org.junit.Test;

public final class LZStringTest {
    private static String roundTrip(String text) {
        return LZString.decompressFromEncodedURIComponent(LZString.compressToEncodedURIComponent(text));
    }

    @Test
    public void matchesOutputOfTheReferenceImplementation() {
        // Captured from the lz-string reference algorithm, so links stay readable by the website.
        assertEquals("Q", LZString.compressToEncodedURIComponent(""));
        assertEquals("IZA", LZString.compressToEncodedURIComponent("a"));
        assertEquals("BYUwNmD2AEoTcrQO6QE5gCZA", LZString.compressToEncodedURIComponent("hello hello hello world"));
        assertEquals("NrAMBoIIgdQCwKYEMAuACAyghATAzlOAIwC64Yx4UAwkgE50D2KhoJJQA",
            LZString.compressToEncodedURIComponent("[[0,0,\"Wheat Seeds\",1],[0,1,\"Carrot\",0]]"));
        assertEquals("CeGoJLRyQ", LZString.compressToEncodedURIComponent("ä€中"));
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
        String compressed = LZString.compressToEncodedURIComponent("[[1,2,\"Do-not-eat-shroom\",0]] € & = ?");
        assertEquals(compressed.replaceAll("[A-Za-z0-9+$-]", ""), "");
    }

    @Test
    public void spacesStandInForPlusSigns() {
        String json = "[[0,0,\"Cactus\",1],[0,1,\"Cactus\",1],[0,2,\"Cactus\",1]]";
        String compressed = LZString.compressToEncodedURIComponent(json);
        assertEquals("NrAMBoIIgYQQwMYBcCuBnK4CMBdcZtxZFUNs8CAmI+ZdTXHIA", compressed);
        assertEquals(json, LZString.decompressFromEncodedURIComponent(compressed.replace('+', ' ')));
    }

    @Test
    public void rejectsInputThatIsNotACompleteStream() {
        assertNull(LZString.decompressFromEncodedURIComponent(""));
        assertNull(LZString.decompressFromEncodedURIComponent("not a layout!"));
        assertNull(LZString.decompressFromEncodedURIComponent("NrAMBoIIgdQC.wKYEM"));
        String compressed = LZString.compressToEncodedURIComponent("[[0,0,\"Wheat Seeds\",1],[0,1,\"Carrot\",0]]");
        assertNull(LZString.decompressFromEncodedURIComponent(compressed.substring(0, compressed.length() / 2)));
    }
}
