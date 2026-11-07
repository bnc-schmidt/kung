package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class DungeonApiEnrichment {
    /**
     * A failed start value is asked again after these pauses (3 + 10 + 30 s). A later value would miss the secrets
     * found meanwhile, so the run gives up after that. The end value has only the summary's wait.
     */
    private static final long[] BASELINE_RETRY_MS = {3_000L, 10_000L, 30_000L};
    private static final long[] FINAL_RETRY_MS = {1_500L, 4_000L};
    private static final long[] NO_RETRY = {};
    private static final int MAX_PROBLEM_LENGTH = 80;

    private final Set<String> requestedTotals = new HashSet<>();
    private final Set<String> requestedBaselines = new HashSet<>();
    private final Set<String> requestedFinals = new HashSet<>();
    private final Map<String, Integer> baselines = new HashMap<>();
    private boolean finalFetchStarted;
    private long finalFetchStartTick = Long.MIN_VALUE;
    private int pendingFinalFetches;
    private long generation;
    /** Why a number is missing: the first problem of this run, shown in the summary. */
    private String problem = "";

    public void reset() {
        generation++;
        requestedTotals.clear();
        requestedBaselines.clear();
        requestedFinals.clear();
        baselines.clear();
        finalFetchStarted = false;
        finalFetchStartTick = Long.MIN_VALUE;
        pendingFinalFetches = 0;
        problem = "";
    }

    public boolean finalFetchStarted() {
        return finalFetchStarted;
    }

    public String problem() {
        return problem;
    }

    public void startFinalFetch(long nowTick) {
        if (finalFetchStarted) return;
        finalFetchStarted = true;
        finalFetchStartTick = nowTick;
    }

    public boolean finalFetchCompleteOrTimedOut(long nowTick, long timeoutTicks) {
        boolean timedOut = nowTick - finalFetchStartTick >= timeoutTicks;
        if (pendingFinalFetches > 0 && timedOut) note("the answers came too late");
        return pendingFinalFetches <= 0 || timedOut;
    }

    public boolean hasPendingFinalFetches() {
        return pendingFinalFetches > 0;
    }

    public void requestTotal(
        Minecraft client,
        String name,
        Consumer<HypixelSkyBlockProfileClient.SecretResult> onSuccess
    ) {
        String requestedName = key(name);
        // The end value of the run already is the total: asking again spends the server's limit for nothing.
        if (requestedFinals.contains(requestedName) || !requestedTotals.add(requestedName)) {
            return;
        }
        request(client, name, "total-secrets", NO_RETRY, 0, () -> false, onSuccess, () -> { });
    }

    public void requestBaseline(
        Minecraft client,
        String name,
        Consumer<HypixelSkyBlockProfileClient.SecretResult> onSuccess
    ) {
        String requestedName = key(name);
        if (finalFetchStarted || !requestedBaselines.add(requestedName)) {
            return;
        }
        request(client, name, "run-secret baseline", BASELINE_RETRY_MS, 0, () -> !finalFetchStarted, result -> {
            if (finalFetchStarted) {
                KungDebugRecorder.event("player-stats", "run-secret baseline ignored after finish player=" + name);
                return;
            }
            baselines.put(resultKey(result, name), result.secretsFound());
            baselines.put(requestedName, result.secretsFound());
            onSuccess.accept(result);
        }, () -> { });
    }

    public void requestFinal(
        Minecraft client,
        String name,
        BiConsumer<HypixelSkyBlockProfileClient.SecretResult, Integer> onSuccess
    ) {
        String requestedName = key(name);
        if (!requestedFinals.add(requestedName)) {
            return;
        }
        // Only a start snapshot established before the final request can be compared.
        Integer baseline = baselines.get(requestedName);
        if (baseline == null) {
            KungDebugRecorder.event("player-stats", "run-secret final without start value player=" + name);
            note("no start value for " + name);
        }
        pendingFinalFetches++;
        request(client, name, "run-secret final", FINAL_RETRY_MS, 0, () -> true,
            result -> onSuccess.accept(result, runSecretDelta(baseline, result.secretsFound())),
            () -> pendingFinalFetches = Math.max(0, pendingFinalFetches - 1));
    }

    /**
     * One lookup. A transport or HTTP failure (not a 429: that window lasts minutes) is asked again after the next
     * pause while {@code wanted} holds; {@code onDone} runs once, after the answer or the last failure.
     */
    private void request(
        Minecraft client,
        String name,
        String operation,
        long[] retryPausesMs,
        int attempt,
        BooleanSupplier wanted,
        Consumer<HypixelSkyBlockProfileClient.SecretResult> onSuccess,
        Runnable onDone
    ) {
        long requestedGeneration = generation;
        HypixelSkyBlockProfileClient.INSTANCE.loadTotalSecrets(name).whenComplete((result, throwable) ->
            client.execute(() -> {
                if (requestedGeneration != generation) return;
                String error = errorOf(result, throwable);
                if (error == null) {
                    try {
                        onSuccess.accept(result);
                    } finally {
                        onDone.run();
                    }
                    return;
                }
                boolean retry = retryable(result, throwable, error) && attempt < retryPausesMs.length && wanted.getAsBoolean();
                KungDebugRecorder.event("player-stats", operation + " failed player=" + name + " error=" + error
                    + (retry ? " retry in " + retryPausesMs[attempt] + " ms" : ""));
                if (!retry) {
                    note(operation + " " + error);
                    onDone.run();
                    return;
                }
                CompletableFuture.delayedExecutor(retryPausesMs[attempt], TimeUnit.MILLISECONDS).execute(() ->
                    client.execute(() -> {
                        if (requestedGeneration != generation) return;
                        if (wanted.getAsBoolean()) {
                            request(client, name, operation, retryPausesMs, attempt + 1, wanted, onSuccess, onDone);
                        } else {
                            onDone.run();
                        }
                    }));
            })
        );
    }

    /** Null for a usable answer, else what went wrong. */
    private static String errorOf(HypixelSkyBlockProfileClient.SecretResult result, Throwable throwable) {
        if (throwable != null) return throwable.getClass().getSimpleName();
        if (result == null) return "empty response";
        if (!result.success()) return result.error() == null || result.error().isBlank() ? "request failed" : result.error();
        return result.secretsFound() < 0 ? "total secrets unavailable" : null;
    }

    /** A hidden counter ("unavailable") or a limit window (429) stays the same on the next ask. */
    private static boolean retryable(HypixelSkyBlockProfileClient.SecretResult result, Throwable throwable, String error) {
        boolean answered = throwable == null && result != null && result.success();
        return !answered && !error.contains("429");
    }

    private void note(String text) {
        if (problem.isEmpty()) {
            problem = text.length() > MAX_PROBLEM_LENGTH ? text.substring(0, MAX_PROBLEM_LENGTH) + "..." : text;
        }
    }

    static int runSecretDelta(Integer baseline, int finalTotal) {
        // Missing achievements use -1, not zero. Subtracting that sentinel would
        // turn the entire lifetime counter into a fabricated run contribution.
        return baseline != null && baseline >= 0 && finalTotal >= baseline ? finalTotal - baseline : -1;
    }

    private static String resultKey(HypixelSkyBlockProfileClient.SecretResult result, String fallbackName) {
        String resultName = HypixelLocation.isPlayerName(result.name()) ? result.name() : fallbackName;
        return key(resultName);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
