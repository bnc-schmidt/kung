package com.github.beng420.kung.config;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.ChatEmote;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** One collapsible row per custom emote; rows keep their identity until the list itself changes. */
final class ChatEmoteSettings implements Supplier<List<SettingEntry>> {
    private final MiscConfig config;
    private List<ChatEmote> entries = List.of();
    private List<SettingEntry> rows = List.of();

    ChatEmoteSettings(MiscConfig config) { this.config = config; }

    @Override
    public List<SettingEntry> get() {
        if (!rows.isEmpty() && entries.equals(config.customChatEmotes())) return rows;
        entries = List.copyOf(config.customChatEmotes());
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
        return rows = List.copyOf(next);
    }
}
