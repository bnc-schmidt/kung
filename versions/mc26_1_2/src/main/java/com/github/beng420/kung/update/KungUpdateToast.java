package com.github.beng420.kung.update;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfigScreen;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiTheme;
import java.net.URI;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;

/** One transient update card; it never opens a screen or grabs the mouse by itself. */
final class KungUpdateToast {
    private static final UiTheme THEME = UiTheme.settings();
    private static final URI RELEASE_PAGE = URI.create("https://github.com/bnc-schmidt/kung/releases/latest");
    private final KungUpdateToastState state = new KungUpdateToastState();
    private String title = "";
    private String currentVersion = "";
    private String latestVersion = "";
    private boolean preview;
    private Runnable onFinished;
    private Connection connection;
    private Screen drawnScreen;
    private KungUpdateToastState.Layout drawnLayout;
    private float drawnScale;
    private int drawnWidth;
    private int drawnHeight;

    void initializeClient() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "update_notice"), (graphics, delta) -> {
                Minecraft client = Minecraft.getInstance();
                if (McCompat.screen(client) == null) render(client, graphics, -1, -1);
            });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) ->
                render(client, graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(client, current, event));
        });
    }

    void show(Minecraft client, String title, String currentVersion, String latestVersion, boolean preview,
              Runnable onFinished) {
        clear();
        this.title = title;
        this.currentVersion = currentVersion;
        this.latestVersion = latestVersion;
        this.preview = preview;
        this.onFinished = onFinished;
        connection = client.getConnection() == null ? null : client.getConnection().getConnection();
        drawnLayout = null;
        state.show(now());
    }

    void clear() {
        Runnable finished = onFinished;
        onFinished = null;
        state.clear();
        drawnLayout = null;
        drawnScreen = null;
        connection = null;
        if (finished != null) finished.run();
    }

    boolean active() { return state.active(); }

    void disconnected(Connection connection) {
        if (this.connection == connection) clear();
    }

    void tick(Minecraft client, boolean updateAvailable) {
        if (!state.active()) return;
        var handler = client.getConnection();
        if (connection == null || !connection.isConnected() || (!preview && !updateAvailable)
            || (handler != null && handler.getConnection() != connection)) {
            clear();
            return;
        }
        var window = client.getWindow();
        float scale = normalScale(client);
        var layout = state.layout(window.getGuiScaledWidth() / scale, window.getGuiScaledHeight() / scale);
        boolean hovered = McCompat.screen(client) != null && layout.contains(
            client.mouseHandler.getScaledXPos(window) / scale, client.mouseHandler.getScaledYPos(window) / scale);
        state.advance(now(), canDisplay(client), hovered);
        if (!state.active()) clear();
    }

    static boolean canDisplay(Minecraft client) {
        return client.player != null && client.level != null && client.getConnection() != null
            && !McCompat.hudHidden(client) && McCompat.overlay(client) == null
            && !(McCompat.screen(client) instanceof KungConfigScreen settings && settings.hasReleaseNotesPopup())
            && !(McCompat.screen(client) instanceof LevelLoadingScreen);
    }

    private void render(Minecraft client, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!state.active() || !canDisplay(client)) return;
        float scale = normalScale(client);
        var layout = state.layout(graphics.guiWidth() / scale, graphics.guiHeight() / scale);
        boolean interactive = McCompat.screen(client) != null;
        double pointerX = interactive ? mouseX / scale : -1;
        double pointerY = interactive ? mouseY / scale : -1;
        boolean hovered = interactive && layout.contains(pointerX, pointerY);
        state.advance(now(), true, hovered);
        if (!state.active()) {
            clear();
            return;
        }
        layout = state.layout(graphics.guiWidth() / scale, graphics.guiHeight() / scale);
        drawnLayout = layout;
        drawnScreen = McCompat.screen(client);
        drawnScale = scale;
        drawnWidth = graphics.guiWidth();
        drawnHeight = graphics.guiHeight();

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(layout.x(), layout.y());
            graphics.pose().scale(layout.scale(), layout.scale());
            int width = KungUpdateToastState.WIDTH;
            int height = KungUpdateToastState.HEIGHT;
            graphics.fill(2, 3, width + 2, height + 3, 0x66000000);
            graphics.fill(0, 0, width, height, THEME.panel());
            graphics.fill(0, 0, width, 2, THEME.accent());
            text(client, graphics, title, 12, 12, 192, THEME.text());
            text(client, graphics, "Installed: v" + currentVersion, 12, 31, width - 24, THEME.muted());
            text(client, graphics, "Latest: v" + latestVersion, 12, 44, width - 24, THEME.success());
            button(client, graphics, layout, KungUpdateToastState.CLOSE, "x", pointerX, pointerY, false);
            button(client, graphics, layout, KungUpdateToastState.UPDATES, "Open Updates", pointerX, pointerY, true);
            button(client, graphics, layout, KungUpdateToastState.GITHUB, "GitHub", pointerX, pointerY, false);
            text(client, graphics, interactive ? (hovered ? "Timer paused" : "Click an option above") : "Open chat to click",
                12, 89, width - 24, THEME.muted());
            graphics.fill(0, height - 2, width, height, THEME.control());
            graphics.fill(0, height - 2, Math.round(width * state.remaining()), height, THEME.accent());
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private boolean click(Minecraft client, Screen screen, MouseButtonEvent event) {
        if (!canDisplay(client) || drawnLayout == null || drawnScreen != screen
            || drawnWidth != client.getWindow().getGuiScaledWidth()
            || drawnHeight != client.getWindow().getGuiScaledHeight()) return false;
        var action = state.hitTest(drawnLayout, event.x() / drawnScale, event.y() / drawnScale, event.button());
        switch (action) {
            case NONE -> { return false; }
            case BLOCK -> { return true; }
            case DISMISS -> clear();
            case UPDATES -> {
                clear();
                client.schedule(() -> McCompat.setScreen(client, KungConfigScreen.updates()));
            }
            case GITHUB -> {
                clear();
                client.schedule(() -> ConfirmLinkScreen.confirmLinkNow(screen, RELEASE_PAGE));
            }
        }
        return true;
    }

    private static void button(Minecraft client, GuiGraphicsExtractor graphics, KungUpdateToastState.Layout layout,
        UiBounds bounds, String label, double mouseX, double mouseY, boolean primary) {
        boolean hovered = layout.contains(bounds, mouseX, mouseY);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), hovered ? THEME.controlHover()
            : primary ? THEME.accentDark() : THEME.control());
        graphics.centeredText(client.font, label, bounds.x() + bounds.width() / 2, bounds.y() + 6, THEME.text());
    }

    private static void text(Minecraft client, GuiGraphicsExtractor graphics, String text, int x, int y, int width, int color) {
        if (client.font.width(text) > width) text = client.font.plainSubstrByWidth(text, width - client.font.width("...")) + "...";
        graphics.text(client.font, text, x, y, color, true);
    }

    private static float normalScale(Minecraft client) {
        var window = client.getWindow();
        return (float) window.calculateScale(client.options.guiScale().get(), client.isEnforceUnicode())
            / Math.max(1, window.getGuiScale());
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }
}
