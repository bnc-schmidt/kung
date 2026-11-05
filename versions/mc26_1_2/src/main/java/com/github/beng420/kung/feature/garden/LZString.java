package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the URI-safe decompressor of lz-string ({@code decompressFromEncodedURIComponent}), the encoding Sky
 * Mutations uses for share links. Only reading is needed; the tests carry the matching compressor.
 *
 * <p>Algorithm and bit layout: lz-string by pieroxy, MIT License, Copyright (c) 2013 Pieroxy.
 * Written from the published algorithm, independently of any other Java port.
 */
final class LZString {
    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$";

    private LZString() { }

    /**
     * Returns null when the input is empty, uses characters outside the alphabet, or is not a
     * complete stream; plus signs may arrive as spaces, as they do after URL decoding.
     */
    static String decompressFromEncodedURIComponent(String input) {
        if (input == null) return "";
        if (input.isEmpty()) return null;
        input = input.replace(' ', '+');
        for (int i = 0; i < input.length(); i++) {
            if (ALPHABET.indexOf(input.charAt(i)) < 0) return null;
        }
        Reader in = new Reader(input);
        String w;
        switch (in.read(2)) {
            case 0 -> w = String.valueOf((char) in.read(8));
            case 1 -> w = String.valueOf((char) in.read(16));
            case 2 -> { return ""; }
            default -> { return null; }
        }
        List<String> dictionary = new ArrayList<>(List.of("", "", "", w)); // 0..2 are control codes
        StringBuilder result = new StringBuilder(w);
        int enlargeIn = 4, numBits = 3;
        while (true) {
            if (in.index > input.length()) return null; // ran off the end without a terminator
            int code = in.read(numBits);
            switch (code) {
                case 0, 1 -> {
                    dictionary.add(String.valueOf((char) in.read(code == 0 ? 8 : 16)));
                    code = dictionary.size() - 1;
                    enlargeIn--;
                }
                case 2 -> { return result.toString(); }
                default -> { }
            }
            if (enlargeIn == 0) { enlargeIn = 1 << numBits; numBits++; }
            String entry;
            if (code < dictionary.size()) entry = dictionary.get(code);
            else if (code == dictionary.size()) entry = w + w.charAt(0);
            else return null;
            result.append(entry);
            dictionary.add(w + entry.charAt(0));
            enlargeIn--;
            w = entry;
            if (enlargeIn == 0) { enlargeIn = 1 << numBits; numBits++; }
        }
    }

    private static final class Reader {
        private final String input;
        private int value;
        private int mask = 32;
        int index = 1; // next character to load; one past the end means the stream was cut

        Reader(String input) {
            this.input = input;
            value = at(0);
        }

        private int at(int position) {
            return position < input.length() ? ALPHABET.indexOf(input.charAt(position)) : 0;
        }

        int read(int count) {
            int bits = 0;
            for (int i = 0; i < count; i++) {
                if ((value & mask) > 0) bits |= 1 << i;
                mask >>= 1;
                if (mask == 0) {
                    mask = 32;
                    value = at(index++);
                }
            }
            return bits;
        }
    }
}
