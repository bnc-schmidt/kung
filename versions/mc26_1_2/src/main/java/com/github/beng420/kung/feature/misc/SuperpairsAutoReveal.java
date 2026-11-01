package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.experiment.AutoPacer;
import com.github.beng420.kung.feature.experiment.ExperimentAutoClicker;
import com.github.beng420.kung.feature.misc.SuperpairsPlanner.Pick;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Clicks a hidden Superpairs card at a time until no completely unseen pair is left (every click reveals one
 * card, so unseen pairs fall as fast as the game takes clicks); during that, after an Instant Find it clicks a
 * known priority single (Titanic, T7) and it takes the twin of a priority card just revealed. At Unseen pairs 0
 * it stops and leaves the remaining clicks to the player; only with Auto Claim on does it go on to claim the
 * known pairs, best first as {@link SuperpairsPlanner} ranks them. Full flavor only (listed in modrinthCut); the one caller sits in {@code if (!KungBuild.MODRINTH)}.
 */
public final class SuperpairsAutoReveal {
    private static final String AREA = "superpairs-auto";
    /** A click that revealed nothing within this is counted as lost and tried again. */
    static final long REVEAL_TIMEOUT_MILLIS = 2_500L;
    /** The board must be this still, so a burst of slot updates is over before the next click. */
    static final long SETTLE_MILLIS = 150L;
    /**
     * Cards without a click prompt (the "?" button name) are clicked anyway after this much stillness. The
     * prompt names are the only readiness signal known, so a name nobody has traced must not stall the helper.
     */
    static final long STALL_MILLIS = 3_000L;

    private static final AutoPacer PACER = new AutoPacer(new SplittableRandom());
    private static final SplittableRandom PICK = new SplittableRandom();
    private static int awaiting = -1;
    private static long awaitingSinceMillis;
    private static int claimSlot = -1;
    private static long claimPacketMillis;
    private static long claimSinceMillis;
    private static long clicks;

    private SuperpairsAutoReveal() { }

    public static void tick(Minecraft client, SuperpairsBoard board, AbstractContainerMenu menu,
                            long lastBoardPacketMillis, MiscConfig config, Pick pick) {
        if (!config.superpairsAutoRevealEnabled()) {
            reset();
            return;
        }
        // While cards are still unseen the auto mode may use an Instant Find or a priority twin. Once Unseen
        // pairs is 0 it is finished and leaves the remaining clicks to the player, unless Auto Claim is on.
        boolean revealDone = board.boardSlotCount() > 0 && board.summary().maximumUnseenPairs() == 0;
        if (!pick.slots().isEmpty() && (!revealDone || config.superpairsAutoClaimEnabled())) {
            claim(client, menu, lastBoardPacketMillis, config, pick);
            return;
        }
        claimSlot = -1;
        List<Integer> unseen = board.unseenSlots();
        if (board.boardSlotCount() == 0 || board.summary().maximumUnseenPairs() == 0) {
            if (clicks > 0) {
                KungDebugRecorder.event(AREA, "done clicks=" + clicks + " unseen=" + unseen.size());
            }
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (awaiting >= 0) {
            if (unseen.contains(awaiting) && now - awaitingSinceMillis < REVEAL_TIMEOUT_MILLIS) {
                return;
            }
            if (unseen.contains(awaiting)) {
                KungDebugRecorder.event(AREA, "no reveal slot=" + awaiting + " retry");
            }
            awaiting = -1;
        }
        List<Integer> targets = targets(board, slot -> hiddenLabel(menu, slot));
        if (targets.isEmpty()) {
            PACER.idle();
            return;
        }
        String prompt = hiddenLabel(menu, targets.getFirst());
        if (!ready(prompt, now - lastBoardPacketMillis)) {
            PACER.idle();
            return;
        }
        if (!PACER.poll(clicks, now, config.experimentAutoClickDelayMillis(), clicks == 0)) {
            return;
        }
        int slot = targets.get(PICK.nextInt(targets.size()));
        if (ExperimentAutoClicker.click(client, menu, slot)) {
            awaiting = slot;
            awaitingSinceMillis = now;
            clicks++;
            KungDebugRecorder.event(AREA, "click slot=" + slot + " prompt=\"" + prompt + "\" unseen=" + unseen.size()
                + " maxUnseenPairs=" + board.summary().maximumUnseenPairs());
        }
    }

    /**
     * Clicks what the planner picked: the best known pair (first card, then its twin), the twin of a priority card
     * just revealed, or a priority single after an Instant Find. A click the game does not react to within
     * {@link #REVEAL_TIMEOUT_MILLIS} marks its slot as a taken pair, so the planner moves on.
     */
    private static void claim(Minecraft client, AbstractContainerMenu menu, long lastBoardPacketMillis, MiscConfig config,
                              Pick pick) {
        long now = System.currentTimeMillis();
        if (claimSlot >= 0) {
            if (lastBoardPacketMillis != claimPacketMillis) {
                claimSlot = -1;
            } else if (now - claimSinceMillis > REVEAL_TIMEOUT_MILLIS) {
                KungDebugRecorder.event(AREA, "no reaction slot=" + claimSlot + ", counted as a taken pair");
                SuperpairsHelperFeature.markDead(claimSlot);
                claimSlot = -1;
                return;
            } else {
                return;
            }
        }
        int slot = pick.slots().getFirst();
        String prompt = hiddenLabel(menu, slot);
        if (prompt == null || !ready(prompt, now - lastBoardPacketMillis)) {
            PACER.idle();
            return;
        }
        if (!PACER.poll(clicks, now, config.experimentAutoClickDelayMillis(), clicks == 0)) {
            return;
        }
        if (ExperimentAutoClicker.click(client, menu, slot)) {
            claimSlot = slot;
            claimPacketMillis = lastBoardPacketMillis;
            claimSinceMillis = now;
            clicks++;
            KungDebugRecorder.event(AREA, "claim slot=" + slot + " prompt=\"" + prompt + "\" pick=" + pick.slots()
                + " (" + pick.reason() + ")");
        }
    }

    public static void reset() {
        awaiting = -1;
        claimSlot = -1;
        clicks = 0;
        PACER.idle();
    }

    /** Whether to click now: still long enough, and the prompt says the server takes a click (or nothing does). */
    static boolean ready(String prompt, long quietMillis) {
        return quietMillis >= SETTLE_MILLIS && (SuperpairsBoard.acceptsClick(prompt) || quietMillis >= STALL_MILLIS);
    }

    /** Unseen slots whose card is still hidden; {@code hiddenLabel} is null for a slot showing anything else. */
    static List<Integer> targets(SuperpairsBoard board, IntFunction<String> hiddenLabel) {
        List<Integer> targets = new ArrayList<>();
        for (int slot : board.unseenSlots()) {
            if (hiddenLabel.apply(slot) != null) {
                targets.add(slot);
            }
        }
        return targets;
    }

    /** The button prompt of a hidden card, or null when the slot shows a card face up or nothing. */
    private static String hiddenLabel(AbstractContainerMenu menu, int slot) {
        ItemStack stack = menu.slots.get(slot).getItem();
        if (stack.isEmpty()) {
            return null;
        }
        String label = stack.getHoverName().getString();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return SuperpairsBoard.classify(itemId, label) == SuperpairsBoard.Kind.MARKER ? label : null;
    }
}
