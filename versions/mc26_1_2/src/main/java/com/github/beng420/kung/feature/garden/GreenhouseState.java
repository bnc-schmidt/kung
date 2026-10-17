package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.ui.UiNumberField;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the Greenhouse screen lets the player set: which plot cells are usable, the mutation goals, the solve
 * time budget and the crop priorities. Plain Java without game classes, so the rules are unit-tested and the
 * file round-trips without a running game.
 */
final class GreenhouseState {
    static final int CELLS = GreenhouseSearch.CELLS;
    static final UiNumberField COUNT = new UiNumberField(1, 100_000, 1);
    static final UiNumberField SECONDS = new UiNumberField(1, 60, 1);
    static final UiNumberField PRIORITY = new UiNumberField(0, 100, 1);
    /** The longest a solve may run; the solver may stop sooner. A saved value is kept as it is. */
    static final int DEFAULT_SECONDS = 30;
    private static final int FILE_VERSION = 1;
    private static final int MAX_BYTES = 65_536;
    /** Caps on what a stored summary may hold, so a hand-edited file cannot make the screen build huge lists. */
    private static final int MAX_GOAL_LINES = 64;
    private static final int MAX_NOTES = 16;
    private static final int MAX_LINE_LENGTH = 300;
    private static final int MAX_LAYOUT_ENTRIES = 200;
    private static final Gson JSON = new Gson();

    enum Mode { TARGET, MAX }

    /** A goal row: {@code count} only matters in {@link Mode#TARGET}, and is kept when the mode flips. */
    record Target(String mutation, Mode mode, int count) { }

    private record SavedTarget(String mutation, String mode, int count) { }

    /** {@code prioritiesOpen} is false in files written before the column could be collapsed. */
    private record SavedGoal(String text, String level, String note) { }

    private record SavedSummary(List<SavedGoal> goals, List<String> notes, long elapsedMillis, int fixed, int met) { }

    /**
     * {@code layout} is the shown grid as the site's entry list and {@code summary} the last solve's lines; both are
     * absent in files written before the planner kept its result.
     */
    private record Saved(int version, String unlocked, List<SavedTarget> targets, int seconds,
                         Map<String, Integer> priorities, boolean prioritiesOpen, JsonElement layout,
                         SavedSummary summary) { }

    private final boolean[] unlocked = new boolean[CELLS];
    private final List<Target> targets = new ArrayList<>();
    /** The names in {@link #targets}, so the render path can ask by equals instead of normalizing text. */
    private final Set<String> selected = new HashSet<>();
    private final Map<String, Integer> priorities = new TreeMap<>();
    private int seconds = DEFAULT_SECONDS;
    private boolean prioritiesOpen;
    private GreenhouseGrid layout;
    private GreenhouseSummary summary;
    private boolean painting;
    private boolean paintValue;

    GreenhouseState() {
        Arrays.fill(unlocked, true);
    }

    // --- cell mask -------------------------------------------------------------------------------------------

    boolean isUnlocked(int index) { return unlocked[index]; }

    int unlockedCount() {
        int count = 0;
        for (boolean open : unlocked) if (open) count++;
        return count;
    }

    /** A copy, for the solver request. */
    boolean[] mask() { return unlocked.clone(); }

    void unlockAll() { Arrays.fill(unlocked, true); }

    /**
     * Starts a drag: the cell under the pointer flips, and every cell the drag reaches gets that same new
     * state, so one stroke locks or unlocks a run of cells instead of flickering each one.
     */
    void beginPaint(int index) {
        painting = true;
        paintValue = !unlocked[index];
        unlocked[index] = paintValue;
    }

    /** Applies the drag's state; false when no drag is running or the cell already had it. */
    boolean paint(int index) {
        if (!painting || unlocked[index] == paintValue) return false;
        unlocked[index] = paintValue;
        return true;
    }

    void endPaint() { painting = false; }

    boolean painting() { return painting; }

    // --- goals -----------------------------------------------------------------------------------------------

    List<Target> targets() { return Collections.unmodifiableList(targets); }

    /**
     * True when added; false for a duplicate, an unknown mutation or one the planner does not offer (see
     * {@link #searchable}). Loading saved targets goes through here, so an old file cannot bring one back.
     */
    boolean add(String mutation) {
        var found = GreenhouseMutations.find(mutation).filter(GreenhouseState::searchable);
        if (found.isEmpty() || indexOf(found.get().name()) >= 0) return false;
        targets.add(new Target(found.get().name(), Mode.TARGET, 1));
        selected.add(found.get().name());
        return true;
    }

    /** Loose match (case, hyphens); for the render path use {@link #isSelected}. */
    boolean contains(String mutation) { return indexOf(mutation) >= 0; }

    /** Exact match on the mutation name as the recipe list spells it; no text processing, safe once per frame. */
    boolean isSelected(String mutationName) { return selected.contains(mutationName); }

    /** Position in {@link #targets()} of a mutation, matching loosely; -1 when it is not selected. */
    int indexOf(String mutation) {
        String wanted = GreenhouseCrops.normalize(mutation);
        for (int i = 0; i < targets.size(); i++) {
            if (GreenhouseCrops.normalize(targets.get(i).mutation()).equals(wanted)) return i;
        }
        return -1;
    }

    void remove(int index) {
        if (index >= 0 && index < targets.size()) selected.remove(targets.remove(index).mutation());
    }

    void setMode(int index, Mode mode) {
        Target target = targets.get(index);
        targets.set(index, new Target(target.mutation(), mode, target.count()));
    }

    /** Raises or lowers a count by {@code delta}, staying within 1..100000. */
    void stepCount(int index, int delta) {
        setCount(index, targets.get(index).count() + delta);
    }

    void setCount(int index, int count) {
        Target target = targets.get(index);
        targets.set(index, new Target(target.mutation(), target.mode(), COUNT.clamp(count)));
    }

    // --- time budget -----------------------------------------------------------------------------------------

    int seconds() { return seconds; }

    void setSeconds(int seconds) { this.seconds = SECONDS.clamp(seconds); }

    // --- priorities ------------------------------------------------------------------------------------------

    /** Whether the priorities column is shown; collapsed until the player opens it. */
    boolean prioritiesOpen() { return prioritiesOpen; }

    void setPrioritiesOpen(boolean open) { prioritiesOpen = open; }

    int priority(String crop) { return priorities.getOrDefault(GreenhouseCrops.canonical(crop), 0); }

    void setPriority(String crop, int value) {
        String name = GreenhouseCrops.canonical(crop);
        int clamped = PRIORITY.clamp(value);
        if (clamped == 0) priorities.remove(name);
        else priorities.put(name, clamped);
    }

    void resetPriorities() { priorities.clear(); }

    /** Only the crops with a non-zero priority. */
    Map<String, Integer> priorities() { return Map.copyOf(priorities); }

    /** How many cells of a grid hold a crop or target although the mask has them locked. */
    int cropsOnLockedCells(GreenhouseGrid grid) {
        int count = 0;
        for (int index = 0; index < CELLS; index++) {
            if (!unlocked[index] && grid.get(index / GreenhouseGrid.SIZE, index % GreenhouseGrid.SIZE) != null) count++;
        }
        return count;
    }

    // --- what was solved -------------------------------------------------------------------------------------

    /** The grid that was on show when the state was saved; null when none was kept. */
    GreenhouseGrid layout() { return layout; }

    void setLayout(GreenhouseGrid layout) { this.layout = layout; }

    /** The last solve's lines for {@link #layout()}; null for a pasted or cleared grid. */
    GreenhouseSummary summary() { return summary; }

    void setSummary(GreenhouseSummary summary) { this.summary = summary; }

    // --- solver ----------------------------------------------------------------------------------------------

    GreenhouseSolver.SolveRequest request() {
        List<GreenhouseSolver.Goal> goals = new ArrayList<>();
        for (Target target : targets) {
            goals.add(target.mode() == Mode.MAX ? GreenhouseSolver.Goal.maximize(target.mutation())
                : GreenhouseSolver.Goal.fixed(target.mutation(), target.count()));
        }
        return new GreenhouseSolver.SolveRequest(goals, mask(), priorities());
    }

    // --- mutation search -------------------------------------------------------------------------------------

    /**
     * Mutations the solver can plan but the planner does not offer as goals. Lonelily needs a ring with no crops
     * around it, so "as many as possible" just means leaving every cell empty: there is nothing to plan. It stays
     * in the recipe data, the solver and its tests; only the search list, {@link #add} and the saved targets leave
     * it out.
     */
    static final Set<String> SEARCH_EXCLUDED = Set.of("Lonelily");

    /** True when the planner lists this mutation and accepts it as a goal: solvable and not excluded. */
    static boolean searchable(GreenhouseMutations.Mutation mutation) {
        return mutation.supported() && !SEARCH_EXCLUDED.contains(mutation.name());
    }

    /**
     * The mutations the planner offers (see {@link #searchable}) whose name contains the query, ignoring case and
     * hyphen/space differences; a blank query matches all of them. Mutations without a ring recipe, and the
     * excluded ones such as Lonelily, never appear.
     */
    static List<GreenhouseMutations.Mutation> matching(List<GreenhouseMutations.Mutation> all, String query) {
        String wanted = GreenhouseCrops.normalize(query == null ? "" : query);
        List<GreenhouseMutations.Mutation> found = new ArrayList<>();
        for (GreenhouseMutations.Mutation mutation : all) {
            if (searchable(mutation) && (wanted.isEmpty() || GreenhouseCrops.normalize(mutation.name()).contains(wanted))) {
                found.add(mutation);
            }
        }
        return found;
    }

    // --- persistence -----------------------------------------------------------------------------------------

    String toJson() {
        StringBuilder mask = new StringBuilder(CELLS);
        for (boolean open : unlocked) mask.append(open ? '1' : '0');
        List<SavedTarget> saved = new ArrayList<>();
        for (Target target : targets) {
            saved.add(new SavedTarget(target.mutation(), target.mode().name().toLowerCase(Locale.ROOT), target.count()));
        }
        return JSON.toJson(new Saved(FILE_VERSION, mask.toString(), saved, seconds, new TreeMap<>(priorities),
            prioritiesOpen, layout == null ? null : GreenhouseLayoutCodec.entries(layout), savedSummary()));
    }

    /**
     * Rebuilds state from {@link #toJson()} output. Anything unusable falls back to the default for that part
     * (a bad mask opens every cell, a stale mutation name is dropped) and a broken document gives the defaults.
     */
    static GreenhouseState fromJson(String text) {
        GreenhouseState state = new GreenhouseState();
        Saved saved;
        try {
            saved = JSON.fromJson(text, Saved.class);
        } catch (RuntimeException broken) {
            return state;
        }
        if (saved == null || saved.version() != FILE_VERSION) return state;
        String mask = saved.unlocked();
        if (mask != null && mask.length() == CELLS && mask.chars().allMatch(c -> c == '0' || c == '1')) {
            for (int i = 0; i < CELLS; i++) state.unlocked[i] = mask.charAt(i) == '1';
        }
        if (saved.targets() != null) {
            for (SavedTarget target : saved.targets()) {
                if (target == null || target.mutation() == null || !state.add(target.mutation())) continue;
                int index = state.targets.size() - 1;
                state.setCount(index, target.count());
                if ("max".equals(target.mode())) state.setMode(index, Mode.MAX);
            }
        }
        state.setSeconds(saved.seconds() == 0 ? DEFAULT_SECONDS : saved.seconds());
        state.prioritiesOpen = saved.prioritiesOpen();
        state.layout = storedLayout(saved.layout());
        // A result without its layout would describe a grid nobody can see.
        state.summary = state.layout == null ? null : storedSummary(saved.summary());
        if (saved.priorities() != null) {
            saved.priorities().forEach((crop, value) -> {
                if (crop != null && value != null && GreenhouseCrops.NAMES.contains(GreenhouseCrops.canonical(crop))) {
                    state.setPriority(crop, value);
                }
            });
        }
        return state;
    }

    private SavedSummary savedSummary() {
        if (summary == null) return null;
        List<SavedGoal> goals = new ArrayList<>();
        for (GreenhouseSummary.Goal goal : summary.goals()) {
            goals.add(new SavedGoal(goal.text(), goal.level().name(), goal.note()));
        }
        return new SavedSummary(goals, summary.notes(), summary.elapsedMillis(), summary.fixed(), summary.met());
    }

    /** The grid for stored entries, or null when they are missing or break the pasted-link rules. */
    private static GreenhouseGrid storedLayout(JsonElement element) {
        if (element == null || !element.isJsonArray()) return null;
        JsonArray entries = element.getAsJsonArray();
        if (entries.size() > MAX_LAYOUT_ENTRIES) return null;
        try {
            return GreenhouseLayoutCodec.parse(entries);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** The stored lines, or null when they are missing, over the caps or not in the expected shape. */
    private static GreenhouseSummary storedSummary(SavedSummary saved) {
        if (saved == null || saved.goals() == null || saved.notes() == null || saved.goals().size() > MAX_GOAL_LINES
            || saved.notes().size() > MAX_NOTES || saved.elapsedMillis() < 0 || saved.fixed() < 0
            || saved.met() < 0 || saved.met() > saved.fixed()) {
            return null;
        }
        List<GreenhouseSummary.Goal> goals = new ArrayList<>();
        for (SavedGoal goal : saved.goals()) {
            if (goal == null || !fits(goal.text()) || goal.note() == null || !fits(goal.note())) return null;
            try {
                goals.add(new GreenhouseSummary.Goal(goal.text(), GreenhouseSummary.Level.valueOf(goal.level()), goal.note()));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                return null;
            }
        }
        for (String note : saved.notes()) {
            if (!fits(note)) return null;
        }
        return new GreenhouseSummary(goals, saved.notes(), saved.elapsedMillis(), saved.fixed(), saved.met());
    }

    private static boolean fits(String text) { return text != null && text.length() <= MAX_LINE_LENGTH; }

    /** Defaults when the file is missing, too large, unreadable or corrupt. */
    static GreenhouseState load(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return new GreenhouseState();
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return new GreenhouseState();
        }
    }

    /** Writes a sibling file and moves it over the target, so a crash never leaves half a document. */
    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
