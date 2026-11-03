package com.github.beng420.kung.feature.mining;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiningConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Warns a player who holds Tasty Mithril that Don Expresso (Mithril Gourmand event) is about to leave: a yellow
 * title with a clickable teleport message a minute before, then a red title and a pling every second.
 * The timing lives in {@link GourmandTimer}.
 */
public final class GourmandAlertFeature extends ConfigurableFeature<MiningConfig> {
    public static final GourmandAlertFeature INSTANCE = new GourmandAlertFeature();
    static final String TELEPORT_COMMAND = "/tptodonexpresso";
    /** Item id and name from the wiki; the trace area "gourmand" records what the real item carries. */
    private static final String TASTY_MITHRIL_ID = "MITHRIL_GOURMAND";
    private static final String TASTY_MITHRIL_NAME = "Tasty Mithril";
    /** Custom-data key that ties a Tasty Mithril to the event it dropped in (from the NEU/Skyblocker item repo). */
    private static final String EVENT_UUID_KEY = "gourmand_uuid";
    private static final Pattern TRACED_TEXT = Pattern.compile("(?i)gourmand|expresso|tasty mithril");
    private static final int SCAN_TICKS = 10;

    private final GourmandTimer timer = new GourmandTimer();
    private final Set<String> tracedSidebar = new HashSet<>();
    private final Set<String> leftovers = new HashSet<>();
    /** The server the timer runs on, learned on the first tick after a line set it; "" until then. */
    private String timerServer = "";
    private boolean hasMithril;
    private boolean itemTraced;
    private int ticks;

    private GourmandAlertFeature() { super(config -> config.mining); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observeMessage(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message.getString()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    @Override
    public boolean isEnabled() { return initialized() && config().gourmandAlertEnabled(); }

    void observeMessage(String text) {
        if (!isEnabled()) return;
        var event = timer.observe(text, now());
        if (event != GourmandTimer.Event.NONE) {
            KungDebugRecorder.event("gourmand", event + " line=" + text);
            timerServer = "";
            if (event == GourmandTimer.Event.STARTED) rememberLeftovers(Minecraft.getInstance());
        } else if (!text.startsWith("[Kung") && TRACED_TEXT.matcher(text).find()) {
            // A line about the event that the timer does not read: the next real run shows whether it is a signal.
            KungDebugRecorder.event("gourmand", "unread line=" + text);
        }
    }

    private void tick(Minecraft client) {
        if (!isEnabled() || client.player == null) {
            if (timer.active()) onReset();
            return;
        }
        int tick = ticks++;
        if (tick % 20 == 0) traceSidebar();
        if (!timer.active()) return;
        // The event belongs to one lobby: leaving it (the island, another server) drops the timer.
        String server = HypixelInstanceTracker.INSTANCE.serverId();
        if (timerServer.isEmpty()) {
            timerServer = server;
        } else if (!server.isEmpty() && !server.equals(timerServer)) {
            KungDebugRecorder.event("gourmand", "left server " + timerServer + " -> " + server);
            onReset();
            return;
        }
        if (tick % SCAN_TICKS == 0) hasMithril = holdsTastyMithril(client.player.getInventory());
        var alert = timer.tick(now(), hasMithril, config().gourmandCountdownSeconds());
        if (alert.kind() != GourmandTimer.Kind.NONE) show(client, alert);
    }

    private void show(Minecraft client, GourmandTimer.Alert alert) {
        float volume = config().gourmandVolume() / 100F;
        boolean warning = alert.kind() == GourmandTimer.Kind.WARNING;
        // Fade 0 and a stay of one second: each new title replaces the last without a flicker.
        McCompat.setTitleTimes(client, 0, warning ? 50 : 20, warning ? 10 : 0);
        String title = warning ? "Don leaves in " + span(alert.seconds()) : Integer.toString(alert.seconds());
        McCompat.setTitle(client, Component.literal(title)
            .withStyle(warning ? ChatFormatting.YELLOW : ChatFormatting.RED, ChatFormatting.BOLD));
        McCompat.setSubtitle(client, Component.literal(warning ? "Feed him your Tasty Mithril" : "Don Expresso leaves")
            .withStyle(ChatFormatting.GRAY));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(),
            warning ? 1.0F : 1.5F, volume));
        if (alert.link()) KungMessages.send(client, teleportMessage(alert.seconds()));
        KungDebugRecorder.event("gourmand", alert.kind() + " seconds=" + alert.seconds());
    }

    /** The warning in chat; a click runs {@code /tptodonexpresso} (the command works while an event is active). */
    static Component teleportMessage(int seconds) {
        var link = Component.literal("[Click to teleport to Don Expresso]").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(TELEPORT_COMMAND))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(TELEPORT_COMMAND))));
        return KungMessages.warning("Mining", "Don Expresso leaves in " + span(seconds) + ". ").copy().append(link);
    }

    static String span(int seconds) { return seconds >= 60 ? "1 minute" : seconds + " seconds"; }

    static boolean isTastyMithril(String skyblockId, String name) {
        return TASTY_MITHRIL_ID.equals(skyblockId) || TASTY_MITHRIL_NAME.equals(name == null ? null : name.strip());
    }

    /** Left over from an older event, so Don would refuse it: the player held it before this event started. */
    static boolean isCurrent(String eventUuid, Set<String> leftovers) {
        return eventUuid.isEmpty() || !leftovers.contains(eventUuid);
    }

    private static String eventUuid(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(EVENT_UUID_KEY, "");
    }

    private boolean holdsTastyMithril(Inventory inventory) {
        boolean holds = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !isTastyMithril(SkyblockItemId.of(stack), stack.getHoverName().getString())) continue;
            if (!itemTraced) {
                itemTraced = true;
                KungDebugRecorder.event("gourmand", "item name=" + stack.getHoverName().getString() + " count=" + stack.getCount()
                    + " leftover=" + !isCurrent(eventUuid(stack), leftovers)
                    + " data=" + stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
            }
            holds |= isCurrent(eventUuid(stack), leftovers);
        }
        return holds;
    }

    /** Called when the event starts: whatever Tasty Mithril is held now cannot be fed to this Don. */
    private void rememberLeftovers(Minecraft client) {
        leftovers.clear();
        if (client.player == null) return;
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !isTastyMithril(SkyblockItemId.of(stack), stack.getHoverName().getString())) continue;
            String uuid = eventUuid(stack);
            if (!uuid.isEmpty()) leftovers.add(uuid);
        }
        KungDebugRecorder.event("gourmand", "leftover events=" + leftovers.size());
    }

    /** The sidebar may carry the event timer; record each such line once so a trace can tell. */
    private void traceSidebar() {
        var context = HypixelInstanceTracker.INSTANCE;
        for (var lines : List.of(context.sidebarLines(), context.visibleLines())) {
            for (String line : lines) {
                if (TRACED_TEXT.matcher(line).find() && tracedSidebar.add(line)) {
                    KungDebugRecorder.event("gourmand", "sidebar line=" + line);
                }
            }
        }
    }

    @Override
    protected void onReset() {
        timer.reset();
        leftovers.clear();
        timerServer = "";
        hasMithril = false;
        itemTraced = false;
        ticks = 0;
        tracedSidebar.clear();
    }

    @Override
    protected void onShutdown() { onReset(); }

    private static long now() { return System.nanoTime() / 1_000_000; }
}
