package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungReleaseHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void historyAndBodiesAreLazyCoalescedAndCachedSeparately() {
        var worker = new Worker();
        var pageCalls = new ArrayList<Integer>();
        var noteCalls = new ArrayList<String>();
        var history = new KungReleaseHistory(worker, page -> {
            pageCalls.add(page);
            return new KungReleaseHistory.Page(List.of(release("0.3.1")), true);
        }, tag -> {
            noteCalls.add(tag);
            return Optional.of(body(tag));
        });
        assertEquals(KungReleaseNotes.Status.IDLE, history.page(1).status());
        assertEquals(KungReleaseNotes.Status.IDLE, history.notes("v0.3.1").status());
        assertEquals(0, worker.tasks.size());
        var notes = new KungReleaseNotes("0.3.2", () -> temp.getRoot().toPath().resolve("receipt.properties"),
            worker, version -> { fail("Opening the sidebar must not prefetch bodies"); return Optional.empty(); }, history);
        new KungReleaseNotesPopup(notes); // The visible sidebar requests only its first list page.
        assertEquals(1, worker.tasks.size());
        history.requestPage(1);
        history.requestPage(1);
        assertEquals(1, worker.tasks.size());
        worker.drain();
        assertEquals(List.of(1), pageCalls);
        assertTrue(noteCalls.isEmpty());
        history.requestPage(1);
        history.requestNotes(release("0.3.1"));
        history.requestNotes(release("0.3.1"));
        assertEquals(1, worker.tasks.size());
        worker.drain();
        assertEquals(List.of("v0.3.1"), noteCalls);
        history.requestNotes(release("0.3.1"));
        assertEquals(0, worker.tasks.size());
        history.requestPage(2);
        worker.drain();
        assertEquals(List.of(1, 2), pageCalls);
        assertEquals(1, noteCalls.size());
    }

    @Test public void lateResultsStayWithTheirVersionAndHistoryCannotAcknowledgeAnUpdate() throws Exception {
        var worker = new Worker();
        var history = new KungReleaseHistory(worker, page -> new KungReleaseHistory.Page(List.of(), false),
            tag -> Optional.of(body(tag)));
        var file = temp.getRoot().toPath().resolve("updates/release-notes.properties");
        var notes = new KungReleaseNotes("0.3.2", () -> file, worker,
            version -> Optional.of(body("v" + version)), history);
        notes.openMenu();
        var popup = new KungReleaseNotesPopup(notes);
        popup.select(release("0.3.1"));
        popup.select(release("0.3.0"));
        worker.tasks.remove().run(); // Installed version finishes while older notes are selected.
        worker.tasks.remove().run(); // Sidebar metadata loads independently of the selected body.
        worker.tasks.remove().run(); // The previous historical selection finishes late.
        assertEquals("0.3.0", popup.selectedVersion());
        assertEquals(KungReleaseNotes.Status.LOADING, popup.selectedNotes().status());
        worker.drain();
        assertEquals("0.3.0", popup.selectedNotes().notes().version());
        notes.acknowledge(popup.selectedNotes().notes());
        worker.drain();
        assertFalse(Files.exists(file));
        assertTrue(notes.openMenu());
        popup.select(null);
        assertEquals("0.3.2", popup.selectedVersion());
        assertEquals(KungReleaseNotes.Status.READY, popup.selectedNotes().status());
        assertEquals(0, worker.tasks.size());
        notes.acknowledge(popup.selectedNotes().notes());
        worker.drain();
        assertTrue(Files.readString(file).contains("lastSeenVersion=0.3.2"));
    }

    @Test public void failuresAndMissingBodiesCanRetryWithoutPoisoningOtherEntries() {
        var worker = new Worker();
        var pageCalls = new AtomicInteger();
        var bodyCalls = new AtomicInteger();
        var history = new KungReleaseHistory(worker, page -> {
            if (pageCalls.incrementAndGet() == 1) throw new IOException("offline");
            return new KungReleaseHistory.Page(List.of(release("0.3.1")), false);
        }, tag -> switch (bodyCalls.incrementAndGet()) {
            case 1 -> throw new IOException("offline");
            case 2 -> Optional.empty();
            default -> Optional.of(body(tag));
        });
        history.requestPage(1);
        worker.drain();
        assertEquals(KungReleaseNotes.Status.FAILED, history.page(1).status());
        history.requestPage(1);
        history.requestNotes(release("0.3.1"));
        worker.drain();
        assertEquals(KungReleaseNotes.Status.READY, history.page(1).status());
        assertEquals(KungReleaseNotes.Status.FAILED, history.notes("v0.3.1").status());
        history.requestNotes(release("0.3.1"));
        worker.drain();
        assertEquals(KungReleaseNotes.Status.UNAVAILABLE, history.notes("v0.3.1").status());
        history.requestNotes(release("0.3.1"));
        worker.drain();
        assertEquals(KungReleaseNotes.Status.READY, history.notes("v0.3.1").status());
        assertEquals(KungReleaseNotes.Status.IDLE, history.notes("v0.3.0").status());
    }

    @Test public void cachesEvictOldResultsAndReloadOnlyWhenRequested() {
        var history = new KungReleaseHistory(Runnable::run, page -> new KungReleaseHistory.Page(List.of(), false),
            tag -> Optional.of(body(tag)));
        for (int page = 1; page <= 5; page++) history.requestPage(page);
        for (int version = 1; version <= 9; version++) history.requestNotes(release("0.2." + version));
        assertEquals(KungReleaseNotes.Status.IDLE, history.page(1).status());
        assertEquals(KungReleaseNotes.Status.IDLE, history.notes("v0.2.1").status());
        assertEquals(KungReleaseNotes.Status.READY, history.page(5).status());
        assertEquals(KungReleaseNotes.Status.READY, history.notes("v0.2.9").status());
        history.requestNotes(release("0.2.1"));
        assertEquals(KungReleaseNotes.Status.READY, history.notes("v0.2.1").status());
    }

    @Test public void listParsingKeepsPublishedTagsAndDatesWithoutTrustingExternalLinks() {
        var page = KungReleaseHistory.parsePage("["
            + "{\"tag_name\":\"v0.3.1\",\"body\":\"Ignored\",\"published_at\":\"2026-09-12T12:00:00Z\",\"html_url\":\"https://example.com\"},"
            + "{\"tag_name\":\"v0.3.1\"}, {\"tag_name\":\"v0.4.0\",\"draft\":true},"
            + "{\"tag_name\":\"0.3.0-beta\",\"prerelease\":true}, {\"tag_name\":\"\"}]", true);
        assertEquals(2, page.releases().size());
        assertTrue(page.hasNext());
        assertEquals("2026-09-12", page.releases().getFirst().date());
        assertEquals("https://github.com/bnc-schmidt/kung/releases/tag/v0.3.1", page.releases().getFirst().page().toString());
        assertEquals("0.3.0-beta", page.releases().getLast().version());
        assertEquals("", page.releases().getLast().date());
    }

    @Test public void httpHistoryPaginatesWithoutFollowingLinksAndFetchesOnlyTheExactSelectedTag() throws Exception {
        var requests = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases", exchange -> {
            String path = exchange.getRequestURI().toString();
            requests.add(path);
            int status = 200;
            String json;
            if (path.startsWith("/releases?")) {
                boolean second = path.endsWith("page=2");
                json = second ? "[]" : "[{\"tag_name\":\"v0.3.1\",\"body\":\"Do not prefetch this body\"}]";
                if (!second) exchange.getResponseHeaders().add("Link", "<https://example.com/untrusted>; rel=\"next\"");
            } else if (path.equals("/releases/tags/0.3.0-beta%2Btest")) {
                json = "{\"tag_name\":\"0.3.0-beta+test\",\"body\":\"Selected notes\"}";
            } else {
                status = 404;
                json = "{}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases");
            var client = HttpClient.newHttpClient();
            assertTrue(KungReleaseHistory.fetchPage(client, 1, endpoint).hasNext());
            assertFalse(KungReleaseHistory.fetchPage(client, 2, endpoint).hasNext());
            var selected = KungReleaseNotes.fetchTag(client, "0.3.0-beta+test", URI.create(endpoint + "/tags/")).orElseThrow();
            assertEquals("Selected notes", selected.body());
            assertTrue(KungReleaseNotes.fetchTag(client, "v0.2.9", URI.create(endpoint + "/tags/")).isEmpty());
            assertEquals(List.of("/releases?per_page=10&page=1", "/releases?per_page=10&page=2",
                "/releases/tags/0.3.0-beta%2Btest", "/releases/tags/v0.2.9"), requests);
        } finally { server.stop(0); }
    }

    private static KungReleaseHistory.Release release(String version) {
        return new KungReleaseHistory.Release("v" + version, "2026-09-12");
    }
    private static KungReleaseNotes.Notes body(String tag) {
        var release = new KungReleaseHistory.Release(tag, "");
        return new KungReleaseNotes.Notes(release.version(), "Notes for " + tag, release.page());
    }
    private static final class Worker implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
