package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.WaypointsScreen;
import com.github.beng420.kung.feature.waypoints.WaypointEditor;
import com.github.beng420.kung.feature.waypoints.WaypointGroup;
import com.github.beng420.kung.feature.waypoints.WaypointIsland;
import com.github.beng420.kung.feature.waypoints.WaypointRoute;
import com.github.beng420.kung.feature.waypoints.WaypointStore;
import com.github.beng420.kung.feature.waypoints.WaypointType;
import com.github.beng420.kung.feature.waypoints.WaypointsFeature;
import com.github.beng420.kung.message.KungMessages;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /kung waypoints} opens the editor screen. The subcommands do the same edits in chat: list, add, edit and
 * remove the groups and waypoints of the island being edited, move a route, and share groups as a Skyblocker string.
 * The edits themselves are {@link WaypointEditor}'s.
 */
final class WaypointCommandGroup {
    private static final WaypointEditor EDITOR = new WaypointEditor(WaypointStore.INSTANCE);

    private WaypointCommandGroup() {
    }

    private interface Action {
        String run(WaypointIsland current);
    }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        root.then(literal("waypoints")
            .executes(context -> openEditor(context))
            .then(literal("list").executes(context -> list(context)))
            .then(literal("island")
                .executes(context -> run(context, current -> "Editing " + EDITOR.island(current).name()
                    + ". Change it with here, everywhere, an area name, or one of these ids: "
                    + WaypointIsland.all().stream().map(WaypointIsland::id).collect(Collectors.joining(", ")) + "."))
                .then(argument("island", StringArgumentType.greedyString()).executes(context ->
                    run(context, current -> EDITOR.chooseIsland(StringArgumentType.getString(context, "island"), current)))))
            .then(group())
            .then(literal("add")
                .then(groupNumber().executes(context -> run(context, current ->
                        EDITOR.addWaypoint(number(context, "group"), "", target(context), current)))
                    .then(argument("name", StringArgumentType.greedyString()).executes(context -> run(context, current ->
                        EDITOR.addWaypoint(number(context, "group"), StringArgumentType.getString(context, "name"),
                            target(context), current))))))
            .then(literal("remove")
                .then(groupNumber().then(waypointNumber().executes(context -> run(context, current ->
                    EDITOR.removeWaypoint(number(context, "group"), number(context, "waypoint"), current))))))
            .then(literal("move")
                .then(groupNumber().then(waypointNumber()
                    .then(literal("up").executes(context -> run(context, current -> EDITOR.moveWaypoint(
                        number(context, "group"), number(context, "waypoint"), -1, current))))
                    .then(literal("down").executes(context -> run(context, current -> EDITOR.moveWaypoint(
                        number(context, "group"), number(context, "waypoint"), 1, current)))))))
            .then(edit())
            .then(literal("ordered")
                .then(route("next", 1))
                .then(route("previous", -1))
                .then(route("first", 0))
                .then(route("reset", 0)))
            .then(literal("export")
                .executes(context -> export(context, 0))
                .then(groupNumber().executes(context -> export(context, number(context, "group")))))
            .then(literal("import")
                .executes(context -> importClipboard(context, false))
                .then(literal("here").executes(context -> importClipboard(context, true)))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> group() {
        return literal("group")
            .then(literal("new").then(argument("name", StringArgumentType.greedyString()).executes(context -> run(context,
                current -> EDITOR.newGroup(StringArgumentType.getString(context, "name"),
                    KungConfig.get().waypoints.defaultType(), current)))))
            .then(literal("remove").then(groupNumber().executes(context -> run(context, current ->
                EDITOR.removeGroup(number(context, "group"), current)))))
            .then(literal("rename").then(groupNumber().then(argument("name", StringArgumentType.greedyString())
                .executes(context -> run(context, current -> EDITOR.renameGroup(number(context, "group"),
                    StringArgumentType.getString(context, "name"), current))))))
            .then(literal("route").then(groupNumber().then(argument("on", BoolArgumentType.bool()).executes(context ->
                run(context, current -> EDITOR.setOrdered(number(context, "group"), BoolArgumentType.getBool(context, "on"), current))))))
            .then(literal("walls").then(groupNumber().then(argument("on", BoolArgumentType.bool()).executes(context ->
                run(context, current -> EDITOR.setThroughWalls(number(context, "group"), BoolArgumentType.getBool(context, "on"), current))))))
            .then(literal("type").then(groupNumber().then(argument("type", StringArgumentType.word()).executes(context ->
                run(context, current -> EDITOR.setType(number(context, "group"), type(StringArgumentType.getString(context, "type")), current))))))
            .then(literal("toggle").then(groupNumber().executes(context -> run(context, current ->
                EDITOR.toggleGroup(number(context, "group"), current)))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> edit() {
        return literal("edit").then(groupNumber().then(waypointNumber()
            .then(literal("name").then(argument("name", StringArgumentType.greedyString()).executes(context -> run(context,
                current -> EDITOR.renameWaypoint(number(context, "group"), number(context, "waypoint"),
                    StringArgumentType.getString(context, "name"), current)))))
            .then(literal("pos")
                .executes(context -> run(context, current -> EDITOR.moveWaypointTo(number(context, "group"),
                    number(context, "waypoint"), target(context), current)))
                .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
                    .then(argument("z", IntegerArgumentType.integer()).executes(context -> run(context, current ->
                        EDITOR.moveWaypointTo(number(context, "group"), number(context, "waypoint"),
                            new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                                IntegerArgumentType.getInteger(context, "z")), current)))))))
            .then(literal("color").then(argument("hex", StringArgumentType.word()).executes(context -> run(context, current ->
                EDITOR.colorWaypoint(number(context, "group"), number(context, "waypoint"),
                    StringArgumentType.getString(context, "hex"), current)))))
            .then(literal("alpha").then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(context -> run(context,
                current -> EDITOR.alphaWaypoint(number(context, "group"), number(context, "waypoint"),
                    IntegerArgumentType.getInteger(context, "percent"), current)))))
            .then(literal("toggle").executes(context -> run(context, current ->
                EDITOR.toggleWaypoint(number(context, "group"), number(context, "waypoint"), current))))));
    }

    /** Moves the route of this island by {@code delta} waypoints, or back to the first one when {@code delta} is 0. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> route(String name, int delta) {
        return literal(name).executes(context -> {
            WaypointGroup route = WaypointsFeature.INSTANCE.activeRoute();
            if (route == null) {
                context.getSource().sendFeedback(KungMessages.error("Waypoints",
                    "No route is on here. A route is a group set to route with all its waypoints on."));
                return 0;
            }
            if (delta == 0) route.resetRoute();
            else route.setCurrentIndex(WaypointRoute.step(route.currentIndex(), route.waypoints().size(), delta));
            context.getSource().sendFeedback(KungMessages.info("Waypoints",
                "Route \"" + route.name() + "\": waypoint " + (route.currentIndex() + 1) + " of " + route.waypoints().size() + "."));
            return 1;
        });
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> groupNumber() {
        return argument("group", IntegerArgumentType.integer(1));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> waypointNumber() {
        return argument("waypoint", IntegerArgumentType.integer(1));
    }

    private static int number(CommandContext<FabricClientCommandSource> context, String name) {
        return IntegerArgumentType.getInteger(context, name);
    }

    private static WaypointType type(String name) {
        WaypointType type = WaypointType.parse(name, null);
        if (type == null) {
            throw new IllegalArgumentException("Types: " + Arrays.stream(WaypointType.values())
                .map(WaypointType::serialName).collect(Collectors.joining(", ")) + ".");
        }
        return type;
    }

    /** The block the player looks at, else the one they stand in. */
    private static BlockPos target(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return client.player.blockPosition();
    }

    private static int openEditor(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        // Chat finishes handling the command before the new screen is opened.
        client.schedule(() -> McCompat.setScreen(client, new WaypointsScreen(null)));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        for (String line : EDITOR.list(WaypointsFeature.INSTANCE.island())) {
            context.getSource().sendFeedback(KungMessages.info("Waypoints", line));
        }
        return 1;
    }

    private static int export(CommandContext<FabricClientCommandSource> context, int group) {
        return run(context, current -> {
            String text = EDITOR.export(group, current);
            context.getSource().getClient().keyboardHandler.setClipboard(text);
            return "Copied to the clipboard (" + text.length() + " characters).";
        });
    }

    private static int importClipboard(CommandContext<FabricClientCommandSource> context, boolean here) {
        return run(context, current -> EDITOR.importText(context.getSource().getClient().keyboardHandler.getClipboard(), here, current));
    }

    private static int run(CommandContext<FabricClientCommandSource> context, Action action) {
        try {
            String message = action.run(WaypointsFeature.INSTANCE.island());
            if (!KungConfig.get().waypoints.enabled()) message += " Waypoints is off: switch it on in /kung settings.";
            context.getSource().sendFeedback(KungMessages.info("Waypoints", message));
            return 1;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFeedback(KungMessages.error("Waypoints", exception.getMessage()));
            return 0;
        }
    }
}
