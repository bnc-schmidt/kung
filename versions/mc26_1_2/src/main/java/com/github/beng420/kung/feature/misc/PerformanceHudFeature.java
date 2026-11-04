package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.ui.HudPose;
import com.github.beng420.kung.util.ServerTpsTracker;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * TPS, FPS and ping in one line, plus an optional 30 second graph per stat. TPS comes from the
 * packet-arrival tracker, so a bad ping cannot pass itself off as server lag.
 */
public final class PerformanceHudFeature extends ConfigurableFeature<MiscConfig> {
    public static final PerformanceHudFeature INSTANCE = new PerformanceHudFeature();
    static final int SECONDS = 30;
    private static final int GRAPH_WIDTH = 120;
    private static final int GRAPH_HEIGHT = 24;
    private static final int LABEL_WIDTH = 22;
    /** Without a floor the axis zooms into the noise: 19.4 to 20 TPS filled the whole graph. */
    private static final float MIN_GRAPH_SPAN = 10F;
    private static final int TPS_COLOR = 0x55FFFF;
    private static final int FPS_COLOR = 0x55FF55;
    private static final int PING_COLOR = 0xFFAA00;
    /** Samples at the configured refresh rate, covering the last 30 seconds. */
    private final Deque<float[]> history = new ArrayDeque<>();
    private int ticksUntilSample;
    /** Round trip of our own ping request; Hypixel's tab list reports a constant instead. */
    private static volatile int ping;
    private static final long PING_REFRESH_MILLIS = 500;
    /** Client thread only. */
    private static long lastRequestMillis;

    private PerformanceHudFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "performance_hud"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().performanceHudEnabled(); }

    private void tick(Minecraft client) {
        if (--ticksUntilSample > 0) return;
        int tenths = config().performanceRefreshTenths();
        ticksUntilSample = Math.max(1, tenths * 2);
        if (!isEnabled() || client.level == null) {
            history.clear();
            return;
        }
        requestPing(client);
        history.addLast(new float[] {(float) tps(), client.getFps(), ping});
        while (history.size() > samples(tenths)) history.removeFirst();
    }

    /** However many samples fit into the 30 second window at the chosen refresh rate. */
    static int samples(int refreshTenths) {
        return Math.clamp(SECONDS * 10 / Math.max(1, refreshTenths), 2, 300);
    }

    private static double tps() {
        var snapshot = ServerTpsTracker.INSTANCE.snapshot();
        return snapshot.available() ? snapshot.current() : 0;
    }

    /** Vanilla's own measurement: send the time, the server echoes it back untouched. */
    public static void observePong(long sentMillis) {
        ping = (int) Math.clamp(Util.getMillis() - sentMillis, 0, 9999);
    }

    private static void requestPing(Minecraft client) {
        var connection = client.getConnection();
        if (connection != null) connection.send(new ServerboundPingRequestPacket(Util.getMillis()));
    }

    /**
     * The latest measured round trip, for features that time their clicks. It asks for a fresh one when the last
     * request is older than half a second, so it works with the HUD off; 0 until the first answer.
     */
    public static int freshPing(Minecraft client) {
        long now = Util.getMillis();
        if (now - lastRequestMillis >= PING_REFRESH_MILLIS) {
            lastRequestMillis = now;
            requestPing(client);
        }
        return ping;
    }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (!isEnabled() || client.level == null || McCompat.hudHidden(client)
            || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), List.copyOf(history), (float) tps(), client.getFps(), ping);
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, List<float[]> samples,
                             float tps, int fps, int ping) {
        var font = Minecraft.getInstance().font;
        var line = Component.literal(String.format(Locale.ROOT, "TPS: %.1f ", tps)).withColor(TPS_COLOR)
            .append(Component.literal("FPS: " + fps + " ").withColor(FPS_COLOR))
            .append(Component.literal("Ping: " + ping + "ms").withColor(PING_COLOR));
        HudPose.begin(graphics, config.performanceHudX(), config.performanceHudY(),
            config.performanceHudScale() / 100F);
        try {
            graphics.text(font, line, 4, 4, 0xFFFFFFFF, true);
            int top = 16;
            int capacity = samples(config.performanceRefreshTenths());
            if (config.performanceGraphTps()) top = graph(graphics, font, samples, capacity, 0, 20F, TPS_COLOR, top);
            if (config.performanceGraphFps()) top = graph(graphics, font, samples, capacity, 1, 0F, FPS_COLOR, top);
            if (config.performanceGraphPing()) graph(graphics, font, samples, capacity, 2, 0F, PING_COLOR, top);
        } finally {
            HudPose.end(graphics);
        }
    }

    /** One stat as a line over the window, with its low and high on the left; returns the next free y. */
    private static int graph(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, List<float[]> samples,
                             int capacity, int stat, float fixedMax, int color, int top) {
        int left = 4 + LABEL_WIDTH;
        graphics.fill(left, top, left + GRAPH_WIDTH, top + GRAPH_HEIGHT, 0x60000000);
        float max = fixedMax;
        float min = Float.MAX_VALUE;
        for (float[] sample : samples) {
            max = Math.max(max, sample[stat]);
            min = Math.min(min, sample[stat]);
        }
        if (samples.isEmpty() || max <= 0) return top + GRAPH_HEIGHT + 2;
        // A flat line still needs a span, and a tiny one would blow jitter up to full height.
        float low = Math.max(0F, Math.min(min, max - MIN_GRAPH_SPAN));
        float span = max - low;
        graphics.text(font, label(max), 4, top, 0xFF000000 | color, true);
        graphics.text(font, label(low), 4, top + GRAPH_HEIGHT - 8, 0xFF000000 | color, true);

        int previousX = -1;
        int previousY = -1;
        for (int index = 0; index < samples.size(); index++) {
            int x = left + index * GRAPH_WIDTH / capacity;
            int y = top + GRAPH_HEIGHT - 1 - Math.round((samples.get(index)[stat] - low) / span * (GRAPH_HEIGHT - 2));
            if (previousX < 0) {
                graphics.fill(x, y, x + 1, y + 1, 0xFF000000 | color);
            } else if (x == previousX) {
                // Denser than the graph is wide: the column keeps the whole move between both samples.
                graphics.fill(x, Math.min(previousY, y), x + 1, Math.max(previousY, y) + 1, 0xFF000000 | color);
            } else {
                // Fill every pixel column between the two samples, or the line falls apart into stripes.
                int lastY = previousY;
                for (int column = previousX + 1; column <= x; column++) {
                    int columnY = Math.round(previousY + (y - previousY) * (column - previousX) / (float) (x - previousX));
                    graphics.fill(column, Math.min(lastY, columnY), column + 1, Math.max(lastY, columnY) + 1,
                        0xFF000000 | color);
                    lastY = columnY;
                }
            }
            previousX = x;
            previousY = y;
        }
        return top + GRAPH_HEIGHT + 2;
    }

    private static String label(float value) {
        return value >= 1000 ? Math.round(value / 100) / 10F + "k" : String.valueOf(Math.round(value));
    }

    /** Graph rows only exist while their stat is on; the HUD box has to match. */
    static int height(MiscConfig config) {
        int graphs = (config.performanceGraphTps() ? 1 : 0) + (config.performanceGraphFps() ? 1 : 0)
            + (config.performanceGraphPing() ? 1 : 0);
        return 16 + graphs * (GRAPH_HEIGHT + 2);
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        var samples = new ArrayDeque<float[]>();
        for (int index = 0; index < samples(config.performanceRefreshTenths()); index++) {
            samples.addLast(new float[] {19 + (index % 3) * 0.5F, 110 + index % 17, 120 + index % 40});
        }
        draw(graphics, config, List.copyOf(samples), 19.9F, 118, 146);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.performanceHudScale() / 100F;
        boolean graphs = config.performanceGraphTps() || config.performanceGraphFps() || config.performanceGraphPing();
        int width = graphs ? GRAPH_WIDTH + LABEL_WIDTH + 8 : 120;
        return new KungHudLayout.Bounds(config.performanceHudX(), config.performanceHudY(),
            Math.round(width * scale), Math.round(height(config) * scale));
    }
}
