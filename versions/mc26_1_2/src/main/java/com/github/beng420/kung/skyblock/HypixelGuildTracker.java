package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class HypixelGuildTracker {
    public static final HypixelGuildTracker INSTANCE = new HypixelGuildTracker();

    private static final Pattern GUILD_CHAT_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<sender>.+?)\\s*:\\s*(?<message>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_JOIN_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+joined\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_LEAVE_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+left\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_LIST_PAGE_PATTERN =
        Pattern.compile(".*\\bPage\\s+(?<page>\\d+)\\s*(?:/|of)\\s*(?<pages>\\d+)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final List<String> LIST_LINE_MARKERS = List.of("guild members", "guild name", "online members",
        "offline members", "guild master", "guild officers");
    private static final List<String> NAMELESS_LIST_LINES = List.of("guild name", "online members", "offline members");
    private static final Set<String> IGNORED_LIST_TOKENS = Set.of("guild", "name", "members", "member", "online",
        "offline", "master", "officer", "officers", "rank", "ranks", "page", "of", "status", "total", "vip", "mvp",
        "mvp_", "gm", "joined", "left");
    private static final long REFRESH_TTL_MILLIS = 5L * 60L * 1000L;
    private static final long REFRESH_COOLDOWN_MILLIS = 10_000L;
    private static final int LIST_CAPTURE_TICKS = 120;
    private static final int NEXT_PAGE_DELAY_TICKS = 12;

    private final Map<String, String> guildPlayerNames = new HashMap<>();
    private int listCaptureTicks;
    private int pendingNextPage;
    private int pendingNextPageDelayTicks;
    private long lastRefreshRequestMillis;
    private long lastMemberListSeenMillis;
    private String lastLoggedGuildState = "";

    private HypixelGuildTracker() {
    }

    public static void initializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        KungMessages.onText((message, overlay) -> INSTANCE.observeMessage(message.getString()));
    }

    public synchronized Set<String> knownGuildMemberNames() {
        return Set.copyOf(guildPlayerNames.values());
    }

    public synchronized boolean shouldRefreshMemberList() {
        return guildPlayerNames.isEmpty() || System.currentTimeMillis() - lastMemberListSeenMillis > REFRESH_TTL_MILLIS;
    }

    public synchronized boolean requestMemberListRefresh(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshRequestMillis < REFRESH_COOLDOWN_MILLIS) {
            return false;
        }
        lastRefreshRequestMillis = now;
        listCaptureTicks = LIST_CAPTURE_TICKS;
        pendingNextPage = 0;
        pendingNextPageDelayTicks = 0;
        client.player.connection.sendCommand("g list");
        KungDebugRecorder.event("guild", "requested member list refresh");
        return true;
    }

    private synchronized void tick(Minecraft client) {
        if (listCaptureTicks > 0) {
            listCaptureTicks--;
        }
        if (pendingNextPage <= 0) {
            return;
        }
        if (pendingNextPageDelayTicks > 0) {
            pendingNextPageDelayTicks--;
            return;
        }
        if (client != null && client.player != null && client.player.connection != null) {
            int page = pendingNextPage;
            pendingNextPage = 0;
            listCaptureTicks = LIST_CAPTURE_TICKS;
            client.player.connection.sendCommand("g list " + page);
            KungDebugRecorder.event("guild", "requested member list page=" + page);
        }
    }

    private synchronized void observeMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        String message = HypixelLocation.clean(rawMessage);
        if (message.isBlank()) {
            return;
        }

        Matcher chatMatcher = GUILD_CHAT_PATTERN.matcher(message);
        if (chatMatcher.matches()) {
            registerGuildPlayerName(senderName(chatMatcher.group("sender")), "chat");
            return;
        }

        Matcher joinMatcher = GUILD_JOIN_PATTERN.matcher(message);
        if (joinMatcher.matches()) {
            registerGuildPlayerName(joinMatcher.group("name"), "join");
            return;
        }

        Matcher leaveMatcher = GUILD_LEAVE_PATTERN.matcher(message);
        if (leaveMatcher.matches()) {
            removeGuildPlayerName(leaveMatcher.group("name"));
            return;
        }

        if (looksLikeGuildListLine(message)) {
            listCaptureTicks = LIST_CAPTURE_TICKS;
            lastMemberListSeenMillis = System.currentTimeMillis();
            if (mayContainGuildMemberNames(message)) {
                observeGuildListNames(message);
            }
            observeGuildListPage(message);
        } else if (listCaptureTicks > 0) {
            observeGuildListNames(message);
            observeGuildListPage(message);
        }
    }

    private void observeGuildListPage(String message) {
        Matcher matcher = GUILD_LIST_PAGE_PATTERN.matcher(message);
        if (!matcher.matches()) {
            return;
        }
        int page = parsePositiveInt(matcher.group("page"));
        int pages = parsePositiveInt(matcher.group("pages"));
        if (page > 0 && pages > page && pendingNextPage == 0) {
            pendingNextPage = page + 1;
            pendingNextPageDelayTicks = NEXT_PAGE_DELAY_TICKS;
        }
    }

    private void observeGuildListNames(String text) {
        String normalized = HypixelLocation.nameText(text);
        Matcher matcher = HypixelLocation.PLAYER_NAME.matcher(normalized);
        while (matcher.find()) {
            String name = matcher.group();
            if (!IGNORED_LIST_TOKENS.contains(name.toLowerCase(Locale.ROOT))) {
                registerGuildPlayerName(name, "list");
            }
        }
    }

    private boolean looksLikeGuildListLine(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return LIST_LINE_MARKERS.stream().anyMatch(lower::contains);
    }

    private boolean mayContainGuildMemberNames(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return NAMELESS_LIST_LINES.stream().noneMatch(lower::startsWith);
    }

    private void registerGuildPlayerName(String name, String reason) {
        if (!HypixelLocation.isPlayerName(name)) {
            return;
        }
        String normalized = normalizeName(name);
        boolean added = guildPlayerNames.put(normalized, name) == null;
        if (added) {
            logGuildState("register " + name + " " + reason);
        }
    }

    private void removeGuildPlayerName(String name) {
        if (!HypixelLocation.isPlayerName(name)) {
            return;
        }
        String normalized = normalizeName(name);
        guildPlayerNames.remove(normalized);
        logGuildState("remove " + name);
    }

    private void logGuildState(String reason) {
        String state = "reason=" + reason + " count=" + guildPlayerNames.size() + " players=" + guildPlayerNames.keySet();
        if (!state.equals(lastLoggedGuildState)) {
            lastLoggedGuildState = state;
            KungDebugRecorder.event("guild", state);
        }
    }

    private static String senderName(String rawSender) {
        String clean = HypixelLocation.withoutBrackets(HypixelLocation.clean(rawSender));
        Matcher matcher = HypixelLocation.PLAYER_NAME.matcher(clean);
        String last = "";
        while (matcher.find()) {
            last = matcher.group();
        }
        return last;
    }

    private static int parsePositiveInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
