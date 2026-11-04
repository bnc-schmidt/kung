package com.github.beng420.kung.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A HUD or menu part drawn in its own coordinates: {@code begin}, draw inside try, {@code end} in finally. */
public final class HudPose {
    private HudPose() { }

    /** Saves the pose, moves the origin to (x, y) and scales by {@code scale}. */
    public static void begin(GuiGraphicsExtractor graphics, float x, float y, float scale) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
    }

    /** Restores the pose saved by {@link #begin}. */
    public static void end(GuiGraphicsExtractor graphics) {
        graphics.pose().popMatrix();
    }
}
