package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.ChatCommandSetting;
import com.github.beng420.kung.config.category.MiscConfig.CommandChannel;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import com.github.beng420.kung.skyblock.SpecialMayors;
import com.github.beng420.kung.update.KungUpdater;
import com.github.beng420.kung.update.KungVersionLookup;
import com.github.beng420.kung.skyblock.ZodiacEvents;
import com.github.beng420.kung.util.CatacombsAverageCalculator;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.Floor;
import com.github.beng420.kung.util.CatacombsAverageCalculator.Goal;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.KungTime;
import com.github.beng420.kung.util.ServerTpsTracker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;

enum ChatCommand {
    // A goal makes the command take a level and an optional player: "!c67 [name]" runs left for Catacombs 67,
    // "!ca60 [name]" for class average 60.
    // "!kung": the installed and the newest GitHub and Modrinth version. Always on, in every channel but All chat.
    KUNG(null, "kung"),
    // "!kung help", "!kunghelp" and "!helpkung": list the commands that answer in that channel right now.
    KUNG_HELP(null, "kung\\s+help", "kunghelp", "helpkung"),
    C(Goal.CATACOMBS), CA(Goal.CLASS_AVERAGE), TPS(null),
    // More names for one command: add them after the goal, e.g. SCORPIUS answers to !scorp and !scorpius.
    JERRY(null, "jerry"), SCORPIUS(null, "scorp", "scorpius"), DERPY(null, "derpy"),
    // The zodiac events: Year of the Pig, Witch and Seal.
    YOTP(null, "yotp"), YOTW(null, "yotw"), YOTS(null, "yots"),
    // "!ca60 [name] -arch -f7": class average 60 playing only that class, on that floor; both flags are optional and
    // go in either order. The variants use the !ca settings.
    CA_ARCHER(Goal.CLASS_AVERAGE, DungeonClass.ARCHER, "ca"), CA_BERSERK(Goal.CLASS_AVERAGE, DungeonClass.BERSERK, "ca"),
    CA_HEALER(Goal.CLASS_AVERAGE, DungeonClass.HEALER, "ca"), CA_MAGE(Goal.CLASS_AVERAGE, DungeonClass.MAGE, "ca"),
    CA_TANK(Goal.CLASS_AVERAGE, DungeonClass.TANK, "ca"),
    // "!a50 [name]": runs that class still needs for that level (!b !h !m !t for the others). Every class also answers
    // to its long names (!arch50, !archer50, ...), the same forms the !ca "-class" flags take. Other mods and Hypixel
    // use !m1-!m7 and !t1-!t5 themselves, so the one-letter mage and tank commands leave those to them.
    A(Goal.CLASS_LEVEL, DungeonClass.ARCHER, "a", "arch", "archer"),
    B(Goal.CLASS_LEVEL, DungeonClass.BERSERK, "b", "bers", "berserk", "berserker"),
    H(Goal.CLASS_LEVEL, DungeonClass.HEALER, "h", "heal", "healer"), M(Goal.CLASS_LEVEL, DungeonClass.MAGE, "m", "mage"),
    T(Goal.CLASS_LEVEL, DungeonClass.TANK, "t", "tank"),
    // "!be barb [-name]": the player's Bestiary kills of the mob the words start ("barb" is Barbarian Duke X); the Kung
    // server finds the mob and answers. "-name" asks about another player; without a mob the answer shows how to type it.
    BESTIARY(true, "bestiary|be");

    /** Any level from 1 up, without leading zeros. */
    private static final String LEVEL = "(?<level>[1-9][0-9]*)";
    /** The floor flags of !ca: f1 to f7, m1 to m7, and e or entrance for the Entrance. */
    private static final String FLOORS = "[fm][1-7]|e(?:ntrance)?";
    /** The SkyBlock profile names, which are the profile flags: "-coconut", "-mango". */
    private static final String PROFILES = "apple|banana|blueberry|coconut|cucumber|grapes|kiwi|lemon|lime|mango|orange|papaya"
        + "|pear|peach|pineapple|pomegranate|raspberry|strawberry|tomato|watermelon|zucchini";
    private static final Pattern PROFILE_NAME = Pattern.compile(PROFILES, Pattern.CASE_INSENSITIVE);
    private final Goal goal;
    private final DungeonClass dungeonClass;
    final Pattern pattern;

    ChatCommand(Goal goal, String... names) {
        this(goal, null, names);
    }

    /**
     * A command with mob words and a player flag after its trigger instead of a level: "!be" and "!bestiary". The
     * flag only keeps this apart from {@code TPS(null)}; it is always true.
     */
    ChatCommand(boolean mobWords, String triggers) {
        this.goal = null;
        this.dungeonClass = null;
        pattern = Pattern.compile("^!(?:" + triggers + ")(?:\\s+(?<mob>[a-z0-9' ]{1,32}?))?"
            + "(?:\\s+-(?<name>[A-Za-z0-9_]{1,16}))?(?:\\s+-(?<profile>" + PROFILES + "))?\\s*$", Pattern.CASE_INSENSITIVE);
    }

    ChatCommand(Goal goal, DungeonClass dungeonClass, String... names) {
        this.goal = goal;
        this.dungeonClass = dungeonClass;
        String[] all = names.length == 0 ? new String[] {name().toLowerCase(Locale.ROOT)} : names;
        // "!m1" to "!m7" alone start the master mode floors for the party, "!t1" to "!t5" the Kuudra tiers: the
        // one-letter mage and tank commands leave them to Hypixel. The long names (the other alternatives) take them.
        String hypixelStart = goal == Goal.CLASS_LEVEL ? switch (dungeonClass) {
            case MAGE -> "(?![1-7]\\s*$)";
            case TANK -> "(?![1-5]\\s*$)";
            default -> "";
        } : "";
        String triggers = all[0] + hypixelStart
            + Arrays.stream(all).skip(1).map(name -> "|" + name).collect(Collectors.joining());
        // Every level command takes flags after the optional name: a "-floor", a "-profile" and for the !ca variants a
        // "-class", each at most once and in any order. !c, !ca and the class level commands take no class; each !ca
        // variant has its class.
        String floorFlag = "-(?:" + FLOORS + ")";
        String profileFlag = "-(?:" + PROFILES + ")";
        String classFlag = dungeonClass == null ? "" : "-(?:" + classFlags(dungeonClass) + ")";
        List<List<String>> orders = new ArrayList<>();
        if (goal != null) {
            for (List<String> order : orderedSubsets(List.of(floorFlag, profileFlag))) {
                if (goal != Goal.CLASS_AVERAGE || dungeonClass == null) {
                    orders.add(order);
                    continue;
                }
                // The class flag goes anywhere between the others.
                for (int at = 0; at <= order.size(); at++) {
                    List<String> withClass = new ArrayList<>(order);
                    withClass.add(at, classFlag);
                    orders.add(withClass);
                }
            }
        }
        String flags = orders.isEmpty() ? "" : "(?<flags>" + orders.stream()
            .map(order -> order.stream().map(flag -> "\\s+" + flag).collect(Collectors.joining()))
            .collect(Collectors.joining("|")) + ")";
        pattern = Pattern.compile("^!(?:" + triggers + ")"
            + (goal == null ? "" : LEVEL + "(?:\\s+(?<name>[A-Za-z0-9_]{1,16}))?") + flags + "\\s*$", Pattern.CASE_INSENSITIVE);
    }

    /** Every way to pick some of the items and put them in a row, each at most once, the empty row included. */
    private static List<List<String>> orderedSubsets(List<String> items) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of());
        for (String item : items) {
            List<String> rest = new ArrayList<>(items);
            rest.remove(item);
            for (List<String> tail : orderedSubsets(rest)) {
                List<String> row = new ArrayList<>();
                row.add(item);
                row.addAll(tail);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Short, long and one-letter form of each class. */
    private static String classFlags(DungeonClass dungeonClass) {
        return switch (dungeonClass) {
            case ARCHER -> "a|arch|archer";
            case BERSERK -> "b|bers|berserk|berserker";
            case HEALER -> "h|heal|healer";
            case MAGE -> "m|mage";
            case TANK -> "t|tank";
        };
    }

    /**
     * The player a line asks about, or null for the sender. Only {@code !be} has a player flag that can be taken for a
     * profile: "-coconut" alone is the profile, and "-coconut -coconut" the player Coconut on that profile.
     */
    String target(Matcher match) {
        if (goal == null && this != BESTIARY) return null;
        String name = match.group("name");
        return this == BESTIARY && name != null && match.group("profile") == null && PROFILE_NAME.matcher(name).matches() ? null : name;
    }

    /** The SkyBlock profile a line names with "-coconut" and the like ("Coconut"), or null for the selected one. */
    String profile(Matcher match) {
        if (goal == null && this != BESTIARY) return null;
        String word = null;
        if (this == BESTIARY) {
            word = match.group("profile");
            String name = match.group("name");
            if (word == null && name != null && PROFILE_NAME.matcher(name).matches()) word = name;
        } else {
            for (String flag : match.group("flags").trim().split("\\s+")) {
                if (flag.length() > 1 && PROFILE_NAME.matcher(flag.substring(1)).matches()) word = flag.substring(1);
            }
        }
        return word == null ? null : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The mob words of a !bestiary line, or null for none (and for every other command). */
    String mob(Matcher match) { return this == BESTIARY ? match.group("mob") : null; }

    /** The level the chat line asks for; 0 for commands without one. A number too big for a long reads as the largest one. */
    long level(Matcher match) {
        if (goal == null) return 0L;
        try {
            return Long.parseLong(match.group("level"));
        } catch (NumberFormatException tooBig) {
            return Long.MAX_VALUE;
        }
    }

    /** The floor a level command line names with "-f7", "-m3" and the like, or null for none (and for every other command). */
    Floor floor(Matcher match) {
        if (goal == null) return null;
        for (String flag : match.group("flags").trim().split("\\s+")) {
            Floor floor = flag.length() < 2 ? null : Floor.of(flag.substring(1));
            if (floor != null) return floor;
        }
        return null;
    }

    DungeonClass dungeonClass() { return dungeonClass; }

    private String what(long level) {
        return (goal == Goal.CLASS_LEVEL ? dungeonClass.label() + " " : goal.id().toUpperCase(Locale.ROOT)) + level;
    }

    private boolean isMayorCommand() { return this == JERRY || this == SCORPIUS || this == DERPY; }

    private ZodiacEvents.Event zodiac() {
        return switch (this) {
            case YOTP -> ZodiacEvents.Event.PIG;
            case YOTW -> ZodiacEvents.Event.WITCH;
            case YOTS -> ZodiacEvents.Event.SEAL;
            default -> null;
        };
    }

    private SpecialMayors.Special special() {
        return switch (this) {
            case SCORPIUS -> SpecialMayors.Special.SCORPIUS;
            case DERPY -> SpecialMayors.Special.DERPY;
            default -> SpecialMayors.Special.JERRY;
        };
    }

    boolean isEnabled(MiscConfig config, ChatChannel channel) {
        // The version line has no switch: a party may always ask which Kung this is. Not in All chat, where a
        // stranger could make the client talk to the whole lobby.
        if (this == KUNG) return channel != ChatChannel.ALL;
        if (!config.chatCommandsEnabled()) return false;
        // The help answers only where there is something to list.
        if (this == KUNG_HELP) return !helpMessage(config, channel).isEmpty();
        // The two groups have a switch of their own above their commands.
        if (isMayorCommand() && !config.specialMayorsChatCommandsEnabled()) return false;
        if (zodiac() != null && !config.yearlyEventsChatCommandsEnabled()) return false;
        ChatCommandSetting setting = setting();
        return config.commandEnabled(setting) && config.channelEnabled(setting, switch (channel) {
            case PARTY -> CommandChannel.PARTY;
            case GUILD -> CommandChannel.GUILD;
            case ALL -> CommandChannel.ALL_CHAT;
            case PRIVATE -> CommandChannel.PRIVATE;
            case COOP -> CommandChannel.COOP;
        });
    }

    /** Which settings switch the command: the !ca variants share the !ca50 ones, the class commands their own. */
    private ChatCommandSetting setting() {
        return switch (this) {
            case C -> ChatCommandSetting.C50;
            case CA, CA_ARCHER, CA_BERSERK, CA_HEALER, CA_MAGE, CA_TANK -> ChatCommandSetting.CA50;
            case TPS -> ChatCommandSetting.TPS;
            case JERRY -> ChatCommandSetting.JERRY;
            case SCORPIUS -> ChatCommandSetting.SCORP;
            case DERPY -> ChatCommandSetting.DERPY;
            case YOTP -> ChatCommandSetting.YOTP;
            case YOTW -> ChatCommandSetting.YOTW;
            case YOTS -> ChatCommandSetting.YOTS;
            case BESTIARY -> ChatCommandSetting.BESTIARY;
            case A, B, H, M, T -> ChatCommandSetting.CLASS_LEVEL;
            case KUNG, KUNG_HELP -> throw new IllegalStateException(name() + " has no switch of its own.");
        };
    }

    /** How the command is typed in the help, or null for the help itself. The variants of one command share a line. */
    private String usage() {
        return switch (this) {
            case KUNG, KUNG_HELP -> null;
            case C -> "!c<lvl> [name] [-floor] [-profile]";
            case CA, CA_ARCHER, CA_BERSERK, CA_HEALER, CA_MAGE, CA_TANK -> "!ca<lvl> [name] [-class] [-floor] [-profile]";
            case A, B, H, M, T -> "!a/b/h/m/t<lvl> [name] [-floor] [-profile]";
            case BESTIARY -> "!be <mob> [-name] [-profile]";
            case SCORPIUS -> "!scorp";
            default -> "!" + name().toLowerCase(Locale.ROOT);
        };
    }

    /** The commands that answer in the channel with the current settings, or "" when none does. */
    static String helpMessage(MiscConfig config, ChatChannel channel) {
        List<String> usages = new ArrayList<>();
        for (ChatCommand command : values()) {
            String usage = command.usage();
            if (usage != null && !usages.contains(usage) && command.isEnabled(config, channel)) usages.add(usage);
        }
        return usages.isEmpty() ? "" : "Kung commands: " + String.join(", ", usages);
    }

    void execute(Minecraft client, ChatChannel channel, String targetName, long level, Floor floor, Consumer<String> responseConsumer) {
        execute(client, channel, targetName, level, floor, null, responseConsumer);
    }

    void execute(Minecraft client, ChatChannel channel, String targetName, long level, Floor floor, String mob,
                 Consumer<String> responseConsumer) {
        execute(client, channel, targetName, level, floor, null, mob, responseConsumer);
    }

    /**
     * {@code mob} is the typed mob words of a !bestiary line (see {@link #mob}); {@code profile} the SkyBlock profile
     * the line names (see {@link #profile}), null for the player's selected one.
     */
    void execute(Minecraft client, ChatChannel channel, String targetName, long level, Floor floor, String profile, String mob,
                 Consumer<String> responseConsumer) {
        if (this == KUNG) {
            KungVersionLookup.INSTANCE.message().whenComplete((line, error) -> client.execute(() -> responseConsumer.accept(
                error == null ? line : "Kung v" + KungUpdater.currentVersion() + " | could not look up the newest versions.")));
            return;
        }
        if (this == KUNG_HELP) {
            responseConsumer.accept(helpMessage(com.github.beng420.kung.config.KungConfig.get().misc, channel));
            return;
        }
        if (this == TPS) {
            responseConsumer.accept(ServerTpsTracker.INSTANCE.snapshot().message());
            return;
        }
        if (this == BESTIARY) {
            if (mob == null || mob.isBlank()) {
                responseConsumer.accept("Type a mob name: !be barb, or !be barb -name for another player.");
                return;
            }
            HypixelSkyBlockProfileClient.INSTANCE.loadBestiaryAnswer(targetName, mob.trim(), profile).whenComplete((line, error) -> client.execute(() -> {
                KungDebugRecorder.event("bestiary", "channel=" + channel.name() + " target=" + targetName + " mob=" + mob.trim()
                    + " answer=" + (error == null ? line : "failed " + error));
                responseConsumer.accept(error == null ? line : "Could not look up the Bestiary of " + targetName + ".");
            }));
            return;
        }
        if (zodiac() != null) {
            responseConsumer.accept(ZodiacEvents.answer(zodiac(), System.currentTimeMillis(), KungTime.zone()));
            return;
        }
        if (isMayorCommand()) {
            responseConsumer.accept(SpecialMayors.answer(special(), System.currentTimeMillis(), KungTime.zone()));
            return;
        }
        CatacombsAverageCalculator.calculateAsync(targetName, goal, level, dungeonClass, floor, profile, false).whenComplete((result, throwable) -> client.execute(() -> {
            if (throwable != null) {
                KungMod.LOGGER.warn("Failed to answer !" + name().toLowerCase(Locale.ROOT) + " command.", throwable);
                responseConsumer.accept("Could not calculate " + what(level) + " for " + targetName + ".");
                return;
            }
            if (result.debugDetails() != null && !result.debugDetails().isBlank()) {
                KungDebugRecorder.event("cata-result", "command=" + name().toLowerCase(Locale.ROOT) + " channel=" + channel.name() + " target=" + targetName + " level=" + level
                    + " success=" + result.success() + " " + result.debugDetails());
            }
            if (!result.success()) {
                responseConsumer.accept("Could not calculate " + what(level) + " for " + targetName + ": " + result.message());
                return;
            }
            responseConsumer.accept(result.message());
        }));
    }
}
