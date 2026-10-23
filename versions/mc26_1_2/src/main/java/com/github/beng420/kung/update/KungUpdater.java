package com.github.beng420.kung.update;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;

public enum KungUpdater {
    INSTANCE;

    private static final String RELEASE_API = "https://api.github.com/repos/bnc-schmidt/kung/releases/latest";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+(?:\\.\\d+){0,3}).*$", Pattern.CASE_INSENSITIVE);
    private static final List<String> SKIPPED_ASSET_PARTS = List.of("-sources", "-javadoc", "-dev", "-all");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Kung Updater");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean previewing = new AtomicBoolean();
    private final AtomicBoolean installing = new AtomicBoolean();
    private final KungUpdateProcess installerProcess = new KungUpdateProcess();
    private volatile boolean installationBlocked;
    private final AtomicReference<State> state = new AtomicReference<>(State.checking(currentVersion()));
    private final KungUpdateNotification notification = new KungUpdateNotification();
    private final KungUpdateCheckSchedule checkSchedule = new KungUpdateCheckSchedule();
    private final KungUpdateToast toast = new KungUpdateToast();
    private final KungReleaseNotes releaseNotes = new KungReleaseNotes(currentVersion(),
        () -> com.github.beng420.kung.runtime.KungPaths.fileLayout().updateDirectory().resolve("release-notes.properties"),
        executor, httpClient);

    public KungReleaseNotes releaseNotes() { return releaseNotes; }

    public void initializeClientNotifications() {
        toast.initializeClient();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            var server = client.getCurrentServer();
            if (notification.joined(handler.getConnection(), server == null ? null : server.ip, nowMillis())) {
                checkForUpdatesAsync();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Reconfiguration can replace the play listener while retaining the connection.
            if (!handler.getConnection().isConnected()) {
                notification.disconnected(handler.getConnection());
                toast.disconnected(handler.getConnection());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            checkForUpdatesAsync();
            var handler = client.getConnection();
            State snapshot = state.get();
            toast.tick(client, snapshot.status() == Status.UPDATE_AVAILABLE);
            long epoch = HypixelInstanceTracker.INSTANCE.instanceEpoch();
            if (notification.shouldNotify(handler == null ? null : handler.getConnection(),
                epoch, KungUpdateToast.canDisplay(client),
                snapshot.status() == Status.UPDATE_AVAILABLE ? snapshot.latestVersion() : "", toast.active(), nowMillis())) {
                KungDebugRecorder.event("update-notice", "show installed=" + snapshot.currentVersion()
                    + " latest=" + snapshot.latestVersion() + " epoch=" + epoch);
                toast.show(client, "Kung update available", snapshot.currentVersion(), snapshot.latestVersion(), false, () -> {
                    notification.finished(nowMillis());
                    KungDebugRecorder.event("update-notice", "finished cooldownMs=" + KungUpdateNotification.COOLDOWN_MILLIS);
                });
            }
        });
    }

    public void previewNotification(Minecraft client) {
        var handler = client.getConnection();
        if (handler == null || !previewing.compareAndSet(false, true)) return;
        var connection = handler.getConnection();
        // Preview reads fresh data without changing the polling schedule or automatic eligibility.
        executor.execute(() -> {
            State snapshot;
            try {
                snapshot = checkForUpdates();
            } catch (Exception exception) {
                KungMod.LOGGER.warn("Kung update preview check failed.", exception);
                snapshot = new State(Status.FAILED, currentVersion(), "", "Update check failed", null);
            }
            State result = snapshot;
            client.execute(() -> {
                try {
                    var currentHandler = client.getConnection();
                    if (!connection.isConnected() || currentHandler == null
                        || currentHandler.getConnection() != connection) return;
                    if (result.latestVersion().isBlank()) {
                        KungMessages.send(client, KungMessages.Type.ERROR, "Updater",
                            result.status() == Status.FAILED
                                ? "Could not check GitHub. Please try again."
                                : "No published Kung release was found on GitHub.");
                        return;
                    }
                    String title = switch (result.status()) {
                        case UP_TO_DATE -> "Kung is up to date";
                        case UNSUPPORTED -> "New Kung release";
                        default -> "Kung update available";
                    };
                    toast.show(client, title, result.currentVersion(), result.latestVersion(), true, null);
                } finally {
                    previewing.set(false);
                }
            });
        });
    }

    public void checkForUpdatesAsync() {
        // The Modrinth launcher updates that jar; GitHub releases carry the full build.
        if (KungBuild.MODRINTH) return;
        long now = nowMillis();
        State previous = state.get();
        if (!checkSchedule.due(now) || installing.get()
            || previous.status() == Status.DOWNLOADING || previous.status() == Status.INSTALL_READY) {
            return;
        }
        if (!checking.compareAndSet(false, true)) {
            return;
        }

        checkSchedule.started(now);
        KungDebugRecorder.event("update-check", "started current=" + currentVersion());
        // Keep a known release usable while refreshing. A late result must not overwrite installation state.
        executor.execute(() -> {
            try {
                State result;
                try {
                    result = checkForUpdates();
                } catch (Exception exception) {
                    KungMod.LOGGER.warn("Kung update check failed.", exception);
                    // A failed refresh (e.g. GitHub's rate limit) keeps the last answer; only a first check reports FAILED.
                    KungDebugRecorder.event("update-check", "failed reason=" + exception.getMessage()
                        + " retaining=" + previous.status());
                    result = previous.status() != Status.CHECKING ? previous : new State(
                        Status.FAILED, currentVersion(), "", "Update check failed", null);
                }
                boolean applied = state.compareAndSet(previous, result);
                KungDebugRecorder.event("update-check", "finished status=" + result.status()
                    + " latest=" + result.latestVersion() + " applied=" + applied);
            } finally {
                checking.set(false);
            }
        });
    }

    /** The settings button: asks GitHub now instead of at the next interval, and says in chat what it found. */
    public void checkNowAsync(Minecraft client) {
        if (KungBuild.MODRINTH) return;
        long now = nowMillis();
        State previous = state.get();
        if (installing.get() || previous.status() == Status.DOWNLOADING || previous.status() == Status.INSTALL_READY) {
            KungMessages.send(client, KungMessages.Type.INFO, "Updater", "An update is already being installed.");
            return;
        }
        if (!checkSchedule.manualAllowed(now) || !checking.compareAndSet(false, true)) {
            KungMessages.send(client, KungMessages.Type.INFO, "Updater", "Already checking, one moment.");
            return;
        }
        checkSchedule.manualStarted(now);
        KungDebugRecorder.event("update-check", "manual started current=" + currentVersion());
        executor.execute(() -> {
            try {
                State result = checkForUpdates();
                state.compareAndSet(previous, result);
                KungDebugRecorder.event("update-check", "manual finished status=" + result.status()
                    + " latest=" + result.latestVersion());
                KungMessages.send(client, result.status() == Status.UPDATE_AVAILABLE ? KungMessages.Type.SUCCESS
                    : KungMessages.Type.INFO, "Updater", checkResultMessage(result));
            } catch (Exception exception) {
                KungMod.LOGGER.warn("Kung manual update check failed.", exception);
                KungMessages.send(client, KungMessages.Type.ERROR, "Updater",
                    "Could not reach GitHub. Please try again later.");
            } finally {
                checking.set(false);
            }
        });
    }

    private static String checkResultMessage(State result) {
        return switch (result.status()) {
            case UPDATE_AVAILABLE -> "Update v" + result.latestVersion() + " is available (installed: v"
                + result.currentVersion() + "). Install it under Debug > Updates.";
            case UP_TO_DATE -> "Kung is up to date (v" + result.currentVersion() + ", latest: "
                + (result.latestVersion().isBlank() ? "none published" : "v" + result.latestVersion()) + ").";
            default -> "v" + result.latestVersion() + " exists, but " + result.message() + ".";
        };
    }

    private static long nowMillis() { return System.nanoTime() / 1_000_000L; }

    public void initializeInstallation() {
        try {
            installerProcess.holdSession(updateDirectory());
            if (!Files.exists(pendingMarkerPath())) return;
            // Recovery schedules a fresh helper. Never replace Fabric's already loaded JAR at startup.
            KungUpdateInstaller.Plan plan;
            try {
                plan = KungUpdateInstaller.readPlan(updateDirectory());
            } catch (IOException | RuntimeException invalid) {
                rejectPending("Unverified or incomplete pending update: " + invalid.getMessage());
                return;
            }
            Path current = installTarget();
            if (!current.equals(plan.target())) {
                rejectPending("Pending update no longer belongs to the loaded mod.");
                return;
            }
            String currentHash = KungUpdateInstaller.sha256(current);
            if (currentHash.equals(plan.sourceSha256())) {
                Files.delete(pendingMarkerPath());
                return;
            }
            if (!currentHash.equals(plan.targetSha256())) {
                rejectPending("The installed mod changed since the update was prepared.");
                return;
            }
            String version = KungUpdateDownload.version(plan.source());
            KungUpdateDownload.validate(plan.source(), version, installedVersions());
            if (!isNewerVersion(version, currentVersion())
                || !KungUpdateInstaller.sha256(plan.source()).equals(plan.sourceSha256())) {
                rejectPending("The pending update is stale or damaged.");
                return;
            }
            installerProcess.launch(updateDirectory(), com.github.beng420.kung.runtime.KungPaths.fileLayout().logDirectory());
            state.set(new State(Status.INSTALL_READY, currentVersion(), version, "Close Minecraft to apply update", null));
        } catch (Exception exception) {
            installationBlocked = true;
            state.set(new State(Status.FAILED, currentVersion(), "", "Update recovery failed; see latest.log", null));
            KungMod.LOGGER.warn("Kung update recovery left the installed mod unchanged.", exception);
        }
    }

    private void rejectPending(String reason) throws IOException {
        Path rejected = updateDirectory().resolve("rejected-" + java.util.UUID.randomUUID() + ".properties");
        Files.move(pendingMarkerPath(), rejected, StandardCopyOption.ATOMIC_MOVE);
        KungMod.LOGGER.warn("Kung pending update preserved at {}: {}", rejected, reason);
    }

    private Path installTarget() throws IOException {
        Path current = currentModJar().orElseThrow(() -> new IOException("Current mod is not a standalone JAR."))
            .toAbsolutePath().normalize();
        Path mods = com.github.beng420.kung.runtime.KungPaths.fileLayout().gameDirectory().resolve("mods");
        KungUpdateProcess.rejectLinks(current);
        if (!mods.equals(current.getParent())) throw new IOException("Current mod is outside this profile's mods directory.");
        return current;
    }

    private static Map<String, String> installedVersions() {
        Map<String, String> versions = new HashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            versions.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        }
        versions.put("java", Integer.toString(Runtime.version().feature()));
        return versions;
    }

    public boolean isUpdateAvailable() {
        return state.get().status() == Status.UPDATE_AVAILABLE;
    }

    public boolean canInstallUpdate() {
        State snapshot = state.get();
        return snapshot.status() == Status.UPDATE_AVAILABLE && snapshot.updateInfo() != null
            && !installing.get() && !installationBlocked;
    }

    public String buttonLabel() {
        return switch (state.get().status()) {
            case CHECKING -> "Updates: Checking...";
            case UP_TO_DATE -> "Updates: Up to date";
            case UPDATE_AVAILABLE -> installationBlocked ? "Updates: Recovery needed" : "Updates: Available";
            case DOWNLOADING -> "Updates: Downloading...";
            case INSTALL_READY -> "Updates: Restart needed";
            case UNSUPPORTED -> "Updates: Dev build";
            case FAILED -> "Updates: Failed";
        };
    }

    public String statusMessage() {
        State snapshot = state.get();
        return switch (snapshot.status()) {
            case CHECKING -> "Checking GitHub";
            case UP_TO_DATE -> "Version " + snapshot.currentVersion();
            case UPDATE_AVAILABLE -> "Installs " + snapshot.latestVersion();
            case DOWNLOADING -> "Downloading...";
            case INSTALL_READY -> "Restart Minecraft";
            case UNSUPPORTED -> snapshot.message();
            case FAILED -> snapshot.message();
        };
    }

    public String latestVersionLabel() {
        State snapshot = state.get();
        return snapshot.latestVersion().isBlank()
            ? (snapshot.status() == Status.CHECKING ? "Latest: checking..." : "Latest: unavailable")
            : "Latest: v" + snapshot.latestVersion();
    }

    public void installLatestAsync(Minecraft client) {
        if (!canInstallUpdate()) return;
        State snapshot = state.get();
        if (snapshot.status() != Status.UPDATE_AVAILABLE || snapshot.updateInfo() == null) {
            return;
        }
        if (!installing.compareAndSet(false, true)) {
            return;
        }

        state.set(snapshot.withStatus(Status.DOWNLOADING, "Downloading update"));
        executor.execute(() -> {
            try {
                Path currentJar = installTarget();
                String originalHash = KungUpdateInstaller.sha256(currentJar);
                UpdateInfo update = snapshot.updateInfo();
                Path downloadedJar = KungUpdateDownload.download(httpClient, URI.create(update.downloadUrl()),
                    updateDirectory(), update.size(), update.digest(), update.version(), installedVersions());
                KungUpdateInstaller.writePlan(updateDirectory(), new KungUpdateInstaller.Plan(downloadedJar,
                    currentJar, KungUpdateInstaller.sha256(downloadedJar), originalHash));
                installerProcess.launch(updateDirectory(), com.github.beng420.kung.runtime.KungPaths.fileLayout().logDirectory());
                state.set(snapshot.withStatus(Status.INSTALL_READY, "Restart Minecraft"));
                KungMessages.send(
                    client,
                    KungMessages.Type.SUCCESS,
                    "Updater",
                    "Update verified and queued. Close Minecraft to apply it, then start it again."
                );
            } catch (Exception exception) {
                installationBlocked = Files.exists(pendingMarkerPath());
                KungMod.LOGGER.warn("Kung update install failed.", exception);
                state.set(new State(
                    Status.FAILED,
                    currentVersion(),
                    snapshot.latestVersion(),
                    "Update installation failed",
                    snapshot.updateInfo()
                ));
                KungMessages.send(
                    client,
                    KungMessages.Type.ERROR,
                    "Updater",
                    "The update could not be prepared. Your installed JAR was kept. See latest.log."
                );
            } finally {
                installing.set(false);
            }
        });
    }

    private State checkForUpdates() throws IOException, InterruptedException {
        String currentVersion = currentVersion();
        String minecraftVersion = minecraftVersion();
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_API))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kung-Updater")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.limiting(
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 1_048_576));
        if (response.statusCode() == 404) {
            return new State(Status.UP_TO_DATE, currentVersion, "", "No releases found", null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String tagName = stringValue(release, "tag_name");
        String latestVersion = releaseVersion(tagName);
        if (!isNewerVersion(latestVersion, currentVersion)) {
            return new State(Status.UP_TO_DATE, currentVersion, latestVersion, "Version " + currentVersion, null);
        }

        Optional<UpdateAsset> asset = findCompatibleJar(release, minecraftVersion);
        if (asset.isEmpty()) {
            return new State(
                Status.UNSUPPORTED,
                currentVersion,
                latestVersion,
                "No compatible jar for Minecraft " + minecraftVersion,
                null
            );
        }

        UpdateInfo updateInfo = new UpdateInfo(latestVersion, asset.get().name(), asset.get().downloadUrl(),
            asset.get().size(), asset.get().digest());
        return new State(
            Status.UPDATE_AVAILABLE,
            currentVersion,
            latestVersion,
            "Update " + latestVersion + " available",
            updateInfo
        );
    }

    private Optional<UpdateAsset> findCompatibleJar(JsonObject release, String minecraftVersion) {
        if (!release.has("assets") || !release.get("assets").isJsonArray()) {
            return Optional.empty();
        }

        JsonArray assets = release.getAsJsonArray("assets");
        List<UpdateAsset> installableAssets = new ArrayList<>();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            Optional<UpdateAsset> asset = assetFromJson(element.getAsJsonObject());
            if (asset.isPresent() && isInstallableJar(asset.get().name())) {
                installableAssets.add(asset.get());
            }
        }

        Optional<UpdateAsset> minecraftAsset = installableAssets.stream()
            .filter(asset -> asset.name().startsWith("kung-" + minecraftVersion + "-"))
            .findFirst();
        if (minecraftAsset.isPresent()) {
            return minecraftAsset;
        }

        if (minecraftVersion.isBlank() && installableAssets.size() == 1) {
            return Optional.of(installableAssets.getFirst());
        }
        return Optional.empty();
    }

    private static Optional<UpdateAsset> assetFromJson(JsonObject object) {
        String name = stringValue(object, "name");
        String downloadUrl = stringValue(object, "browser_download_url");
        if (name.isBlank() || downloadUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri = URI.create(downloadUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
            || !uri.getPath().startsWith("/bnc-schmidt/kung/releases/download/") || uri.getUserInfo() != null) {
            return Optional.empty();
        }
        long size = object.has("size") ? object.get("size").getAsLong() : 0;
        if (size <= 0 || size > KungUpdateDownload.MAX_BYTES) return Optional.empty();
        String digest = stringValue(object, "digest");
        if (!digest.isBlank() && !digest.matches("(?i)sha256:[0-9a-f]{64}")) return Optional.empty();
        return Optional.of(new UpdateAsset(name, downloadUrl, size, digest));
    }

    private static boolean isInstallableJar(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".jar")) {
            return false;
        }
        for (String skippedPart : SKIPPED_ASSET_PARTS) {
            if (lowerName.contains(skippedPart)) {
                return false;
            }
        }
        return true;
    }

    private Optional<Path> currentModJar() {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(KungMod.MOD_ID);
        if (container.isEmpty()) {
            return Optional.empty();
        }

        ModOrigin origin = container.get().getOrigin();
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            for (Path path : origin.getPaths()) {
                if (isJarFile(path)) {
                    return Optional.of(path.toAbsolutePath());
                }
            }
        }

        for (Path rootPath : container.get().getRootPaths()) {
            if (isJarFile(rootPath)) {
                return Optional.of(rootPath.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    private static boolean isJarFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    public static String currentVersion() {
        return FabricLoader.getInstance()
            .getModContainer(KungMod.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.0.0");
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("");
    }

    private static String releaseVersion(String tagName) {
        String trimmed = tagName == null ? "" : tagName.trim();
        Matcher matcher = VERSION_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    static boolean isNewerVersion(String latestVersion, String currentVersion) {
        List<Integer> latestParts = numericVersionParts(latestVersion);
        List<Integer> currentParts = numericVersionParts(currentVersion);
        int max = Math.max(latestParts.size(), currentParts.size());
        for (int index = 0; index < max; index++) {
            int latest = index < latestParts.size() ? latestParts.get(index) : 0;
            int current = index < currentParts.size() ? currentParts.get(index) : 0;
            if (latest != current) {
                return latest > current;
            }
        }
        return false;
    }

    private static List<Integer> numericVersionParts(String version) {
        return Pattern.compile("\\d+")
            .matcher(version == null ? "" : version)
            .results()
            .map(result -> Integer.parseInt(result.group()))
            .toList();
    }

    private static String stringValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static Path updateDirectory() {
        return com.github.beng420.kung.runtime.KungPaths.fileLayout().updateDirectory();
    }

    private static Path pendingMarkerPath() {
        return updateDirectory().resolve("pending.properties");
    }

    private enum Status {
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        INSTALL_READY,
        UNSUPPORTED,
        FAILED
    }

    private record State(
        Status status,
        String currentVersion,
        String latestVersion,
        String message,
        UpdateInfo updateInfo
    ) {
        static State checking(String currentVersion) {
            return new State(Status.CHECKING, currentVersion, "", "Checking GitHub", null);
        }

        State withStatus(Status status, String message) {
            return new State(status, currentVersion, latestVersion, message, updateInfo);
        }
    }

    private record UpdateInfo(String version, String assetName, String downloadUrl, long size, String digest) {
    }

    private record UpdateAsset(String name, String downloadUrl, long size, String digest) {
    }
}
