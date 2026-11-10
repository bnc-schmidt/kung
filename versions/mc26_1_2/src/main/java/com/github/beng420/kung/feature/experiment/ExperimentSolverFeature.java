package com.github.beng420.kung.feature.experiment;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Chronomatron and Ultrasequencer, the two add-on experiments of the Experimentation Table: marks the slot(s)
 * to click next, learned from what the game shows. The visual aid is in both flavors. The optional automatic
 * clicking is the full flavor only: it sits in {@code if (!KungBuild.MODRINTH)} blocks and in classes that
 * {@code versions/common.gradle} cuts from the Modrinth jar.
 */
public final class ExperimentSolverFeature extends ConfigurableFeature<MiscConfig> {
    public static final ExperimentSolverFeature INSTANCE = new ExperimentSolverFeature();
    private static final String AREA = "experiments";
    private static final int TOP_SLOT_COUNT = 54;
    private static final Pattern TITLE = Pattern.compile("^(Chronomatron|Ultrasequencer) \\(([\\w ]+)\\)$");
    private static final Pattern STAKES = Pattern.compile("^(Chronomatron|Ultrasequencer) \\u279C Stakes$");
    private static final String TIER_ITEM_SUFFIX = " Experiment";
    /** Solid backgrounds, with the block drawn over them: a see-through yellow vanished on blue glass. */
    private static final int NOW = 0xFF3FBF3F;
    private static final int AFTER = 0xFFE6D23A;
    private static final int SLOT_BACKGROUND = 0xFF373737;

    // Updated from the applied packet hooks and the client tick, so only ever on the client thread.
    private static AbstractContainerMenu activeMenu;
    private static ChronomatronModel chronomatron;
    private static UltrasequencerModel ultrasequencer;
    private static List<SlotView> views = List.of();
    /** The numbered items of this Ultrasequencer round as they were shown, to draw them back once hidden. */
    private static final Map<Integer, ItemStack> remembered = new HashMap<>();
    private static String lastLogged = "";
    /** The rounds each tier needs for its best reward, read from the Stakes menus seen this session: "game|tier". */
    private static final Map<String, Integer> stakes = new HashMap<>();
    /** The round after which this game's buffs are maxed according to its Stakes menu, 0 when unknown. */
    private static int detectedMaxRound;
    /** The round last reported as finished, so a model sitting in its end state reports it once. */
    private static int reportedRound;
    /** Full flavor: the finished round that maxed the buffs and whose window still has to be closed. */
    private static int closeAfterRound;

    private ExperimentSolverFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(ExperimentSolverFeature::tick);
    }

    @Override
    public boolean isEnabled() {
        // Part of the Superpairs Helper: its switch turns on the add-on solvers too, and "Add-on Solvers" turns them off alone.
        return initialized() && config().superpairsHelperEnabled() && config().experimentSolversEnabled();
    }

    private static void tick(Minecraft client) {
        if (INSTANCE.isEnabled()) {
            observeStakes(client);
        }
        if (!INSTANCE.isEnabled() || !ensureSession(client)) {
            resetState("closed-or-disabled");
            return;
        }
        MiscConfig config = INSTANCE.config();
        if (!KungBuild.MODRINTH && config.experimentAutoClickEnabled()) {
            List<Integer> candidates = candidates();
            int target = candidates.isEmpty() ? -1 : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            long step = progress();
            if (ExperimentAutoClicker.tick(client, activeMenu, target, step, step == 0,
                config.experimentAutoClickDelayMillis())) {
                KungDebugRecorder.event(AREA, "auto-click slot=" + target + " step=" + step
                    + " candidates=" + describe(candidates));
                applyClick(target);
            }
            // The round that maxes the buffs is done: close the window after a short wait, as a player would.
            if (closeAfterRound > 0 && ExperimentAutoClicker.tickClose(client, activeMenu, closeAfterRound,
                config.experimentAutoClickDelayMillis())) {
                KungDebugRecorder.event(AREA, "auto-close after round " + closeAfterRound);
                closeAfterRound = 0;
            }
        }
    }

    /** Called from the applied ClientPacketListener slot hooks, never Netty receipt. */
    public static void observeMenuPacket(int containerId) {
        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled() || client.player == null
            || client.player.containerMenu.containerId != containerId || !ensureSession(client)) {
            return;
        }
        update();
    }

    /** A click the player made on the open screen. */
    public static void observeSlotClick(AbstractContainerScreen<?> screen, int slotId) {
        if (activeMenu != null && screen.getMenu() == activeMenu) {
            applyClick(slotId);
        }
    }

    /** Paints the solver's mark over a slot, from the slot render hook. */
    public static void drawSlot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        if (activeMenu == null || screen.getMenu() != activeMenu || slot.index >= views.size()) {
            return;
        }
        int x = slot.x;
        int y = slot.y;
        if (chronomatron != null && chronomatron.colorAt(slot.index) != null) {
            String color = chronomatron.colorAt(slot.index);
            boolean now = color.equals(chronomatron.expectedColor());
            boolean after = color.equals(chronomatron.followingColor());
            if (now || after) {
                graphics.fill(x, y, x + 16, y + 16, now ? NOW : AFTER);
                ItemStack shown = slot.getItem();
                if (!shown.isEmpty()) {
                    graphics.item(shown, x, y);
                }
                // The same color twice in a row: the yellow frame says to click this block again.
                if (now && after) {
                    UiShapes.slotFrame(graphics, x, y, AFTER);
                }
            }
        } else if (ultrasequencer != null && ultrasequencer.pending(slot.index)) {
            // The game hides the numbers once the timer runs; draw the remembered item and number back.
            int rank = ultrasequencer.upcomingSlots(2).indexOf(slot.index);
            graphics.fill(x, y, x + 16, y + 16, rank == 0 ? NOW : rank == 1 ? AFTER : SLOT_BACKGROUND);
            ItemStack stack = remembered.get(slot.index);
            if (stack != null) {
                graphics.item(stack, x, y);
            }
            Font font = Minecraft.getInstance().font;
            String number = String.valueOf(ultrasequencer.numberAt(slot.index));
            graphics.text(font, number, x + 17 - font.width(number), y + 9, 0xFFFFFFFF, true);
        }
    }

    /**
     * Reads the tiers of an open Stakes menu: the rounds each tier's lore asks for its best reward. The game
     * that follows looks its tier up here, so the max buffs round needs no setting.
     */
    private static void observeStakes(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (client.player == null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        Matcher title = STAKES.matcher(screen.getTitle().getString().trim());
        AbstractContainerMenu menu = container.getMenu();
        if (!title.matches() || menu != client.player.containerMenu) {
            return;
        }
        for (int index = 0; index < MenuItems.topSlots(menu); index++) {
            ItemStack stack = menu.slots.get(index).getItem();
            String name = stack.isEmpty() ? "" : stack.getHoverName().getString().trim();
            if (!name.endsWith(TIER_ITEM_SUFFIX) || !stack.has(DataComponents.LORE)) {
                continue;
            }
            String key = title.group(1) + "|" + name.substring(0, name.length() - TIER_ITEM_SUFFIX.length());
            int needed = StakesLore.roundsNeeded(MenuItems.lore(stack));
            Integer before = stakes.put(key, needed);
            if (before == null || before != needed) {
                KungDebugRecorder.event(AREA, "stakes " + key + " rounds=" + needed);
            }
        }
    }

    private static boolean ensureSession(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (client.player == null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        Matcher title = TITLE.matcher(screen.getTitle().getString().trim());
        AbstractContainerMenu menu = container.getMenu();
        if (!title.matches() || menu != client.player.containerMenu
            || MenuItems.topSlots(menu) != TOP_SLOT_COUNT) {
            return false;
        }
        // A resize keeps what was learned. Another menu never does, even with the same title.
        if (activeMenu != menu) {
            resetState("new-menu");
            activeMenu = menu;
            boolean chrono = title.group(1).equals("Chronomatron");
            chronomatron = chrono ? new ChronomatronModel() : null;
            ultrasequencer = chrono ? null : new UltrasequencerModel();
            int needed = stakes.getOrDefault(title.group(1) + "|" + title.group(2), 0);
            detectedMaxRound = StakesLore.maxBuffsRound(title.group(1), needed);
            KungDebugRecorder.event(AREA, "start container=" + menu.containerId + " title=\""
                + screen.getTitle().getString() + "\" stakes rounds=" + needed + " max buffs round="
                + detectedMaxRound);
            update();
        }
        return true;
    }

    private static void update() {
        List<SlotView> snapshot = new ArrayList<>(TOP_SLOT_COUNT);
        for (int slot = 0; slot < TOP_SLOT_COUNT; slot++) {
            snapshot.add(view(activeMenu.slots.get(slot).getItem()));
        }
        views = snapshot;
        if (chronomatron != null) {
            chronomatron.update(views);
            String state = chronomatron.phase() + "|" + chronomatron.round() + "|" + chronomatron.flashes()
                + "|" + chronomatron.sequence() + "|" + chronomatron.problem();
            if (!state.equals(lastLogged)) {
                lastLogged = state;
                KungDebugRecorder.event(AREA, "chronomatron phase=" + chronomatron.phase()
                    + " round=" + chronomatron.round() + " flashes=" + chronomatron.flashes()
                    + " sequence=" + chronomatron.sequence() + " problem=" + chronomatron.problem()
                    + " cells=" + describe(List.copyOf(chronomatron.cells()))
                    + " colored=" + describe(coloredSlots()));
                if (chronomatron.problem() != null) {
                    warnUnsure("Chronomatron");
                }
            }
        } else {
            ultrasequencer.update(views);
            if (ultrasequencer.phase() == UltrasequencerModel.Phase.REMEMBER) {
                remembered.keySet().removeIf(slot -> ultrasequencer.numberAt(slot) == 0);
                for (int slot = 0; slot < TOP_SLOT_COUNT; slot++) {
                    int number = ultrasequencer.numberAt(slot);
                    if (number != 0 && views.get(slot).name().equals(String.valueOf(number))) {
                        remembered.put(slot, activeMenu.slots.get(slot).getItem().copy());
                    }
                }
            }
            String state = ultrasequencer.phase() + "|" + ultrasequencer.usable() + "|" + ultrasequencer.describe();
            if (!state.equals(lastLogged)) {
                lastLogged = state;
                KungDebugRecorder.event(AREA, "ultrasequencer phase=" + ultrasequencer.phase()
                    + " usable=" + ultrasequencer.usable() + " numbers=[" + ultrasequencer.describe() + "]");
                if (ultrasequencer.phase() == UltrasequencerModel.Phase.PLAY && !ultrasequencer.usable()) {
                    warnUnsure("Ultrasequencer");
                }
            }
        }
    }

    /** Tells the player that the solver marks nothing this round, instead of marking something wrong. */
    private static void warnUnsure(String game) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(KungMessages.warning(game, "Missed part of this round's pattern, so nothing is marked."));
        }
    }

    private static SlotView view(ItemStack stack) {
        if (stack.isEmpty()) {
            return SlotView.EMPTY;
        }
        return new SlotView(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getHoverName().getString().trim(), stack.hasFoil());
    }

    /** The slots that would be a correct next click. */
    private static List<Integer> candidates() {
        List<Integer> slots = new ArrayList<>();
        if (chronomatron != null) {
            String expected = chronomatron.expectedColor();
            for (int slot : chronomatron.cells()) {
                if (expected != null && expected.equals(chronomatron.colorAt(slot))) {
                    slots.add(slot);
                }
            }
        } else if (ultrasequencer != null && ultrasequencer.nextSlot() != null) {
            slots.add(ultrasequencer.nextSlot());
        }
        return slots;
    }

    /** For the trace: every slot showing a Chronomatron color, cell or not. */
    private static List<Integer> coloredSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < views.size(); slot++) {
            if (ChronomatronModel.colorOf(views.get(slot).item()) != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** For the trace: each slot with the item and name it shows right now. */
    private static String describe(List<Integer> slots) {
        StringBuilder text = new StringBuilder("[");
        for (int slot : slots) {
            SlotView view = slot < views.size() ? views.get(slot) : SlotView.EMPTY;
            text.append(text.length() > 1 ? ", " : "").append(slot).append('=')
                .append(view.item().replace("minecraft:", "")).append(" \"").append(view.name()).append('"')
                .append(view.glint() ? " glint" : "");
        }
        return text.append(']').toString();
    }

    private static long progress() {
        return chronomatron != null ? chronomatron.progress() : ultrasequencer.progress();
    }

    /** Counts a click. Cells are known by their color at rest: the click itself empties the slot on the client. */
    private static void applyClick(int slot) {
        if (slot < 0 || slot >= views.size()) {
            return;
        }
        if (chronomatron != null) {
            chronomatron.clickSlot(slot);
        } else if (ultrasequencer != null) {
            ultrasequencer.click(slot);
        }
        int done = chronomatron != null ? chronomatron.finishedRound() : ultrasequencer.finishedRound();
        if (done > 0 && done != reportedRound) {
            reportedRound = done;
            onRoundFinished(done);
        }
    }

    /** The last click of a round: from the configured round on the buffs are maxed and the window can be closed. */
    private static void onRoundFinished(int round) {
        MiscConfig config = INSTANCE.config();
        int maxRound = config.experimentMaxBuffsRound() > 0 ? config.experimentMaxBuffsRound() : detectedMaxRound;
        KungDebugRecorder.event(AREA, "round " + round + " finished, max buffs round " + maxRound);
        if (maxRound <= 0 || round < maxRound) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(KungMessages.success(chronomatron != null ? "Chronomatron" : "Ultrasequencer",
                "Round " + round + " done: the buffs are maxed, you can close the window now."));
        }
        if (!KungBuild.MODRINTH && config.experimentAutoClickEnabled()) {
            closeAfterRound = round;
        }
    }

    private static void resetState(String reason) {
        if (activeMenu == null) {
            return;
        }
        KungDebugRecorder.event(AREA, "reset container=" + activeMenu.containerId + " reason=" + reason);
        activeMenu = null;
        chronomatron = null;
        ultrasequencer = null;
        views = List.of();
        remembered.clear();
        lastLogged = "";
        reportedRound = 0;
        closeAfterRound = 0;
        detectedMaxRound = 0;
        if (!KungBuild.MODRINTH) ExperimentAutoClicker.reset();
    }
}
