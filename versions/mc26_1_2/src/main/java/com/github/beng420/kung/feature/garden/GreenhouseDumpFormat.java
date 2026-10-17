package com.github.beng420.kung.feature.garden;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure parts of {@code /kung greenhouse dump}: where a world position falls on the grid, what a head's
 * texture property says, and a size-capped text body. No game classes, so they are unit-tested.
 */
final class GreenhouseDumpFormat {
    /** Everything the file may hold; the checklist is written even when the rest was cut. */
    static final int MAX_CHARS = 300_000;
    private static final int CHECKLIST_RESERVE = 4_000;
    private static final int MAX_VALUE_CHARS = 400;
    private static final Pattern URL = Pattern.compile("\"?url\"?\\s*:\\s*\"([^\"]*)\"");

    /**
     * A position relative to the grid corner. {@code dx}/{@code dz} are whole blocks (floor of the offset, so a
     * position west or north of the corner is negative); {@code row}/{@code column} apply the RAW default mapping
     * ({@code dx = column, dz = row}, no session orientation) and are only meaningful when {@code inGrid}.
     */
    record CellRef(int dx, int dz, boolean inGrid, int row, int column) { }

    /** The skin of a player head: the url, the hash at its end, and the decoded JSON. */
    record Texture(String url, String hash, String json) { }

    private GreenhouseDumpFormat() { }

    static CellRef cell(double x, double z, int cornerX, int cornerZ) {
        int dx = (int) Math.floor(x - cornerX);
        int dz = (int) Math.floor(z - cornerZ);
        boolean inGrid = dx >= 0 && dx < GreenhouseGrid.SIZE && dz >= 0 && dz < GreenhouseGrid.SIZE;
        return new CellRef(dx, dz, inGrid, dz, dx);
    }

    /** The 10x10 area plus one block around it (corner - 1 .. corner + 11) and y 70..82, ends included. */
    static boolean inGridArea(double x, double y, double z, int cornerX, int cornerZ) {
        return x >= cornerX - 1 && x <= cornerX + GreenhouseGrid.SIZE + 1
            && z >= cornerZ - 1 && z <= cornerZ + GreenhouseGrid.SIZE + 1
            && y >= 70 && y <= 82;
    }

    /**
     * The skin url and its hash from a profile's base64 {@code textures} value. Empty when the value does not
     * decode to something with a url; never throws.
     */
    static Optional<Texture> texture(String base64) {
        if (base64 == null || base64.isBlank()) return Optional.empty();
        try {
            String json = new String(Base64.getMimeDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
            Matcher match = URL.matcher(json);
            if (!match.find()) return Optional.empty();
            String url = match.group(1);
            String hash = url.substring(url.lastIndexOf('/') + 1);
            return hash.isEmpty() ? Optional.empty() : Optional.of(new Texture(url, hash, json));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** One line, quoted, without colour codes or control characters, cut to a sane length. */
    static String quote(String value) {
        if (value == null) return "-";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length() && out.length() < MAX_VALUE_CHARS; i++) {
            char c = value.charAt(i);
            if (c == '§') {
                i++; // colour code and its letter
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c < ' ') {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** {@code a=2 b=1}, biggest first, then by name. */
    static String counts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + " " + right).orElse("-");
    }

    /** The questions the dump answers, written last so a cut body still ends with them. */
    static List<String> checklist() {
        return List.of(
            "CHECKLIST - what this file settles:",
            "1. Stand position vs cell centre: 'player cell' (dx, dz and the offset inside the cell) against the"
                + " armor stands' cells; a stand at .5/.5 is a cell centre.",
            "2. Head names and textures per planted crop: 'head:' lines (item, hover name, item name, custom name,"
                + " texture hash, base64) next to the crop you know stands there.",
            "3. Are crops armor stands? Count the armor stands inside the grid and compare with the planted cells;"
                + " crops without a stand show only as blocks in the block table.",
            "4. Soil: farmland/dirt/soul sand ids and properties at y 71..74 per cell, including moisture/age.",
            "5. Pets and pests: 'other entities in the grid area' and the per-type counts for the whole plot.",
            "6. Orientation: compare 'player facing' with where you stand in the grid; cell dx/dz are RAW world"
                + " offsets, row/col use dx = col, dz = row with no rotation or mirror.");
    }

    /** Collects lines up to {@link #MAX_CHARS}; the checklist is appended after, in the reserved space. */
    static final class Body {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        void line(String line) {
            if (truncated) return;
            if (text.length() + line.length() + 1 > MAX_CHARS - CHECKLIST_RESERVE) {
                truncated = true;
                text.append("... truncated: the dump reached its size cap (").append(MAX_CHARS).append(" chars)\n");
                return;
            }
            text.append(line).append('\n');
        }

        boolean truncated() { return truncated; }

        /** The finished text, checklist included. */
        String finish() {
            text.append('\n');
            for (String line : checklist()) text.append(line).append('\n');
            return text.toString();
        }
    }
}
