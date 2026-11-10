package com.github.beng420.kung.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adds the player's friends to the tab suggestions of the slash commands that take a player name. The server's own
 * suggestions stay; the friends come on top, so {@code /p <Tab>} lists them whatever Hypixel answers.
 *
 * <p>Two sources say where a name goes: a list of Hypixel's commands (which the server does not describe to the
 * client) and the command tree itself, where a text argument called "player" or the like is a name. The tree covers
 * the commands that mods add, such as {@code /pv}.
 */
public final class FriendNameCompletion {
    /** Names a mod gives the text argument that takes a player; lower case. */
    private static final Set<String> PLAYER_ARGUMENTS =
        Set.of("player", "playername", "player_name", "username", "user", "ign", "friend");

    private FriendNameCompletion() {
    }

    /**
     * {@link #nameStart(String, int)}, and where that finds no name, the start of a text argument the command tree
     * calls a player. -1 when the word at the cursor is no name.
     */
    public static <S> int nameStart(ParseResults<S> parse, int cursor) {
        int listed = nameStart(parse.getReader().getString(), cursor);
        if (listed >= 0) return listed;
        SuggestionContext<S> context = parse.getContext().findSuggestionContext(cursor);
        for (CommandNode<S> child : context.parent.getChildren()) {
            if (child instanceof ArgumentCommandNode<S, ?> argument && argument.getType() instanceof StringArgumentType
                && PLAYER_ARGUMENTS.contains(argument.getName().toLowerCase(Locale.ROOT))) {
                return Math.min(context.startPos, cursor);
            }
        }
        return -1;
    }

    /**
     * The index in {@code input} where the player name being typed starts, or -1 when the word at the cursor is no
     * name: {@code /msg}, {@code /w}, {@code /tell}, {@code /whisper}, {@code /visit}, {@code /pv}, {@code /trade},
     * {@code /ah}, {@code /duel} and {@code /report} take one first; {@code /p} and {@code /party} take one first (the
     * invite), after {@code invite}, and after {@code accept}; {@code /f remove} and {@code /f best},
     * {@code /ignore add} and {@code /g invite} take one.
     */
    public static int nameStart(String input, int cursor) {
        String typed = input.substring(0, Math.min(cursor, input.length()));
        if (!typed.startsWith("/")) return -1;
        String[] words = typed.substring(1).split(" ", -1);
        // Still typing the command itself.
        if (words.length < 2) return -1;
        int argument = words.length - 2;
        String first = argument > 0 ? words[1].toLowerCase(Locale.ROOT) : "";
        boolean name = switch (words[0].toLowerCase(Locale.ROOT)) {
            case "msg", "w", "tell", "whisper", "visit", "pv", "trade", "ah", "duel", "report" -> argument == 0;
            case "p", "party" -> argument == 0 || first.equals("invite") || first.equals("accept") && argument == 1;
            case "f", "friend" -> argument == 1 && (first.equals("remove") || first.equals("best"));
            case "ignore" -> argument == 1 && first.equals("add");
            case "g", "guild" -> argument == 1 && first.equals("invite");
            default -> false;
        };
        return name ? typed.length() - words[words.length - 1].length() : -1;
    }

    /** {@code original} with the friends that start with the typed name; unchanged when the cursor is on no name. */
    public static Suggestions merge(String input, int cursor, Suggestions original, Collection<String> friends) {
        return merge(input, cursor, nameStart(input, cursor), original, friends);
    }

    /** {@code original} with the friends for the name that starts at {@code start}; unchanged when that is -1. */
    public static Suggestions merge(String input, int cursor, int start, Suggestions original,
                                    Collection<String> friends) {
        if (start < 0) return original;
        String typed = input.substring(0, cursor);
        String prefix = typed.substring(start);
        SuggestionsBuilder builder = new SuggestionsBuilder(typed, start);
        for (String friend : friends) {
            if (friend.regionMatches(true, 0, prefix, 0, prefix.length())) builder.suggest(friend);
        }
        return Suggestions.merge(input, List.of(original, builder.build()));
    }
}
