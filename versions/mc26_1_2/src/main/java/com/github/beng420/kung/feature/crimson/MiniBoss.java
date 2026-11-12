package com.github.beng420.kung.feature.crimson;

import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.world.phys.AABB;

/**
 * The five Crimson Isle minibosses with the area each one spawns in (the boxes SkyHanni uses) and the spot it spawns at
 * (where its beacon stands; Ashfang's is the wiki's, not yet seen). Magma Boss leaps (nameplate seen from 60 to 98
 * high) and flickers in and out of sight, so it needs a longer look before it counts as gone.
 */
public enum MiniBoss {
    BLADESOUL("Bladesoul", -330, 80, -486, -257, 107, -545, -295, -518, 3_000),
    MAGE_OUTLAW("Mage Outlaw", -200, 98, -843, -162, 116, -878, -181, -860, 3_000),
    BARBARIAN_DUKE_X("Barbarian Duke X", -550, 101, -890, -522, 131, -918, -537, -905, 3_000),
    ASHFANG("Ashfang", -462, 155, -1035, -507, 131, -955, -485, -1016, 3_000),
    MAGMA_BOSS("Magma Boss", -318, 59, -751, -442, 90, -851, -368, -804, 8_000);

    /** Ashfang's helpers share his name at the front ("Ashfang Follower"); they are not the boss. */
    private static final Pattern HELPER = Pattern.compile("\\s(?:Follower|Underling|Acolyte|Minion)\\b", Pattern.CASE_INSENSITIVE);
    /** A level tag in front of a nameplate: "[Lv500] Bladesoul". */
    private static final Pattern LEVEL_TAG = Pattern.compile("^\\[[^\\]]*]\\s*");
    /** Hypixel puts icon glyphs (private-use characters) and spaces between the level tag and the name. */
    private static final Pattern BEFORE_NAME = Pattern.compile("^[^\\p{L}\\p{N}]+");

    private final String displayName;
    private final AABB area;
    private final double spawnX;
    private final double spawnZ;
    private final long absentHold;

    MiniBoss(String displayName, double x1, double y1, double z1, double x2, double y2, double z2,
             double spawnX, double spawnZ, long absentHold) {
        this.displayName = displayName;
        this.area = new AABB(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1);
        this.spawnX = spawnX + 0.5;
        this.spawnZ = spawnZ + 0.5;
        this.absentHold = absentHold;
    }

    public String displayName() { return displayName; }
    public AABB area() { return area; }
    /** How long, in millis, the spawn spot must show no boss before the boss counts as dead. */
    public long absentHold() { return absentHold; }

    /** Blocks on the ground plane between a point and the spot this boss spawns at. */
    public double distanceToSpawn(double x, double z) {
        return Math.hypot(x - spawnX, z - spawnZ);
    }

    /** The boss a chat name ("BLADESOUL", "Bladesoul", "The Mage Outlaw") belongs to, or null. */
    public static MiniBoss byName(String name) {
        String plain = name.trim().replaceFirst("(?i)^the\\s+", "");
        for (MiniBoss boss : values()) {
            if (boss.displayName.equalsIgnoreCase(plain)) return boss;
        }
        return null;
    }

    /** Whether a cleaned nameplate ("[Lv486] Bladesoul 48.6M/50M❤", icon glyphs before the name) is this boss and not a helper of it. */
    public boolean isNameplate(String cleanName) {
        String rest = BEFORE_NAME.matcher(LEVEL_TAG.matcher(cleanName).replaceFirst("")).replaceFirst("");
        return rest.toLowerCase(Locale.ROOT).startsWith(displayName.toLowerCase(Locale.ROOT))
            && !HELPER.matcher(rest).find();
    }

    /** Blocks between a point and this boss's area, flat on the ground plane; 0 inside it. */
    public double distanceTo(double x, double z) {
        double dx = Math.max(Math.max(area.minX - x, 0), x - area.maxX);
        double dz = Math.max(Math.max(area.minZ - z, 0), z - area.maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
