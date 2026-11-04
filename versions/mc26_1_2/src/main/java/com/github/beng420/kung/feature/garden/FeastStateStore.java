package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.util.AtomicFiles;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Bounded profile cache; one writer coalesces updates and replaces the file atomically. */
final class FeastStateStore {
    private static final Gson JSON = new Gson();
    private static final int MAX_PROFILES = 32;
    private static final int MAX_BYTES = 131_072;
    private final Path file;
    private final Executor executor;
    private final Map<String, Saved> profiles = new LinkedHashMap<>();
    private boolean loaded;
    private boolean writing;
    private String pending;
    private CompletableFuture<Void> writer = CompletableFuture.completedFuture(null);

    record Saved(String profileId, String eventKey, FeastProgress.Snapshot progress, Long kernels,
                 long pendingKernelGains, Double kernelRatePerHour) {
        Saved {
            // An invalid optional estimate must not discard valid progress or currency from older caches.
            if (kernelRatePerHour != null && (!Double.isFinite(kernelRatePerHour) || kernelRatePerHour < 0)) {
                kernelRatePerHour = null;
            }
        }
    }
    private record Document(int version, Map<String, Saved> profiles) {}

    FeastStateStore(Path file, Executor executor) {
        this.file = file;
        this.executor = executor;
    }

    synchronized Saved get(String profile) {
        load();
        return profiles.get(profile);
    }

    synchronized void put(String profile, Saved value) {
        load();
        if (!validKey(profile) || !valid(value) || Objects.equals(profiles.get(profile), value)) return;
        profiles.remove(profile);
        profiles.put(profile, value);
        while (profiles.size() > MAX_PROFILES) profiles.remove(profiles.keySet().iterator().next());
        pending = JSON.toJson(new Document(1, profiles));
        if (!writing) {
            writing = true;
            writer = CompletableFuture.runAsync(this::drain, executor);
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Feast cache exceeds size limit");
            var data = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
            if (data == null || data.version != 1 || data.profiles == null
                || data.profiles.size() > MAX_PROFILES) throw new IOException("Invalid Feast cache");
            data.profiles.forEach((key, value) -> {
                if (validKey(key) && valid(value)) profiles.put(key, value);
            });
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Could not read Kung's Feast progress cache.", exception);
        }
    }

    private void drain() {
        while (true) {
            String data;
            synchronized (this) {
                data = pending;
                pending = null;
                if (data == null) {
                    writing = false;
                    return;
                }
            }
            try {
                AtomicFiles.write(file, data);
            } catch (IOException exception) {
                KungMod.LOGGER.warn("Could not save Kung's Feast progress cache.", exception);
            }
        }
    }

    void flush() {
        CompletableFuture<Void> active;
        synchronized (this) { active = writer; }
        try { active.get(3, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        catch (Exception exception) { KungMod.LOGGER.warn("Could not finish saving Kung's Feast progress cache.", exception); }
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9-]{36}:[a-z]{1,32}");
    }

    private static boolean valid(Saved value) {
        if (value == null || value.profileId == null || !value.profileId.matches("(?:[a-f0-9-]{36})?")
            || value.eventKey == null || value.kernels != null && value.kernels < 0) return false;
        if (value.pendingKernelGains < 0
            || value.pendingKernelGains > (value.kernels == null ? 0 : value.kernels)) return false;
        var progress = value.progress;
        if (progress == null) return value.eventKey.isEmpty();
        if (progress.kind() == null || !value.eventKey.matches(
            (progress.kind() == FeastProgress.Kind.GRAND ? "grand:" : "harvest:") + "[0-9]{1,6}")) return false;
        if (progress.goals().size() != (progress.kind() == FeastProgress.Kind.GRAND ? 9 : 5)
            || progress.donations() < 0) return false;
        int previous = 0;
        for (Integer goal : progress.goals()) {
            if (goal == null || goal <= previous || goal > 1_000_000) return false;
            previous = goal;
        }
        return progress.donations() <= previous;
    }
}
