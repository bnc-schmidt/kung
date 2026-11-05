package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.GardenAlarmsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;

/** The Max Visitor Alarm of Garden Alarms; the effect alarms live in {@link EffectAlarmsFeature}. */
public final class VisitorAlarmFeature extends ConfigurableFeature<GardenAlarmsConfig> {
    public static final VisitorAlarmFeature INSTANCE = new VisitorAlarmFeature();
    // Vanilla tab ordering; the shared context's unordered rows cannot associate visitor names with their header.
    private static final Comparator<PlayerInfo> TAB_ORDER = Comparator.comparingInt(PlayerInfo::getTabListOrder).reversed()
        .thenComparingInt(info -> info.getGameMode() == GameType.SPECTATOR ? 1 : 0)
        .thenComparing(info -> info.getTeam() == null ? "" : info.getTeam().getName())
        .thenComparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER);

    private final VisitorAlarmState state = new VisitorAlarmState();
    private final AlarmMuteKey muteKey = new AlarmMuteKey();
    private final FeastContext profile = new FeastContext();
    private SimpleSoundInstance sound;
    // Keep ownership across alarm cycles/resets so the next alert replaces any surviving chat entry.
    private Component reminderMessage;
    private Set<String> candidateRoster;
    private long candidateSince;
    private long epoch = -1;
    private long worldSince;
    private long nextTone;
    private int polls;
    private boolean inGarden;
    private boolean announced;
    private boolean highTone;

    private VisitorAlarmFeature() { super(config -> config.gardenAlarms); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        KungMessages.onGameText(text -> {
            if (initialized() && isEnabled()) observeMessage(text);
        });
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled() && config().maxVisitor(); }

    private void tick(Minecraft client) {
        if (!isEnabled()) {
            if (state.queue() != null || sound != null) onReset();
            return;
        }
        if (!HypixelLocation.onHypixel(client) || client.getConnection() == null) {
            onReset();
            return;
        }
        long now = now();
        // Read every tick, so the edge is current even while nothing rings.
        if (muteKey.press(client, config().muteKeybind()) && state.ringing()) acknowledge(client, "key-mute");
        var context = HypixelInstanceTracker.INSTANCE;
        if (epoch != context.instanceEpoch()) {
            epoch = context.instanceEpoch();
            worldSince = now;
            candidateRoster = null;
            profile.worldChanged();
        }
        inGarden = context.tracking() && VisitorQueue.ownGarden(context.instanceLine(), context.sidebarLines(), context.visibleLines());
        if (++polls >= 5) {
            polls = 0;
            if (profile.observe(context.sidebarLines(), context.visibleLines())) clearQueue(client);
            if (inGarden && client.player != null && now - worldSince >= 2_000) observeQueue(client, now);
        }
        if (inGarden && candidateRoster != null && now - candidateSince >= 500
            && state.queue() != null && state.queue().visitors().equals(candidateRoster)) state.tick();
        if (state.ringing()) {
            if (!announced && client.player != null) {
                announced = true;
                record("started");
            }
            if (client.player != null && state.reminderDue(now)) {
                reminderMessage = KungMessages.replaceLocal(client, reminderMessage, alarmMessage());
            }
            if (now >= nextTone) {
                stopSound(client);
                highTone = !highTone;
                sound = SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(),
                    highTone ? 1.6F : 1.0F, config().volume() / 100F);
                client.getSoundManager().play(sound);
                nextTone = now + 500;
            }
        } else {
            if (announced) record("visitor-served");
            announced = false;
            nextTone = 0;
            stopSound(client);
        }
    }

    private void observeQueue(Minecraft client, long now) {
        var players = client.getConnection().getListedOnlinePlayers();
        if (players.size() > 100) return;
        List<String> lines = players.stream().sorted(TAB_ORDER).limit(80)
            .map(PlayerInfo::getTabListDisplayName).map(component -> component == null ? "" : component.getString()).toList();
        var snapshot = VisitorQueue.parse(lines);
        if (snapshot == null) { candidateRoster = null; return; }
        if (!snapshot.visitors().equals(candidateRoster)) {
            candidateRoster = snapshot.visitors();
            candidateSince = now;
            return;
        }
        // Require a stable roster across packet batches.
        if (now - candidateSince < 500) return;
        var previous = state.queue();
        boolean changed = previous == null || !previous.visitors().equals(snapshot.visitors()) || previous.full() != snapshot.full();
        state.observe(snapshot);
        if (changed) record("queue");
    }

    private void observeMessage(String message) {
        if (!FeastContext.profileMessage(message)) return;
        String previous = profile.profile();
        profile.observeProfileMessage(message);
        if (!previous.isBlank() && !previous.equals(profile.profile())) clearQueue(Minecraft.getInstance());
    }

    public static void observeContainerInput(int containerId, int slotId, int button, ContainerInput input) {
        if (!INSTANCE.isEnabled() || !INSTANCE.inGarden || !INSTANCE.state.ringing()
            || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)
            || button < 0 || button > 1) return;
        var client = Minecraft.getInstance();
        if (client.player == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) return;
        var menu = screen.getMenu();
        int topSlots = MenuItems.topSlots(menu);
        if (menu != client.player.containerMenu || menu.containerId != containerId
            || topSlots <= 13 || topSlots > 54 || slotId < 0 || slotId >= topSlots) return;
        var info = menu.slots.get(13).getItem();
        if (!info.has(DataComponents.LORE)) return;
        String title = screen.getTitle().getString();
        String action = menu.slots.get(slotId).getItem().getHoverName().getString();
        if (!VisitorOffer.isDecision(title, action,
            MenuItems.lore(info, 40), INSTANCE.state.queue().visitors())) return;
        INSTANCE.acknowledge(client, "offer-click title=" + title + " action=" + action);
    }

    static Component alarmMessage() {
        return KungMessages.warning("Visitors",
            "Visitor queue full! Accept/decline a visitor or click here to mute.").copy().withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/kung visitors mute"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "Mute sound and reminders until the queue is full again."))));
    }

    public static boolean muteCurrentAlarm() {
        return INSTANCE.isEnabled() && INSTANCE.acknowledge(Minecraft.getInstance(), "chat-mute");
    }

    private boolean acknowledge(Minecraft client, String reason) {
        if (!state.acknowledge()) return false;
        stopSound(client);
        announced = false;
        nextTone = 0;
        record(reason);
        return true;
    }

    private void clearQueue(Minecraft client) {
        state.reset();
        stopSound(client);
        candidateRoster = null;
        announced = false;
        nextTone = 0;
    }

    private void stopSound(Minecraft client) {
        if (sound != null) client.getSoundManager().stop(sound);
        sound = null;
    }

    private void record(String reason) {
        KungDebugRecorder.event("visitor-alarm", reason + " visitors="
            + (state.queue() == null ? List.of() : state.queue().visitors())
            + " full=" + (state.queue() != null && state.queue().full()) + " ringing=" + state.ringing());
    }

    @Override
    protected void onReset() {
        clearQueue(Minecraft.getInstance());
        inGarden = false;
        epoch = -1;
        polls = 0;
        profile.reset();
    }

    private static long now() { return System.nanoTime() / 1_000_000; }
}
