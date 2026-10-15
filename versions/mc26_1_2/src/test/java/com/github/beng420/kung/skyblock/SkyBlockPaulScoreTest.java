package com.github.beng420.kung.skyblock;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyBlockPaulScoreTest {
    @Test
    public void paulWithoutTheEzpzPerkGivesNoBonus() {
        assertFalse(active("""
            {"name":"Paul", "perks":[{"name":"Marauder"},{"name":"Benediction"}],
             "minister":{"name":"Cole","perk":{"name":"Mining Fiesta"}}}
            """));
    }

    @Test
    public void electedEzpzPerkGivesTheBonus() {
        assertTrue(active("""
            {"name":"Paul", "perks":[{"name":"Marauder"},{"name":"EZPZ"},{"name":"Benediction"}]}
            """));
        assertTrue(active("""
            {"name":"Paul", "perks":[{"name":"§aEzPz"}]}
            """));
    }

    @Test
    public void onlyThePaulMinistersSelectedPerkCounts() {
        assertTrue(active("""
            {"name":"Aatrox", "minister":{"name":"Paul", "perk":{"name":"EZPZ"}}}
            """));
        assertFalse(active("""
            {"name":"Aatrox", "minister":{"name":"Paul", "perk":{"name":"Benediction"}}}
            """));
    }

    @Test
    public void candidateListsAndMissingOrMalformedPerksAreIgnored() {
        assertFalse(active("""
            {"name":"Paul", "perks":[{"name":"Marauder"}],
             "election":{"year":518,"candidates":[{"name":"Paul","perks":[{"name":"EZPZ"}]}]}}
            """));
        for (String value : new String[] {"{}", "{\"perks\":\"EZPZ\"}", "{\"perks\":[\"EZPZ\", {\"name\":5}]}",
            "{\"minister\":{\"perk\":\"EZPZ\"}}"}) {
            assertFalse(value, active(value));
        }
        assertFalse(SkyBlockMayorTracker.parsePaulScoreActive(null));
    }

    private static boolean active(String json) {
        return SkyBlockMayorTracker.parsePaulScoreActive(JsonParser.parseString(json).getAsJsonObject());
    }
}
