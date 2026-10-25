package com.github.beng420.kung.feature.slayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the nametags of the Inferno Demonlord and its demons say about the Hellion Shield: the attunement and the
 * dagger hits left. The boss and each demon carry their own, on a stand next to the body.
 */
final class BlazeShield {
    /** The shield changes its attunement after this many strikes, and a new phase starts at a full count. */
    static final int HITS_PER_ATTUNEMENT = 8;

    enum Attunement {
        ASHEN(0x555555), SPIRIT(0xFFFFFF), CRYSTAL(0x55FFFF), AURIC(0xFFFF55), IMMUNE(0xFF5555);

        final int rgb;

        Attunement(int rgb) {
            this.rgb = rgb;
        }
    }

    /** Who a stand name belongs to. */
    enum Kind { BOSS, DEMON }

    /** {@code hits} is {@link #NO_HITS} while the tag names none: an immune demon, or a format not seen yet. */
    record Reading(Attunement attunement, int hits) {
        static final int NO_HITS = -1;
    }

    private static final Pattern ATTUNEMENT = Pattern.compile("\\b(ASHEN|SPIRIT|CRYSTAL|AURIC|IMMUNE)\\b");
    private static final Pattern HITS =
        Pattern.compile("(?<![\\d.,])(\\d{1,3})\\s*hits?\\b|\\bhits?\\s*[:x]?\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);
    /** "ASHEN ♨8 04:19": a bare number right after the attunement, not a health value (7k, 7❤) or the timer. */
    private static final Pattern BARE_NUMBER = Pattern.compile("^[^\\p{L}\\d]*(\\d{1,3})(?![\\d.,kKmMbB❤%/:])");
    private static final Pattern STRIKE = Pattern.compile("strike using the (ashen|spirit|crystal|auric) attunement");
    private static final Pattern OWNER =Pattern.compile("(?i)spawned by:?\\s*(\\w{1,16})");

    private BlazeShield() {
    }

    /** The shield a stand name shows ("ASHEN ♨ 5 Hits"), or null when it shows none. */
    static Reading parse(String name) {
        Matcher shield = ATTUNEMENT.matcher(name);
        if (!shield.find()) {
            return null;
        }

        Attunement attunement = Attunement.valueOf(shield.group(1));
        int hits = Reading.NO_HITS;
        Matcher counted = HITS.matcher(name);
        if (attunement == Attunement.IMMUNE) {
            // "IMMUNE 04:07": the only number is the timer.
        } else if (counted.find()) {
            hits = Integer.parseInt(counted.group(1) != null ? counted.group(1) : counted.group(2));
        } else {
            Matcher bare = BARE_NUMBER.matcher(name.substring(shield.end()));
            if (bare.find()) {
                hits = Integer.parseInt(bare.group(1));
            }
        }
        return new Reading(attunement, hits);
    }

    /** "strike using the crystal attunement on your dagger!" (cleaned) -> CRYSTAL: what the hit shield wants. */
    static Attunement strikeRequirement(String message) {
        Matcher strike = STRIKE.matcher(message);
        return strike.find() ? Attunement.valueOf(strike.group(1).toUpperCase(Locale.ROOT)) : null;
    }

    /** "Spawned by: Name" -> "Name"; null for every other stand. Hypixel dropped this stand on 2026-10-18. */
    static String ownerOf(String name) {
        Matcher owner = OWNER.matcher(name);
        return owner.find() ? owner.group(1) : null;
    }

    static Kind kindOf(String name) {
        String lower = plainLetters(name);
        if (lower.contains("inferno demonlord")) {
            return Kind.BOSS;
        }
        return slotOf(lower) != null ? Kind.DEMON : null;
    }

    /** The demons are told apart by name, so each keeps its own actor. */
    static String slotOf(String name) {
        String lower = plainLetters(name);
        return lower.contains("quazii") ? "quazii" : lower.contains("typhoeus") ? "typhoeus" : null;
    }

    /** The demons' names are written in circled letters (ⓆⓊⒶⓏⒾⒾ); lower-cased plain letters match them. */
    private static String plainLetters(String text) {
        StringBuilder plain = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 'Ⓐ' && c <= 'Ⓩ') {
                plain.append((char) ('a' + (c - 'Ⓐ')));
            } else if (c >= 'ⓐ' && c <= 'ⓩ') {
                plain.append((char) ('a' + (c - 'ⓐ')));
            } else {
                plain.append(Character.toLowerCase(c));
            }
        }
        return plain.toString();
    }

    /** "slayer boss! inferno demonlord iv spawned!" (cleaned); the mini boss line says "slayer miniboss!". */
    static boolean isSpawnMessage(String message) {
        return message.startsWith("slayer boss!") && message.contains("spawned")
            && (message.contains("demonlord") || message.contains("inferno"));
    }

    /**
     * The boss back from its demons. Its tag can still carry a demon's shield for a moment ("ASHEN 4" where the boss
     * had "SPIRIT 4"), and a shield cannot change attunement without its hits running out first. So the boss's own
     * last reading stands in until the tag reads anything new. The one exception is a tag at a full count: the shield
     * ran through its last hit before the demons came, and the next phase has begun.
     */
    static final class Return {
        private static final long HOLD_MILLIS = 3_000L;

        private final Reading memory;
        private final long startedMillis;
        private Reading firstSight;
        private Reading forced;
        private boolean released;

        Return(Reading memory, long nowMillis) {
            this.memory = memory;
            this.startedMillis = nowMillis;
        }

        boolean released() {
            return released;
        }

        /**
         * "Strike using the CRYSTAL attunement" after a hit on the boss: the shield wants another attunement than the
         * one it had, so the last hit went through and a new phase began at a full count, before the tag says so.
         */
        void strike(Attunement required) {
            if (!released && required != memory.attunement()) {
                forced = new Reading(required, HITS_PER_ATTUNEMENT);
            }
        }

        Reading resolve(Reading tag, long nowMillis) {
            if (released) {
                return tag;
            }

            if (tag != null && tag.equals(memory)) {
                // The tag says what the boss had: it is the boss's own, so it can follow every hit from here.
                released = true;
            } else if (tag != null && firstSight == null) {
                firstSight = tag;
                released = tag.hits() == HITS_PER_ATTUNEMENT && memory.hits() < HITS_PER_ATTUNEMENT;
            } else if (tag != null && !tag.equals(firstSight)) {
                released = true;
            }
            released |= nowMillis - startedMillis > HOLD_MILLIS;
            return released ? tag : forced != null ? forced : memory;
        }
    }
}
