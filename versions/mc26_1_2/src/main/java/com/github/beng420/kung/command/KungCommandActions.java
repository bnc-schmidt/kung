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
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class KungCommandActions {
    private KungCommandActions() {
    }

    static int roomProjectStatus(CommandContext<FabricClientCommandSource> context) {
        String project = KungConfig.get().dungeon.roomDataProjectDirectory();
        context.getSource().sendFeedback(commandMessage(project.isBlank()
            ? "Room project: off. Link with /kung room project <project folder>."
            : "Room project: " + project + ". Room changes are saved directly to this project."));
        return 1;
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
            context.getSource().sendFeedback(commandMessage(
                "Room project linked: " + project
                    + ". Learned rooms, hashes and metadata changes are now saved directly to the project."
            ));
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            context.getSource().sendFeedback(commandMessage("Could not link room project: " + exception.getMessage()));
            return 0;
        }
    }

    static int disableRoomProject(CommandContext<FabricClientCommandSource> context) {
        KungConfig.get().dungeon.setRoomDataProjectDirectory("");
        DungeonKnownRoomCatalog.reload();
        DungeonRoomClassifier.reload();
        context.getSource().sendFeedback(commandMessage("Room project disabled. Room changes now stay in this game profile."));
        return 1;
    }

    private static Component roomWriteMessage(String message, boolean saved) {
        return commandMessage(message + (saved && DungeonRoomProject.enabled() ? " Saved to the linked project." : ""));
    }

    static int learnRoomType(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        RoomType roomType = parseRoomType(StringArgumentType.getString(context, "type"));
        if (roomType == null) {
            context.getSource().sendFeedback(commandMessage(
                "Unknown room type. Use: " + String.join(", ", roomTypeNames())
            ));
            return 0;
        }

        DungeonStateTracker.LearnRoomTypeResult result =
            dungeonStateTracker.learnCurrentRoomType(context.getSource().getClient(), roomType);

        context.getSource().sendFeedback(roomWriteMessage(result.message(), result.learned()));
        return result.learned() ? 1 : 0;
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
        context.getSource().sendFeedback(commandMessage(result.message()));
        return result.found() ? 1 : 0;
    }

    static int learnLookedRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        Minecraft client = context.getSource().getClient();
        BlockPos targetPosition = lookedRoomPosition(context, client);
        if (targetPosition == null) {
            return 0;
        }

        if (!input.hasExplicitMetadata()) {
            input = resolveRoomLearnInput(context, client, targetPosition, input);
            if (input == null) {
                return 0;
            }
        }

        DungeonStateTracker.LearnRoomResult result = dungeonStateTracker.learnRoomAt(
            client,
            targetPosition,
            input.name(),
            input.type(),
            input.secrets(),
            input.crypts()
        );
        context.getSource().sendFeedback(roomWriteMessage(result.message(), result.learned()));
        return result.learned() ? 1 : 0;
    }

    static int learnRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        return learnCurrentRoomInput(context, dungeonStateTracker, false);
    }

    private static int learnCurrentRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        boolean multiRoom
    ) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        Minecraft client = context.getSource().getClient();
        if (!input.hasExplicitMetadata()) {
            input = resolveRoomLearnInput(
                context,
                client,
                client.player == null ? null : client.player.blockPosition(),
                input
            );
            if (input == null) {
                return 0;
            }
        }

        DungeonStateTracker.LearnRoomResult result = multiRoom
            ? dungeonStateTracker.learnCurrentMultiRoom(
                client, input.name(), input.type(), input.secrets(), input.crypts())
            : dungeonStateTracker.learnCurrentRoom(
                client, input.name(), input.type(), input.secrets(), input.crypts());
        context.getSource().sendFeedback(roomWriteMessage(result.message(), result.learned()));
        return result.learned() ? 1 : 0;
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
            context.getSource().sendFeedback(roomWriteMessage(result.message(), result.deleted()));
            return result.deleted() ? 1 : 0;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            context.getSource().sendFeedback(commandMessage(
                "Could not delete room data. See latest.log."
            ));
            return 0;
        }
    }

    static int learnMultiRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        return learnCurrentRoomInput(context, dungeonStateTracker, true);
    }

    static int exportRoomInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        Minecraft client = context.getSource().getClient();
        input = resolveKnownRoomInput(context, client, input);
        if (input == null) {
            return 0;
        }

        DungeonStateTracker.RoomDataResult result = dungeonStateTracker.exportCurrentRoom(
            client,
            input.name(),
            input.type(),
            input.secrets(),
            input.crypts()
        );
        if (result.found()) {
            client.keyboardHandler.setClipboard(result.clipboardText());
        }
        context.getSource().sendFeedback(commandMessage(result.message()));
        return result.found() ? 1 : 0;
    }

    static int pushRoomSyncInput(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        RoomLearnInput input = parseRoomLearnInput(context);
        if (input == null) {
            return 0;
        }

        Minecraft client = context.getSource().getClient();
        input = resolveKnownRoomInput(context, client, input);
        if (input == null) {
            return 0;
        }

        DungeonStateTracker.RoomDataResult result = dungeonStateTracker.exportCurrentRoom(
            client,
            input.name(),
            input.type(),
            input.secrets(),
            input.crypts()
        );
        if (!result.found()) {
            context.getSource().sendFeedback(commandMessage(result.message()));
            return 0;
        }

        DungeonRoomDataSyncClient.INSTANCE.pushRoomReportAsync(client, result.clipboardText());
        context.getSource().sendFeedback(commandMessage("Server Sync push started: " + input.name()));
        return 1;
    }

    /** The input itself when it names type and secrets, else the known room's data; null after the lookup already reported. */
    private static RoomLearnInput resolveKnownRoomInput(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        RoomLearnInput input
    ) {
        if (input.hasExplicitMetadata()) {
            return input;
        }
        DungeonKnownRoomCatalog.KnownRoomInfo info = client.player == null
            ? knownRoomInfo(context, input.name())
            : knownRoomInfo(context, client, client.player.blockPosition(), input.name());
        return info == null
            ? null
            : new RoomLearnInput(info.name(), info.secrets(), info.type(), info.crypts(), true);
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
        for (String line : JerryPerkpocalypse.INSTANCE.statusLines(System.currentTimeMillis())) {
            context.getSource().sendFeedback(KungMessages.info("Jerry", line));
        }
        return 1;
    }

    static int roomSyncStatus(CommandContext<FabricClientCommandSource> context) {
        KungConfig config = KungConfig.get();
        String url = config.dungeon.roomSyncServerUrl().isBlank() ? "none" : config.dungeon.roomSyncServerUrl();
        context.getSource().sendFeedback(commandMessage(
            "Server Sync: enabled=" + config.dungeon.roomSyncEnabled()
                + " upload=" + config.dungeon.roomSyncUploadEnabled()
                + " server=" + url
                + " status=" + DungeonRoomDataSyncClient.INSTANCE.statusMessage()
                + " sign-in=" + KungServerSession.INSTANCE.status()
        ));
        return 1;
    }

    static int setRoomSyncEnabled(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        KungConfig.get().dungeon.setRoomSyncEnabled(enabled);
        DungeonKnownRoomCatalog.reload();
        context.getSource().sendFeedback(commandMessage(
            "Server Sync " + (enabled ? "enabled for this session." : "disabled.")
        ));
        return 1;
    }

    static int setRoomSyncUpload(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        KungConfig.get().dungeon.setRoomSyncUploadEnabled(enabled);
        context.getSource().sendFeedback(commandMessage(
            "Server Sync upload " + (enabled ? "enabled for this session." : "disabled.")
        ));
        return 1;
    }

    static int setRoomSyncServer(CommandContext<FabricClientCommandSource> context) {
        String url = StringArgumentType.getString(context, "url").trim();
        if (url.isEmpty()) {
            context.getSource().sendFeedback(commandMessage("Server Sync URL cannot be empty."));
            return 0;
        }
        KungConfig.get().dungeon.setRoomSyncServerUrl(url);
        context.getSource().sendFeedback(commandMessage("Server Sync server saved."));
        return 1;
    }

    static RoomLearnInput parseRoomLearnInput(CommandContext<FabricClientCommandSource> context) {
        String input = StringArgumentType.getString(context, "input").trim();
        if (input.isEmpty()) {
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
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
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
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
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
            return null;
        }
        String name = normalizeRoomNameInput(String.join(" ", java.util.Arrays.copyOf(parts, namePartCount)));
        if (name.isEmpty()) {
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
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
            context.getSource().sendFeedback(commandMessage("You are not currently in a world."));
            return null;
        }

        DirectionStep direction = directionFromYaw(client.player.getYRot());
        DungeonScanUtils.GridPosition playerRoom =
            DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        int targetRoomGridX = playerRoom.gridX() + direction.dx();
        int targetRoomGridZ = playerRoom.gridZ() + direction.dz();
        if (!DungeonScanUtils.isValidRoomGrid(targetRoomGridX, targetRoomGridZ)) {
            context.getSource().sendFeedback(commandMessage(
                "No valid dungeon room cell in the direction you are looking. roomGrid="
                    + playerRoom.gridX() + "," + playerRoom.gridZ()
                    + " dir=" + direction.name()
            ));
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
        context.getSource().sendFeedback(roomWriteMessage(result.message(), result.learned()));
        return result.learned() ? 1 : 0;
    }

    static int updateCurrentRoomCrypts(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        DungeonStateTracker.LearnRoomResult result = dungeonStateTracker.updateCurrentRoomCrypts(
            context.getSource().getClient(),
            IntegerArgumentType.getInteger(context, "count")
        );
        context.getSource().sendFeedback(roomWriteMessage(result.message(), result.learned()));
        return result.learned() ? 1 : 0;
    }

    static DungeonKnownRoomCatalog.KnownRoomInfo knownRoomInfo(
        CommandContext<FabricClientCommandSource> context,
        String name
    ) {
        if (name.isEmpty()) {
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
            return null;
        }

        List<DungeonKnownRoomCatalog.KnownRoomInfo> infos = DungeonKnownRoomCatalog.knownRoomInfos(name);
        if (infos.isEmpty()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + name + "\" is not known yet. Use secrets/type when saving it for the first time."
            ));
            return null;
        }
        if (infos.size() > 1) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + name + "\" is ambiguous. Please use secrets/type."
            ));
            return null;
        }
        return infos.get(0);
    }

    static RoomLearnInput resolveRoomLearnInput(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        BlockPos roomPosition,
        RoomLearnInput input
    ) {
        List<DungeonKnownRoomCatalog.KnownRoomInfo> infos = DungeonKnownRoomCatalog.knownRoomInfos(input.name());
        if (infos.isEmpty()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + input.name() + "\" is not known yet. Saving it as NORMAL with 0 secrets."
            ));
            return new RoomLearnInput(input.name(), 0, RoomType.NORMAL, 0, true);
        }
        if (infos.size() == 1) {
            DungeonKnownRoomCatalog.KnownRoomInfo info = infos.get(0);
            return new RoomLearnInput(info.name(), info.secrets(), info.type(), info.crypts(), true);
        }
        if (client == null || roomPosition == null) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + input.name() + "\" is ambiguous. Please use secrets/type."
            ));
            return null;
        }

        RoomCore roomCore = roomCoreAt(client, roomPosition);
        if (!roomCore.valid()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + input.name() + "\" is ambiguous and the target room could not be hashed: "
                    + roomCore.message() + " Please use secrets/type."
            ));
            return null;
        }

        List<DungeonKnownRoomCatalog.KnownRoomInfo> matchingInfos =
            DungeonKnownRoomCatalog.knownRoomInfos(input.name(), roomCore.coreHash(), roomCore.stableCoreHash());
        if (matchingInfos.size() == 1) {
            DungeonKnownRoomCatalog.KnownRoomInfo info = matchingInfos.get(0);
            return new RoomLearnInput(info.name(), info.secrets(), info.type(), info.crypts(), true);
        }

        context.getSource().sendFeedback(commandMessage(
            "Room \"" + input.name() + "\" is ambiguous. Please use secrets/type."
        ));
        return null;
    }

    static DungeonKnownRoomCatalog.KnownRoomInfo knownRoomInfo(
        CommandContext<FabricClientCommandSource> context,
        Minecraft client,
        BlockPos roomPosition,
        String name
    ) {
        if (name.isEmpty()) {
            context.getSource().sendFeedback(commandMessage("The room name cannot be empty."));
            return null;
        }

        List<DungeonKnownRoomCatalog.KnownRoomInfo> infos = DungeonKnownRoomCatalog.knownRoomInfos(name);
        if (infos.isEmpty()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + name + "\" is not known yet. Use secrets/type when saving it for the first time."
            ));
            return null;
        }
        if (infos.size() == 1) {
            return infos.getFirst();
        }

        RoomCore roomCore = roomCoreAt(client, roomPosition);
        if (!roomCore.valid()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + name + "\" is ambiguous and the target room could not be hashed: "
                    + roomCore.message() + " Please use secrets/type."
            ));
            return null;
        }

        List<DungeonKnownRoomCatalog.KnownRoomInfo> matchingInfos =
            DungeonKnownRoomCatalog.knownRoomInfos(name, roomCore.coreHash(), roomCore.stableCoreHash());
        if (matchingInfos.size() == 1) {
            return matchingInfos.getFirst();
        }
        if (matchingInfos.isEmpty()) {
            context.getSource().sendFeedback(commandMessage(
                "Room \"" + name + "\" is ambiguous. targetHash="
                    + roomCore.coreHash() + "/" + roomCore.stableCoreHash()
                    + " does not match any known variant. Please use secrets/type."
            ));
            return null;
        }

        context.getSource().sendFeedback(commandMessage(
            "Room \"" + name + "\" remains ambiguous despite targetHash="
                + roomCore.coreHash() + "/" + roomCore.stableCoreHash()
                + ". Please use secrets/type."
        ));
        return null;
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
            com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.UndoResult result =
                com.github.beng420.kung.feature.dungeon.DungeonKnownRoomCatalog.undoLast();
            context.getSource().sendFeedback(roomWriteMessage(result.message(), result.undone()));
            return result.undone() ? 1 : 0;
        } catch (java.io.IOException exception) {
            context.getSource().sendFeedback(commandMessage(
                "Could not undo the last room entry. See latest.log."
            ));
            return 0;
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
        context.getSource().sendFeedback(KungMessages.debug("MapDebug", "Copied to clipboard."));
        return 1;
    }

    static int copyUltraDebug(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        Minecraft client = context.getSource().getClient();
        DungeonStateTracker.RoomDataResult result = dungeonStateTracker.ultraDebug(client);
        client.keyboardHandler.setClipboard(result.clipboardText());
        context.getSource().sendFeedback(KungMessages.debug("DungeonDebug", result.message()));
        return result.found() ? 1 : 0;
    }

    static int probeDoorAhead(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        if (client.player == null) {
            context.getSource().sendFeedback(commandMessage("You are not currently in a world."));
            return 0;
        }
        return probeDoor(context, directionFromYaw(client.player.getYRot()));
    }

    static int probeDoorDirection(CommandContext<FabricClientCommandSource> context) {
        DirectionStep direction = parseDirection(StringArgumentType.getString(context, "direction"));
        if (direction == null) {
            context.getSource().sendFeedback(commandMessage(
                "Unknown direction. Use: " + String.join(", ", directionNames())
            ));
            return 0;
        }
        return probeDoor(context, direction);
    }

    static int probeDoor(CommandContext<FabricClientCommandSource> context, DirectionStep direction) {
        Minecraft client = context.getSource().getClient();
        if (client.level == null || client.player == null) {
            context.getSource().sendFeedback(commandMessage("You are not currently in a world."));
            return 0;
        }
        DungeonScanUtils.GridPosition roomGrid = DungeonScanUtils.getRoomGridPosition(client.player.blockPosition());
        int doorGridX = roomGrid.gridX() * 2 + direction.dx();
        int doorGridZ = roomGrid.gridZ() * 2 + direction.dz();
        if (!DungeonScanUtils.isDoorScanPoint(doorGridX, doorGridZ)) {
            context.getSource().sendFeedback(commandMessage(
                "No valid door cell in front of you. roomGrid="
                    + roomGrid.gridX() + "," + roomGrid.gridZ()
                    + " dir=" + direction.name()
            ));
            return 0;
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
        DoorProbeSamples samples = sampleDoor(client, doorGridX, doorGridZ, worldX, worldZ);
        context.getSource().sendFeedback(commandMessage(
            "doorProbe dir=" + direction.name()
                + " roomGrid=" + roomGrid.gridX() + "," + roomGrid.gridZ()
                + " doorGrid=" + doorGridX + "," + doorGridZ
                + " world=" + worldX + "," + worldZ
                + " kind=" + doorKind.name()
                + " centerPattern=" + samples.centerPattern()
                + " centerY66-74=" + samples.centerBlocks()
        ));
        return 1;
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
        context.getSource().sendFeedback(KungMessages.info("Visitors", muted
            ? "Alarm muted until the next visitor cycle."
            : "No visitor alarm is active."));
        return muted ? 1 : 0;
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
            context.getSource().sendFeedback(KungMessages.error("Greenhouse", "No valid layout code in clipboard"));
            return 0;
        }
        context.getSource().sendFeedback(KungMessages.info("Greenhouse", shown.get().message()));
        return 1;
    }

    /** For when the Carpenter is not found: the plot the player stands in is taken as the greenhouse. */
    static int showHologramHere(CommandContext<FabricClientCommandSource> context) {
        var shown = GreenhouseHologram.showFromClipboardHere();
        if (shown.isEmpty()) {
            context.getSource().sendFeedback(KungMessages.error("Greenhouse", "No valid layout code in clipboard"));
            return 0;
        }
        context.getSource().sendFeedback(shown.get().insideGreenhouse()
            ? KungMessages.info("Greenhouse", shown.get().message())
            : KungMessages.warning("Greenhouse", shown.get().message()));
        return shown.get().insideGreenhouse() ? 1 : 0;
    }

    /** Developer dump of the plot the player stands in: armor stands, heads, soil blocks. */
    static int dumpGreenhouse(CommandContext<FabricClientCommandSource> context) {
        var outcome = GreenhouseDump.run(context.getSource().getClient());
        if (outcome.error() != null) {
            context.getSource().sendFeedback(KungMessages.error("Greenhouse", outcome.error()));
            return 0;
        }
        context.getSource().sendFeedback(KungMessages.info("Greenhouse", "Dump saved: " + outcome.path() + " ("
            + outcome.armorStands() + " armor stands, " + outcome.cells() + " cells)"));
        return 1;
    }

    static int hideHologram(CommandContext<FabricClientCommandSource> context) {
        boolean wasShowing = GreenhouseHologram.isShowing();
        GreenhouseHologram.hide();
        context.getSource().sendFeedback(KungMessages.info("Greenhouse",
            wasShowing ? "Hologram hidden." : "No hologram is showing."));
        return wasShowing ? 1 : 0;
    }

    static int rotateHologram(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(KungMessages.info("Greenhouse",
            "Hologram " + GreenhouseHologram.rotate() + "."));
        return 1;
    }

    static int mirrorHologram(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(KungMessages.info("Greenhouse",
            "Hologram " + GreenhouseHologram.mirror() + "."));
        return 1;
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
        context.getSource().sendFeedback(commandMessage(
            "Tracking="
                + tracker.tracking()
                + " dungeonHub="
                + tracker.dungeonHub()
                + " catacombs="
                + tracker.catacombs()
                + " dungeonContext="
                + tracker.dungeonRunContext()
                + " server="
                + blankAsUnknown(tracker.serverId())
                + " instance="
                + blankAsUnknown(tracker.instanceLine())
        ));
        return 1;
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
        context.getSource().sendFeedback(KungMessages.info(
            "Log",
            "Trace copied to clipboard (up to " + maxLines + " lines)."
        ));
        return 1;
    }

    static int saveDebugLog(CommandContext<FabricClientCommandSource> context) {
        try {
            Path path = KungDebugRecorder.saveToFile(context.getSource().getClient()).toAbsolutePath();
            context.getSource().sendFeedback(KungMessages.info("Log", "Trace saved: " + path));
            return 1;
        } catch (IOException exception) {
            context.getSource().sendFeedback(KungMessages.info(
                "Log",
                "Could not save trace: " + exception.getClass().getSimpleName()
            ));
            return 0;
        }
    }

    static int clearDebugLog(CommandContext<FabricClientCommandSource> context) {
        KungDebugRecorder.clear();
        context.getSource().sendFeedback(KungMessages.info("Log", "Trace cleared."));
        return 1;
    }

    static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    static Component commandMessage(String message) {
        return KungMessages.info(message);
    }

    static int testDoorTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        return testDoorTitle(context, dungeonStateTracker, IntegerArgumentType.getInteger(context, "doors"));
    }

    static int testDoorTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        int doors
    ) {
        Minecraft client = context.getSource().getClient();
        client.execute(() -> dungeonStateTracker.debugShowDoorTitle(client, doors));
        context.getSource().sendFeedback(commandMessage("Kung test title shown: " + doors + " doors"));
        return 1;
    }

    static int testDungeonStartTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        return testDungeonStartTitle(context, dungeonStateTracker, IntegerArgumentType.getInteger(context, "doors"));
    }

    static int testDungeonStartTitle(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker,
        int doors
    ) {
        dungeonStateTracker.debugScheduleDoorTitle(doors);
        context.getSource().sendFeedback(commandMessage(
            "Kung dungeon-start title scheduled: " + doors + " doors"
        ));
        return 1;
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
            context.getSource().sendFeedback(commandMessage("The split name cannot be empty."));
            return 0;
        }

        dungeonStateTracker.splitTracker().mark(name, dungeonStateTracker.dungeonTick());
        context.getSource().sendFeedback(commandMessage("Split markiert. Naechster Abschnitt: " + name));
        return 1;
    }

    static int resetSplits(
        CommandContext<FabricClientCommandSource> context,
        DungeonStateTracker dungeonStateTracker
    ) {
        dungeonStateTracker.splitTracker().reset();
        context.getSource().sendFeedback(commandMessage("Splits reset."));
        return 1;
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
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        escaped.append('"');
        return escaped.toString();
    }

    static DirectionStep directionFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90.0f), 4)) {
            case 1 -> new DirectionStep("west", -1, 0);
            case 2 -> new DirectionStep("north", 0, -1);
            case 3 -> new DirectionStep("east", 1, 0);
            default -> new DirectionStep("south", 0, 1);
        };
    }

    static DirectionStep parseDirection(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> new DirectionStep("north", 0, -1);
            case "south", "s" -> new DirectionStep("south", 0, 1);
            case "east", "e" -> new DirectionStep("east", 1, 0);
            case "west", "w" -> new DirectionStep("west", -1, 0);
            default -> null;
        };
    }

    static String[] directionNames() {
        return new String[] {"north", "south", "east", "west"};
    }

    static DoorProbeSamples sampleDoor(Minecraft client, int gridX, int gridZ, int worldX, int worldZ) {
        StringBuilder centerBlocks = new StringBuilder();
        StringBuilder centerPattern = new StringBuilder();

        for (int y = 66; y <= 74; y++) {
            if (y > 66) {
                centerBlocks.append('/');
            }
            BlockState state = client.level.getBlockState(new BlockPos(worldX, y, worldZ));
            centerBlocks.append(y)
                .append(':')
                .append(Block.getId(state))
                .append(':')
                .append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
        }

        appendCenterPattern(client, centerPattern, worldX, 68, worldZ);
        for (int y = 69; y <= 72; y++) {
            centerPattern.append('/');
            appendCenterPattern(client, centerPattern, worldX, y, worldZ);
        }
        centerPattern.append('/');
        appendCenterPattern(client, centerPattern, worldX, 73, worldZ);
        centerPattern.append('/');
        appendCenterPattern(client, centerPattern, worldX, 74, worldZ);

        return new DoorProbeSamples(centerPattern.toString(), centerBlocks.toString());
    }

    static void appendCenterPattern(
        Minecraft client,
        StringBuilder builder,
        int worldX,
        int y,
        int worldZ
    ) {
        BlockPos pos = new BlockPos(worldX, y, worldZ);
        BlockState state = client.level.getBlockState(pos);
        if (isClear(client, pos)) {
            builder.append(y).append(":clear");
            return;
        }

        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        builder.append(y)
            .append(':')
            .append(Block.getId(state))
            .append(':')
            .append(blockName);
    }

    static boolean isClear(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty();
    }

    private record DirectionStep(String name, int dx, int dz) {
    }

    private record RoomLearnInput(
        String name,
        int secrets,
        RoomType type,
        int crypts,
        boolean hasExplicitMetadata
    ) {
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
