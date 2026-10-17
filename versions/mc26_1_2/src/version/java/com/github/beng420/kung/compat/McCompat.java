package com.github.beng420.kung.compat;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

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
}
