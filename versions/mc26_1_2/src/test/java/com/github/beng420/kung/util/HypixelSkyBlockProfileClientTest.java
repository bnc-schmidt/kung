package com.github.beng420.kung.util;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.dungeon.KungServerSession;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.ProfileResult;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public final class HypixelSkyBlockProfileClientTest {
    @Test public void providersKeepTheirHeadersAndRefreshTimestampsAcrossServerRetries() throws Exception {
        for (String provider : PROVIDERS) {
            try (var server = new Server(new int[] {502, 503, 200}, "{\"success\":true}")) {
                assertTrue(load(provider, server.uri()).get(10, TimeUnit.SECONDS).get("success").getAsBoolean());
                assertEquals(3, server.requests.size());
                long previousTimestamp = 0;
                for (var request : server.requests) {
                    var headers = request.getRequestHeaders();
                    String query = request.getRequestURI().getRawQuery();
                    assertEquals("GET", request.getRequestMethod());
                    assertEquals("application/json", headers.getFirst("Accept"));
                    assertEquals("Kung-HypixelProfile", headers.getFirst("User-Agent"));
                    assertTrue(query.startsWith("uuid=sample+id%2B"));
                    if (provider.equals("Hypixel")) {
                        assertEquals("test-key", headers.getFirst("API-Key"));
                        assertNull(headers.getFirst("Authorization"));
                        assertEquals("no-cache, no-store, max-age=0", headers.getFirst("Cache-Control"));
                        assertEquals("no-cache", headers.getFirst("Pragma"));
                        long timestamp = Long.parseLong(query.substring(query.indexOf("&_kungFresh=") + 12));
                        assertTrue("Retries need a fresh provider timestamp", timestamp > previousTimestamp);
                        previousTimestamp = timestamp;
                    } else {
                        // The Hypixel key stays on the Kung server; the client only sends its session token.
                        assertEquals("uuid=sample+id%2B", query);
                        assertEquals("Bearer session-token", headers.getFirst("Authorization"));
                        assertNull(headers.getFirst("API-Key"));
                    }
                }
            }
        }
    }

    @Test public void retryableFailuresStopAtThreeWhileClientErrorsAndInvalidBodiesFailImmediately() throws Exception {
        for (String provider : PROVIDERS) {
            String service = provider.equals("Hypixel") ? "Hypixel" : "Kung server";
            // A 429 is not retried: the retries only burn the rate limit that caused it.
            for (int status : new int[] {500, 400, 429}) {
                try (var server = new Server(new int[] {status}, "{}")) {
                    var failure = assertThrows(ExecutionException.class, () -> load(provider, server.uri()).get(10, TimeUnit.SECONDS));
                    assertEquals(service + " returned HTTP " + status, failure.getCause().getMessage());
                    assertEquals(status == 500 ? 3 : 1, server.requests.size());
                }
            }
            for (String body : List.of("{\"success\":false}", "not json")) {
                try (var server = new Server(new int[] {200}, body)) {
                    var failure = assertThrows(ExecutionException.class, () -> load(provider, server.uri()).get(10, TimeUnit.SECONDS));
                    if (body.startsWith("{")) assertEquals(service + " returned success=false", failure.getCause().getMessage());
                    assertEquals(1, server.requests.size());
                }
            }
        }
    }

    @Test public void truncatedResponsesRetryAndKeepTheirTransportFailureAfterThreeAttempts() throws Exception {
        for (String provider : PROVIDERS) {
            try (var server = new ServerSocket()) {
                server.bind(new InetSocketAddress("127.0.0.1", 0));
                server.setSoTimeout(5_000);
                var responses = CompletableFuture.runAsync(() -> {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try (var socket = server.accept()) {
                            socket.setSoTimeout(2_000);
                            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            while (true) {
                                String line = input.readLine();
                                if (line == null || line.isEmpty()) break;
                            }
                            // Closing the raw socket guarantees EOF after the incomplete body.
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n"
                                + "Connection: close\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
                        } catch (IOException exception) {
                            throw new CompletionException(exception);
                        }
                    }
                });
                var endpoint = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/profiles");
                var failure = assertThrows(ExecutionException.class, () -> load(provider, endpoint).get(10, TimeUnit.SECONDS));
                responses.get(10, TimeUnit.SECONDS);
                assertTrue(failure.getCause() instanceof IOException);
            }
        }
    }

    @Test public void errorsNameTheUpstreamReasonAndCombinedErrorsKeepTheChatMessages() throws Exception {
        try (var server = new Server(new int[] {403}, "{\"success\":false,\"cause\":\"Invalid API key\"}")) {
            var failure = assertThrows(ExecutionException.class, () -> load("KungServer", server.uri()).get(10, TimeUnit.SECONDS));
            assertEquals("Kung server returned HTTP 403: Invalid API key", failure.getCause().getMessage());
        }
        try (var server = new Server(new int[] {403}, "<html><body>" + "Blocked ".repeat(200) + "</body></html>")) {
            var failure = assertThrows(ExecutionException.class, () -> load("Hypixel", server.uri()).get(10, TimeUnit.SECONDS));
            assertEquals("Hypixel returned HTTP 403", failure.getCause().getMessage());
        }
        assertEquals("X returned HTTP 403: nope", HypixelSkyBlockProfileClient.httpError("X", 403, "{\"error\":\"nope\"}"));
        assertEquals("X returned HTTP 403: a b c", HypixelSkyBlockProfileClient.httpError("X", 403, "{\"message\":\"a\\n b | c\"}"));
        assertEquals("X returned HTTP 403", HypixelSkyBlockProfileClient.httpError("X", 403, "{\"cause\":{\"a\":1},\"error\":true}"));
        assertEquals("X returned HTTP 403: " + "y".repeat(100),
            HypixelSkyBlockProfileClient.httpError("X", 403, "{\"cause\":\"" + "y".repeat(500) + "\"}"));

        String own = "Hypixel returned HTTP 403: Invalid API key";
        String[][] chat = {
            {"Kung server returned HTTP 403: Invalid API key", "CA data service blocked the request"},
            {"Kung server returned HTTP 429", "CA data service is rate limited"},
            {"request timed out", "CA data service timed out"},
            {"Kung server returned HTTP 500", "CA data service unavailable"},
            {KungServerSession.NOT_ALLOWED, "not allowed on the Kung server; set your own Hypixel API key"},
            {"Sign-in failed: Invalid session: restart the game and the launcher", MOJANG_SESSION_REPLY},
            {"Sign-in failed: Can't confirm your Mojang session (Status: 429): restart the game and the launcher",
                MOJANG_SESSION_REPLY},
            {"Sign-in failed: Can't confirm your Mojang session (request timed out): restart the game and the launcher",
                MOJANG_SESSION_REPLY},
            {"Sign-in failed: Mojang is unreachable", "CA data service unavailable"},
            {"Sign-in failed: too many attempts, try again later", "CA data service unavailable"}};
        for (String[] row : chat) {
            var combined = HypixelSkyBlockProfileClient.combine(ProfileResult.error(own), ProfileResult.error(row[0]));
            assertEquals(own + " | " + row[0], combined.error());
            assertEquals(row[1], CatacombsAverageCalculator.externalProfileError(combined.error()));
            assertEquals(row[1], CatacombsAverageCalculator.externalProfileError(row[0]));
        }
        String noSource = HypixelSkyBlockProfileClient.NO_SOURCE;
        assertEquals(noSource, CatacombsAverageCalculator.externalProfileError(noSource));
        assertEquals("Kung server returned HTTP 403", HypixelSkyBlockProfileClient.combine(
            ProfileResult.error(noSource), ProfileResult.error("Kung server returned HTTP 403")).error());
        assertEquals("invalid username", HypixelSkyBlockProfileClient.combine(
            ProfileResult.error("invalid username"), ProfileResult.error("invalid username")).error());
        var served = ProfileResult.ok(null, 0);
        assertSame(served, HypixelSkyBlockProfileClient.combine(ProfileResult.error(own), served));
    }

    private static final String MOJANG_SESSION_REPLY =
        "Kung sign-in failed (Mojang session invalid, the Kung user has to restart the game)";
    private static final List<String> PROVIDERS = List.of("Hypixel", "KungServer");
    private static HttpServer authServer;
    private static DungeonConfig previousDungeonConfig;

    /** The Kung server signs in at its own address; this one only answers the two sign-in calls. */
    @BeforeClass public static void startAuthServer() throws IOException {
        authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authServer.createContext("/auth/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = (exchange.getRequestURI().getPath().endsWith("/challenge")
                ? "{\"challenge\":\"00000000000000000000000000000001\",\"expiresInMs\":60000}"
                : "{\"token\":\"session-token\",\"expiresAt\":" + (System.currentTimeMillis() + 3_600_000L) + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        authServer.start();
        previousDungeonConfig = KungConfig.get().dungeon;
        KungConfig.get().dungeon = new DungeonConfig();
        KungConfig.get().dungeon.setRoomSyncServerUrl("http://127.0.0.1:" + authServer.getAddress().getPort());
    }

    @AfterClass public static void stopAuthServer() {
        KungConfig.get().dungeon = previousDungeonConfig;
        authServer.stop(0);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<JsonObject> load(String provider, URI endpoint) throws Exception {
        Object credential = "test-key";
        Class<?> credentialType = String.class;
        if (provider.equals("KungServer")) {
            // The session constructor is package-private so only tests can swap out the Mojang join.
            var constructor = KungServerSession.class.getDeclaredConstructor(UnaryOperator.class);
            constructor.setAccessible(true);
            credential = constructor.newInstance((UnaryOperator<String>) challenge -> "Tester");
            credentialType = KungServerSession.class;
        }
        var method = HypixelSkyBlockProfileClient.class.getDeclaredMethod("load" + provider + "Object",
            URI.class, String.class, String.class, credentialType);
        method.setAccessible(true);
        return (CompletableFuture<JsonObject>) method.invoke(HypixelSkyBlockProfileClient.INSTANCE,
            endpoint, "uuid", "sample id+", credential);
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;
        private final List<com.sun.net.httpserver.HttpExchange> requests = new ArrayList<>();

        Server(int[] statuses, String response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/profiles", exchange -> {
                int status = statuses[Math.min(requests.size(), statuses.length - 1)];
                requests.add(exchange);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                finally { exchange.close(); }
            });
            server.start();
        }

        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/profiles"); }
        @Override public void close() { server.stop(0); }
    }
}
