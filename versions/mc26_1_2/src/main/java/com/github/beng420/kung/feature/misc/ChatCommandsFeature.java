package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.HypixelChatSender;
import com.github.beng420.kung.skyblock.HypixelGuildTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.HypixelPartyTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

public final class ChatCommandsFeature extends ConfigurableFeature<MiscConfig> {
    public static final ChatCommandsFeature INSTANCE = new ChatCommandsFeature();
    private static final ChatCommand[] COMMANDS = ChatCommand.values();

    private static final Pattern PARTY_GUILD_CHAT_PATTERN =
        Pattern.compile("^(?<channel>Party|Guild)\\s*>\\s*(?<sender>.+?)\\s*:\\s*(?<message>.+)$");
    private static final Pattern PRIVATE_CHAT_PATTERN =
        Pattern.compile("^(?<direction>From|To)\\s+(?<name>.+?)\\s*:\\s*(?<message>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLIC_CHAT_PATTERN =
        Pattern.compile("^(?<sender>.+?)\\s*:\\s*(?<message>.+)$");
    private static final Pattern CHAT_TIMESTAMP_PATTERN =
        Pattern.compile("^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}]\\s+");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final long SEND_INTERVAL_MILLIS = 650L;
    private static final long DUPLICATE_COMMAND_WINDOW_MILLIS = 1_200L;
    private static final int GUILD_RESOLVE_WAIT_TICKS = 45;

    private static final Queue<PendingResponse> PENDING_RESPONSES = new ArrayDeque<>();
    private static final Queue<PendingExecution> PENDING_EXECUTIONS = new ArrayDeque<>();
    private static final Map<ChatChannel, Map<String, String>> OBSERVED_CHANNEL_NAMES = new EnumMap<>(ChatChannel.class);
    private static long lastSendMillis;
    private static String lastCommandSignature = "";
    private static long lastCommandMillis;
    private static HypixelChatSender chatSender;

    private ChatCommandsFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        chatSender = services().hypixelChat();
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            observeMessage(Minecraft.getInstance(), message.getString(), overlay ? "game-overlay" : "game"));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(Minecraft.getInstance(), message.getString(), "chat"));
        ClientTickEvents.END_CLIENT_TICK.register(ChatCommandsFeature::tick);
    }

    @Override
    protected void onReset() {
        PENDING_RESPONSES.clear();
        PENDING_EXECUTIONS.clear();
        OBSERVED_CHANNEL_NAMES.clear();
        lastSendMillis = 0L;
        lastCommandSignature = "";
        lastCommandMillis = 0L;
    }

    @Override
    protected void onShutdown() {
        onReset();
        chatSender = null;
    }

    @Override
    public boolean isEnabled() {
        return config().chatCommandsEnabled();
    }

    private static void observeMessage(Minecraft client, String rawMessage, String source) {
        if (client == null || client.player == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        String clean = HypixelLocation.clean(rawMessage);
        ChatContext chatContext = chatContext(client, clean);
        if (chatContext == null) {
            return;
        }

        ChatChannel channel = chatContext.channel();
        String senderName = chatContext.senderName();
        rememberChannelName(channel, senderName);
        String body = chatContext.message().trim();

        for (ChatCommand command : COMMANDS) {
            Matcher matcher = command.pattern.matcher(body);
            if (!matcher.matches()) {
                continue;
            }

            if (!command.isEnabled(KungConfig.get().misc, channel)) {
                KungDebugRecorder.event("chat-command", "ignored source=" + source + " reason=disabled-channel line=" + clean);
                return;
            }

            String signature = commandSignature(channel, senderName, body);
            if (isDuplicateCommand(signature)) {
                KungDebugRecorder.event("chat-command", "ignored source=" + source + " reason=duplicate channel=" + channel.name()
                    + " sender=" + senderName + " body=" + body);
                return;
            }

            String targetName = command.target(matcher);
            if (targetName == null || targetName.isBlank()) {
                targetName = senderName;
            } else {
                if (channel == ChatChannel.GUILD && HypixelGuildTracker.INSTANCE.shouldRefreshMemberList()) {
                    boolean requested = HypixelGuildTracker.INSTANCE.requestMemberListRefresh(client);
                    PENDING_EXECUTIONS.add(new PendingExecution(
                        command,
                        channel,
                        senderName,
                        targetName,
                        GUILD_RESOLVE_WAIT_TICKS,
                        chatContext.replyTarget()
                    ));
                    KungDebugRecorder.event("chat-name", "channel=" + channel.name()
                        + " input=" + targetName
                        + " reason=defer-guild-refresh requested=" + requested);
                    return;
                }
                NameResolution resolution = resolveTargetName(client, channel, targetName, senderName);
                KungDebugRecorder.event("chat-name", "channel=" + channel.name()
                    + " input=" + targetName
                    + " resolved=" + resolution.name()
                    + " reason=" + resolution.reason()
                    + " candidates=" + resolution.candidates());
                targetName = resolution.name();
            }

            if (targetName.isBlank()) {
                queue(channel, chatContext.replyTarget(), "Could not read the target player name.");
                return;
            }
            if (!validUsername(targetName)) {
                queue(channel, chatContext.replyTarget(), "Could not match " + targetName + " to a player.");
                return;
            }

            command.execute(client, channel, targetName, response -> queue(channel, chatContext.replyTarget(), response));
            return;
        }
    }

    private static ChatContext chatContext(Minecraft client, String clean) {
        clean = stripChatTimestamp(clean);
        Matcher partyGuildMatcher = PARTY_GUILD_CHAT_PATTERN.matcher(clean);
        if (partyGuildMatcher.matches()) {
            ChatChannel channel = ChatChannel.from(partyGuildMatcher.group("channel"));
            return new ChatContext(
                channel,
                senderName(partyGuildMatcher.group("sender")),
                partyGuildMatcher.group("message"),
                ""
            );
        }

        Matcher privateMatcher = PRIVATE_CHAT_PATTERN.matcher(clean);
        if (privateMatcher.matches()) {
            String peerName = senderName(privateMatcher.group("name"));
            boolean outgoing = "To".equalsIgnoreCase(privateMatcher.group("direction"));
            String selfName = client != null && client.player != null ? client.player.getName().getString() : "";
            return new ChatContext(
                ChatChannel.PRIVATE,
                outgoing ? selfName : peerName,
                privateMatcher.group("message"),
                peerName
            );
        }

        Matcher publicMatcher = PUBLIC_CHAT_PATTERN.matcher(clean);
        if (publicMatcher.matches()) {
            String senderName = senderName(publicMatcher.group("sender"));
            if (validUsername(senderName)) {
                return new ChatContext(ChatChannel.ALL, senderName, publicMatcher.group("message"), "");
            }
        }
        return null;
    }

    private static NameResolution resolveTargetName(Minecraft client, ChatChannel channel, String input, String senderName) {
        String normalizedInput = normalizeName(input);
        Set<String> candidates = nameCandidates(client, channel, senderName);
        String exact = exactMatch(normalizedInput, candidates);
        if (!exact.isBlank()) {
            return new NameResolution(exact, "exact", candidateText(candidates));
        }

        String best = bestNameMatch(normalizedInput, candidates);
        if (!best.isBlank()) {
            return new NameResolution(best, "closest", candidateText(candidates));
        }

        return new NameResolution(input, "raw", candidateText(candidates));
    }

    private static Set<String> nameCandidates(Minecraft client, ChatChannel channel, String senderName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (client != null && client.player != null) {
            candidates.add(client.player.getName().getString());
        }
        if (validUsername(senderName)) {
            candidates.add(senderName);
        }
        Map<String, String> observed = OBSERVED_CHANNEL_NAMES.get(channel);
        if (observed != null) {
            candidates.addAll(observed.values());
        }
        if (channel == ChatChannel.PARTY) {
            candidates.addAll(HypixelPartyTracker.INSTANCE.knownPartyPlayerNames());
        } else if (channel == ChatChannel.GUILD) {
            candidates.addAll(HypixelGuildTracker.INSTANCE.knownGuildMemberNames());
        }
        return candidates;
    }

    private static String exactMatch(String normalizedInput, Set<String> candidates) {
        for (String candidate : candidates) {
            if (normalizeName(candidate).equals(normalizedInput)) {
                return candidate;
            }
        }
        return "";
    }

    private static String bestNameMatch(String normalizedInput, Set<String> candidates) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeName(candidate);
            int score = matchScore(normalizedInput, normalizedCandidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 40 ? best : "";
    }

    private static int matchScore(String input, String candidate) {
        if (input.isBlank() || candidate.isBlank()) {
            return Integer.MIN_VALUE;
        }
        if (candidate.startsWith(input)) {
            return 1000 - (candidate.length() - input.length());
        }
        if (candidate.contains(input)) {
            return 760 - candidate.indexOf(input) * 8 - (candidate.length() - input.length());
        }
        int distance = levenshtein(input, candidate);
        int maxLength = Math.max(input.length(), candidate.length());
        int closeness = 220 - distance * 35 - Math.max(0, maxLength - input.length()) * 2;
        return distance <= Math.max(1, input.length() / 3) ? closeness : Integer.MIN_VALUE;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String candidateText(Set<String> candidates) {
        return candidates.isEmpty() ? "-" : String.join(",", candidates);
    }

    private static void rememberChannelName(ChatChannel channel, String name) {
        if (!validUsername(name)) {
            return;
        }
        OBSERVED_CHANNEL_NAMES.computeIfAbsent(channel, ignored -> new HashMap<>()).put(normalizeName(name), name);
    }

    private static void queue(ChatChannel channel, String replyTarget, String message) {
        PENDING_RESPONSES.add(new PendingResponse(channel, replyTarget, message));
    }

    private static void tick(Minecraft client) {
        flushPendingExecutions(client);
        flushResponses(client);
    }

    private static void flushPendingExecutions(Minecraft client) {
        if (client == null || client.player == null || PENDING_EXECUTIONS.isEmpty()) {
            return;
        }
        int pendingCount = PENDING_EXECUTIONS.size();
        for (int index = 0; index < pendingCount; index++) {
            PendingExecution pending = PENDING_EXECUTIONS.remove();
            int ticksLeft = pending.ticksLeft() - 1;
            if (ticksLeft > 0) {
                PENDING_EXECUTIONS.add(new PendingExecution(
                    pending.command(),
                    pending.channel(),
                    pending.senderName(),
                    pending.inputName(),
                    ticksLeft,
                    pending.replyTarget()
                ));
                continue;
            }

            NameResolution resolution = resolveTargetName(client, pending.channel(), pending.inputName(), pending.senderName());
            KungDebugRecorder.event("chat-name", "channel=" + pending.channel().name()
                + " input=" + pending.inputName()
                + " resolved=" + resolution.name()
                + " reason=after-guild-refresh/" + resolution.reason()
                + " candidates=" + resolution.candidates());
            if (!validUsername(resolution.name())) {
                queue(pending.channel(), pending.replyTarget(), "Could not match " + pending.inputName() + " to a player.");
                continue;
            }
            pending.command().execute(client, pending.channel(), resolution.name(), response ->
                queue(pending.channel(), pending.replyTarget(), response));
        }
    }

    private static void flushResponses(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null || PENDING_RESPONSES.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendMillis < SEND_INTERVAL_MILLIS) {
            return;
        }
        PendingResponse response = PENDING_RESPONSES.remove();
        String commandPrefix = response.channel().commandPrefix(response.replyTarget());
        if (commandPrefix.isBlank()) {
            return;
        }
        if (chatSender != null) {
            chatSender.send(client, commandPrefix, response.message());
        }
        lastSendMillis = now;
    }

    private static boolean isDuplicateCommand(String signature) {
        long now = System.currentTimeMillis();
        if (signature.equals(lastCommandSignature) && now - lastCommandMillis <= DUPLICATE_COMMAND_WINDOW_MILLIS) {
            return true;
        }
        lastCommandSignature = signature;
        lastCommandMillis = now;
        return false;
    }

    private static String commandSignature(ChatChannel channel, String senderName, String body) {
        return channel.name()
            + "|"
            + (senderName == null ? "" : senderName.toLowerCase(Locale.ROOT))
            + "|"
            + (body == null ? "" : body.toLowerCase(Locale.ROOT));
    }

    private static String senderName(String rawSender) {
        String clean = HypixelLocation.clean(rawSender).replaceAll("\\[[^\\]]+]", " ").replaceAll("\\s+", " ").trim();
        Matcher matcher = USERNAME_PATTERN.matcher(clean);
        return matcher.find() ? matcher.group() : "";
    }

    private static String stripChatTimestamp(String cleanLine) {
        return CHAT_TIMESTAMP_PATTERN.matcher(cleanLine).replaceFirst("");
    }

    private static boolean validUsername(String name) {
        return name != null && USERNAME_PATTERN.matcher(name).matches();
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public enum ChatChannel {
        PARTY,
        GUILD,
        ALL,
        PRIVATE;

        String commandPrefix(String replyTarget) {
            return switch (this) {
                case PARTY -> "pc";
                case GUILD -> "gc";
                case ALL -> "ac";
                case PRIVATE -> validUsername(replyTarget) ? "msg " + replyTarget : "";
            };
        }

        static ChatChannel from(String value) {
            if ("Party".equalsIgnoreCase(value)) {
                return PARTY;
            }
            if ("Guild".equalsIgnoreCase(value)) {
                return GUILD;
            }
            return null;
        }
    }

    private record ChatContext(ChatChannel channel, String senderName, String message, String replyTarget) {
    }

    private record PendingResponse(ChatChannel channel, String replyTarget, String message) {
    }

    private record PendingExecution(
        ChatCommand command,
        ChatChannel channel,
        String senderName,
        String inputName,
        int ticksLeft,
        String replyTarget
    ) {
    }

    private record NameResolution(String name, String reason, String candidates) {
    }
}
