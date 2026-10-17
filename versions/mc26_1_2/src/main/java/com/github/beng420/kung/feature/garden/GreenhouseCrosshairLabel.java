package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.feature.garden.GreenhousePickHook.Aim;
import com.github.beng420.kung.feature.garden.GreenhousePickHook.Cell;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * A small label below the crosshair naming the hologram cell or soil marker the player aims at, because several
 * crops' heads look alike (Sunflower and Moonflower differ by a ring). Only while the hologram is drawn and no
 * screen is open. The aimed thing comes from {@link GreenhousePickHook#aimed}, which has no side effects: this never
 * scans the plot and never sends anything. The labelled volumes are rebuilt only when the hologram, the scan or the
 * corner changes, so a frame costs one ray test over them and one text call. Client thread only.
 */
final class GreenhouseCrosshairLabel {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "greenhouse_label");
    /** GUI pixels from the screen centre (the crosshair) to the top of the text. */
    private static final int OFFSET_Y = 12;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static int builtVersion = -1;
    private static int builtScan = -1;
    private static BlockPos builtCorner;
    private static List<Cell> cells = List.of();
    private static boolean failed;

    private GreenhouseCrosshairLabel() { }

    static void initialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID,
            (graphics, delta) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        // Guarded like the pick hook: an exception inside a HUD element would crash the frame.
        try {
            Minecraft client = Minecraft.getInstance();
            if (failed || !GreenhouseHologramSettings.showCrosshairLabel() || !GreenhouseHologram.isDrawn()
                || client.player == null || McCompat.hudHidden(client) || McCompat.screen(client) != null) return;
            Optional<BlockPos> corner = GreenhouseLocator.current(client, false);
            if (corner.isEmpty()) return;
            Optional<Aim> aim = GreenhousePickHook.aimed(new GreenhousePickHook.LiveGame(client).view(),
                cells(corner.get()));
            if (aim.isEmpty()) return;
            String text = aim.get().text();
            graphics.text(client.font, text, (graphics.guiWidth() - client.font.width(text)) / 2,
                graphics.guiHeight() / 2 + OFFSET_Y, TEXT_COLOR, true);
        } catch (RuntimeException failure) {
            failed = true;
            KungMod.LOGGER.warn("Greenhouse crosshair label stopped after an error", failure);
        }
    }

    private static List<Cell> cells(BlockPos corner) {
        int version = GreenhouseHologram.version();
        int scan = GreenhouseScanner.version();
        if (version != builtVersion || scan != builtScan || !corner.equals(builtCorner)) {
            builtVersion = version;
            builtScan = scan;
            builtCorner = corner;
            cells = GreenhousePickHook.labelCells(GreenhouseHologram.cellBoxes(), GreenhouseHologram.soilGhosts(),
                GreenhouseScanner.extras(version), corner);
        }
        return cells;
    }
}
