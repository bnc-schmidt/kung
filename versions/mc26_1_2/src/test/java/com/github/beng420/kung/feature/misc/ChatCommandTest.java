package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.ServerTpsTracker;
import com.github.beng420.kung.config.category.MiscConfig.ChatCommandSetting;
import com.github.beng420.kung.config.category.MiscConfig.CommandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatCommandTest {
    @Test
    public void patternsKeepOptionalTargetsAndRejectExtraArguments() {
        assertTarget(ChatCommand.C, "!c50", null);
        assertTarget(ChatCommand.C, "!C50  Player_123\t", "Player_123");
        assertTarget(ChatCommand.CA, "!ca50 ", null);
        assertTarget(ChatCommand.CA, "!CA50 A", "A");
        assertTarget(ChatCommand.TPS, "!TpS\t", null);
        for (ChatCommand command : ChatCommand.values()) {
            String name = "!" + command.name().toLowerCase(java.util.Locale.ROOT);
            assertFalse(command.pattern.matcher(name + "extra").matches());
            assertFalse(command.pattern.matcher(name + " Player Second").matches());
            assertFalse(command.pattern.matcher("hello " + name).matches());
        }
        assertFalse(ChatCommand.TPS.pattern.matcher("!tps Player").matches());
        assertFalse(ChatCommand.C.pattern.matcher("!c50 invalid-name").matches());
        assertFalse(ChatCommand.CA.pattern.matcher("!ca50 abcdefghijklmnopq").matches());
    }

    @Test
    public void eachCommandKeepsItsOwnLiveChannelAndMasterSwitches() {
        MiscConfig config = new MiscConfig();
        for (ChatCommand command : ChatCommand.values()) assertChannels(config, command, false, false, false, false);
        config.setChatCommandsEnabled(true);
        for (ChatCommand command : ChatCommand.values()) assertChannels(config, command, true, true, false, false);

        config.setUseDefaultChannels(ChatCommandSetting.C50, false);
        config.setUseDefaultChannels(ChatCommandSetting.CA50, false);
        config.setUseDefaultChannels(ChatCommandSetting.TPS, false);
        config.setChannelEnabled(ChatCommandSetting.C50, CommandChannel.PARTY, false);
        config.setChannelEnabled(ChatCommandSetting.C50, CommandChannel.PRIVATE, true);
        config.setChannelEnabled(ChatCommandSetting.CA50, CommandChannel.GUILD, false);
        config.setChannelEnabled(ChatCommandSetting.CA50, CommandChannel.ALL_CHAT, true);
        config.setChannelEnabled(ChatCommandSetting.TPS, CommandChannel.PARTY, false);
        config.setChannelEnabled(ChatCommandSetting.TPS, CommandChannel.GUILD, false);
        config.setChannelEnabled(ChatCommandSetting.TPS, CommandChannel.ALL_CHAT, true);
        config.setChannelEnabled(ChatCommandSetting.TPS, CommandChannel.PRIVATE, true);
        assertChannels(config, ChatCommand.C, false, true, false, true);
        assertChannels(config, ChatCommand.CA, true, false, true, false);
        assertChannels(config, ChatCommand.TPS, false, false, true, true);

        config.setCommandEnabled(ChatCommandSetting.C50, false);
        assertChannels(config, ChatCommand.C, false, false, false, false);
        assertChannels(config, ChatCommand.CA, true, false, true, false);
        config.setCommandEnabled(ChatCommandSetting.CA50, false);
        assertChannels(config, ChatCommand.CA, false, false, false, false);
        assertChannels(config, ChatCommand.TPS, false, false, true, true);
        config.setCommandEnabled(ChatCommandSetting.TPS, false);
        assertChannels(config, ChatCommand.TPS, false, false, false, false);

        config.setCommandEnabled(ChatCommandSetting.C50, true);
        config.setCommandEnabled(ChatCommandSetting.CA50, true);
        config.setCommandEnabled(ChatCommandSetting.TPS, true);
        config.setChatCommandsEnabled(false);
        for (ChatCommand command : ChatCommand.values()) assertChannels(config, command, false, false, false, false);
    }

    @Test
    public void ca50TakesAnOptionalNameAndOneClassInEveryForm() {
        assertClass("!ca50 -arch", ChatCommand.CA_ARCHER, null);
        assertClass("!ca50 Beng114 -Archer", ChatCommand.CA_ARCHER, "Beng114");
        assertClass("!ca50 Beng114 -a", ChatCommand.CA_ARCHER, "Beng114");
        for (String flag : new String[] {"-b", "-bers", "-berserk", "-berserker"}) assertClass("!ca50 " + flag, ChatCommand.CA_BERSERK, null);
        for (String flag : new String[] {"-h", "-heal", "-healer"}) assertClass("!ca50 Name_1 " + flag + "  ", ChatCommand.CA_HEALER, "Name_1");
        for (String flag : new String[] {"-m", "-mage"}) assertClass("!CA50 " + flag, ChatCommand.CA_MAGE, null);
        for (String flag : new String[] {"-t", "-tank"}) assertClass("!ca50 Tank " + flag, ChatCommand.CA_TANK, "Tank");
        // Without a flag it stays the plain command, and a flag the class does not know matches nothing.
        assertEquals(List.of(ChatCommand.CA), matching("!ca50 Beng114"));
        for (String body : new String[] {"!ca50 -x", "!ca50 Beng114 -arch extra", "!ca50 Beng114 -a -b", "!ca50 -arch Beng114",
            "!ca50 Beng114 arch", "!ca50 Beng114 -archers", "!c50 -arch", "!tps -arch"}) {
            assertEquals(body, List.of(), matching(body));
        }
    }

    @Test
    public void oneLetterCommandsLevelOneClassAndTakeAnOptionalName() {
        assertClass("!a50", ChatCommand.A, null);
        assertClass("!B50 Beng114 ", ChatCommand.B, "Beng114");
        assertClass("!h50", ChatCommand.H, null);
        assertClass("!m50 Beng114", ChatCommand.M, "Beng114");
        assertClass("!t50\t", ChatCommand.T, null);
        assertEquals(DungeonClass.ARCHER, ChatCommand.A.dungeonClass());
        assertEquals(DungeonClass.BERSERK, ChatCommand.B.dungeonClass());
        assertEquals(DungeonClass.HEALER, ChatCommand.H.dungeonClass());
        assertEquals(DungeonClass.MAGE, ChatCommand.M.dungeonClass());
        assertEquals(DungeonClass.TANK, ChatCommand.T.dungeonClass());
        assertEquals(DungeonClass.BERSERK, ChatCommand.CA_BERSERK.dungeonClass());
        assertNull(ChatCommand.CA.dungeonClass());
        for (String body : new String[] {"!a50 -arch", "!a50 Beng114 Second", "!a50x", "!arch50", "a50", "!a", "!b50 invalid-name"}) {
            assertEquals(body, List.of(), matching(body));
        }
    }

    @Test
    public void everyLevelFromOneToTwoHundredReachesExactlyOneCommand() {
        for (int level = 1; level <= 200; level++) {
            assertLevel("!c" + level, ChatCommand.C, level);
            assertLevel("!CA" + level + " Beng114", ChatCommand.CA, level);
            assertLevel("!ca" + level + " Beng114 -mage", ChatCommand.CA_MAGE, level);
            assertLevel("!a" + level, ChatCommand.A, level);
            assertLevel("!b" + level + " Beng114", ChatCommand.B, level);
            assertLevel("!h" + level, ChatCommand.H, level);
            if (level > 7) assertLevel("!m" + level, ChatCommand.M, level); // !m1 to !m7 start master mode floors
            assertLevel("!t" + level + "\t", ChatCommand.T, level);
        }
        for (int floor = 1; floor <= 7; floor++) {
            assertEquals("!m" + floor, List.of(), matching("!m" + floor));
            assertEquals("!M" + floor + " ", List.of(), matching("!M" + floor + " "));
            // With a player name it is still a mage level question.
            assertLevel("!m" + floor + " Beng114", ChatCommand.M, floor);
        }
        assertLevel("!m8", ChatCommand.M, 8);
        assertLevel("!c43", ChatCommand.C, 43);
        assertLevel("!c98 Beng114", ChatCommand.C, 98);
        assertLevel("!ca60 -tank", ChatCommand.CA_TANK, 60);
        // No level, level 0, a leading zero, or more than 200 is no command; the old names stay plain levels.
        for (String body : new String[] {"!c", "!ca", "!c0", "!c00", "!c050", "!c201", "!c1000", "!ca0", "!ca201 -arch",
            "!a0", "!t999", "!c 50", "!c-5", "!c5.5", "!ca60x", "!c50Beng114"}) {
            assertEquals(body, List.of(), matching(body));
        }
        var tps = ChatCommand.TPS.pattern.matcher("!tps");
        assertTrue(tps.matches());
        assertEquals(0, ChatCommand.TPS.level(tps));
    }

    @Test
    public void classVariantsFollowTheCa50SettingsAndClassLevelHasItsOwn() {
        MiscConfig config = new MiscConfig();
        config.setChatCommandsEnabled(true);
        ChatCommand[] solo = {ChatCommand.CA_ARCHER, ChatCommand.CA_BERSERK, ChatCommand.CA_HEALER,
            ChatCommand.CA_MAGE, ChatCommand.CA_TANK};
        ChatCommand[] level = {ChatCommand.A, ChatCommand.B, ChatCommand.H, ChatCommand.M, ChatCommand.T};
        for (ChatCommand command : solo) assertChannels(config, command, true, true, false, false);
        for (ChatCommand command : level) assertChannels(config, command, true, true, false, false);

        config.setUseDefaultChannels(ChatCommandSetting.CA50, false);
        config.setChannelEnabled(ChatCommandSetting.CA50, CommandChannel.PARTY, false);
        config.setChannelEnabled(ChatCommandSetting.CA50, CommandChannel.ALL_CHAT, true);
        for (ChatCommand command : solo) assertChannels(config, command, false, true, true, false);
        for (ChatCommand command : level) assertChannels(config, command, true, true, false, false);
        assertChannels(config, ChatCommand.C, true, true, false, false);

        config.setUseDefaultChannels(ChatCommandSetting.CLASS_LEVEL, false);
        config.setChannelEnabled(ChatCommandSetting.CLASS_LEVEL, CommandChannel.GUILD, false);
        config.setChannelEnabled(ChatCommandSetting.CLASS_LEVEL, CommandChannel.PRIVATE, true);
        for (ChatCommand command : level) assertChannels(config, command, true, false, false, true);
        for (ChatCommand command : solo) assertChannels(config, command, false, true, true, false);

        config.setCommandEnabled(ChatCommandSetting.CLASS_LEVEL, false);
        for (ChatCommand command : level) assertChannels(config, command, false, false, false, false);
        for (ChatCommand command : solo) assertChannels(config, command, false, true, true, false);
        config.setCommandEnabled(ChatCommandSetting.CA50, false);
        for (ChatCommand command : solo) assertChannels(config, command, false, false, false, false);
    }

    @Test
    public void commandsFollowTheSharedDefaultChannelsUntilTheyHaveTheirOwn() {
        MiscConfig config = new MiscConfig();
        config.setChatCommandsEnabled(true);
        config.setDefaultChannelEnabled(CommandChannel.PARTY, false);
        config.setDefaultChannelEnabled(CommandChannel.ALL_CHAT, true);
        for (ChatCommand command : ChatCommand.values()) assertChannels(config, command, false, true, true, false);

        // A command that takes its own channels starts from the shipped ones, whatever the defaults say.
        config.setUseDefaultChannels(ChatCommandSetting.TPS, false);
        assertChannels(config, ChatCommand.TPS, true, true, false, false);
        config.setChannelEnabled(ChatCommandSetting.TPS, CommandChannel.PRIVATE, true);
        assertChannels(config, ChatCommand.TPS, true, true, false, true);
        assertChannels(config, ChatCommand.C, false, true, true, false);
        config.setDefaultChannelEnabled(CommandChannel.GUILD, false);
        assertChannels(config, ChatCommand.TPS, true, true, false, true);
        assertChannels(config, ChatCommand.C, false, false, true, false);

        // Back on the defaults: the own channels are ignored again.
        config.setUseDefaultChannels(ChatCommandSetting.TPS, true);
        assertChannels(config, ChatCommand.TPS, false, false, true, false);
    }

    @Test
    public void eachGroupHasASwitchThatTurnsOffItsCommandsOnly() {
        MiscConfig config = new MiscConfig();
        config.setChatCommandsEnabled(true);
        ChatCommand[] mayors = {ChatCommand.JERRY, ChatCommand.SCORPIUS, ChatCommand.DERPY};
        ChatCommand[] yearly = {ChatCommand.YOTP, ChatCommand.YOTW, ChatCommand.YOTS};
        config.setSpecialMayorsChatCommandsEnabled(false);
        for (ChatCommand command : mayors) assertChannels(config, command, false, false, false, false);
        for (ChatCommand command : yearly) assertChannels(config, command, true, true, false, false);
        assertChannels(config, ChatCommand.TPS, true, true, false, false);
        // The group switch only gates: the commands keep their own switches.
        config.setSpecialMayorsChatCommandsEnabled(true);
        config.setCommandEnabled(ChatCommandSetting.SCORP, false);
        assertChannels(config, ChatCommand.SCORPIUS, false, false, false, false);
        assertChannels(config, ChatCommand.JERRY, true, true, false, false);

        config.setYearlyEventsChatCommandsEnabled(false);
        for (ChatCommand command : yearly) assertChannels(config, command, false, false, false, false);
        for (ChatCommand command : new ChatCommand[] {ChatCommand.JERRY, ChatCommand.DERPY}) {
            assertChannels(config, command, true, true, false, false);
        }
    }

    @Test
    public void useDefaultEverywhereTouchesOnlyTheOverrides() {
        MiscConfig config = new MiscConfig();
        config.setChatCommandsEnabled(true);
        config.setDefaultChannelEnabled(CommandChannel.PARTY, false);
        config.setDefaultChannelEnabled(CommandChannel.PRIVATE, true);
        config.setSpecialMayorsChatCommandsEnabled(false);
        config.setCommandEnabled(ChatCommandSetting.C50, false);
        config.setUseDefaultChannels(ChatCommandSetting.DERPY, false);
        config.setChannelEnabled(ChatCommandSetting.DERPY, CommandChannel.ALL_CHAT, true);
        config.setUseDefaultChannels(ChatCommandSetting.CA50, false);
        config.setChannelEnabled(ChatCommandSetting.CA50, CommandChannel.GUILD, false);

        config.useDefaultChannelsEverywhere();

        assertTrue(config.useDefaultChannels(ChatCommandSetting.DERPY));
        assertTrue(config.useDefaultChannels(ChatCommandSetting.CA50));
        // The defaults and the switches are what they were.
        assertFalse(config.defaultChannelEnabled(CommandChannel.PARTY));
        assertTrue(config.defaultChannelEnabled(CommandChannel.PRIVATE));
        assertFalse(config.specialMayorsChatCommandsEnabled());
        assertFalse(config.commandEnabled(ChatCommandSetting.C50));
        assertTrue(config.chatCommandsEnabled());
        assertChannels(config, ChatCommand.CA, false, true, false, true);
        assertChannels(config, ChatCommand.DERPY, false, false, false, false);
        assertChannels(config, ChatCommand.C, false, false, false, false);
    }

    @Test
    public void tpsRepliesSynchronouslyOnceWithoutAPlayerOrCalculation() {
        List<String> responses = new ArrayList<>();
        String expected = ServerTpsTracker.INSTANCE.snapshot().message();
        ChatCommand.TPS.execute(null, ChatChannel.PRIVATE, "Player", 0, responses::add);
        assertEquals(List.of(expected), responses);
    }

    /** The commands whose pattern accepts the chat body; a body must never reach two of them. */
    private static List<ChatCommand> matching(String body) {
        List<ChatCommand> hits = new ArrayList<>();
        for (ChatCommand command : ChatCommand.values()) if (command.pattern.matcher(body).matches()) hits.add(command);
        return hits;
    }

    private static void assertLevel(String body, ChatCommand expected, int level) {
        assertEquals(body, List.of(expected), matching(body));
        var match = expected.pattern.matcher(body);
        assertTrue(body, match.matches());
        assertEquals(body, level, expected.level(match));
    }

    private static void assertClass(String body, ChatCommand expected, String name) {
        assertEquals(body, List.of(expected), matching(body));
        assertTarget(expected, body, name);
    }

    private static void assertTarget(ChatCommand command, String text, String expected) {
        var match = command.pattern.matcher(text);
        assertTrue(text, match.matches());
        assertEquals(expected, command.target(match));
    }

    private static void assertChannels(MiscConfig config, ChatCommand command, boolean party, boolean guild,
                                       boolean all, boolean direct) {
        assertEquals(command + " party", party, command.isEnabled(config, ChatChannel.PARTY));
        assertEquals(command + " guild", guild, command.isEnabled(config, ChatChannel.GUILD));
        assertEquals(command + " all", all, command.isEnabled(config, ChatChannel.ALL));
        assertEquals(command + " private", direct, command.isEnabled(config, ChatChannel.PRIVATE));
    }
}
