package com.github.beng420.kung.feature.waypoints;

import java.util.Comparator;
import net.minecraft.core.BlockPos;

/** One marked block. {@code rgb} is 0xRRGGBB, {@code alpha} the fill opacity from 0 to 1. */
public record Waypoint(int x, int y, int z, String name, int rgb, float alpha, boolean enabled) {
    public static final int DEFAULT_RGB = 0x00FF00;
    public static final float DEFAULT_ALPHA = 0.5F;

    public Waypoint {
        name = name == null ? "" : name;
        rgb &= 0xFFFFFF;
        alpha = Float.isNaN(alpha) ? DEFAULT_ALPHA : Math.clamp(alpha, 0F, 1F);
    }

    public static Waypoint at(BlockPos pos, String name) {
        return new Waypoint(pos.getX(), pos.getY(), pos.getZ(), name, DEFAULT_RGB, DEFAULT_ALPHA, true);
    }

    /** Names in the order a player counts: "Plot 2" before "Plot 10". */
    public static final Comparator<Waypoint> NAME_ORDER = (first, second) -> {
        String a = first.name();
        String b = second.name();
        String prefixA = a.replaceFirst("\\d+$", "");
        String prefixB = b.replaceFirst("\\d+$", "");
        int byPrefix = prefixA.compareTo(prefixB);
        if (byPrefix != 0) return byPrefix;
        String digitsA = a.substring(prefixA.length());
        String digitsB = b.substring(prefixB.length());
        if (digitsA.isEmpty() || digitsB.isEmpty()) return a.compareTo(b);
        try {
            return Integer.compare(Integer.parseInt(digitsA), Integer.parseInt(digitsB));
        } catch (NumberFormatException tooLong) {
            return a.compareTo(b);
        }
    };

    public BlockPos pos() { return new BlockPos(x, y, z); }

    public Waypoint withName(String value) { return new Waypoint(x, y, z, value, rgb, alpha, enabled); }
    public Waypoint withPos(int nx, int ny, int nz) { return new Waypoint(nx, ny, nz, name, rgb, alpha, enabled); }
    public Waypoint withColor(int newRgb, float newAlpha) { return new Waypoint(x, y, z, name, newRgb, newAlpha, enabled); }
    public Waypoint withEnabled(boolean value) { return new Waypoint(x, y, z, name, rgb, alpha, value); }

    /** Squared distance from a point to the centre of the block. */
    public double distanceSqr(double px, double py, double pz) {
        double dx = x + 0.5 - px;
        double dy = y + 0.5 - py;
        double dz = z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
