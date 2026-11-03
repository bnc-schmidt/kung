package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.SackCounts;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public final class SackApiProbeTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String KEY = "123e4567e89b12d3a456426614174000";

    private static SackCounts parse(String json) {
        return HypixelSkyBlockProfileClient.parseSackCounts(UUID, JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    public void diffNamesEveryChangedIdAndNothingElse() {
        Map<String, Long> before = new TreeMap<>(Map.of("WHEAT", 100L, "CARROT", 7L, "COAL", 3L));
        Map<String, Long> after = new TreeMap<>(Map.of("WHEAT", 150L, "CARROT", 7L, "STONE", 9L));
        // A missing id counts as 0 on either side.
        assertEquals("COAL 3->0, STONE 0->9, WHEAT 100->150", SackApiProbe.diff(before, after));
        assertEquals("", SackApiProbe.diff(before, before));
        assertEquals("", SackApiProbe.diff(Map.of(), Map.of()));
    }

    @Test
    public void yourOwnKeyIsAskedFirstThenTheServerAndNeitherMeansNoSource() {
        assertEquals("hypixel", HypixelSkyBlockProfileClient.sackSource(true, true));
        assertEquals("hypixel", HypixelSkyBlockProfileClient.sackSource(true, false));
        assertEquals("kung-server", HypixelSkyBlockProfileClient.sackSource(false, true));
        assertEquals("", HypixelSkyBlockProfileClient.sackSource(false, false));
    }

    @Test
    public void theSelectedProfilesInventoryHoldsTheSacks() {
        SackCounts counts = parse("{\"success\":true,\"profiles\":["
            + "{\"selected\":false,\"cute_name\":\"Apple\",\"members\":{\"" + KEY + "\":{\"inventory\":{\"sacks_counts\":{\"WHEAT\":1}}}}},"
            + "{\"selected\":true,\"cute_name\":\"Banana\",\"members\":{\"" + KEY + "\":{\"inventory\":{\"sacks_counts\":{\"WHEAT\":1500,\"CARROT_ITEM\":20}}}}}"
            + "]}");
        assertEquals("Banana", counts.profile());
        assertEquals(Map.of("CARROT_ITEM", 20L, "WHEAT", 1500L), counts.counts());
    }

    @Test
    public void olderDataKeepsTheSacksOnTheMemberAndTheFirstProfileWinsWithoutASelection() {
        SackCounts counts = parse("{\"profiles\":[{\"cute_name\":\"Apple\",\"members\":{\"" + KEY
            + "\":{\"sacks_counts\":{\"COAL\":4}}}}]}");
        assertEquals("Apple", counts.profile());
        assertEquals(Map.of("COAL", 4L), counts.counts());
    }

    @Test
    public void aHiddenInventoryOrAnUnknownMemberGivesNoCounts() {
        assertTrue(parse("{\"profiles\":[{\"selected\":true,\"cute_name\":\"Apple\",\"members\":{\"" + KEY + "\":{}}}]}")
            .counts().isEmpty());
        assertTrue(parse("{\"profiles\":[{\"selected\":true,\"cute_name\":\"Apple\",\"members\":{\"other\":{}}}]}")
            .counts().isEmpty());
        // The note says which part is missing, so a trace shows why the Sacks stay empty.
        assertEquals("member keys=player_data; no inventory object", parse("{\"profiles\":[{\"members\":{\"" + KEY
            + "\":{\"player_data\":{}}}}]}").note());
        assertEquals("member keys=inventory; inventory keys=bag_contents,inv_contents", parse("{\"profiles\":[{\"members\":{\""
            + KEY + "\":{\"inventory\":{\"inv_contents\":{},\"bag_contents\":{}}}}}]}").note());
        assertEquals("member not found", parse("{\"profiles\":[{\"members\":{\"other\":{}}}]}").note());
        assertEquals("no profiles in the answer", parse("{\"success\":true}").note());
        assertEquals("", parse("{\"profiles\":[{\"members\":{\"" + KEY + "\":{\"sacks_counts\":{\"COAL\":1}}}}]}").note());
        assertEquals("", parse("{\"success\":true}").profile());
        assertEquals("", parse("{\"profiles\":null}").profile());
    }
}
