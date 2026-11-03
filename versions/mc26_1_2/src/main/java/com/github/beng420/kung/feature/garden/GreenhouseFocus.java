package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Layout;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Narrows the hologram to the item in the player's hand: a crop, mutation or soil item shows only the cells and soil
 * blocks it belongs to, so the player sees at once where to put what they hold. It applies only while the hologram
 * still has something to do with the item (a spot not yet right, or a soil block to fix); a finished crop, or an item
 * the layout does not use, leaves the whole hologram on show, so the player can look around for the next thing to
 * place. Plain Java: {@link GreenhouseHologram#focus()} feeds it the held item and the scan.
 *
 * <p>An item belongs to every crop that is planted from it: the Melon Slice and the Melon Seeds both plant Melon
 * Seeds, the Pumpkin and the Pumpkin Seeds both plant Pumpkin Seeds ({@link GreenhouseItems.Item#holdFirst}). Dirt
 * belongs to the Dirt and the Farmland blocks (see {@link GreenhouseItems#itemSoil}). A soil block under a spot of a
 * shown crop stays visible with it, so a crop that waits for its soil is not hidden without a reason.
 */
final class GreenhouseFocus {
    /** The crops a held item plants and the soil it places ({@link Soil#NONE} for none). */
    record Held(Set<String> crops, Soil soil) { }

    /**
     * {@code shown} is aligned with the placements the focus was made for; {@code covered}: the footprints of the shown
     * ones; {@code hidden}: those of the others.
     */
    record Focus(Held held, boolean[] shown, List<Footprint> covered, List<Footprint> hidden) {
        /** Whether a soil block is drawn: it is the held soil, or lies under a shown crop. */
        boolean showsGhost(Ghost ghost) {
            if (held.soil() != Soil.NONE && GreenhouseItems.itemSoil(ghost.soil()) == held.soil()) return true;
            for (Footprint footprint : covered) {
                if (footprint.covers(ghost.dx() + 0.5, ghost.dz() + 0.5)) return true;
            }
            return false;
        }

        List<Ghost> shownGhosts(List<Ghost> ghosts) {
            List<Ghost> out = new ArrayList<>();
            for (Ghost ghost : ghosts) if (showsGhost(ghost)) out.add(ghost);
            return List.copyOf(out);
        }

        /** The layout, its states and its blocked flags and the soil ghosts with everything else taken out. */
        View narrow(Layout layout, List<State> states, boolean[] blocked, List<Ghost> ghosts) {
            List<Placement> placements = new ArrayList<>();
            List<State> keptStates = states == null ? null : new ArrayList<>();
            boolean[] keptBlocked = blocked == null ? null : new boolean[shown.length];
            int kept = 0;
            for (int i = 0; i < shown.length && i < layout.placements().size(); i++) {
                if (!shown[i]) continue;
                placements.add(layout.placements().get(i));
                if (keptStates != null) keptStates.add(i < states.size() ? states.get(i) : State.EMPTY);
                if (keptBlocked != null) keptBlocked[kept] = i < blocked.length && blocked[i];
                kept++;
            }
            // The (0, 0) marker and the cell volumes belong to the whole layout, not to what is held.
            return new View(new Layout(List.of(), List.copyOf(placements), List.of()),
                keptStates == null ? null : List.copyOf(keptStates),
                keptBlocked == null ? null : Arrays.copyOf(keptBlocked, kept), shownGhosts(ghosts));
        }
    }

    /** What the renderer draws under a focus; {@code states} and {@code blocked} are null as they were. */
    record View(Layout layout, List<State> states, boolean[] blocked, List<Ghost> ghosts) { }

    private GreenhouseFocus() { }

    /**
     * What a SkyBlock item id plants and places; empty for an item that is neither a crop, a mutation nor a soil
     * block of the item data (and for no id at all).
     */
    static Optional<Held> held(String skyblockId) {
        String id = GreenhouseSupply.normalizeId(skyblockId);
        if (id.isEmpty()) return Optional.empty();
        Set<String> crops = new HashSet<>();
        for (GreenhouseItems.Item item : GreenhouseItems.all()) {
            if (item.id() != null && GreenhouseSupply.normalizeId(item.id()).equals(id)
                || item.holdFirst().stream().anyMatch(other -> GreenhouseSupply.normalizeId(other).equals(id))) {
                crops.add(GreenhouseCrops.canonical(item.crop()));
            }
        }
        Soil soil = Soil.NONE;
        for (GreenhouseItems.Item item : GreenhouseItems.soils()) {
            if (item.id() == null || !GreenhouseSupply.normalizeId(item.id()).equals(id)) continue;
            for (Soil candidate : Soil.values()) if (candidate.displayName().equals(item.crop())) soil = candidate;
        }
        return crops.isEmpty() && soil == Soil.NONE ? Optional.empty() : Optional.of(new Held(Set.copyOf(crops), soil));
    }

    /**
     * The focus for the held item, or empty when the whole hologram stays: the item is neither crop nor soil, or the
     * layout has nothing left to place for it.
     *
     * @param states aligned with {@code placements}, or null when no scan matches the layout (all still to place)
     */
    static Optional<Focus> of(String skyblockId, List<Placement> placements, List<State> states, List<Ghost> ghosts) {
        Optional<Held> found = held(skyblockId);
        if (found.isEmpty()) return Optional.empty();
        Held held = found.get();
        List<Footprint> footprints = GreenhouseScanLogic.footprints(placements);
        boolean[] shown = new boolean[placements.size()];
        List<Footprint> covered = new ArrayList<>();
        List<Footprint> hidden = new ArrayList<>();
        boolean pending = false;
        for (int i = 0; i < shown.length; i++) {
            shown[i] = held.crops().contains(GreenhouseCrops.canonical(placements.get(i).crop()));
            if (shown[i]) {
                covered.add(footprints.get(i));
                State state = states == null || i >= states.size() ? State.EMPTY : states.get(i);
                if (state != State.CORRECT) pending = true;
            } else {
                hidden.add(footprints.get(i));
            }
        }
        Focus focus = new Focus(held, shown, List.copyOf(covered), List.copyOf(hidden));
        // A soil block of the held soil needs fixing, or one under a shown crop (which then waits for it).
        for (Ghost ghost : ghosts) if (focus.showsGhost(ghost)) pending = true;
        return pending ? Optional.of(focus) : Optional.empty();
    }
}
