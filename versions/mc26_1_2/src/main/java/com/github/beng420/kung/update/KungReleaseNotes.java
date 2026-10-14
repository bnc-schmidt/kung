package com.github.beng420.kung.update;

import com.github.beng420.kung.KungMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Installed-version display/dismissal receipts; GitHub/network work uses the updater worker. */
public final class KungReleaseNotes {
    private static final String API = "https://api.github.com/repos/bnc-schmidt/kung/releases/tags/";
    private static final String RELEASE_PAGE = "https://github.com/bnc-schmidt/kung/releases/tag/";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final String version;
    private final Supplier<Path> receipt;
    private final Executor executor;
    private final Fetcher fetcher;
    private final KungReleaseHistory history;
    private volatile Snapshot snapshot = new Snapshot(Status.IDLE, null);
    private boolean loaded;
    private boolean acknowledged;
    private boolean dismissed;
    private boolean persisted;

    KungReleaseNotes(String version, Supplier<Path> receipt, Executor executor, HttpClient client) {
        this(version, receipt, executor, requested -> fetch(client, requested, URI.create(API)),
            new KungReleaseHistory(executor, client));
    }

    KungReleaseNotes(String version, Supplier<Path> receipt, Executor executor, Fetcher fetcher) {
        this(version, receipt, executor, fetcher, new KungReleaseHistory(executor,
            page -> new KungReleaseHistory.Page(java.util.List.of(), false), tag -> Optional.empty()));
    }

    KungReleaseNotes(String version, Supplier<Path> receipt, Executor executor, Fetcher fetcher, KungReleaseHistory history) {
        this.version = normalizedVersion(version);
        this.receipt = receipt;
        this.executor = executor;
        this.fetcher = fetcher;
        this.history = history;
    }

    public Snapshot snapshot() { return snapshot; }
    public String version() { return version; }
    public KungReleaseHistory history() { return history; }
    public URI releasePage() { return URI.create(RELEASE_PAGE + encoded("v" + version)); }

    /** Called once per settings screen; manual opening bypasses a previous display or explicit dismissal. */
    public boolean openMenu() { return openMenu(false); }

    public synchronized boolean openMenu(boolean force) {
        loadReceipt();
        if ((acknowledged || dismissed) && !force) return false;
        request();
        return true;
    }

    public synchronized void retry() {
        request();
    }

    private void request() {
        if (snapshot.status() == Status.LOADING || snapshot.status() == Status.READY) return;
        snapshot = new Snapshot(Status.LOADING, null);
        executor.execute(() -> {
            try {
                Optional<Notes> notes = fetcher.fetch(version);
                snapshot = notes.map(value -> new Snapshot(Status.READY, value))
                    .orElseGet(() -> new Snapshot(Status.UNAVAILABLE, null));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                KungMod.LOGGER.warn("Could not load Kung release notes for {}.", version, exception);
                snapshot = new Snapshot(Status.FAILED, null);
            }
        });
    }

    /** Only acknowledge the actual notes rendered by the popup, never a loading/error placeholder. */
    public synchronized void acknowledge(Notes displayed) {
        if (acknowledged || displayed == null || snapshot.notes() != displayed || !displayed.version().equals(version)) return;
        acknowledged = true;
        persisted = false;
        executor.execute(this::flushAcknowledgement);
    }

    /** Closing the offer is a choice even if GitHub has no notes yet; it does not claim the body was read. */
    public synchronized void dismiss() {
        loadReceipt();
        if (dismissed) return;
        dismissed = true;
        persisted = false;
        executor.execute(this::flushAcknowledgement);
    }

    /** Also called during client shutdown, so a queued worker write cannot be lost on a quick exit. */
    public synchronized void flushAcknowledgement() {
        if ((!acknowledged && !dismissed) || persisted) return;
        try {
            writeReceipt(receipt.get(), version, acknowledged, dismissed);
            persisted = true;
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Could not save Kung's release-notes receipt.", exception);
        }
    }

    private void loadReceipt() {
        if (loaded) return;
        loaded = true;
        try {
            Path path = receipt.get();
            if (!Files.isRegularFile(path)) return;
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            acknowledged = version.equals(properties.getProperty("lastSeenVersion"));
            dismissed = version.equals(properties.getProperty("lastDismissedVersion"));
            persisted = acknowledged || dismissed;
        } catch (IOException | IllegalArgumentException exception) {
            KungMod.LOGGER.warn("Could not read Kung's displayed release-notes version.", exception);
        }
    }

    private static void writeReceipt(Path path, String version, boolean acknowledged, boolean dismissed) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Properties properties = new Properties();
        if (acknowledged) properties.setProperty("lastSeenVersion", version);
        if (dismissed) properties.setProperty("lastDismissedVersion", version);
        try (var output = Files.newOutputStream(temporary)) {
            properties.store(output, "Kung displayed release notes");
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Optional<Notes> fetch(HttpClient client, String version, URI tagEndpoint) throws IOException, InterruptedException {
        for (String tag : new String[] {"v" + version, version}) {
            var response = requestJson(client, URI.create(tagEndpoint + encoded(tag)));
            if (response.statusCode() == 404) continue;
            return parse(response.body(), version);
        }
        return Optional.empty();
    }

    static Optional<Notes> fetchTag(HttpClient client, String tag, URI tagEndpoint) throws IOException, InterruptedException {
        var response = requestJson(client, URI.create(tagEndpoint + encoded(tag)));
        if (response.statusCode() == 404) return Optional.empty();
        // Historical entries use the exact selected tag, including its prefix and prerelease suffix.
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        return tag.equals(string(object, "tag_name")) ? parse(response.body(), normalizedVersion(tag)) : Optional.empty();
    }

    static HttpResponse<String> requestJson(HttpClient client, URI endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kung-Updater")
            .GET().build();
        // Complete the body within the request timeout, rather than leaving an unbounded stream read.
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("GitHub release response is too large.");
        }
        return response;
    }

    static Optional<Notes> parse(String json, String installedVersion) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String tag = string(object, "tag_name");
        String body = string(object, "body");
        if (!normalizedVersion(tag).equals(normalizedVersion(installedVersion)) || body.isBlank()
            || (object.has("draft") && object.get("draft").getAsBoolean())) return Optional.empty();
        // Build the source URL ourselves; arbitrary links or instructions in release prose are just text.
        return Optional.of(new Notes(normalizedVersion(installedVersion), body,
            URI.create(RELEASE_PAGE + encoded(tag))));
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    static String normalizedVersion(String version) {
        return version == null ? "" : version.strip().replaceFirst("^[vV]", "");
    }

    static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public enum Status { IDLE, LOADING, READY, UNAVAILABLE, FAILED }
    public record Snapshot(Status status, Notes notes) { }
    public record Notes(String version, String body, URI page) { }
    @FunctionalInterface interface Fetcher { Optional<Notes> fetch(String version) throws Exception; }
}
