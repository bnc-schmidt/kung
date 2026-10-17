package com.github.beng420.kung.feature.garden;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Kung's own layout key: {@code KGH1:} plus URL-safe Base64 (no padding) of a zlib stream holding
 * {@code [version=1]} and then 100 cells in row-major order, two bytes each: the crop (0 = empty, otherwise
 * 1 + the index into {@link GreenhouseCrops#NAMES}) and the flag (0..2). The zlib trailer (Adler-32) is what
 * notices a damaged key, so a typo is rejected instead of drawing a different layout. (A mostly empty layout has
 * several valid spellings of its zero runs; a typo that lands on one decodes to the same grid, which is harmless.)
 *
 * <p>The crop index is a position in {@code NAMES}: that list may only grow at its end, or old keys would
 * silently change their crops.
 */
final class GreenhouseCode {
    static final String PREFIX = "KGH1:";
    /** A full key is about 300 characters; this bounds what a clipboard may make us scan. */
    static final int MAX_TEXT_LENGTH = 16 * 1024;
    private static final int VERSION = 1;
    private static final int CELLS = GreenhouseGrid.SIZE * GreenhouseGrid.SIZE;
    private static final int PAYLOAD = 1 + CELLS * 2;

    private GreenhouseCode() { }

    /**
     * The key for a grid. A crop that is not in {@link GreenhouseCrops#NAMES} cannot be represented and is
     * written as an empty cell; {@link #unrepresentable} counts those so a caller can warn.
     */
    static String encode(GreenhouseGrid grid) {
        byte[] payload = new byte[PAYLOAD];
        payload[0] = VERSION;
        List<String> names = GreenhouseCrops.NAMES;
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                int crop = cell == null ? -1 : names.indexOf(cell.name());
                if (crop < 0 || cell.flag() < 0 || cell.flag() > GreenhouseGrid.BONUS) continue;
                int at = 1 + (row * GreenhouseGrid.SIZE + column) * 2;
                payload[at] = (byte) (crop + 1);
                payload[at + 1] = (byte) cell.flag();
            }
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(payload));
    }

    /** Cells {@link #encode} has to drop: an unknown crop name or a flag outside 0..2. */
    static int unrepresentable(GreenhouseGrid grid) {
        int dropped = 0;
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && (!GreenhouseCrops.NAMES.contains(cell.name()) || cell.flag() < 0
                    || cell.flag() > GreenhouseGrid.BONUS)) dropped++;
            }
        }
        return dropped;
    }

    /**
     * A Kung key anywhere in the text (junk lines or words around it are ignored), else a Sky Mutations link
     * or bare layout string. Empty when the text holds neither; never throws.
     */
    static Optional<GreenhouseGrid> decode(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) return Optional.empty();
        try {
            int start = text.indexOf(PREFIX);
            // A damaged key is not retried as a site link: that would only turn one error into another.
            if (start >= 0) return decodeKey(token(text, start + PREFIX.length()));
            return GreenhouseLayoutCodec.decode(text);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String token(String text, int from) {
        int end = from;
        while (end < text.length() && isBase64Url(text.charAt(end))) end++;
        return text.substring(from, end);
    }

    private static boolean isBase64Url(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }

    private static Optional<GreenhouseGrid> decodeKey(String token) {
        if (token.isEmpty()) return Optional.empty();
        byte[] compressed = Base64.getUrlDecoder().decode(token);
        // Only the canonical spelling counts: the decoder ignores spare bits in the last character.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(compressed).equals(token)) return Optional.empty();
        byte[] payload = inflate(compressed);
        if (payload == null || payload.length != PAYLOAD || payload[0] != VERSION) return Optional.empty();
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int index = 0; index < CELLS; index++) {
            int crop = payload[1 + index * 2] & 0xFF;
            int flag = payload[2 + index * 2] & 0xFF;
            if (crop > GreenhouseCrops.NAMES.size() || flag > GreenhouseGrid.BONUS) return Optional.empty();
            if (crop == 0) continue;
            grid.set(index / GreenhouseGrid.SIZE, index % GreenhouseGrid.SIZE,
                new GreenhouseGrid.Cell(GreenhouseCrops.NAMES.get(crop - 1), flag));
        }
        return Optional.of(grid);
    }

    private static byte[] deflate(byte[] payload) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION); // zlib header and Adler-32 trailer.
        try {
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Exactly {@link #PAYLOAD} bytes or null: a stream that wants to be bigger is refused, not expanded. */
    private static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[PAYLOAD + 1];
            int length = 0;
            while (!inflater.finished() && length < out.length) {
                int read = inflater.inflate(out, length, out.length - length);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null;
                length += read;
            }
            // Bytes after the stream's end are garbage, not part of the key.
            if (!inflater.finished() || inflater.getRemaining() != 0) return null;
            return Arrays.copyOf(out, length);
        } catch (DataFormatException invalid) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
