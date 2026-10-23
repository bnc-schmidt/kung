package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.KungDebugRecorder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Fetches the whole-run record times once, so the first PB title already knows the old time. After that Hypixel's own
 * "NEW RECORD" lines keep them current (see {@link DungeonPbTitle}). Your Hypixel API key is used first, else the
 * Kung server, like the Catacombs calculator.
 */
public final class DungeonPbTitleFeature extends ConfigurableFeature<SplitsConfig> {
    public static final DungeonPbTitleFeature INSTANCE = new DungeonPbTitleFeature();
    private static final long FIRST_ATTEMPT_DELAY_MILLIS = 30_000L;
    private static final long RETRY_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_ATTEMPTS = 3;
    private boolean seeding;
    private int attempts;
    private long nextAttemptMillis;

    private DungeonPbTitleFeature() { super(config -> config.splits); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled() && config().pbTitle(); }

    private void tick(Minecraft client) {
        if (client.player == null || seeding || attempts >= MAX_ATTEMPTS || !isEnabled() || config().floorRecordsSeeded()) return;
        var server = client.getCurrentServer();
        if (server == null || !HypixelLocation.isHypixelAddress(server.ip)) return;
        long now = System.currentTimeMillis();
        if (nextAttemptMillis == 0L) nextAttemptMillis = now + FIRST_ATTEMPT_DELAY_MILLIS;
        if (now < nextAttemptMillis) return;
        seeding = true;
        attempts++;
        nextAttemptMillis = now + RETRY_MILLIS;
        HypixelSkyBlockProfileClient.INSTANCE.loadCalculatorPlayer(client.player.getName().getString())
            .whenComplete((result, throwable) -> client.execute(() -> {
                seeding = false;
                if (throwable == null && result != null && result.success()) apply(result);
            }));
    }

    private void apply(HypixelSkyBlockProfileClient.ProfileResult result) {
        try {
            var stats = result.player().selectedProfile().stats();
            if (!stats.available()) return;
            var records = DungeonPbTitle.recordsFrom(stats);
            records.forEach(record -> config().recordFloorTime(record.floor(), record.master(), record.millis()));
            config().setFloorRecordsSeeded(true);
            KungDebugRecorder.event("splits", "floor records seeded floors=" + records.size() + " source=" + result.source());
        } catch (RuntimeException exception) {
            KungDebugRecorder.event("splits", "floor records unreadable " + exception);
        }
    }
}
