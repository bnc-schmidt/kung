package com.github.beng420.kung.feature.dungeon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reconciles confirmed death events and server counters without creating player identities. */
public final class DungeonDeathTracker {
    public enum MessageSource { SYSTEM, CHAT, ACTIONBAR }

    private static final long DUPLICATE_WINDOW_TICKS = 20L;
    private static final Pattern DEATH = Pattern.compile(
        "^☠\\s+(?:\\[[A-Z0-9+]+]\\s+)?(?<name>You|[A-Za-z0-9_]{3,16})\\s+"
            + "(?:(?:died|were killed|was killed|was slain|was shot|fell|burned|drowned|blew up|"
            + "suffocated|hit the ground|disconnected)\\b[^:\\r\\n]*\\s+and\\s+became a ghost|became a ghost)\\.$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEAM_TOTAL = Pattern.compile("^Team Deaths:\\s*(\\d{1,5})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_TOTAL = Pattern.compile("^Deaths:\\s*(\\d{1,5})$", Pattern.CASE_INSENSITIVE);
    private final Map<UUID, PlayerDeaths> players = new HashMap<>();
    private int reportedTeamTotal;

    /** The resolver must return an already known roster UUID, never register a name from this text. */
    public DeathEvent observeMessage(String raw, MessageSource source, UUID self,
        Function<String, UUID> knownRosterLookup, long nowTick) {
        if (source != MessageSource.SYSTEM) return null;
        Matcher matcher = DEATH.matcher(DungeonLifecycleSignals.clean(raw));
        if (!matcher.matches()) return null;
        String name = matcher.group("name");
        UUID uuid = name.equalsIgnoreCase("You") ? self : knownRosterLookup.apply(name);
        if (uuid == null) return null;
        PlayerDeaths player = players.computeIfAbsent(uuid, ignored -> new PlayerDeaths());
        // The self and named system lines can describe the same death across adjacent ticks.
        if (nowTick != Long.MIN_VALUE && player.lastEventTick != Long.MIN_VALUE
            && nowTick >= player.lastEventTick && nowTick - player.lastEventTick <= DUPLICATE_WINDOW_TICKS) return null;
        player.lastEventTick = nowTick;
        player.messages++;
        return new DeathEvent(uuid, player.deaths(), totalDeaths());
    }

    /** Only feed a server scoreboard/tab field, never a death count quoted in player chat. */
    public void observeTeamTotal(int observed) {
        if (observed >= 0) reportedTeamTotal = Math.max(reportedTeamTotal, observed);
    }

    /** UUID must already belong to a real roster member. */
    public void observePlayerTotal(UUID playerId, int observed) {
        if (playerId == null || observed < 0) return;
        PlayerDeaths player = players.computeIfAbsent(playerId, ignored -> new PlayerDeaths());
        player.reported = Math.max(player.reported, observed);
    }

    public int playerDeaths(UUID playerId) {
        PlayerDeaths player = players.get(playerId);
        return player == null ? 0 : player.deaths();
    }

    public int totalDeaths() {
        return Math.max(reportedTeamTotal, attributedDeaths());
    }

    public int unattributedDeaths() {
        return Math.max(0, reportedTeamTotal - attributedDeaths());
    }

    public void reset() {
        players.clear();
        reportedTeamTotal = 0;
    }

    /** A resolved UUID replaces an earlier identity for the same player, not another teammate. */
    public void remapPlayer(UUID oldUuid, UUID newUuid) {
        if (oldUuid == null || newUuid == null || oldUuid.equals(newUuid)) return;
        PlayerDeaths previous = players.remove(oldUuid);
        if (previous == null) return;
        PlayerDeaths resolved = players.computeIfAbsent(newUuid, ignored -> new PlayerDeaths());
        resolved.messages = Math.max(resolved.messages, previous.messages);
        resolved.reported = Math.max(resolved.reported, previous.reported);
        resolved.lastEventTick = Math.max(resolved.lastEventTick, previous.lastEventTick);
    }

    public static int teamTotal(String line) {
        Matcher matcher = TEAM_TOTAL.matcher(DungeonLifecycleSignals.clean(line));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    public static int playerTotal(String line) {
        Matcher matcher = PLAYER_TOTAL.matcher(DungeonLifecycleSignals.clean(line));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private int attributedDeaths() {
        return players.values().stream().mapToInt(PlayerDeaths::deaths).sum();
    }

    private static final class PlayerDeaths {
        private int messages;
        private int reported;
        private long lastEventTick = Long.MIN_VALUE;
        private int deaths() { return Math.max(messages, reported); }
    }

    public record DeathEvent(UUID playerId, int playerDeaths, int totalDeaths) {}
}
