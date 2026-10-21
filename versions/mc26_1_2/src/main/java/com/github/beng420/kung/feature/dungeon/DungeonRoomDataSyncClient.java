package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class DungeonRoomDataSyncClient {
    public static final DungeonRoomDataSyncClient INSTANCE = new DungeonRoomDataSyncClient();

    private volatile String statusMessage = "Idle";
    private volatile boolean liveSyncInFlight;

    private DungeonRoomDataSyncClient() {
    }

    public String statusMessage() {
        return statusMessage;
    }

    public String menuStatus() {
        if (!KungConfig.get().dungeon.roomSyncEnabled()) {
            return "Disabled";
        }
        if (!configured()) {
            return "Not connected: no server";
        }
        String signIn = KungServerSession.INSTANCE.status();
        if (signIn.equals(KungServerSession.NOT_ALLOWED) || signIn.startsWith("Sign-in failed")) {
            return "Not connected: " + signIn;
        }
        String status = statusMessage == null ? "" : statusMessage;
        String lower = status.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("failed")) {
            return "Not connected: " + status;
        }
        if (lower.contains("pinging") || lower.contains("pulling") || lower.contains("pushing")) {
            return "Checking...";
        }
        if (lower.startsWith("live rooms")) {
            return "Ready: " + status;
        }
        return "Ready (" + signIn + ")";
    }

    public boolean configured() {
        return !KungConfig.get().dungeon.roomSyncServerUrl().isBlank();
    }

    public boolean active() {
        return KungConfig.get().dungeon.roomSyncEnabled() && configured();
    }


    public void pullAsync(Minecraft client) {
        if (!active()) {
            send(client, KungMessages.Type.WARNING, "Server Sync is not active. Set a server and enable it.");
            return;
        }

        statusMessage = "Pulling...";
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> response = exchange(requestBuilder(endpoint("rooms"))
                    .header("Accept", "application/json")
                    .GET());
                ensureSuccess(response, "Pull");
                DungeonKnownRoomCatalog.RemoteCacheResult result =
                    DungeonKnownRoomCatalog.updateRemoteCache(response.body());
                statusMessage = "Pulled " + result.roomCount() + " rooms";
                send(client, KungMessages.Type.SUCCESS, "Pull ok: rooms=" + result.roomCount()
                    + " variants=" + result.variantCount()
                    + " cells=" + result.componentCount());
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                statusMessage = "Pull failed";
                KungMod.LOGGER.warn("Failed to pull dungeon room sync data.", exception);
                send(client, KungMessages.Type.ERROR, "Pull failed. See latest.log.");
            }
        });
    }

    public void pushRoomReportAsync(String roomReportJson) {
        if (!active() || !KungConfig.get().dungeon.roomSyncUploadEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                pushRoomReport(roomReportJson);
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                statusMessage = "Push failed";
                KungMod.LOGGER.warn("Failed to push dungeon room report.", exception);
            }
        });
    }

    public void pushRoomReportAsync(Minecraft client, String roomReportJson) {
        if (!active()) {
            send(client, KungMessages.Type.WARNING, "Server Sync is not active. Set a server and enable it.");
            return;
        }

        statusMessage = "Pushing...";
        CompletableFuture.runAsync(() -> {
            try {
                PushResult result = pushRoomReport(roomReportJson);
                statusMessage = "Pushed";
                send(client, KungMessages.Type.SUCCESS, "Push ok: " + result.message());
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                statusMessage = "Push failed";
                KungMod.LOGGER.warn("Failed to push dungeon room report.", exception);
                send(client, KungMessages.Type.ERROR, "Push failed. See latest.log.");
            }
        });
    }

    private PushResult pushRoomReport(String roomReportJson) throws IOException, InterruptedException {
        HttpResponse<String> response = exchange(requestBuilder(endpoint("rooms/report"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(roomReportJson)));
        ensureSuccess(response, "Push");
        return new PushResult(response.body().isBlank() ? "gesendet" : response.body());
    }

    public void pingAsync(Minecraft client) {
        if (!configured()) {
            send(client, KungMessages.Type.ERROR, "Ping failed: no server set.");
            return;
        }

        statusMessage = "Pinging...";
        CompletableFuture.runAsync(() -> {
            long startedAt = System.nanoTime();
            try {
                String playerName = client != null && client.player != null
                    ? client.player.getName().getString()
                    : "ping";
                String query = "runs/live?runKey=ping&player="
                    + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
                HttpResponse<String> response = exchange(requestBuilder(endpoint(query))
                    .header("Accept", "application/json")
                    .GET());
                long durationMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                ensureSuccess(response, "Ping");
                int liveClientCount = liveClientCount(response.body());
                statusMessage = "Ping ok";
                send(client, KungMessages.Type.SUCCESS, "Ping ok: HTTP "
                    + response.statusCode()
                    + " in "
                    + durationMillis
                    + "ms, liveClients="
                    + liveClientCount
                    + ", "
                    + KungServerSession.INSTANCE.status()
                    + ".");
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                statusMessage = "Ping failed";
                KungMod.LOGGER.warn("Failed to ping dungeon room sync server.", exception);
                send(client, KungMessages.Type.ERROR, "Ping failed: " + shortError(exception) + ".");
            }
        });
    }

    public void syncLiveRoomsAsync(
        Minecraft client,
        String runKey,
        List<LiveRoomReport> rooms,
        List<LiveDoorReport> doors,
        List<LivePlayerReport> players,
        Consumer<LiveSyncSnapshot> remoteSnapshotConsumer
    ) {
        if (!active() || runKey == null || runKey.isBlank() || client == null || client.player == null) {
            KungDebugRecorder.event("room-sync", "live skipped active="
                + active()
                + " configured="
                + configured()
                + " runKey="
                + (runKey == null ? "<null>" : runKey)
                + " client="
                + (client != null)
                + " player="
                + (client != null && client.player != null));
            return;
        }
        if (liveSyncInFlight) {
            KungDebugRecorder.event("room-sync", "live skipped in-flight runKey=" + runKey);
            return;
        }
        liveSyncInFlight = true;

        String playerName = client.player.getName().getString();
        List<LiveRoomReport> localRooms = rooms == null ? List.of() : List.copyOf(rooms);
        List<LiveDoorReport> localDoors = doors == null ? List.of() : List.copyOf(doors);
        List<LivePlayerReport> localPlayers = players == null ? List.of() : List.copyOf(players);
        CompletableFuture.runAsync(() -> {
            try {
                if (KungConfig.get().dungeon.roomSyncUploadEnabled()
                    && (!localRooms.isEmpty() || !localDoors.isEmpty() || !localPlayers.isEmpty())) {
                    pushLiveSnapshot(runKey, playerName, localRooms, localDoors, localPlayers);
                    KungDebugRecorder.event("room-sync", "live push ok runKey="
                        + runKey
                        + " player="
                        + playerName
                        + " rooms="
                        + localRooms.size()
                        + " doors="
                        + localDoors.size()
                        + " players="
                        + localPlayers.size());
                } else {
                    KungDebugRecorder.event("room-sync", "live push skipped runKey="
                        + runKey
                        + " upload="
                        + KungConfig.get().dungeon.roomSyncUploadEnabled()
                        + " rooms="
                        + localRooms.size()
                        + " doors="
                        + localDoors.size()
                        + " players="
                        + localPlayers.size());
                }
                LiveSyncSnapshot remoteSnapshot = pullLiveSnapshot(runKey, playerName);
                KungDebugRecorder.event("room-sync", "live pull ok runKey="
                    + runKey
                    + " player="
                    + playerName
                    + " rooms="
                    + remoteSnapshot.rooms().size()
                    + " doors="
                    + remoteSnapshot.doors().size()
                    + " players="
                    + remoteSnapshot.players().size());
                if (remoteSnapshotConsumer != null) {
                    client.execute(() -> remoteSnapshotConsumer.accept(remoteSnapshot));
                }
                statusMessage = "Live rooms " + remoteSnapshot.rooms().size()
                    + ", doors " + remoteSnapshot.doors().size()
                    + ", players " + remoteSnapshot.players().size();
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                statusMessage = "Live sync failed";
                KungDebugRecorder.event("room-sync", "live failed runKey="
                    + runKey
                    + " error="
                    + shortError(exception));
                KungMod.LOGGER.warn("Failed to sync live dungeon rooms.", exception);
            } finally {
                liveSyncInFlight = false;
            }
        });
    }

    private void pushLiveSnapshot(
        String runKey,
        String playerName,
        List<LiveRoomReport> rooms,
        List<LiveDoorReport> doors,
        List<LivePlayerReport> players
    ) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("kind", "kung-live-room-sync");
        body.addProperty("schema", 1);
        body.addProperty("runKey", runKey);
        body.addProperty("player", playerName);
        body.addProperty("createdAt", System.currentTimeMillis());

        JsonArray roomArray = new JsonArray();
        for (LiveRoomReport room : rooms) {
            if (room == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("roomGridX", room.roomGridX());
            object.addProperty("roomGridZ", room.roomGridZ());
            object.addProperty("name", room.name());
            object.addProperty("type", room.type() == null ? RoomType.UNKNOWN.name() : room.type().name());
            object.addProperty("secrets", room.secrets());
            object.addProperty("crypts", room.crypts());
            object.addProperty("roomSecretsFound", room.roomSecretsFound());
            object.addProperty("roomSecretsMax", room.roomSecretsMax());
            object.addProperty("visited", room.visited());
            object.addProperty("cleared", room.cleared());
            object.addProperty("completed", room.completed());
            roomArray.add(object);
        }
        body.add("rooms", roomArray);

        JsonArray doorArray = new JsonArray();
        for (LiveDoorReport door : doors) {
            if (door == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("scanGridX", door.scanGridX());
            object.addProperty("scanGridZ", door.scanGridZ());
            object.addProperty("kind", door.kind() == null ? DungeonDoorKind.NONE.name() : door.kind().name());
            object.addProperty("targetType", door.targetType() == null ? RoomType.UNKNOWN.name() : door.targetType().name());
            object.addProperty("targetVisited", door.targetVisited());
            doorArray.add(object);
        }
        body.add("doors", doorArray);

        JsonArray playerArray = new JsonArray();
        for (LivePlayerReport player : players) {
            if (player == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("name", player.name());
            object.addProperty("secretsFound", player.secretsFound());
            object.addProperty("secretsSource", player.secretsSource());
            object.addProperty("deaths", player.deaths());
            playerArray.add(object);
        }
        body.add("players", playerArray);

        HttpResponse<String> response = exchange(requestBuilder(endpoint("runs/live/report"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureSuccess(response, "Live push");
    }

    private LiveSyncSnapshot pullLiveSnapshot(
        String runKey,
        String playerName
    ) throws IOException, InterruptedException {
        String query = "runs/live?runKey="
            + URLEncoder.encode(runKey, StandardCharsets.UTF_8)
            + "&player="
            + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        HttpResponse<String> response = exchange(requestBuilder(endpoint(query))
            .header("Accept", "application/json")
            .GET());
        ensureSuccess(response, "Live pull");
        return parseLiveSnapshot(response.body());
    }

    private static HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Kung-RoomSync");
    }

    /** Callers already run off the render thread, so waiting for sign-in and the reply is fine here. */
    private static HttpResponse<String> exchange(HttpRequest.Builder request) throws IOException, InterruptedException {
        try {
            return KungServerSession.INSTANCE.send(request).get();
        } catch (ExecutionException exception) {
            // Sign-in failures carry their status as the message, e.g. "Not allowed on this server".
            Throwable cause = exception.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        }
    }

    private static void ensureSuccess(HttpResponse<?> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(operation + " returned HTTP " + response.statusCode());
        }
    }

    public static URI endpoint(String path) {
        String base = KungConfig.get().dungeon.roomSyncServerUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            return new URI(base + "/" + path);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid room sync URL.", exception);
        }
    }

    private static int liveClientCount(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement clients = root.get("clients");
            return clients != null && clients.isJsonArray() ? clients.getAsJsonArray().size() : 0;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private static LiveSyncSnapshot parseLiveSnapshot(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement clientsElement = root.get("clients");
        if (clientsElement == null || !clientsElement.isJsonArray()) {
            return new LiveSyncSnapshot(List.of(), List.of(), List.of());
        }

        List<LiveRoomReport> rooms = new ArrayList<>();
        List<LiveDoorReport> doors = new ArrayList<>();
        List<LivePlayerReport> players = new ArrayList<>();
        for (JsonElement clientElement : clientsElement.getAsJsonArray()) {
            if (clientElement == null || !clientElement.isJsonObject()) {
                continue;
            }
            JsonObject clientObject = clientElement.getAsJsonObject();
            String source = string(clientObject, "player", "remote");
            long updatedAt = longValue(clientObject, "updatedAt", 0L);
            JsonElement roomsElement = clientObject.get("rooms");
            if (roomsElement == null || !roomsElement.isJsonArray()) {
                continue;
            }
            for (JsonElement roomElement : roomsElement.getAsJsonArray()) {
                if (roomElement == null || !roomElement.isJsonObject()) {
                    continue;
                }
                JsonObject roomObject = roomElement.getAsJsonObject();
                rooms.add(new LiveRoomReport(
                    intValue(roomObject, "roomGridX", -1),
                    intValue(roomObject, "roomGridZ", -1),
                    string(roomObject, "name", ""),
                    roomType(string(roomObject, "type", RoomType.UNKNOWN.name())),
                    intValue(roomObject, "secrets", 0),
                    intValue(roomObject, "crypts", 0),
                    intValue(roomObject, "roomSecretsFound", 0),
                    intValue(roomObject, "roomSecretsMax", 0),
                    booleanValue(roomObject, "visited"),
                    booleanValue(roomObject, "cleared"),
                    booleanValue(roomObject, "completed"),
                    source,
                    updatedAt
                ));
            }

            JsonElement doorsElement = clientObject.get("doors");
            if (doorsElement != null && doorsElement.isJsonArray()) {
                for (JsonElement doorElement : doorsElement.getAsJsonArray()) {
                    if (doorElement == null || !doorElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject doorObject = doorElement.getAsJsonObject();
                    doors.add(new LiveDoorReport(
                        intValue(doorObject, "scanGridX", -1),
                        intValue(doorObject, "scanGridZ", -1),
                        doorKind(string(doorObject, "kind", DungeonDoorKind.NONE.name())),
                        roomType(string(doorObject, "targetType", RoomType.UNKNOWN.name())),
                        booleanValue(doorObject, "targetVisited"),
                        source,
                        updatedAt
                    ));
                }
            }

            JsonElement playersElement = clientObject.get("players");
            if (playersElement == null || !playersElement.isJsonArray()) {
                continue;
            }
            for (JsonElement playerElement : playersElement.getAsJsonArray()) {
                if (playerElement == null || !playerElement.isJsonObject()) {
                    continue;
                }
                JsonObject playerObject = playerElement.getAsJsonObject();
                players.add(new LivePlayerReport(
                    string(playerObject, "name", ""),
                    intValue(playerObject, "secretsFound", -1),
                    intValue(playerObject, "deaths", -1),
                    source,
                    updatedAt,
                    string(playerObject, "secretsSource", "")
                ));
            }
        }
        return new LiveSyncSnapshot(List.copyOf(rooms), List.copyOf(doors), List.copyOf(players));
    }

    private static void send(Minecraft client, KungMessages.Type type, String message) {
        KungMessages.send(client, type, "Server Sync", message);
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String name, int fallback) {
        JsonElement value = object == null ? null : object.get(name);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (NumberFormatException | IllegalStateException exception) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        JsonElement value = object == null ? null : object.get(name);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsLong() : fallback;
        } catch (NumberFormatException | IllegalStateException exception) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static RoomType roomType(String value) {
        try {
            return RoomType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return RoomType.UNKNOWN;
        }
    }

    private static DungeonDoorKind doorKind(String value) {
        try {
            return DungeonDoorKind.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return DungeonDoorKind.NONE;
        }
    }

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    public record LiveRoomReport(
        int roomGridX,
        int roomGridZ,
        String name,
        RoomType type,
        int secrets,
        int crypts,
        int roomSecretsFound,
        int roomSecretsMax,
        boolean visited,
        boolean cleared,
        boolean completed,
        String source,
        long updatedAtMillis
    ) {
    }

    public record LiveDoorReport(
        int scanGridX,
        int scanGridZ,
        DungeonDoorKind kind,
        RoomType targetType,
        boolean targetVisited,
        String source,
        long updatedAtMillis
    ) {
    }

    public record LivePlayerReport(
        String name,
        int secretsFound,
        int deaths,
        String source,
        long updatedAtMillis,
        String secretsSource
    ) {
        public LivePlayerReport(String name, int secretsFound, int deaths, String source, long updatedAtMillis) {
            this(name, secretsFound, deaths, source, updatedAtMillis, "");
        }

        public boolean hasPersonalSecretSource() {
            // Older clients advertised the shared tab count as their own secrets.
            return "API_DELTA".equals(secretsSource) || "PERSONAL".equals(secretsSource);
        }
    }

    public record LiveSyncSnapshot(
        List<LiveRoomReport> rooms,
        List<LiveDoorReport> doors,
        List<LivePlayerReport> players
    ) {
    }

    private record PushResult(String message) {
    }
}
