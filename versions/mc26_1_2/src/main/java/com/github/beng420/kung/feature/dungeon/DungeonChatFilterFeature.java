package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.category.DungeonChatFilterConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class DungeonChatFilterFeature extends ConfigurableFeature<DungeonChatFilterConfig> {
    private static final String BOSS_PREFIX = "[BOSS] ";
    private final DungeonStateTracker tracker;

    public DungeonChatFilterFeature(DungeonStateTracker tracker) {
        super(config -> config.chatFilter);
        this.tracker = java.util.Objects.requireNonNull(tracker);
    }

    @Override
    protected void onInitialize() {
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            allowMessage(message));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
            allowMessage(message));
    }

    @Override
    public boolean isEnabled() {
        return config().enabled();
    }

    private boolean allowMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (!shouldHide(client, message)) {
            return true;
        }

        KungDebugRecorder.event("chat-filter", "hidden " + KungDebugRecorder.compact(message.getString()));
        return false;
    }

    private boolean shouldHide(Minecraft client, Component component) {
        if (component == null) {
            return false;
        }

        DungeonChatFilterConfig config = config();
        if (!config.enabled() || !tracker.canFilterDungeonChat(client)) {
            return false;
        }

        String message = DungeonLifecycleSignals.clean(component.getString());
        if (message.isEmpty() || message.startsWith("[Kung")) {
            return false;
        }

        if (config.blessings() && isBlessingMessage(message)) {
            return true;
        }
        if (config.lootSpam() && isLootSpamMessage(message)) {
            return true;
        }
        if (config.watcher() && "the watcher".equals(bossName(message))) {
            return true;
        }
        return config.bossMessages() && bossMessageEnabled(config, bossName(message));
    }

    private static boolean isBlessingMessage(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("dungeon buff!") && lower.contains("blessing of ")
            || lower.contains(" has obtained blessing of ")
            || lower.contains(" found a blessing of ")
            || lower.contains("a blessing of ") && lower.contains(" was picked up")
            || lower.startsWith("granted you +")
            || lower.contains(" granted you +")
            || lower.startsWith("also granted you +")
            || lower.contains(" also granted you +");
    }

    private static boolean isLootSpamMessage(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(" has obtained superboom tnt")
            || lower.contains("you picked up a defense orb")
            || lower.contains(" found a wither essence") && lower.contains("everyone gains an extra essence")
            || lower.contains(" essence! you found ");
    }

    private static String bossName(String message) {
        if (!message.startsWith(BOSS_PREFIX)) {
            return "";
        }
        int end = message.indexOf(':', BOSS_PREFIX.length());
        if (end <= BOSS_PREFIX.length()) {
            return "";
        }
        return message.substring(BOSS_PREFIX.length(), end)
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean bossMessageEnabled(DungeonChatFilterConfig config, String bossName) {
        return switch (bossName) {
            case "bonzo" -> config.bonzo();
            case "scarf" -> config.scarf();
            case "the professor", "professor" -> config.professor();
            case "thorn" -> config.thorn();
            case "livid" -> config.livid();
            case "sadan" -> config.sadan();
            case "maxor" -> config.maxor();
            case "storm" -> config.storm();
            case "goldor" -> config.goldor();
            case "necron" -> config.necron();
            case "the wither king", "wither king" -> config.witherKing();
            default -> false;
        };
    }
}
