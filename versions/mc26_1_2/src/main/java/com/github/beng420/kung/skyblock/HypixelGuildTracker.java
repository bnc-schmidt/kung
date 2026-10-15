package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

public final class HypixelGuildTracker {
    public static final HypixelGuildTracker INSTANCE = new HypixelGuildTracker();

    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern GUILD_CHAT_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<sender>.+?)\\s*:\\s*(?<message>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_JOIN_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+joined\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_LEAVE_PATTERN =
        Pattern.compile("^Guild\\s*>\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+left\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_LIST_PAGE_PATTERN =
        Pattern.compile(".*\\bPage\\s+(?<page>\\d+)\\s*(?:/|of)\\s*(?<pages>\\d+)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final long REFRESH_TTL_MILLIS = 5L * 60L * 1000L;
    private static final long REFRESH_COOLDOWN_MILLIS = 10_000L;
    private static final int LIST_CAPTURE_TICKS = 120;
    private static final int NEXT_PAGE_DELAY_TICKS = 12;

    private final Map<String, String> guildPlayerNames = new HashMap<>();
    private boolean initialized;
    private int listCaptureTicks;
    private int pendingNextPage;
    private int pendingNextPageDelayTicks;
    private long lastRefreshRequestMillis;
    private long lastMemberListSeenMillis;
    private String lastLoggedGuildState = "";

    private HypixelGuildTracker() {
    }

    public static void initializeClient() {
        INSTANCE.registerClientHooks();
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

    private void registerClientHooks() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message.getString()));
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
        String normalized = text
            .replaceAll("\\[[^\\]]+]", " ")
            .replaceAll("[\\u25cf\\u25cb]", " ")
            .replaceAll("[^A-Za-z0-9_ ]", " ");
        Matcher matcher = PLAYER_NAME_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String name = matcher.group();
            if (!isIgnoredGuildListToken(name)) {
                registerGuildPlayerName(name, "list");
            }
        }
    }

    private boolean looksLikeGuildListLine(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("guild members")
            || lower.contains("guild name")
            || lower.contains("online members")
            || lower.contains("offline members")
            || lower.contains("guild master")
            || lower.contains("guild officers")
            || lower.contains("guild members:");
    }

    private boolean mayContainGuildMemberNames(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return !lower.startsWith("guild name")
            && !lower.startsWith("online members")
            && !lower.startsWith("offline members");
    }

    private void registerGuildPlayerName(String name, String reason) {
        if (!isPlayerName(name)) {
            return;
        }
        String normalized = normalizeName(name);
        boolean added = guildPlayerNames.put(normalized, name) == null;
        if (added) {
            logGuildState("register " + name + " " + reason);
        }
    }

    private void removeGuildPlayerName(String name) {
        if (!isPlayerName(name)) {
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
        String clean = HypixelLocation.clean(rawSender).replaceAll("\\[[^\\]]+]", " ").replaceAll("\\s+", " ").trim();
        Matcher matcher = PLAYER_NAME_PATTERN.matcher(clean);
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

    private static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches();
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isIgnoredGuildListToken(String name) {
        return name.equalsIgnoreCase("Guild")
            || name.equalsIgnoreCase("Name")
            || name.equalsIgnoreCase("Members")
            || name.equalsIgnoreCase("Member")
            || name.equalsIgnoreCase("Online")
            || name.equalsIgnoreCase("Offline")
            || name.equalsIgnoreCase("Master")
            || name.equalsIgnoreCase("Officer")
            || name.equalsIgnoreCase("Officers")
            || name.equalsIgnoreCase("Rank")
            || name.equalsIgnoreCase("Ranks")
            || name.equalsIgnoreCase("Page")
            || name.equalsIgnoreCase("of")
            || name.equalsIgnoreCase("Status")
            || name.equalsIgnoreCase("Total")
            || name.equalsIgnoreCase("VIP")
            || name.equalsIgnoreCase("MVP")
            || name.equalsIgnoreCase("MVP_")
            || name.equalsIgnoreCase("GM")
            || name.equalsIgnoreCase("Joined")
            || name.equalsIgnoreCase("Left");
    }
}
