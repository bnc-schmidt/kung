package com.github.beng420.kung.util;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.DungeonRoomDataSyncClient;
import com.github.beng420.kung.feature.dungeon.KungServerSession;

import com.github.beng420.kung.skyblock.HypixelLocation;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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

    private final HttpClient httpClient = KungHttp.api();

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
        String normalized = HypixelLocation.trimmedName(username);
        if (!KungConfig.get().misc.hypixelApiEnabled()) {
            return CompletableFuture.completedFuture(ProfileResult.error("Hypixel API is disabled"));
        }
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(ProfileResult.error("Hypixel API key is not set"));
        }
        if (!HypixelLocation.isPlayerName(normalized)) {
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
        String normalized = HypixelLocation.trimmedName(username);
        if (!secretsSourceAvailable()) {
            return CompletableFuture.completedFuture(SecretResult.error(NO_SOURCE));
        }
        if (!HypixelLocation.isPlayerName(normalized)) {
            return CompletableFuture.completedFuture(SecretResult.error("invalid username"));
        }
        if (!KungConfig.get().misc.directHypixelApiEnabled()) {
            // The server reads the same lifetime counter with its key: both ends of a run match one source.
            return resolveUsername(normalized)
                .thenCompose(profile -> loadKungServerObject(DungeonRoomDataSyncClient.endpoint("hypixel/secrets"), "uuid",
                        profile.uuid(), KungServerSession.INSTANCE)
                    .thenApply(root -> SecretResult.ok(profile.name(), (int) Json.number(root, "secrets", -1)))
                    .exceptionally(throwable -> SecretResult.error(shortError(throwable))));
        }
        String key = KungConfig.get().misc.hypixelApiKey();
        return resolveUsername(normalized)
            .thenCompose(profile -> loadHypixelObject(HYPIXEL_PLAYER_API, "uuid", profile.uuid(), key)
                .thenApply(playerRoot -> SecretResult.ok(profile.name(), totalSecrets(playerRoot)))
                .exceptionally(throwable -> SecretResult.error(shortError(throwable))));
    }

    public static final String SOURCE_HYPIXEL = "hypixel";
    public static final String SOURCE_KUNG_SERVER = "kung-server";

    /** Where a Sack lookup goes: your own key first, else the Kung server; empty when neither is set up. */
    public static String sackSource(boolean directEnabled, boolean serverConfigured) {
        return directEnabled ? SOURCE_HYPIXEL : serverConfigured ? SOURCE_KUNG_SERVER : "";
    }

    public static String sackSource() {
        return sackSource(KungConfig.get().misc.directHypixelApiEnabled(), DungeonRoomDataSyncClient.INSTANCE.configured());
    }

    /**
     * The Sack counts of one player's selected profile, from {@link #sackSource()}. The Sack Tracker resync and the
     * {@code /kung dev sackapi} probe use it. A limited lookup (429) is not retried.
     */
    public CompletableFuture<SackCounts> loadSackCounts(String uuid) {
        String source = sackSource();
        if (source.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException(NO_SOURCE));
        String plain = uuid.replace("-", "");
        CompletableFuture<JsonObject> root = SOURCE_HYPIXEL.equals(source)
            ? loadHypixelObject(HYPIXEL_PROFILES_API, "uuid", plain, KungConfig.get().misc.hypixelApiKey())
            : loadKungServerObject(DungeonRoomDataSyncClient.endpoint("hypixel/sacks"), "uuid", plain,
                KungServerSession.INSTANCE);
        return root.thenApply(profiles -> parseSackCounts(uuid, profiles).withSource(source));
    }

    /**
     * Item id to amount of the selected profile's Sacks (the first profile when none says "selected"); empty when the
     * profile hides its inventory. The field sits in the member's {@code inventory}, older data had it on the member.
     */
    public static SackCounts parseSackCounts(String uuid, JsonObject profilesRoot) {
        JsonObject chosen = null;
        JsonElement profiles = profilesRoot == null ? null : profilesRoot.get("profiles");
        if (profiles != null && profiles.isJsonArray()) {
            for (JsonElement element : profiles.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject profile = element.getAsJsonObject();
                if (chosen == null || Json.bool(profile, "selected", false)) chosen = profile;
            }
        }
        if (chosen == null) return new SackCounts("", Map.of(), "", "no profiles in the answer");
        JsonObject member = Json.object(Json.object(chosen, "members"), uuid.replace("-", "").toLowerCase(Locale.ROOT));
        JsonObject sacks = Json.object(Json.object(member, "inventory"), "sacks_counts");
        if (sacks == null) sacks = Json.object(member, "sacks_counts");
        Map<String, Long> counts = new TreeMap<>();
        if (sacks != null) {
            sacks.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    counts.put(entry.getKey(), entry.getValue().getAsLong());
                }
            });
        }
        return new SackCounts(Json.string(chosen, "cute_name", ""), counts, "", sacks == null ? missingSacks(member) : "");
    }

    /** Why a member has no Sack counts: absent, or an answer without the {@code inventory} object (the Inventory API is off, or the route cut it). */
    private static String missingSacks(JsonObject member) {
        if (member == null) return "member not found";
        JsonObject inventory = Json.object(member, "inventory");
        return "member keys=" + String.join(",", new java.util.TreeSet<>(member.keySet()))
            + (inventory == null ? "; no inventory object" : "; inventory keys=" + String.join(",", new java.util.TreeSet<>(inventory.keySet())));
    }

    private CompletableFuture<ProfileResult> loadPlayerFromKungServer(String username) {
        String normalized = HypixelLocation.trimmedName(username);
        if (!HypixelLocation.isPlayerName(normalized)) {
            return CompletableFuture.completedFuture(ProfileResult.error("invalid username"));
        }
        return resolveUsername(normalized)
            .thenCompose(this::loadKungServer)
            .exceptionally(throwable -> ProfileResult.error(shortError(throwable)));
    }

    private CompletableFuture<MinecraftProfile> resolveUsername(String username) {
        URI uri = URI.create(MOJANG_PROFILE_API + URLEncoder.encode(username, StandardCharsets.UTF_8));
        HttpRequest request = KungHttp.json(uri, 8, "Kung-HypixelProfile").GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (!KungHttp.isOk(response.statusCode())) {
                    throw new IllegalStateException("Mojang returned HTTP " + response.statusCode());
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                String id = Json.string(root, "id", "");
                String name = Json.string(root, "name", username);
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
        HttpRequest.Builder request = KungHttp.json(uri, 15, "Kung-HypixelProfile").GET();
        // Sign in first: a refused sign-in fails once instead of being retried like a flaky connection.
        return session.token().thenCompose(ignored -> loadObject("Kung server", () -> session.send(request), 1));
    }

    private CompletableFuture<JsonObject> loadHypixelObject(URI baseUri, String parameter, String value, String apiKey) {
        String query = baseUri + "?" + parameter + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        return loadObject("Hypixel", () -> httpClient.sendAsync(
            KungHttp.json(URI.create(query + "&_kungFresh=" + System.currentTimeMillis()), 12, "Kung-HypixelProfile")
                .header("API-Key", apiKey)
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
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
                if (!KungHttp.isOk(response.statusCode())) {
                    IllegalStateException error =
                        new IllegalStateException(httpError(service, response.statusCode(), response.body()));
                    if (shouldRetry(response.statusCode()) && attempt < API_ATTEMPTS) {
                        return retryObject(service, send, attempt);
                    }
                    return CompletableFuture.failedFuture(error);
                }
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!Json.bool(root, "success", false)) {
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

    /**
     * Server errors may pass in a moment. A 429 will not: retrying it 300 and 600 ms later only spends the per-player
     * budget of the Kung server (every attempt counts) and the Hypixel quota, so one limited lookup became three.
     */
    private static boolean shouldRetry(int statusCode) {
        return statusCode >= 500;
    }

    private ProfileResult parse(MinecraftProfile minecraftProfile, JsonObject playerRoot, JsonObject profilesRoot) {
        return parseProfiles(minecraftProfile.uuid(), minecraftProfile.name(), playerRoot, profilesRoot);
    }

    static ProfileResult parseProfiles(String uuid, String name, JsonObject playerRoot, JsonObject profilesRoot) {
        if (profilesRoot == null || !Json.bool(profilesRoot, "success", false)) {
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
            JsonObject member = Json.object(Json.object(profileObject, "members"), memberKey);
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
        return (int) Json.number(Json.object(Json.object(playerRoot, "player"), "achievements"),
            "skyblock_treasure_hunter", -1.0);
    }

    private static ProfileData parseProfile(JsonObject profileObject, JsonObject member) {
        JsonObject dungeons = Json.object(member, "dungeons");
        JsonObject dungeonTypes = Json.object(dungeons, "dungeon_types");
        JsonObject catacombs = Json.object(dungeonTypes, "catacombs");
        JsonObject playerClasses = Json.object(dungeons, "player_classes");
        EnumMap<DungeonClass, Double> classXp = new EnumMap<>(DungeonClass.class);
        EnumMap<DungeonClass, Integer> classPerks = new EnumMap<>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            JsonObject classObject = Json.object(playerClasses, classId(dungeonClass));
            classXp.put(dungeonClass, Json.number(classObject, "experience", 0.0));
            classPerks.put(dungeonClass, classPerk(member, dungeonClass));
        }
        return new ProfileData(
            Json.string(profileObject, "profile_id", ""),
            Json.string(profileObject, "cute_name", "Profile"),
            Json.bool(profileObject, "selected", false),
            Json.number(catacombs, "experience", 0.0),
            classXp,
            classPerks,
            parseDungeonStats(dungeons, member)
        );
    }

    private static CatacombsAverageCalculator.DungeonStats parseDungeonStats(JsonObject dungeons, JsonObject member) {
        JsonObject types = Json.object(dungeons, "dungeon_types");
        JsonObject daily = Json.object(dungeons, "daily_runs");
        long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
        int dailyRuns = (long) Json.number(daily, "current_day_stamp", -1) == today
            ? (int) Json.number(daily, "completed_runs_count", 0) : 0;
        JsonObject journal = Json.object(dungeons, "dungeon_journal");
        JsonElement unlocked = journal == null ? null : journal.get("unlocked_journals");
        DungeonClass selectedClass = null;
        String selectedId = Json.string(dungeons, "selected_dungeon_class", "");
        for (DungeonClass value : DungeonClass.values()) {
            if (value.id().equals(selectedId)) selectedClass = value;
        }
        return new CatacombsAverageCalculator.DungeonStats(dungeons != null, selectedClass,
            (long) Json.number(dungeons, "secrets", -1), dailyRuns,
            unlocked != null && unlocked.isJsonArray() ? unlocked.getAsJsonArray().size() : 0,
            parseFloorStats(Json.object(types, "catacombs")),
            parseFloorStats(Json.object(types, "master_catacombs")), explorerAttributeLevel(member));
    }

    static int explorerAttributeLevel(JsonObject member) {
        JsonObject stacks = Json.object(Json.object(member, "attributes"), "stacks");
        if (stacks == null) return -1;
        double syphoned = Json.number(stacks, "catacombs_explorer", 0);
        int level = 0;
        while (level < EXPLORER_SHARD_THRESHOLDS.length && syphoned >= EXPLORER_SHARD_THRESHOLDS[level]) level++;
        return level;
    }

    private static CatacombsAverageCalculator.FloorStats parseFloorStats(JsonObject object) {
        return new CatacombsAverageCalculator.FloorStats(
            intMap(Json.object(object, "best_score")),
            intMap(Json.object(object, "milestone_completions")),
            intMap(Json.object(object, "fastest_time_s_plus")),
            intMap(Json.object(object, "fastest_time_s")));
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
        JsonObject perks = Json.object(Json.object(member, "player_data"), "perks");
        return (int) Math.round(Json.number(perks, classPerkId(dungeonClass), 0.0));
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

        HttpRequest request = KungHttp.json(URI.create(HYPIXEL_PROFILES_API + "?uuid=" + uuid), 8, "Kung-HypixelProfile")
            .header("API-Key", key)
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

    private static String shortError(Throwable throwable) {
        return KungErrors.brief(throwable.getCause() != null ? throwable.getCause() : throwable);
    }

    /** A profile's name and its Sack amounts by item id (sorted); {@code source}: where the lookup went; {@code note}: why there are none. */
    public record SackCounts(String profile, Map<String, Long> counts, String source, String note) {
        SackCounts withSource(String source) {
            return new SackCounts(profile, counts, source, note);
        }
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
