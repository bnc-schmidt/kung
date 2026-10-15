package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.DungeonEventRouter;
import com.github.beng420.kung.feature.misc.NpcDialogueTrace;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.Scoreboard;

/** Receives applied packets on the client thread. All consumers share this session state. */
public final class HypixelInstanceTracker {
    public static final HypixelInstanceTracker INSTANCE = new HypixelInstanceTracker();
    private final HypixelInstanceState state = new HypixelInstanceState();
    private final HypixelDungeonFloorState dungeonFloor = new HypixelDungeonFloorState();
    // Package-private with the constructor, so tests can run commitServerChange without a client.
    final SkyBlockSidebar.Pending pendingSidebar = new SkyBlockSidebar.Pending();
    private final Map<UUID, String> tabRows = new HashMap<>();
    private List<String> tabHeaderFooter = List.of();
    private List<String> sidebarLines = List.of();
    private List<String> visibleLines = List.of();
    private Object observedLevel;
    private boolean positionKnown;
    private String lastLoggedState = "";
    private String lastAnnouncedContext = "";

    HypixelInstanceTracker() {}

    public static void initializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.disconnect(client));
        ClientReceiveMessageEvents.GAME.register(HypixelInstanceTracker::recordMessage);
        registerFloorEntryObserver(message -> INSTANCE.dungeonFloor.entryMessage(message, System.currentTimeMillis()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, received) -> recordMessage(message, false));
    }

    static void registerFloorEntryObserver(Consumer<String> observer) {
        // Entry metadata must survive chat filtering and display-only message formatting.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) observer.accept(message.getString());
            return true;
        });
    }

    public boolean tracking() { return observedLevel != null; }
    public boolean dungeonHub() { return state.location().kind() == HypixelLocation.Kind.DUNGEON_HUB; }
    public boolean catacombs() { return tracking() && state.catacombs(); }
    public boolean dungeonRunContext() { return catacombs(); }
    public HypixelLocation location() { return state.location(); }
    public String instanceLine() { return state.location().name(); }
    public String serverId() { return state.serverId(); }
    public long instanceEpoch() { return state.epoch(); }
    public HypixelDungeonFloor dungeonFloor() { return dungeonFloor.current(); }
    public boolean positionKnown() { return positionKnown; }
    public List<String> sidebarLines() { return sidebarLines; }
    public List<String> visibleLines() { return visibleLines; }

    public void observeWorldChangePacket() {
        Minecraft client = Minecraft.getInstance();
        observedLevel = client.level;
        beginWorld(client);
    }

    public void observeSidebarScore(String owner) {
        pendingSidebar.score(owner);
    }

    public void observeSidebarTeam(ClientboundSetPlayerTeamPacket packet) {
        pendingSidebar.team(packet.getName(), packet.getPlayers(), packet.getParameters().isPresent());
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && packet.getParameters().isPresent()) {
            var team = client.level.getScoreboard().getPlayerTeam(packet.getName());
            if (team != null) pendingSidebar.team(packet.getName(), team.getPlayers(), true);
        }
        observeSidebar();
    }

    public void observeSidebar() {
        pendingSidebar.changed();
    }

    public void observeTabList(Component header, Component footer) {
        ArrayList<String> lines = new ArrayList<>();
        addLines(lines, header);
        addLines(lines, footer);
        tabHeaderFooter = List.copyOf(lines);
        resolve(Minecraft.getInstance(), false);
    }

    public void observePlayerInfo(ClientboundPlayerInfoUpdatePacket packet) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) return;
        for (var entry : packet.entries()) {
            if (entry.displayName() == null) tabRows.remove(entry.profileId());
            else tabRows.put(entry.profileId(), entry.displayName().getString());
        }
        resolve(Minecraft.getInstance(), false);
    }

    public void observePlayerInfoRemoved(List<UUID> ids) {
        ids.forEach(tabRows::remove);
        resolve(Minecraft.getInstance(), false);
    }

    public void observePlayerPosition() {
        positionKnown = true;
    }

    private void tick(Minecraft client) {
        if (client.level == null) {
            if (observedLevel != null) disconnect(client);
            return;
        }
        // Fallback for client-side world replacement. Never reread UI left over from the old world.
        if (observedLevel != client.level) {
            observedLevel = client.level;
            beginWorld(client);
        }
        // Hypixel rewrites a row using several packets (e.g. m61e -> m61 -> m61e).
        // Read once after the client's packet batch; world/connection invalidation stays immediate.
        List<String> sidebar = pendingSidebar.poll(client.level.getScoreboard());
        if (sidebar != null) {
            sidebarLines = sidebar;
            resolve(client, true);
        }
        String server = state.confirmedServerChange(sidebarLines);
        if (!server.isEmpty()) {
            // Keeps the raw rows of the committed transfer for the trace.
            KungDebugRecorder.event("context-state", "server-change " + state.serverId() + " -> " + server
                + " sidebar=" + sidebarLines);
            var connection = client.getConnection();
            commitServerChange(server, client.level.getScoreboard(),
                connection == null ? List.of() : connection.getOnlinePlayers());
            publish(client, "server-change");
        }
        announce(client);
    }

    /**
     * A different server id stayed on the sidebar: a new instance session. No world packet follows,
     * so a static row such as "The Catacombs (M7)" is never re-sent; dropping the evidence would leave
     * the location and floor unknown for the rest of the run. The rows still live on the scoreboard
     * and tab list after the hold are the truth for the new id, so they are read again here instead.
     * Package-private so tests can run it without a client.
     */
    void commitServerChange(String server, Scoreboard board, Collection<PlayerInfo> players) {
        state.observe(HypixelInstanceState.Source.SIDEBAR, HypixelLocation.UNKNOWN, server);
        dungeonFloor.worldChanged(System.currentTimeMillis());
        // Same owners as tick() polls: rows the server never touched in this world stay ineligible.
        pendingSidebar.changed();
        sidebarLines = pendingSidebar.poll(board);
        tabRows.clear();
        for (PlayerInfo info : players) {
            Component name = info.getTabListDisplayName();
            if (name != null) tabRows.put(info.getProfile().id(), name.getString());
        }
        ArrayList<String> lines = evidenceLines();
        visibleLines = List.copyOf(lines);
        state.observe(HypixelInstanceState.Source.SIDEBAR, HypixelLocation.parse(sidebarLines), server);
        state.observe(HypixelInstanceState.Source.TAB,
            HypixelLocation.parse(lines.subList(sidebarLines.size(), lines.size())), server);
        dungeonFloor.observe(state.location(), visibleLines, System.currentTimeMillis());
    }

    private void disconnect(Minecraft client) {
        observedLevel = null;
        dungeonFloor.reset();
        beginWorld(client);
        lastAnnouncedContext = "";
    }

    private void beginWorld(Minecraft client) {
        state.beginWorld();
        dungeonFloor.worldChanged(System.currentTimeMillis());
        positionKnown = false;
        clearEvidence();
        publish(client, "world-change");
    }

    private void clearEvidence() {
        pendingSidebar.reset();
        tabRows.clear();
        tabHeaderFooter = List.of();
        sidebarLines = List.of();
        visibleLines = List.of();
    }

    private ArrayList<String> evidenceLines() {
        ArrayList<String> lines = new ArrayList<>(sidebarLines);
        lines.addAll(tabHeaderFooter);
        lines.addAll(tabRows.values());
        return lines;
    }

    private void resolve(Minecraft client, boolean sidebarPacket) {
        ArrayList<String> lines = evidenceLines();
        if (visibleLines.equals(lines)) return;
        visibleLines = List.copyOf(lines);
        HypixelLocation location = HypixelLocation.parse(sidebarPacket ? sidebarLines
            : lines.subList(sidebarLines.size(), lines.size()));
        String server = HypixelLocation.serverId(sidebarLines);
        // A world packet is the real transfer. A different id without one may be a one-tick
        // rewrite of the row, so it only counts once tick() saw it stay (commitServerChange).
        // The id is unchanged past this point, so observe() never starts a new session here.
        if (state.holdServerChange(server)) return;
        state.observe(sidebarPacket ? HypixelInstanceState.Source.SIDEBAR : HypixelInstanceState.Source.TAB,
            location, server);
        dungeonFloor.observe(state.location(), visibleLines, System.currentTimeMillis());
        publish(client, "context-packet");
    }

    private void publish(Minecraft client, String source) {
        String key = "epoch=" + state.epoch() + " location=" + state.location().kind()
            + " instance=" + instanceLine() + " server=" + serverId()
            + " floor=" + dungeonFloor().floor() + " master=" + dungeonFloor().masterMode();
        if (!key.equals(lastLoggedState)) {
            lastLoggedState = key;
            KungDebugRecorder.event("context-state", source + " " + key + " lines=" + visibleLines);
            DungeonEventRouter.observeInstanceChanged();
        }
        announce(client);
    }

    private void announce(Minecraft client) {
        if (client.player == null || instanceLine().isBlank() || serverId().isBlank()) return;
        String context = instanceLine() + " (" + serverId() + ")";
        if (context.equals(lastAnnouncedContext)) return;
        lastAnnouncedContext = context;
        KungDebugRecorder.event("context", "announce Entered " + context);
        if (KungConfig.get().debug.contextMessagesEnabled()) {
            client.player.sendSystemMessage(KungMessages.debug("Context", "Entered " + context));
        }
    }

    private static void addLines(List<String> lines, Component component) {
        if (component != null) lines.addAll(List.of(component.getString().split("\\R")));
    }

    private static void recordMessage(Component message, boolean overlay) {
        String text = message.getString();
        if (HypixelLocation.clean(text).startsWith("[Kung")) return;
        // The action bar repeats every second; its own area keeps it from spending the chat budget.
        KungDebugRecorder.event(overlay ? "actionbar" : "message", text);
        if (!overlay) NpcDialogueTrace.message(message);
    }
}
