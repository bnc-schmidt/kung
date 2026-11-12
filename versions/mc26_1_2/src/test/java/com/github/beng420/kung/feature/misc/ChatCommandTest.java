package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.Floor;
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
            // !bestiary takes any number of mob words.
            if (command != ChatCommand.BESTIARY) assertFalse(command.pattern.matcher(name + " Player Second").matches());
            assertFalse(command.pattern.matcher("hello " + name).matches());
        }
        assertFalse(ChatCommand.TPS.pattern.matcher("!tps Player").matches());
        assertFalse(ChatCommand.C.pattern.matcher("!c50 invalid-name").matches());
        assertFalse(ChatCommand.CA.pattern.matcher("!ca50 abcdefghijklmnopq").matches());
    }

    @Test
    public void eachCommandKeepsItsOwnLiveChannelAndMasterSwitches() {
        MiscConfig config = new MiscConfig();
        for (ChatCommand command : switchable()) assertChannels(config, command, false, false, false, false);
        config.setChatCommandsEnabled(true);
        for (ChatCommand command : switchable()) assertChannels(config, command, true, true, false, false);

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
        for (ChatCommand command : switchable()) assertChannels(config, command, false, false, false, false);
    }

    @Test
    public void theVersionLineAndTheHelpAnswerToTheirOwnNamesAndNothingLonger() {
        for (String body : new String[] {"!kung", "!KUNG", "!Kung  "}) {
            assertEquals(body, List.of(ChatCommand.KUNG), matching(body));
        }
        for (String body : new String[] {"!kunghelp", "!helpkung", "!kung help", "!Kung   Help  "}) {
            assertEquals(body, List.of(ChatCommand.KUNG_HELP), matching(body));
        }
        for (String body : new String[] {"!kungfu", "!kung helpme", "!kung help me", "!help", "kung", "!kung Beng114"}) {
            assertEquals(body, List.of(), matching(body));
        }
    }

    @Test
    public void theVersionLineHasNoSwitchAndStaysOutOfAllChat() {
        MiscConfig config = new MiscConfig();
        assertFalse(config.chatCommandsEnabled());
        assertChannels(config, ChatCommand.KUNG, true, true, false, true);
        config.setChatCommandsEnabled(true);
        assertChannels(config, ChatCommand.KUNG, true, true, false, true);
        assertTrue(ChatCommand.KUNG.isEnabled(config, ChatChannel.COOP));
    }

    @Test
    public void theHelpListsExactlyTheCommandsThatAnswerInTheChannel() {
        MiscConfig config = new MiscConfig();
        // The help needs the Chat Commands feature.
        assertEquals("", ChatCommand.helpMessage(config, ChatChannel.PARTY));
        assertFalse(ChatCommand.KUNG_HELP.isEnabled(config, ChatChannel.PARTY));
        config.setChatCommandsEnabled(true);
        assertEquals("Kung commands: !c<lvl> [name] [-floor] [-profile], !ca<lvl> [name] [-class] [-floor] [-profile], !tps, !jerry, !scorp, "
            + "!derpy, !yotp, !yotw, !yots, !a/b/h/m/t<lvl> [name] [-floor] [-profile], !be <mob> [-name] [-profile]",
            ChatCommand.helpMessage(config, ChatChannel.PARTY));
        // One chat message holds at most 256 characters.
        assertTrue(ChatCommand.helpMessage(config, ChatChannel.PARTY).length() <= 256);
        assertTrue(ChatCommand.KUNG_HELP.isEnabled(config, ChatChannel.PARTY));
        // All chat has no command on by default, so the help stays quiet there too.
        assertEquals("", ChatCommand.helpMessage(config, ChatChannel.ALL));
        assertFalse(ChatCommand.KUNG_HELP.isEnabled(config, ChatChannel.ALL));
        // A switched-off command or group leaves the list, and the version line never appears in it.
        config.setCommandEnabled(ChatCommandSetting.TPS, false);
        config.setCommandEnabled(ChatCommandSetting.CLASS_LEVEL, false);
        config.setCommandEnabled(ChatCommandSetting.BESTIARY, false);
        config.setSpecialMayorsChatCommandsEnabled(false);
        config.setYearlyEventsChatCommandsEnabled(false);
        assertEquals("Kung commands: !c<lvl> [name] [-floor] [-profile], !ca<lvl> [name] [-class] [-floor] [-profile]",
            ChatCommand.helpMessage(config, ChatChannel.PARTY));
        config.setCommandEnabled(ChatCommandSetting.C50, false);
        config.setCommandEnabled(ChatCommandSetting.CA50, false);
        assertEquals("", ChatCommand.helpMessage(config, ChatChannel.PARTY));
        assertFalse(ChatCommand.KUNG_HELP.isEnabled(config, ChatChannel.PARTY));
    }

    /** Every command with a switch: the version line is always on and has none. */
    private static List<ChatCommand> switchable() {
        return java.util.Arrays.stream(ChatCommand.values()).filter(command -> command != ChatCommand.KUNG).toList();
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
    public void ca50TakesAFloorAloneOrWithAClassInEitherOrder() {
        assertFloor("!ca50 -f7", ChatCommand.CA, null, Floor.F7);
        assertFloor("!ca50 Beng114 -M3", ChatCommand.CA, "Beng114", Floor.M3);
        assertFloor("!CA60 -e", ChatCommand.CA, null, Floor.ENTRANCE);
        assertFloor("!ca60 Beng114 -Entrance", ChatCommand.CA, "Beng114", Floor.ENTRANCE);
        assertFloor("!ca60 -entrance -mage", ChatCommand.CA_MAGE, null, Floor.ENTRANCE);
        assertFloor("!ca60 -h -e", ChatCommand.CA_HEALER, null, Floor.ENTRANCE);
        assertFloor("!ca50 Beng114", ChatCommand.CA, "Beng114", null);
        // The class first or the floor first: the same command, the same floor.
        assertFloor("!ca50 -arch -f1", ChatCommand.CA_ARCHER, null, Floor.F1);
        assertFloor("!ca50 -f1 -arch", ChatCommand.CA_ARCHER, null, Floor.F1);
        assertFloor("!ca50 Beng114 -Tank -M5  ", ChatCommand.CA_TANK, "Beng114", Floor.M5);
        assertFloor("!ca50 Beng114 -m5 -tank", ChatCommand.CA_TANK, "Beng114", Floor.M5);
        // -m is the Mage and -m7 is Master 7; the digit tells them apart, in every position.
        assertFloor("!ca60 -m7 -m", ChatCommand.CA_MAGE, null, Floor.M7);
        assertFloor("!ca60 -m -m7", ChatCommand.CA_MAGE, null, Floor.M7);
        assertFloor("!ca60 -m", ChatCommand.CA_MAGE, null, null);
        assertFloor("!ca60 -tank", ChatCommand.CA_TANK, null, null);
        for (int number = 1; number <= 7; number++) {
            assertFloor("!ca50 -f" + number, ChatCommand.CA, null, Floor.valueOf("F" + number));
            assertFloor("!ca50 -m" + number, ChatCommand.CA, null, Floor.valueOf("M" + number));
            assertFloor("!ca50 -healer -F" + number, ChatCommand.CA_HEALER, null, Floor.valueOf("F" + number));
        }
        // Each flag once, nothing else after them, and a floor that does not exist matches nothing.
        for (String body : new String[] {"!ca50 -f8", "!ca50 -f0", "!ca50 -f", "!ca50 -f77", "!ca50 -fl7", "!ca50 -f7 -f6",
            "!ca50 -ent", "!ca50 -entrances", "!ca50 -e -f7", "!ca50 -entrance -entrance",
            "!ca50 -f7 -m3", "!ca50 -arch -mage -f7", "!ca50 -f7 -arch -mage", "!ca50 -arch -f7 -f6", "!ca50 -f7 -arch extra",
            "!ca50 -f7 Beng114", "!ca50 -arch -f7 Beng114", "!ca50 -arch f7"}) {
            assertEquals(body, List.of(), matching(body));
        }
        // Without the dash it is a player name, as before.
        assertFloor("!ca50 f7", ChatCommand.CA, "f7", null);
        // The other commands take no floor.
        for (String body : new String[] {"!tps -f7", "!jerry -f7"}) assertEquals(body, List.of(), matching(body));
    }

    @Test
    public void classLevelCommandsTakeOneFloorButNoClass() {
        assertFloor("!a50", ChatCommand.A, null, null);
        assertFloor("!t50 -m6", ChatCommand.T, null, Floor.M6);
        assertFloor("!B50 Beng114 -F3", ChatCommand.B, "Beng114", Floor.F3);
        assertFloor("!tank60 -e", ChatCommand.T, null, Floor.ENTRANCE);
        assertFloor("!mage60 Beng114 -Entrance  ", ChatCommand.M, "Beng114", Floor.ENTRANCE);
        assertFloor("!m50 -m6", ChatCommand.M, null, Floor.M6);
        for (int number = 1; number <= 7; number++) {
            assertFloor("!h50 -f" + number, ChatCommand.H, null, Floor.valueOf("F" + number));
            assertFloor("!arch50 -m" + number, ChatCommand.A, null, Floor.valueOf("M" + number));
        }
        // A class flag, a second floor, a flag before the name or no dash match nothing; without the dash it is a name.
        for (String body : new String[] {"!a50 -arch", "!a50 -m", "!t50 -f7 -f6", "!t50 -f7 Beng114", "!t50 -f8", "!t50 -ent"}) {
            assertEquals(body, List.of(), matching(body));
        }
        assertFloor("!t50 f7", ChatCommand.T, "f7", null);
        // A lone !m6 and !t5 stay with Hypixel, but with a floor flag they are level commands.
        assertEquals(List.of(), matching("!m6"));
        assertFloor("!t5 -f7", ChatCommand.T, null, Floor.F7);
    }

    @Test
    public void c50TakesAnOptionalNameAndOneFloorButNoClass() {
        assertFloor("!c50", ChatCommand.C, null, null);
        assertFloor("!c67 -f7", ChatCommand.C, null, Floor.F7);
        assertFloor("!C67 Beng114 -M3", ChatCommand.C, "Beng114", Floor.M3);
        assertFloor("!c50 -e", ChatCommand.C, null, Floor.ENTRANCE);
        assertFloor("!c50 Beng114 -Entrance  ", ChatCommand.C, "Beng114", Floor.ENTRANCE);
        for (int number = 1; number <= 7; number++) {
            assertFloor("!c50 -f" + number, ChatCommand.C, null, Floor.valueOf("F" + number));
            assertFloor("!c50 -m" + number, ChatCommand.C, null, Floor.valueOf("M" + number));
        }
        // A class flag, a second floor, a flag before the name or no dash match nothing; without the dash it is a name.
        for (String body : new String[] {"!c50 -arch", "!c50 -m", "!c50 -f7 -f6", "!c50 -arch -f7", "!c50 -f7 -arch", "!c50 -f7 Beng114",
            "!c50 -f8", "!c50 -f0", "!c50 -ent", "!c50 invalid-name"}) {
            assertEquals(body, List.of(), matching(body));
        }
        assertFloor("!c50 f7", ChatCommand.C, "f7", null);
    }

    @Test
    public void everyLevelCommandTakesOneProfileAlongsideItsOtherFlags() {
        assertProfile("!c50 -coconut", ChatCommand.C, null, "Coconut", null);
        assertProfile("!c50 Beng114 -MANGO", ChatCommand.C, "Beng114", "Mango", null);
        assertProfile("!c50 -mango -m6", ChatCommand.C, null, "Mango", Floor.M6);
        assertProfile("!c50 -f7 -mango", ChatCommand.C, null, "Mango", Floor.F7);
        assertProfile("!ca60 Beng114 -pear -e", ChatCommand.CA, "Beng114", "Pear", Floor.ENTRANCE);
        assertProfile("!t50 -m6 -lime", ChatCommand.T, null, "Lime", Floor.M6);
        assertProfile("!mage50 Beng114 -kiwi", ChatCommand.M, "Beng114", "Kiwi", null);
        // The !ca class flag goes first, last or between the other two.
        assertProfile("!ca60 -arch -lemon -f7", ChatCommand.CA_ARCHER, null, "Lemon", Floor.F7);
        assertProfile("!ca60 -lemon -arch -f7", ChatCommand.CA_ARCHER, null, "Lemon", Floor.F7);
        assertProfile("!ca60 -f7 -lemon -arch", ChatCommand.CA_ARCHER, null, "Lemon", Floor.F7);
        assertProfile("!ca60 Beng114 -mage -papaya", ChatCommand.CA_MAGE, "Beng114", "Papaya", null);
        // -m is the Mage, -mango a profile: the whole word tells them apart.
        assertProfile("!ca60 -m -mango", ChatCommand.CA_MAGE, null, "Mango", null);
        assertProfile("!ca60 -mango -m", ChatCommand.CA_MAGE, null, "Mango", null);
        for (String name : new String[] {"apple", "banana", "blueberry", "coconut", "cucumber", "grapes", "kiwi", "lemon", "lime", "mango",
            "orange", "papaya", "pear", "peach", "pineapple", "pomegranate", "raspberry", "strawberry", "tomato", "watermelon", "zucchini"}) {
            assertProfile("!c50 -" + name, ChatCommand.C, null, Character.toUpperCase(name.charAt(0)) + name.substring(1), null);
        }
        assertProfile("!c50", ChatCommand.C, null, null, null);
        // A second profile, a profile before the name, no dash or a word that is no profile match nothing or stay a name.
        for (String body : new String[] {"!c50 -mango -kiwi", "!c50 -mango Beng114", "!c50 -fig", "!c50 -mangoes", "!a50 -arch -mango",
            "!ca60 -arch -mage -mango", "!ca60 -mango -arch -mango", "!tps -mango", "!jerry -mango"}) {
            assertEquals(body, List.of(), matching(body));
        }
        assertProfile("!c50 mango", ChatCommand.C, "mango", null, null);
    }

    private static void assertProfile(String body, ChatCommand expected, String name, String profile, Floor floor) {
        assertFloor(body, expected, name, floor);
        var match = expected.pattern.matcher(body);
        assertTrue(body, match.matches());
        assertEquals(body, profile, expected.profile(match));
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
        for (String body : new String[] {"!a50 -arch", "!a50 Beng114 Second", "!a50x", "!arch", "!archer0", "a50", "!a", "!b50 invalid-name"}) {
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
            if (level > 5) assertLevel("!t" + level + "\t", ChatCommand.T, level); // !t1 to !t5 start Kuudra tiers
            // The long names take every level, the floors and tiers included.
            assertLevel("!mage" + level, ChatCommand.M, level);
            assertLevel("!arch" + level, ChatCommand.A, level);
            assertLevel("!ARCHER" + level + " Beng114", ChatCommand.A, level);
            assertLevel("!bers" + level, ChatCommand.B, level);
            assertLevel("!berserker" + level, ChatCommand.B, level);
            assertLevel("!heal" + level, ChatCommand.H, level);
            assertLevel("!healer" + level + " Beng114", ChatCommand.H, level);
            assertLevel("!TANK" + level + " Beng114", ChatCommand.T, level);
        }
        for (int floor = 1; floor <= 7; floor++) {
            assertEquals("!m" + floor, List.of(), matching("!m" + floor));
            assertEquals("!M" + floor + " ", List.of(), matching("!M" + floor + " "));
            // With a player name it is still a mage level question.
            assertLevel("!m" + floor + " Beng114", ChatCommand.M, floor);
        }
        for (int tier = 1; tier <= 5; tier++) {
            assertEquals("!t" + tier, List.of(), matching("!t" + tier));
            assertEquals("!T" + tier + "\t", List.of(), matching("!T" + tier + "\t"));
            // With a player name it is still a tank level question.
            assertLevel("!t" + tier + " Beng114", ChatCommand.T, tier);
        }
        assertLevel("!m8", ChatCommand.M, 8);
        assertLevel("!t6", ChatCommand.T, 6);
        assertLevel("!t15", ChatCommand.T, 15); // a longer number is no tier
        assertLevel("!c43", ChatCommand.C, 43);
        assertLevel("!c98 Beng114", ChatCommand.C, 98);
        assertLevel("!ca60 -tank", ChatCommand.CA_TANK, 60);
        // No level, level 0, or a leading zero is no command; the old names stay plain levels.
        for (String body : new String[] {"!c", "!ca", "!c0", "!c00", "!c050", "!ca0",
            "!a0", "!c 50", "!c-5", "!c5.5", "!ca60x", "!c50Beng114", "!mage", "!tank", "!mage0"}) {
            assertEquals(body, List.of(), matching(body));
        }
    }

    @Test
    public void aLevelHasNoUpperLimit() {
        assertLevel("!c201", ChatCommand.C, 201);
        assertLevel("!c1000", ChatCommand.C, 1000);
        assertLevel("!ca201 -arch", ChatCommand.CA_ARCHER, 201);
        assertLevel("!t999", ChatCommand.T, 999);
        assertLevel("!tank201", ChatCommand.T, 201);
        assertLevel("!ca999999999999 Beng114 -f7", ChatCommand.CA, 999_999_999_999L);
        // A number too big for a long reads as the largest one, so it still gets an answer.
        var huge = ChatCommand.C.pattern.matcher("!c" + "9".repeat(40));
        assertTrue(huge.matches());
        assertEquals(Long.MAX_VALUE, ChatCommand.C.level(huge));
    }

    @Test
    public void aCommandWithoutALevelReadsLevelZero() {
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
        for (ChatCommand command : switchable()) assertChannels(config, command, false, true, true, false);

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
        ChatCommand.TPS.execute(null, ChatChannel.PRIVATE, "Player", 0, null, responses::add);
        assertEquals(List.of(expected), responses);
    }

    /** The commands whose pattern accepts the chat body; a body must never reach two of them. */
    private static List<ChatCommand> matching(String body) {
        List<ChatCommand> hits = new ArrayList<>();
        for (ChatCommand command : ChatCommand.values()) if (command.pattern.matcher(body).matches()) hits.add(command);
        return hits;
    }

    private static void assertLevel(String body, ChatCommand expected, long level) {
        assertEquals(body, List.of(expected), matching(body));
        var match = expected.pattern.matcher(body);
        assertTrue(body, match.matches());
        assertEquals(body, level, expected.level(match));
    }

    private static void assertClass(String body, ChatCommand expected, String name) {
        assertEquals(body, List.of(expected), matching(body));
        assertTarget(expected, body, name);
    }

    private static void assertFloor(String body, ChatCommand expected, String name, Floor floor) {
        assertEquals(body, List.of(expected), matching(body));
        assertTarget(expected, body, name);
        assertEquals(body, floor, floorOf(expected, body));
    }

    private static Floor floorOf(ChatCommand command, String body) {
        var match = command.pattern.matcher(body);
        assertTrue(body, match.matches());
        return command.floor(match);
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
