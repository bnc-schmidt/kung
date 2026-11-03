package com.github.beng420.kung.feature.garden;

/** Rings once while farming goes on without an effect, then stays quiet until the effect is seen again. */
final class MissingEffectAlarm {
    private boolean latched;

    /**
     * @param effectActive true or false as the server shows it, null while the tab does not say
     * @return true when the alarm should ring now
     */
    boolean update(boolean farming, Boolean effectActive) {
        if (Boolean.TRUE.equals(effectActive)) {
            latched = false;
            return false;
        }
        if (latched || !farming || effectActive == null) return false;
        latched = true;
        return true;
    }

    /** A new plot or world: the next farming run without the effect rings again. */
    void rearm() { latched = false; }
}
