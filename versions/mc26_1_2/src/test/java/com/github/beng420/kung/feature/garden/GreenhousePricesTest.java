package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GreenhousePricesTest {
    private static final long MINUTE = 60_000L;
    private static final Set<String> WANTED = Set.of("INK_SACK:3", "MELON", "PUMPKIN", "SEEDS");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** The real answer's shape, trimmed: other products first, per-product summaries, then quick_status. */
    static String payload(Map<String, Double> buyPrices) {
        StringBuilder json = new StringBuilder("{\"success\":true,\"lastUpdated\":1792230042766,\"products\":{")
            .append("\"DIAMOND\":{\"product_id\":\"DIAMOND\",\"sell_summary\":[{\"amount\":1,\"pricePerUnit\":3.7,"
                + "\"orders\":1}],\"buy_summary\":[],\"quick_status\":{\"productId\":\"DIAMOND\",\"sellPrice\":3.7,"
                + "\"sellVolume\":9,\"buyPrice\":17.28,\"buyVolume\":9}}");
        buyPrices.forEach((id, price) -> json.append(",\"").append(id).append("\":{\"product_id\":\"").append(id)
            .append("\",\"sell_summary\":[{\"amount\":5,\"pricePerUnit\":1.5,\"orders\":1}],\"buy_summary\":[],"
                + "\"quick_status\":{\"productId\":\"").append(id).append("\",\"sellPrice\":0.5,\"sellVolume\":1,"
                + "\"sellMovingWeek\":2,\"sellOrders\":3,\"buyPrice\":").append(price)
            .append(",\"buyVolume\":1,\"buyMovingWeek\":2,\"buyOrders\":3}}"));
        return json.append("}}").toString();
    }

    /** A price source that already holds exactly these prices (a fetch ran on the calling thread). */
    static GreenhousePrices fixed(Path file, Map<String, Double> buyPrices) {
        GreenhousePrices prices = new GreenhousePrices(file, GreenhousePrices.cropProducts(),
            () -> payload(buyPrices), () -> 1_000_000L, Runnable::run);
        prices.refreshIfStale();
        return prices;
    }

    private Path cacheFile() { return temporary.getRoot().toPath().resolve("kung").resolve("greenhouse-prices.json"); }

    private static final class Rig {
        final AtomicLong now = new AtomicLong(10_000_000L);
        final AtomicInteger calls = new AtomicInteger();
        final List<Runnable> queued = new ArrayList<>();
        volatile IOException failure;
        volatile Map<String, Double> served = Map.of("MELON", 4.0, "PUMPKIN", 40.0);
        GreenhousePrices prices;

        void open(Path file, boolean direct) {
            prices = new GreenhousePrices(file, WANTED, () -> {
                calls.incrementAndGet();
                if (failure != null) throw failure;
                return payload(served);
            }, now::get, direct ? Runnable::run : queued::add);
        }
    }

    @Test
    public void parseKeepsTheInstantBuyPriceOfWantedProductsOnly() throws IOException {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("INK_SACK:3", 8.65);
        prices.put("MELON", 4.0);
        prices.put("SEEDS", 0.0);
        prices.put("OTHER", 99.0);

        Map<String, Double> parsed = GreenhousePrices.parse(payload(prices), WANTED);

        // buyPrice (above sellPrice 0.5), not sellPrice; DIAMOND, OTHER and the offer-less SEEDS are dropped.
        assertEquals(Map.of("INK_SACK:3", 8.65, "MELON", 4.0), parsed);
    }

    @Test
    public void parseSkipsProductsWithoutQuickStatusOrNumber() throws IOException {
        String json = "{\"success\":true,\"products\":{\"MELON\":{\"product_id\":\"MELON\"},"
            + "\"PUMPKIN\":{\"quick_status\":{\"buyPrice\":\"n/a\"}},\"SEEDS\":null,"
            + "\"INK_SACK:3\":{\"quick_status\":{\"buyPrice\":2.5,\"sellPrice\":1}}}}";

        assertEquals(Map.of("INK_SACK:3", 2.5), GreenhousePrices.parse(json, WANTED));
    }

    @Test
    public void parseRejectsAnAnswerThatIsNotASuccessfulBazaarListing() {
        for (String json : new String[] {"{\"success\":false,\"cause\":\"Key missing\"}", "{\"success\":true}",
            "{\"success\":true,\"products\":[]}", "<html>blocked</html>", "", "null", "{\"success\":true,\"products\":{"}) {
            assertThrows(json, IOException.class, () -> GreenhousePrices.parse(json, WANTED));
        }
    }

    @Test
    public void startsWithNothingAndFetchesOnce() {
        Rig rig = new Rig();
        rig.open(cacheFile(), true);

        assertEquals(GreenhousePrices.Status.NONE, rig.prices.status());
        assertEquals(-1, rig.prices.ageMillis());
        assertFalse(rig.prices.unitPrice("MELON").isPresent());
        int version = rig.prices.version();

        rig.prices.refreshIfStale();

        assertEquals(1, rig.calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, rig.prices.status());
        assertEquals(4.0, rig.prices.unitPrice("MELON").orElseThrow(), 0.0);
        assertEquals(40.0, rig.prices.unitPrice("PUMPKIN").orElseThrow(), 0.0);
        assertFalse(rig.prices.unitPrice("SEEDS").isPresent());
        assertFalse(rig.prices.unitPrice(null).isPresent());
        assertEquals(0, rig.prices.ageMillis());
        assertNotEquals(version, rig.prices.version());
        assertEquals("", rig.prices.lastError());
    }

    @Test
    public void freshPricesAreNotFetchedAgainUntilTenMinutesPass() {
        Rig rig = new Rig();
        rig.open(cacheFile(), true);
        rig.prices.refreshIfStale();

        rig.now.addAndGet(10 * MINUTE - 1);
        rig.prices.refreshIfStale();
        assertEquals(1, rig.calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, rig.prices.status());
        assertEquals(10 * MINUTE - 1, rig.prices.ageMillis());

        rig.now.addAndGet(1);
        assertEquals(GreenhousePrices.Status.STALE, rig.prices.status());
        rig.served = Map.of("MELON", 5.0);
        rig.prices.refreshIfStale();

        assertEquals(2, rig.calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, rig.prices.status());
        assertEquals(5.0, rig.prices.unitPrice("MELON").orElseThrow(), 0.0);
        // A product the new answer no longer lists is gone, not kept from the old cache.
        assertFalse(rig.prices.unitPrice("PUMPKIN").isPresent());
    }

    @Test
    public void aFailedFetchKeepsTheLastCacheAndRetriesOnlyOncePerMinute() {
        Rig rig = new Rig();
        rig.open(cacheFile(), true);
        rig.prices.refreshIfStale();
        rig.now.addAndGet(11 * MINUTE);
        rig.failure = new IOException("Hypixel returned HTTP 503");

        rig.prices.refreshIfStale();

        assertEquals(2, rig.calls.get());
        assertEquals(GreenhousePrices.Status.OFFLINE, rig.prices.status());
        assertEquals(4.0, rig.prices.unitPrice("MELON").orElseThrow(), 0.0);
        assertEquals(11 * MINUTE, rig.prices.ageMillis());
        assertEquals("Hypixel returned HTTP 503", rig.prices.lastError());

        rig.now.addAndGet(GreenhousePrices.RETRY_AFTER_FAILURE_MILLIS - 1);
        rig.prices.refreshIfStale();
        assertEquals(2, rig.calls.get());

        rig.now.addAndGet(1);
        rig.failure = null;
        rig.prices.refreshIfStale();
        assertEquals(3, rig.calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, rig.prices.status());
        assertEquals("", rig.prices.lastError());
    }

    @Test
    public void offlineFromTheStartHasNoPricesAndSaysSo() {
        Rig rig = new Rig();
        rig.failure = new IOException("Connection refused");
        rig.open(cacheFile(), true);
        int version = rig.prices.version();

        rig.prices.refreshIfStale();

        assertEquals(GreenhousePrices.Status.OFFLINE, rig.prices.status());
        assertFalse(rig.prices.unitPrice("MELON").isPresent());
        assertEquals(-1, rig.prices.ageMillis());
        assertNotEquals(version, rig.prices.version());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    public void anAnswerWithoutAnyCropPriceCountsAsAFailure() {
        Rig rig = new Rig();
        rig.served = Map.of("NOT_A_CROP", 1.0);
        rig.open(cacheFile(), true);

        rig.prices.refreshIfStale();

        assertEquals(GreenhousePrices.Status.OFFLINE, rig.prices.status());
        assertTrue(rig.prices.lastError().contains("no crop prices"));
    }

    @Test
    public void theFetchRunsOnTheExecutorAndOnlyOneAtATime() {
        Rig rig = new Rig();
        rig.open(cacheFile(), false);

        rig.prices.refreshIfStale();
        rig.prices.refreshIfStale();

        assertEquals(1, rig.queued.size());
        assertTrue(rig.prices.refreshing());
        assertEquals(0, rig.calls.get());
        assertEquals(GreenhousePrices.Status.NONE, rig.prices.status());

        rig.queued.get(0).run();

        assertFalse(rig.prices.refreshing());
        assertEquals(1, rig.calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, rig.prices.status());
    }

    @Test
    public void aRejectedExecutorIsAFailureNotAStuckRefresh() {
        GreenhousePrices prices = new GreenhousePrices(cacheFile(), WANTED, () -> "", () -> 1L, task -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        });

        prices.refreshIfStale();

        assertFalse(prices.refreshing());
        assertEquals(GreenhousePrices.Status.OFFLINE, prices.status());
    }

    @Test
    public void theCacheSurvivesARestart() throws IOException {
        Rig first = new Rig();
        first.open(cacheFile(), true);
        first.prices.refreshIfStale();
        assertTrue(Files.isRegularFile(cacheFile()));
        assertFalse(Files.exists(cacheFile().resolveSibling("greenhouse-prices.json.tmp")));
        assertTrue(Files.size(cacheFile()) < GreenhousePrices.MAX_CACHE_BYTES);

        Rig second = new Rig();
        second.now.set(first.now.get() + 3 * MINUTE);
        second.failure = new IOException("offline");
        second.open(cacheFile(), true);

        assertEquals(GreenhousePrices.Status.FRESH, second.prices.status());
        assertEquals(40.0, second.prices.unitPrice("PUMPKIN").orElseThrow(), 0.0);
        assertEquals(3 * MINUTE, second.prices.ageMillis());
        second.prices.refreshIfStale();
        assertEquals(0, second.calls.get());

        second.now.addAndGet(8 * MINUTE);
        assertEquals(GreenhousePrices.Status.STALE, second.prices.status());
        second.prices.refreshIfStale();
        // Old prices stay usable while the fetch fails.
        assertEquals(GreenhousePrices.Status.OFFLINE, second.prices.status());
        assertEquals(4.0, second.prices.unitPrice("MELON").orElseThrow(), 0.0);
    }

    @Test
    public void aCacheDatedInTheFutureIsStale() throws IOException {
        Rig first = new Rig();
        first.open(cacheFile(), true);
        first.prices.refreshIfStale();

        Rig second = new Rig();
        second.now.set(first.now.get() - 5 * MINUTE);
        second.open(cacheFile(), true);

        assertEquals(GreenhousePrices.Status.STALE, second.prices.status());
        assertEquals(0, second.prices.ageMillis());
        second.prices.refreshIfStale();
        assertEquals(1, second.calls.get());
    }

    @Test
    public void unusableCacheFilesAreIgnored() throws IOException {
        String ok = "\"fetchedAt\":9000000,\"prices\":{\"MELON\":4.0}";
        List<String> bad = List.of(
            "not json at all",
            "",
            "[]",
            "{\"schema\":2," + ok + "}",
            "{\"schema\":1,\"fetchedAt\":0,\"prices\":{\"MELON\":4.0}}",
            "{\"schema\":1,\"fetchedAt\":9000000}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{}}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"MELON\":-1}}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"MELON\":0}}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"MELON\":1e300}}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"melon seeds\":4.0}}",
            "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"MELON\":null}}",
            "{\"schema\":1," + ok.replace("4.0", "\"x\"") + "}");
        Files.createDirectories(cacheFile().getParent());
        for (String text : bad) {
            Files.writeString(cacheFile(), text, StandardCharsets.UTF_8);
            Rig rig = new Rig();
            rig.open(cacheFile(), true);
            assertEquals(text, GreenhousePrices.Status.NONE, rig.prices.status());
            assertFalse(text, rig.prices.unitPrice("MELON").isPresent());
        }

        // The same document in good shape does load, so the cases above fail for their own reason.
        Files.writeString(cacheFile(), "{\"schema\":1," + ok + "}", StandardCharsets.UTF_8);
        Rig good = new Rig();
        good.open(cacheFile(), true);
        assertEquals(4.0, good.prices.unitPrice("MELON").orElseThrow(), 0.0);
    }

    @Test
    public void anOversizedCacheFileIsIgnored() throws IOException {
        Files.createDirectories(cacheFile().getParent());
        String padding = " ".repeat(GreenhousePrices.MAX_CACHE_BYTES);
        Files.writeString(cacheFile(), "{\"schema\":1,\"fetchedAt\":9000000,\"prices\":{\"MELON\":4.0}}" + padding,
            StandardCharsets.UTF_8);
        Rig rig = new Rig();
        rig.open(cacheFile(), true);

        assertEquals(GreenhousePrices.Status.NONE, rig.prices.status());
    }

    @Test
    public void aBadCacheIsReplacedByTheNextGoodFetch() throws IOException {
        Files.createDirectories(cacheFile().getParent());
        Files.writeString(cacheFile(), "garbage", StandardCharsets.UTF_8);
        Rig rig = new Rig();
        rig.open(cacheFile(), true);
        rig.prices.refreshIfStale();

        Rig reopened = new Rig();
        reopened.open(cacheFile(), true);
        assertEquals(4.0, reopened.prices.unitPrice("MELON").orElseThrow(), 0.0);
    }

    @Test
    public void cropProductsAreTheItemIdsOfTheBundledCrops() {
        Set<String> products = GreenhousePrices.cropProducts();

        assertTrue(products.containsAll(Set.of("INK_SACK:3", "MELON", "PUMPKIN", "SEEDS", "NETHER_STALK",
            "DOUBLE_PLANT", "CARROT_ITEM", "THORNSHADE", "DUSTGRAIN")));
        // Fire, Jerryseed and Rose Dragon Egg have no item id, so 62 of the 65 crops remain (Dead Plants is DEAD_PLANT).
        assertEquals(62, products.size());
    }

    @Test
    public void decodeReadsGzipAndPlainAndCapsTheSize() throws IOException {
        byte[] plain = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(zipped)) {
            out.write(plain);
        }

        assertEquals("{\"success\":true}", GreenhousePrices.decode(plain, ""));
        assertEquals("{\"success\":true}", GreenhousePrices.decode(zipped.toByteArray(), "gzip"));
        assertEquals("{\"success\":true}", GreenhousePrices.decode(zipped.toByteArray(), "GZIP"));
        assertThrows(IOException.class, () -> GreenhousePrices.decode(plain, "gzip"));

        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bomb)) {
            out.write(new byte[GreenhousePrices.MAX_RESPONSE_BYTES + 1]);
        }
        assertThrows(IOException.class, () -> GreenhousePrices.decode(bomb.toByteArray(), "gzip"));
    }

    @Test
    public void aLazyInstanceLeavesTheDiskCacheToItsWorkerAndSkipsTheFetchWhenItIsFresh() {
        Path file = cacheFile();
        AtomicLong clock = new AtomicLong(10_000_000L);
        new GreenhousePrices(file, WANTED, () -> payload(Map.of("MELON", 4.0)), clock::get, Runnable::run)
            .refreshIfStale();
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        GreenhousePrices lazy = GreenhousePrices.lazy(file, WANTED, () -> {
            calls.incrementAndGet();
            return payload(Map.of("MELON", 9.0));
        }, clock::get, queued::add);

        // Creating it read nothing, although a good cache is on disk.
        assertEquals(GreenhousePrices.Status.NONE, lazy.status());
        assertTrue(lazy.unitPrice("MELON").isEmpty());
        int before = lazy.version();

        lazy.refreshIfStale();
        assertEquals(1, queued.size());
        queued.get(0).run();

        assertEquals(GreenhousePrices.Status.FRESH, lazy.status());
        assertEquals(4.0, lazy.unitPrice("MELON").orElseThrow(), 0.0);
        assertEquals(0, calls.get());
        assertNotEquals(before, lazy.version());
        assertFalse(lazy.refreshing());
    }

    @Test
    public void aLazyInstanceWithAnOldCacheLoadsItThenFetches() {
        Path file = cacheFile();
        AtomicLong clock = new AtomicLong(10_000_000L);
        new GreenhousePrices(file, WANTED, () -> payload(Map.of("MELON", 4.0)), clock::get, Runnable::run)
            .refreshIfStale();
        clock.addAndGet(11 * MINUTE);
        AtomicInteger calls = new AtomicInteger();
        GreenhousePrices lazy = GreenhousePrices.lazy(file, WANTED, () -> {
            calls.incrementAndGet();
            return payload(Map.of("MELON", 9.0));
        }, clock::get, Runnable::run);

        assertEquals(GreenhousePrices.Status.NONE, lazy.status());
        lazy.refreshIfStale();

        assertEquals(1, calls.get());
        assertEquals(GreenhousePrices.Status.FRESH, lazy.status());
        assertEquals(9.0, lazy.unitPrice("MELON").orElseThrow(), 0.0);
    }

    @Test
    public void aLazyInstanceWithoutAnyCacheFetchesAndAMissingFileIsNotAnError() {
        AtomicInteger calls = new AtomicInteger();
        GreenhousePrices lazy = GreenhousePrices.lazy(cacheFile(), WANTED, () -> {
            calls.incrementAndGet();
            return payload(Map.of("PUMPKIN", 40.0));
        }, () -> 10_000_000L, Runnable::run);

        lazy.refreshIfStale();

        assertEquals(1, calls.get());
        assertEquals(40.0, lazy.unitPrice("PUMPKIN").orElseThrow(), 0.0);
    }
}
