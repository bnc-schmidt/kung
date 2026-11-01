package com.github.beng420.kung.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.Model;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft API that moved between 26.1.2 and 26.2. Each versions/mcX/src/version/java holds
 * a copy with the same signatures; shared code calls these instead of the moved members.
 */
public final class McCompat {
    public static final EntityType<?> ARMOR_STAND = EntityTypes.ARMOR_STAND;
    public static final EntityType<?> PLAYER = EntityTypes.PLAYER;
    public static final EntityType<?> ENDER_DRAGON = EntityTypes.ENDER_DRAGON;
    public static final EntityType<?> SPIDER = EntityTypes.SPIDER;
    public static final EntityType<?> CAVE_SPIDER = EntityTypes.CAVE_SPIDER;

    private McCompat() {
    }

    public static Screen screen(Minecraft client) { return client.gui.screen(); }

    public static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    public static Overlay overlay(Minecraft client) { return client.gui.overlay(); }

    public static boolean hudHidden(Minecraft client) { return client.gui.hud.isHidden(); }

    public static ChatComponent chat(Minecraft client) { return client.gui.hud.getChat(); }

    public static PlayerTabOverlay tabList(Minecraft client) { return client.gui.hud.getTabList(); }

    public static void setTitleTimes(Minecraft client, int fadeIn, int stay, int fadeOut) {
        client.gui.hud.setTimes(fadeIn, stay, fadeOut);
    }

    public static void setTitle(Minecraft client, Component title) { client.gui.hud.setTitle(title); }

    public static void setSubtitle(Minecraft client, Component subtitle) { client.gui.hud.setSubtitle(subtitle); }

    public static net.minecraft.client.gui.components.toasts.ToastManager toasts(Minecraft client) {
        return client.gui.toastManager();
    }

    public static Camera camera(Minecraft client) { return client.gameRenderer.mainCamera(); }

    /** Re-meshes every loaded section, like F3+A. */
    public static void rebuildChunks(Minecraft client) { client.levelExtractor.allChanged(); }

    /** 26.2 has no immediate buffers here; the geometry runs when the frame's submits flush. */
    public static void draw(LevelRenderContext context, RenderType type, SubmitNodeCollector.CustomGeometryRenderer geometry) {
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), type, geometry);
    }

    public static ParticleOptions particleOptions(ClientboundLevelParticlesPacket packet) { return packet.getParticle(); }

    public static double particleX(ClientboundLevelParticlesPacket packet) { return packet.getX(); }

    public static double particleY(ClientboundLevelParticlesPacket packet) { return packet.getY(); }

    public static double particleZ(ClientboundLevelParticlesPacket packet) { return packet.getZ(); }

    public static float particleXDist(ClientboundLevelParticlesPacket packet) { return packet.getXDist(); }

    public static float particleYDist(ClientboundLevelParticlesPacket packet) { return packet.getYDist(); }

    public static float particleZDist(ClientboundLevelParticlesPacket packet) { return packet.getZDist(); }

    public static float particleMaxSpeed(ClientboundLevelParticlesPacket packet) { return packet.getMaxSpeed(); }

    public static int particleCount(ClientboundLevelParticlesPacket packet) { return packet.getCount(); }

    public static boolean particleOverrideLimiter(ClientboundLevelParticlesPacket packet) { return packet.isOverrideLimiter(); }

    /** Opens the folder or file in the system file browser. */
    public static void openPath(java.nio.file.Path path) { net.minecraft.util.Util.getPlatform().openPath(path); }

    /** What Minecraft.startUseItem does with an interaction result: a hand swing when the client is to play it. */
    public static void swingForResult(LocalPlayer player, InteractionHand hand, InteractionResult result) {
        if (result instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
        }
    }

    /** Where an entity that is still gliding to a new position will end up; its own position when it is not moving. */
    public static Vec3 interpolatedPosition(Entity entity) {
        var interpolation = entity.getInterpolation();
        return interpolation == null ? entity.position() : interpolation.position();
    }

    public static <S> void submitModel(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack pose,
                                       RenderType type, int light, int overlay, int tint) {
        collector.submitModel(model, state, pose, type, light, overlay, tint, null, 0, null);
    }
}
