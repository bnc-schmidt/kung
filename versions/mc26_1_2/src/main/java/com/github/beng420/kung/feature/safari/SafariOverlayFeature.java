package com.github.beng420.kung.feature.safari;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.category.SafariConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SafariOverlayFeature extends ConfigurableFeature<SafariConfig> {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "safari_uniques");
    private static final int PANEL = 0xAA111318;
    private static final int BORDER = 0xBB3498DB;
    private static final int TRACK = 0x40FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int DIM = 0xFF8C8C8C;
    /** Which zone the editor preview opens, and so how tall it is. */
    private static final SafariCritters.Region PREVIEW_REGION = SafariCritters.Region.ICY;
    /** One item per zone for its heading; filled on the client thread, since building a stack needs the game. */
    private static final Map<SafariCritters.Region, ItemStack> ICONS = new EnumMap<>(SafariCritters.Region.class);
    private static final Set<String> EXAMPLE = Set.of("Foxtrot", "Honeybug", "Hideonfloor", "Flitter",
        "Driftling", "Tepid", "Mantis Shrimp", "Bloodbat");
    private final SafariSession session = new SafariSession();

    public SafariOverlayFeature() { super(config -> config.safari); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        registerMessageObserver(this::observeMessage);
        SafariThrowTracker.register();
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID,
            (graphics, delta) -> render(graphics));
    }

    static void registerMessageObserver(Consumer<String> observer) {
        // Observe before chat mods cancel or rewrite a catch; never change message visibility.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) observer.accept(message.getString());
            return true;
        });
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    @Override
    protected void onReset() {
        session.reset();
    }

    private boolean updateContext(Minecraft client) {
        if (!isEnabled()) {
            onReset();
            return false;
        }
        var server = client.getCurrentServer();
        if (server == null || !HypixelLocation.isHypixelAddress(server.ip)) {
            onReset();
            return false;
        }
        // Synchronize on chat as well as ticks so a catch cannot be assigned to the old epoch.
        var tracker = HypixelInstanceTracker.INSTANCE;
        session.select(tracker.instanceEpoch(), tracker.instanceLine());
        return client.player != null && client.level != null;
    }

    private void tick(Minecraft client) {
        updateContext(client);
    }

    private void observeMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (!updateContext(client)) return;
        if (session.observeMessage(message, client.player.getGameProfile().name())) {
            KungDebugRecorder.event("safari-uniques", "epoch=" + HypixelInstanceTracker.INSTANCE.instanceEpoch()
                + " unique=" + session.caught().size() + " message=" + message);
        }
    }

    private void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || client.level == null || McCompat.hudHidden(client)
            || McCompat.screen(client) instanceof KungHudEditorScreen || KungHudEditorState.externalEditing()) return;
        if (updateContext(client) && session.active()) {
            draw(graphics, config(), session.region(), session.caught());
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, SafariConfig config) {
        draw(graphics, config, PREVIEW_REGION, EXAMPLE);
    }

    private static void draw(GuiGraphicsExtractor graphics, SafariConfig config, SafariCritters.Region current,
                             Set<String> caught) {
        Font font = Minecraft.getInstance().font;
        boolean columns = config.allZones();
        List<SafariLayout.Section> sections = SafariLayout.sections(current, caught, columns);
        int width = SafariLayout.width(sections, columns);
        float scale = config.scale() / 100.0F;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.x(), config.y());
            graphics.pose().scale(scale, scale);
            if (config.background()) {
                graphics.fill(-1, -1, width + 1, SafariLayout.height(sections, columns) + 1, BORDER);
                graphics.fill(0, 0, width, SafariLayout.height(sections, columns), PANEL);
            }
            int y = SafariLayout.PADDING;
            for (int index = 0; index < sections.size(); index++) {
                SafariLayout.Section section = sections.get(index);
                drawSection(graphics, font, section,
                    (columns ? index * SafariLayout.COLUMN_WIDTH : 0) + SafariLayout.PADDING, columns ? SafariLayout.PADDING : y);
                y += section.height() + SafariLayout.SECTION_GAP;
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** Heading (zone item, name, count, progress line), then one row per critter when the zone is open. */
    private static void drawSection(GuiGraphicsExtractor graphics, Font font, SafariLayout.Section section, int x, int y) {
        int zone = zoneColor(section.region());
        int inner = SafariLayout.COLUMN_WIDTH - SafariLayout.PADDING * 2;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y);
            graphics.pose().scale(0.5F, 0.5F);
            graphics.item(ICONS.computeIfAbsent(section.region(), SafariOverlayFeature::icon), 0, 0);
        } finally {
            graphics.pose().popMatrix();
        }
        String title = section.region().title();
        graphics.text(font, title, x + 11, y, zone, true);
        graphics.text(font, section.found() + "/" + section.total(), x + 15 + font.width(title), y,
            section.complete() ? GREEN : WHITE, true);
        graphics.fill(x, y + 10, x + inner, y + 11, TRACK);
        graphics.fill(x, y + 10, x + inner * section.found() / section.total(), y + 11, zone);
        int rowY = y + SafariLayout.HEADER_HEIGHT;
        for (SafariLayout.Row row : section.rows()) {
            graphics.text(font, row.found() ? "✔" : "✘", x + 1, rowY, row.found() ? GREEN : RED, true);
            graphics.text(font, row.name(), x + 11, rowY, row.found() ? DIM : WHITE, true);
            rowY += SafariLayout.ROW_HEIGHT;
        }
    }

    private static ItemStack icon(SafariCritters.Region region) {
        return new ItemStack(switch (region) {
            case FOREST -> Items.OAK_SAPLING;
            case CAVERN -> Items.IRON_PICKAXE;
            case ICY -> Items.BLUE_ICE;
            case HAUNTED -> Items.SOUL_LANTERN;
        });
    }

    static int zoneColor(SafariCritters.Region region) {
        return switch (region) {
            case FOREST -> 0xFF6FD66F;
            case CAVERN -> 0xFFE0A050;
            case ICY -> 0xFF7FDCFF;
            case HAUNTED -> 0xFFC27BFF;
        };
    }

    /** The preview's size, so the HUD editor's frame fits what {@link #drawPreview} draws. */
    public static OverlayBounds overlayBounds(SafariConfig config) {
        float scale = config.scale() / 100.0F;
        boolean columns = config.allZones();
        List<SafariLayout.Section> sections = SafariLayout.sections(PREVIEW_REGION, EXAMPLE, columns);
        return new OverlayBounds(config.x(), config.y(), Math.round(SafariLayout.width(sections, columns) * scale),
            Math.round(SafariLayout.height(sections, columns) * scale));
    }

    public record OverlayBounds(int x, int y, int width, int height) {}
}
