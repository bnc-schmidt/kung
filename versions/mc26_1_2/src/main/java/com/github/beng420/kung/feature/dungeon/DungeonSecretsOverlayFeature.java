package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.ui.HudPose;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** The party's secrets of this run, live: one row per player, "?" where that player's Kung has not reported. */
public final class DungeonSecretsOverlayFeature extends ConfigurableFeature<DungeonConfig> {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "dungeon_secrets_overlay");
    private static final int WIDTH = 130;
    private static final int ROW_HEIGHT = 10;
    private static final int PADDING = 2;
    private static final int PREVIEW_ROWS = 4;
    private static final int TEXT = 0xFFE9EDF2;
    private static final int SELF = 0xFFFFF176;
    private static final int MUTED = 0xFF858B95;
    private static final int TEAM = 0xFF55FFFF;
    private final DungeonStateTracker dungeonStateTracker;

    public DungeonSecretsOverlayFeature(DungeonStateTracker dungeonStateTracker) {
        super(config -> config.dungeon);
        this.dungeonStateTracker = java.util.Objects.requireNonNull(dungeonStateTracker);
    }

    @Override
    protected void onInitialize() {
        // Same layer as the map and the splits: container backgrounds dim all of them, and F1 hides them.
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID,
            (graphics, deltaTracker) -> render(graphics));
    }

    @Override
    public boolean isEnabled() {
        return config().secretsOverlayEnabled();
    }

    private void render(GuiGraphicsExtractor graphics) {
        if (KungHudEditorState.externalEditing()) return;
        DungeonConfig config = config();
        boolean editing = McCompat.screen(Minecraft.getInstance()) instanceof KungHudEditorScreen;
        if (!config.secretsOverlayEnabled() || editing || !dungeonStateTracker.isInDungeonArea()) return;
        var runStats = dungeonStateTracker.runStats();
        draw(graphics, config, runStats.secretRows(), runStats.partySecretTotal());
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, DungeonConfig config) {
        draw(graphics, config, List.of(
            new DungeonRunStats.SecretRow("You", "7", true),
            new DungeonRunStats.SecretRow("Teammate", "4", false),
            new DungeonRunStats.SecretRow("Another", "?", false),
            new DungeonRunStats.SecretRow("Friend", "2", false)), 21);
    }

    private static void draw(GuiGraphicsExtractor graphics, DungeonConfig config,
                             List<DungeonRunStats.SecretRow> rows, int partyTotal) {
        if (rows.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        HudPose.begin(graphics, config.secretsOverlayX(), config.secretsOverlayY(), config.secretsOverlayScale() / 100.0F);
        try {
            int y = PADDING;
            graphics.text(client.font, "Secrets", PADDING, y, MUTED, true);
            for (DungeonRunStats.SecretRow row : rows) {
                y += ROW_HEIGHT;
                drawRow(graphics, y, row.name(), row.count(), row.self() ? SELF : TEXT,
                    row.count().equals("?") ? MUTED : row.self() ? SELF : TEXT);
            }
            if (partyTotal >= 0) {
                y += ROW_HEIGHT;
                drawRow(graphics, y, "Team", Integer.toString(partyTotal), TEAM, TEAM);
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    private static void drawRow(GuiGraphicsExtractor graphics, int y, String name, String count, int nameColor, int countColor) {
        Minecraft client = Minecraft.getInstance();
        graphics.text(client.font, name, PADDING, y, nameColor, true);
        graphics.text(client.font, count, WIDTH - PADDING - client.font.width(count), y, countColor, true);
    }

    public static KungHudLayout.Bounds overlayBounds(DungeonConfig config) {
        float scale = config.secretsOverlayScale() / 100.0F;
        int height = PADDING * 2 + (PREVIEW_ROWS + 2) * ROW_HEIGHT;
        return new KungHudLayout.Bounds(config.secretsOverlayX(), config.secretsOverlayY(),
            Math.round(WIDTH * scale), Math.round(height * scale));
    }
}
