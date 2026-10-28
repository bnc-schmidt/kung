package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.ChatCommandsFeature.ChatChannel;
import com.github.beng420.kung.skyblock.SpecialMayors;
import com.github.beng420.kung.skyblock.ZodiacEvents;
import com.github.beng420.kung.util.CatacombsAverageCalculator;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.Goal;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.KungTime;
import com.github.beng420.kung.util.ServerTpsTracker;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

enum ChatCommand {
    // A goal makes the command take a level and an optional player: "!c67 [name]" runs left for Catacombs 67,
    // "!ca60 [name]" for class average 60.
    C(Goal.CATACOMBS), CA(Goal.CLASS_AVERAGE), TPS(null),
    // More names for one command: add them after the goal, e.g. SCORPIUS answers to !scorp and !scorpius.
    JERRY(null, "jerry"), SCORPIUS(null, "scorp", "scorpius"), DERPY(null, "derpy"),
    // The zodiac events: Year of the Pig, Witch and Seal.
    YOTP(null, "yotp"), YOTW(null, "yotw"), YOTS(null, "yots"),
    // "!ca60 [name] -arch": class average 60 playing only that class. It uses the !ca settings.
    CA_ARCHER(Goal.CLASS_AVERAGE, DungeonClass.ARCHER, "ca"), CA_BERSERK(Goal.CLASS_AVERAGE, DungeonClass.BERSERK, "ca"),
    CA_HEALER(Goal.CLASS_AVERAGE, DungeonClass.HEALER, "ca"), CA_MAGE(Goal.CLASS_AVERAGE, DungeonClass.MAGE, "ca"),
    CA_TANK(Goal.CLASS_AVERAGE, DungeonClass.TANK, "ca"),
    // "!a50 [name]": runs that class still needs for that level (!b !h !m !t for the others).
    A(Goal.CLASS_LEVEL, DungeonClass.ARCHER), B(Goal.CLASS_LEVEL, DungeonClass.BERSERK),
    H(Goal.CLASS_LEVEL, DungeonClass.HEALER), M(Goal.CLASS_LEVEL, DungeonClass.MAGE),
    T(Goal.CLASS_LEVEL, DungeonClass.TANK);

    /** Levels 1 to 200 without leading zeros; a longer number is no command. */
    private static final String LEVEL = "(?<level>[1-9]|[1-9][0-9]|1[0-9]{2}|200)";
    private static final CatacombsAverageCalculator CALCULATOR = new CatacombsAverageCalculator();
    private final Goal goal;
    private final DungeonClass dungeonClass;
    final Pattern pattern;

    ChatCommand(Goal goal, String... names) {
        this(goal, null, names);
    }

    ChatCommand(Goal goal, DungeonClass dungeonClass, String... names) {
        this.goal = goal;
        this.dungeonClass = dungeonClass;
        String triggers = names.length == 0 ? name().toLowerCase(Locale.ROOT) : String.join("|", names);
        // Only the !ca variants take a "-class" flag, after the optional name.
        String classFlag = goal == Goal.CLASS_AVERAGE && dungeonClass != null
            ? "\\s+-(?:" + classFlags(dungeonClass) + ")" : "";
        pattern = Pattern.compile("^!(?:" + triggers + ")"
            + (goal == null ? "" : LEVEL + "(?:\\s+(?<name>[A-Za-z0-9_]{1,16}))?") + classFlag + "\\s*$", Pattern.CASE_INSENSITIVE);
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

    String target(Matcher match) { return goal == null ? null : match.group("name"); }

    /** The level the chat line asks for; 0 for commands without one. */
    int level(Matcher match) { return goal == null ? 0 : Integer.parseInt(match.group("level")); }

    DungeonClass dungeonClass() { return dungeonClass; }

    private String what(int level) {
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
        if (!config.chatCommandsEnabled()) return false;
        // The two groups have a switch of their own above their commands.
        if (isMayorCommand() && !config.specialMayorsChatCommandsEnabled()) return false;
        if (zodiac() != null && !config.yearlyEventsChatCommandsEnabled()) return false;
        return switch (this) {
            case C -> config.c50ChatCommandEnabled() && switch (channel) {
                case PARTY -> config.c50PartyCommandsEnabled();
                case GUILD -> config.c50GuildCommandsEnabled();
                case ALL -> config.c50AllChatCommandsEnabled();
                case PRIVATE -> config.c50PrivateCommandsEnabled();
            };
            case CA, CA_ARCHER, CA_BERSERK, CA_HEALER, CA_MAGE, CA_TANK -> config.ca50ChatCommandEnabled() && switch (channel) {
                case PARTY -> config.ca50PartyCommandsEnabled();
                case GUILD -> config.ca50GuildCommandsEnabled();
                case ALL -> config.ca50AllChatCommandsEnabled();
                case PRIVATE -> config.ca50PrivateCommandsEnabled();
            };
            case TPS -> config.tpsChatCommandEnabled() && switch (channel) {
                case PARTY -> config.tpsPartyCommandsEnabled();
                case GUILD -> config.tpsGuildCommandsEnabled();
                case ALL -> config.tpsAllChatCommandsEnabled();
                case PRIVATE -> config.tpsPrivateCommandsEnabled();
            };
            case JERRY -> config.jerryChatCommandEnabled() && switch (channel) {
                case PARTY -> config.jerryPartyCommandsEnabled();
                case GUILD -> config.jerryGuildCommandsEnabled();
                case ALL -> config.jerryAllChatCommandsEnabled();
                case PRIVATE -> config.jerryPrivateCommandsEnabled();
            };
            case SCORPIUS -> config.scorpChatCommandEnabled() && switch (channel) {
                case PARTY -> config.scorpPartyCommandsEnabled();
                case GUILD -> config.scorpGuildCommandsEnabled();
                case ALL -> config.scorpAllChatCommandsEnabled();
                case PRIVATE -> config.scorpPrivateCommandsEnabled();
            };
            case DERPY -> config.derpyChatCommandEnabled() && switch (channel) {
                case PARTY -> config.derpyPartyCommandsEnabled();
                case GUILD -> config.derpyGuildCommandsEnabled();
                case ALL -> config.derpyAllChatCommandsEnabled();
                case PRIVATE -> config.derpyPrivateCommandsEnabled();
            };
            case YOTP -> config.yotpChatCommandEnabled() && switch (channel) {
                case PARTY -> config.yotpPartyCommandsEnabled();
                case GUILD -> config.yotpGuildCommandsEnabled();
                case ALL -> config.yotpAllChatCommandsEnabled();
                case PRIVATE -> config.yotpPrivateCommandsEnabled();
            };
            case YOTW -> config.yotwChatCommandEnabled() && switch (channel) {
                case PARTY -> config.yotwPartyCommandsEnabled();
                case GUILD -> config.yotwGuildCommandsEnabled();
                case ALL -> config.yotwAllChatCommandsEnabled();
                case PRIVATE -> config.yotwPrivateCommandsEnabled();
            };
            case YOTS -> config.yotsChatCommandEnabled() && switch (channel) {
                case PARTY -> config.yotsPartyCommandsEnabled();
                case GUILD -> config.yotsGuildCommandsEnabled();
                case ALL -> config.yotsAllChatCommandsEnabled();
                case PRIVATE -> config.yotsPrivateCommandsEnabled();
            };
            case A, B, H, M, T -> config.classLevelChatCommandEnabled() && switch (channel) {
                case PARTY -> config.classLevelPartyCommandsEnabled();
                case GUILD -> config.classLevelGuildCommandsEnabled();
                case ALL -> config.classLevelAllChatCommandsEnabled();
                case PRIVATE -> config.classLevelPrivateCommandsEnabled();
            };
        };
    }

    void execute(Minecraft client, ChatChannel channel, String targetName, int level, Consumer<String> responseConsumer) {
        if (this == TPS) {
            responseConsumer.accept(ServerTpsTracker.INSTANCE.snapshot().message());
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
        CALCULATOR.calculateAsync(targetName, goal, level, dungeonClass, false).whenComplete((result, throwable) -> client.execute(() -> {
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
