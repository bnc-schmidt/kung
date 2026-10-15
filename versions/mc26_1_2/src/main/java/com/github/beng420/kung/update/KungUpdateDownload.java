package com.github.beng420.kung.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** A download becomes eligible for installation only after integrity and dependency checks. */
final class KungUpdateDownload {
    static final long MAX_BYTES = 128L * 1024 * 1024;

    private KungUpdateDownload() { }

    static String version(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            return metadata(zip).get("version").getAsString();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid release version.", exception);
        }
    }

    private static JsonObject metadata(ZipFile zip) throws IOException {
        var entry = zip.getEntry("fabric.mod.json");
        if (entry == null || entry.getSize() <= 0 || entry.getSize() > 65_536) {
            throw new IOException("Missing or oversized Fabric metadata.");
        }
        try (var input = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readNBytes(65_537), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static Path download(HttpClient client, URI uri, Path directory, long size, String digest,
                         String version, Map<String, String> installed) throws IOException, InterruptedException {
        if (size <= 0 || size > MAX_BYTES) throw new IOException("Invalid release asset size.");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "download-", ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Kung-Updater").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofFile(temporary), size));
            if (response.statusCode() != 200) throw new IOException("Asset returned HTTP " + response.statusCode());
            if (Files.size(temporary) != size) throw new IOException("Incomplete release asset.");
            String actualHash = KungUpdateInstaller.sha256(temporary);
            if (!digest.isBlank() && !digest.equalsIgnoreCase("sha256:" + actualHash)) {
                throw new IOException("Release asset checksum mismatch.");
            }
            validate(temporary, version, installed);
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            Path verified = directory.resolve("verified-" + actualHash + ".jar");
            Files.move(temporary, verified, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return verified;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void validate(Path jar, String version, Map<String, String> installed) throws IOException {
        KungUpdateInstaller.validateJar(jar);
        try (var zip = new ZipFile(jar.toFile())) {
            JsonObject metadata = metadata(zip);
            if (!"kung".equals(metadata.get("id").getAsString())
                || !version.equals(metadata.get("version").getAsString())
                || (metadata.has("environment") && "server".equals(metadata.get("environment").getAsString()))
                || zip.getEntry("com/github/beng420/kung/KungClient.class") == null
                || zip.getEntry("com/github/beng420/kung/KungMod.class") == null) {
                throw new IOException("The downloaded JAR is not the requested Kung release.");
            }
            JsonObject dependencies = metadata.getAsJsonObject("depends");
            if (dependencies == null || !dependencies.has("minecraft")) {
                throw new IOException("Missing Minecraft dependency.");
            }
            for (var dependency : dependencies.entrySet()) {
                String loaded = installed.get(dependency.getKey());
                if (loaded == null || !matches(dependency.getValue(), loaded)) {
                    throw new IOException("Update requires a compatible " + dependency.getKey() + ".");
                }
            }
            JsonObject conflicts = metadata.getAsJsonObject("breaks");
            if (conflicts != null) {
                for (var conflict : conflicts.entrySet()) {
                    String loaded = installed.get(conflict.getKey());
                    if (loaded != null && matches(conflict.getValue(), loaded)) {
                        throw new IOException("Update is incompatible with installed " + conflict.getKey() + ".");
                    }
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Invalid Kung release metadata.", exception);
        }
    }

    private static boolean matches(JsonElement requirement, String loaded) throws Exception {
        if (requirement.isJsonArray()) {
            for (JsonElement alternative : requirement.getAsJsonArray()) {
                if (matches(alternative, loaded)) return true;
            }
            return false;
        }
        return VersionPredicate.parse(requirement.getAsString()).test(Version.parse(loaded));
    }
}
