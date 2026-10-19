package com.github.beng420.kung.util;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.DungeonRoomDataSyncClient;
import com.github.beng420.kung.feature.dungeon.KungServerSession;

import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.PlayerData;
import com.github.beng420.kung.util.CatacombsAverageCalculator.ProfileData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class HypixelSkyBlockProfileClient {
    public static final HypixelSkyBlockProfileClient INSTANCE = new HypixelSkyBlockProfileClient();

    private static final URI MOJANG_PROFILE_API =
        URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/");
    private static final URI HYPIXEL_PLAYER_API = URI.create("https://api.hypixel.net/v2/player");
    private static final URI HYPIXEL_PROFILES_API = URI.create("https://api.hypixel.net/v2/skyblock/profiles");
    static final String NO_SOURCE = "no Hypixel API key or Kung server set";
    private static final String ERROR_SEPARATOR = " | ";
    private static final int API_ATTEMPTS = 3;
    // Bonzo / Catacombs Explorer is Epic. API stacks count cumulative syphoned shards.
    private static final int[] EXPLORER_SHARD_THRESHOLDS = {1, 2, 4, 6, 9, 12, 16, 20, 25, 32};

    /** Result of the last key test, so the settings row can show more than "a key is present". */
    private volatile String lastKeyCheck = "";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private HypixelSkyBlockProfileClient() {
    }

    /**
     * Shared by the chat commands and calculator screen: your own key first, then the Kung server's. For yourself,
     * a lookup also refreshes the local copy of your data, which answers when both fail.
     */
    public CompletableFuture<ProfileResult> loadCalculatorPlayer(String username) {
        CompletableFuture<ProfileResult> direct = KungConfig.get().misc.directHypixelApiEnabled()
            ? loadPlayer(username)
            : CompletableFuture.completedFuture(ProfileResult.error(NO_SOURCE));
        return direct.thenCompose(result -> result.success() || !DungeonRoomDataSyncClient.INSTANCE.configured()
                ? CompletableFuture.completedFuture(result)
                : loadPlayerFromKungServer(username).thenApply(server -> combine(result, server)))
            .handle((result, throwable) -> CatacombsLocalStore.INSTANCE.apply(username,
                throwable == null ? result : ProfileResult.error(shortError(throwable))));
    }

    /** Both lookups failed: keep your own key's error next to the server's for the trace; {@link #lastError} is the server's. */
    static ProfileResult combine(ProfileResult direct, ProfileResult server) {
        return server.success() || NO_SOURCE.equals(direct.error()) || direct.error().equals(server.error())
            ? server : ProfileResult.error(direct.error() + ERROR_SEPARATOR + server.error());
    }

    /** What the chat message and calculator screen are built from: the last lookup that was tried. */
    public static String lastError(String error) {
        int cut = error == null ? -1 : error.lastIndexOf(ERROR_SEPARATOR);
        return cut < 0 ? error : error.substring(cut + ERROR_SEPARATOR.length());
    }

    public CompletableFuture<ProfileResult> loadPlayer(String username) {
        String key = KungConfig.get().misc.hypixelApiKey();
        String normalized = username == null ? "" : username.trim();
        if (!KungConfig.get().misc.hypixelApiEnabled()) {
            return CompletableFuture.completedFuture(ProfileResult.error("Hypixel API is disabled"));
        }
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(ProfileResult.error("Hypixel API key is not set"));
        }
        if (!validUsername(normalized)) {
            return CompletableFuture.completedFuture(ProfileResult.error("invalid username"));
        }
        return resolveUsername(normalized)
            .thenCompose(profile -> loadHypixel(profile, key))
            .exceptionally(throwable -> ProfileResult.error(shortError(throwable)));
    }

    /** The run's secret tracking needs one of these: your own key, else the Kung server's. */
    public static boolean secretsSourceAvailable() {
        return KungConfig.get().misc.directHypixelApiEnabled() || DungeonRoomDataSyncClient.INSTANCE.configured();
    }

    public CompletableFuture<SecretResult> loadTotalSecrets(String username) {
        String normalized = username == null ? "" : username.trim();
        if (!secretsSourceAvailable()) {
            return CompletableFuture.completedFuture(SecretResult.error(NO_SOURCE));
        }
        if (!validUsername(normalized)) {
            return CompletableFuture.completedFuture(SecretResult.error("invalid username"));
        }
        if (!KungConfig.get().misc.directHypixelApiEnabled()) {
            // The server reads the same lifetime counter with its key: both ends of a run match one source.
            return resolveUsername(normalized)
                .thenCompose(profile -> loadKungServerObject(DungeonRoomDataSyncClient.endpoint("hypixel/secrets"), "uuid",
                        profile.uuid(), KungServerSession.INSTANCE)
                    .thenApply(root -> SecretResult.ok(profile.name(), (int) number(root, "secrets", -1)))
                    .exceptionally(throwable -> SecretResult.error(shortError(throwable))));
        }
        String key = KungConfig.get().misc.hypixelApiKey();
        return resolveUsername(normalized)
            .thenCompose(profile -> loadHypixelObject(HYPIXEL_PLAYER_API, "uuid", profile.uuid(), key)
                .thenApply(playerRoot -> SecretResult.ok(profile.name(), totalSecrets(playerRoot)))
                .exceptionally(throwable -> SecretResult.error(shortError(throwable))));
    }

    private CompletableFuture<ProfileResult> loadPlayerFromKungServer(String username) {
        String normalized = username == null ? "" : username.trim();
        if (!validUsername(normalized)) {
            return CompletableFuture.completedFuture(ProfileResult.error("invalid username"));
        }
        return resolveUsername(normalized)
            .thenCompose(this::loadKungServer)
            .exceptionally(throwable -> ProfileResult.error(shortError(throwable)));
    }

    private CompletableFuture<MinecraftProfile> resolveUsername(String username) {
        URI uri = URI.create(MOJANG_PROFILE_API + URLEncoder.encode(username, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .header("User-Agent", "Kung-HypixelProfile")
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Mojang returned HTTP " + response.statusCode());
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                String id = string(root, "id", "");
                String name = string(root, "name", username);
                if (id.isBlank()) {
                    throw new IllegalStateException("Mojang returned no UUID");
                }
                return new MinecraftProfile(id, name);
            });
    }

    private CompletableFuture<ProfileResult> loadHypixel(MinecraftProfile profile, String apiKey) {
        return loadHypixelObject(HYPIXEL_PROFILES_API, "uuid", profile.uuid(), apiKey)
            .thenApply(profilesRoot -> parse(profile, new JsonObject(), profilesRoot).withSource("hypixel"))
            .exceptionally(throwable -> ProfileResult.error(shortError(throwable)));
    }

    private CompletableFuture<ProfileResult> loadKungServer(MinecraftProfile profile) {
        // The server holds the Hypixel key and answers with Hypixel's own profiles JSON.
        return loadKungServerObject(DungeonRoomDataSyncClient.endpoint("hypixel/profiles"), "uuid", profile.uuid(),
                KungServerSession.INSTANCE)
            .thenApply(profilesRoot -> parse(profile, new JsonObject(), profilesRoot).withSource("kung-server"));
    }

    private CompletableFuture<JsonObject> loadKungServerObject(URI baseUri, String parameter, String value, KungServerSession session) {
        URI uri = URI.create(baseUri + "?" + parameter + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("User-Agent", "Kung-HypixelProfile")
            .GET();
        // Sign in first: a refused sign-in fails once instead of being retried like a flaky connection.
        return session.token().thenCompose(ignored -> loadObject("Kung server", () -> session.send(request), 1));
    }

    private CompletableFuture<JsonObject> loadHypixelObject(URI baseUri, String parameter, String value, String apiKey) {
        String query = baseUri + "?" + parameter + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        return loadObject("Hypixel", () -> httpClient.sendAsync(
            HttpRequest.newBuilder(URI.create(query + "&_kungFresh=" + System.currentTimeMillis()))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("API-Key", apiKey)
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
                .header("User-Agent", "Kung-HypixelProfile")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()), 1);
    }

    private CompletableFuture<JsonObject> loadObject(
        String service, Supplier<CompletableFuture<HttpResponse<String>>> send, int attempt
    ) {
        // Resend through the supplier each attempt so provider-specific freshness timestamps advance on retries.
        return send.get()
            .<CompletableFuture<JsonObject>>handle((response, throwable) -> {
                if (throwable != null) {
                    if (attempt < API_ATTEMPTS) {
                        return retryObject(service, send, attempt);
                    }
                    return CompletableFuture.failedFuture(throwable);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    IllegalStateException error =
                        new IllegalStateException(httpError(service, response.statusCode(), response.body()));
                    if (shouldRetry(response.statusCode()) && attempt < API_ATTEMPTS) {
                        return retryObject(service, send, attempt);
                    }
                    return CompletableFuture.failedFuture(error);
                }
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!bool(root, "success")) {
                        throw new IllegalStateException(service + " returned success=false");
                    }
                    return CompletableFuture.completedFuture(root);
                } catch (RuntimeException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            })
            .thenCompose(future -> future);
    }

    /**
     * The status plus the reason from a JSON error body ({"cause":"Invalid API key"}); a block page adds nothing.
     * Only the response body is quoted, never the request, so no key or token can end up in it.
     */
    static String httpError(String service, int statusCode, String body) {
        String message = service + " returned HTTP " + statusCode;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String field : new String[] {"cause", "error", "message"}) {
                JsonElement value = root.get(field);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String reason = value.getAsString().replaceAll("[\\p{Cntrl}|]+", " ").replaceAll("\\s+", " ").trim();
                    if (!reason.isEmpty()) {
                        return message + ": " + (reason.length() > 100 ? reason.substring(0, 100) : reason);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Not a JSON object (an HTML block page, an empty body): the status alone is the message.
        }
        return message;
    }

    private CompletableFuture<JsonObject> retryObject(
        String service, Supplier<CompletableFuture<HttpResponse<String>>> send, int previousAttempt
    ) {
        return CompletableFuture.supplyAsync(
                () -> null,
                CompletableFuture.delayedExecutor(300L * previousAttempt, TimeUnit.MILLISECONDS)
            )
            .thenCompose(ignored -> loadObject(service, send, previousAttempt + 1));
    }

    private static boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private ProfileResult parse(MinecraftProfile minecraftProfile, JsonObject playerRoot, JsonObject profilesRoot) {
        return parseProfiles(minecraftProfile.uuid(), minecraftProfile.name(), playerRoot, profilesRoot);
    }

    static ProfileResult parseProfiles(String uuid, String name, JsonObject playerRoot, JsonObject profilesRoot) {
        if (profilesRoot == null || !bool(profilesRoot, "success")) {
            return ProfileResult.error("Profile API returned no successful data");
        }
        int secrets = totalSecrets(playerRoot);
        JsonElement profilesElement = profilesRoot.get("profiles");
        if (profilesElement == null || !profilesElement.isJsonArray()) {
            return ProfileResult.error("Hypixel returned no SkyBlock profiles");
        }

        java.util.ArrayList<ProfileData> profiles = new java.util.ArrayList<>();
        int selectedIndex = -1;
        int fallbackIndex = 0;
        double bestDungeonXp = -1.0;
        String memberKey = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        for (JsonElement element : profilesElement.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject profileObject = element.getAsJsonObject();
            JsonObject member = objectMember(objectMember(profileObject, "members"), memberKey);
            if (member == null) {
                continue;
            }
            ProfileData profile = parseProfile(profileObject, member);
            int index = profiles.size();
            profiles.add(profile);
            if (profile.selected() && selectedIndex < 0) {
                selectedIndex = index;
            }
            // last_save is absent from current responses. Never let a later empty profile
            // replace selected=true; without that flag use the strongest dungeon profile.
            if (profile.totalDungeonXp() > bestDungeonXp) {
                bestDungeonXp = profile.totalDungeonXp();
                fallbackIndex = index;
            }
        }
        if (profiles.isEmpty()) {
            return ProfileResult.error("no SkyBlock profile found");
        }
        return ProfileResult.ok(new PlayerData(name, profiles, selectedIndex >= 0 ? selectedIndex : fallbackIndex), secrets);
    }

    private static int totalSecrets(JsonObject playerRoot) {
        return (int) number(objectMember(objectMember(playerRoot, "player"), "achievements"),
            "skyblock_treasure_hunter", -1.0);
    }

    private static ProfileData parseProfile(JsonObject profileObject, JsonObject member) {
        JsonObject dungeons = objectMember(member, "dungeons");
        JsonObject dungeonTypes = objectMember(dungeons, "dungeon_types");
        JsonObject catacombs = objectMember(dungeonTypes, "catacombs");
        JsonObject playerClasses = objectMember(dungeons, "player_classes");
        EnumMap<DungeonClass, Double> classXp = new EnumMap<>(DungeonClass.class);
        EnumMap<DungeonClass, Integer> classPerks = new EnumMap<>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            JsonObject classObject = objectMember(playerClasses, classId(dungeonClass));
            classXp.put(dungeonClass, number(classObject, "experience", 0.0));
            classPerks.put(dungeonClass, classPerk(member, dungeonClass));
        }
        return new ProfileData(
            string(profileObject, "profile_id", ""),
            string(profileObject, "cute_name", "Profile"),
            bool(profileObject, "selected"),
            number(catacombs, "experience", 0.0),
            classXp,
            classPerks,
            parseDungeonStats(dungeons, member)
        );
    }

    private static CatacombsAverageCalculator.DungeonStats parseDungeonStats(JsonObject dungeons, JsonObject member) {
        JsonObject types = objectMember(dungeons, "dungeon_types");
        JsonObject daily = objectMember(dungeons, "daily_runs");
        long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
        int dailyRuns = (long) number(daily, "current_day_stamp", -1) == today
            ? (int) number(daily, "completed_runs_count", 0) : 0;
        JsonObject journal = objectMember(dungeons, "dungeon_journal");
        JsonElement unlocked = journal == null ? null : journal.get("unlocked_journals");
        DungeonClass selectedClass = null;
        String selectedId = string(dungeons, "selected_dungeon_class", "");
        for (DungeonClass value : DungeonClass.values()) {
            if (value.id().equals(selectedId)) selectedClass = value;
        }
        return new CatacombsAverageCalculator.DungeonStats(dungeons != null, selectedClass,
            (long) number(dungeons, "secrets", -1), dailyRuns,
            unlocked != null && unlocked.isJsonArray() ? unlocked.getAsJsonArray().size() : 0,
            parseFloorStats(objectMember(types, "catacombs")),
            parseFloorStats(objectMember(types, "master_catacombs")), explorerAttributeLevel(member));
    }

    static int explorerAttributeLevel(JsonObject member) {
        JsonObject stacks = objectMember(objectMember(member, "attributes"), "stacks");
        if (stacks == null) return -1;
        double syphoned = number(stacks, "catacombs_explorer", 0);
        int level = 0;
        while (level < EXPLORER_SHARD_THRESHOLDS.length && syphoned >= EXPLORER_SHARD_THRESHOLDS[level]) level++;
        return level;
    }

    private static CatacombsAverageCalculator.FloorStats parseFloorStats(JsonObject object) {
        return new CatacombsAverageCalculator.FloorStats(
            intMap(objectMember(object, "best_score")),
            intMap(objectMember(object, "milestone_completions")),
            intMap(objectMember(object, "fastest_time_s_plus")),
            intMap(objectMember(object, "fastest_time_s")));
    }

    private static Map<String, Integer> intMap(JsonObject object) {
        Map<String, Integer> values = new HashMap<>();
        if (object != null) {
            for (var entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    values.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
        }
        return Map.copyOf(values);
    }

    private static String classId(DungeonClass dungeonClass) {
        return dungeonClass == DungeonClass.BERSERK ? "berserk" : dungeonClass.id();
    }

    private static int classPerk(JsonObject member, DungeonClass dungeonClass) {
        JsonObject perks = objectMember(objectMember(member, "player_data"), "perks");
        return (int) Math.round(number(perks, classPerkId(dungeonClass), 0.0));
    }

    private static String classPerkId(DungeonClass dungeonClass) {
        return switch (dungeonClass) {
            case ARCHER -> "toxophilite";
            case BERSERK -> "unbridled_rage";
            case HEALER -> "heart_of_gold";
            case MAGE -> "cold_efficiency";
            case TANK -> "diamond_in_the_rough";
        };
    }

    public String statusMessage() {
        KungConfig config = KungConfig.get();
        if (config.misc.hypixelApiKey().isBlank()) {
            return config.misc.hypixelApiEnabled() ? "No key set" : "Off, no key set";
        }
        String checked = lastKeyCheck.isBlank() ? "key set, never tested" : lastKeyCheck;
        return (config.misc.hypixelApiEnabled() ? "" : "Off, ") + checked;
    }

    /**
     * One real request with the key. A key that is present says nothing about whether it works, and a
     * wrong one otherwise only shows up as a failing command later.
     */
    public CompletableFuture<String> verifyKey(String uuid) {
        String key = KungConfig.get().misc.hypixelApiKey();
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(report("no key set"));
        }
        if (uuid == null || uuid.isBlank()) {
            return CompletableFuture.completedFuture(report("no player to test with"));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(HYPIXEL_PROFILES_API + "?uuid=" + uuid))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .header("API-Key", key)
            .header("User-Agent", "Kung-HypixelProfile")
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> report(switch (response.statusCode()) {
                case 200 -> "key works";
                case 403 -> "key rejected";
                case 429 -> "key works, rate limited";
                default -> "Hypixel returned HTTP " + response.statusCode();
            }))
            .exceptionally(throwable -> report(shortError(throwable)));
    }

    private String report(String message) {
        lastKeyCheck = message;
        return message;
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(name);
    }

    private static String string(JsonObject object, String name, String fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static double number(JsonObject object, String name, double fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return false;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static boolean validUsername(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{3,16}");
    }

    private static String shortError(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public record ProfileResult(boolean success, PlayerData player, int secretsFound, String error, String source,
                                long localUpdatedMillis) {
        /** Answered from the local player's saved data because every lookup failed. */
        public boolean local() {
            return localUpdatedMillis > 0L;
        }

        ProfileResult withSource(String source) {
            return new ProfileResult(success, player, secretsFound, error, source, localUpdatedMillis);
        }

        ProfileResult withPlayer(PlayerData player) {
            return new ProfileResult(success, player, secretsFound, error, source, localUpdatedMillis);
        }

        static ProfileResult ok(PlayerData player, int secretsFound) {
            return new ProfileResult(true, player, secretsFound, "", "", 0L);
        }

        static ProfileResult error(String error) {
            return new ProfileResult(false, null, -1, error, "", 0L);
        }

        static ProfileResult fromLocal(PlayerData player, long updatedMillis) {
            return new ProfileResult(true, player, -1, "", "local", updatedMillis);
        }
    }

    public record SecretResult(boolean success, String name, int secretsFound, String error) {
        static SecretResult ok(String name, int secretsFound) {
            return new SecretResult(true, name, secretsFound, "");
        }

        static SecretResult error(String error) {
            return new SecretResult(false, "", -1, error);
        }
    }

    private record MinecraftProfile(String uuid, String name) {
    }
}
