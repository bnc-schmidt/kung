package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class SkyBlockMayorTracker {
    public static final SkyBlockMayorTracker INSTANCE = new SkyBlockMayorTracker();

    private static final URI ELECTION_URI =
        URI.create("https://api.hypixel.net/v2/resources/skyblock/election");
    private static final long SUCCESS_REFRESH_INTERVAL_MILLIS = 30L * 60L * 1000L;
    private static final long FAILED_REFRESH_INTERVAL_MILLIS = 2L * 60L * 1000L;
    private static final long FEAST_REFRESH_INTERVAL_MILLIS = 60_000L;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private volatile boolean requestInFlight;
    private volatile boolean paulScoreBonusActive;
    private volatile boolean jerryInOffice;
    private volatile long electionYear = -1;
    private volatile double catacombsXpMultiplier = 1.0;
    private volatile String catacombsXpBoostLabel = "None";
    private volatile String mayorName = "";
    private volatile String ministerName = "";
    private volatile String status = "unknown";
    private volatile long nextRefreshAfterMillis;
    private volatile long lastAttemptMillis;
    private volatile FeastStatus feastStatus = new FeastStatus(false, -1, 0L);
    private String lastLoggedState = "";

    private SkyBlockMayorTracker() {
    }

    public static void initializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick(client));
    }

    /** Elected EZPZ, or Jerry currently copying Paul with all his perks. */
    public boolean paulScoreBonusActive() {
        return paulScoreBonusActive || "Paul".equals(jerryCopiedMayor());
    }

    public boolean jerryInOffice() {
        return jerryInOffice;
    }

    /** The mayor Jerry copies right now, or "" when Jerry is not in office or the copy is not known yet. */
    public String jerryCopiedMayor() {
        return jerryInOffice ? JerryPerkpocalypse.INSTANCE.copiedMayor(electionYear, System.currentTimeMillis()) : "";
    }

    public long electionYear() {
        return electionYear;
    }

    public double catacombsXpMultiplier() {
        return catacombsXpMultiplier;
    }

    public String catacombsXpBoostLabel() {
        return catacombsXpBoostLabel;
    }

    public String mayorName() {
        return mayorName;
    }

    public String ministerName() {
        return ministerName;
    }

    public String status() {
        return status;
    }

    public FeastStatus feastStatus() { return feastStatus; }

    public record FeastStatus(boolean grandFeast, long electionYear, long validUntilMillis) {
        public boolean active(long nowMillis) {
            return grandFeast && electionYear >= 0 && nowMillis < validUntilMillis;
        }
    }

    private void tick(Minecraft client) {
        if (client == null || client.player == null || requestInFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean feastRefresh = KungConfig.get().feast.enabled()
            && now - lastAttemptMillis >= FEAST_REFRESH_INTERVAL_MILLIS;
        if (now < nextRefreshAfterMillis && !feastRefresh) {
            return;
        }
        refreshAsync(now);
    }

    private void refreshAsync(long nowMillis) {
        requestInFlight = true;
        lastAttemptMillis = nowMillis;
        nextRefreshAfterMillis = nowMillis + FAILED_REFRESH_INTERVAL_MILLIS;
        HttpRequest request = HttpRequest.newBuilder(ELECTION_URI)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("User-Agent", "Kung-MayorTracker")
            .GET()
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, throwable) -> {
                try {
                    if (throwable != null) {
                        markError(throwable);
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        markError(new IllegalStateException("HTTP " + response.statusCode()));
                        return;
                    }
                    applyResponse(response.body());
                    nextRefreshAfterMillis = System.currentTimeMillis() + SUCCESS_REFRESH_INTERVAL_MILLIS;
                } catch (RuntimeException exception) {
                    markError(exception);
                } finally {
                    requestInFlight = false;
                }
            });
    }

    private void applyResponse(String body) {
        JsonElement rootElement = JsonParser.parseString(body);
        JsonObject root = rootElement != null && rootElement.isJsonObject() ? rootElement.getAsJsonObject() : null;
        if (root == null || !booleanMember(root, "success")) {
            throw new IllegalStateException("invalid response");
        }

        JsonObject mayor = objectMember(root, "mayor");
        String parsedMayorName = stringMember(mayor, "name");
        JsonObject minister = objectMember(mayor, "minister");
        String parsedMinisterName = stringMember(minister, "name");
        boolean active = parsePaulScoreActive(mayor);
        MayorXpBoost xpBoost = mayorXpBoost(parsedMayorName, parsedMinisterName);
        feastStatus = parseFeastStatus(mayor, System.currentTimeMillis());
        electionYear = feastStatus.electionYear();
        jerryInOffice = "jerry".equalsIgnoreCase(parsedMayorName.trim());

        mayorName = parsedMayorName;
        ministerName = parsedMinisterName;
        paulScoreBonusActive = active;
        catacombsXpMultiplier = xpBoost.multiplier();
        catacombsXpBoostLabel = xpBoost.label();
        status = "ok";
        logState();
    }

    private void markError(Throwable throwable) {
        status = "error:" + shortError(throwable);
        nextRefreshAfterMillis = System.currentTimeMillis() + FAILED_REFRESH_INTERVAL_MILLIS;
        KungMod.LOGGER.warn("Failed to fetch SkyBlock mayor data.", throwable);
        logState();
    }

    private void logState() {
        String state = "status=" + status
            + " mayor=" + blank(mayorName)
            + " minister=" + blank(ministerName)
            + " paulScore=" + paulScoreBonusActive
            + " jerryCopies=" + blank(jerryCopiedMayor())
            + " cataXp=" + catacombsXpBoostLabel;
        if (!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            KungDebugRecorder.event("mayor", state);
        }
    }

    private static MayorXpBoost mayorXpBoost(String mayor, String minister) {
        if (isAura(mayor) || isAura(minister)) {
            return new MayorXpBoost(1.59, "Aura (+59%)");
        }
        if (isDerpy(mayor) || isDerpy(minister)) {
            return new MayorXpBoost(1.5, "Derpy (+50%)");
        }
        return new MayorXpBoost(1.0, "None");
    }

    static FeastStatus parseFeastStatus(JsonObject mayor, long nowMillis) {
        // Candidate history also contains Grand Feast: only the elected perks are active.
        boolean active = false;
        JsonElement perks = mayor == null ? null : mayor.get("perks");
        if (perks != null && perks.isJsonArray()) {
            for (JsonElement perk : perks.getAsJsonArray()) {
                if (perk.isJsonObject() && isGrandFeastPerk(perk.getAsJsonObject())) active = true;
            }
        }
        JsonObject minister = objectMember(mayor, "minister");
        active |= isGrandFeastPerk(objectMember(minister, "perk"));
        JsonObject election = objectMember(mayor, "election");
        long year = -1;
        try {
            if (election != null && election.has("year")) year = election.get("year").getAsLong();
        } catch (RuntimeException ignored) {
            // Without a term identity, stale progress cannot safely cross an election.
        }
        return new FeastStatus(active, year, nowMillis + 2 * FEAST_REFRESH_INTERVAL_MILLIS);
    }

    private static boolean isGrandFeastPerk(JsonObject perk) {
        return "Grand Feast".equals(HypixelLocation.clean(stringMember(perk, "name")));
    }

    static boolean parsePaulScoreActive(JsonObject mayor) {
        // The +10 score is the elected EZPZ perk, not the name Paul or his candidate perks.
        JsonElement perks = mayor == null ? null : mayor.get("perks");
        if (perks != null && perks.isJsonArray()) {
            for (JsonElement perk : perks.getAsJsonArray()) {
                if (perk.isJsonObject() && isEzpzPerk(perk.getAsJsonObject())) return true;
            }
        }
        return isEzpzPerk(objectMember(objectMember(mayor, "minister"), "perk"));
    }

    private static boolean isEzpzPerk(JsonObject perk) {
        return "EZPZ".equalsIgnoreCase(HypixelLocation.clean(stringMember(perk, "name")));
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return null;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String stringMember(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return "";
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean booleanMember(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return false;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static boolean isDerpy(String name) {
        return "derpy".equals(name.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isAura(String name) {
        return "aura".equals(name.trim().toLowerCase(Locale.ROOT));
    }

    private static String shortError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String name = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? name : name + "(" + message + ")";
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private record MayorXpBoost(double multiplier, String label) {
    }
}
