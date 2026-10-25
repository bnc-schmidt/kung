package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import org.junit.Test;

public class ChatCommandAliasTest {
    private static boolean matches(ChatCommand command, String body) {
        return command.pattern.matcher(body).matches();
    }

    /** For tests of other packages: does the named command's pattern match the chat line? */
    public static boolean matches(String body, String commandName) {
        return matches(ChatCommand.valueOf(commandName), body);
    }

    @Test
    public void everyNameOfACommandTriggersIt() {
        assertTrue(matches(ChatCommand.JERRY, "!jerry"));
        assertTrue(matches(ChatCommand.JERRY, "!Jerry "));
        assertTrue(matches(ChatCommand.SCORPIUS, "!scorp"));
        assertTrue(matches(ChatCommand.SCORPIUS, "!scorpius"));
        assertTrue(matches(ChatCommand.SCORPIUS, "!SCORP"));
        assertTrue(matches(ChatCommand.DERPY, "!derpy"));
        // The names are whole words, and the mayor commands take no player name.
        assertFalse(matches(ChatCommand.SCORPIUS, "!scorpiuss"));
        assertFalse(matches(ChatCommand.SCORPIUS, "!scorpi"));
        assertFalse(matches(ChatCommand.JERRY, "!jerry Beng114"));
        assertFalse(matches(ChatCommand.JERRY, "jerry"));
        assertFalse(matches(ChatCommand.JERRY, "!derpy"));
        // Existing commands keep their single name and their optional player.
        assertTrue(matches(ChatCommand.TPS, "!tps"));
        assertTrue(matches(ChatCommand.C50, "!c50 Beng114"));
        assertTrue(matches(ChatCommand.CA50, "!ca50"));
        assertFalse(matches(ChatCommand.TPS, "!tps now"));
        var named = ChatCommand.C50.pattern.matcher("!c50 Beng114");
        assertTrue(named.matches());
        assertEquals("Beng114", ChatCommand.C50.target(named));
        var mayor = ChatCommand.DERPY.pattern.matcher("!derpy");
        assertTrue(mayor.matches());
        assertNull(ChatCommand.DERPY.target(mayor));
    }

    @Test
    public void aReplyWaitsMoreThanHalfASecondAfterTheCommandAndAfterThePreviousReply() {
        // Hypixel's limit is one message per 500 ms: the delay must clear it, and so must the pause between replies.
        assertTrue(ChatCommandsFeature.REPLY_DELAY_MILLIS > 500);
        long arrived = 10_000;
        long ready = arrived + ChatCommandsFeature.REPLY_DELAY_MILLIS;
        assertFalse(ChatCommandsFeature.replyDue(arrived, ready, 0));
        assertFalse(ChatCommandsFeature.replyDue(ready - 1, ready, 0));
        assertTrue(ChatCommandsFeature.replyDue(ready, ready, 0));
        // A second reply queued at the same moment still waits for the pause after the first one.
        assertFalse(ChatCommandsFeature.replyDue(ready + 100, ready, ready));
        assertTrue(ChatCommandsFeature.replyDue(ready + 650, ready, ready));
    }

    @Test
    public void everySpecialMayorCommandHasItsOwnSwitchesWithTheTpsDefaults() {
        var config = new MiscConfig();
        config.setChatCommandsEnabled(true); // the Chat Commands master switch is off by default
        for (ChatCommand command : new ChatCommand[] {ChatCommand.JERRY, ChatCommand.SCORPIUS, ChatCommand.DERPY}) {
            assertTrue(command.isEnabled(config, ChatChannel.PARTY));
            assertTrue(command.isEnabled(config, ChatChannel.GUILD));
            assertFalse(command.isEnabled(config, ChatChannel.ALL)); // like !tps: public chat is opt-in
            assertFalse(command.isEnabled(config, ChatChannel.PRIVATE));
        }
    }

    @Test
    public void switchingOneSpecialMayorCommandLeavesTheOthersAlone() {
        var config = new MiscConfig();
        config.setChatCommandsEnabled(true);

        config.setScorpChatCommandEnabled(false);
        assertFalse(ChatCommand.SCORPIUS.isEnabled(config, ChatChannel.PARTY));
        assertTrue(ChatCommand.JERRY.isEnabled(config, ChatChannel.PARTY));
        assertTrue(ChatCommand.DERPY.isEnabled(config, ChatChannel.PARTY));

        config.setDerpyAllChatCommandsEnabled(true);
        assertTrue(ChatCommand.DERPY.isEnabled(config, ChatChannel.ALL));
        assertFalse(ChatCommand.JERRY.isEnabled(config, ChatChannel.ALL));
        assertFalse(ChatCommand.SCORPIUS.isEnabled(config, ChatChannel.ALL));

        config.setJerryGuildCommandsEnabled(false);
        assertFalse(ChatCommand.JERRY.isEnabled(config, ChatChannel.GUILD));
        assertTrue(ChatCommand.DERPY.isEnabled(config, ChatChannel.GUILD));

        config.setJerryPrivateCommandsEnabled(true);
        assertTrue(ChatCommand.JERRY.isEnabled(config, ChatChannel.PRIVATE));
        assertFalse(ChatCommand.DERPY.isEnabled(config, ChatChannel.PRIVATE));

        // The other commands and the master switch are untouched by all of it.
        assertTrue(ChatCommand.TPS.isEnabled(config, ChatChannel.PARTY));
        config.setChatCommandsEnabled(false);
        assertFalse(ChatCommand.JERRY.isEnabled(config, ChatChannel.PARTY));
    }
}
