package com.github.beng420.kung.config.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SplitsConfig extends ConfigCategory {
    public static final int RECENT_RUN_LIMIT = 20;
    /** The two rows of a best run that are no phase: the time when the boss was entered, and the whole run. */
    public static final String BEST_RUN_BOSS_ENTRY = "Boss Entry";
    public static final String BEST_RUN_TOTAL = "Total";
    private boolean enabled = false;
    private int x = 226;
    private int y = 8;
    private int scale = 85;
    private TimeFormat format = TimeFormat.MINUTES;
    private boolean timeLost = true;
    private boolean runEndChat = false;
    private boolean pbTitle = true;
    private boolean timePrediction = true;
    private PredictionMode predictionMode = PredictionMode.PHASE_END;
    private PredictionSource predictionSource = PredictionSource.PB;
    /** Real phase durations in milliseconds, keyed by Entrance/F1–F7/M1–M7 and phase name. */
    private Map<String, Map<String, Long>> personalBests = new TreeMap<>();
    /** Oldest to newest confirmed runs, using the same floor keys and real milliseconds as PBs. */
    private Map<String, List<Map<String, Long>>> recentRuns = new TreeMap<>();
    /** Whole-run record times in milliseconds by floor key: seeded once from the API, then from Hypixel's NEW RECORD lines. */
    private Map<String, Long> floorRecords = new TreeMap<>();
    private boolean floorRecordsSeeded = false;
    /** Show the best recorded run beside the splits (overlay) and after each line (chat paste). */
    private boolean includePbRun = false;
    /** Which best run the overlay and the chat paste compare with. */
    private PbScope pbScope = PbScope.ALL_TIME;
    /** The fastest run of each floor key since the game started, in the shape of {@link #bestRuns}; never saved. */
    private transient Map<String, Map<String, Long>> sessionBestRuns = new TreeMap<>();
    /**
     * The fastest confirmed run Kung recorded per floor key: real milliseconds by phase, plus {@link #BEST_RUN_BOSS_ENTRY}
     * and {@link #BEST_RUN_TOTAL}. It is always recorded; {@link #includePbRun} only decides whether it is shown.
     */
    private Map<String, Map<String, Long>> bestRuns = new TreeMap<>();
    private transient long predictionRevision;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public int x() { return x; }
    public void setX(int value) { x = value; save(); }
    public int y() { return y; }
    public void setY(int value) { y = value; save(); }
    public int scale() { return scale; }
    @Override
    public void normalize() {
        setScale(scale());
        setFormat(format());
    }
    public void setScale(int value) { scale = Math.clamp(value, 25, 300); save(); }
    public TimeFormat format() { return format == null ? TimeFormat.MINUTES : format; }
    public void setFormat(TimeFormat value) { format = value == null ? TimeFormat.MINUTES : value; save(); }
    public boolean timeLost() { return timeLost; }
    public void setTimeLost(boolean value) { timeLost = value; save(); }
    public boolean runEndChat() { return runEndChat; }
    public void setRunEndChat(boolean value) { runEndChat = value; save(); }
    public boolean pbTitle() { return pbTitle; }
    public void setPbTitle(boolean value) { pbTitle = value; save(); }
    public boolean includePbRun() { return includePbRun; }
    public void setIncludePbRun(boolean value) { includePbRun = value; save(); }

    /** The splits of the floor's fastest recorded run, by phase name; empty when none was recorded. */
    public Map<String, Long> bestRun(int floor, boolean masterMode) {
        String key = floorKey(floor, masterMode);
        Map<String, Long> run = key == null || bestRuns == null ? null : bestRuns.get(key);
        return run == null ? Map.of() : Map.copyOf(run);
    }

    /** The whole-run time of the fastest recorded run in milliseconds, -1 when none. */
    public long bestRunMillis(int floor, boolean masterMode) {
        Long total = bestRun(floor, masterMode).get(BEST_RUN_TOTAL);
        return total != null && total > 0L ? total : -1L;
    }

    public PbScope pbScope() { return pbScope == null ? PbScope.ALL_TIME : pbScope; }
    public void setPbScope(PbScope value) { pbScope = value == null ? PbScope.ALL_TIME : value; save(); }

    /** The best run the overlay and the chat paste compare with: the saved one, or the fastest since the game started. */
    public Map<String, Long> comparedRun(int floor, boolean masterMode) {
        if (pbScope() == PbScope.ALL_TIME) return bestRun(floor, masterMode);
        String key = floorKey(floor, masterMode);
        Map<String, Long> run = key == null || sessionBestRuns == null ? null : sessionBestRuns.get(key);
        return run == null ? Map.of() : Map.copyOf(run);
    }

    /** Keeps the run when it is faster than the floor's recorded one; the caller saves. True when it became the best. */
    private boolean keepBestRun(int floor, boolean masterMode, Map<String, Long> run) {
        if (bestRuns == null) bestRuns = new TreeMap<>();
        return keepFaster(bestRuns, floorKey(floor, masterMode), run);
    }

    /** The same for this session's best; it is never saved. */
    private void keepSessionRun(int floor, boolean masterMode, Map<String, Long> run) {
        if (sessionBestRuns == null) sessionBestRuns = new TreeMap<>();
        keepFaster(sessionBestRuns, floorKey(floor, masterMode), run);
    }

    private static boolean keepFaster(Map<String, Map<String, Long>> runs, String key, Map<String, Long> run) {
        Long total = run == null ? null : run.get(BEST_RUN_TOTAL);
        if (key == null || total == null || total <= 0L) return false;
        Map<String, Long> known = runs.get(key);
        Long knownTotal = known == null ? null : known.get(BEST_RUN_TOTAL);
        if (knownTotal != null && knownTotal > 0L && knownTotal <= total) return false;
        Map<String, Long> kept = new TreeMap<>();
        run.forEach((phase, duration) -> {
            if (phase != null && !phase.isBlank() && duration != null && duration > 0L) kept.put(phase, duration);
        });
        runs.put(key, kept);
        return true;
    }

    public void clearBestRun(int floor, boolean masterMode) {
        String key = floorKey(floor, masterMode);
        if (key == null || bestRuns == null || bestRuns.remove(key) == null) return;
        save();
    }

    /** The floor's record time in milliseconds, -1 when unknown. */
    public long floorRecordMillis(int floor, boolean masterMode) {
        String key = floorKey(floor, masterMode);
        Long record = key == null || floorRecords == null ? null : floorRecords.get(key);
        return record != null && record > 0L ? record : -1L;
    }

    /** Remembers a record time; a slower time never replaces a faster one (the API tiers S and S+ differ). */
    public void recordFloorTime(int floor, boolean masterMode, long millis) {
        String key = floorKey(floor, masterMode);
        if (key == null || millis <= 0L) return;
        long known = floorRecordMillis(floor, masterMode);
        if (known > 0L && known <= millis) return;
        if (floorRecords == null) floorRecords = new TreeMap<>();
        floorRecords.put(key, millis);
        save();
    }

    public boolean floorRecordsSeeded() { return floorRecordsSeeded; }
    public void setFloorRecordsSeeded(boolean value) { floorRecordsSeeded = value; save(); }
    public boolean timePrediction() { return timePrediction; }
    public void setTimePrediction(boolean value) { timePrediction = value; save(); }
    public PredictionMode predictionMode() { return predictionMode == null ? PredictionMode.PHASE_END : predictionMode; }
    public void setPredictionMode(PredictionMode value) {
        predictionMode = value == null ? PredictionMode.PHASE_END : value;
        save();
    }
    public PredictionSource predictionSource() { return predictionSource == null ? PredictionSource.PB : predictionSource; }
    public void setPredictionSource(PredictionSource value) {
        predictionSource = value == null ? PredictionSource.PB : value;
        predictionRevision++;
        save();
    }

    public long predictionRevision() { return predictionRevision; }

    public long predictionMillis(int floor, boolean masterMode, String phase) {
        return predictionSource() == PredictionSource.AVG
            ? averageMillis(floor, masterMode, phase) : personalBestMillis(floor, masterMode, phase);
    }

    public long averageMillis(int floor, boolean masterMode, String phase) {
        if (phase == null) return -1L;
        double total = 0D;
        int count = 0;
        for (Map<String, Long> run : recentRunsFor(floor, masterMode)) {
            Long duration = run == null ? null : run.get(phase);
            if (duration == null || duration <= 0L) continue;
            total += duration;
            count++;
        }
        return count == 0 ? -1L : Math.round(total / count);
    }

    public int recentRunCount(int floor, boolean masterMode) {
        return recentRunsFor(floor, masterMode).size();
    }

    private List<Map<String, Long>> recentRunsFor(int floor, boolean masterMode) {
        String key = floorKey(floor, masterMode);
        List<Map<String, Long>> runs = key == null || recentRuns == null ? null : recentRuns.get(key);
        return runs == null ? List.of() : runs.subList(Math.max(0, runs.size() - RECENT_RUN_LIMIT), runs.size());
    }

    public void clearRecentRuns(int floor, boolean masterMode) {
        String key = floorKey(floor, masterMode);
        if (key == null || recentRuns == null || recentRuns.remove(key) == null) return;
        predictionRevision++;
        save();
    }

    public long personalBestMillis(int floor, boolean masterMode, String phase) {
        String key = floorKey(floor, masterMode);
        if (key == null || phase == null || personalBests == null) return -1L;
        Map<String, Long> phases = personalBests.get(key);
        Long best = phases == null ? null : phases.get(phase);
        return best != null && best > 0L ? best : -1L;
    }

    /** Explicit edits may replace a best with a slower time as well as a faster one. */
    public void setPersonalBestMillis(int floor, boolean masterMode, String phase, long millis) {
        String key = floorKey(floor, masterMode);
        if (key == null || phase == null || phase.isBlank() || millis <= 0L) return;
        if (personalBestMillis(floor, masterMode, phase) == millis) return;
        if (personalBests == null) personalBests = new TreeMap<>();
        personalBests.computeIfAbsent(key, ignored -> new TreeMap<>()).put(phase, millis);
        predictionRevision++;
        save();
    }

    public void clearPersonalBest(int floor, boolean masterMode, String phase) {
        String key = floorKey(floor, masterMode);
        if (key == null || phase == null || personalBests == null) return;
        Map<String, Long> phases = personalBests.get(key);
        if (phases == null || !phases.containsKey(phase)) return;
        phases.remove(phase);
        if (phases.isEmpty()) personalBests.remove(key);
        predictionRevision++;
        save();
    }

    /** Flush PB candidates and, only for a confirmed finish, the run's AVG samples in one save. */
    public void recordRun(int floor, boolean masterMode, Map<String, Long> measurements, Map<String, Long> completedRun) {
        recordRun(floor, masterMode, measurements, completedRun, null);
    }

    /**
     * The same, and a confirmed run's splits ({@code bestRun}, with {@link #BEST_RUN_BOSS_ENTRY} and
     * {@link #BEST_RUN_TOTAL}) become the floor's best run when its total beats the recorded one. All in one save.
     * Returns whether it did.
     */
    public boolean recordRun(int floor, boolean masterMode, Map<String, Long> measurements,
                             Map<String, Long> completedRun, Map<String, Long> bestRun) {
        String key = floorKey(floor, masterMode);
        if (key == null || measurements == null) return false;
        boolean changed = false;
        for (var entry : measurements.entrySet()) {
            String phase = entry.getKey();
            Long duration = entry.getValue();
            if (phase == null || phase.isBlank() || duration == null || duration <= 0L) continue;
            long previous = personalBestMillis(floor, masterMode, phase);
            if (previous > 0L && duration >= previous) continue;
            if (personalBests == null) personalBests = new TreeMap<>();
            personalBests.computeIfAbsent(key, ignored -> new TreeMap<>()).put(phase, duration);
            changed = true;
        }
        Map<String, Long> sample = new TreeMap<>();
        if (completedRun != null) completedRun.forEach((phase, duration) -> {
            if (phase != null && !phase.isBlank() && duration != null && duration > 0L) sample.put(phase, duration);
        });
        if (!sample.isEmpty()) {
            List<Map<String, Long>> runs = new ArrayList<>(recentRunsFor(floor, masterMode));
            runs.add(sample);
            if (runs.size() > RECENT_RUN_LIMIT) runs.removeFirst();
            if (recentRuns == null) recentRuns = new TreeMap<>();
            recentRuns.put(key, runs);
            changed = true;
        }
        boolean newBest = keepBestRun(floor, masterMode, bestRun);
        keepSessionRun(floor, masterMode, bestRun);
        if (changed || newBest) {
            predictionRevision++;
            save();
        }
        return newBest;
    }

    private static String floorKey(int floor, boolean masterMode) {
        if (floor < 0 || floor > 7) return null;
        return floor == 0 ? "Entrance" : (masterMode ? "M" : "F") + floor;
    }

    public enum PbScope {
        ALL_TIME("All Time"), SESSION("Session");

        private final String label;

        PbScope(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum PredictionSource {
        PB, AVG;

        public String label() { return name(); }
    }

    public enum PredictionMode {
        PHASE_END("Phase End"), LIVE("Live");

        private final String label;

        PredictionMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum TimeFormat {
        MINUTES("Minutes"), SECONDS("Seconds");

        private final String label;

        TimeFormat(String label) { this.label = label; }
        public String label() { return label; }
    }
}
