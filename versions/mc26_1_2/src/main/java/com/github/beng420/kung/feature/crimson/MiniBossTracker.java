package com.github.beng420.kung.feature.crimson;

import com.github.beng420.kung.config.category.MiniBossConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.waypoints.WaypointIsland;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Watches the five Crimson Isle minibosses from the client while the Miniboss Timer is on: the DOWN and spawning chat
 * lines, the beacon block in each boss area and the boss's nameplate. What it sees feeds one {@link MiniBossTimer}
 * per boss and lobby, which the HUD reads.
 */
public final class MiniBossTracker extends ConfigurableFeature<MiniBossConfig> {
    public static final MiniBossTracker INSTANCE = new MiniBossTracker();
    /** Entities are looked for this near the area; the boss's nameplate was first seen about 30 blocks off. */
    private static final int MOB_RANGE = 48;
    /**
     * A missing boss counts as dead only with the player this near the spawn spot (the boss fights within about 15
     * blocks of it, 30 for Magma Boss, and its nameplate shows from about 30 away).
     */
    private static final int MOB_VIEW = 20;
    /**
     * The beacon shows only near the player: seen at 88.8 blocks, gone at 113.2 and 118.8 (the beacon vanished while the
     * area's chunks stayed loaded). Beyond this a missing beacon proves nothing.
     */
    private static final int BEACON_RANGE = 80;
    private static final Pattern DOWN = Pattern.compile("(?i)^(.+?) DOWN!$");
    private static final Pattern SPAWNING = Pattern.compile("(?i)^BEWARE - (.+?) Is Spawning\\.?$");

    private final Map<MiniBoss, MiniBossTimer> timers = new EnumMap<>(MiniBoss.class);
    private String lobby;
    private boolean onIsle;
    private List<String> islandLines;
    private int ticks;
    private int blankTicks;

    private MiniBossTracker() { super(config -> config.miniBoss); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) chat(message);
            return true;
        });
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    /** Whether the client is in a Crimson Isle lobby the timers are being kept for. */
    public boolean inLobby() { return lobby != null; }

    /** What the HUD writes for a boss in this lobby. */
    public MiniBossTimer.Text text(MiniBoss boss) {
        var timer = timers.get(boss);
        return timer == null ? new MiniBossTimer.Text("Unknown", 0xFF5555) : timer.text(System.currentTimeMillis());
    }

    @Override
    protected void onReset() { setLobby(null); }

    private void tick(Minecraft client) {
        var level = client.level;
        var player = client.player;
        var tracker = HypixelInstanceTracker.INSTANCE;
        if (!isEnabled() || level == null || player == null || !tracker.tracking()) {
            setLobby(null);
            return;
        }
        List<String> lines = tracker.visibleLines();
        if (lines != islandLines) {
            islandLines = lines;
            onIsle = WaypointIsland.current(lines).id().equals("crimson_isle");
        }
        String id = tracker.serverId();
        // An id Kung cannot read must not switch the whole feature off: after 3 s on the isle the lobby is "?".
        blankTicks = onIsle && id.isBlank() ? blankTicks + 1 : 0;
        if (id.isBlank() && blankTicks > 60) id = "?";
        setLobby(onIsle && !id.isBlank() ? id + "#" + tracker.instanceEpoch() : null);
        if (lobby == null || ++ticks % 2 != 0) return;
        for (MiniBoss boss : MiniBoss.values()) scan(level, boss, player.getX(), player.getZ());
    }

    private void scan(ClientLevel level, MiniBoss boss, double playerX, double playerZ) {
        double dist = boss.distanceTo(playerX, playerZ);
        boolean mobView = dist <= MOB_RANGE && boss.distanceToSpawn(playerX, playerZ) <= MOB_VIEW;
        boolean beaconView = dist <= BEACON_RANGE && chunksLoaded(level, boss);
        boolean beacon = beaconView && hasBeacon(level, boss);
        boolean mob = dist <= MOB_RANGE && hasBoss(level, boss);
        timers.get(boss).observe(System.currentTimeMillis(), beaconView, beacon, mobView, mob);
    }

    private static boolean chunksLoaded(ClientLevel level, MiniBoss boss) {
        var area = boss.area();
        for (int cx = (int) Math.floor(area.minX) >> 4; cx <= (int) Math.floor(area.maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(area.minZ) >> 4; cz <= (int) Math.floor(area.maxZ) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    /** Whether a beacon block stands in the area; only chunks with block entities are asked, never every block. */
    private static boolean hasBeacon(ClientLevel level, MiniBoss boss) {
        var area = boss.area();
        for (int cx = (int) Math.floor(area.minX) >> 4; cx <= (int) Math.floor(area.maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(area.minZ) >> 4; cz <= (int) Math.floor(area.maxZ) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (area.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                        && chunk.getBlockState(pos).is(Blocks.BEACON)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether the boss's nameplate is in the area, searched a good way above and below it (Magma Boss leaps). */
    private static boolean hasBoss(ClientLevel level, MiniBoss boss) {
        for (Entity entity : level.getEntitiesOfClass(Entity.class, boss.area().inflate(4, 30, 4), Entity::hasCustomName)) {
            if (boss.isNameplate(HypixelLocation.clean(entity.getCustomName().getString()))) return true;
        }
        return false;
    }

    private void chat(Component message) {
        if (!isEnabled()) return;
        String text = HypixelLocation.clean(message.getString());
        Matcher down = DOWN.matcher(text);
        Matcher spawning = SPAWNING.matcher(text);
        MiniBoss boss;
        if (down.matches() && (boss = MiniBoss.byName(down.group(1))) != null) {
            if (timers.containsKey(boss)) timers.get(boss).down(System.currentTimeMillis());
        } else if (spawning.matches() && (boss = MiniBoss.byName(spawning.group(1))) != null) {
            if (timers.containsKey(boss)) timers.get(boss).spawned(System.currentTimeMillis());
        }
    }

    private void setLobby(String key) {
        if (Objects.equals(key, lobby)) return;
        lobby = key;
        timers.clear();
        if (key != null) for (MiniBoss boss : MiniBoss.values()) timers.put(boss, new MiniBossTimer(boss.absentHold()));
        ticks = 0;
    }
}
