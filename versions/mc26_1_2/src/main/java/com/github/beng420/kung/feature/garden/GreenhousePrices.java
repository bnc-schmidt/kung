package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.runtime.KungPaths;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Instant-buy Bazaar prices of the crops, for the planner's cost mode. Public endpoint, no API key:
 * {@code {"success":true,"products":{"PRODUCT_ID":{"quick_status":{"buyPrice":..,"sellPrice":..}}}}}.
 * {@code buyPrice} is what an instant buy costs (it is above {@code sellPrice}). The answer is 3.6 MB with 2000+
 * products, so it is read as a stream and only the crops' products are kept.
 *
 * <p>Nothing here touches the client thread except cheap reads: {@link #refreshIfStale()} starts at most one
 * fetch on a daemon thread, a failure keeps the last cache, and the cache survives restarts in
 * {@code config/kung/greenhouse-prices.json}.
 */
final class GreenhousePrices {
    static final String ENDPOINT = "https://api.hypixel.net/v2/skyblock/bazaar";
    static final String FILE_NAME = "greenhouse-prices.json";
    static final long STALE_AFTER_MILLIS = 10L * 60_000L;
    /** Spacing of retries after a failure, so a caller asking every frame does not hammer the API. */
    static final long RETRY_AFTER_FAILURE_MILLIS = 60_000L;
    static final int MAX_CACHE_BYTES = 100_000;
    /** Cap on the decompressed answer (the real one is 3.6 MB). */
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    /** A single price above this is treated as garbage, not as a price. */
    static final double MAX_PRICE = 1.0e9;
    private static final int MAX_CACHED_PRODUCTS = 500;
    private static final Pattern ID = Pattern.compile("[A-Z0-9_]+(?::[0-9]+)?");
    private static final Gson JSON = new Gson();

    enum Status { NONE, FRESH, STALE, OFFLINE }

    /** Returns the Bazaar answer's JSON text; throws when it cannot. Tests inject their own. */
    @FunctionalInterface
    interface Fetcher {
        String fetch() throws IOException;
    }

    private record Snapshot(long fetchedAtMillis, Map<String, Double> prices) { }

    private record Saved(int schema, long fetchedAt, Map<String, Double> prices) { }

    private static GreenhousePrices instance;

    private final Path file;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final Executor executor;
    private final Set<String> products;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicInteger version = new AtomicInteger();
    private volatile Snapshot snapshot;
    /** The disk cache has been looked at; false only for {@link #get()}'s instance until its first worker run. */
    private volatile boolean cacheLoaded;
    private volatile boolean lastFailed;
    private volatile long lastAttemptMillis;
    private volatile String lastError = "";

    /**
     * @param file     the disk cache; read now, written after each good fetch
     * @param products the product ids to keep from an answer
     * @param clock    epoch milliseconds
     * @param executor runs the fetch off the client thread
     */
    GreenhousePrices(Path file, Set<String> products, Fetcher fetcher, LongSupplier clock, Executor executor) {
        this(file, products, fetcher, clock, executor, true);
    }

    /** With {@code loadNow} false nothing is read here: the first worker run reads the cache before it fetches. */
    private GreenhousePrices(Path file, Set<String> products, Fetcher fetcher, LongSupplier clock, Executor executor,
                             boolean loadNow) {
        this.file = file;
        this.products = Set.copyOf(products);
        this.fetcher = fetcher;
        this.clock = clock;
        this.executor = executor;
        if (loadNow) {
            snapshot = read(file);
            cacheLoaded = true;
        }
    }

    /**
     * Like the constructor, but the disk cache is read later on the executor's thread (the first
     * {@link #refreshIfStale()}), as the shared instance does, so creating this never touches the disk.
     */
    static GreenhousePrices lazy(Path file, Set<String> products, Fetcher fetcher, LongSupplier clock, Executor executor) {
        return new GreenhousePrices(file, products, fetcher, clock, executor, false);
    }

    /** The planner's shared instance: Bazaar over HTTP, cache in the Kung settings directory. */
    static synchronized GreenhousePrices get() {
        if (instance == null) {
            instance = lazy(KungPaths.fileLayout().settingsDirectory().resolve(FILE_NAME),
                cropProducts(), new HttpFetcher(), System::currentTimeMillis, GreenhousePrices::daemon);
        }
        return instance;
    }

    /** The product ids the planner prices: every crop with a SkyBlock item id. */
    static Set<String> cropProducts() {
        return GreenhouseItems.all().stream().map(GreenhouseItems.Item::id).filter(id -> id != null)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** The instant-buy price of one item, empty when unknown or the Bazaar lists no offer for it. */
    Optional<Double> unitPrice(String productId) {
        Snapshot current = snapshot;
        return current == null || productId == null ? Optional.empty() : Optional.ofNullable(current.prices().get(productId));
    }

    /** Milliseconds since the prices were fetched, -1 when there are none. */
    long ageMillis() {
        Snapshot current = snapshot;
        return current == null ? -1 : Math.max(0, clock.getAsLong() - current.fetchedAtMillis());
    }

    /** FRESH: under 10 minutes old. Else OFFLINE if the last fetch failed, STALE with old prices, NONE without any. */
    Status status() {
        Snapshot current = snapshot;
        if (current != null && !stale(current, clock.getAsLong())) return Status.FRESH;
        if (lastFailed) return Status.OFFLINE;
        return current == null ? Status.NONE : Status.STALE;
    }

    /** Changes whenever the prices or the status did; a screen polls it to know when to rebuild its costs. */
    int version() { return version.get(); }

    boolean refreshing() { return refreshing.get(); }

    /** Why the last fetch failed, empty after a good one. */
    String lastError() { return lastError; }

    /**
     * Starts a fetch when the prices are missing or over 10 minutes old and none is running. Returns at once; the
     * result shows in {@link #version()}. A failed fetch is retried at most once a minute.
     */
    void refreshIfStale() {
        long now = clock.getAsLong();
        Snapshot current = snapshot;
        if (cacheLoaded && current != null && !stale(current, now)) return;
        long sinceAttempt = now - lastAttemptMillis;
        if (lastFailed && sinceAttempt >= 0 && sinceAttempt < RETRY_AFTER_FAILURE_MILLIS) return;
        if (!refreshing.compareAndSet(false, true)) return;
        lastAttemptMillis = now;
        try {
            executor.execute(this::fetch);
        } catch (RuntimeException rejected) {
            refreshing.set(false);
            fail(rejected);
        }
    }

    /** A cache dated in the future (the clock was set back) counts as stale. */
    private static boolean stale(Snapshot snapshot, long now) {
        long age = now - snapshot.fetchedAtMillis();
        return age < 0 || age >= STALE_AFTER_MILLIS;
    }

    /** Reads the disk cache once, off the client thread; a fetch that got there first is never overwritten. */
    private void loadCache() {
        if (cacheLoaded) return;
        Snapshot disk = read(file);
        synchronized (this) {
            if (cacheLoaded) return;
            cacheLoaded = true;
            if (snapshot == null && disk != null) {
                snapshot = disk;
                version.incrementAndGet();
            }
        }
    }

    private void fetch() {
        try {
            loadCache();
            Snapshot cached = snapshot;
            // A cache that is still fresh once it has been read needs no request.
            if (cached != null && !stale(cached, clock.getAsLong())) return;
            Map<String, Double> prices = parse(fetcher.fetch(), products);
            if (prices.isEmpty()) throw new IOException("The Bazaar answer held no crop prices");
            Snapshot fresh = new Snapshot(clock.getAsLong(), Map.copyOf(prices));
            snapshot = fresh;
            lastFailed = false;
            lastError = "";
            version.incrementAndGet();
            try {
                write(file, fresh);
            } catch (IOException | RuntimeException unwritable) {
                KungMod.LOGGER.warn("Cannot save the Greenhouse price cache: {}", unwritable.toString());
            }
        } catch (IOException | RuntimeException failure) {
            fail(failure);
        } finally {
            refreshing.set(false);
        }
    }

    private void fail(Exception failure) {
        if (!lastFailed) KungMod.LOGGER.warn("Bazaar prices unavailable: {}", failure.toString());
        lastFailed = true;
        lastError = String.valueOf(failure.getMessage());
        version.incrementAndGet();
    }

    private static void daemon(Runnable task) {
        Thread thread = new Thread(task, "Kung-GreenhousePrices");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The {@code buyPrice} of each wanted product, streamed so the 3.6 MB answer never becomes a tree. A product
     * without offers (price 0) or without {@code quick_status} is left out, which the cost model treats as no data.
     */
    static Map<String, Double> parse(String json, Set<String> wanted) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        boolean success = false;
        boolean products = false;
        try (JsonReader in = new JsonReader(new StringReader(json))) {
            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "success" -> success = in.nextBoolean();
                    case "products" -> {
                        readProducts(in, wanted, out);
                        products = true;
                    }
                    default -> in.skipValue();
                }
            }
            in.endObject();
        } catch (RuntimeException unreadable) {
            throw new IOException("Unreadable Bazaar answer: " + unreadable.getMessage(), unreadable);
        }
        if (!success || !products) throw new IOException("Hypixel answered without Bazaar products");
        return out;
    }

    private static void readProducts(JsonReader in, Set<String> wanted, Map<String, Double> out) throws IOException {
        in.beginObject();
        while (in.hasNext()) {
            String id = in.nextName();
            if (!wanted.contains(id) || in.peek() != JsonToken.BEGIN_OBJECT) {
                in.skipValue();
                continue;
            }
            double price = readBuyPrice(in);
            if (usable(price)) out.put(id, price);
        }
        in.endObject();
    }

    private static double readBuyPrice(JsonReader in) throws IOException {
        double price = 0;
        in.beginObject();
        while (in.hasNext()) {
            if (!in.nextName().equals("quick_status") || in.peek() != JsonToken.BEGIN_OBJECT) {
                in.skipValue();
                continue;
            }
            in.beginObject();
            while (in.hasNext()) {
                if (in.nextName().equals("buyPrice") && in.peek() == JsonToken.NUMBER) price = in.nextDouble();
                else in.skipValue();
            }
            in.endObject();
        }
        in.endObject();
        return price;
    }

    private static boolean usable(double price) { return Double.isFinite(price) && price > 0 && price <= MAX_PRICE; }

    /** The disk cache, or null when it is missing, oversized, corrupt or holds anything implausible. */
    private static Snapshot read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_CACHE_BYTES) return null;
            Saved saved = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (saved == null || saved.schema() != 1 || saved.fetchedAt() <= 0 || saved.prices() == null
                || saved.prices().isEmpty() || saved.prices().size() > MAX_CACHED_PRODUCTS) {
                return null;
            }
            for (Map.Entry<String, Double> entry : saved.prices().entrySet()) {
                if (entry.getKey() == null || !ID.matcher(entry.getKey()).matches() || entry.getValue() == null
                    || !usable(entry.getValue())) {
                    return null;
                }
            }
            return new Snapshot(saved.fetchedAt(), Map.copyOf(saved.prices()));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Writes a sibling file and moves it over the cache, so a crash never leaves half a document. */
    private static void write(Path file, Snapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(new Saved(1, snapshot.fetchedAtMillis(), snapshot.prices())),
            StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The answer text, gunzipped when the server compressed it (500 KB instead of 3.6 MB), capped in size. */
    static String decode(byte[] body, String contentEncoding) throws IOException {
        try (InputStream stream = "gzip".equalsIgnoreCase(contentEncoding)
            ? new GZIPInputStream(new ByteArrayInputStream(body)) : new ByteArrayInputStream(body)) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Bazaar answer is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Production fetcher: one GET with the repo's usual 5 s connect and 10 s request timeouts. */
    static final class HttpFetcher implements Fetcher {
        /** Built on first use, which is on the worker thread: its TLS setup is a visible hitch on the client thread. */
        private HttpClient client;

        private synchronized HttpClient client() {
            if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            return client;
        }

        @Override
        public String fetch() throws IOException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "Kung-GreenhousePrices")
                .GET()
                .build();
            HttpResponse<byte[]> response;
            try {
                response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", interrupted);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Hypixel returned HTTP " + response.statusCode());
            }
            return decode(response.body(), response.headers().firstValue("Content-Encoding").orElse(""));
        }
    }
}
