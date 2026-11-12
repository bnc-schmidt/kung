package com.github.beng420.kung.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.github.beng420.kung.feature.dungeon.KungServerSession;
import org.junit.Test;

/** The chat line of a !bestiary lookup that failed: the reason in words, never the raw error. */
public final class BestiaryFailureTest {
    @Test
    public void failureLinesNameTheReason() {
        assertEquals("Could not look up the Bestiary of Notch: no Kung server set.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", HypixelSkyBlockProfileClient.NO_SERVER));
        assertEquals("Could not look up the Bestiary of Notch: not allowed on the Kung server.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", KungServerSession.NOT_ALLOWED));
        assertEquals("Could not look up the Bestiary of Notch: rate limited, try again in a few minutes.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "Kung server returned HTTP 429: too many requests"));
        assertEquals("Could not look up the Bestiary of Notch: the Kung server has no Bestiary lookup yet.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "Kung server returned HTTP 404: not found"));
        assertEquals("Could not look up the Bestiary of Notch: timed out.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "request timed out"));
        assertEquals("Could not look up the Bestiary of Notch: service unavailable.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "connection reset"));
        assertEquals("Could not look up the Bestiary of Notch: service unavailable.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", null));
    }

    @Test
    public void aKeyOrTokenInTheErrorNeverReachesTheChat() {
        String line = HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "boom API-Key=secret-123 | Authorization: Bearer abc");
        assertFalse(line, line.contains("secret"));
        assertFalse(line, line.contains("Bearer"));
    }

    @Test
    public void aKungSignInThatNeedsARestartIsSaidSo() {
        assertEquals("Could not look up the Bestiary of Notch: Kung sign-in failed, the Kung user has to restart the game.",
            HypixelSkyBlockProfileClient.bestiaryFailure("Notch", "Invalid session: restart the game and the launcher"));
    }
}
