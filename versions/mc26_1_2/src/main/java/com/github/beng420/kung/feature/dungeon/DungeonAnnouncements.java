package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.message.HypixelChatSender;
import com.github.beng420.kung.message.KungMessages;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

public final class DungeonAnnouncements {
    private static final long PARTY_COOLDOWN_MILLIS = 300L;

    private final Queue<String> pendingPartyMessages = new ArrayDeque<>();
    private long lastPartyMessageMillis;

    public void reset() {
        pendingPartyMessages.clear();
        lastPartyMessageMillis = 0L;
    }

    public void announceDeath(
        Minecraft client,
        DungeonConfig config,
        DungeonPlayerStats stats,
        int totalDeaths
    ) {
        if (!config.deathMessagesEnabled()) {
            return;
        }
        String individualText = stats.name()
            + " died "
            + stats.deaths()
            + " "
            + (stats.deaths() == 1 ? "time" : "times");
        String eventText = individualText + " | Total Deaths: " + totalDeaths;
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(KungMessages.info("Dungeon", eventText));
        }
        String partyMessage = deathPartyMessage(
            individualText,
            eventText,
            totalDeaths,
            config.deathMessagesShareTotalEnabled(),
            config.deathMessagesShareIndividualEnabled()
        );
        if (!partyMessage.isBlank()) {
            sendPartyAfterCooldown(client, partyMessage);
        }
    }

    static String deathPartyMessage(
        String individualText,
        String eventText,
        int totalDeaths,
        boolean shareTotal,
        boolean shareIndividual
    ) {
        return shareTotal && shareIndividual ? eventText
            : shareTotal ? "Total Deaths: " + totalDeaths
            : shareIndividual ? individualText : "";
    }

    public void sendPartyAfterCooldown(Minecraft client, String message) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingPartyMessages.isEmpty() && now - lastPartyMessageMillis >= PARTY_COOLDOWN_MILLIS) {
            HypixelChatSender.sendParty(client, message);
            lastPartyMessageMillis = now;
            return;
        }
        pendingPartyMessages.add(message);
    }

    public void flush(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        while (!pendingPartyMessages.isEmpty() && now - lastPartyMessageMillis >= PARTY_COOLDOWN_MILLIS) {
            HypixelChatSender.sendParty(client, pendingPartyMessages.remove());
            lastPartyMessageMillis = now;
        }
    }

    public static void showFiveCryptTitle(Minecraft client) {
        if (client == null || client.gui == null || client.player == null) {
            return;
        }
        McCompat.setTitleTimes(client, 0, 30, 5);
        McCompat.setTitle(client, Component.literal("5 Crypts").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.35F, 1.0F));
    }
}
