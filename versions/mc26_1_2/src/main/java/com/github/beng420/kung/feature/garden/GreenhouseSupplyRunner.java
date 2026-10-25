package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseSupply.Plan;
import java.util.ArrayList;
import java.util.List;
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

        /** The same line followed by a clickable prompt that opens the Bazaar with {@code search} typed in. */
        void chatBazaar(Level level, String message, String search);

        /** Whether the player plays Ironman, which has no Bazaar (taken from the planner's Ironman price mode). */
        boolean ironman();
    }

    /** Who asked for the items: a pick in the world holds them afterwards, a click in the planner only pulls. */
    private enum Origin { PICK, PLANNER }

    /** {@code moved}: the amount the game reported, -1 while it has not. */
    private record Pending(Plan plan, int baseline, long expiresAt, int moved, Origin origin) {
        Pending withMoved(int amount) { return new Pending(plan, baseline, expiresAt, amount, origin); }
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

    /**
     * Entry for a click on a crop of the planner's shopping list: pulls the missing amount of {@code count} plants
     * from the Sacks and, when the Sacks run short, opens the Bazaar for the rest (unless Ironman). Nothing is
     * switched to: the planner is open. Client thread.
     */
    public static void fetch(String cropName, int count) {
        live().onFetch(cropName, count);
    }

    void onPick(String crop, int flag) {
        act(GreenhouseSupply.plan(crop, flag, host.required(crop), GreenhouseSupply.planted(crop), host.inventory()),
            Origin.PICK);
    }

    void onPickSoil(GreenhouseSoils.Soil soil) {
        act(GreenhouseSupply.planSoil(soil, host.soilNeeded(soil), host.inventory()), Origin.PICK);
    }

    void onFetch(String crop, int count) {
        act(GreenhouseSupply.plan(crop, GreenhouseGrid.INGREDIENT, count, GreenhouseSupply.planted(crop),
            host.inventory()), Origin.PLANNER);
    }

    private void act(Plan plan, Origin origin) {
        if (origin == Origin.PLANNER) {
            // The planner never switches the hotbar: it reports, or pulls.
            switch (plan.action()) {
                case PULL -> pull(plan, origin);
                case UNKNOWN_CROP, NOT_SACKABLE, TARGET_SPOT -> host.chat(Level.WARNING, plan.message());
                case SWITCH -> host.chat(Level.INFO, plan.message());
            }
            return;
        }
        switch (plan.action()) {
            case TARGET_SPOT -> host.chat(Level.INFO, plan.message());
            case UNKNOWN_CROP -> host.chat(Level.WARNING, plan.message());
            case SWITCH -> {
                Held held = switchTo(plan);
                if (held == Held.NONE) host.chat(Level.INFO, plan.message());
                else pending = null;
            }
            case NOT_SACKABLE -> {
                // A hint leaves a running pull alone; only a real switch to this item replaces its auto-switch.
                if (switchTo(plan) == Held.SWITCHED) pending = null;
                host.chat(Level.WARNING, plan.message());
            }
            case PULL -> pull(plan, origin);
        }
    }

    private void pull(Plan plan, Origin origin) {
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
        int held = host.inventory().getOrDefault(GreenhouseSupply.normalizeId(plan.item().id()), 0);
        pending = new Pending(plan, held, now + PENDING_MILLIS, -1, origin);
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
        if (got < asked) shortage(current, asked - got);
        if (current.origin() == Origin.PICK) switchTo(current.plan());
    }

    /**
     * The Sacks held less than the plan pulled: says how many items are missing and where to buy them. A pick in
     * the world gets a clickable prompt, a click in the planner opens the Bazaar by itself; Ironman has no Bazaar.
     */
    private void shortage(Pending current, int missing) {
        GreenhouseItems.Item item = current.plan().item();
        String message = "Missing " + missing + " " + item.unit();
        if (host.ironman()) {
            host.chat(Level.WARNING, message);
        } else if (current.origin() == Origin.PICK) {
            host.chatBazaar(Level.WARNING, message, item.unit());
        } else {
            host.chat(Level.WARNING, message + ", opening the Bazaar");
            host.sendCommand("bz " + item.unit());
        }
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
            int have = host.inventory().getOrDefault(GreenhouseSupply.normalizeId(item.id()), 0);
            int got = Math.max(0, have - current.baseline());
            if (got < current.plan().amount()) shortage(current, current.plan().amount() - got);
        }
    }

    /** Disconnect: forget the pull and the cooldown. */
    void reset() {
        pending = null;
        sent = false;
    }

    boolean pendingPull() { return pending != null; }

    /** What a switch to the plan's item came to. */
    private enum Held {
        /** The player holds none of it anywhere in the inventory (or it has no item). */
        NONE,
        /** It sits in the main inventory and could not be swapped in: the player was told to move it. */
        HINTED,
        /** It is in the selected hotbar slot now. */
        SWITCHED
    }

    /** The item's own preferred items first (Melon Seeds), then the item itself; the first one held anywhere wins. */
    private Held switchTo(Plan plan) {
        GreenhouseItems.Item item = plan.item();
        if (item.id() == null) return Held.NONE;
        List<String> ids = new ArrayList<>();
        for (String preferred : item.holdFirst()) ids.add(GreenhouseSupply.normalizeId(preferred));
        String own = GreenhouseSupply.normalizeId(item.id());
        ids.add(own);
        for (String id : ids) {
            int hotbar = host.hotbarSlot(id);
            if (hotbar >= 0) {
                host.select(hotbar);
                return Held.SWITCHED;
            }
            int main = host.mainSlot(id);
            if (main < 0) continue;
            if (host.swapIntoSelected(main)) return Held.SWITCHED;
            host.chat(Level.WARNING, "Move " + (id.equals(own) ? item.unit() : item.crop()) + " into your hotbar to hold it");
            return Held.HINTED;
        }
        return Held.NONE;
    }
}
