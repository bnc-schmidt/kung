package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.DungeonServerTickEvents;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Tick timer for the Ragnarock Axe: the cast, the strength and the rest of the cooldown, in server ticks.
 * The right-click starts it; chat and actionbar only confirm or correct it (all unverified, see the "rag" trace).
 */
public final class RagTickTimerFeature extends ConfigurableFeature<MiscConfig> {
    public static final RagTickTimerFeature INSTANCE = new RagTickTimerFeature();
    static final String RAG_ID = "RAGNAROCK_AXE";
    private static final Pattern CANCELLED = Pattern.compile("^Ragnarock was cancelled due to .*");
    private static final Pattern ON_COOLDOWN = Pattern.compile("^This ability is on cooldown for (\\d+)s\\.?$");
    private static final Pattern CASTING_IN = Pattern.compile("CASTING IN (\\d+)s");
    private static final int WIDTH = 120;
    private static final int HEIGHT = 28;
    private final RagTimer timer = new RagTimer();
    private int castSlot = -1;
    private int lastCastingIn = -1;
    private RagTimer.Phase tracedPhase = RagTimer.Phase.IDLE;

    private RagTickTimerFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::validateCast);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        DungeonServerTickEvents.register(() -> {
            validateCast(Minecraft.getInstance());
            timer.tick();
            tracePhase();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message.getString(), overlay));
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "rag_tick_timer"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().ragTimerEnabled(); }

    /** Every use of the main-hand item, from MultiPlayerGameModeMixin; only the Ragnarock Axe counts. */
    public void observeUse(Player player, InteractionHand hand) {
        if (!isEnabled() || hand != InteractionHand.MAIN_HAND || player != Minecraft.getInstance().player
            || !isRag(player.getMainHandItem())) return;
        boolean started = timer.use(config().ragTimerCooldownTicks());
        if (started) castSlot = player.getInventory().getSelectedSlot();
        KungDebugRecorder.event("rag", (started ? "click start" : "click ignored") + " " + state());
    }

    private void observeMessage(String text, boolean overlay) {
        var player = Minecraft.getInstance().player;
        if (!isEnabled() || player == null) return;
        if (overlay) {
            var casting = CASTING_IN.matcher(text);
            if (casting.find() && isRag(player.getMainHandItem())) {
                int seconds = Integer.parseInt(casting.group(1));
                boolean running = timer.phase() == RagTimer.Phase.CAST;
                timer.castingIn(seconds, config().ragTimerCooldownTicks());
                if (!running && timer.phase() == RagTimer.Phase.CAST) castSlot = player.getInventory().getSelectedSlot();
                if (seconds != lastCastingIn) KungDebugRecorder.event("rag", "actionbar casting in " + seconds + "s " + state());
                lastCastingIn = seconds;
            } else if (text.contains("NOT ENOUGH MANA") && timer.rejected()) {
                KungDebugRecorder.event("rag", "no mana, start taken back " + state());
            }
            return;
        }
        String line = text.strip();
        var cooldown = ON_COOLDOWN.matcher(line);
        if (CANCELLED.matcher(line).matches()) {
            boolean aborted = timer.abort();
            KungDebugRecorder.event("rag", "chat cancelled" + (aborted ? "" : " (no cast)") + " " + state());
        } else if (cooldown.matches() && isRag(player.getMainHandItem())) {
            timer.onCooldown(Integer.parseInt(cooldown.group(1)));
            KungDebugRecorder.event("rag", "chat on cooldown " + cooldown.group(1) + "s " + state());
        }
    }

    /** The cast ends when the axe leaves the hand; the strength and the cooldown do not care. */
    private void validateCast(Minecraft client) {
        if (!isEnabled()) {
            if (timer.phase() != RagTimer.Phase.IDLE) onReset();
            return;
        }
        if (timer.phase() != RagTimer.Phase.CAST) return;
        var player = client.player;
        if (player == null || player.getInventory().getSelectedSlot() != castSlot || !isRag(player.getMainHandItem())) {
            timer.abort();
            KungDebugRecorder.event("rag", "swapped away " + state());
        }
    }

    private void tracePhase() {
        var phase = timer.phase();
        if (phase == tracedPhase) return;
        KungDebugRecorder.event("rag", "phase " + tracedPhase + " -> " + phase + " " + state());
        tracedPhase = phase;
    }

    private String state() { return "since=" + timer.since() + " left=" + timer.left() + " phase=" + timer.phase(); }

    static boolean isRag(ItemStack stack) { return RAG_ID.equals(SkyblockItemId.of(stack)); }

    @Override
    protected void onReset() {
        timer.reset();
        castSlot = -1;
        lastCastingIn = -1;
        tracedPhase = RagTimer.Phase.IDLE;
    }

    @Override
    protected void onShutdown() { onReset(); }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        validateCast(client);
        if (!isEnabled() || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        var config = config();
        var phase = timer.phase();
        if (!RagTimer.visible(phase, config.ragTimerEffectEnabled(), config.ragTimerCooldownEnabled())) return;
        draw(graphics, config, phase, timer.left(), timer.total());
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, RagTimer.Phase phase, int left, int total) {
        var font = Minecraft.getInstance().font;
        String label = switch (phase) {
            case CAST -> "Rag Cast";
            case EFFECT -> "Rag Strength";
            default -> "Rag Cooldown";
        };
        int color = switch (phase) {
            case CAST -> 0xFFFFAA00;
            case EFFECT -> 0xFFFF5555;
            default -> 0xFF55AAFF;
        };
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.ragTimerX(), config.ragTimerY());
            float scale = config.ragTimerScale() / 100F;
            graphics.pose().scale(scale, scale);
            graphics.fill(0, 0, WIDTH, HEIGHT, 0x99111318);
            graphics.text(font, label, 6, 4, color, true);
            String ticks = left + "t";
            graphics.text(font, ticks, WIDTH - 6 - font.width(ticks), 4, 0xFFFFFFFF, true);
            graphics.fill(5, 16, WIDTH - 5, 24, 0xFF101318);
            graphics.fill(6, 17, WIDTH - 6, 23, 0xFF343A44);
            graphics.fill(6, 17, 6 + Math.round((WIDTH - 12) * (float) left / Math.max(1, total)), 23, color);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, RagTimer.Phase.CAST, 42, RagTimer.CAST_TICKS);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.ragTimerScale() / 100F;
        return new KungHudLayout.Bounds(config.ragTimerX(), config.ragTimerY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
