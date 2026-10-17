package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.Test;

public final class GreenhouseCodeTest {
    private static GreenhouseGrid sample() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET));
        grid.set(4, 7, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(9, 9, new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS));
        return grid;
    }

    private static GreenhouseGrid decoded(String text) {
        Optional<GreenhouseGrid> grid = GreenhouseCode.decode(text);
        assertTrue("expected a layout in: " + text, grid.isPresent());
        return grid.get();
    }

    /** A key around a hand-made payload, for the corrupt shapes the encoder never writes. */
    private static String keyOf(byte[] payload) {
        Deflater deflater = new Deflater();
        deflater.setInput(payload);
        deflater.finish();
        byte[] buffer = new byte[payload.length + 64];
        int length = deflater.deflate(buffer);
        deflater.end();
        return GreenhouseCode.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Arrays.copyOf(buffer, length));
    }

    private static byte[] payload(int version) {
        byte[] payload = new byte[201];
        payload[0] = (byte) version;
        return payload;
    }

    @Test
    public void roundTripsEveryCropAndFlag() {
        GreenhouseGrid grid = new GreenhouseGrid();
        int index = 0;
        for (String name : GreenhouseCrops.NAMES) {
            grid.set(index / GreenhouseGrid.SIZE, index % GreenhouseGrid.SIZE,
                new GreenhouseGrid.Cell(name, index % 3));
            index++;
        }

        String key = GreenhouseCode.encode(grid);

        assertTrue(key.startsWith(GreenhouseCode.PREFIX));
        assertEquals(grid, decoded(key));
        assertEquals(0, GreenhouseCode.unrepresentable(grid));
    }

    @Test
    public void roundTripsAnEmptyGridAndKeepsPositions() {
        assertEquals(new GreenhouseGrid(), decoded(GreenhouseCode.encode(new GreenhouseGrid())));
        GreenhouseGrid grid = sample();
        GreenhouseGrid back = decoded(GreenhouseCode.encode(grid));
        assertEquals(new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET), back.get(0, 0));
        assertEquals(new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT), back.get(4, 7));
        assertEquals(new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS), back.get(9, 9));
        assertEquals(3, back.filled());
    }

    @Test
    public void aFullLayoutKeyStaysShortAndUrlSafe() {
        GreenhouseGrid grid = dense();

        String key = GreenhouseCode.encode(grid);

        assertTrue("key is " + key.length() + " characters", key.length() < 400);
        assertTrue(key.matches("KGH1:[A-Za-z0-9_-]+"));
        assertEquals(grid, decoded(key));
    }

    private static GreenhouseGrid dense() {
        Random random = new Random(11);
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell(
                    GreenhouseCrops.NAMES.get(random.nextInt(GreenhouseCrops.NAMES.size())), random.nextInt(3)));
            }
        }
        return grid;
    }

    /** Returns how many flips were rejected; a flip is never allowed to show a different layout. */
    private static int rejectedFlips(GreenhouseGrid grid) {
        String key = GreenhouseCode.encode(grid);
        int rejected = 0;
        for (int at = GreenhouseCode.PREFIX.length(); at < key.length(); at++) {
            char flipped = key.charAt(at) == 'A' ? 'B' : 'A';
            String damaged = key.substring(0, at) + flipped + key.substring(at + 1);
            Optional<GreenhouseGrid> back = GreenhouseCode.decode(damaged);
            // A mostly empty layout is long runs of zeros, which deflate can spell several ways: a flip into
            // another spelling decodes to the same bytes, so it is harmless. Anything else must fail the checks.
            assertTrue("position " + at, back.isEmpty() || back.get().equals(grid));
            if (back.isEmpty()) rejected++;
        }
        return rejected;
    }

    @Test
    public void aDamagedKeyIsRejectedOrDecodesToTheSameLayout() {
        int keyLength = GreenhouseCode.encode(dense()).length() - GreenhouseCode.PREFIX.length();
        // Dense data has one spelling: practically every flip is caught by the stream's checksum.
        assertTrue(rejectedFlips(dense()) >= keyLength - 2);
        assertTrue(rejectedFlips(sample()) > 0);
        assertTrue(rejectedFlips(new GreenhouseGrid()) > 0);
    }

    @Test
    public void aTruncatedKeyOrOneWithTrailingBytesIsRejected() {
        String key = GreenhouseCode.encode(sample());
        assertTrue(GreenhouseCode.decode(key.substring(0, key.length() - 4)).isEmpty());
        byte[] compressed = Base64.getUrlDecoder().decode(key.substring(GreenhouseCode.PREFIX.length()));
        byte[] padded = Arrays.copyOf(compressed, compressed.length + 3);
        String withTail = GreenhouseCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(padded);
        assertTrue(GreenhouseCode.decode(withTail).isEmpty());
    }

    @Test
    public void garbageEmptyAndOversizedTextAreRejected() {
        assertTrue(GreenhouseCode.decode(null).isEmpty());
        assertTrue(GreenhouseCode.decode("").isEmpty());
        assertTrue(GreenhouseCode.decode("   \n\t ").isEmpty());
        assertTrue(GreenhouseCode.decode("hello world").isEmpty());
        assertTrue(GreenhouseCode.decode("KGH1:").isEmpty());
        assertTrue(GreenhouseCode.decode("KGH1:!!!").isEmpty());
        assertTrue(GreenhouseCode.decode("KGH1:AAAA").isEmpty());
        assertTrue(GreenhouseCode.decode("KGH2:" + GreenhouseCode.encode(sample()).substring(5)).isEmpty());
        assertTrue(GreenhouseCode.decode("A".repeat(GreenhouseCode.MAX_TEXT_LENGTH + 1)).isEmpty());
        assertTrue(GreenhouseCode.decode(GreenhouseCode.encode(sample()) + " ".repeat(GreenhouseCode.MAX_TEXT_LENGTH))
            .isEmpty());
    }

    @Test
    public void aStreamThatInflatesToMoreThanALayoutIsRefused() {
        assertTrue(GreenhouseCode.decode(keyOf(new byte[2_000_000])).isEmpty());
    }

    @Test
    public void wrongVersionSizeIndexOrFlagIsRejected() {
        assertTrue(GreenhouseCode.decode(keyOf(payload(1))).isPresent());
        assertTrue(GreenhouseCode.decode(keyOf(payload(0))).isEmpty());
        assertTrue(GreenhouseCode.decode(keyOf(payload(2))).isEmpty());
        assertTrue(GreenhouseCode.decode(keyOf(Arrays.copyOf(payload(1), 200))).isEmpty());
        assertTrue(GreenhouseCode.decode(keyOf(Arrays.copyOf(payload(1), 202))).isEmpty());

        byte[] badCrop = payload(1);
        badCrop[1] = (byte) (GreenhouseCrops.NAMES.size() + 1);
        assertTrue(GreenhouseCode.decode(keyOf(badCrop)).isEmpty());
        byte[] lastCrop = payload(1);
        lastCrop[1] = (byte) GreenhouseCrops.NAMES.size();
        lastCrop[2] = 1;
        assertTrue(GreenhouseCode.decode(keyOf(lastCrop)).isPresent());
        byte[] hugeCrop = payload(1);
        hugeCrop[1] = (byte) 200;
        assertTrue(GreenhouseCode.decode(keyOf(hugeCrop)).isEmpty());
        byte[] badFlag = payload(1);
        badFlag[2] = 3;
        assertTrue(GreenhouseCode.decode(keyOf(badFlag)).isEmpty());
        byte[] negativeFlag = payload(1);
        negativeFlag[2] = (byte) 0xFF;
        assertTrue(GreenhouseCode.decode(keyOf(negativeFlag)).isEmpty());
    }

    @Test
    public void unknownCropsAreDroppedAndCounted() {
        GreenhouseGrid grid = sample();
        grid.set(2, 2, new GreenhouseGrid.Cell("Brand New Crop", GreenhouseGrid.INGREDIENT));
        grid.set(3, 3, new GreenhouseGrid.Cell("Carrot", 7));

        assertEquals(2, GreenhouseCode.unrepresentable(grid));
        GreenhouseGrid back = decoded(GreenhouseCode.encode(grid));
        assertNull(back.get(2, 2));
        assertNull(back.get(3, 3));
        assertEquals(3, back.filled());
        assertEquals(sample(), back);
    }

    @Test
    public void junkAroundTheKeyAndWhitespaceAreIgnored() {
        GreenhouseGrid grid = sample();
        String key = GreenhouseCode.encode(grid);

        assertEquals(grid, decoded("  \r\n" + key + " \t\r\n"));
        assertEquals(grid, decoded("my greenhouse!\n" + key + "\nthanks for looking"));
        assertEquals(grid, decoded("layout (" + key + "), enjoy"));
        assertEquals(grid, decoded("Check this: " + key));
    }

    @Test
    public void aSkyMutationsLinkOrBareLayoutStringAlsoWorks() {
        GreenhouseGrid grid = sample();
        String link = GreenhouseLayoutCodec.encode(grid);

        assertEquals(grid, decoded(link));
        assertEquals(grid, decoded(link.substring(GreenhouseLayoutCodec.URL.length())));
        assertEquals(grid, decoded("see\n" + link + "\n"));
    }

    @Test
    public void aDamagedKeyIsNotRetriedAsASiteLink() {
        String key = GreenhouseCode.encode(sample());
        String damaged = key.substring(0, 20) + (key.charAt(20) == 'A' ? 'B' : 'A') + key.substring(21);

        assertFalse(GreenhouseCode.decode(damaged).isPresent());
        assertNotEquals(key, damaged);
    }
}
