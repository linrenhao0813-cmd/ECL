package com.ecl.modrinth.api;

import com.ecl.util.HttpUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DefaultModrinthApiClientTest {
    private HttpServer server;
    private ScheduledExecutorService scheduler;
    private URI baseUri;
    private DefaultModrinthApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/");
        client = newClient(3);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        scheduler.shutdownNow();
        server.stop(0);
    }

    @Test
    void searchAddsModVersionAndLoaderFacetsAndIgnoresUnknownFields() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        server.createContext("/v2/search", exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, """
                    {
                      "hits":[{
                        "project_id":"sodium-id",
                        "slug":"sodium",
                        "title":"Sodium",
                        "author":"JellySquid",
                        "description":"Renderer optimization",
                        "downloads":42,
                        "follows":7,
                        "categories":["fabric","optimization"],
                        "versions":["1.21.1"],
                        "date_modified":"2025-01-02T03:04:05Z",
                        "future_field":{"safe":true}
                      }],
                      "offset":0,
                      "limit":20,
                      "total_hits":1,
                      "future_top_level":true
                    }
                    """);
        });

        ModSearchResult result = client.searchMods(new ModSearchQuery(
                "sodium", "1.21.1", "fabric", java.util.Set.of("optimization"),
                ModSearchIndex.DOWNLOADS, 0, 20)).get(3, TimeUnit.SECONDS);

        assertEquals(1, result.totalHits());
        assertEquals("Sodium", result.hits().getFirst().title());
        assertEquals(List.of("fabric"), result.hits().getFirst().loaders());
        assertTrue(query.get().contains("project_type:mod"));
        assertTrue(query.get().contains("versions:1.21.1"));
        assertTrue(query.get().contains("categories:fabric"));
        assertTrue(query.get().contains("index=downloads"));
        assertTrue(userAgent.get().contains("ECL-Test/1.0"));
    }

    @Test
    void coalescesIdenticalGetRequestsWhileTheyAreInFlight() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/v2/project/sodium", exchange -> {
            calls.incrementAndGet();
            requestStarted.countDown();
            try {
                if (!releaseResponse.await(3, TimeUnit.SECONDS)) {
                    respond(exchange, 500, "test timeout");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, "interrupted");
                return;
            }
            respond(exchange, 200, projectJson());
        });

        CompletableFuture<?> first = client.getProject("sodium");
        assertTrue(requestStarted.await(3, TimeUnit.SECONDS));
        CompletableFuture<?> second = client.getProject("sodium");
        releaseResponse.countDown();
        CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);
        client.getProject("sodium").get(3, TimeUnit.SECONDS);

        assertEquals(1, calls.get());
    }

    @Test
    void retriesTransientServerFailuresWithFiniteBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v2/project/retry-project", exchange -> {
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 503, "temporarily unavailable");
            } else {
                respond(exchange, 200, projectJson());
            }
        });

        assertEquals("Sodium", client.getProject("retry-project").get(3, TimeUnit.SECONDS).title());
        assertEquals(3, calls.get());
    }

    @Test
    void honorsRetryAfterForRateLimitedRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v2/project/rate-limited", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "{\"error\":\"rate_limited\"}");
            } else {
                respond(exchange, 200, projectJson());
            }
        });

        assertEquals("Sodium", client.getProject("rate-limited").get(3, TimeUnit.SECONDS).title());
        assertEquals(2, calls.get());
    }

    @Test
    void projectVersionRequestUsesExactMinecraftVersionAndLoader() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/v2/project/project-1/version", exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    [{
                      "id":"version-1",
                      "project_id":"project-1",
                      "name":"Release",
                      "version_number":"1.0",
                      "version_type":"release",
                      "featured":true,
                      "status":"listed",
                      "game_versions":["1.21.1"],
                      "loaders":["neoforge"],
                      "date_published":"2025-01-02T03:04:05Z",
                      "files":[],
                      "dependencies":[]
                    }]
                    """);
        });

        var versions = client.getProjectVersions("project-1", "1.21.1", "neoforge")
                .get(3, TimeUnit.SECONDS);

        assertEquals(1, versions.size());
        assertEquals(List.of("neoforge"), versions.getFirst().loaders());
        assertTrue(query.get().contains("game_versions=[\"1.21.1\"]"));
        assertTrue(query.get().contains("loaders=[\"neoforge\"]"));
    }

    @Test
    void doesNotRetryNotFoundResponses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v2/project/missing", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 404, "{\"error\":\"not_found\"}");
        });

        try {
            client.getProject("missing").join();
            fail("Expected a missing project error");
        } catch (Exception error) {
            assertInstanceOf(ModNotFoundException.class, unwrap(error));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void postsHashLookupWithoutCallingTheRealApi() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/v2/version_files", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "abc":{
                        "id":"version-1",
                        "project_id":"project-1",
                        "name":"Release",
                        "version_number":"1.0",
                        "version_type":"release",
                        "featured":true,
                        "status":"listed",
                        "game_versions":["1.21.1"],
                        "loaders":["fabric"],
                        "date_published":"2025-01-02T03:04:05Z",
                        "files":[],
                        "dependencies":[]
                      }
                    }
                    """);
        });

        Map<String, ?> result = client.getVersionsFromHashes(List.of("ABC"), "sha1")
                .get(3, TimeUnit.SECONDS);

        assertTrue(result.containsKey("abc"));
        assertTrue(requestBody.get().contains("\"algorithm\":\"sha1\""));
        assertTrue(requestBody.get().contains("\"abc\""));
    }

    private DefaultModrinthApiClient newClient(int maxAttempts) {
        ModrinthApiConfiguration configuration = new ModrinthApiConfiguration(
                baseUri, "ECL-Test/1.0 (test suite)", Duration.ofSeconds(3),
                Duration.ofSeconds(30), maxAttempts, Duration.ofMillis(1));
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ModrinthHttpTransport transport = (method, uri, body, headers, timeout) ->
                HttpUtil.requestAsync(method, uri.toString(), body == null ? null : "application/json",
                        body, headers, timeout);
        return new DefaultModrinthApiClient(configuration, mapper, transport, scheduler);
    }

    private static String projectJson() {
        return """
                {
                  "id":"sodium-id",
                  "slug":"sodium",
                  "title":"Sodium",
                  "description":"Renderer optimization",
                  "downloads":42,
                  "followers":7,
                  "categories":["fabric"],
                  "updated":"2025-01-02T03:04:05Z",
                  "license":{"id":"LGPL-3.0-only","name":"LGPL-3.0"}
                }
                """;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
