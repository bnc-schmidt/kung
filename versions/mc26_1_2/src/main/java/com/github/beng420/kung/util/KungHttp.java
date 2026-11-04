package com.github.beng420.kung.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/** The two shared HTTP clients and the request basics every Kung call repeats. */
public final class KungHttp {
    private KungHttp() { }

    /** Never follows a redirect: the Hypixel API key and the Kung server's session token stay on the host they were meant for. */
    public static HttpClient api() {
        return Api.CLIENT;
    }

    /** Follows redirects, for GitHub downloads and the font fetch. Carries no key or token. */
    public static HttpClient download() {
        return Download.CLIENT;
    }

    public static boolean isOk(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /** A request with the timeout, {@code Accept: application/json} and User-Agent that every Kung API call carries. */
    public static HttpRequest.Builder json(URI uri, int timeoutSeconds, String userAgent) {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("User-Agent", userAgent);
    }

    private static final class Api {
        static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static final class Download {
        static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }
}
