package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.WaypointsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the player's saved waypoints on the island they belong to, and moves ordered groups (routes) on as the
 * player walks up to the next waypoint. The groups are in {@link WaypointStore}; commands edit them
 * ({@code /kung waypoints}), {@link WaypointRenderer} draws them.
 */
public final class WaypointsFeature extends ConfigurableFeature<WaypointsConfig> {
    public static final WaypointsFeature INSTANCE = new WaypointsFeature();
    /** The island is read from the tab list twice a second, not every tick. */
    private static final int ISLAND_CHECK_TICKS = 10;

    /** A waypoint that is drawn this frame, with its place on the route when its group is ordered. */
    public record Shown(WaypointGroup group, Waypoint waypoint, int index, WaypointRoute.Role role) { }

    private final WaypointStore store = WaypointStore.INSTANCE;
    private final WaypointEditor editor = new WaypointEditor(store);
    private WaypointIsland island = WaypointIsland.UNKNOWN;
    private boolean inSkyBlock;
    private long epoch = -1;
    private int ticks;
    private boolean addKeyDown;
    private WaypointStore.LoadReport pendingNotice;

    private WaypointsFeature() {
        super(cfg -> cfg.waypoints);
    }

    @Override
    protected void onInitialize() {
        WaypointStore.LoadReport report = store.load();
        if (!report.clean()) pendingNotice = report;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WaypointRenderer.initialize();
    }

    @Override
    public boolean isEnabled() {
        // Developer only for now; a copied config does nothing for anyone else.
        return initialized() && config().enabled() && KungDeveloperAccess.allowed();
    }

    /** The island the tab list names, or {@link WaypointIsland#UNKNOWN} before it has. */
    public WaypointIsland island() { return island; }

    /** The waypoints to draw now: the enabled ones of this island's groups, and only the near three of a route. */
    public List<Shown> shown() {
        List<Shown> shown = new ArrayList<>();
        if (!isEnabled()) return shown;
        for (WaypointGroup group : store.visibleOn(island.id(), inSkyBlock)) {
            List<Waypoint> waypoints = group.waypoints();
            for (int index = 0; index < waypoints.size(); index++) {
                Waypoint waypoint = waypoints.get(index);
                WaypointRoute.Role role = group.ordered()
                    ? WaypointRoute.role(index, group.currentIndex(), waypoints.size()) : WaypointRoute.Role.NONE;
                if (waypoint.enabled() && (!group.ordered() || role.shown())) {
                    shown.add(new Shown(group, waypoint, index, role));
                }
            }
        }
        return shown;
    }

    /** The first ordered group of this island that has waypoints and all of them on: the one the route commands move. */
    public WaypointGroup activeRoute() {
        for (WaypointGroup group : store.groupsOn(island.id())) {
            if (group.ordered() && group.fullyEnabled()) return group;
        }
        return null;
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        HypixelInstanceTracker instance = HypixelInstanceTracker.INSTANCE;
        if (instance.instanceEpoch() != epoch) {
            epoch = instance.instanceEpoch();
            store.resetRoutes();
            ticks = 0;
        }
        if (ticks++ % ISLAND_CHECK_TICKS == 0) {
            inSkyBlock = instance.tracking() && instance.location().kind() != HypixelLocation.Kind.UNKNOWN;
            island = instance.tracking() ? WaypointIsland.current(instance.visibleLines()) : WaypointIsland.UNKNOWN;
        }
        if (pendingNotice != null) {
            tellLoadProblem(client, pendingNotice);
            pendingNotice = null;
        }
        if (!isEnabled()) return;
        WaypointsConfig config = config();
        // One press per push, in the world with no screen open, so typing never adds a waypoint.
        boolean held = McCompat.screen(client) == null && LoadoutsAutoCloseFeature.keybindHeld(config.addKeybind());
        if (held && !addKeyDown && inSkyBlock) addAtCrosshair(client);
        addKeyDown = held;
        Vec3 at = client.player.position();
        for (WaypointGroup group : store.visibleOn(island.id(), inSkyBlock)) {
            if (!group.ordered() || group.waypoints().isEmpty()) continue;
            group.setCurrentIndex(WaypointRoute.advance(group.waypoints(), group.currentIndex(), at.x, at.y, at.z,
                config.activationRadius(), config.allowGoingBackwards(), config.allowSkipping()));
        }
    }

    /** The Add Waypoint Key: the looked-at block, else the player's own, goes into every marked group of this island. */
    private void addAtCrosshair(Minecraft client) {
        BlockPos pos = client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            ? hit.getBlockPos() : client.player.blockPosition();
        try {
            client.player.sendSystemMessage(KungMessages.info("Waypoints", editor.addToMarked(pos, island, inSkyBlock)));
        } catch (IllegalArgumentException refused) {
            client.player.sendSystemMessage(KungMessages.warning("Waypoints", refused.getMessage()));
        }
    }

    private static void tellLoadProblem(Minecraft client, WaypointStore.LoadReport report) {
        String what = report.problem().isEmpty()
            ? report.skipped() + " waypoint entries could not be read and were left out."
            : "The waypoint file could not be read: " + report.problem() + ".";
        String kept = report.backup() != null ? " The original is kept as " + report.backup().getFileName() + "."
            : " The file on disk is left untouched.";
        client.player.sendSystemMessage(KungMessages.warning("Waypoints", what + kept));
    }
}
