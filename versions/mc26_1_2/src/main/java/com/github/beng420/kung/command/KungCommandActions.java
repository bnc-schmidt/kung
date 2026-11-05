package com.github.beng420.kung.command;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungConfigScreen;
import com.github.beng420.kung.config.SplitPersonalBestsScreen;
import com.github.beng420.kung.feature.dungeon.CatacombsCalculatorScreen;
import com.github.beng420.kung.feature.dungeon.DungeonDoorKind;
import com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog;
import com.github.beng420.kung.feature.dungeon.DungeonRoomDataSyncClient;
import com.github.beng420.kung.feature.dungeon.DungeonRoomClassifier;
import com.github.beng420.kung.feature.dungeon.DungeonRoomProject;
import com.github.beng420.kung.feature.dungeon.DungeonScanUtils;
import com.github.beng420.kung.feature.dungeon.KungServerSession;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.feature.garden.GreenhouseDump;
import com.github.beng420.kung.feature.garden.GreenhouseHologram;
import com.github.beng420.kung.feature.garden.GreenhouseScreen;
import com.github.beng420.kung.feature.garden.VisitorAlarmFeature;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.JerryPerkpocalypse;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.update.KungUpdater;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class KungCommandActions {
    private KungCommandActions() {
    }

    /** Which room a learn command targets: the one the player stands in (one cell or the whole multi-room) or the one looked at. */
    enum LearnMode { HERE, MULTI, LOOKED }

    /** Sends the line to the player and returns the command result: 1 when {@code ok}, else 0. */
    static int reply(CommandContext<FabricClientCommandSource> context, Component message, boolean ok) {
        context.getSource().sendFeedback(message);
        return ok ? 1 : 0;
    }

    /** {@link #reply(CommandContext, Component, boolean)} with a plain [Kung] line. */
    static int reply(CommandContext<FabricClientCommandSource> context, String text, boolean ok) {
        return reply(context, KungMessages.info(text), ok);
    }

    /** Sends a [Kung area] line and returns 1. */
    static int info(CommandContext<FabricClientCommandSource> context, String area, String text) {
        return reply(context, KungMessages.info(area, text), true);
    }

    static int roomProjectStatus(CommandContext<FabricClientCommandSource> context) {
        String project = KungConfig.get().dungeon.roomDataProjectDirectory();
        return reply(context, project.isBlank()
            ? "Room project: off. Link with /kung room project <project folder>."
            : "Room project: " + project + ". Room changes are saved directly to this project.", true);
    }

    static int setRoomProject(CommandContext<FabricClientCommandSource> context) {
        String input = StringArgumentType.getString(context, "path").trim();
        if (input.length() >= 2 && input.startsWith("\"") && input.endsWith("\"")) {
            input = input.substring(1, input.length() - 1);
        }
        try {
            Path project = DungeonRoomProject.validateProject(input);
            KungConfig.get().dungeon.setRoomDataProjectDirectory(project.toString());
            DungeonKnownRoomCatalog.reload();
            DungeonRoomClassifier.reload();
            return reply(context,
                "Room project linked: " + project
                    + ". Learned rooms, hashes and metadata changes are now saved directly to the project.",
                true);
        } catch (IOException | IllegalArgumentException exception) {
            return reply(context, "Could not link room project: " + exception.getMessage(), false);
        }
    }

    static int disableRoomProject(CommandContext<FabricClientCommandSource> context) {
        KungConfig.get().dungeon.setRoomDataProjectDirectory("");
        DungeonKnownRoomCatalog.reload();
        DungeonRoomClassifier.reload();
        return reply(context, "Room project disabled. Room changes now stay in this game profile.", true);
    }

    /** Reports a room data change; a saved one adds a note when a linked project took it. */
    private static int roomWrite(CommandContext<FabricClientCommandSource> context, String message, boolean saved) {
        return reply(context, message + (saved && DungeonRoomProject.enabled() ? " Saved to the linked project." : ""), saved);
    }

    static int learnRoomType(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        RoomType roomType = parseRoomType(StringArgumentType.getString(context, "type"));
        if (roomType == null) {
            return reply(context, "Unknown room type. Use: " + String.join(", ", roomTypeNames()), false);
        }

        DungeonStateTracker.LearnRoomResult result =
            dungeonStateTracker.learnCurrentRoomType(context.getSource().getClient(), roomType);

        return roomWrite(context, result.message(), result.learned());
    }

    static int copyRoomData(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        DungeonStateTracker.RoomDataResult result = dungeonStateTracker.currentRoomData(client);
        if (result.found()) {
            client.keyboardHandler.setClipboard(result.clipboardText());
        }
        return reply(context, result.message(), result.found());
    }

    static int learnRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        LearnMode mode
    ) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        Minecraft client = context.getSource().getClient();
        BlockPos roomPosition = mode == LearnMode.LOOKED
            ? lookedRoomPosition(context, client)
            : context.getSource().getPlayer().blockPosition();
        if (roomPosition == null) {
            return 0;
        }

        if (!input.hasExplicitMetadata()) {
            input = resolveRoomLearnInput(context, client, roomPosition, input);
            if (input == null) {
                return 0;
            }
        }

        DungeonStateTracker.LearnRoomResult result = switch (mode) {
            case HERE -> dungeonStateTracker.learnCurrentRoom(
                client, input.name(), input.type(), input.secrets(), input.crypts());
            case MULTI -> dungeonStateTracker.learnCurrentMultiRoom(
                client, input.name(), input.type(), input.secrets(), input.crypts());
            case LOOKED -> dungeonStateTracker.learnRoomAt(
                client, roomPosition, input.name(), input.type(), input.secrets(), input.crypts());
        };
        return roomWrite(context, result.message(), result.learned());
    }

    static int deleteRoomInput(CommandContext<FabricClientCommandSource> context) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        try {
            DungeonKnownRoomCatalog.DeleteRoomResult result = input.hasExplicitMetadata()
                ? DungeonKnownRoomCatalog.deleteRoom(input.name(), input.type(), input.secrets())
                : DungeonKnownRoomCatalog.deleteRoom(input.name());
            return roomWrite(context, result.message(), result.deleted());
        } catch (IOException | IllegalArgumentException exception) {
            return reply(context, "Could not delete room data. See latest.log.", false);
        }
    }

    static int exportRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        RoomLearnInput input = knownRoomInput(context, client);
        if (input == null) {
            return 0;
        }

        DungeonStateTracker.RoomDataResult result = exportRoom(dungeonStateTracker, client, input);
        if (result.found()) {
            client.keyboardHandler.setClipboard(result.clipboardText());
        }
        return reply(context, result.message(), result.found());
    }

    static int pushRoomSyncInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        RoomLearnInput input = knownRoomInput(context, client);
        if (input == null) {
            return 0;
        }

        DungeonStateTracker.RoomDataResult result = exportRoom(dungeonStateTracker, client, input);
        if (!result.found()) {
            return reply(context, result.message(), false);
        }

        DungeonRoomDataSyncClient.INSTANCE.pushRoomReportAsync(client, result.clipboardText());
        return reply(context, "Server Sync push started: " + input.name(), true);
    }

    private static DungeonStateTracker.RoomDataResult exportRoom(
        DungeonStateTracker dungeonStateTracker,
        Minecraft client,
        RoomLearnInput input
    ) {
        return dungeonStateTracker.exportCurrentRoom(
            client, input.name(), input.type(), input.secrets(), input.crypts());
    }

    /** The typed input, with a known room's type and secrets when it names none; null after the problem was reported. */
    private static RoomLearnInput knownRoomInput(CommandContext<FabricClientCommandSource> context, Minecraft client) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null || input.hasExplicitMetadata()) {
            return input;
        }
        DungeonKnownRoomCatalog.KnownCoreHint info =
            knownRoomInfo(context, client, context.getSource().getPlayer().blockPosition(), input.name());
        return info == null ? null : RoomLearnInput.of(info);
    }

    static int pullRoomSync(CommandContext<FabricClientCommandSource> context) {
        DungeonRoomDataSyncClient.INSTANCE.pullAsync(context.getSource().getClient());
        return 1;
    }

    static int pingRoomSync(CommandContext<FabricClientCommandSource> context) {
        DungeonRoomDataSyncClient.INSTANCE.pingAsync(context.getSource().getClient());
        return 1;
    }

    static int jerryStatus(CommandContext<FabricClientCommandSource> context) {
        for (var line : JerryPerkpocalypse.INSTANCE.statusMessages(System.currentTimeMillis())) {
            context.getSource().sendFeedback(line);
        }
        return 1;
    }

    static int roomSyncStatus(CommandContext<FabricClientCommandSource> context) {
        KungConfig config = KungConfig.get();
        String url = config.dungeon.roomSyncServerUrl().isBlank() ? "none" : config.dungeon.roomSyncServerUrl();
        return reply(context,
            "Server Sync: enabled=" + config.dungeon.roomSyncEnabled()
                + " upload=" + config.dungeon.roomSyncUploadEnabled()
                + " server=" + url
                + " status=" + DungeonRoomDataSyncClient.INSTANCE.statusMessage()
                + " sign-in=" + KungServerSession.INSTANCE.status(),
            true);
    }

    static int setRoomSyncEnabled(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        KungConfig.get().dungeon.setRoomSyncEnabled(enabled);
        DungeonKnownRoomCatalog.reload();
        return reply(context, "Server Sync " + (enabled ? "enabled for this session." : "disabled."), true);
    }

    static int setRoomSyncUpload(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        KungConfig.get().dungeon.setRoomSyncUploadEnabled(enabled);
        return reply(context, "Server Sync upload " + (enabled ? "enabled for this session." : "disabled."), true);
    }

    static int setRoomSyncServer(CommandContext<FabricClientCommandSource> context) {
        String url = StringArgumentType.getString(context, "url").trim();
        if (url.isEmpty()) {
            return reply(context, "Server Sync URL cannot be empty.", false);
        }
        KungConfig.get().dungeon.setRoomSyncServerUrl(url);
        return reply(context, "Server Sync server saved.", true);
    }

    static RoomLearnInput parseRoomLearnInput(CommandContext<FabricClientCommandSource> context) {
        String input = StringArgumentType.getString(context, "input").trim();
        if (input.isEmpty()) {
            reply(context, "The room name cannot be empty.", false);
            return null;
        }

        String[] parts = input.split("\\s+");
        if (parts.length >= 4) {
            Integer crypts = parseInteger(parts[parts.length - 1]);
            RoomType roomType = parseRoomType(parts[parts.length - 2]);
            Integer secrets = parseInteger(parts[parts.length - 3]);
            if (crypts != null && roomType != null && secrets != null) {
                return explicitRoomInput(context, parts, parts.length - 3, secrets, roomType, crypts);
            }
        }
        if (parts.length >= 3) {
            RoomType roomType = parseRoomType(parts[parts.length - 1]);
            Integer secrets = parseInteger(parts[parts.length - 2]);
            if (roomType != null && secrets != null) {
                return explicitRoomInput(context, parts, parts.length - 2, secrets, roomType, 0);
            }
        }
        if (parts.length >= 2) {
            Integer secrets = parseInteger(parts[parts.length - 1]);
            if (secrets != null) {
                return explicitRoomInput(context, parts, parts.length - 1, secrets, RoomType.NORMAL, 0);
            }
        }

        String name = normalizeRoomNameInput(input);
        if (name.isEmpty()) {
            reply(context, "The room name cannot be empty.", false);
            return null;
        }
        return new RoomLearnInput(name, 0, RoomType.NORMAL, 0, false);
    }

    static String normalizeRoomNameInput(String value) {
        String name = value == null ? "" : value.trim();
        if (name.length() >= 2
            && ((name.startsWith("\"") && name.endsWith("\""))
                || (name.startsWith("'") && name.endsWith("'")))) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return name.replace("\\\"", "\"").replace("\\'", "'");
    }

    static RoomLearnInput explicitRoomInput(
        CommandContext<FabricClientCommandSource> context,
        String[] parts,
        int namePartCount,
        int secrets,
        RoomType roomType,
        int crypts
    ) {
        if (namePartCount <= 0) {
            reply(context, "The room name cannot be empty.", false);
            return null;
        }
        String name = normalizeRoomNameInput(String.join(" ", java.util.Arrays.copyOf(parts, namePartCount)));
        if (name.isEmpty()) {
            reply(context, "The room name cannot be empty.", false);
            return null;
        }
        return new RoomLearnInput(
            name,
            secrets,
            roomType,
            crypts,
            true
        );
    }

    static BlockPos lookedRoomPosition(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client
    ) {
        if (client.level == null || client.player == null) {
            reply(context, "You are not currently in a world.", false);
            return null;
        }

        Direction direction = Direction.fromYRot(client.player.getYRot());
        DungeonScanUtils.GridPosition playerRoom =
            DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        int targetRoomGridX = playerRoom.gridX() + direction.getStepX();
        int targetRoomGridZ = playerRoom.gridZ() + direction.getStepZ();
        if (!DungeonScanUtils.isValidRoomGrid(targetRoomGridX, targetRoomGridZ)) {
            reply(context,
                "No valid dungeon room cell in the direction you are looking. roomGrid="
                    + playerRoom.gridX() + "," + playerRoom.gridZ()
                    + " dir=" + direction.getName(),
                false);
            return null;
        }

        int scanGridX = targetRoomGridX * 2;
        int scanGridZ = targetRoomGridZ * 2;
        return new BlockPos(
            DungeonScanUtils.worldXForScanGrid(scanGridX),
            client.player.blockPosition().getY(),
            DungeonScanUtils.worldZForScanGrid(scanGridZ)
        );
    }

    static int updateCurrentRoomPrince(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        DungeonStateTracker.LearnRoomResult result = dungeonStateTracker.updateCurrentRoomPrince(
            context.getSource().getClient(), BoolArgumentType.getBool(context, "prince")
        );
        return roomWrite(context, result.message(), result.learned());
    }

    static int updateCurrentRoomCrypts(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        DungeonStateTracker.LearnRoomResult result = dungeonStateTracker.updateCurrentRoomCrypts(
            context.getSource().getClient(),
            IntegerArgumentType.getInteger(context, "count")
        );
        return roomWrite(context, result.message(), result.learned());
    }

    /** Export and push: the one known room the name means, told apart by the target room's hash when several share it. */
    static DungeonKnownRoomCatalog.KnownCoreHint knownRoomInfo(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        BlockPos roomPosition,
        String name
    ) {
        List<DungeonKnownRoomCatalog.KnownCoreHint> infos = DungeonKnownRoomCatalog.knownRoomInfos(name);
        if (infos.isEmpty()) {
            reply(context, "Room \"" + name + "\" is not known yet. Use secrets/type when saving it for the first time.", false);
            return null;
        }
        if (infos.size() == 1) {
            return infos.getFirst();
        }

        RoomCore roomCore = hashedRoom(context, client, roomPosition, name);
        if (roomCore == null) {
            return null;
        }
        List<DungeonKnownRoomCatalog.KnownCoreHint> matchingInfos =
            DungeonKnownRoomCatalog.knownRoomInfos(name, roomCore.coreHash(), roomCore.stableCoreHash());
        if (matchingInfos.size() == 1) {
            return matchingInfos.getFirst();
        }
        String targetHash = roomCore.coreHash() + "/" + roomCore.stableCoreHash();
        reply(context, matchingInfos.isEmpty()
            ? "Room \"" + name + "\" is ambiguous. targetHash=" + targetHash
                + " does not match any known variant. Please use secrets/type."
            : "Room \"" + name + "\" remains ambiguous despite targetHash=" + targetHash
                + ". Please use secrets/type.", false);
        return null;
    }

    /** Learn: like {@link #knownRoomInfo}, but an unknown name is saved as a plain NORMAL room with no secrets. */
    static RoomLearnInput resolveRoomLearnInput(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        BlockPos roomPosition,
        RoomLearnInput input
    ) {
        List<DungeonKnownRoomCatalog.KnownCoreHint> infos = DungeonKnownRoomCatalog.knownRoomInfos(input.name());
        if (infos.isEmpty()) {
            reply(context, "Room \"" + input.name() + "\" is not known yet. Saving it as NORMAL with 0 secrets.", true);
            return new RoomLearnInput(input.name(), 0, RoomType.NORMAL, 0, true);
        }
        if (infos.size() == 1) {
            return RoomLearnInput.of(infos.getFirst());
        }

        RoomCore roomCore = hashedRoom(context, client, roomPosition, input.name());
        if (roomCore == null) {
            return null;
        }
        List<DungeonKnownRoomCatalog.KnownCoreHint> matchingInfos =
            DungeonKnownRoomCatalog.knownRoomInfos(input.name(), roomCore.coreHash(), roomCore.stableCoreHash());
        if (matchingInfos.size() == 1) {
            return RoomLearnInput.of(matchingInfos.getFirst());
        }

        reply(context, "Room \"" + input.name() + "\" is ambiguous. Please use secrets/type.", false);
        return null;
    }

    /** The hash of the room at the position; null after reporting that it could not be hashed. */
    private static RoomCore hashedRoom(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        BlockPos roomPosition,
        String name
    ) {
        RoomCore roomCore = roomCoreAt(client, roomPosition);
        if (!roomCore.valid()) {
            reply(context,
                "Room \"" + name + "\" is ambiguous and the target room could not be hashed: "
                    + roomCore.message() + " Please use secrets/type.",
                false);
            return null;
        }
        return roomCore;
    }

    static RoomCore roomCoreAt(Minecraft client, BlockPos position) {
        if (client.level == null || client.player == null) {
            return RoomCore.failed("You are not currently in a world.");
        }

        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(position);
        int scanGridX = roomGrid.gridX() * 2;
        int scanGridZ = roomGrid.gridZ() * 2;
        if (scanGridX < 0
            || scanGridZ < 0
            || scanGridX >= DungeonScanUtils.SCAN_GRID_SIZE
            || scanGridZ >= DungeonScanUtils.SCAN_GRID_SIZE) {
            return RoomCore.failed("The target is outside the known dungeon grid.");
        }

        int worldX = DungeonScanUtils.worldXForScanGrid(scanGridX);
        int worldZ = DungeonScanUtils.worldZForScanGrid(scanGridZ);
        if (!DungeonScanUtils.isChunkLoaded(client.level, worldX, worldZ)) {
            return RoomCore.failed("The center of this room is not loaded yet.");
        }

        int coreHash = DungeonScanUtils.getCoreHash(client.level, worldX, worldZ);
        if (DungeonRoomClassifier.isEmptyCore(coreHash)) {
            return RoomCore.failed("The target room appears empty to the scanner.");
        }

        return RoomCore.valid(coreHash, DungeonScanUtils.getStableCoreHash(client.level, worldX, worldZ));
    }

    static int undoLastRoomLearn(CommandContext<FabricClientCommandSource> context) {
        try {
            DungeonKnownRoomCatalog.UndoResult result = DungeonKnownRoomCatalog.undoLast();
            return roomWrite(context, result.message(), result.undone());
        } catch (IOException exception) {
            return reply(context, "Could not undo the last room entry. See latest.log.", false);
        }
    }

    static int debugRooms(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        for (String line : dungeonStateTracker.debugRoomSummary()) {
            context.getSource().sendFeedback(KungMessages.debug("RoomDebug", line));
        }
        return 1;
    }

    static int debugMapDecorations(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        List<String> lines = dungeonStateTracker.debugMapDecorations(client);
        client.keyboardHandler.setClipboard(String.join("\n", lines));
        for (String line : lines) {
            context.getSource().sendFeedback(KungMessages.debug("MapDebug", line));
        }
        return reply(context, KungMessages.debug("MapDebug", "Copied to clipboard."), true);
    }

    static int copyUltraDebug(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        DungeonStateTracker.RoomDataResult result = dungeonStateTracker.ultraDebug(client);
        client.keyboardHandler.setClipboard(result.clipboardText());
        return reply(context, KungMessages.debug("DungeonDebug", result.message()), result.found());
    }

    static int probeDoorAhead(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        if (client.player == null) {
            return reply(context, "You are not currently in a world.", false);
        }
        return probeDoor(context, Direction.fromYRot(client.player.getYRot()));
    }

    static int probeDoorDirection(CommandContext<FabricClientCommandSource> context) {
        Direction direction = parseDirection(StringArgumentType.getString(context, "direction"));
        if (direction == null) {
            return reply(context, "Unknown direction. Use: " + String.join(", ", directionNames()), false);
        }
        return probeDoor(context, direction);
    }

    static int probeDoor(CommandContext<FabricClientCommandSource> context, Direction direction) {
        Minecraft client = context.getSource().getClient();
        if (client.level == null || client.player == null) {
            return reply(context, "You are not currently in a world.", false);
        }
        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        int doorGridX = roomGrid.gridX() * 2 + direction.getStepX();
        int doorGridZ = roomGrid.gridZ() * 2 + direction.getStepZ();
        if (!DungeonScanUtils.isDoorScanPoint(doorGridX, doorGridZ)) {
            return reply(context,
                "No valid door cell in front of you. roomGrid="
                    + roomGrid.gridX() + "," + roomGrid.gridZ()
                    + " dir=" + direction.getName(),
                false);
        }

        int worldX = DungeonScanUtils.worldXForScanGrid(doorGridX);
        int worldZ = DungeonScanUtils.worldZForScanGrid(doorGridZ);
        DungeonDoorKind doorKind = DungeonScanUtils.detectDoorKind(
            client.level,
            doorGridX,
            doorGridZ,
            worldX,
            worldZ
        );
        DoorProbeSamples samples = sampleDoor(client, worldX, worldZ);
        return reply(context,
            "doorProbe dir=" + direction.getName()
                + " roomGrid=" + roomGrid.gridX() + "," + roomGrid.gridZ()
                + " doorGrid=" + doorGridX + "," + doorGridZ
                + " world=" + worldX + "," + worldZ
                + " kind=" + doorKind.name()
                + " centerPattern=" + samples.centerPattern()
                + " centerY66-74=" + samples.centerBlocks(),
            true);
    }

    static int openSettings(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        client.execute(() -> McCompat.setScreen(client, new KungConfigScreen()));
        return 1;
    }

    static int openSplitPersonalBests(CommandContext<FabricClientCommandSource> context, DungeonStateTracker tracker) {
        Minecraft client = context.getSource().getClient();
        client.schedule(() -> McCompat.setScreen(client, new SplitPersonalBestsScreen(tracker.splitTracker())));
        return 1;
    }

    static int muteVisitorAlarm(CommandContext<FabricClientCommandSource> context) {
        boolean muted = VisitorAlarmFeature.muteCurrentAlarm();
        return reply(context, KungMessages.info("Visitors", muted
            ? "Alarm muted until the next visitor cycle."
            : "No visitor alarm is active."), muted);
    }

    static int openUpdates(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        // Chat finishes handling the click before the new screen is opened.
        client.schedule(() -> McCompat.setScreen(client, KungConfigScreen.updates()));
        return 1;
    }

    static int openGreenhouse(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        client.schedule(() -> McCompat.setScreen(client, new GreenhouseScreen()));
        return 1;
    }

    static int showHologram(CommandContext<FabricClientCommandSource> context) {
        var shown = GreenhouseHologram.showFromClipboard();
        if (shown.isEmpty()) {
            return reply(context, KungMessages.error("Greenhouse", "No valid layout code in clipboard"), false);
        }
        return info(context, "Greenhouse", shown.get().message());
    }

    /** For when the Carpenter is not found: the plot the player stands in is taken as the greenhouse. */
    static int showHologramHere(CommandContext<FabricClientCommandSource> context) {
        var shown = GreenhouseHologram.showFromClipboardHere();
        if (shown.isEmpty()) {
            return reply(context, KungMessages.error("Greenhouse", "No valid layout code in clipboard"), false);
        }
        return reply(context, shown.get().insideGreenhouse()
            ? KungMessages.info("Greenhouse", shown.get().message())
            : KungMessages.warning("Greenhouse", shown.get().message()), shown.get().insideGreenhouse());
    }

    /** Developer dump of the plot the player stands in: armor stands, heads, soil blocks. */
    static int dumpGreenhouse(CommandContext<FabricClientCommandSource> context) {
        var outcome = GreenhouseDump.run(context.getSource().getClient());
        if (outcome.error() != null) {
            return reply(context, KungMessages.error("Greenhouse", outcome.error()), false);
        }
        return info(context, "Greenhouse", "Dump saved: " + outcome.path() + " ("
            + outcome.armorStands() + " armor stands, " + outcome.cells() + " cells)");
    }

    static int hideHologram(CommandContext<FabricClientCommandSource> context) {
        boolean wasShowing = GreenhouseHologram.isShowing();
        GreenhouseHologram.hide();
        return reply(context, KungMessages.info("Greenhouse",
            wasShowing ? "Hologram hidden." : "No hologram is showing."), wasShowing);
    }

    static int rotateHologram(CommandContext<FabricClientCommandSource> context) {
        return info(context, "Greenhouse", "Hologram " + GreenhouseHologram.rotate() + ".");
    }

    static int mirrorHologram(CommandContext<FabricClientCommandSource> context) {
        return info(context, "Greenhouse", "Hologram " + GreenhouseHologram.mirror() + ".");
    }

    static int previewUpdateNotification(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        client.schedule(() -> KungUpdater.INSTANCE.previewNotification(client));
        return 1;
    }

    static int openChangelog(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        client.schedule(() -> McCompat.setScreen(client, KungConfigScreen.changelog()));
        return 1;
    }

    static int debugInstanceContext(CommandContext<FabricClientCommandSource> context) {
        HypixelInstanceTracker tracker = HypixelInstanceTracker.INSTANCE;
        return reply(context,
            "Tracking="
                + tracker.tracking()
                + " dungeonHub="
                + tracker.dungeonHub()
                + " catacombs="
                + tracker.catacombs()
                + " dungeonContext="
                + tracker.catacombs()
                + " server="
                + blankAsUnknown(tracker.serverId())
                + " instance="
                + blankAsUnknown(tracker.instanceLine()),
            true);
    }

    static int openCatacombsCalculator(
        CommandContext<FabricClientCommandSource> context,
        String username
    ) {
        Minecraft client = context.getSource().getClient();
        client.execute(() -> McCompat.setScreen(client, new CatacombsCalculatorScreen(username)));
        return 1;
    }

    static int copyDebugLog(CommandContext<FabricClientCommandSource> context, int maxLines) {
        Minecraft client = context.getSource().getClient();
        String dump = KungDebugRecorder.dump(maxLines);
        client.keyboardHandler.setClipboard(dump);
        return info(context, "Log", "Trace copied to clipboard (up to " + maxLines + " lines).");
    }

    static int saveDebugLog(CommandContext<FabricClientCommandSource> context) {
        try {
            Path path = KungDebugRecorder.saveToFile().toAbsolutePath();
            return info(context, "Log", "Trace saved: " + path);
        } catch (IOException exception) {
            return reply(context,
                KungMessages.info("Log", "Could not save trace: " + exception.getClass().getSimpleName()),
                false);
        }
    }

    static int clearDebugLog(CommandContext<FabricClientCommandSource> context) {
        KungDebugRecorder.clear();
        return info(context, "Log", "Trace cleared.");
    }

    static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    static int testDoorTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        int doors
    ) {
        Minecraft client = context.getSource().getClient();
        client.execute(() -> dungeonStateTracker.debugShowDoorTitle(client, doors));
        return reply(context, "Kung test title shown: " + doors + " doors", true);
    }

    static int testDungeonStartTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        int doors
    ) {
        dungeonStateTracker.debugScheduleDoorTitle(doors);
        return reply(context, "Kung dungeon-start title scheduled: " + doors + " doors", true);
    }

    static int openHudEditor(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        client.execute(() -> McCompat.setScreen(client, new KungHudEditorScreen(dungeonStateTracker)));
        return 1;
    }

    static int markSplit(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        String name = StringArgumentType.getString(context, "name").trim();
        if (name.isEmpty()) {
            return reply(context, "The split name cannot be empty.", false);
        }

        dungeonStateTracker.splitTracker().mark(name, dungeonStateTracker.dungeonTick());
        return reply(context, "Split markiert. Naechster Abschnitt: " + name, true);
    }

    static int resetSplits(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        dungeonStateTracker.splitTracker().reset();
        return reply(context, "Splits reset.", true);
    }

    static RoomType parseRoomType(String value) {
        try {
            return RoomType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static String[] roomTypeNames() {
        RoomType[] values = RoomType.values();
        String[] names = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            names[index] = values[index].name().toLowerCase(Locale.ROOT);
        }
        return names;
    }

    static CompletableFuture<Suggestions> suggestRoomNames(
        CommandContext<FabricClientCommandSource> context,
        SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining();
        boolean quoted = remaining.startsWith("\"");
        String prefix = quoted ? remaining.substring(1) : remaining;
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        for (String name : DungeonKnownRoomCatalog.knownRoomNames()) {
            if (!name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                continue;
            }
            builder.suggest(quoted ? quoteStringArgument(name) : name);
        }
        return builder.buildFuture();
    }

    static String quoteStringArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static Direction parseDirection(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            default -> null;
        };
    }

    static String[] directionNames() {
        return new String[] {"north", "south", "east", "west"};
    }

    /** The blocks at the door's centre from Y 66 to 74, and the same from Y 68 as "clear" or the block when it has collision. */
    static DoorProbeSamples sampleDoor(Minecraft client, int worldX, int worldZ) {
        StringJoiner centerBlocks = new StringJoiner("/");
        StringJoiner centerPattern = new StringJoiner("/");
        for (int y = 66; y <= 74; y++) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState state = client.level.getBlockState(pos);
            String block = y + ":" + Block.getId(state) + ":" + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            centerBlocks.add(block);
            if (y >= 68) {
                centerPattern.add(state.getCollisionShape(client.level, pos).isEmpty() ? y + ":clear" : block);
            }
        }
        return new DoorProbeSamples(centerPattern.toString(), centerBlocks.toString());
    }

    private record RoomLearnInput(
        String name,
        int secrets,
        RoomType type,
        int crypts,
        boolean hasExplicitMetadata
    ) {
        static RoomLearnInput of(DungeonKnownRoomCatalog.KnownCoreHint info) {
            return new RoomLearnInput(info.name(), info.secrets(), info.type(), info.crypts(), true);
        }
    }

    private record RoomCore(boolean valid, String message, int coreHash, int stableCoreHash) {
        static RoomCore valid(int coreHash, int stableCoreHash) {
            return new RoomCore(true, "", coreHash, stableCoreHash);
        }

        static RoomCore failed(String message) {
            return new RoomCore(false, message, 0, 0);
        }
    }

    private record DoorProbeSamples(String centerPattern, String centerBlocks) {
    }
}
