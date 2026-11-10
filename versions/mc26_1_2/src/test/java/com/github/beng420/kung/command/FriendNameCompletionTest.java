package com.github.beng420.kung.command;

import static org.junit.Assert.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.List;
import org.junit.Test;

public class FriendNameCompletionTest {
    private static final List<String> FRIENDS = List.of("Alice", "alfred", "Bob_2");

    private static int start(String input) {
        return FriendNameCompletion.nameStart(input, input.length());
    }

    private static List<String> texts(Suggestions suggestions) {
        return suggestions.getList().stream().map(Suggestion::getText).sorted().toList();
    }

    @Test
    public void theNameSlotIsFoundInTheCommandsThatTakeAPlayer() {
        assertEquals("/msg ".length(), start("/msg "));
        assertEquals("/msg Al".length() - 2, start("/msg Al"));
        assertEquals("/w ".length(), start("/w "));
        assertEquals("/tell ".length(), start("/tell "));
        assertEquals("/whisper ".length(), start("/whisper "));
        assertEquals("/visit ".length(), start("/visit "));
        assertEquals("/p ".length(), start("/p "));
        assertEquals("/party ".length(), start("/party "));
        assertEquals("/p invite ".length(), start("/p invite "));
        // /p invite takes any number of names.
        assertEquals("/p invite Alice Bo".length() - 2, start("/p invite Alice Bo"));
        assertEquals("/p accept ".length(), start("/p accept "));
        assertEquals("/f remove ".length(), start("/f remove "));
        assertEquals("/friend best ".length(), start("/friend best "));
        // The command names are no case.
        assertEquals("/MSG ".length(), start("/MSG "));
    }

    @Test
    public void moreHypixelCommandsTakeAName() {
        assertEquals("/pv ".length(), start("/pv "));
        assertEquals("/trade ".length(), start("/trade "));
        assertEquals("/ah ".length(), start("/ah "));
        assertEquals("/duel ".length(), start("/duel "));
        assertEquals("/report ".length(), start("/report "));
        assertEquals("/ignore add ".length(), start("/ignore add "));
        assertEquals("/g invite ".length(), start("/g invite "));
        assertEquals("/guild invite ".length(), start("/guild invite "));
        assertEquals(-1, start("/ignore list "));
        assertEquals(-1, start("/g kick "));
    }

    /** What Minecraft does with a typed command: the slash is skipped, the full string stays in the reader. */
    private static ParseResults<Object> parse(CommandDispatcher<Object> dispatcher, String input) {
        StringReader reader = new StringReader(input);
        reader.skip();
        return dispatcher.parse(reader, new Object());
    }

    private static CommandDispatcher<Object> modCommands() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        // As Skyblocker and the like declare /pv: a player, then a page.
        dispatcher.register(LiteralArgumentBuilder.literal("lookup").then(
            RequiredArgumentBuilder.argument("player", StringArgumentType.word()).then(
                RequiredArgumentBuilder.argument("page", IntegerArgumentType.integer(1)))));
        dispatcher.register(LiteralArgumentBuilder.literal("warp").then(
            RequiredArgumentBuilder.argument("place", StringArgumentType.word())));
        dispatcher.register(LiteralArgumentBuilder.literal("level").then(
            RequiredArgumentBuilder.argument("player", IntegerArgumentType.integer())));
        return dispatcher;
    }

    @Test
    public void aTextArgumentTheCommandTreeCallsAPlayerIsANameSlot() {
        var dispatcher = modCommands();
        for (String input : new String[] {"/lookup ", "/lookup Al", "/lookup alice"}) {
            assertEquals(input, "/lookup ".length(), FriendNameCompletion.nameStart(parse(dispatcher, input), input.length()));
        }
        // The cursor inside the word still points at its start.
        assertEquals("/lookup ".length(), FriendNameCompletion.nameStart(parse(dispatcher, "/lookup alice"), "/lookup al".length()));
    }

    @Test
    public void otherArgumentsOfTheCommandTreeAreNoNameSlot() {
        var dispatcher = modCommands();
        for (String input : new String[] {"/lookup alice ", "/lookup alice 2", "/warp ", "/warp ha", "/level ", "/nothing ",
            "/lookup"}) {
            assertEquals(input, -1, FriendNameCompletion.nameStart(parse(dispatcher, input), input.length()));
        }
    }

    @Test
    public void theListedCommandsAreFoundEvenWhereTheTreeKnowsNothing() {
        var dispatcher = modCommands();
        assertEquals("/msg ".length(), FriendNameCompletion.nameStart(parse(dispatcher, "/msg "), "/msg ".length()));
    }

    @Test
    public void otherWordsAreNoNameSlot() {
        for (String input : new String[] {"", "msg ", "hello al", "/", "/msg", "/p", "/msg Alice hi", "/msg Alice ",
            "/p kick ", "/p accept Alice ", "/f ", "/f list ", "/f add ", "/friend remove Alice ", "/r ", "/chat ",
            "/kung "}) {
            assertEquals(input, -1, start(input));
        }
    }

    @Test
    public void theCursorDecidesNotTheEndOfTheInput() {
        assertEquals("/msg ".length(), FriendNameCompletion.nameStart("/msg Alice hello", "/msg ".length()));
        assertEquals(-1, FriendNameCompletion.nameStart("/msg Alice hello", "/msg Alice hel".length()));
    }

    @Test
    public void friendsThatStartWithTheTypedNameAreAddedWithoutRegardToCase() {
        Suggestions merged = FriendNameCompletion.merge("/msg al", 7, Suggestions.empty().join(), FRIENDS);
        assertEquals(List.of("Alice", "alfred"), texts(merged));
        assertEquals(StringRange.between(5, 7), merged.getRange());
    }

    @Test
    public void anEmptyWordListsEveryFriend() {
        Suggestions merged = FriendNameCompletion.merge("/p ", 3, Suggestions.empty().join(), FRIENDS);
        assertEquals(List.of("Alice", "Bob_2", "alfred"), texts(merged));
    }

    @Test
    public void theServersSuggestionsStayAndAnameBothGiveIsListedOnce() {
        var server = new com.mojang.brigadier.suggestion.SuggestionsBuilder("/p ", 3);
        server.suggest("Alice");
        server.suggest("Zed");
        Suggestions merged = FriendNameCompletion.merge("/p ", 3, server.build(), FRIENDS);
        assertEquals(List.of("Alice", "Bob_2", "Zed", "alfred"), texts(merged));
    }

    @Test
    public void whereNoNameIsTypedTheSuggestionsAreUntouched() {
        Suggestions original = Suggestions.empty().join();
        assertSame(original, FriendNameCompletion.merge("/chat ", 6, original, FRIENDS));
        assertSame(original, FriendNameCompletion.merge("hello", 5, original, FRIENDS));
    }

    @Test
    public void nothingTypedMatchesNoFriendAndAddsNothing() {
        Suggestions merged = FriendNameCompletion.merge("/msg zz", 7, Suggestions.empty().join(), FRIENDS);
        assertTrue(merged.isEmpty());
    }
}
