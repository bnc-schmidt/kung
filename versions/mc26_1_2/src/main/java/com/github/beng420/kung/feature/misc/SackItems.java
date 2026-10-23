package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** The SkyBlock item ids that fit in a sack (bundled from NotEnoughUpdates-REPO, MIT), for tracking items outside sack menus. */
final class SackItems {
    private static final String RESOURCE = "kung-sack-items.json";

    private SackItems() { }

    /** True for a sack item id; Hypixel writes "INK_SACK:3", the bundle "INK_SACK-3". */
    static boolean contains(String id) {
        return id != null && Holder.IDS.contains(id.replace(':', '-'));
    }

    private static Set<String> load() {
        Set<String> ids = new HashSet<>();
        try (var stream = SackItems.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("missing " + RESOURCE);
            JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("ids").forEach(id -> ids.add(id.getAsString()));
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Sack item list unusable, tracking outside sacks is off.", exception);
        }
        return Set.copyOf(ids);
    }

    private static final class Holder {
        static final Set<String> IDS = load();
    }
}
