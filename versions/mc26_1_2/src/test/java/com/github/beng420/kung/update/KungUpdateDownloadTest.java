package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungUpdateDownloadTest {
    private static final String VERSION = "0.3.5";
    private static final String DEPENDENCIES = """
        {"minecraft":"26.1.2","java":">=25","fabricloader":">=0.19.2","fabric-api":"*"}
        """;
    private static final Map<String, String> INSTALLED = Map.of(
        "minecraft", "26.1.2", "java", "25", "fabricloader", "0.19.2", "fabric-api", "0.146.0+26.1.2");

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void completeHttpDownloadBecomesVerifiedOnlyAfterSizeHashAndMetadataChecks() throws Exception {
        byte[] bytes = jar(metadata("kung", VERSION, DEPENDENCIES), true);
        Path reference = temp.newFile("reference.jar").toPath();
        Files.write(reference, bytes);
        String hash = KungUpdateInstaller.sha256(reference);
        Path directory = temp.newFolder("updates").toPath();
        Path verified = download(200, bytes, bytes.length, "sha256:" + hash, directory);
        assertEquals(directory.resolve("verified-" + hash + ".jar"), verified);
        assertArrayEquals(bytes, Files.readAllBytes(verified));
        assertOnlyFile(directory, verified);
    }

    @Test public void releaseWithoutAChecksumIsRejectedBeforeAnyRequest() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = server(200, new byte[] {1}, requests);
        Path directory = temp.newFolder("updates").toPath();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String digest : new String[] {"", "sha256:abc", "md5:" + "0".repeat(32)}) {
                assertThrows(IOException.class, () -> KungUpdateDownload.download(
                    client, uri(server), directory, 1, digest, VERSION, INSTALLED));
            }
            assertEquals(0, requests.get());
            assertEmpty(directory);
        } finally {
            server.stop(0);
        }
    }

    @Test public void shortBodyCannotBecomeAValidDownloadEvenIfItIsACompleteZip() throws Exception {
        byte[] bytes = jar(metadata("kung", VERSION, DEPENDENCIES), true);
        assertRejectedDownload(200, bytes, bytes.length + 10, digestOf(bytes));
    }

    @Test public void bodyLargerThanAdvertisedIsBoundedAndDiscarded() throws Exception {
        byte[] bytes = jar(metadata("kung", VERSION, DEPENDENCIES), true);
        assertRejectedDownload(200, bytes, bytes.length - 10, digestOf(bytes));
    }

    @Test public void nonSuccessHttpStatusCannotInstallEvenIfItsBodyIsAValidJar() throws Exception {
        byte[] bytes = jar(metadata("kung", VERSION, DEPENDENCIES), true);
        assertRejectedDownload(404, bytes, bytes.length, digestOf(bytes));
    }

    @Test public void wrongReleaseChecksumIsRejectedAndTemporaryFileRemoved() throws Exception {
        byte[] bytes = jar(metadata("kung", VERSION, DEPENDENCIES), true);
        assertRejectedDownload(200, bytes, bytes.length, "sha256:" + "0".repeat(64));
    }

    @Test public void invalidZipAndInvalidFabricMetadataNeverBecomeVerified() throws Exception {
        byte[] invalidZip = "not a zip".getBytes(StandardCharsets.UTF_8);
        assertRejectedDownload(200, invalidZip, invalidZip.length, digestOf(invalidZip));
        byte[] invalidJson = jar("{broken", true);
        assertRejectedDownload(200, invalidJson, invalidJson.length, digestOf(invalidJson));
    }

    @Test public void invalidAdvertisedSizeFailsBeforeOpeningNetworkConnection() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = server(200, new byte[] {1}, requests);
        Path directory = temp.newFolder("updates").toPath();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (long size : new long[] {-1, 0, KungUpdateDownload.MAX_BYTES + 1}) {
                assertThrows(IOException.class, () -> KungUpdateDownload.download(
                    client, uri(server), directory, size, "sha256:" + "0".repeat(64), VERSION, INSTALLED));
            }
            assertEquals(0, requests.get());
            assertEmpty(directory);
        } finally {
            server.stop(0);
        }
    }

    @Test public void wrongModIdentityVersionOrMissingEntrypointIsRejected() throws Exception {
        assertRejectedJar(metadata("another-mod", VERSION, DEPENDENCIES), true, INSTALLED);
        assertRejectedJar(metadata("kung", "0.3.4", DEPENDENCIES), true, INSTALLED);
        assertRejectedJar(metadata("kung", VERSION, DEPENDENCIES), false, INSTALLED);
    }

    @Test public void missingRequiredMetadataIsRejected() throws Exception {
        assertRejectedJar("{\"id\":\"kung\",\"version\":\"0.3.5\"}", true, INSTALLED);
        assertRejectedJar(metadata("kung", VERSION, "{}"), true, INSTALLED);
        assertRejectedJar("{}", true, INSTALLED);
        assertRejectedJar(metadata("kung", VERSION, "null"), true, INSTALLED);
    }

    @Test public void incompatibleMinecraftJavaLoaderOrMissingRequiredModIsRejected() throws Exception {
        for (var incompatible : Map.of("minecraft", "26.2", "java", "21", "fabricloader", "0.18.0").entrySet()) {
            var installed = new HashMap<>(INSTALLED);
            installed.put(incompatible.getKey(), incompatible.getValue());
            assertRejectedJar(metadata("kung", VERSION, DEPENDENCIES), true, installed);
        }
        var missingApi = new HashMap<>(INSTALLED);
        missingApi.remove("fabric-api");
        assertRejectedJar(metadata("kung", VERSION, DEPENDENCIES), true, missingApi);
    }

    @Test public void minecraftAlternativesAcceptOnlyAMatchingInstalledVersion() throws Exception {
        String alternatives = DEPENDENCIES.replace("\"26.1.2\"", "[\"26.1.1\",\"26.1.2\"]");
        Path candidate = writeJar(metadata("kung", VERSION, alternatives), true);
        KungUpdateDownload.validate(candidate, VERSION, INSTALLED);
        var incompatible = new HashMap<>(INSTALLED);
        incompatible.put("minecraft", "26.2");
        assertThrows(IOException.class, () -> KungUpdateDownload.validate(candidate, VERSION, incompatible));
    }

    @Test public void serverOnlyReleaseIsRejectedWhileClientAndUniversalAreAccepted() throws Exception {
        String base = metadata("kung", VERSION, DEPENDENCIES);
        assertRejectedJar(withField(base, "\"environment\":\"server\""), true, INSTALLED);
        for (String environment : new String[] {"client", "*"}) {
            Path candidate = writeJar(withField(base, "\"environment\":\"" + environment + "\""), true);
            KungUpdateDownload.validate(candidate, VERSION, INSTALLED);
        }
    }

    @Test public void breaksRejectsOnlyInstalledModsWhoseVersionsMatchTheConflict() throws Exception {
        String metadata = withField(metadata("kung", VERSION, DEPENDENCIES),
            "\"breaks\":{\"another-mod\":[\"<2.0.0\",\"3.0.0\"]}");
        Path candidate = writeJar(metadata, true);
        KungUpdateDownload.validate(candidate, VERSION, INSTALLED);
        var installed = new HashMap<>(INSTALLED);
        installed.put("another-mod", "2.0.0");
        KungUpdateDownload.validate(candidate, VERSION, installed);
        installed.put("another-mod", "1.5.0");
        assertThrows(IOException.class, () -> KungUpdateDownload.validate(candidate, VERSION, installed));
        installed.put("another-mod", "3.0.0");
        assertThrows(IOException.class, () -> KungUpdateDownload.validate(candidate, VERSION, installed));
    }

    private static String digestOf(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertRejectedDownload(int status, byte[] bytes, long advertisedSize, String digest) throws Exception {
        Path directory = temp.newFolder().toPath();
        assertThrows(IOException.class, () -> download(status, bytes, advertisedSize, digest, directory));
        assertEmpty(directory);
    }

    private void assertRejectedJar(String metadata, boolean entrypoints, Map<String, String> installed) throws Exception {
        Path candidate = writeJar(metadata, entrypoints);
        assertThrows(IOException.class, () -> KungUpdateDownload.validate(candidate, VERSION, installed));
    }

    private Path writeJar(String metadata, boolean entrypoints) throws IOException {
        Path candidate = temp.newFile().toPath();
        return Files.write(candidate, jar(metadata, entrypoints));
    }

    private static Path download(int status, byte[] bytes, long advertisedSize, String digest, Path directory) throws Exception {
        HttpServer server = server(status, bytes, new AtomicInteger());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return KungUpdateDownload.download(client, uri(server), directory, advertisedSize, digest, VERSION, INSTALLED);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(int status, byte[] bytes, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset.jar", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // The bounded client may close an oversized response while the server is still sending it.
            }
        });
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/asset.jar");
    }

    private static String metadata(String id, String version, String dependencies) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
            + "\",\"depends\":" + dependencies + "}";
    }

    private static String withField(String metadata, String field) {
        return metadata.substring(0, metadata.length() - 1) + "," + field + "}";
    }

    private static byte[] jar(String metadata, boolean entrypoints) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(zip, "fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
            if (entrypoints) {
                entry(zip, "com/github/beng420/kung/KungClient.class", new byte[0]);
                entry(zip, "com/github/beng420/kung/KungMod.class", new byte[0]);
            }
            entry(zip, "assets/kung/payload.txt", "resource payload".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void assertEmpty(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    private static void assertOnlyFile(Path directory, Path expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(java.util.List.of(expected), files.toList());
        }
    }
}
