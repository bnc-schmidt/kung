package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class GreenhouseMutationsTest {
    private static GreenhouseMutations.Mutation mutation(String name) {
        return GreenhouseMutations.find(name).orElseThrow();
    }

    @Test
    public void bundleLoadsWithEverySpecialAndEverySupportedRecipe() {
        assertEquals("", GreenhouseMutations.loadError());
        List<GreenhouseMutations.Mutation> all = GreenhouseMutations.all();

        assertEquals(40, all.size());
        assertEquals(37, all.stream().filter(GreenhouseMutations.Mutation::supported).count());
        assertTrue(all.stream().filter(m -> !m.supported()).map(GreenhouseMutations.Mutation::name).toList()
            .containsAll(List.of("Godseed", "Shellfruit", "Jerryflower")));
    }

    @Test
    public void mutationAndIngredientNamesUseTheCropListSpelling() {
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.all()) {
            assertTrue(mutation.name(), GreenhouseCrops.NAMES.contains(mutation.name()));
            for (String crop : mutation.needs().keySet()) {
                assertTrue(mutation.name() + " needs " + crop, GreenhouseCrops.NAMES.contains(crop));
            }
        }
    }

    @Test
    public void supportedListCarriesTheFieldsAUiNeeds() {
        List<GreenhouseMutations.Mutation> supported = GreenhouseMutations.supported();

        assertEquals(37, supported.size());
        for (GreenhouseMutations.Mutation mutation : supported) {
            assertTrue(mutation.name(), mutation.supported());
            assertTrue(mutation.name(), mutation.chancePct() >= 1 && mutation.chancePct() <= 100);
            assertTrue(mutation.name(), mutation.width() >= 1 && mutation.height() >= 1);
            assertFalse(mutation.name(), mutation.soil().isEmpty());
            assertFalse(mutation.name(), mutation.source().isEmpty());
        }
        assertEquals(25, mutation("Zombud").chancePct());
        assertEquals(30, mutation("Witherbloom").chancePct());
        assertFalse(supported.stream().anyMatch(m -> m.name().equals("Godseed")));
    }

    @Test
    public void everyRecipeFitsItsRing() {
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.all()) {
            if (!mutation.supported()) continue;
            int total = mutation.needs().values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(mutation.name(), total <= mutation.ringSize());
            assertEquals(mutation.name(), mutation.emptyRing(), total == 0);
            assertFalse(mutation.name(), mutation.soil().isEmpty());
        }
        assertEquals(8, mutation("Dustgrain").ringSize());
        assertEquals(12, mutation("Noctilume").ringSize());
        assertEquals(16, mutation("Snoozling").ringSize());
    }

    @Test
    public void wikiRecipesAreKeptAsCounts() {
        assertEquals(2, (int) mutation("Dustgrain").needs().get("Wheat Seeds"));
        assertEquals(4, (int) mutation("Cindershade").needs().get("Ashwreath"));
        assertEquals(4, (int) mutation("Cindershade").needs().get("Witherbloom"));
        assertEquals(List.of("Soul Sand"), mutation("Cindershade").soil());
        assertEquals(3, mutation("Snoozling").width());
        assertEquals(2, mutation("Glasscorn").height());
        assertTrue(mutation("Lonelily").emptyRing());
    }

    @Test
    public void lookupIgnoresCaseAndSeparators() {
        assertEquals("Do-not-eat-shroom", mutation("do not eat shroom").name());
        assertEquals("All-in Aloe", mutation("ALL-IN ALOE").name());
        assertFalse(GreenhouseMutations.find("Not a mutation").isPresent());
        assertFalse(GreenhouseMutations.find(null).isPresent());
    }

    @Test
    public void parseRejectsBadData() {
        String head = "{\"schema\":1,\"mutations\":[";
        assertEquals(1, GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat Seeds\":2},"
            + "\"soil\":[\"Farmland\"],\"chancePct\":30}]}").size());
        // A supported recipe needs a spawn chance: it weights the split between recipes a ring meets.
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat Seeds\":2},\"soil\":[]}]}"));
        // Wiki spelling is not the crop list's spelling.
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat\":2},\"soil\":[]}]}"));
        // No need and no emptyRing would match every empty cell.
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{},\"soil\":[]}]}"));
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat Seeds\":9},\"soil\":[]}]}"));
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse(head
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat Seeds\":2},\"chancePct\":30},"
            + "{\"name\":\"Dustgrain\",\"width\":1,\"height\":1,\"needs\":{\"Wheat Seeds\":2},\"chancePct\":30}]}"));
        assertThrows(IllegalArgumentException.class, () -> GreenhouseMutations.parse("{\"schema\":2,\"mutations\":[]}"));
    }
}
