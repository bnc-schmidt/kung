package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.util.KungDebugRecorder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

public final class HypixelPartyTracker {
    public static final HypixelPartyTracker INSTANCE = new HypixelPartyTracker();

    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern PARTY_LIST_HEADER_PATTERN = Pattern.compile("^Party Members\\s*\\(\\d+\\).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_LIST_LINE_PATTERN = Pattern.compile("^Party (?:Leader|Moderators?|Members?)\\s*:\\s*(?<names>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_JOIN_PATTERN = Pattern.compile("^(?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})\\s+(?:joined|has joined) the party\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_FINDER_JOIN_PATTERN = Pattern.compile("^(?:Party Finder >\\s*)?(?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})\\s+joined the dungeon group!.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_JOINED_OTHER_PATTERN = Pattern.compile("^You have joined (?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})'s party!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_LEAVE_PATTERN = Pattern.compile("^(?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})\\s+(?:has left|left) the party\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_REMOVE_PATTERN = Pattern.compile("^(?:(?:\\[[^\\]]+]\\s*)*(?<name>[A-Za-z0-9_]{3,16})\\s+(?:was removed from your party|has been kicked from the party).*|You kicked (?:\\[[^\\]]+]\\s*)*(?<kicked>[A-Za-z0-9_]{3,16}) from the party!?).*$", Pattern.CASE_INSENSITIVE);

    private final Map<String, String> partyPlayerNames = new HashMap<>();
    private final Map<String, UUID> playerUuids = new HashMap<>();
    private long tickCounter;
    private boolean initialized;
    private String lastLoggedPartyState = "";

    private HypixelPartyTracker() {
    }

    public static void initializeClient() {
        INSTANCE.registerClientHooks();
    }

    public boolean isKnownPartyPlayer(String name) {
        return name != null && partyPlayerNames.containsKey(normalizeName(name));
    }

    public int partyPlayerCount() {
        return partyPlayerNames.size();
    }

    public Set<String> knownPartyPlayerNames() {
        return Set.copyOf(partyPlayerNames.values());
    }

    public Set<UUID> knownPartyPlayerUuids() {
        Set<UUID> uuids = new HashSet<>();
        for (String name : partyPlayerNames.keySet()) {
            uuids.add(partyPlayerUuid(name));
        }
        return Set.copyOf(uuids);
    }

    public UUID partyPlayerUuid(String name) {
        if (name == null || name.isBlank() || !isKnownPartyPlayer(name)) {
            return null;
        }
        String normalized = normalizeName(name);
        return playerUuids.getOrDefault(normalized, syntheticUuid(normalized));
    }

    public UUID observedOnlinePlayerUuid(String name) {
        if (!isPlayerName(name)) {
            return null;
        }
        return playerUuids.get(normalizeName(name));
    }

    public String partyPlayerName(UUID uuid) {
        if (uuid == null) {
            return "";
        }
        for (String name : partyPlayerNames.keySet()) {
            if (partyPlayerUuid(name).equals(uuid)) {
                return name;
            }
        }
        return "";
    }

    public void observeOnlinePlayer(String name, UUID uuid) {
        if (isPlayerName(name) && uuid != null) {
            playerUuids.put(normalizeName(name), uuid);
        }
    }

    private void registerClientHooks() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            observeMessage(Minecraft.getInstance(), message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(Minecraft.getInstance(), message.getString()));
    }

    private void tick(Minecraft client) {
        tickCounter++;
        if (tickCounter % 20L != 0L) {
            return;
        }
        observeOnlinePlayers(client);
    }

    private void observeOnlinePlayers(Minecraft client) {
        if (client == null || client.getConnection() == null) {
            return;
        }
        if (client.player != null) {
            observeOnlinePlayer(client.player.getName().getString(), client.player.getUUID());
        }
        for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            observeOnlinePlayer(info.getProfile().name(), info.getProfile().id());
        }
    }

    private void observeMessage(Minecraft client, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String plainMessage = HypixelLocation.clean(message);
        String lower = plainMessage.toLowerCase(Locale.ROOT);
        if (isPartyResetMessage(lower)) {
            partyPlayerNames.clear();
            logPartyState("reset");
            return;
        }
        if (PARTY_LIST_HEADER_PATTERN.matcher(plainMessage).matches()) {
            partyPlayerNames.clear();
            registerSelf(client);
            logPartyState("party-list-header");
            return;
        }

        Matcher listMatcher = PARTY_LIST_LINE_PATTERN.matcher(plainMessage);
        if (listMatcher.matches()) {
            observePartyListNames(client, listMatcher.group("names"));
            return;
        }

        Matcher joinedOtherMatcher = PARTY_JOINED_OTHER_PATTERN.matcher(plainMessage);
        if (joinedOtherMatcher.matches()) {
            registerPartyPlayerName(joinedOtherMatcher.group("name"), null);
            registerSelf(client);
            return;
        }

        Matcher joinMatcher = PARTY_JOIN_PATTERN.matcher(plainMessage);
        if (joinMatcher.matches()) {
            registerPartyPlayerName(joinMatcher.group("name"), null);
            return;
        }

        Matcher partyFinderJoinMatcher = PARTY_FINDER_JOIN_PATTERN.matcher(plainMessage);
        if (partyFinderJoinMatcher.matches()) {
            registerPartyPlayerName(partyFinderJoinMatcher.group("name"), null);
            return;
        }

        Matcher leaveMatcher = PARTY_LEAVE_PATTERN.matcher(plainMessage);
        if (leaveMatcher.matches()) {
            removePartyPlayerName(leaveMatcher.group("name"));
            return;
        }

        Matcher removeMatcher = PARTY_REMOVE_PATTERN.matcher(plainMessage);
        if (removeMatcher.matches()) {
            String name = removeMatcher.group("name") == null ? removeMatcher.group("kicked") : removeMatcher.group("name");
            removePartyPlayerName(name);
        }
    }

    private void observePartyListNames(Minecraft client, String namesText) {
        registerSelf(client);

        String normalized = HypixelLocation.nameText(stripFormatting(namesText));
        Matcher matcher = PLAYER_NAME_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String name = matcher.group();
            if (!isIgnoredPartyListToken(name)) {
                registerPartyPlayerName(name, null);
            }
        }
    }

    private void registerSelf(Minecraft client) {
        if (client != null && client.player != null) {
            registerPartyPlayerName(client.player.getName().getString(), client.player.getUUID());
        }
    }

    private UUID registerPartyPlayerName(String name, UUID uuid) {
        if (!isPlayerName(name)) {
            return null;
        }

        String normalized = normalizeName(name);
        boolean added = partyPlayerNames.put(normalized, name) == null;
        UUID playerUuid = uuid;
        if (playerUuid == null) {
            playerUuid = playerUuids.get(normalized);
        }
        if (playerUuid == null) {
            playerUuid = syntheticUuid(normalized);
        }
        playerUuids.put(normalized, playerUuid);
        if (added || uuid != null) {
            logPartyState("register " + name);
        }
        return playerUuid;
    }

    private void removePartyPlayerName(String name) {
        if (name != null) {
            String normalized = normalizeName(name);
            partyPlayerNames.remove(normalized);
            logPartyState("remove " + name);
        }
    }

    private void logPartyState(String reason) {
        String state = "reason=" + reason + " players=" + partyPlayerNames.keySet();
        if (!state.equals(lastLoggedPartyState)) {
            lastLoggedPartyState = state;
            KungDebugRecorder.event("party", state);
        }
    }

    private static UUID syntheticUuid(String name) {
        return UUID.nameUUIDFromBytes(("kung:party-player:" + normalizeName(name)).getBytes(StandardCharsets.UTF_8));
    }

    private static String stripFormatting(String text) {
        return HypixelLocation.stripFormatting(text);
    }

    private static boolean isPartyResetMessage(String lowerMessage) {
        return lowerMessage.contains("you are not currently in a party")
            || lowerMessage.equals("you left the party.")
            || lowerMessage.equals("you left the party")
            || lowerMessage.contains("the party was disbanded")
            || lowerMessage.contains("the party has been disbanded")
            || lowerMessage.contains("you have been kicked from the party")
            || lowerMessage.contains("you were kicked from the party");
    }

    private static boolean isIgnoredPartyListToken(String name) {
        return name.equalsIgnoreCase("Party")
            || name.equalsIgnoreCase("Leader")
            || name.equalsIgnoreCase("Moderator")
            || name.equalsIgnoreCase("Moderators")
            || name.equalsIgnoreCase("Member")
            || name.equalsIgnoreCase("Members")
            || name.equalsIgnoreCase("Online")
            || name.equalsIgnoreCase("Offline")
            || name.equalsIgnoreCase("Status");
    }

    private static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches();
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
