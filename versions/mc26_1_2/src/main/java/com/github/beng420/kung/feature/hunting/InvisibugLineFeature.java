package com.github.beng420.kung.feature.hunting;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.InvisibugConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.slayer.BlazeSlayerFeature;
import com.github.beng420.kung.feature.hunting.InvisibugTracker.Stand;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * A line from the crosshair to the nearest Invisibug on Moonglade Marsh. An Invisibug has no name and no model: it is an
 * empty armor stand that gives off crit particles, so a crit particle near a blank stand marks that stand, and the
 * stand stays found while crits keep coming. The line follows the stand's interpolated position and is drawn in the HUD
 * at the projected point, like {@link BlazeSlayerFeature}'s text: particles, fire and water drawn after a world line
 * would paint over it.
 */
public final class InvisibugLineFeature extends ConfigurableFeature<InvisibugConfig> {
    public static final InvisibugLineFeature INSTANCE = new InvisibugLineFeature();

    private static final RenderStateDataKey<List<Vec3>> TARGET = RenderStateDataKey.create(() -> "kung:invisibug-target");
    /**
     * The point the line aims at, above the stand's feet: a resting bug's crit particles appear exactly this high over
     * the stand (2026-10-27 trace, 0.80 to 0.82 over dozens of packets).
     */
    private static final double AIM_HEIGHT = 0.8;
    /** A frame older than this was not drawn by the level (loading screen, paused render): show nothing. */
    private static final long FRAME_MAX_AGE_NANOS = 150_000_000L;
    /** Particles within this many blocks of a found bug are counted by type in the trace. */
    private static final double NEAR_BUG_RANGE = 3.0;

    private final InvisibugTracker tracker = new InvisibugTracker();
    private ClientLevel trackedLevel;
    private long ticks;
    /** Read from the sidebar and tab list twice a second, not per particle. */
    private boolean marsh;
    private long outsideEpoch = -1;
    private Frame frame = Frame.EMPTY;

    // One second of particle traffic, written to the trace and then reset.
    private int critPackets;
    private int critParticles;
    private int withStand;
    private int withoutStand;
    private double minStandDistance = Double.MAX_VALUE;
    private double maxStandDistance;
    private final Map<String, Integer> nearBug = new HashMap<>();

    private InvisibugLineFeature() {
        super(cfg -> cfg.invisibug);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LevelRenderEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(this::capture);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "invisibug_line"), (graphics, delta) -> draw(graphics));
    }

    @Override
    public boolean isEnabled() {
        return initialized() && config().enabled();
    }

    @Override
    protected void onReset() {
        tracker.clear();
        frame = Frame.EMPTY;
    }

    /** Called on the client thread for every particle packet, after vanilla has applied it. */
    public void observeParticles(ClientboundLevelParticlesPacket packet) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (!isEnabled() || level == null || client.player == null) {
            return;
        }
        boolean crit = packet.getParticle().getType() == ParticleTypes.CRIT;
        if (!crit && tracker.isEmpty()) {
            return;
        }
        if (!marsh) {
            if (crit) {
                traceOutside();
            }
            return;
        }
        syncLevel(level);

        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        if (!crit) {
            noteNearBug(level, String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType())), x, y, z);
            return;
        }

        critPackets++;
        critParticles += packet.getCount();
        // Generous box around the spot below the particle; source() applies the exact limits.
        double aside = InvisibugTracker.PARTICLE_MAX_SIDEWAYS + 0.5;
        List<Stand> stands = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class,
            new AABB(x - aside, y - InvisibugTracker.PARTICLE_MAX_ABOVE - 0.5, z - aside, x + aside, y, z + aside),
            InvisibugLineFeature::blank)) {
            stands.add(new Stand(stand.getId(), stand.getX(), stand.getY(), stand.getZ()));
        }
        Stand hit = InvisibugTracker.source(x, y, z, stands);
        if (hit == null) {
            withoutStand++;
            return;
        }

        withStand++;
        double distance = hit.distanceTo(x, y, z);
        minStandDistance = Math.min(minStandDistance, distance);
        maxStandDistance = Math.max(maxStandDistance, distance);
        boolean first = !tracker.contains(hit.id());
        tracker.seen(hit.id(), ticks);
        if (first) {
            Entity entity = level.getEntity(hit.id());
            KungDebugRecorder.event("invisibug", String.format(Locale.ROOT,
                "found id=%d stand=(%.2f %.2f %.2f) height=%.2f particle=(%.2f %.2f %.2f) distance=%.2f"
                    + " count=%d spread=%.2f,%.2f,%.2f speed=%.2f bugs=%d",
                hit.id(), hit.x(), hit.y(), hit.z(), entity == null ? -1.0 : entity.getBbHeight(), x, y, z, distance,
                packet.getCount(), packet.getXDist(), packet.getYDist(), packet.getZDist(), packet.getMaxSpeed(),
                tracker.ids().size()));
        }
    }

    /** An armor stand with no name and nothing in any slot. */
    private static boolean blank(ArmorStand stand) {
        if (stand.getCustomName() != null) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!stand.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Entity ids belong to one world; a new level must not inherit the old one's bugs. */
    private void syncLevel(ClientLevel level) {
        if (trackedLevel != level) {
            trackedLevel = level;
            onReset();
        }
    }

    private void noteNearBug(ClientLevel level, String type, double x, double y, double z) {
        for (int id : tracker.ids()) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.distanceToSqr(x, y, z) <= NEAR_BUG_RANGE * NEAR_BUG_RANGE) {
                nearBug.merge(type, 1, Integer::sum);
                return;
            }
        }
    }

    private void traceOutside() {
        var instance = HypixelInstanceTracker.INSTANCE;
        if (outsideEpoch == instance.instanceEpoch()) {
            return;
        }
        outsideEpoch = instance.instanceEpoch();
        KungDebugRecorder.event("invisibug", "crit particle outside Moonglade Marsh, ignored: instance="
            + instance.instanceLine() + " lines=" + instance.visibleLines());
    }

    private void tick(Minecraft client) {
        if (!isEnabled() || client.level == null) {
            tracker.clear();
            frame = Frame.EMPTY;
            marsh = false;
            return;
        }
        ClientLevel level = client.level;
        syncLevel(level);
        ticks++;
        if (ticks % 10 == 0) {
            var instance = HypixelInstanceTracker.INSTANCE;
            marsh = instance.tracking() && InvisibugTracker.inMarsh(instance.instanceLine(), instance.visibleLines());
        }
        tracker.prune(ticks, id -> level.getEntity(id) instanceof ArmorStand stand && stand.isAlive())
            .forEach((id, reason) -> KungDebugRecorder.event("invisibug",
                "lost id=" + id + " " + reason + " bugs=" + tracker.ids().size()));
        if (ticks % 20 == 0) {
            flushTrace();
        }
    }

    private void flushTrace() {
        if (critPackets > 0 || !nearBug.isEmpty() || !tracker.isEmpty()) {
            KungDebugRecorder.event("invisibug", String.format(Locale.ROOT,
                "per second: crit packets=%d particles=%d withStand=%d withoutStand=%d standDistance=%s bugs=%d near=%s",
                critPackets, critParticles, withStand, withoutStand,
                withStand == 0 ? "-" : String.format(Locale.ROOT, "%.2f..%.2f", minStandDistance, maxStandDistance),
                tracker.ids().size(), nearBug));
        }
        critPackets = 0;
        critParticles = 0;
        withStand = 0;
        withoutStand = 0;
        minStandDistance = Double.MAX_VALUE;
        maxStandDistance = 0;
        nearBug.clear();
    }

    private void extract(LevelExtractionContext context) {
        List<Vec3> target = List.of();
        if (isEnabled() && !tracker.isEmpty() && Minecraft.getInstance().player != null) {
            var camera = context.camera();
            Vec3 eye = camera.position();
            float partialTick = camera.getCameraEntityPartialTicks(context.deltaTracker());
            List<Stand> stands = new ArrayList<>();
            for (int id : tracker.ids()) {
                Entity entity = context.level().getEntity(id);
                if (entity != null && !entity.isRemoved()) {
                    Vec3 at = entity.getPosition(partialTick);
                    stands.add(new Stand(id, at.x, at.y + AIM_HEIGHT, at.z));
                }
            }
            Stand nearest = InvisibugTracker.nearest(eye.x, eye.y, eye.z, stands, InvisibugTracker.LINE_RANGE);
            if (nearest != null) {
                target = List.of(new Vec3(nearest.x(), nearest.y(), nearest.z()));
            }
        }
        // Each frame owns its target, so a world change or disabling cannot leave an old line behind.
        context.levelState().setData(TARGET, target);
    }

    /** Keeps this frame's target with the matrices the level was drawn with; the HUD projects it last. */
    private void capture(LevelRenderContext context) {
        List<Vec3> target = context.levelState().getDataOrDefault(TARGET, List.of());
        if (target.isEmpty()) {
            frame = Frame.EMPTY;
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        frame = new Frame(BlazeSlayerFeature.clipMatrix(camera, Minecraft.getInstance()), camera.pos, target.get(0),
            System.nanoTime());
    }

    private void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        Frame current = frame;
        if (current.target() == null || !isEnabled() || McCompat.hudHidden(client) || McCompat.screen(client) != null
            || System.nanoTime() - current.nanos() > FRAME_MAX_AGE_NANOS) {
            return;
        }

        Vec3 camera = current.camera();
        Vec3 target = current.target();
        Vector4f point = new Vector4f((float) (target.x - camera.x), (float) (target.y - camera.y),
            (float) (target.z - camera.z), 1F);
        current.clip().transform(point);
        InvisibugTracker.End end = InvisibugTracker.lineEnd(point.x, point.y, point.w);

        float centerX = graphics.guiWidth() / 2F;
        float centerY = graphics.guiHeight() / 2F;
        float endX = (end.x() * 0.5F + 0.5F) * graphics.guiWidth();
        float endY = (0.5F - end.y() * 0.5F) * graphics.guiHeight();
        float length = (float) Math.hypot(endX - centerX, endY - centerY);
        if (length < 1F) {
            return;
        }

        int color = config().color();
        int thickness = config().thickness();
        graphics.pose().pushMatrix();
        try {
            // A unit square stretched to the line's length and thickness, turned toward the target.
            graphics.pose().translate(centerX, centerY);
            graphics.pose().rotate((float) Math.atan2(endY - centerY, endX - centerX));
            graphics.pose().translate(0F, -thickness / 2F);
            graphics.pose().scale(length, thickness);
            graphics.fill(0, 0, 1, 1, color);
        } finally {
            graphics.pose().popMatrix();
        }
        if (end.onScreen()) {
            int dotX = Math.round(endX);
            int dotY = Math.round(endY);
            graphics.fill(dotX - 2, dotY - 2, dotX + 2, dotY + 2, color);
        }
    }

    private record Frame(Matrix4f clip, Vec3 camera, Vec3 target, long nanos) {
        static final Frame EMPTY = new Frame(new Matrix4f(), Vec3.ZERO, null, 0L);
    }
}
