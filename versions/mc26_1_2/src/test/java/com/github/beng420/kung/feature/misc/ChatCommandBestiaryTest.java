package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.ChatCommandSetting;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.*;

/** "!bestiary" and "!be": the mob words and the "-name" flag the client forwards; the Kung server does the rest. */
public final class ChatCommandBestiaryTest {
    @Test
    public void takesTheMobWordsAndAnOptionalPlayerFlag() {
        assertParsed("!be barb", "barb", null);
        assertParsed("!bestiary barb", "barb", null);
        assertParsed("!BE Barb", "Barb", null);
        assertParsed("!be barb -yrkuna", "barb", "yrkuna");
        assertParsed("!bestiary barb duke x -Yrkuna_1", "barb duke x", "Yrkuna_1");
        assertParsed("!be   barb  -yrkuna  ", "barb", "yrkuna");
        assertParsed("!be b", "b", null);
        assertParsed("!be nature's prime", "nature's prime", null);
        // Without a mob the command still answers, with how to type it.
        assertParsed("!be", null, null);
        assertParsed("!bestiary", null, null);
        assertParsed("!be -yrkuna", null, "yrkuna");
    }

    @Test
    public void aProfileFlagFollowsThePlayerFlagOrStandsAlone() {
        assertProfile("!be barb -yrkuna -coconut", "yrkuna", "Coconut");
        assertProfile("!bestiary barb -Yrkuna_1 -MANGO  ", "Yrkuna_1", "Mango");
        // "-coconut" alone is the profile of the sender, not a player named Coconut.
        assertProfile("!be barb -coconut", null, "Coconut");
        assertProfile("!be -mango", null, "Mango");
        // A player named like a fruit goes first and takes a profile behind it.
        assertProfile("!be barb -lemon -lemon", "lemon", "Lemon");
        assertProfile("!be barb -lemon -apple", "lemon", "Apple");
        assertProfile("!be barb -yrkuna", "yrkuna", null);
        assertProfile("!be barb", null, null);
        // Only the 21 profile names count, once, and behind the player flag.
        for (String body : new String[] {"!be barb -coconut -yrkuna", "!be barb -yrkuna -coconut -mango", "!be barb -yrkuna -coconutty",
            "!be barb -coconut extra"}) {
            assertFalse(body, ChatCommand.BESTIARY.pattern.matcher(body).matches());
        }
    }

    private static void assertProfile(String body, String target, String profile) {
        Matcher match = ChatCommand.BESTIARY.pattern.matcher(body);
        assertTrue(body, match.matches());
        assertEquals(body, target, ChatCommand.BESTIARY.target(match));
        assertEquals(body, profile, ChatCommand.BESTIARY.profile(match));
    }

    @Test
    public void rejectsLinesThatAreNotTheCommand() {
        for (String body : new String[] {"!bex", "!best", "!bestiaryx barb", "!be barb -", "!be barb --yrkuna", "!be barb -yrkuna extra",
            "!be barb -a2345678901234567", "!be " + "x".repeat(33), "!be barb!", "!be barb\nnext", "be barb", "hello !be barb", "! be barb"}) {
            assertFalse(body, ChatCommand.BESTIARY.pattern.matcher(body).matches());
        }
    }

    @Test
    public void noOtherCommandTakesTheSameLine() {
        for (String body : new String[] {"!be barb", "!bestiary barb -yrkuna", "!be", "!be b", "!be 5"}) {
            List<ChatCommand> hits = new ArrayList<>();
            for (ChatCommand command : ChatCommand.values()) if (command.pattern.matcher(body).matches()) hits.add(command);
            assertEquals(body, List.of(ChatCommand.BESTIARY), hits);
        }
        // The class level commands keep !b50 and !bers50.
        assertFalse(ChatCommand.BESTIARY.pattern.matcher("!b50").matches());
        assertFalse(ChatCommand.BESTIARY.pattern.matcher("!bers50 Player").matches());
        assertTrue(ChatCommand.B.pattern.matcher("!b50").matches());
    }

    @Test
    public void helpAndSwitchFollowTheOtherCommands() {
        MiscConfig config = new MiscConfig();
        assertFalse(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.PARTY));
        config.setChatCommandsEnabled(true);
        assertTrue(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.PARTY));
        assertTrue(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.GUILD));
        // All chat and private chat stay off by default, like every command.
        assertFalse(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.ALL));
        assertFalse(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.PRIVATE));
        assertTrue(ChatCommand.helpMessage(config, ChatChannel.PARTY).endsWith(", !be <mob> [-name] [-profile]"));
        config.setCommandEnabled(ChatCommandSetting.BESTIARY, false);
        assertFalse(ChatCommand.BESTIARY.isEnabled(config, ChatChannel.PARTY));
        assertFalse(ChatCommand.helpMessage(config, ChatChannel.PARTY).contains("!be"));
    }

    @Test
    public void aLineWithoutMobWordsAnswersHowToTypeItAndAsksNoServer() {
        List<String> responses = new ArrayList<>();
        ChatCommand.BESTIARY.execute(null, ChatChannel.PARTY, "Player", 0, null, null, responses::add);
        ChatCommand.BESTIARY.execute(null, ChatChannel.PARTY, "Player", 0, null, "  ", responses::add);
        assertEquals(List.of("Type a mob name: !be barb, or !be barb -name for another player.",
            "Type a mob name: !be barb, or !be barb -name for another player."), responses);
    }

    private static void assertParsed(String body, String mob, String target) {
        Matcher match = ChatCommand.BESTIARY.pattern.matcher(body);
        assertTrue(body, match.matches());
        assertEquals(body, mob, ChatCommand.BESTIARY.mob(match));
        assertEquals(body, target, ChatCommand.BESTIARY.target(match));
        assertNull(body, ChatCommand.BESTIARY.floor(match));
        assertEquals(body, 0L, ChatCommand.BESTIARY.level(match));
    }

    @Test
    public void otherCommandsCarryNoMobWords() {
        Matcher tps = ChatCommand.TPS.pattern.matcher("!tps");
        assertTrue(tps.matches());
        assertNull(ChatCommand.TPS.mob(tps));
    }
}
