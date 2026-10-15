package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.List;

/** One world session owns its evidence. Missing UI rows do not create a new session. */
public final class HypixelInstanceState {
    public enum Source { SIDEBAR, TAB }
    /** Client ticks (0.5 s) a changed server id must stay on the sidebar before it is a transfer. */
    public static final int SERVER_CHANGE_CONFIRM_TICKS = 10;
    private long epoch;
    private HypixelLocation location = HypixelLocation.UNKNOWN;
    private String serverId = "";
    private HypixelLocation sidebarLocation = HypixelLocation.UNKNOWN;
    private HypixelLocation tabLocation = HypixelLocation.UNKNOWN;
    private String pendingServer = "";
    private int pendingTicks;

    public void beginWorld() {
        pendingServer = "";
        epoch++;
        location = HypixelLocation.UNKNOWN;
        serverId = "";
        sidebarLocation = HypixelLocation.UNKNOWN;
        tabLocation = HypixelLocation.UNKNOWN;
    }

    public void observe(HypixelLocation next, String server) {
        observe(Source.SIDEBAR, next, server);
    }

    /** Returns true when a changed server row invalidated the other cached rows. */
    public boolean observe(Source source, HypixelLocation next, String server) {
        if (!server.isBlank() && !serverId.isBlank() && !serverId.equals(server)) {
            beginWorld();
            serverId = server;
            return true;
        }
        if (!server.isBlank()) serverId = server;
        HypixelLocation previous = source == Source.SIDEBAR ? sidebarLocation : tabLocation;
        if (source == Source.SIDEBAR) sidebarLocation = next;
        else tabLocation = next;
        if (!next.equals(previous) && next.kind() != HypixelLocation.Kind.UNKNOWN) location = next;
        return false;
    }

    /**
     * True while a different server id has to wait before {@link #observe} may see it: Hypixel
     * rewrites the server row packet by packet (m96cq -> m96h -> m96cq) without leaving the
     * instance. Any other id, including none, drops the wait.
     */
    public boolean holdServerChange(String server) {
        if (server.isBlank() || serverId.isBlank() || serverId.equals(server)) {
            pendingServer = "";
            return false;
        }
        if (!server.equals(pendingServer)) {
            KungDebugRecorder.event("context-state", "server-hold " + serverId + " -> " + server);
            pendingServer = server;
            pendingTicks = 0;
        }
        return true;
    }

    /** One client tick: returns the held server id once the live sidebar kept showing it, else "". */
    public String confirmedServerChange(List<String> sidebarLines) {
        if (pendingServer.isEmpty()) return "";
        if (!pendingServer.equals(HypixelLocation.serverId(sidebarLines))) {
            pendingServer = "";
        } else if (++pendingTicks >= SERVER_CHANGE_CONFIRM_TICKS) {
            String confirmed = pendingServer;
            pendingServer = "";
            return confirmed;
        }
        return "";
    }

    public long epoch() { return epoch; }
    public HypixelLocation location() { return location; }
    public String serverId() { return serverId; }
    public boolean catacombs() { return location.kind() == HypixelLocation.Kind.CATACOMBS; }
}
