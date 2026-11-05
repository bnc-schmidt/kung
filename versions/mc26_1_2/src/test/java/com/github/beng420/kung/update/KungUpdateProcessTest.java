package com.github.beng420.kung.update;

import static com.github.beng420.kung.update.ZipFixtures.entry;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungUpdateProcessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void packagedHelperIncludesItsNestedTypesAndNoMinecraftDependencies() throws Exception {
        Path directory = temp.newFolder("updates").toPath();
        Path helper = KungUpdateProcess.packageHelper(directory);
        try (var jar = new JarFile(helper.toFile())) {
            assertPackaged(jar, KungUpdateInstaller.class);
            assertTrue(jar.stream().allMatch(entry -> entry.getName().startsWith(
                "com/github/beng420/kung/update/KungUpdateInstaller")));
        }
    }

    @Test public void standaloneHelperInstallsWithOnlyItsExtractedJarOnTheClasspath() throws Exception {
        Fixture fixture = fixture();
        byte[] original = Files.readAllBytes(fixture.target());
        byte[] replacement = Files.readAllBytes(fixture.source());
        Result result = runHelper(fixture.directory());
        assertEquals(result.output(), 0, result.exitCode());
        assertArrayEquals(replacement, Files.readAllBytes(fixture.target()));
        assertArrayEquals(original, Files.readAllBytes(fixture.directory().resolve("previous.jar")));
        assertFalse(Files.exists(fixture.directory().resolve("pending.properties")));
        assertTrue(result.output().contains("installed successfully"));
    }

    @Test public void anotherGameSessionBlocksTheHelperAndPreservesThePendingUpdateForRetry() throws Exception {
        Fixture fixture = fixture();
        byte[] original = Files.readAllBytes(fixture.target());
        Path marker = fixture.directory().resolve("pending.properties");
        try (var channel = FileChannel.open(fixture.directory().resolve("session.lock"),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Result blocked = runHelper(fixture.directory());
            assertEquals(blocked.output(), 1, blocked.exitCode());
            assertTrue(blocked.output().contains("remains pending"));
            assertArrayEquals(original, Files.readAllBytes(fixture.target()));
            assertTrue(Files.exists(marker));
        }
        Result retried = runHelper(fixture.directory());
        assertEquals(retried.output(), 0, retried.exitCode());
        assertArrayEquals(Files.readAllBytes(fixture.source()), Files.readAllBytes(fixture.target()));
        assertFalse(Files.exists(marker));
    }

    @Test public void helperWaitsForTheRealParentExitAndCanRetryAfterBeingKilledWhileWaiting() throws Exception {
        Fixture fixture = fixture();
        byte[] original = Files.readAllBytes(fixture.target());
        Path marker = fixture.directory().resolve("pending.properties");
        Path source = temp.newFile("DummyParent.java").toPath();
        Files.writeString(source, """
            class DummyParent {
                public static void main(String[] args) throws Exception {
                    System.out.println("READY");
                    System.out.flush();
                    System.in.read();
                }
            }
            """);
        Path parentOutput = temp.newFile().toPath();
        Path helperOutput = temp.newFile().toPath();
        Process parent = new ProcessBuilder(javaExecutable().toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(parentOutput.toFile()).start();
        Process child = null;
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (parent.isAlive() && !Files.readString(parentOutput).contains("READY")
                && System.nanoTime() < readyDeadline) {
                Thread.sleep(25);
            }
            assertTrue("Dummy JVM did not become ready: " + Files.readString(parentOutput),
                parent.isAlive() && Files.readString(parentOutput).contains("READY"));
            long parentStart = parent.info().startInstant().orElseThrow().toEpochMilli();
            child = startHelper(fixture.directory(), parent.pid(), parentStart, helperOutput);
            assertFalse("Helper exited before its parent", child.waitFor(1_200, TimeUnit.MILLISECONDS));
            assertTrue(parent.isAlive());
            assertArrayEquals(original, Files.readAllBytes(fixture.target()));
            assertTrue(Files.exists(marker));

            // A shutdown can terminate the waiting helper too; the queued update must remain retryable.
            terminate(child);
            assertArrayEquals(original, Files.readAllBytes(fixture.target()));
            assertTrue(Files.exists(marker));
            child = startHelper(fixture.directory(), parent.pid(), parentStart, helperOutput);
            assertFalse("Retry ignored its live parent", child.waitFor(1_200, TimeUnit.MILLISECONDS));
            assertArrayEquals(original, Files.readAllBytes(fixture.target()));

            parent.getOutputStream().close();
            assertTrue("Dummy parent did not exit", parent.waitFor(15, TimeUnit.SECONDS));
            assertEquals(Files.readString(parentOutput), 0, parent.exitValue());
            assertTrue("Helper did not finish after its parent exited", child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(Files.readString(helperOutput), 0, child.exitValue());
            assertArrayEquals(Files.readAllBytes(fixture.source()), Files.readAllBytes(fixture.target()));
            assertArrayEquals(original, Files.readAllBytes(fixture.directory().resolve("previous.jar")));
            assertFalse(Files.exists(marker));
        } finally {
            try {
                terminate(child);
            } finally {
                terminate(parent);
            }
        }
    }

    private Result runHelper(Path directory) throws Exception {
        Path output = temp.newFile().toPath();
        // A live PID with a different start time models PID reuse: the old Minecraft has already exited.
        long earlierStart = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() - 60_000;
        Process child = startHelper(directory, ProcessHandle.current().pid(), earlierStart, output);
        try {
            assertTrue("Installer child did not terminate: " + Files.readString(output), child.waitFor(15, TimeUnit.SECONDS));
            return new Result(child.exitValue(), Files.readString(output));
        } finally {
            terminate(child);
        }
    }

    private static Process startHelper(Path directory, long parentPid, long parentStart, Path output) throws IOException {
        Path helper = KungUpdateProcess.packageHelper(directory);
        return new ProcessBuilder(javaExecutable().toString(), "-cp", helper.toString(), KungUpdateInstaller.class.getName(),
            Long.toString(parentPid), Long.toString(parentStart), directory.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static Path javaExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isRegularFile(java) ? java : java.resolveSibling("java.exe");
    }

    private static void terminate(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertTrue("Temporary child process did not terminate", process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private Fixture fixture() throws IOException {
        Path profile = temp.newFolder().toPath();
        Path directory = Files.createDirectories(profile.resolve("config/kung/updates"));
        Path target = Files.createDirectories(profile.resolve("mods")).resolve("kung-current.jar");
        Path source = directory.resolve("verified-update.jar");
        jar(target, "0.3.4");
        jar(source, "0.3.5");
        var plan = new KungUpdateInstaller.Plan(source, target,
            KungUpdateInstaller.sha256(source), KungUpdateInstaller.sha256(target));
        KungUpdateInstaller.writePlan(directory, plan);
        return new Fixture(directory, source, target);
    }

    private static void jar(Path path, String version) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "fabric.mod.json", ("{\"id\":\"kung\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            entry(zip, "payload.txt", version.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void assertPackaged(JarFile jar, Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        assertNotNull(resource, jar.getJarEntry(resource));
        for (Class<?> nested : type.getDeclaredClasses()) assertPackaged(jar, nested);
    }

    private record Fixture(Path directory, Path source, Path target) { }
    private record Result(int exitCode, String output) { }
}
