package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.DungeonConfig.DragonDebuffScope;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.ui.HudPose;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;

public final class DragonDebuffHud {
    private static final int WIDTH = 410;

    private DragonDebuffHud() { }

    static List<Component> lines(List<DungeonDebuffTracker.Dragon> dragons, long tick) {
        if (dragons.isEmpty()) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("M7 Debuff").withStyle(ChatFormatting.AQUA));
        for (var dragon : dragons) {
            lines.add(KungMessages.highlight(dragon.summary(tick)));
        }
        return List.copyOf(lines);
    }

    static void draw(GuiGraphicsExtractor graphics, DungeonConfig config, List<DungeonDebuffTracker.Dragon> dragons, long tick) {
        var font = Minecraft.getInstance().font;
        var lines = lines(dragons, tick);
        HudPose.begin(graphics, config.dragonDebuffX(), config.dragonDebuffY(), config.dragonDebuffScale() / 100F);
        try {
            for (int row = 0; row < lines.size(); row++) {
                graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(lines.get(row), WIDTH - 8)),
                    4, 4 + row * 11, 0xFFFFFFFF, true);
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, DungeonConfig config) {
        List<DungeonDebuffTracker.Dragon> examples = new ArrayList<>();
        for (var statue : M7DragonTracker.Statue.values()) {
            var dragon = new DungeonDebuffTracker.Dragon(statue, 0);
            for (int offset : new int[] {2, 2, 3, 4, 5}) dragon.arrows.add(offset);
            dragon.nearestInWave = true;
            dragon.sprayTick = 6;
            examples.add(dragon);
        }
        draw(graphics, config, config.dragonDebuffScope() == DragonDebuffScope.NEAREST_STATUE
            ? List.of(examples.getLast()) : examples, 40);
    }

    public static KungHudLayout.Bounds overlayBounds(DungeonConfig config) {
        float scale = config.dragonDebuffScale() / 100F;
        return new KungHudLayout.Bounds(config.dragonDebuffX(), config.dragonDebuffY(),
            Math.round(WIDTH * scale), Math.round((config.dragonDebuffScope() == DragonDebuffScope.NEAREST_STATUE ? 30 : 74) * scale));
    }
}
