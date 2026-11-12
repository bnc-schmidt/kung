package com.github.beng420.kung.config;

import com.github.beng420.kung.feature.crimson.MiniBossTimerFeature;
import com.github.beng420.kung.feature.dungeon.DungeonMapFeature;
import com.github.beng420.kung.feature.dungeon.DragonDebuffHud;
import com.github.beng420.kung.feature.dungeon.DungeonSecretsOverlayFeature;
import com.github.beng420.kung.feature.dungeon.DungeonSplitsOverlayFeature;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.feature.dungeon.M7DragonFeature;
import com.github.beng420.kung.feature.garden.FeastOverlayFeature;
import com.github.beng420.kung.feature.misc.SuperpairsHelperFeature;
import com.github.beng420.kung.feature.misc.BowDrawIndicatorFeature;
import com.github.beng420.kung.feature.misc.StorageHudFeature;
import com.github.beng420.kung.feature.misc.FrozenBlazeHudFeature;
import com.github.beng420.kung.feature.misc.CurrentChatDisplayFeature;
import com.github.beng420.kung.feature.misc.PerformanceHudFeature;
import com.github.beng420.kung.feature.misc.RagTickTimerFeature;
import com.github.beng420.kung.feature.slayer.TarantulaHelperFeature;
import com.github.beng420.kung.feature.misc.SackTrackerFeature;
import com.github.beng420.kung.feature.safari.SafariOverlayFeature;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** HUD geometry is in Minecraft GUI units; config setters remain the persistence owner. */
public final class KungHudLayout {
    private KungHudLayout() { }

    public static List<Entry> entries(KungConfig config, DungeonStateTracker tracker) {
        return List.of(
            new Entry("dungeon_map", "Dungeon Map", () -> DungeonMapFeature.overlayBounds(config.dungeon),
                config.dungeon::x, config.dungeon::y, config.dungeon::setX, config.dungeon::setY,
                config.dungeon::scale, config.dungeon::setScale, config.dungeon::enabled, config.dungeon::setEnabled, null),
            new Entry("dungeon_splits", "Splits Overlay",
                () -> DungeonSplitsOverlayFeature.overlayBounds(config.splits, tracker.splitTracker()),
                config.splits::x, config.splits::y, config.splits::setX, config.splits::setY,
                config.splits::scale, config.splits::setScale, config.splits::enabled, config.splits::setEnabled,
                graphics -> DungeonSplitsOverlayFeature.drawPreview(graphics, config.splits, tracker.splitTracker())),
            new Entry("dungeon_secrets", "Secrets Overlay", () -> DungeonSecretsOverlayFeature.overlayBounds(config.dungeon),
                config.dungeon::secretsOverlayX, config.dungeon::secretsOverlayY,
                config.dungeon::setSecretsOverlayX, config.dungeon::setSecretsOverlayY,
                config.dungeon::secretsOverlayScale, config.dungeon::setSecretsOverlayScale,
                config.dungeon::secretsOverlayEnabled, config.dungeon::setSecretsOverlayEnabled,
                graphics -> DungeonSecretsOverlayFeature.drawPreview(graphics, config.dungeon)),
            new Entry("superpairs", "Superpairs Helper", () -> SuperpairsHelperFeature.overlayBounds(config.misc),
                config.misc::superpairsHelperX, config.misc::superpairsHelperY,
                config.misc::setSuperpairsHelperX, config.misc::setSuperpairsHelperY,
                config.misc::superpairsHelperScale, config.misc::setSuperpairsHelperScale,
                config.misc::superpairsHelperEnabled, config.misc::setSuperpairsHelperEnabled, null),
            new Entry("feast_progress", "Feast Progress", () -> FeastOverlayFeature.overlayBounds(config.feast),
                config.feast::x, config.feast::y, config.feast::setX, config.feast::setY,
                config.feast::scale, config.feast::setScale, config.feast::enabled, config.feast::setEnabled,
                graphics -> FeastOverlayFeature.drawPreview(graphics, config.feast)),
            new Entry("safari_uniques", "Safari Uniques", () -> SafariOverlayFeature.overlayBounds(config.safari),
                config.safari::x, config.safari::y, config.safari::setX, config.safari::setY,
                config.safari::scale, config.safari::setScale, config.safari::enabled, config.safari::setEnabled,
                graphics -> SafariOverlayFeature.drawPreview(graphics, config.safari)),
            new Entry("dragon_debuff", "Dragon Debuff", () -> DragonDebuffHud.overlayBounds(config.dungeon),
                config.dungeon::dragonDebuffX, config.dungeon::dragonDebuffY,
                config.dungeon::setDragonDebuffX, config.dungeon::setDragonDebuffY,
                config.dungeon::dragonDebuffScale, config.dungeon::setDragonDebuffScale,
                config.dungeon::dragonDebuffHudShown, config.dungeon::setDragonDebuffHudShown,
                graphics -> DragonDebuffHud.drawPreview(graphics, config.dungeon)),
            new Entry("dragon_prio", "Dragon Prio", () -> M7DragonFeature.overlayBounds(config.dungeon),
                config.dungeon::dragonPrioX, config.dungeon::dragonPrioY,
                config.dungeon::setDragonPrioX, config.dungeon::setDragonPrioY,
                config.dungeon::dragonPrioScale, config.dungeon::setDragonPrioScale,
                config.dungeon::dragonPrioHudShown, config.dungeon::setDragonPrioHudShown,
                graphics -> M7DragonFeature.drawPrioPreview(graphics, config.dungeon)),
            new Entry("bow_draw_indicator", "Bow Draw Indicator", () -> BowDrawIndicatorFeature.overlayBounds(config.misc),
                config.misc::bowDrawIndicatorX, config.misc::bowDrawIndicatorY,
                config.misc::setBowDrawIndicatorX, config.misc::setBowDrawIndicatorY,
                config.misc::bowDrawIndicatorScale, config.misc::setBowDrawIndicatorScale,
                config.misc::bowDrawIndicatorEnabled, config.misc::setBowDrawIndicatorEnabled,
                graphics -> BowDrawIndicatorFeature.drawPreview(graphics, config.misc)),
            new Entry("frozen_blaze_hud", "Frozen Blaze HUD", () -> FrozenBlazeHudFeature.overlayBounds(config.misc),
                config.misc::frozenBlazeHudX, config.misc::frozenBlazeHudY,
                config.misc::setFrozenBlazeHudX, config.misc::setFrozenBlazeHudY,
                config.misc::frozenBlazeHudScale, config.misc::setFrozenBlazeHudScale,
                config.misc::frozenBlazeHudEnabled, config.misc::setFrozenBlazeHudEnabled,
                graphics -> FrozenBlazeHudFeature.drawPreview(graphics, config.misc)),
            new Entry("rag_tick_timer", "Rag Tick Timer", () -> RagTickTimerFeature.overlayBounds(config.misc),
                config.misc::ragTimerX, config.misc::ragTimerY,
                config.misc::setRagTimerX, config.misc::setRagTimerY,
                config.misc::ragTimerScale, config.misc::setRagTimerScale,
                config.misc::ragTimerEnabled, config.misc::setRagTimerEnabled,
                graphics -> RagTickTimerFeature.drawPreview(graphics, config.misc)),
            new Entry("storage_hud", "Storage HUD", () -> StorageHudFeature.overlayBounds(config.misc),
                config.misc::storageHudX, config.misc::storageHudY,
                config.misc::setStorageHudX, config.misc::setStorageHudY,
                config.misc::storageHudScale, config.misc::setStorageHudScale,
                config.misc::storageHudEnabled, config.misc::setStorageHudEnabled,
                graphics -> StorageHudFeature.drawPreview(graphics, config.misc)),
            new Entry("egg_sac_countdown", "Egg Sac Spawn Timer", () -> TarantulaHelperFeature.overlayBounds(config.slayer),
                config.slayer::eggSacCountdownX, config.slayer::eggSacCountdownY,
                config.slayer::setEggSacCountdownX, config.slayer::setEggSacCountdownY,
                config.slayer::eggSacCountdownScale, config.slayer::setEggSacCountdownScale,
                config.slayer::eggSacCountdownEnabled, config.slayer::setEggSacCountdownEnabled,
                graphics -> TarantulaHelperFeature.drawCountdownPreview(graphics, config.slayer)),
            new Entry("performance_hud", "Performance", () -> PerformanceHudFeature.overlayBounds(config.misc),
                config.misc::performanceHudX, config.misc::performanceHudY,
                config.misc::setPerformanceHudX, config.misc::setPerformanceHudY,
                config.misc::performanceHudScale, config.misc::setPerformanceHudScale,
                config.misc::performanceHudEnabled, config.misc::setPerformanceHudEnabled,
                graphics -> PerformanceHudFeature.drawPreview(graphics, config.misc)),
            new Entry("current_chat_display", "Current Chat Display", () -> CurrentChatDisplayFeature.overlayBounds(config.misc),
                config.misc::currentChatDisplayX, config.misc::currentChatDisplayY,
                config.misc::setCurrentChatDisplayX, config.misc::setCurrentChatDisplayY,
                config.misc::currentChatDisplayScale, config.misc::setCurrentChatDisplayScale,
                config.misc::currentChatDisplayEnabled, config.misc::setCurrentChatDisplayEnabled,
                graphics -> CurrentChatDisplayFeature.drawPreview(graphics, config.misc)),
            new Entry("sack_tracker", "Sack Tracker", () -> SackTrackerFeature.overlayBounds(config.misc),
                config.misc::sackTrackerX, config.misc::sackTrackerY,
                config.misc::setSackTrackerX, config.misc::setSackTrackerY,
                config.misc::sackTrackerScale, config.misc::setSackTrackerScale,
                config.misc::sackTrackerEnabled, config.misc::setSackTrackerEnabled,
                graphics -> SackTrackerFeature.drawPreview(graphics, config.misc)),
            new Entry("miniboss_timer", "Miniboss Timer", () -> MiniBossTimerFeature.overlayBounds(config.miniBoss),
                config.miniBoss::x, config.miniBoss::y, config.miniBoss::setX, config.miniBoss::setY,
                config.miniBoss::scale, config.miniBoss::setScale, config.miniBoss::enabled, config.miniBoss::setEnabled,
                graphics -> MiniBossTimerFeature.drawPreview(graphics, config.miniBoss))
        );
    }

    public record Bounds(int x, int y, int width, int height) { }

    private static final int PLACEHOLDER = 0x669AA4B2;
    private static final int PLACEHOLDER_TEXT = 0xFFFFFFFF;

    public record Entry(
        String id,
        String name,
        Supplier<Bounds> boundsSupplier,
        IntSupplier configX,
        IntSupplier configY,
        IntConsumer setX,
        IntConsumer setY,
        IntSupplier scalePercent,
        IntConsumer setScalePercent,
        BooleanSupplier enabledSupplier,
        Consumer<Boolean> setEnabled,
        /** Draws the HUD with sample data; null draws a labeled placeholder. Never enables a feature or reads a world. */
        Consumer<GuiGraphicsExtractor> preview
    ) {
        public Bounds bounds() {
            return boundsSupplier.get();
        }

        public void drawPreview(GuiGraphicsExtractor graphics) {
            if (preview != null) {
                preview.accept(graphics);
                return;
            }
            Bounds bounds = bounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), PLACEHOLDER);
            var font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(name, Math.max(0, bounds.width() - 8));
            graphics.text(font, label, bounds.x() + (bounds.width() - font.width(label)) / 2,
                bounds.y() + bounds.height() / 2 - 4, PLACEHOLDER_TEXT, true);
        }

        public void moveX(float boxX) {
            if (!Float.isFinite(boxX)) return;
            int current = configX.getAsInt();
            int next = configPosition(boxX, bounds().x(), current);
            if (next != current) setX.accept(next);
        }

        public void moveY(float boxY) {
            if (!Float.isFinite(boxY)) return;
            int current = configY.getAsInt();
            int next = configPosition(boxY, bounds().y(), current);
            if (next != current) setY.accept(next);
        }

        public float scale() {
            return scalePercent.getAsInt() / 100.0F;
        }

        public void scale(float scale) {
            if (!Float.isFinite(scale)) return;
            int next = Math.clamp(Math.round(scale * 100.0F), 25, 300);
            if (next != scalePercent.getAsInt()) setScalePercent.accept(next);
        }

        public boolean enabled() {
            return enabledSupplier.getAsBoolean();
        }

        public void enabled(boolean enabled) {
            if (enabled != enabled()) setEnabled.accept(enabled);
        }

        private static int configPosition(float boxPosition, int currentBoxPosition, int currentConfigPosition) {
            // Borders/text margins scale independently from the stored content origin.
            long offset = (long) currentBoxPosition - currentConfigPosition;
            long next = Math.round((double) boxPosition - offset);
            return (int) Math.clamp(next, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
    }
}
