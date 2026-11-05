package com.github.beng420.kung.compat;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import java.util.ArrayList;
import kotlin.Unit;
import org.polyfrost.oneconfig.api.config.v1.ConfigManager;
import org.polyfrost.oneconfig.api.config.v1.Properties;
import org.polyfrost.oneconfig.api.config.v1.Visualizer;
import org.polyfrost.oneconfig.api.hud.v1.HudManager;
import org.polyfrost.oneconfig.internal.ui.hud.CompatOverlayRenderer;

/** Loaded only after the optional-mod check and OneConfig's client initialization. */
final class KungOneConfigHuds {
    private static boolean registered;

    private KungOneConfigHuds() { }

    static void register(DungeonStateTracker tracker) {
        if (registered) return;
        var config = KungConfig.get();
        var layouts = KungHudLayout.entries(config, tracker);
        for (var layout : layouts) {
            var properties = new ArrayList<>(KungOneConfig.hudProperties(layout.name()));
            if (layout.id().equals("dungeon_map")) {
                var textScale = Properties.functional(config.dungeon::textScale, value -> {
                    if (!ConfigManager.isRebindingProfiles() && value != config.dungeon.textScale()) {
                        config.dungeon.setTextScale(value);
                    }
                }, "kung_text_scale", "Text Scale (%)", "Scale map labels independently of the map.", Integer.class);
                textScale.addMetadata("visualizer", Visualizer.SliderVisualizer.class);
                textScale.addMetadata("min", 50F);
                textScale.addMetadata("max", 200F);
                textScale.addMetadata("step", 5F);
                textScale.addMetadata("default", 100);
                properties.add(textScale);
            }
            new KungOneConfigHud(layout, properties, () -> !ConfigManager.isRebindingProfiles()).register();
        }
        CompatOverlayRenderer.register(graphics -> {
            if (HudManager.INSTANCE.isEditing()) {
                for (var layout : layouts) if (layout.enabled()) layout.drawPreview(graphics);
            }
            return Unit.INSTANCE;
        });
        KungHudEditorState.setExternalEditor(() -> HudManager.INSTANCE.isEditing());
        registered = true;
    }
}
