package com.github.beng420.kung.feature.foraging;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.ForagingConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import com.github.beng420.kung.util.ServerTickPacketContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Moonglade Beacon menus ("Tune Frequency" and "Upgrade Signal Strength"): a number on each of the color, speed
 * and pitch buttons says how many left clicks (positive) or right clicks (negative) make it match the reference,
 * and a button that already matches is lit green. Unlike SkyHanni's solver it never blocks a click.
 * <p>
 * The color is plain text in the menu. The reference speed and pitch are timed ({@link BeaconTiming}): its marker
 * steps along the top row, once per beat, and each step plays a note block sound in its pitch. A "?" on a button
 * means that is not known yet. The Strength menu's two sets play over each other, so only Tune Frequency has a pitch
 * for now. The trace area {@code beacon} records the menu, every click and every sound while it is open.
 */
public final class BeaconSolverFeature extends ConfigurableFeature<ForagingConfig> {
    public static final BeaconSolverFeature INSTANCE = new BeaconSolverFeature();
    static final String AREA = "beacon";
    private static final Pattern TITLE = Pattern.compile("^(Tune Frequency|Upgrade Signal Strength)$");
    private static final String STRENGTH_TITLE = "Upgrade Signal Strength";
    private static final String NOTE_SOUND = "minecraft:block.note_block.bass";
    private static final int TOP_SLOT_COUNT = 54;
    /** The reference marker moves along this row; its pane shows the color to match. */
    private static final int REFERENCE_FIRST = 10;
    private static final int REFERENCE_LAST = 16;
    /** The player's own marker moves along this row. */
    private static final int OURS_FIRST = 28;
    private static final int OURS_LAST = 34;
    /** The color button of the lowest set; speed and pitch follow every second slot. */
    private static final int BUTTONS = 46;
    /** In the Strength menu the normal set moves up a row, to make room for the enchanted one. */
    private static final int STRENGTH_ROW_SHIFT = 9;
    /** No count for this slot: not a button, or its value cannot be read. */
    private static final int NONE = Integer.MIN_VALUE;
    /** A button whose reference is still being timed. */
    private static final int UNKNOWN = Integer.MIN_VALUE + 1;
    private static final int LORE_LIMIT = 320;
    private static final int GREEN = 0x8055FF55;

    /** One tuning set: its buttons, and what has been timed of its reference. The enchanted set has glinting markers. */
    private static final class Controls {
        final boolean enchanted;
        final int color;
        final int speed;
        final int pitch;
        final BeaconTiming timing = new BeaconTiming();
        int tracedSpeed = -1;
        int tracedPitch = -1;

        Controls(boolean enchanted, int color) {
            this.enchanted = enchanted;
            this.color = color;
            this.speed = color + 2;
            this.pitch = color + 4;
        }
    }

    // Updated from the applied packet hooks and the client tick, so only ever on the client thread.
    private static AbstractContainerMenu activeMenu;
    private static List<Controls> sets = List.of();
    private static long openedNanos;
    private static int ticks;
    /** What each top slot last held, so the trace only gets what changed. */
    private static final Map<Integer, String> seen = new HashMap<>();
    /** The count last drawn on each button, for the click lines: the click itself empties the slot before they run. */
    private static final Map<Integer, String> shown = new HashMap<>();

    private BeaconSolverFeature() { super(config -> config.foraging); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(BeaconSolverFeature::tick);
    }

    @Override
    protected void onReset() { end("reset"); }

    @Override
    public boolean isEnabled() { return initialized() && config().beaconSolverEnabled(); }

    private static void tick(Minecraft client) {
        if (!INSTANCE.isEnabled() || !ensureSession(client)) {
            end("closed-or-disabled");
            return;
        }
        ticks++;
        long now = System.nanoTime();
        for (Controls set : sets) {
            set.timing.settle(now);
            traceTimed(set);
        }
    }

    /** Called from the applied ClientPacketListener slot hooks, never Netty receipt. */
    public static void observeMenuPacket(int containerId) {
        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled() || client.player == null || client.player.containerMenu.containerId != containerId
            || !ensureSession(client)) {
            return;
        }
        for (int index = 0; index < TOP_SLOT_COUNT; index++) {
            String now = describe(activeMenu.slots.get(index).getItem());
            if (now.equals(seen.put(index, now))) continue;
            trace("slot=" + index + " " + now + " bundle=" + ServerTickPacketContext.isApplyingBundle());
        }
        long now = System.nanoTime();
        for (Controls set : sets) {
            int reference = markerAt(REFERENCE_FIRST, REFERENCE_LAST, set.enchanted);
            if (reference >= 0) set.timing.referenceMarker(reference, now);
            int ours = markerAt(OURS_FIRST, OURS_LAST, set.enchanted);
            if (ours >= 0) set.timing.ourMarker(ours, now);
        }
    }

    /** Every sound while the menu is open; the reference's note block pitch is in these. */
    public static void observeSound(ClientboundSoundPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (activeMenu == null || client.player == null || client.player.containerMenu != activeMenu) return;
        String sound = packet.getSound().value().location().toString();
        trace("sound " + sound + " pitch=" + packet.getPitch() + " volume=" + packet.getVolume() + " bundle="
            + ServerTickPacketContext.isApplyingBundle());
        int pitch = BeaconTuning.pitchOfSound(packet.getPitch());
        // Two sets would play over each other, and a sound could not be told to belong to either.
        if (sound.equals(NOTE_SOUND) && pitch >= 0 && sets.size() == 1) sets.getFirst().timing.sound(pitch, System.nanoTime());
    }

    /** A click the player made on the open screen, with the count that was drawn on its button. */
    public static void observeSlotClick(AbstractContainerScreen<?> screen, int slotId, int button, ContainerInput input) {
        if (!active(screen)) return;
        trace("click slot=" + slotId + " button=" + button + " type=" + input + " shown=" + shown.get(slotId));
    }

    /** The number drawn on a button, from the slot's item-count hook; null for any other slot. */
    public static String slotLabel(AbstractContainerScreen<?> screen, Slot slot) {
        if (slot == null || !active(screen)) return null;
        int clicks = clicksAt(slot.index);
        String label = clicks == NONE ? null : clicks == UNKNOWN ? "§7?" : clicks == 0 ? null
            : (clicks > 0 ? "§a" : "§b") + clicks;
        if (label == null) shown.remove(slot.index);
        else shown.put(slot.index, label);
        return label;
    }

    /** Lights a button that already matches, from the slot render hook. */
    public static void drawSlot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        if (active(screen) && clicksAt(slot.index) == 0) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, GREEN);
        }
    }

    private static boolean active(AbstractContainerScreen<?> screen) {
        return activeMenu != null && screen.getMenu() == activeMenu;
    }

    /** The clicks the button in this slot needs, {@link #NONE} for any other slot or while it cannot be read. */
    private static int clicksAt(int slotIndex) {
        for (Controls set : sets) {
            if (slotIndex == set.color) return colorClicks(set);
            if (slotIndex == set.speed) return linearClicks(set.timing.referenceSpeed(), ownSpeed(set), false);
            if (slotIndex == set.pitch) return linearClicks(set.timing.referencePitch(), ownPitch(set), true);
        }
        return NONE;
    }

    private static int colorClicks(Controls set) {
        int reference = referenceColor(set.enchanted);
        String ours = BeaconTuning.current(MenuItems.lore(activeMenu.slots.get(set.color).getItem()), "color");
        int own = ours == null ? -1 : BeaconTuning.colorOfName(ours);
        return reference < 0 || own < 0 ? NONE : BeaconTuning.clicks(reference, own, BeaconTuning.COLORS.size());
    }

    /**
     * Speed and pitch buttons say "increase" and "decrease", not "cycle", so the clicks are the plain difference:
     * it is right whether or not the ends wrap round. An unknown reference is {@link #UNKNOWN}, which a pitch also
     * is while the menu has two sets.
     */
    private static int linearClicks(int reference, int own, boolean pitch) {
        if (own < 0 || pitch && sets.size() > 1) return NONE;
        return reference < 0 ? UNKNOWN : reference - own;
    }

    private static int ownSpeed(Controls set) {
        String value = BeaconTuning.current(MenuItems.lore(activeMenu.slots.get(set.speed).getItem()), "speed");
        return value == null ? -1 : BeaconTuning.speedOfName(value);
    }

    private static int ownPitch(Controls set) {
        String value = BeaconTuning.current(MenuItems.lore(activeMenu.slots.get(set.pitch).getItem()), "pitch");
        return value == null ? -1 : BeaconTuning.pitchOfName(value);
    }

    /** The color of the marker pane of this set in the reference row, -1 when none is showing. */
    private static int referenceColor(boolean enchanted) {
        int at = markerAt(REFERENCE_FIRST, REFERENCE_LAST, enchanted);
        return at < 0 ? -1 : BeaconTuning.colorOfPane(paneId(activeMenu.slots.get(at).getItem()));
    }

    /** The slot of the one colored pane in a row (gray panes are the track), -1 for none or several. */
    private static int markerAt(int first, int last, boolean enchanted) {
        int found = -1;
        for (int index = first; index <= last; index++) {
            ItemStack stack = activeMenu.slots.get(index).getItem();
            if (stack.isEmpty() || stack.hasFoil() != enchanted || BeaconTuning.colorOfPane(paneId(stack)) < 0) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static String paneId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static boolean ensureSession(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) || client.player == null) return false;
        String title = screen.getTitle().getString().trim();
        AbstractContainerMenu menu = screen.getMenu();
        if (!TITLE.matcher(title).matches() || menu != client.player.containerMenu
            || menu.slots.size() < TOP_SLOT_COUNT) {
            return false;
        }
        if (activeMenu != menu) {
            end("new-menu");
            activeMenu = menu;
            openedNanos = System.nanoTime();
            sets = title.equals(STRENGTH_TITLE)
                ? List.of(new Controls(false, BUTTONS - STRENGTH_ROW_SHIFT), new Controls(true, BUTTONS))
                : List.of(new Controls(false, BUTTONS));
            trace("open container=" + menu.containerId + " title=\"" + title + "\" slots=" + menu.slots.size());
        }
        return true;
    }

    private static void end(String reason) {
        if (activeMenu != null) trace("end " + reason);
        activeMenu = null;
        sets = List.of();
        ticks = 0;
        seen.clear();
        shown.clear();
    }

    /** What was timed so far, once each time it changes: the numbers to check against the speed and pitch buttons. */
    private static void traceTimed(Controls set) {
        int speed = set.timing.referenceSpeed();
        if (speed != set.tracedSpeed) {
            set.tracedSpeed = speed;
            trace("reference speed=" + (speed + 1) + " enchanted=" + set.enchanted);
        }
        int pitch = set.timing.referencePitch();
        if (pitch != set.tracedPitch) {
            set.tracedPitch = pitch;
            trace("reference pitch=" + pitch + " enchanted=" + set.enchanted);
        }
    }

    /** The item, glint, name and lore of a slot on one line; "empty" for none. */
    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        String lore = String.join(" | ", MenuItems.lore(stack));
        if (lore.length() > LORE_LIMIT) lore = lore.substring(0, LORE_LIMIT) + "...";
        return paneId(stack) + " foil=" + stack.hasFoil() + " name=\"" + stack.getHoverName().getString() + "\" lore=\""
            + lore + "\"";
    }

    private static void trace(String message) {
        KungDebugRecorder.event(AREA, "+" + (System.nanoTime() - openedNanos) / 1_000_000 + "ms k=" + ticks + " " + message);
    }
}
