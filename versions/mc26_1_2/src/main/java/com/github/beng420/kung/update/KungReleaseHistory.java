package com.github.beng420.kung.update;

import com.github.beng420.kung.KungMod;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** On-demand history has no installed-version receipt and never selects a release for the popup. */
public final class KungReleaseHistory {
    public static final URI RELEASES_PAGE = URI.create("https://github.com/bnc-schmidt/kung/releases");
    private static final URI API = URI.create("https://api.github.com/repos/bnc-schmidt/kung/releases");
    private static final int PAGE_SIZE = 10;
    private static final KungReleaseNotes.Snapshot IDLE = new KungReleaseNotes.Snapshot(KungReleaseNotes.Status.IDLE, null);
    private static final KungReleaseNotes.Snapshot LOADING = new KungReleaseNotes.Snapshot(KungReleaseNotes.Status.LOADING, null);
    private static final PageSnapshot PAGE_IDLE = new PageSnapshot(KungReleaseNotes.Status.IDLE, null);
    private static final PageSnapshot PAGE_LOADING = new PageSnapshot(KungReleaseNotes.Status.LOADING, null);
    private final Executor executor;
    private final PageFetcher pageFetcher;
    private final KungReleaseNotes.Fetcher notesFetcher;
    private final LinkedHashMap<Integer, PageSnapshot> pages = new LinkedHashMap<>(8, 0.75F, true);
    private final LinkedHashMap<String, KungReleaseNotes.Snapshot> releases = new LinkedHashMap<>(16, 0.75F, true);
    private final Set<Integer> loadingPages = new HashSet<>();
    private final Set<String> loadingReleases = new HashSet<>();

    KungReleaseHistory(Executor executor, HttpClient client) {
        this(executor, page -> fetchPage(client, page, API),
            tag -> KungReleaseNotes.fetchTag(client, tag, URI.create(API + "/tags/")));
    }

    KungReleaseHistory(Executor executor, PageFetcher pageFetcher, KungReleaseNotes.Fetcher notesFetcher) {
        this.executor = executor;
        this.pageFetcher = pageFetcher;
        this.notesFetcher = notesFetcher;
    }

    public synchronized PageSnapshot page(int number) {
        return loadingPages.contains(number) ? PAGE_LOADING : pages.getOrDefault(number, PAGE_IDLE);
    }

    public synchronized KungReleaseNotes.Snapshot notes(String tag) {
        return loadingReleases.contains(tag) ? LOADING : releases.getOrDefault(tag, IDLE);
    }

    public synchronized void requestPage(int number) {
        if (number < 1 || loadingPages.contains(number) || page(number).status() == KungReleaseNotes.Status.READY) return;
        loadingPages.add(number);
        executor.execute(() -> {
            PageSnapshot result;
            try {
                result = new PageSnapshot(KungReleaseNotes.Status.READY, pageFetcher.fetch(number));
            } catch (Exception exception) {
                logFailure("release history", exception);
                result = new PageSnapshot(KungReleaseNotes.Status.FAILED, null);
            }
            synchronized (this) {
                pages.put(number, result);
                trim(pages, 4);
                loadingPages.remove(number);
            }
        });
    }

    public synchronized void requestNotes(Release release) {
        String tag = release.tag();
        if (loadingReleases.contains(tag) || notes(tag).status() == KungReleaseNotes.Status.READY) return;
        loadingReleases.add(tag);
        executor.execute(() -> {
            KungReleaseNotes.Snapshot result;
            try {
                result = notesFetcher.fetch(tag)
                    .filter(value -> value.version().equals(release.version()))
                    .map(value -> new KungReleaseNotes.Snapshot(KungReleaseNotes.Status.READY, value))
                    .orElseGet(() -> new KungReleaseNotes.Snapshot(KungReleaseNotes.Status.UNAVAILABLE, null));
            } catch (Exception exception) {
                logFailure("release " + tag, exception);
                result = new KungReleaseNotes.Snapshot(KungReleaseNotes.Status.FAILED, null);
            }
            synchronized (this) {
                releases.put(tag, result);
                trim(releases, 8);
                loadingReleases.remove(tag);
            }
        });
    }

    private static void trim(LinkedHashMap<?, ?> cache, int limit) {
        while (cache.size() > limit) cache.pollFirstEntry();
    }

    private static void logFailure(String request, Exception exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        KungMod.LOGGER.warn("Could not load Kung {}.", request, exception);
    }

    static Page fetchPage(HttpClient client, int number, URI endpoint) throws IOException, InterruptedException {
        var response = KungReleaseNotes.requestJson(client, URI.create(endpoint + "?per_page=" + PAGE_SIZE + "&page=" + number));
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());
        boolean hasNext = response.headers().allValues("Link").stream().anyMatch(value -> value.contains("rel=\"next\""));
        return parsePage(response.body(), hasNext);
    }

    static Page parsePage(String json, boolean hasNext) {
        var entries = new ArrayList<Release>();
        var tags = new HashSet<String>();
        for (var element : JsonParser.parseString(json).getAsJsonArray()) {
            if (entries.size() == PAGE_SIZE) break;
            if (!element.isJsonObject()) continue;
            var object = element.getAsJsonObject();
            if (object.has("draft") && object.get("draft").getAsBoolean()) continue;
            String tag = object.has("tag_name") && object.get("tag_name").isJsonPrimitive()
                ? object.get("tag_name").getAsString() : "";
            if (tag.isBlank() || !tags.add(tag)) continue;
            String date = object.has("published_at") && object.get("published_at").isJsonPrimitive()
                ? object.get("published_at").getAsString() : "";
            date = date.matches("\\d{4}-\\d{2}-\\d{2}T.*") ? date.substring(0, 10) : "";
            // GitHub includes bodies in its list response; retain only the small selection metadata.
            entries.add(new Release(tag, date));
        }
        return new Page(List.copyOf(entries), hasNext);
    }

    public record Release(String tag, String date) {
        public String version() { return KungReleaseNotes.normalizedVersion(tag); }
        public URI page() { return URI.create(RELEASES_PAGE + "/tag/" + KungReleaseNotes.encoded(tag)); }
    }
    public record Page(List<Release> releases, boolean hasNext) {
        public Page { releases = List.copyOf(releases); }
    }
    public record PageSnapshot(KungReleaseNotes.Status status, Page page) { }
    @FunctionalInterface interface PageFetcher { Page fetch(int number) throws Exception; }
}
