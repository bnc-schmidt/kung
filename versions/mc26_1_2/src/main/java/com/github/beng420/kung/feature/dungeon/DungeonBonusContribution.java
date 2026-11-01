package com.github.beng420.kung.feature.dungeon;

import java.util.Locale;
import java.util.regex.Pattern;

/** Named evidence only: a generic kill announcement identifies an observer, not the killer. */
public enum DungeonBonusContribution {
    PRINCE("P"), MIMIC("M"), BAT("B");

    private static final String NAME = "[A-Za-z0-9_]{3,16}";
    private static final String BONUS = "(?:the )?(prince|mimic|bat)";
    private static final Pattern PARTY = Pattern.compile(
        "^Party > (?:\\[[^]]+]\\s*)*(" + NAME + "): (.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_PERSON = Pattern.compile(
        "^I (?:killed|slew) " + BONUS + "[!.]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED = Pattern.compile(
        "^(?:\\[[^]]+]\\s*)*(" + NAME + ") (?:killed|slew) " + BONUS + "[!.]?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSIVE = Pattern.compile(
        "^" + BONUS + " (?:was )?(?:killed|slain) by (?:\\[[^]]+]\\s*)*(" + NAME + ")[!.]?$",
        Pattern.CASE_INSENSITIVE);

    private final String marker;

    DungeonBonusContribution(String marker) { this.marker = marker; }
    String marker() { return marker; }

    static Claim namedClaim(String message) {
        if (message == null || message.contains("[Kung]")) return null;
        var party = PARTY.matcher(message);
        String body = message;
        if (party.matches()) {
            body = party.group(2);
            var firstPerson = FIRST_PERSON.matcher(body);
            if (firstPerson.matches()) return new Claim(party.group(1), bonus(firstPerson.group(1)));
        }
        var named = NAMED.matcher(body);
        if (named.matches()) return new Claim(named.group(1), bonus(named.group(2)));
        var passive = PASSIVE.matcher(body);
        return passive.matches() ? new Claim(passive.group(2), bonus(passive.group(1))) : null;
    }

    /** The party member who sent this chat line, or null for any other message. */
    static String partySender(String message) {
        if (message == null || message.contains("[Kung]")) return null;
        var party = PARTY.matcher(message);
        return party.matches() ? party.group(1) : null;
    }

    private static DungeonBonusContribution bonus(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    record Claim(String name, DungeonBonusContribution bonus) { }
}
