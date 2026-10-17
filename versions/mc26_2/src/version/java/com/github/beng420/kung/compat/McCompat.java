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
import net.minecraft.world.entity.EntityTypes;

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
}
