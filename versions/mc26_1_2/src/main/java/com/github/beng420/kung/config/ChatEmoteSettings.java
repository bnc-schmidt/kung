package com.github.beng420.kung.config;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.ChatEmote;
import java.util.ArrayList;
import java.util.List;

/** One collapsible row per custom emote; rows keep their identity until the list itself changes. */
final class ChatEmoteSettings extends LiveRows<List<ChatEmote>> {
    private final MiscConfig config;

    ChatEmoteSettings(MiscConfig config) { this.config = config; }

    @Override
    protected List<ChatEmote> key() { return List.copyOf(config.customChatEmotes()); }

    @Override
    protected List<SettingEntry> build(List<ChatEmote> entries) {
        var next = new ArrayList<SettingEntry>();
        next.add(SettingEntry.toggle("Replace In Text", config::chatEmotesReplaceWords,
            config::setChatEmotesReplaceWords)
            .withTooltip("Also replaces the word without its colons: \"but i am on an iman account\"",
                "is sent as \"but i am on an ♲ account\". Whole words only, any capitalization.",
                "Command names and a /msg recipient stay as typed."));
        next.add(SettingEntry.button("Add Emote", "+", config::addCustomChatEmote)
            .withTooltip("Adds an emote; open it with the arrow to set its shortcut and text."));
        for (var emote : entries) {
            next.add(SettingEntry.group(() -> emote.shortcut().isBlank() ? "New Emote" : ":" + emote.shortcut() + ": = " + emote.emote())
                .withChildren(List.of(
                    SettingEntry.text("Shortcut", emote::shortcut, value -> config.setChatEmoteShortcut(emote, value))
                        .withTooltip("The word between the colons: gg is typed as :gg:.",
                            "Without colons only when Replace In Text is on."),
                    SettingEntry.text("Emote", emote::emote, value -> config.setChatEmoteText(emote, value))
                        .withTooltip("Pasted as is. Whether Hypixel shows it is up to the character: emojis are not sent."),
                    SettingEntry.button("Remove", "-", () -> config.removeCustomChatEmote(emote))
                )));
        }
        return next;
    }
}
