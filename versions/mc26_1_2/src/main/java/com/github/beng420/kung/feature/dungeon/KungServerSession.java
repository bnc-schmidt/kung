package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Signs in to the Kung server the way an online-mode server checks a joining player: the server hands
 * out a challenge, the client joins it at Mojang, and the server asks Mojang whether that happened.
 * Players never type a token, and the Minecraft access token only ever goes to Mojang.
 */
public final class KungServerSession {
    public static final KungServerSession INSTANCE = new KungServerSession(KungServerSession::joinMojang);

    public static final String NOT_ALLOWED = "Not allowed on this server";
    private static final String RESTART_HINT = "restart the game and the launcher";
    private static final long RENEW_BEFORE_MS = 5 * 60_000L;
    // A broken sign-in is reused this long, so live sync can't hammer Mojang or the server.
    // A refusal (not on the allowlist) is kept until the game restarts or the server URL changes.
    private static final long RETRY_AFTER_MS = 60_000L;

    /** Joins the challenge at Mojang and returns the account name to log in with. */
    private final UnaryOperator<String> mojangJoin;
    LongSupplier clock = System::currentTimeMillis;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private Session session;
    private CompletableFuture<String> signIn;
    private URI signInBase;
    private long retryAt;
    private volatile String status = "Not signed in yet";

    KungServerSession(UnaryOperator<String> mojangJoin) {
        this.mojangJoin = mojangJoin;
    }

    public String status() {
        return status;
    }

    /** The session token, from memory only; concurrent callers share one sign-in. */
    public synchronized CompletableFuture<String> token() {
        URI base = DungeonRoomDataSyncClient.endpoint("");
        long now = clock.getAsLong();
        if (session != null && session.base().equals(base) && now < session.renewAt()) {
            return CompletableFuture.completedFuture(session.token());
        }
        if (signIn == null || !base.equals(signInBase) || signIn.isDone() && now >= retryAt) {
            signInBase = base;
            retryAt = now + RETRY_AFTER_MS;
            signIn = CompletableFuture.supplyAsync(() -> signIn(base));
        }
        return signIn;
    }

    /** Drops a token the server refused, unless a newer sign-in already replaced it. */
    public synchronized void invalidate(String rejected) {
        if (session != null && session.token().equals(rejected)) {
            session = null;
            signIn = null;
        }
    }

    /** Sends with the session token. A 401 means the server forgot the session: sign in again once, retry once. */
    public CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request) {
        return token().thenCompose(token -> send(request, token).thenCompose(response -> {
            if (response.statusCode() != 401) {
                return CompletableFuture.completedFuture(response);
            }
            invalidate(token);
            return token().thenCompose(fresh -> send(request, fresh));
        }));
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request, String token) {
        return httpClient.sendAsync(request.copy().setHeader("Authorization", "Bearer " + token).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String signIn(URI base) {
        status = "Signing in...";
        try {
            HttpResponse<String> challengeResponse = post("auth/challenge", "");
            if (challengeResponse.statusCode() != 200) {
                throw new IOException("server returned HTTP " + challengeResponse.statusCode());
            }
            String challenge = JsonParser.parseString(challengeResponse.body()).getAsJsonObject()
                .get("challenge").getAsString();
            String name = mojangJoin.apply(challenge);

            JsonObject login = new JsonObject();
            login.addProperty("name", name);
            login.addProperty("challenge", challenge);
            HttpResponse<String> response = post("auth/login", login.toString());
            switch (response.statusCode()) {
                case 200 -> { }
                case 401 -> throw new IOException("Mojang did not verify " + name);
                case 403 -> throw new IOException(NOT_ALLOWED);
                case 429 -> throw new IOException("too many attempts, try again later");
                default -> throw new IOException("server returned HTTP " + response.statusCode());
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String token = root.get("token").getAsString();
            long renewAt = root.get("expiresAt").getAsLong() - RENEW_BEFORE_MS;
            synchronized (this) {
                session = new Session(base, token, renewAt);
            }
            status = "Signed in as " + (root.has("name") ? root.get("name").getAsString() : name);
            return token;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            boolean refused = NOT_ALLOWED.equals(message);
            status = refused ? message : "Sign-in failed: " + message;
            if (refused) {
                synchronized (this) {
                    if (base.equals(signInBase)) retryAt = Long.MAX_VALUE;
                }
            }
            KungMod.LOGGER.warn("Kung server {}", status);
            throw new IllegalStateException(status);
        }
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(DungeonRoomDataSyncClient.endpoint(path))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Kung-RoomSync")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The same Mojang call vanilla makes when joining an online-mode server. */
    private static String joinMojang(String serverId) {
        Minecraft client = Minecraft.getInstance();
        User user = client.getUser();
        try {
            client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
        } catch (AuthenticationException exception) {
            String message = signInFailure(exception);
            KungMod.LOGGER.warn("Kung server sign-in", exception);
            KungDebugRecorder.event("kung-sign-in", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            KungMessages.send(client, KungMessages.Type.WARNING, "", "Kung server sign-in failed: " + message);
            throw new IllegalStateException(message);
        }
        return user.getName();
    }

    /** Mirrors vanilla's authenticateServer: an expired token needs a restart, any other refusal keeps Mojang's own text. */
    static String signInFailure(Throwable exception) {
        if (exception instanceof AuthenticationUnavailableException) {
            return "Mojang is unreachable";
        }
        if (exception instanceof InvalidCredentialsException) {
            return "Invalid session: " + RESTART_HINT;
        }
        String detail = exception.getMessage();
        return "Can't confirm your Mojang session ("
            + (detail == null ? exception.getClass().getSimpleName() : detail) + "): " + RESTART_HINT;
    }

    /** True for a sign-in error that only restarting the game and the launcher fixes: the local Mojang session is unusable. */
    public static boolean needsRestart(String error) {
        return error != null && error.contains(RESTART_HINT);
    }

    private record Session(URI base, String token, long renewAt) {
    }
}
