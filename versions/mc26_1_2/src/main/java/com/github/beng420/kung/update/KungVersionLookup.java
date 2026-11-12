package com.github.beng420.kung.update;

import com.github.beng420.kung.util.Json;
import com.github.beng420.kung.util.KungHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

/**
 * The newest Kung version on GitHub and on Modrinth, for the "!kung" chat command. A Modrinth project that is not
 * approved yet answers 404 (or lists no version), which reads "awaiting approval". Both lookups are cached for ten
 * minutes, so a party asking over and over costs one request each.
 */
public final class KungVersionLookup {
    public static final KungVersionLookup INSTANCE = new KungVersionLookup();
    /** The repo is bnc-schmidt/kung; the account was beng420 before, and GitHub redirects the old name. The chat shows the old one. */
    private static final String REPO = "bnc-schmidt/kung";
    static final String SHOWN_REPO = "beng420/kung";
    static final String AWAITING = "awaiting approval";
    /** The Modrinth project was created on this day (its analytics chart starts there); the wait is counted from it. */
    static final LocalDate WAITING_SINCE = LocalDate.of(2026, 5, 20);
    private static final String UNAVAILABLE = "unavailable";
    private static final URI GITHUB_LATEST = URI.create("https://api.github.com/repos/" + REPO + "/releases/latest");
    // ponytail: the slug is a guess ("kung"); change it here when the Modrinth project is named otherwise.
    private static final URI MODRINTH_VERSIONS = URI.create("https://api.modrinth.com/v2/project/kung/version");
    private static final long CACHE_MILLIS = 10 * 60_000L;

    private record Versions(String github, String modrinth) { }

    private Versions cached;
    private long cachedAt;
    private CompletableFuture<Versions> pending;

    private KungVersionLookup() { }

    /** The whole chat line; completes with "unavailable" parts instead of failing. */
    public synchronized CompletableFuture<String> message() {
        String installed = KungUpdater.currentVersion();
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(line(installed, cached.github(), cached.modrinth(), LocalDate.now()));
        }
        if (pending == null) {
            pending = fetch(GITHUB_LATEST).thenCombine(fetch(MODRINTH_VERSIONS), (github, modrinth) -> new Versions(
                githubVersion(github.status(), github.body()), modrinthVersion(modrinth.status(), modrinth.body())))
                .whenComplete((versions, error) -> finish(versions));
        }
        return pending.thenApply(versions -> line(installed, versions.github(), versions.modrinth(), LocalDate.now()));
    }

    private synchronized void finish(Versions versions) {
        pending = null;
        // A failed lookup is asked again next time.
        if (versions != null && !UNAVAILABLE.equals(versions.github()) && !UNAVAILABLE.equals(versions.modrinth())) {
            cached = versions;
            cachedAt = System.currentTimeMillis();
        }
    }

    private record Answer(int status, String body) { }

    private static CompletableFuture<Answer> fetch(URI uri) {
        var request = KungHttp.json(uri, 8, "Kung-Version-Check").GET().build();
        return KungHttp.api().sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> new Answer(response.statusCode(), response.body()))
            .exceptionally(error -> new Answer(-1, ""));
    }

    static String line(String installed, String github, String modrinth, LocalDate today) {
        return "Kung v" + installed + " | github (repo " + SHOWN_REPO + "): " + github + " | modrinth: "
            + (AWAITING.equals(modrinth) ? awaiting(today) : modrinth);
    }

    /** "awaiting approval (175 days)": how long the project has waited. */
    static String awaiting(LocalDate today) {
        long days = ChronoUnit.DAYS.between(WAITING_SINCE, today);
        return days < 0 ? AWAITING : AWAITING + " (" + days + (days == 1 ? " day)" : " days)");
    }

    /** "v0.8.3" from the latest release's tag; "none yet" before the first release. */
    static String githubVersion(int status, String body) {
        if (status == 404) return "none yet";
        if (status != 200) return UNAVAILABLE;
        try {
            return prefixed(Json.string(JsonParser.parseString(body).getAsJsonObject(), "tag_name", ""));
        } catch (RuntimeException invalid) {
            return UNAVAILABLE;
        }
    }

    /** The newest listed version (Modrinth lists newest first), or "awaiting approval" while the project lists none. */
    static String modrinthVersion(int status, String body) {
        if (status == 404) return AWAITING;
        if (status != 200) return UNAVAILABLE;
        try {
            JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
            if (versions.isEmpty()) return AWAITING;
            JsonObject newest = versions.get(0).getAsJsonObject();
            return prefixed(Json.string(newest, "version_number", ""));
        } catch (RuntimeException invalid) {
            return UNAVAILABLE;
        }
    }

    /** A version that starts with a digit gets the "v" the other numbers carry. */
    private static String prefixed(String version) {
        String trimmed = version == null ? "" : version.trim();
        if (trimmed.isEmpty()) return UNAVAILABLE;
        return Character.isDigit(trimmed.charAt(0)) ? "v" + trimmed : trimmed;
    }
}
