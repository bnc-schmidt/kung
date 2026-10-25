package com.github.beng420.kung.feature.slayer;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.slayer.BlazeShield.Attunement;
import com.github.beng420.kung.feature.slayer.BlazeShield.Reading;
import org.junit.Test;

public final class BlazeShieldTest {
    @Test
    public void tagsGiveTheAttunementAndTheHitsLeft() {
        assertEquals(new Reading(Attunement.ASHEN, 5), BlazeShield.parse("ASHEN ♨ 5 Hits"));
        assertEquals(new Reading(Attunement.SPIRIT, 1), BlazeShield.parse("SPIRIT 1 hit"));
        assertEquals(new Reading(Attunement.AURIC, 8), BlazeShield.parse("AURIC ♨ 8"));
        assertEquals(new Reading(Attunement.CRYSTAL, 3), BlazeShield.parse("CRYSTAL | Hits: 3"));
        // An immune demon names no hits, and a health value is never read as hits.
        assertEquals(new Reading(Attunement.IMMUNE, Reading.NO_HITS), BlazeShield.parse("IMMUNE"));
        assertEquals(new Reading(Attunement.ASHEN, Reading.NO_HITS), BlazeShield.parse("ASHEN 2.5M❤"));
        // Real tags: the hits stick to the ♨, then comes the slayer timer, which is never read as hits.
        assertEquals(new Reading(Attunement.SPIRIT, 8), BlazeShield.parse("SPIRIT ♨8 04:12"));
        assertEquals(new Reading(Attunement.ASHEN, 5), BlazeShield.parse("ASHEN ♨5 04:02"));
        assertEquals(new Reading(Attunement.IMMUNE, Reading.NO_HITS), BlazeShield.parse("IMMUNE 04:07"));
        assertNull(BlazeShield.parse("Quazii 12M❤"));
        assertNull(BlazeShield.parse("☠ Inferno Demonlord IV 13M❤"));
        assertNull(BlazeShield.parse("Spirit Wolf"));
    }

    @Test
    public void standsTellBossDemonsAndOwnerApart() {
        assertEquals(BlazeShield.Kind.BOSS, BlazeShield.kindOf("☠ Inferno Demonlord IV 13M❤"));
        assertEquals(BlazeShield.Kind.DEMON, BlazeShield.kindOf("[Lv300] Quazii 4M❤"));
        assertEquals("typhoeus", BlazeShield.slotOf("Typhoeus 4M❤"));
        // Hypixel writes the demons' names in circled letters.
        assertEquals(BlazeShield.Kind.DEMON, BlazeShield.kindOf("[Lv999] ⓆⓊⒶⓏⒾⒾ 10M/10M❤"));
        assertEquals("quazii", BlazeShield.slotOf("[Lv999] ⓆⓊⒶⓏⒾⒾ 10M/10M❤"));
        assertEquals("typhoeus", BlazeShield.slotOf("[Lv999] ⓉⓎⓅⒽⓄⒺⓊⓈ 10M/10M❤"));
        assertNull(BlazeShield.kindOf("[Lv201]  Smoldering Blaze 1.8M/2M❤"));
        assertNull(BlazeShield.kindOf("Flare Demon 5k❤"));
    }

    @Test
    public void aBossBackFromItsDemonsShowsItsOwnShieldUntilTheTagUpdates() {
        Reading own = new Reading(Attunement.SPIRIT, 4);
        Reading demonTag = new Reading(Attunement.ASHEN, 4);

        // The tag still carries a demon's attunement: the boss's own last shield stands in.
        var stale = new BlazeShield.Return(own, 0L);
        assertEquals(own, stale.resolve(null, 10L));
        assertEquals(own, stale.resolve(demonTag, 50L));
        assertEquals(own, stale.resolve(demonTag, 100L));
        // Then the tag reads something new and is trusted again.
        Reading fresh = new Reading(Attunement.SPIRIT, 3);
        assertEquals(fresh, stale.resolve(fresh, 150L));
        assertTrue(stale.released());
        assertEquals(demonTag, stale.resolve(demonTag, 200L));

        // A tag that already says what the boss had is the boss's own: no waiting, it follows every hit.
        var confirmed = new BlazeShield.Return(own, 0L);
        assertEquals(own, confirmed.resolve(own, 10L));
        assertTrue(confirmed.released());
        Reading oneHitLess = new Reading(Attunement.SPIRIT, 3);
        assertEquals(oneHitLess, confirmed.resolve(oneHitLess, 20L));

        // The last hit went through before the demons came: the next phase starts at a full count.
        var nextPhase = new BlazeShield.Return(new Reading(Attunement.SPIRIT, 1), 0L);
        Reading full = new Reading(Attunement.AURIC, 8);
        assertEquals(full, nextPhase.resolve(full, 10L));
        assertTrue(nextPhase.released());

        // A boss that was itself at a full count cannot tell a full-count tag from a demon's.
        var atFull = new BlazeShield.Return(new Reading(Attunement.SPIRIT, 8), 0L);
        assertEquals(new Reading(Attunement.SPIRIT, 8), atFull.resolve(new Reading(Attunement.ASHEN, 8), 10L));

        // A tag that never updates cannot hold the boss's own shield forever.
        var stuck = new BlazeShield.Return(own, 0L);
        assertEquals(own, stuck.resolve(demonTag, 100L));
        assertEquals(demonTag, stuck.resolve(demonTag, 5_000L));
    }

    @Test
    public void aStrikeMessageCorrectsTheHeldShieldBeforeTheTagDoes() {
        assertEquals(Attunement.CRYSTAL,
            BlazeShield.strikeRequirement("strike using the crystal attunement on your dagger!"));
        assertNull(BlazeShield.strikeRequirement("your hit was reduced by hellion shield!"));

        // 2026-10-25 trace: AURIC 1 before the demons, CRYSTAL 8 after them. The strike message named it first.
        Reading before = new Reading(Attunement.AURIC, 1);
        var returning = new BlazeShield.Return(before, 0L);
        assertEquals(before, returning.resolve(null, 10L));
        returning.strike(Attunement.CRYSTAL);
        assertEquals(new Reading(Attunement.CRYSTAL, 8), returning.resolve(null, 20L));
        // The tag catches up: a full count means the new phase, so it is trusted at once.
        Reading caughtUp = new Reading(Attunement.CRYSTAL, 8);
        assertEquals(caughtUp, returning.resolve(caughtUp, 30L));
        assertTrue(returning.released());

        // A strike that agrees with the held shield changes nothing.
        Reading own = new Reading(Attunement.SPIRIT, 4);
        var same = new BlazeShield.Return(own, 0L);
        same.strike(Attunement.SPIRIT);
        assertEquals(own, same.resolve(null, 10L));
    }
}
