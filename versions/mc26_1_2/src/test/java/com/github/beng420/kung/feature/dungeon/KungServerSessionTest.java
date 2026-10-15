package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class KungServerSessionTest {
    @Test public void signsInThroughMojangOnceCachesTheTokenAndSignsInAgainOnlyAfterA401() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> logins = new CopyOnWriteArrayList<>();
        var roomStatuses = new ConcurrentLinkedDeque<Integer>();
        int[] loginStatus = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            log.add(path + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            int status = 200;
            String response = "{}";
            switch (path) {
                case "/auth/challenge" -> response = "{\"challenge\":\"" + challenge(log.size()) + "\",\"expiresInMs\":60000}";
                case "/auth/login" -> {
                    logins.add(body);
                    status = loginStatus[0];
                    response = status != 200 ? "{\"error\":\"not allowed\"}"
                        : "{\"token\":\"token-" + logins.size() + "\",\"expiresAt\":"
                            + (System.currentTimeMillis() + 3_600_000L) + ",\"uuid\":\"id\",\"name\":\"Tester\"}";
                }
                default -> status = roomStatuses.isEmpty() ? 200 : roomStatuses.poll();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        DungeonConfig previous = KungConfig.get().dungeon;
        try {
            KungConfig.get().dungeon = new DungeonConfig();
            KungConfig.get().dungeon.setRoomSyncServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
            List<String> joined = new CopyOnWriteArrayList<>();
            var session = new KungServerSession(challenge -> {
                joined.add(challenge);
                return "Tester";
            });

            assertEquals("token-1", session.token().get(10, TimeUnit.SECONDS));
            assertEquals("Mojang joins exactly the server's challenge", List.of(challenge(1)), joined);
            assertEquals("{\"name\":\"Tester\",\"challenge\":\"" + challenge(1) + "\"}", logins.get(0));
            assertEquals("sign-in never sends a bearer", List.of("/auth/challenge null", "/auth/login null"), log);
            assertEquals("Signed in as Tester", session.status());
            assertEquals("token-1", session.token().get(10, TimeUnit.SECONDS));
            assertEquals("a cached token makes no requests", 2, log.size());

            // A restarted server forgets the session: one new sign-in, one retry.
            roomStatuses.add(401);
            var rooms = HttpRequest.newBuilder(DungeonRoomDataSyncClient.endpoint("rooms")).GET();
            assertEquals(200, session.send(rooms).get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(List.of("/rooms Bearer token-1", "/auth/challenge null", "/auth/login null", "/rooms Bearer token-2"),
                log.subList(2, log.size()));
            assertEquals(List.of(challenge(1), challenge(4)), joined);

            long[] now = {System.currentTimeMillis()};
            loginStatus[0] = 403;
            var stranger = new KungServerSession(challenge -> "Stranger");
            stranger.clock = () -> now[0];
            var refused = assertThrows(ExecutionException.class, () -> stranger.token().get(10, TimeUnit.SECONDS));
            assertEquals(KungServerSession.NOT_ALLOWED, refused.getCause().getMessage());
            assertEquals("Not allowed on this server", stranger.status());

            // A refusal sticks until the game restarts or the URL changes, even a day later.
            int requests = log.size();
            now[0] += 86_400_000L;
            loginStatus[0] = 200;
            assertThrows(ExecutionException.class, () -> stranger.token().get(10, TimeUnit.SECONDS));
            assertEquals("a refused player makes no more requests", requests, log.size());

            // Any other failure is reused for a minute, then signs in again.
            loginStatus[0] = 500;
            var flaky = new KungServerSession(challenge -> "Flaky");
            flaky.clock = () -> now[0];
            assertThrows(ExecutionException.class, () -> flaky.token().get(10, TimeUnit.SECONDS));
            requests = log.size();
            loginStatus[0] = 200;
            assertThrows(ExecutionException.class, () -> flaky.token().get(10, TimeUnit.SECONDS));
            assertEquals("a failure is reused within the minute", requests, log.size());
            now[0] += 61_000L;
            assertEquals("token-5", flaky.token().get(10, TimeUnit.SECONDS));
        } finally {
            KungConfig.get().dungeon = previous;
            server.stop(0);
        }
    }

    @Test public void mojangJoinFailuresKeepTheirCauseInTheMessage() {
        assertEquals("Mojang is unreachable",
            KungServerSession.signInFailure(new AuthenticationUnavailableException("Status: 503")));
        assertEquals("Invalid session: restart the game and the launcher",
            KungServerSession.signInFailure(new InvalidCredentialsException("Invalid session")));
        assertEquals("Can't confirm your Mojang session (Status: 429): restart the game and the launcher",
            KungServerSession.signInFailure(new AuthenticationException("Status: 429")));
        assertEquals("Can't confirm your Mojang session (Insufficient privileges): restart the game and the launcher",
            KungServerSession.signInFailure(new InsufficientPrivilegesException("Insufficient privileges")));
        assertEquals("a messageless exception falls back to its class name",
            "Can't confirm your Mojang session (UserBannedException): restart the game and the launcher",
            KungServerSession.signInFailure(new UserBannedException()));
    }

    @Test public void onlyUnusableMojangSessionsAskForARestart() {
        assertTrue(KungServerSession.needsRestart(
            "Sign-in failed: " + KungServerSession.signInFailure(new InvalidCredentialsException("Invalid session"))));
        assertTrue(KungServerSession.needsRestart(
            "Sign-in failed: " + KungServerSession.signInFailure(new AuthenticationException("Status: 429"))));
        assertFalse(KungServerSession.needsRestart(
            "Sign-in failed: " + KungServerSession.signInFailure(new AuthenticationUnavailableException("Status: 503"))));
        assertFalse(KungServerSession.needsRestart("Sign-in failed: too many attempts, try again later"));
        assertFalse(KungServerSession.needsRestart(KungServerSession.NOT_ALLOWED));
        assertFalse(KungServerSession.needsRestart(null));
    }

    /** The fake server numbers its challenges by request count, so each one is unique. */
    private static String challenge(int request) {
        return String.format("%032x", request);
    }
}
