package com.github.beng420.kung.feature.garden;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test-side writer of Sky Mutations share links. The planner only reads links; its screen copies a {@code KGH1:} key.
 * Compressor port of lz-string by pieroxy (MIT License, Copyright (c) 2013 Pieroxy), URI-safe variant.
 */
final class GreenhouseLinkEncoder {
    static final String URL = "https://skymutations.eu/greenhouse?layout=";
    // The site writes its JSON with JSON.stringify, which does not escape =, & or quotes like Gson's default does.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private GreenhouseLinkEncoder() { }

    /** The share link for a grid. */
    static String encode(GreenhouseGrid grid) {
        return URL + compress(GSON.toJson(GreenhouseLayoutCodec.entries(grid)));
    }

    /** Never null; the empty string compresses to a single terminator character. */
    static String compress(String input) {
        if (input == null) return "";
        Encoder encoder = new Encoder();
        String w = "";
        for (int i = 0; i < input.length(); i++) {
            String c = String.valueOf(input.charAt(i));
            if (!encoder.dictionary.containsKey(c)) {
                encoder.dictionary.put(c, encoder.dictSize++);
                encoder.pending.add(c);
            }
            String wc = w + c;
            if (encoder.dictionary.containsKey(wc)) {
                w = wc;
                continue;
            }
            encoder.emit(w);
            encoder.dictionary.put(wc, encoder.dictSize++);
            w = c;
        }
        if (!w.isEmpty()) encoder.emit(w);
        encoder.write(2, encoder.numBits); // end of stream
        encoder.flush();
        return encoder.text.toString();
    }

    /** Dictionary state plus the bit packer: codes go out least significant bit first, 6 bits per character. */
    private static final class Encoder {
        final Map<String, Integer> dictionary = new HashMap<>();
        final Set<String> pending = new HashSet<>(); // single characters not yet written out literally
        final StringBuilder text = new StringBuilder();
        int dictSize = 3;
        int numBits = 2;
        private int enlargeIn = 2; // the first entry is not counted
        private int value;
        private int position;

        /** Code of w: a literal on first sight (marker 0 = 8 bit, 1 = 16 bit), else its dictionary index. */
        void emit(String w) {
            if (pending.remove(w)) {
                char literal = w.charAt(0);
                write(literal < 256 ? 0 : 1, numBits);
                write(literal, literal < 256 ? 8 : 16);
                grow();
            } else {
                write(dictionary.get(w), numBits);
            }
            grow();
        }

        private void grow() {
            if (--enlargeIn == 0) {
                enlargeIn = 1 << numBits;
                numBits++;
            }
        }

        void write(int bits, int count) {
            for (int i = 0; i < count; i++) {
                value = (value << 1) | (bits & 1);
                bits >>= 1;
                if (position == 5) {
                    position = 0;
                    text.append(LZString.ALPHABET.charAt(value));
                    value = 0;
                } else {
                    position++;
                }
            }
        }

        void flush() {
            while (true) {
                value <<= 1;
                if (position == 5) {
                    text.append(LZString.ALPHABET.charAt(value));
                    return;
                }
                position++;
            }
        }
    }
}
