package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.KungSettings.FeatureEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ChatCommandSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultsAndResetComeFirstAndEachGroupHasItsOwnSwitch() {
        FeatureEntry chat = chat(new KungConfig(file()));
        assertEquals(List.of("Default Channels", "Reset All", "!c50", "!ca50", "Class Level",
            "!tps", "Special Mayors", "Yearly Events"), labels(chat.settings()));
        assertEquals(List.of("Party", "Guild", "All Chat", "DMs"), labels(setting(chat, "Default Channels").children()));
        assertEquals(SettingKind.BUTTON, setting(chat, "Reset All").kind());
        for (String group : List.of("Special Mayors", "Yearly Events")) {
            assertEquals(SettingKind.TOGGLE, setting(chat, group).kind());
            assertEquals(3, setting(chat, group).children().size());
        }
        assertEquals(List.of("!jerry", "!scorp", "!derpy"), labels(setting(chat, "Special Mayors").children()));
        assertEquals(List.of("!yotp", "!yotw", "!yots"), labels(setting(chat, "Yearly Events").children()));
    }

    @Test
    public void aCommandShowsOneSwitchWhileOnTheDefaultsAndItsOwnChannelsOnceOffThem() {
        KungConfig config = new KungConfig(file());
        SettingEntry command = setting(chat(config), "!c50");
        assertNotNull(command.reset());
        assertFalse("no reset button while it follows the defaults", command.resettable());
        assertEquals(List.of("Use Default"), visible(command));
        assertTrue(command.children().getFirst().booleanSupplier().getAsBoolean());

        config.misc.setDefaultPartyCommandsEnabled(false);
        config.misc.setDefaultPrivateCommandsEnabled(true);
        command.children().getFirst().toggle().run();

        // The own channels start as a copy of the defaults, and the switch makes room for them.
        assertFalse(config.misc.c50UseDefaultChannels());
        assertTrue(command.resettable());
        assertEquals(List.of("Party", "Guild", "All Chat", "DMs"), visible(command));
        assertEquals(List.of(false, true, false, true), channels(command));

        // Editing an own channel leaves the defaults alone.
        command.children().get(1).toggle().run();
        assertEquals(List.of(true, true, false, true), channels(command));
        assertFalse(config.misc.defaultPartyCommandsEnabled());

        // The reset button hands the command back to the defaults; the other commands never moved.
        command.reset().action().run();
        assertTrue(config.misc.c50UseDefaultChannels());
        assertFalse(command.resettable());
        assertEquals(List.of("Use Default"), visible(command));
        assertFalse(config.misc.c50PartyCommandsEnabled());
        assertFalse(setting(chat(config), "!tps").resettable());
    }

    @Test
    public void everyCommandOfEveryGroupCanLeaveAndReturnToTheDefaults() {
        KungConfig config = new KungConfig(file());
        FeatureEntry chat = chat(config);
        List<SettingEntry> commands = new java.util.ArrayList<>();
        for (SettingEntry entry : chat.settings()) {
            if (entry.reset() != null) commands.add(entry);
            if (entry.kind() == SettingKind.TOGGLE) entry.children().stream().filter(child -> child.reset() != null).forEach(commands::add);
        }
        assertEquals(10, commands.size());
        for (SettingEntry command : commands) {
            assertFalse(command.label(), command.resettable());
            command.children().getFirst().toggle().run();
            assertTrue(command.label(), command.resettable());
            command.reset().action().run();
            assertFalse(command.label(), command.resettable());
        }
    }

    @Test
    public void resetAllRowOnlyTurnsUseDefaultBackOnForEveryCommand() {
        KungConfig config = new KungConfig(file());
        FeatureEntry chat = chat(config);
        config.misc.setDefaultGuildCommandsEnabled(false);
        config.misc.setSpecialMayorsChatCommandsEnabled(false);
        config.misc.setYotwUseDefaultChannels(false);
        config.misc.setCa50UseDefaultChannels(false);
        config.misc.setCa50ChatCommandEnabled(false);
        config.misc.setChatCommandsEnabled(true);

        setting(chat, "Reset All").toggle().run();

        assertTrue(config.misc.yotwUseDefaultChannels());
        assertTrue(config.misc.ca50UseDefaultChannels());
        assertFalse("the default channels stay", config.misc.defaultGuildCommandsEnabled());
        assertFalse("the group switch stays", config.misc.specialMayorsChatCommandsEnabled());
        assertFalse("the command switch stays", config.misc.ca50ChatCommandEnabled());
        assertTrue(config.misc.chatCommandsEnabled());
    }

    @Test
    public void anOldFileKeepsTheChannelsItChangedAndEverythingElseFollowsTheDefaults() throws Exception {
        Path file = file();
        Files.writeString(file, "{\"misc\":{\"c50AllChatCommandsEnabled\":true,\"tpsPartyCommandsEnabled\":false,"
            + "\"jerryAllChatCommandsEnabled\":true,\"jerryUseDefaultChannels\":true}}");
        KungConfig config = new KungConfig(file);
        config.load();

        assertFalse("changed all chat", config.misc.c50UseDefaultChannels());
        assertTrue(config.misc.c50AllChatCommandsEnabled());
        assertFalse("changed party", config.misc.tpsUseDefaultChannels());
        assertFalse(config.misc.tpsPartyCommandsEnabled());
        assertTrue("untouched", config.misc.ca50UseDefaultChannels());
        assertFalse(config.misc.ca50AllChatCommandsEnabled());
        // A file that already knows the setting is taken at its word.
        assertTrue(config.misc.jerryUseDefaultChannels());
        assertFalse(config.misc.jerryAllChatCommandsEnabled());
    }

    private Path file() {
        return temporary.getRoot().toPath().resolve("kung.json");
    }

    private static FeatureEntry chat(KungConfig config) {
        return KungSettings.categories(config, () -> { }).stream().flatMap(category -> category.features().stream())
            .filter(feature -> feature.name().equals("Chat Commands")).findFirst().orElseThrow();
    }

    private static SettingEntry setting(FeatureEntry feature, String label) {
        return feature.settings().stream().filter(setting -> setting.label().equals(label)).findFirst().orElseThrow();
    }

    private static List<String> labels(List<SettingEntry> settings) {
        return settings.stream().map(SettingEntry::label).toList();
    }

    private static List<String> visible(SettingEntry command) {
        return command.children().stream().filter(SettingEntry::visible).map(SettingEntry::label).toList();
    }

    /** Party, Guild, All Chat and DMs of an overridden command, in that order. */
    private static List<Boolean> channels(SettingEntry command) {
        return command.children().subList(1, 5).stream().map(setting -> setting.booleanSupplier().getAsBoolean()).toList();
    }
}
