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
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft API that moved between 26.1.2 and 26.2. Each versions/mcX/src/version/java holds
 * a copy with the same signatures; shared code calls these instead of the moved members.
 */
public final class McCompat {
    public static final EntityType<?> ARMOR_STAND = EntityType.ARMOR_STAND;
    public static final EntityType<?> PLAYER = EntityType.PLAYER;
    public static final EntityType<?> ENDER_DRAGON = EntityType.ENDER_DRAGON;
    public static final EntityType<?> SPIDER = EntityType.SPIDER;
    public static final EntityType<?> CAVE_SPIDER = EntityType.CAVE_SPIDER;

    private McCompat() {
    }

    public static Screen screen(Minecraft client) { return client.screen; }

    public static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }

    public static Overlay overlay(Minecraft client) { return client.getOverlay(); }

    public static boolean hudHidden(Minecraft client) { return client.options.hideGui; }

    public static ChatComponent chat(Minecraft client) { return client.gui.getChat(); }

    /** The HUD's tick counter, which the chat uses for its message ages. */
    public static int guiTicks(Minecraft client) { return client.gui.getGuiTicks(); }

    public static PlayerTabOverlay tabList(Minecraft client) { return client.gui.getTabList(); }

    public static void setTitleTimes(Minecraft client, int fadeIn, int stay, int fadeOut) {
        client.gui.setTimes(fadeIn, stay, fadeOut);
    }

    public static void setTitle(Minecraft client, Component title) { client.gui.setTitle(title); }

    public static void setSubtitle(Minecraft client, Component subtitle) { client.gui.setSubtitle(subtitle); }

    public static net.minecraft.client.gui.components.toasts.ToastManager toasts(Minecraft client) {
        return client.getToastManager();
    }

    public static Camera camera(Minecraft client) { return client.gameRenderer.getMainCamera(); }

    /** Re-meshes every loaded section, like F3+A. */
    public static void rebuildChunks(Minecraft client) { client.levelRenderer.allChanged(); }

    /** Draws right away with the current pose; 26.2 submits the same geometry for later. */
    public static void draw(LevelRenderContext context, RenderType type, SubmitNodeCollector.CustomGeometryRenderer geometry) {
        geometry.render(context.poseStack().last(), context.bufferSource().getBuffer(type));
        context.bufferSource().endBatch(type);
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
