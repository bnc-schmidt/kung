package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungReleaseNotesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void firstMenuLoadsOnceAndOnlyDisplayedNotesPersistAcrossRestarts() throws Exception {
        Path file = receipt();
        var worker = new Worker();
        var calls = new AtomicInteger();
        var notes = controller("0.3.2", file, worker, version -> {
            calls.incrementAndGet();
            return Optional.of(release(version));
        });
        assertTrue(notes.openMenu());
        assertTrue(notes.openMenu());
        assertEquals(1, worker.tasks.size());
        assertEquals(KungReleaseNotes.Status.LOADING, notes.snapshot().status());
        assertFalse(Files.exists(file));
        worker.drain();
        assertEquals(1, calls.get());
        assertTrue(notes.openMenu()); // Fetching alone does not acknowledge or dismiss the offer.
        assertEquals(0, worker.tasks.size());
        notes.acknowledge(release("0.3.2")); // A different or stale object was not the displayed result.
        assertTrue(notes.openMenu());
        notes.acknowledge(notes.snapshot().notes());
        assertFalse(notes.openMenu());
        worker.drain();
        assertTrue(Files.readString(file).contains("lastSeenVersion=0.3.2"));

        var same = controller("0.3.2", file, worker, version -> { fail("Seen version must not fetch"); return Optional.empty(); });
        assertFalse(same.openMenu());
        var updated = controller("0.3.3", file, worker, version -> Optional.of(release(version)));
        assertTrue(updated.openMenu());
        worker.drain();
        assertEquals("0.3.3", updated.snapshot().notes().version());
    }

    @Test public void failuresAndMissingReleaseNeverConsumeTheVersionAndCanRetry() throws Exception {
        Path file = receipt();
        var worker = new Worker();
        var calls = new AtomicInteger();
        var notes = controller("0.3.2", file, worker, version -> switch (calls.incrementAndGet()) {
            case 1 -> throw new IOException("offline");
            case 2 -> Optional.empty();
            default -> Optional.of(release(version));
        });
        notes.openMenu();
        worker.drain();
        assertEquals(KungReleaseNotes.Status.FAILED, notes.snapshot().status());
        notes.acknowledge(null);
        assertTrue(notes.openMenu());
        worker.drain();
        assertEquals(KungReleaseNotes.Status.UNAVAILABLE, notes.snapshot().status());
        notes.retry();
        worker.drain();
        assertEquals(KungReleaseNotes.Status.READY, notes.snapshot().status());
        assertFalse(Files.exists(file));
    }

    @Test public void aDevBuildNeverOpensTheNotesOnItsOwnButStillOnRequest() throws Exception {
        var worker = new Worker();
        var notes = controller("0.6.3-dev", receipt(), worker, version -> Optional.empty());
        assertFalse(notes.openMenu());
        assertEquals(0, worker.tasks.size());
        assertTrue(notes.openMenu(true)); // the Changelogs button
        assertEquals(1, worker.tasks.size());
    }

    @Test public void shutdownFlushesAnAcknowledgementStillQueuedOnTheWorker() throws Exception {
        Path file = receipt();
        var worker = new Worker();
        var notes = controller("0.3.2", file, worker, version -> Optional.of(release(version)));
        notes.openMenu();
        worker.drain();
        notes.acknowledge(notes.snapshot().notes());
        assertFalse(Files.exists(file));
        notes.flushAcknowledgement();
        assertTrue(Files.exists(file));
        worker.drain();
        assertFalse(Files.exists(file.resolveSibling("release-notes.properties.tmp")));
        assertFalse(controller("0.3.2", file, worker, version -> Optional.empty()).openMenu());
    }

    @Test public void malformedReceiptDoesNotHideNewNotesOrBreakTheMenu() throws Exception {
        Path file = receipt();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "lastSeenVersion=\\uZZZZ");
        var notes = controller("0.3.2", file, Runnable::run, version -> Optional.of(release(version)));
        assertTrue(notes.openMenu());
        notes.acknowledge(notes.snapshot().notes());
        assertFalse(notes.openMenu());
    }

    @Test public void selectionIsExactIncludingPrereleasesAndNeverTrustsAReleaseProvidedUrl() {
        assertTrue(KungReleaseNotes.parse(json("v0.3.3", "Future release"), "0.3.2").isEmpty());
        assertTrue(KungReleaseNotes.parse(json("v0.3.2-beta", "Beta"), "0.3.2").isEmpty());
        assertTrue(KungReleaseNotes.parse(json("v0.3.2", ""), "0.3.2").isEmpty());
        assertTrue(KungReleaseNotes.parse("{\"tag_name\":\"v0.3.2\",\"draft\":true,\"body\":\"Draft\"}", "0.3.2").isEmpty());
        var result = KungReleaseNotes.parse(json("v0.3.2", "# Fixes\\n- Mimic ESP"), "0.3.2").orElseThrow();
        assertEquals("# Fixes\n- Mimic ESP", result.body());
        assertEquals("https://github.com/bnc-schmidt/kung/releases/tag/v0.3.2", result.page().toString());
        assertTrue(KungReleaseNotes.parse(json("0.3.2", "Notes"), "v0.3.2").isPresent());
    }

    @Test public void httpLookupUsesInstalledTagAndRetriesOnlyItsUnprefixedSpelling() throws Exception {
        var paths = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tags/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            int status = path.equals("/tags/0.3.2") ? 200 : 404;
            byte[] body = (status == 200 ? json("0.3.2", "# Fixes\\n- Current notes") : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tags/");
            var result = KungReleaseNotes.fetch(HttpClient.newHttpClient(), "0.3.2", endpoint).orElseThrow();
            assertEquals("0.3.2", result.version());
            assertEquals(List.of("/tags/v0.3.2", "/tags/0.3.2"), paths);
        } finally { server.stop(0); }
    }

    @Test public void popupLayoutKeepsTextAndButtonsWithinDifferentGuiSizes() {
        for (int width : new int[] {240, 320, 480, 960}) for (int height : new int[] {160, 240, 540}) {
            var layout = KungReleaseNotesPopup.Layout.at(width, height);
            assertTrue(layout.panel().x() >= 0 && layout.panel().right() <= width);
            assertTrue(layout.panel().y() >= 0 && layout.panel().bottom() <= height);
            assertTrue(layout.content().height() > 0);
            assertTrue(layout.content().bottom() < layout.close().y());
            assertTrue(layout.retry().right() < layout.github().x());
            assertTrue(layout.github().right() < layout.close().x());
            assertTrue(layout.close().right() < layout.panel().right());
            assertTrue(layout.sidebar().right() < layout.content().x());
            assertTrue(layout.sidebar().width() <= 100);
            assertTrue(layout.current().bottom() < layout.history().y());
            assertTrue(layout.history().height() > 0);
            assertTrue(layout.history().bottom() < layout.previous().y());
            assertTrue(layout.previous().right() < layout.next().x());
            assertEquals(layout.sidebar().right(), layout.next().right());
            assertTrue(layout.previous().right() < layout.historyRetry().x());
            assertEquals(layout.sidebar().right(), layout.historyRetry().right());
            assertTrue(layout.github().width() <= 58 && layout.close().width() <= 58);
        }
    }

    @Test public void manualOpeningCanReloadAnAcknowledgedVersionWithoutChangingItsReceipt() throws Exception {
        Path file = receipt();
        var worker = new Worker();
        var first = controller("0.3.2", file, worker, version -> Optional.of(release(version)));
        first.openMenu();
        worker.drain();
        first.acknowledge(first.snapshot().notes());
        worker.drain();
        String saved = Files.readString(file);
        var calls = new AtomicInteger();
        var restarted = controller("0.3.2", file, worker, version -> {
            if (calls.incrementAndGet() == 1) throw new IOException("offline");
            return Optional.of(release(version));
        });
        assertFalse(restarted.openMenu());
        assertEquals(0, worker.tasks.size());
        assertTrue(restarted.openMenu(true));
        worker.drain();
        assertEquals(KungReleaseNotes.Status.FAILED, restarted.snapshot().status());
        restarted.retry();
        worker.drain();
        assertEquals(KungReleaseNotes.Status.READY, restarted.snapshot().status());
        assertTrue(restarted.openMenu(true));
        assertEquals(0, worker.tasks.size());
        assertFalse(restarted.openMenu());
        assertEquals(saved, Files.readString(file));
    }

    @Test public void dismissingLoadingPopupSurvivesRestartAndDoesNotClaimNotesWereRead() throws Exception {
        Path file = receipt();
        var worker = new Worker();
        var notes = controller("0.3.2", file, worker, version -> Optional.of(release(version)));
        notes.openMenu();
        assertTrue(new KungReleaseNotesPopup(notes).key(256));
        assertFalse(notes.openMenu());
        notes.flushAcknowledgement(); // Persist even if the network request still occupies the worker.
        String saved = Files.readString(file);
        assertTrue(saved.contains("lastDismissedVersion=0.3.2"));
        assertFalse(saved.contains("lastSeenVersion"));
        worker.drain();
        assertFalse(notes.openMenu());
        var restarted = controller("0.3.2", file, worker, version -> Optional.of(release(version)));
        assertFalse(restarted.openMenu());
        assertTrue(restarted.openMenu(true));
        worker.drain();
        restarted.acknowledge(restarted.snapshot().notes());
        worker.drain();
        assertTrue(Files.readString(file).contains("lastSeenVersion=0.3.2"));
        assertFalse(restarted.openMenu());
        assertTrue(controller("0.3.3", file, worker, version -> Optional.empty()).openMenu());
    }

    @Test public void dismissingMissingOrFailedNotesStopsAutomaticReopeningButAllowsManualRetry() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            Path file = temp.getRoot().toPath().resolve("case-" + fail + "/release-notes.properties");
            var notes = controller("0.3.2", file, Runnable::run, version -> {
                if (fail) throw new IOException("offline");
                return Optional.empty();
            });
            assertTrue(notes.openMenu());
            assertTrue(new KungReleaseNotesPopup(notes).key(257));
            assertFalse(notes.openMenu());
            var restarted = controller("0.3.2", file, Runnable::run, version -> Optional.of(release(version)));
            assertFalse(restarted.openMenu());
            assertTrue(restarted.openMenu(true));
            assertEquals(KungReleaseNotes.Status.READY, restarted.snapshot().status());
        }
    }

    private Path receipt() { return temp.getRoot().toPath().resolve("config/kung/updates/release-notes.properties"); }
    private static KungReleaseNotes controller(String version, Path file, Executor worker, KungReleaseNotes.Fetcher fetcher) {
        return new KungReleaseNotes(version, () -> file, worker, fetcher);
    }
    private static KungReleaseNotes.Notes release(String version) {
        return new KungReleaseNotes.Notes(version, "# Fixes\n- Updated", URI.create("https://github.com/bnc-schmidt/kung/releases/tag/v" + version));
    }
    private static String json(String version, String body) {
        return "{\"tag_name\":\"" + version + "\",\"body\":\"" + body + "\",\"html_url\":\"https://example.com/untrusted\"}";
    }
    private static final class Worker implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
