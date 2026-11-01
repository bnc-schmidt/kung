package com.github.beng420.kung.config.category;

import com.github.beng420.kung.config.category.MiscConfig.SackItem;
import com.github.beng420.kung.feature.garden.FeastContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The tracked sack items of each Minecraft account and SkyBlock profile, keyed "accountUuid:profileUuid". The
 * active profile is whatever the last "Profile ID:" chat line named; none is active until one arrives, after a
 * "Switching to profile" line, and after leaving the server, so nothing is ever counted into a guessed profile.
 */
public final class SackProfiles {
    private static final Pattern NAME = Pattern.compile("^(?:\\[\\d{2}:\\d{2}:\\d{2}\\] )?You are playing on profile: (\\w+)");
    private static final Pattern SWITCHING = Pattern.compile("^(?:\\[\\d{2}:\\d{2}:\\d{2}\\] )?Switching to profile .+\\.\\.\\.$");
    private static final int MAX_PROFILES = 64;

    private static final class Profile {
        private String name = "";
        private List<SackItem> items = new ArrayList<>();
    }

    /** Least recently used first, so the oldest profile goes when there are too many. */
    private Map<String, Profile> profiles = new LinkedHashMap<>();
    private transient String active;
    private transient String pendingName = "";

    /**
     * Reads one chat line; true when the active profile changed. A "Profile ID:" line selects that profile for
     * {@code account}; the very first profile ever seen takes over {@code legacy}, the single list of older versions.
     */
    public boolean observe(String text, String account, List<SackItem> legacy) {
        text = text.strip();
        var name = NAME.matcher(text);
        if (name.find()) {
            pendingName = name.group(1);
            return false;
        }
        if (SWITCHING.matcher(text).matches()) return leave();
        String id = FeastContext.profileId(text);
        return id != null && account != null && select(account + ":" + id, legacy);
    }

    private boolean select(String key, List<SackItem> legacy) {
        boolean first = profiles.isEmpty();
        Profile profile = profiles.remove(key);
        if (profile == null) {
            profile = new Profile();
            if (first) {
                profile.items.addAll(legacy);
                legacy.clear();
            }
        }
        profiles.put(key, profile);
        if (!pendingName.isBlank()) profile.name = pendingName;
        pendingName = "";
        while (profiles.size() > MAX_PROFILES) profiles.remove(profiles.keySet().iterator().next());
        boolean changed = !key.equals(active);
        active = key;
        return changed;
    }

    /** No profile is active any more (left the server, or switching profile); true when one was. */
    public boolean leave() {
        pendingName = "";
        boolean was = active != null;
        active = null;
        return was;
    }

    public boolean known() { return active != null; }

    /** The active profile's items, live; null while no profile is active. */
    List<SackItem> items() { return active == null ? null : profiles.get(active).items; }

    /** The active profile's name ("Coconut"), empty when unknown. */
    public String name() { return active == null ? "" : profiles.get(active).name; }

    void sanitize() {
        if (profiles == null) profiles = new LinkedHashMap<>();
        profiles.values().removeIf(profile -> profile == null);
        for (Profile profile : profiles.values()) {
            if (profile.name == null) profile.name = "";
            profile.items = profile.items == null ? new ArrayList<>()
                : new ArrayList<>(profile.items.stream().filter(item -> item != null && !item.name().isBlank()).toList());
        }
    }
}
