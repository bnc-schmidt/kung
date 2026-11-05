package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Replaces render snapshots only; server blocks, collisions and room scans stay authoritative. */
public final class ColoredPillarsFeature extends ConfigurableFeature<DungeonConfig> {
    public static final ColoredPillarsFeature INSTANCE = new ColoredPillarsFeature();
    private static volatile RenderContext renderContext;
    private long bossEpoch = Long.MIN_VALUE;

    ColoredPillarsFeature() { super(config -> config.dungeon); }

    @Override
    public boolean isEnabled() { return initialized() && config().coloredPillarsEnabled(); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            var instance = HypixelInstanceTracker.INSTANCE;
            observeBossMessage(message.getString(), overlay, instance.catacombs(), instance.instanceEpoch());
            return true;
        });
    }

    @Override
    protected void onReset() {
        bossEpoch = Long.MIN_VALUE;
        update(Minecraft.getInstance(), null);
    }

    private void tick(Minecraft client) {
        var instance = HypixelInstanceTracker.INSTANCE;
        PillarMaterial material = initialized()
            ? selectedMaterial(config(), instance.catacombs(), instance.dungeonFloor(), instance.instanceEpoch()) : null;
        update(client, client.level == null || material == null ? null
            : new RenderContext(client.level, instance.instanceEpoch(), material));
    }

    void observeBossMessage(String message, boolean overlay, boolean catacombs, long epoch) {
        if (overlay || !catacombs || bossEpoch == epoch) return;
        String text = HypixelLocation.clean(message);
        if (text.startsWith("[BOSS] Maxor:") || text.startsWith("[BOSS] Storm:")
                || text.startsWith("[BOSS] Goldor:") || text.startsWith("[BOSS] Necron:")) {
            bossEpoch = epoch;
            KungDebugRecorder.event("colored-pillars", "floor-seven-boss epoch=" + epoch);
        }
    }

    PillarMaterial selectedMaterial(DungeonConfig config, boolean catacombs, HypixelDungeonFloor floor, long epoch) {
        if (!catacombs || bossEpoch != epoch) bossEpoch = Long.MIN_VALUE;
        // Boss dialogue confirms the arena even when entry/sidebar floor metadata was missed.
        // Keep this presentation fallback local: it cannot distinguish F7 from M7.
        boolean floorSeven = floor.floor() == 7 || !floor.known() && bossEpoch == epoch;
        return config.coloredPillarsEnabled() && catacombs && floorSeven ? config.pillarMaterial() : null;
    }

    private static void update(Minecraft client, RenderContext next) {
        RenderContext previous = renderContext;
        if (java.util.Objects.equals(previous, next)) return;
        renderContext = next;
        KungDebugRecorder.event("colored-pillars", next == null ? "render disabled"
            : "render material=" + next.material + " epoch=" + next.epoch);
        if (client.level == null) return;
        // A region-dirty request misses Sodium's in-flight initial meshes. Reload once on change
        // so those old snapshots cannot upload after the new material takes effect.
        McCompat.rebuildChunks(client);
    }

    /** Captured once by each render snapshot, never read from mutable config on a mesh worker. */
    public static PillarMaterial materialFor(ClientLevel level) {
        RenderContext context = renderContext;
        return context != null && context.level == level ? context.material : null;
    }

    public static BlockState replace(int x, int y, int z, BlockState original, PillarMaterial material) {
        if (material == null || y < 169 || y > 206 || x < 43 || x > 103 || z < 38 || z > 68) return original;
        if (!original.is(Blocks.DIORITE) && !original.is(Blocks.POLISHED_DIORITE)) return original;
        for (Pillar pillar : Pillar.ALL) {
            if (Math.abs(x - pillar.x) <= 3 && Math.abs(z - pillar.z) <= 3) return pillar.state(material);
        }
        return original;
    }

    private record RenderContext(ClientLevel level, long epoch, PillarMaterial material) { }

    // Storm's four 7x38x7 columns; the arena floor at Y=168 is deliberately outside the bounds.
    private enum Pillar {
        GREEN(46, 41, "lime"),
        YELLOW(46, 65, "yellow"),
        PURPLE(100, 65, "purple"),
        RED(100, 41, "red");

        private static final Pillar[] ALL = values();
        private final int x;
        private final int z;
        private final BlockState wool;
        private final BlockState glass;
        private final BlockState terracotta;

        // By id: 26.2 turned the per-colour Blocks fields into ColorCollections.
        Pillar(int x, int z, String color) {
            this.x = x;
            this.z = z;
            this.wool = block(color + "_wool");
            this.glass = KungBuild.MODRINTH ? wool : block(color + "_stained_glass");
            this.terracotta = block(color + "_terracotta");
        }

        private static BlockState block(String id) {
            return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
        }

        private BlockState state(PillarMaterial material) {
            return switch (material) {
                case WOOL -> wool;
                case GLASS -> glass;
                case TERRACOTTA -> terracotta;
            };
        }
    }
}
