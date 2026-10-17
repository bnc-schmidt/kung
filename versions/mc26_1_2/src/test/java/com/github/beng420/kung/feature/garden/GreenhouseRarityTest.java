package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseRarityTest {
    @Test
    public void everyMutationHasARarityAndNothingElseDoes() {
        assertEquals("", GreenhouseRarity.loadError());
        Set<String> mutations = new HashSet<>();
        GreenhouseMutations.all().forEach(mutation -> mutations.add(mutation.name()));

        assertEquals(40, mutations.size());
        assertEquals(mutations, GreenhouseRarity.all().keySet());
    }

    @Test
    public void countsMatchTheWikiTable() {
        Map<GreenhouseRarity.Rarity, Integer> counts = new EnumMap<>(GreenhouseRarity.Rarity.class);
        GreenhouseRarity.all().values().forEach(rarity -> counts.merge(rarity, 1, Integer::sum));

        assertEquals(Integer.valueOf(9), counts.get(GreenhouseRarity.Rarity.COMMON));
        assertEquals(Integer.valueOf(6), counts.get(GreenhouseRarity.Rarity.UNCOMMON));
        assertEquals(Integer.valueOf(10), counts.get(GreenhouseRarity.Rarity.RARE));
        assertEquals(Integer.valueOf(8), counts.get(GreenhouseRarity.Rarity.EPIC));
        assertEquals(Integer.valueOf(7), counts.get(GreenhouseRarity.Rarity.LEGENDARY));
    }

    @Test
    public void lookupUsesTheCropListsLooseSpelling() {
        assertEquals(GreenhouseRarity.Rarity.RARE, GreenhouseRarity.of("do not eat shroom").orElseThrow());
        assertEquals(GreenhouseRarity.Rarity.EPIC, GreenhouseRarity.of("Shellfruit").orElseThrow());
        assertEquals(GreenhouseRarity.Rarity.LEGENDARY, GreenhouseRarity.of("godseed").orElseThrow());
        assertFalse(GreenhouseRarity.of(null).isPresent());
    }

    @Test
    public void baseCropsAndBlocksHaveNoRarity() {
        for (String crop : new String[] {"Wheat Seeds", "Carrot", "Cocoa Beans", "Dead Plants", "Fire", "Fermento",
            "Helianthus"}) {
            assertFalse(crop, GreenhouseRarity.of(crop).isPresent());
        }
    }

    @Test
    public void parseIsStrict() {
        assertEquals(Map.of("Lonelily", GreenhouseRarity.Rarity.COMMON),
            GreenhouseRarity.parse("{\"schema\":1,\"rarity\":{\"Lonelily\":\"Common\"}}"));

        assertThrows(IllegalArgumentException.class, () -> GreenhouseRarity.parse("{\"schema\":2,\"rarity\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> GreenhouseRarity.parse("{\"schema\":1}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseRarity.parse("{\"schema\":1,\"rarity\":{\"Lonelyly\":\"Common\"}}"));
        // Only the editor's own spelling is accepted, like in the mutation data.
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseRarity.parse("{\"schema\":1,\"rarity\":{\"lonelily\":\"Common\"}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseRarity.parse("{\"schema\":1,\"rarity\":{\"Lonelily\":\"Mythic\"}}"));
        assertThrows(IllegalArgumentException.class, () -> GreenhouseRarity.parse("null"));
    }

    @Test
    public void displayNamesRoundTrip() {
        for (GreenhouseRarity.Rarity rarity : GreenhouseRarity.Rarity.values()) {
            assertTrue(rarity.name(), GreenhouseRarity.Rarity.byDisplayName(rarity.displayName()).isPresent());
        }
    }
}
