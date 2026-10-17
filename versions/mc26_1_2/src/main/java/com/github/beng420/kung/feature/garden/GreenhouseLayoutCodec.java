package com.github.beng420.kung.feature.garden;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Sky Mutations share links: {@code https://skymutations.eu/greenhouse?layout=<lz-string of JSON>},
 * where the JSON is a list of {@code [row, column, "Crop name", flag]} entries.
 */
final class GreenhouseLayoutCodec {
    static final String URL = "https://skymutations.eu/greenhouse?layout=";
    /** A full 10x10 layout is about a thousand characters; this also bounds what a paste may decompress to. */
    static final int MAX_LINK_LENGTH = 8192;
    /** The longest crop name the site has is 19 characters; anything much longer is not a crop. */
    static final int MAX_NAME_LENGTH = 40;
    // The site writes its JSON with JSON.stringify, which does not escape =, & or quotes like Gson's default does.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private GreenhouseLayoutCodec() { }

    /**
     * Accepts the full URL, any text holding {@code layout=} as a query parameter, or the bare encoded
     * string. Empty when the text is no layout; never throws.
     */
    static Optional<GreenhouseGrid> decode(String text) {
        if (text == null || text.length() > MAX_LINK_LENGTH) return Optional.empty();
        try {
            String encoded = text.strip().replaceAll("[\\r\\n\\t]", "");
            // '=' is not in the encoding alphabet, so the marker cannot occur inside a bare layout.
            int start = encoded.indexOf("layout=");
            if (start >= 0) encoded = encoded.substring(start + "layout=".length());
            int end = encoded.indexOf('&');
            if (end >= 0) encoded = encoded.substring(0, end);
            end = encoded.indexOf('#');
            if (end >= 0) encoded = encoded.substring(0, end);
            // Percent escapes turn + into a space, which the decompressor takes back.
            if (encoded.indexOf('%') >= 0) encoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            String json = LZString.decompressFromEncodedURIComponent(encoded);
            if (json == null || json.isEmpty()) return Optional.empty();
            return Optional.of(parse(JsonParser.parseString(json).getAsJsonArray()));
        } catch (RuntimeException invalid) {
            // Gson and the helpers below signal every kind of malformed layout with runtime exceptions.
            return Optional.empty();
        }
    }

    static String encode(GreenhouseGrid grid) {
        return URL + LZString.compressToEncodedURIComponent(GSON.toJson(entries(grid)));
    }

    /** The grid as the site's list of {@code [row, column, "Name", flag]} entries, row-major. */
    static JsonArray entries(GreenhouseGrid grid) {
        JsonArray entries = new JsonArray();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell == null) continue;
                JsonArray entry = new JsonArray();
                entry.add(row);
                entry.add(column);
                entry.add(cell.name());
                entry.add(cell.flag());
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * The grid for a list of entries, with the same rules as a pasted link (positions inside the grid, a plausible
     * crop name, a non-negative flag). Any bad entry throws a runtime exception; callers that load stored data
     * treat that as "no layout".
     */
    static GreenhouseGrid parse(JsonArray entries) {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (JsonElement element : entries) {
            JsonArray entry = element.getAsJsonArray();
            if (entry.size() < 3) throw new IllegalArgumentException("Entry too short");
            int row = integer(entry.get(0), 0, GreenhouseGrid.SIZE - 1);
            int column = integer(entry.get(1), 0, GreenhouseGrid.SIZE - 1);
            if (!(entry.get(2) instanceof JsonPrimitive name) || !name.isString() || !plausible(name.getAsString())) {
                throw new IllegalArgumentException("Entry without a crop name");
            }
            int flag = entry.size() > 3 ? integer(entry.get(3), 0, Integer.MAX_VALUE) : GreenhouseGrid.INGREDIENT;
            grid.set(row, column, new GreenhouseGrid.Cell(name.getAsString(), flag));
        }
        return grid;
    }

    /**
     * A name the screens can label: at most {@link #MAX_NAME_LENGTH} characters with at least one letter or
     * digit, so a name of only separators ("-", "_") cannot reach the abbreviation drawn in a cell.
     */
    private static boolean plausible(String name) {
        return name.length() <= MAX_NAME_LENGTH && name.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static int integer(JsonElement element, int min, int max) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Not a number");
        }
        double value = primitive.getAsDouble();
        if (value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException("Number out of range");
        }
        return (int) value;
    }
}
