package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class DungeonApiEnrichment {
    private final Set<String> requestedTotals = new HashSet<>();
    private final Set<String> requestedBaselines = new HashSet<>();
    private final Set<String> requestedFinals = new HashSet<>();
    private final Map<String, Integer> baselines = new HashMap<>();
    private boolean finalFetchStarted;
    private long finalFetchStartTick = Long.MIN_VALUE;
    private int pendingFinalFetches;
    private long generation;

    public void reset() {
        generation++;
        requestedTotals.clear();
        requestedBaselines.clear();
        requestedFinals.clear();
        baselines.clear();
        finalFetchStarted = false;
        finalFetchStartTick = Long.MIN_VALUE;
        pendingFinalFetches = 0;
    }

    public boolean finalFetchStarted() {
        return finalFetchStarted;
    }

    public void startFinalFetch(long nowTick) {
        if (finalFetchStarted) return;
        finalFetchStarted = true;
        finalFetchStartTick = nowTick;
    }

    public boolean finalFetchCompleteOrTimedOut(long nowTick, long timeoutTicks) {
        return pendingFinalFetches <= 0 || nowTick - finalFetchStartTick >= timeoutTicks;
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
        if (!requestedTotals.add(requestedName)) {
            return;
        }
        request(client, name, "total-secrets", onSuccess, () -> { });
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
        request(client, name, "run-secret baseline", result -> {
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
        pendingFinalFetches++;
        request(client, name, "run-secret final",
            result -> onSuccess.accept(result, runSecretDelta(baseline, result.secretsFound())),
            () -> pendingFinalFetches = Math.max(0, pendingFinalFetches - 1));
    }

    private void request(
        Minecraft client,
        String name,
        String operation,
        Consumer<HypixelSkyBlockProfileClient.SecretResult> onSuccess,
        Runnable onComplete
    ) {
        long requestedGeneration = generation;
        HypixelSkyBlockProfileClient.INSTANCE.loadTotalSecrets(name).whenComplete((result, throwable) ->
            client.execute(() -> {
                if (requestedGeneration != generation) return;
                try {
                    if (throwable != null) {
                        KungDebugRecorder.event("player-stats", operation + " failed player=" + name
                            + " error=" + throwable.getClass().getSimpleName());
                        return;
                    }
                    if (result == null || !result.success() || result.secretsFound() < 0) {
                        KungDebugRecorder.event("player-stats", operation + " failed player=" + name
                            + " error=" + (result == null ? "empty response"
                                : !result.success() ? result.error() : "total secrets unavailable"));
                        return;
                    }
                    onSuccess.accept(result);
                } finally {
                    onComplete.run();
                }
            })
        );
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
