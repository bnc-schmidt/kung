package com.github.beng420.kung.feature.crimson;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiniBossConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.ui.HudPose;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The Miniboss Timer HUD: one line per Crimson Isle miniboss with the time to its respawn. See {@link MiniBossTimer}. */
public final class MiniBossTimerFeature extends ConfigurableFeature<MiniBossConfig> {
    public static final MiniBossTimerFeature INSTANCE = new MiniBossTimerFeature();
    private static final int WIDTH = 190;
    private static final int LINE = 10;
    private static final int HEIGHT = MiniBoss.values().length * LINE + 8;

    private MiniBossTimerFeature() { super(config -> config.miniBoss); }

    @Override
    protected void onInitialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "miniboss_timer"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (!isEnabled() || !MiniBossTracker.INSTANCE.inLobby() || McCompat.hudHidden(client)
            || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), MiniBossTracker.INSTANCE::text);
    }

    private static void draw(GuiGraphicsExtractor graphics, MiniBossConfig config, Function<MiniBoss, MiniBossTimer.Text> text) {
        var font = Minecraft.getInstance().font;
        HudPose.begin(graphics, config.x(), config.y(), config.scale() / 100F);
        try {
            int y = 4;
            for (MiniBoss boss : MiniBoss.values()) {
                var status = text.apply(boss);
                var line = Component.literal(boss.displayName() + ": ").withColor(0x55FFFF)
                    .append(Component.literal(status.text()).withColor(status.color()));
                graphics.text(font, line, 4, y, 0xFFFFFFFF, true);
                y += LINE;
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    /** A sample for the HUD editor: one boss of each kind of estimate. */
    public static void drawPreview(GuiGraphicsExtractor graphics, MiniBossConfig config) {
        long now = System.currentTimeMillis();
        var down = new MiniBossTimer();
        down.down(now - 50_000);
        var alive = new MiniBossTimer();
        alive.spawned(now);
        var range = new MiniBossTimer();
        range.observe(now, true, true, false, false);
        var samples = new MiniBossTimer[] {down, alive, range, new MiniBossTimer(), new MiniBossTimer()};
        draw(graphics, config, boss -> samples[boss.ordinal()].text(now));
    }

    public static KungHudLayout.Bounds overlayBounds(MiniBossConfig config) {
        float scale = config.scale() / 100F;
        return new KungHudLayout.Bounds(config.x(), config.y(), Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
