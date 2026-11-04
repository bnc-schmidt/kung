package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.util.Json;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.KungHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class SkyBlockMayorTracker {
    public static final SkyBlockMayorTracker INSTANCE = new SkyBlockMayorTracker();

    private static final URI ELECTION_URI =
        URI.create("https://api.hypixel.net/v2/resources/skyblock/election");
    private static final long SUCCESS_REFRESH_INTERVAL_MILLIS = 30L * 60L * 1000L;
    private static final long FAILED_REFRESH_INTERVAL_MILLIS = 2L * 60L * 1000L;
    private static final long FEAST_REFRESH_INTERVAL_MILLIS = 60_000L;
    // Aura wins over Derpy when mayor and minister carry one each.
    private static final List<MayorXpBoost> XP_BOOSTS = List.of(
        new MayorXpBoost("aura", 1.59, "Aura (+59%)"), new MayorXpBoost("derpy", 1.5, "Derpy (+50%)"));
    private static final MayorXpBoost NO_XP_BOOST = new MayorXpBoost("", 1.0, "None");

    private final HttpClient httpClient = KungHttp.api();
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
        return (paulScoreBonusActive && !termOver(System.currentTimeMillis())) || "Paul".equals(jerryCopiedMayor());
    }

    public boolean jerryInOffice() {
        return jerryInOffice && !termOver(System.currentTimeMillis());
    }

    /** The mayor Jerry copies right now, or "" when Jerry is not in office or the copy is not known yet. */
    public String jerryCopiedMayor() {
        return jerryInOffice() ? JerryPerkpocalypse.INSTANCE.copiedMayor(electionYear, System.currentTimeMillis()) : "";
    }

    /**
     * The answer held is for a term that has ended by the calendar: the election API (or its cache) is still on the
     * old mayor. Everything read from it is stale until it names the new election year.
     */
    boolean termOver(long nowMillis) {
        return electionYear >= 0 && nowMillis >= JerryTimeline.termEndMillis(electionYear);
    }

    public long electionYear() {
        return electionYear;
    }

    public double catacombsXpMultiplier() {
        return termOver(System.currentTimeMillis()) ? 1.0 : catacombsXpMultiplier;
    }

    public String catacombsXpBoostLabel() {
        return termOver(System.currentTimeMillis()) ? "None" : catacombsXpBoostLabel;
    }

    /** The mayor in office, or "" before the API answered and after the term it described has ended. */
    public String mayorName() {
        return termOver(System.currentTimeMillis()) ? "" : mayorName;
    }

    public String ministerName() {
        return termOver(System.currentTimeMillis()) ? "" : ministerName;
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
        // After a term's end the old answer is stale: ask again every couple of minutes until the new mayor shows.
        boolean staleRefresh = termOver(now) && now - lastAttemptMillis >= FAILED_REFRESH_INTERVAL_MILLIS;
        if (now < nextRefreshAfterMillis && !feastRefresh && !staleRefresh) {
            return;
        }
        refreshAsync(now);
    }

    private void refreshAsync(long nowMillis) {
        requestInFlight = true;
        lastAttemptMillis = nowMillis;
        nextRefreshAfterMillis = nowMillis + FAILED_REFRESH_INTERVAL_MILLIS;
        HttpRequest request = KungHttp.json(ELECTION_URI, 10, "Kung-MayorTracker").GET().build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, throwable) -> {
                try {
                    if (throwable != null) {
                        markError(throwable);
                        return;
                    }
                    if (!KungHttp.isOk(response.statusCode())) {
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
        if (root == null || !Json.bool(root, "success", false)) {
            throw new IllegalStateException("invalid response");
        }

        JsonObject mayor = Json.object(root, "mayor");
        String parsedMayorName = Json.string(mayor, "name", "");
        JsonObject minister = Json.object(mayor, "minister");
        String parsedMinisterName = Json.string(minister, "name", "");
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
        String first = mayor.trim().toLowerCase(Locale.ROOT);
        String second = minister.trim().toLowerCase(Locale.ROOT);
        return XP_BOOSTS.stream().filter(boost -> boost.mayor().equals(first) || boost.mayor().equals(second))
            .findFirst().orElse(NO_XP_BOOST);
    }

    static FeastStatus parseFeastStatus(JsonObject mayor, long nowMillis) {
        // Candidate history also contains Grand Feast: only the elected perks are active.
        boolean active = hasElectedPerk(mayor, "Grand Feast"::equals);
        // Without a term identity, stale progress cannot safely cross an election.
        long year = Json.longValue(Json.object(mayor, "election"), "year", -1);
        return new FeastStatus(active, year, nowMillis + 2 * FEAST_REFRESH_INTERVAL_MILLIS);
    }

    static boolean parsePaulScoreActive(JsonObject mayor) {
        // The +10 score is the elected EZPZ perk, not the name Paul or his candidate perks.
        return hasElectedPerk(mayor, "EZPZ"::equalsIgnoreCase);
    }

    /** The mayor's elected perks and the minister's one: true when one name passes {@code test}. */
    private static boolean hasElectedPerk(JsonObject mayor, Predicate<String> test) {
        JsonElement perks = mayor == null ? null : mayor.get("perks");
        if (perks != null && perks.isJsonArray()) {
            for (JsonElement perk : perks.getAsJsonArray()) {
                if (perk.isJsonObject() && test.test(perkName(perk.getAsJsonObject()))) return true;
            }
        }
        return test.test(perkName(Json.object(Json.object(mayor, "minister"), "perk")));
    }

    private static String perkName(JsonObject perk) {
        return HypixelLocation.clean(Json.string(perk, "name", ""));
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

    private record MayorXpBoost(String mayor, double multiplier, String label) {
    }
}
