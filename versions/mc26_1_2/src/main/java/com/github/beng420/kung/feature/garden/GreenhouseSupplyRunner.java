package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseSupply.Plan;
import java.util.Map;

/**
 * Carries out a {@link GreenhouseSupply.Plan} when the player picks a hologram cell: switches to the crop's item,
 * or sends one {@code /gfs} for the missing amount, waits for the items to reach the inventory and then
 * switches. Client thread only, event-driven (the caller feeds {@link #tick} and {@link #onChat}), nothing
 * loops, queues or retries on its own: one pick is at most one command.
 */
public final class GreenhouseSupplyRunner {
    /** Between two commands; SkyHanni leaves 1.65 s, a press inside the gap is ignored. */
    static final long COOLDOWN_MILLIS = 2_000L;
    /** How long the items may take to show up after the command. */
    static final long PENDING_MILLIS = 8_000L;

    enum Level { INFO, WARNING, ERROR }

    /** The game behind the runner, so the state machine is unit-tested with a fake. */
    interface Host {
        long now();

        boolean onHypixel();

        /** Ingredient cells of the crop in the shown hologram. */
        int required(String crop);

        /** Soil ghosts of the latest scan that need this soil. */
        int soilNeeded(GreenhouseSoils.Soil soil);

        /** Counts of the player's 36 inventory slots by normalized SkyBlock id. */
        Map<String, Integer> inventory();

        /** First hotbar slot (0-8) holding the id, else -1. */
        int hotbarSlot(String id);

        /** First main inventory slot (9-35) holding the id, else -1. */
        int mainSlot(String id);

        void select(int hotbarSlot);

        /**
         * Swaps a main inventory slot with the selected hotbar slot. False when it was not done, always so in the
         * Modrinth flavor, which has no inventory clicks.
         */
        boolean swapIntoSelected(int mainSlot);

        /** Sends the command as the player typed it, without the leading slash. */
        void sendCommand(String command);

        void chat(Level level, String message);
    }

    /** {@code moved}: the amount the game reported, -1 while it has not. */
    private record Pending(Plan plan, int baseline, long expiresAt, int moved) {
        Pending withMoved(int amount) { return new Pending(plan, baseline, expiresAt, amount); }
    }

    private static GreenhouseSupplyRunner live;
    private final Host host;
    private Pending pending;
    private long lastSend;
    private boolean sent;

    GreenhouseSupplyRunner(Host host) {
        this.host = host;
    }

    /**
     * Entry for the pick-block hook: the middle-clicked hologram cell's crop name and flag
     * ({@link GreenhouseGrid#TARGET} 0 is a mutation spot). Client thread. The first call hooks the runner into
     * the tick, chat and disconnect events and takes the planted counts from the plot scanner.
     */
    public static void pick(String cropName, int flag) {
        live().onPick(cropName, flag);
    }

    private static GreenhouseSupplyRunner live() {
        if (live == null) {
            live = new GreenhouseSupplyRunner(new GreenhouseSupplyHost());
            GreenhouseSupplyHost.register(live);
            GreenhouseSupply.useScanner();
        }
        return live;
    }

    /**
     * Entry for the pick-block hook on a soil ghost (a missing or wrong soil block of the hologram): the same flow
     * for the soil's item, sharing the one cooldown and the one pending wait with the crops. Client thread.
     */
    static void pickSoil(GreenhouseSoils.Soil soil) {
        live().onPickSoil(soil);
    }

    void onPick(String crop, int flag) {
        act(GreenhouseSupply.plan(crop, flag, host.required(crop), GreenhouseSupply.planted(crop), host.inventory()));
    }

    void onPickSoil(GreenhouseSoils.Soil soil) {
        act(GreenhouseSupply.planSoil(soil, host.soilNeeded(soil), host.inventory()));
    }

    private void act(Plan plan) {
        switch (plan.action()) {
            case TARGET_SPOT -> host.chat(Level.INFO, plan.message());
            case UNKNOWN_CROP -> host.chat(Level.WARNING, plan.message());
            case SWITCH -> {
                pending = null;
                if (!switchTo(plan)) host.chat(Level.INFO, plan.message());
            }
            case NOT_SACKABLE -> {
                // A hint about something with no item (Farmland, Fire) leaves a running pull alone.
                if (plan.item().id() != null) pending = null;
                switchTo(plan);
                host.chat(Level.WARNING, plan.message());
            }
            case PULL -> pull(plan);
        }
    }

    private void pull(Plan plan) {
        if (pending != null && pending.plan().crop().equals(plan.crop())) {
            host.chat(Level.INFO, "Still waiting for " + plan.item().unit() + " from Sacks");
            return;
        }
        if (!host.onHypixel()) {
            host.chat(Level.WARNING, "Sacks are only available on Hypixel SkyBlock");
            return;
        }
        long now = host.now();
        if (sent && now - lastSend < COOLDOWN_MILLIS) {
            host.chat(Level.WARNING, "Sack pull on cooldown, press again in a moment");
            return;
        }
        // A different crop's pull is dropped here: its items may still arrive, but nothing switches to them.
        pending = new Pending(plan, plan.inInventory() / plan.item().plantsPerItem(), now + PENDING_MILLIS, -1);
        lastSend = now;
        sent = true;
        host.sendCommand(plan.command());
        host.chat(Level.INFO, plan.message());
    }

    /** Once per client tick; free while nothing is pending. */
    void tick() {
        Pending current = pending;
        if (current == null) return;
        GreenhouseItems.Item item = current.plan().item();
        int have = host.inventory().getOrDefault(GreenhouseSupply.normalizeId(item.id()), 0);
        int wanted = Math.max(1, current.moved() >= 0 ? current.moved() : current.plan().amount());
        boolean expired = host.now() >= current.expiresAt();
        // A short delivery whose Sack answer was not recognised still counts once the wait is over.
        if (have < current.baseline() + wanted && !(expired && have > current.baseline())) {
            if (expired) {
                pending = null;
                host.chat(Level.WARNING, "No " + item.unit() + " arrived from Sacks");
            }
            return;
        }
        pending = null;
        int got = have - current.baseline();
        int asked = current.plan().amount();
        if (got >= asked) {
            host.chat(Level.INFO, "Pulled " + got + " " + item.unit());
        } else if (current.moved() >= 0) {
            // Hypixel moved fewer than asked: that is all the Sacks held, not a failure.
            host.chat(Level.WARNING, "Only " + got + " of " + asked + " " + item.unit() + " were in your sacks.");
        } else {
            host.chat(Level.WARNING, "Only " + got + " of " + asked + " " + item.unit() + " arrived from your sacks.");
        }
        switchTo(current.plan());
    }

    /**
     * Every game line; only the two Sack answers about the pending item matter. A manual or other mod's
     * {@code /gfs} of another item changes nothing.
     */
    void onChat(String line) {
        Pending current = pending;
        if (current == null) return;
        GreenhouseItems.Item item = current.plan().item();
        var moved = GreenhouseSupply.parseMoved(line);
        if (moved.isPresent()) {
            if (item.answersTo(moved.get().item())) pending = current.withMoved(moved.get().amount());
            return;
        }
        var none = GreenhouseSupply.parseNoItem(line);
        if (none.isPresent() && item.answersTo(none.get())) {
            pending = null;
            host.chat(Level.ERROR, "Your Sacks have no " + item.unit());
        }
    }

    /** Disconnect: forget the pull and the cooldown. */
    void reset() {
        pending = null;
        sent = false;
    }

    boolean pendingPull() { return pending != null; }

    /** False when the player holds none of the plan's item anywhere in the inventory. */
    private boolean switchTo(Plan plan) {
        if (plan.item().id() == null) return false;
        String id = GreenhouseSupply.normalizeId(plan.item().id());
        int hotbar = host.hotbarSlot(id);
        if (hotbar >= 0) {
            host.select(hotbar);
            return true;
        }
        int main = host.mainSlot(id);
        if (main < 0) return false;
        if (!host.swapIntoSelected(main)) {
            host.chat(Level.WARNING, "Move " + plan.item().unit() + " into your hotbar to hold it");
        }
        return true;
    }
}
