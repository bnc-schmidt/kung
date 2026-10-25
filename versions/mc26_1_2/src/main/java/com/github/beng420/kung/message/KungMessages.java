package com.github.beng420.kung.message;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.mixin.ChatComponentAccessor;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class KungMessages {
    private static final Pattern HIGHLIGHTS = Pattern.compile(
        "(?i)\\b(?:red|orange|green|blue|purple)(?: dragon\\b|(?=:))"
            + "|\\b(?:ice spray|sprayed)\\b"
            + "|\\b(?:enabled|disabled|connected|disconnected|true|false|yes|no)\\b"
            // A version ("v0.6.3", "0.6.3") is one value: the number rule skips the 0 after the v and would colour only "6.3".
            + "|\\bv\\d+(?:\\.\\d+)+\\b|\\b\\d+(?:\\.\\d+){2,3}\\b"
            + "|(?<![\\w])[-+]?\\d+(?:[.,:]\\d+)*(?:ms|s|t|%|x)?(?![\\w])"
            + "|\\s\\|\\s");
    public enum Type {
        INFO(ChatFormatting.WHITE),
        SUCCESS(ChatFormatting.GREEN),
        WARNING(ChatFormatting.YELLOW),
        ERROR(ChatFormatting.RED),
        DEBUG(ChatFormatting.GRAY);

        private final ChatFormatting bodyColor;

        Type(ChatFormatting bodyColor) {
            this.bodyColor = bodyColor;
        }
    }

    private KungMessages() {
    }

    public static Component info(String message) {
        return component(Type.INFO, "", message);
    }

    public static Component info(String area, String message) {
        return component(Type.INFO, area, message);
    }

    public static Component success(String area, String message) {
        return component(Type.SUCCESS, area, message);
    }

    public static Component warning(String area, String message) {
        return component(Type.WARNING, area, message);
    }

    public static Component error(String area, String message) {
        return component(Type.ERROR, area, message);
    }

    public static Component debug(String area, String message) {
        return component(Type.DEBUG, area, message);
    }

    public static Component detail(String message) {
        return highlight(message);
    }

    /** Shared semantic colors for local informational text; never modifies the actual text. */
    public static MutableComponent highlight(String message) {
        String text = message == null ? "" : message;
        MutableComponent result = Component.empty().withStyle(ChatFormatting.GRAY);
        var matcher = HIGHLIGHTS.matcher(text);
        int start = 0;
        while (matcher.find()) {
            result.append(Component.literal(text.substring(start, matcher.start())));
            result.append(Component.literal(matcher.group()).withColor(highlightColor(matcher.group())));
            start = matcher.end();
        }
        return result.append(Component.literal(text.substring(start)));
    }

    private static int highlightColor(String token) {
        String word = token.toLowerCase(Locale.ROOT).replace(" dragon", "");
        return switch (word) {
            case "red", "disabled", "disconnected", "false", "no" -> 0xFF5555;
            case "orange" -> 0xFFAA00;
            case "green", "enabled", "connected", "true", "yes" -> 0x55FF55;
            case "blue" -> 0x5599FF;
            case "purple" -> 0xAA55FF;
            case "ice spray", "sprayed" -> 0x99DDFF;
            case " | " -> 0x555555;
            default -> 0xFFCC55;
        };
    }

    public static Component component(Type type, String area, String message) {
        Type resolvedType = type == null ? Type.INFO : type;
        MutableComponent component = Component.literal("[")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("Kung").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (area != null && !area.isBlank()) {
            component.append(Component.literal(" " + area.trim()).withColor(0x5599FF));
        }
        return component
            .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
            .append(resolvedType == Type.INFO ? highlight(message)
                : Component.literal(message == null ? "" : message).withStyle(resolvedType.bodyColor));
    }

    public static void send(Minecraft client, Type type, String area, String message) {
        send(client, component(type, area, message));
    }

    public static void send(Minecraft client, Component message) {
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
        });
    }

    /** Client-thread only. Retain the returned component to replace this local message next time. */
    public static Component replaceLocal(Minecraft client, Component previous, Component message) {
        var chat = McCompat.chat(client);
        var access = (ChatComponentAccessor) chat;
        var lines = access.kung$getTrimmedMessages();
        int scroll = access.kung$getChatScrollbarPos();
        var anchor = scroll > 0 && scroll < lines.size() ? lines.get(scroll) : null;
        removeLocal(access.kung$getAllMessages(), lines, previous);
        chat.addClientSystemMessage(message);
        // Keep a scrolled reader on the same surviving line rather than rebuilding all chat wrapping.
        int anchorIndex = anchor == null ? -1 : lines.indexOf(anchor);
        chat.scrollChat(anchorIndex < 0 ? 0 : anchorIndex - access.kung$getChatScrollbarPos());
        return message;
    }

    static void removeLocal(List<GuiMessage> history, List<GuiMessage.Line> lines, Component previous) {
        if (previous == null) return;
        // Identity preserves unrelated messages with identical text, including server/player copies.
        history.removeIf(entry -> ownsLocal(entry, previous));
        lines.removeIf(line -> ownsLocal(line.parent(), previous));
    }

    private static boolean ownsLocal(GuiMessage entry, Component previous) {
        return entry.source() == GuiMessageSource.SYSTEM_CLIENT && entry.content() == previous;
    }
}
