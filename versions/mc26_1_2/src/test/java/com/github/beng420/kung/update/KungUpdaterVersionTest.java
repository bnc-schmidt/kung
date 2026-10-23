package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import org.junit.Test;

public final class KungUpdaterVersionTest {
    @Test public void aDevBuildOneVersionAheadNeverSeesTheReleasedStateAsAnUpdate() {
        // The repo sits on the next version with a -dev suffix: 0.6.2 is out, the dev build is 0.6.3-dev.
        assertFalse(KungUpdater.isNewerVersion("0.6.2", "0.6.3-dev"));
        // When that version is released the dev build is not offered it either: the suffix has no digits.
        assertFalse(KungUpdater.isNewerVersion("0.6.3", "0.6.3-dev"));
        // Only a release beyond it counts, e.g. after the repo moved on without a rebuild.
        assertTrue(KungUpdater.isNewerVersion("0.6.4", "0.6.3-dev"));
        assertTrue(KungUpdater.isNewerVersion("0.7.0", "0.6.3-dev"));
    }

    @Test public void releasedVersionsStillCompareNumerically() {
        assertTrue(KungUpdater.isNewerVersion("0.6.2", "0.6.1"));
        assertTrue(KungUpdater.isNewerVersion("0.10.0", "0.9.9"));
        assertFalse(KungUpdater.isNewerVersion("0.6.2", "0.6.2"));
        assertFalse(KungUpdater.isNewerVersion("0.6.1", "0.6.2"));
    }
}
