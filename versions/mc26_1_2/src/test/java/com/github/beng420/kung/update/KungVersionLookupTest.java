package com.github.beng420.kung.update;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import org.junit.Test;

public final class KungVersionLookupTest {
    @Test
    public void githubGivesItsLatestTagWithAV() {
        assertEquals("v0.8.3", KungVersionLookup.githubVersion(200, "{\"tag_name\":\"v0.8.3\"}"));
        assertEquals("v0.8.3", KungVersionLookup.githubVersion(200, "{\"tag_name\":\"0.8.3\"}"));
        assertEquals("none yet", KungVersionLookup.githubVersion(404, ""));
        assertEquals("unavailable", KungVersionLookup.githubVersion(500, ""));
        assertEquals("unavailable", KungVersionLookup.githubVersion(200, "not json"));
        assertEquals("unavailable", KungVersionLookup.githubVersion(-1, ""));
    }

    @Test
    public void aModrinthProjectWithoutListedVersionsIsAwaitingApproval() {
        assertEquals("awaiting approval", KungVersionLookup.modrinthVersion(404, "{\"error\":\"not_found\"}"));
        assertEquals("awaiting approval", KungVersionLookup.modrinthVersion(200, "[]"));
        assertEquals("v0.8.3", KungVersionLookup.modrinthVersion(200,
            "[{\"version_number\":\"0.8.3\"},{\"version_number\":\"0.8.2\"}]"));
        assertEquals("v0.8.3+26.1.2", KungVersionLookup.modrinthVersion(200, "[{\"version_number\":\"v0.8.3+26.1.2\"}]"));
        assertEquals("unavailable", KungVersionLookup.modrinthVersion(503, ""));
        assertEquals("unavailable", KungVersionLookup.modrinthVersion(200, "{}"));
    }

    @Test
    public void theLineNamesTheInstalledAndBothNewestVersions() {
        LocalDate today = LocalDate.of(2026, 11, 11);
        String line = KungVersionLookup.line("0.8.4-dev", "v0.8.3", "awaiting approval", today);
        assertEquals("Kung v0.8.4-dev | github (repo beng420/kung): v0.8.3 | modrinth: awaiting approval (175 days)", line);
        // Once Modrinth lists a version, the wait is over and no days show.
        assertEquals("Kung v0.8.4-dev | github (repo beng420/kung): v0.8.3 | modrinth: v0.8.3",
            KungVersionLookup.line("0.8.4-dev", "v0.8.3", "v0.8.3", today));
        // Hypixel's chat takes 256 characters; this stays far below even with long versions.
        assertEquals(true, KungVersionLookup.line("0.8.10-dev", "v0.8.10+26.1.2", "awaiting approval", today).length() < 150);
    }

    @Test
    public void theWaitIsCountedInDaysFromTheDayTheProjectWasCreated() {
        assertEquals("awaiting approval (0 days)", KungVersionLookup.awaiting(LocalDate.of(2026, 5, 20)));
        assertEquals("awaiting approval (1 day)", KungVersionLookup.awaiting(LocalDate.of(2026, 5, 21)));
        assertEquals("awaiting approval (175 days)", KungVersionLookup.awaiting(LocalDate.of(2026, 11, 11)));
        // A clock before the project existed shows no number rather than a negative one.
        assertEquals("awaiting approval", KungVersionLookup.awaiting(LocalDate.of(2026, 5, 1)));
    }
}
