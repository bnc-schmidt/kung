package com.github.beng420.kung.config.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SplitsConfig extends ConfigCategory {
    public static final int RECENT_RUN_LIMIT = 20;
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
    private transient long predictionRevision;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public int x() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
        save();
    }

    public int y() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
        save();
    }

    public int scale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = Math.clamp(scale, 25, 300);
        save();
    }

    public TimeFormat format() {
        return format == null ? TimeFormat.MINUTES : format;
    }

    public void setFormat(TimeFormat format) {
        this.format = format == null ? TimeFormat.MINUTES : format;
        save();
    }

    public String formatLabel() { return format().label(); }
    public void cycleFormat() { setFormat(format().next()); }

    public boolean timeLost() { return timeLost; }

    public void setTimeLost(boolean timeLost) {
        this.timeLost = timeLost;
        save();
    }

    public boolean runEndChat() { return runEndChat; }

    public void setRunEndChat(boolean runEndChat) {
        this.runEndChat = runEndChat;
        save();
    }

    public boolean pbTitle() { return pbTitle; }

    public void setPbTitle(boolean pbTitle) {
        this.pbTitle = pbTitle;
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

    public void setFloorRecordsSeeded(boolean seeded) {
        this.floorRecordsSeeded = seeded;
        save();
    }

    public boolean timePrediction() { return timePrediction; }

    public void setTimePrediction(boolean timePrediction) {
        this.timePrediction = timePrediction;
        save();
    }

    public PredictionMode predictionMode() {
        return predictionMode == null ? PredictionMode.PHASE_END : predictionMode;
    }

    public void setPredictionMode(PredictionMode predictionMode) {
        this.predictionMode = predictionMode == null ? PredictionMode.PHASE_END : predictionMode;
        save();
    }

    public PredictionSource predictionSource() {
        return predictionSource == null ? PredictionSource.PB : predictionSource;
    }

    public void setPredictionSource(PredictionSource source) {
        predictionSource = source == null ? PredictionSource.PB : source;
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

    /** Coalesce a batch of measured phases into one save, only when a best improves. */
    public void recordPersonalBests(int floor, boolean masterMode, Map<String, Long> measurements) {
        recordRun(floor, masterMode, measurements, Map.of());
    }

    /** Flush PB candidates and, only for a confirmed finish, the run's AVG samples in one save. */
    public void recordRun(int floor, boolean masterMode, Map<String, Long> measurements, Map<String, Long> completedRun) {
        String key = floorKey(floor, masterMode);
        if (key == null || measurements == null) return;
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
        if (changed) {
            predictionRevision++;
            save();
        }
    }

    private static String floorKey(int floor, boolean masterMode) {
        if (floor < 0 || floor > 7) return null;
        return floor == 0 ? "Entrance" : (masterMode ? "M" : "F") + floor;
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
        public TimeFormat next() { return this == MINUTES ? SECONDS : MINUTES; }
    }
}
