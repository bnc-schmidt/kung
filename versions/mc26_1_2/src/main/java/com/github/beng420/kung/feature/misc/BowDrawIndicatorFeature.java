package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.DungeonServerTickEvents;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.ui.HudPose;
import com.github.beng420.kung.util.KungDebugRecorder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

import static com.github.beng420.kung.feature.misc.BowDrawProgress.*;

public final class BowDrawIndicatorFeature extends ConfigurableFeature<MiscConfig> {
    public static final BowDrawIndicatorFeature INSTANCE = new BowDrawIndicatorFeature();
    private static final java.util.regex.Pattern SHORTBOW_LINE =
        java.util.regex.Pattern.compile("(?:.*\\s)?shortbow(?:\\s.*|:.*)?", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int WIDTH = 200;
    private static final int HEIGHT = 41;
    private static final int BAR_X = 6;
    private static final int BAR_WIDTH = WIDTH - BAR_X * 2;
    private final BowDrawProgress progress = new BowDrawProgress();
    private ItemStack bow = ItemStack.EMPTY;
    private InteractionHand hand;
    private Player owner;
    private ClientLevel level;
    private int slot;
    private long startedAtNanos;
    private int releasedTicks = -1;
    private long releasedAtNanos;

    private BowDrawIndicatorFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::validateDraw);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        DungeonServerTickEvents.register(() -> {
            validateDraw(Minecraft.getInstance());
            progress.serverTick();
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "bow_draw_indicator"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().bowDrawIndicatorEnabled(); }

    /** Observe the actual local use transition, including server/compatibility-driven starts. */
    public void observeUse(Player player, InteractionHand usedHand) {
        var client = Minecraft.getInstance();
        if (!isEnabled() || player != client.player || client.level == null
            || !HypixelLocation.onHypixel(client)
            || !player.isUsingItem() || player.getUsedItemHand() != usedHand
            || !chargeableBow(player.getUseItem())) return;
        owner = player;
        level = client.level;
        bow = player.getUseItem();
        hand = usedHand;
        slot = player.getInventory().getSelectedSlot();
        startedAtNanos = System.nanoTime();
        progress.start();
        KungDebugRecorder.event("bow-draw", "start hand=" + hand + " slot=" + slot);
    }

    /** Server ticks of the draw in progress, or of the one released under 200 ms ago; -1 otherwise. */
    public int drawTicks() {
        return isEnabled() && progress.visible(System.nanoTime()) ? progress.ticks() : -1;
    }

    public void observeStop(Player player) {
        if (player != owner) return;
        validateDraw(Minecraft.getInstance());
        stopDraw();
    }

    private void stopDraw() {
        if (!progress.active()) return;
        long now = System.nanoTime();
        KungDebugRecorder.event("bow-draw", "stop ticks=" + progress.ticks()
            + " heldMs=" + (now - startedAtNanos) / 1_000_000L);
        releasedTicks = progress.ticks();
        releasedAtNanos = now;
        progress.stop(now);
    }

    /**
     * Ticks of the last released draw, if it ended under half a second ago. When spamming, the next
     * draw is already running by the time the server's arrow comes back, so drawTicks would read that one.
     */
    public int releasedTicks() {
        return isEnabled() && System.nanoTime() - releasedAtNanos < 500_000_000L ? releasedTicks : -1;
    }

    static boolean chargeableBow(ItemStack stack) {
        if (!(stack.getItem() instanceof BowItem)) return false;
        var lore = stack.get(DataComponents.LORE);
        return lore == null || lore.lines().stream().noneMatch(line ->
            SHORTBOW_LINE.matcher(HypixelLocation.clean(line.getString())).matches());
    }

    private void validateDraw(Minecraft client) {
        if (owner == null) return;
        if (!isEnabled() || client.player != owner || client.level != level || !owner.isAlive()
            || hand == InteractionHand.MAIN_HAND && slot != owner.getInventory().getSelectedSlot()
            || !sameDrawBow(bow, owner.getItemInHand(hand))) {
            onReset();
            return;
        }
        // Vanilla replaces useItem with the current same-type stack after inventory updates.
        bow = owner.getItemInHand(hand);
        if (progress.active()) {
            if (!owner.isUsingItem()) stopDraw();
            else if (owner.getUsedItemHand() != hand || !ItemStack.isSameItem(bow, owner.getUseItem())) onReset();
        }
        if (!progress.visible(System.nanoTime())) onReset();
    }

    static boolean sameDrawBow(ItemStack previous, ItemStack current) {
        return !current.isEmpty() && (previous == current || ItemStack.isSameItem(previous, current) && chargeableBow(current));
    }

    @Override
    protected void onReset() {
        if (progress.active()) KungDebugRecorder.event("bow-draw", "reset ticks=" + progress.ticks() + " reason=context-or-item");
        progress.reset();
        bow = ItemStack.EMPTY;
        hand = null;
        owner = null;
        level = null;
    }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        validateDraw(client);
        if (!progress.visible(System.nanoTime()) || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), progress.ticks(), progress.active());
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, int ticks, boolean drawing) {
        var font = Minecraft.getInstance().font;
        int color = ticks < MIN_SHOT_TICKS ? 0xFFEF6868 : ticks < FULL_DRAW_TICKS ? 0xFFFFDA55 : 0xFF55DD88;
        HudPose.begin(graphics, config.bowDrawIndicatorX(), config.bowDrawIndicatorY(),
            config.bowDrawIndicatorScale() / 100F);
        try {
            graphics.fill(0, 0, WIDTH, HEIGHT, 0x99111318);
            graphics.text(font, drawing ? "Bow Draw" : "Last Draw", BAR_X, 4, 0xFFFFFFFF, true);
            String power = "~" + Math.round(BowDrawProgress.power(ticks) * 100) + "% power";
            graphics.text(font, power, WIDTH - BAR_X - font.width(power), 4, color, true);
            graphics.fill(BAR_X - 1, 16, WIDTH - BAR_X + 1, 26, 0xFF101318);
            graphics.fill(BAR_X, 17, WIDTH - BAR_X, 25, 0xFF343A44);
            graphics.fill(BAR_X, 17, BAR_X + Math.round(BAR_WIDTH * BowDrawProgress.power(ticks)), 25, color);
            int previousLabelEnd = 0;
            // Duplicate markers share one line, in the first one's color.
            var markers = new java.util.TreeMap<Integer, Integer>();
            for (var threshold : config.bowDrawThresholds()) markers.putIfAbsent(threshold.ticks(), threshold.color());
            // M7 dragons, from where you stand: cyan the least draw that reaches the dragon, red the most
            // that still misses every block, white the draw the aim dot is for.
            var hint = config.bowDrawDragonTicksEnabled()
                ? com.github.beng420.kung.feature.dungeon.M7DragonFeature.INSTANCE.drawHint() : null;
            if (hint != null) {
                markers.put(hint.min(), 0xFF55FFFF);
                markers.put(hint.max(), 0xFFFF5555);
                markers.put(hint.draw(), 0xFFFFFFFF);
            }
            for (var line : markers.entrySet()) {
                int marker = line.getKey();
                int x = BAR_X + Math.round(BAR_WIDTH * BowDrawProgress.power(marker));
                // Not reached yet: the same color at half strength.
                int markerColor = ticks >= marker ? line.getValue() : (line.getValue() & 0x00FFFFFF) | 0x80000000;
                graphics.fill(x - 1, 15, x, 27, markerColor);
                String label = marker + "t";
                int labelX = Math.min(x - font.width(label) / 2, WIDTH - 2 - font.width(label));
                // Keep every tick mark; omit only labels that would overlap their neighbor.
                if (labelX >= previousLabelEnd + 2) {
                    graphics.text(font, label, labelX, 30, markerColor, true);
                    previousLabelEnd = labelX + font.width(label);
                }
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, 13, true);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.bowDrawIndicatorScale() / 100F;
        return new KungHudLayout.Bounds(config.bowDrawIndicatorX(), config.bowDrawIndicatorY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
