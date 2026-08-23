package com.ecl.modrinth.api;

import com.ecl.modrinth.api.dto.ModProjectDto;
import com.ecl.modrinth.api.dto.ModSearchResponseDto;
import com.ecl.modrinth.api.dto.ModVersionDto;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.util.BoundedCache;
import com.ecl.util.HttpUtil;
import com.ecl.util.TextUtil;
import com.ecl.util.ThreadFactories;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class DefaultModrinthApiClient implements ModrinthApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultModrinthApiClient.class);
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);
    private static final Set<String> LOADER_CATEGORIES = Set.of("fabric", "quilt", "forge", "neoforge");
    private static final int MAX_CACHE_ENTRIES = 256;

    private final ModrinthApiConfiguration configuration;
    private final ObjectMapper objectMapper;
    private final ModrinthHttpTransport transport;
    private final ModrinthResponseMapper responseMapper = new ModrinthResponseMapper();
    private final ScheduledExecutorService retryScheduler;
    private final boolean ownsScheduler;
    private final BoundedCache<String, CacheEntry> cache = new BoundedCache<>(MAX_CACHE_ENTRIES);
    private final Map<String, CompletableFuture<HttpUtil.Response>> inFlightGets = new ConcurrentHashMap<>();

    public DefaultModrinthApiClient() {
        this(ModrinthApiConfiguration.defaults(), defaultMapper(),
                (method, uri, body, headers, timeout) -> HttpUtil.requestAsync(
                        method, uri.toString(), body == null ? null : "application/json",
                        body, headers, timeout),
                newRetryScheduler(), true);
    }

    DefaultModrinthApiClient(ModrinthApiConfiguration configuration, ObjectMapper objectMapper,
                             ModrinthHttpTransport transport, ScheduledExecutorService retryScheduler) {
        this(configuration, objectMapper, transport, retryScheduler, false);
    }

    private DefaultModrinthApiClient(ModrinthApiConfiguration configuration, ObjectMapper objectMapper,
                                     ModrinthHttpTransport transport, ScheduledExecutorService retryScheduler,
                                     boolean ownsScheduler) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        this.ownsScheduler = ownsScheduler;
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static ScheduledExecutorService newRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.daemon("ecl-modrinth-retry"));
    }

    @Override
    public CompletableFuture<ModSearchResult> searchMods(ModSearchQuery query) {
        Objects.requireNonNull(query, "query");
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + query.projectType().toLowerCase(Locale.ROOT)));
        facets.add(List.of("versions:" + query.minecraftVersion()));
        if (!query.loader().isBlank()) {
            facets.add(List.of("categories:" + query.loader().toLowerCase(Locale.ROOT)));
        }
        query.categories().stream()
                .filter(category -> category != null && !category.isBlank())
                .sorted()
                .forEach(category -> facets.add(List.of("categories:" + category.trim())));

        Map<String, String> parameters = new LinkedHashMap<>();
        if (!query.keyword().isBlank()) {
            parameters.put("query", query.keyword());
        }
        parameters.put("facets", writeJson(facets));
        parameters.put("index", query.index().apiValue());
        parameters.put("offset", Integer.toString(query.offset()));
        parameters.put("limit", Integer.toString(query.limit()));
        URI uri = endpoint("search", parameters);

        return get(uri).thenApply(response -> {
            requireSuccess(response);
            ModSearchResponseDto dto = readJson(response.body(), ModSearchResponseDto.class);
            List<ModProject> projects = responseMapper.projects(dto.hits());
            return new ModSearchResult(projects, dto.offset(), dto.limit(), dto.totalHits());
        });
    }

    @Override
    public CompletableFuture<ModProject> getProject(String projectIdOrSlug) {
        String id = requireText(projectIdOrSlug, "projectIdOrSlug");
        URI uri = endpoint("project/" + encodePathSegment(id), Map.of());
        return get(uri).thenApply(response -> {
            requireSuccess(response);
            return responseMapper.project(readJson(response.body(), ModProjectDto.class));
        });
    }

    @Override
    public CompletableFuture<ModVersion> getVersion(String versionId) {
        String id = requireText(versionId, "versionId");
        URI uri = endpoint("version/" + encodePathSegment(id), Map.of());
        return get(uri).thenApply(response -> {
            requireSuccess(response);
            return responseMapper.version(readJson(response.body(), ModVersionDto.class));
        });
    }

    @Override
    public CompletableFuture<List<ModVersion>> getProjectVersions(String projectId, String minecraftVersion,
                                                                  String loader) {
        String id = requireText(projectId, "projectId");
        String gameVersion = requireText(minecraftVersion, "minecraftVersion");
        String loaderName = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("game_versions", writeJson(List.of(gameVersion)));
        if (!loaderName.isBlank()) {
            parameters.put("loaders", writeJson(List.of(loaderName)));
        }
        URI uri = endpoint("project/" + encodePathSegment(id) + "/version", parameters);
        return get(uri).thenApply(response -> {
            requireSuccess(response);
            List<ModVersionDto> versions = readJson(response.body(), new TypeReference<>() {});
            return responseMapper.versions(versions);
        });
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(Collection<String> hashes,
                                                                            String algorithm) {
        List<String> normalizedHashes = normalizeHashes(hashes);
        String normalizedAlgorithm = normalizeAlgorithm(algorithm);
        String body = writeJson(Map.of("hashes", normalizedHashes, "algorithm", normalizedAlgorithm));
        return post(endpoint("version_files", Map.of()), body).thenApply(response -> {
            requireSuccess(response);
            return responseMapper.versionsByHash(
                    readJson(response.body(), new TypeReference<Map<String, ModVersionDto>>() {}));
        });
    }

    @Override
    public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
            Collection<String> hashes, String algorithm, List<String> loaders, List<String> gameVersions) {
        List<String> normalizedHashes = normalizeHashes(hashes);
        String normalizedAlgorithm = normalizeAlgorithm(algorithm);
        List<String> normalizedLoaders = normalizeTexts(loaders, "loaders");
        List<String> normalizedGameVersions = normalizeTexts(gameVersions, "gameVersions");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hashes", normalizedHashes);
        request.put("algorithm", normalizedAlgorithm);
        request.put("loaders", normalizedLoaders);
        request.put("game_versions", normalizedGameVersions);
        String body = writeJson(request);
        return post(endpoint("version_files/update", Map.of()), body).thenApply(response -> {
            requireSuccess(response);
            return responseMapper.versionsByHash(
                    readJson(response.body(), new TypeReference<Map<String, ModVersionDto>>() {}));
        });
    }

    private CompletableFuture<HttpUtil.Response> get(URI uri) {
        String key = uri.toString();
        CacheEntry cached = cache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return CompletableFuture.completedFuture(cached.response());
        }
        if (cached != null) {
            cache.remove(key, cached);
        }

        CompletableFuture<HttpUtil.Response> shared = inFlightGets.computeIfAbsent(key, ignored -> {
            CompletableFuture<HttpUtil.Response> request = sendWithRetry("GET", uri, null, 1);
            request.whenComplete((response, error) -> {
                inFlightGets.remove(key, request);
                if (error == null && response != null && response.isSuccess()) {
                    cache.put(key, new CacheEntry(response, Instant.now().plus(configuration.cacheTtl())));
                }
            });
            return request;
        });
        return shared;
    }

    private CompletableFuture<HttpUtil.Response> post(URI uri, String body) {
        return sendWithRetry("POST", uri, body, 1);
    }

    private CompletableFuture<HttpUtil.Response> sendWithRetry(String method, URI uri, String body, int attempt) {
        long startedAt = System.nanoTime();
        Map<String, String> headers = Map.of(
                "User-Agent", configuration.userAgent(),
                "Accept", "application/json"
        );
        return transport.send(method, uri, body, headers, configuration.requestTimeout())
                .handle((response, error) -> {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        LOGGER.warn("Modrinth {} {} failed after {} ms (attempt {}/{})",
                                method, uri.getPath(), elapsedMs, attempt, configuration.maxAttempts(), cause);
                        if (attempt < configuration.maxAttempts()) {
                            return retryAfter(method, uri, body, attempt, retryDelay(attempt, null));
                        }
                        return CompletableFuture.<HttpUtil.Response>failedFuture(
                                new ModrinthApiException("Modrinth network request failed: " + uri.getPath(),
                                        -1, true, cause));
                    }

                    LOGGER.debug("Modrinth {} {} -> {} in {} ms (attempt {}/{})",
                            method, uri.getPath(), response.statusCode(), elapsedMs,
                            attempt, configuration.maxAttempts());
                    LOGGER.debug("Modrinth rate limit remaining={}, reset={}",
                            response.firstHeader("X-Ratelimit-Remaining"),
                            response.firstHeader("X-Ratelimit-Reset"));
                    if (RETRYABLE_STATUS_CODES.contains(response.statusCode())
                            && attempt < configuration.maxAttempts()) {
                        return retryAfter(method, uri, body, attempt, retryDelay(attempt, response));
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<HttpUtil.Response> retryAfter(String method, URI uri, String body, int attempt,
                                                            Duration delay) {
        CompletableFuture<HttpUtil.Response> result = new CompletableFuture<>();
        ScheduledFuture<?> scheduled = retryScheduler.schedule(() ->
                        sendWithRetry(method, uri, body, attempt + 1)
                                .whenComplete((response, error) -> completeFrom(result, response, error)),
                Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                scheduled.cancel(true);
            }
        });
        return result;
    }

    private Duration retryDelay(int attempt, HttpUtil.Response response) {
        if (response != null && response.statusCode() == 429) {
            String retryAfter = response.firstHeader("Retry-After");
            if (retryAfter != null) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    if (seconds >= 0 && seconds <= 300) {
                        return Duration.ofSeconds(seconds);
                    }
                } catch (NumberFormatException ignored) {
                    LOGGER.debug("Invalid Modrinth Retry-After header: {}", retryAfter);
                }
            }
        }
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        return configuration.initialRetryDelay().multipliedBy(multiplier);
    }

    private void requireSuccess(HttpUtil.Response response) {
        if (response.isSuccess()) {
            return;
        }
        String detail = response.body() == null || response.body().isBlank()
                ? ""
                : ": " + TextUtil.abbreviate(response.body(), 240);
        if (response.statusCode() == 404) {
            throw new ModNotFoundException("Modrinth resource was not found" + detail);
        }
        if (response.statusCode() == 429) {
            throw new RateLimitException("Modrinth request rate limit exceeded" + detail);
        }
        boolean retryable = RETRYABLE_STATUS_CODES.contains(response.statusCode());
        throw new ModrinthApiException("Modrinth HTTP " + response.statusCode() + detail,
                response.statusCode(), retryable);
    }

    private URI endpoint(String relativePath, Map<String, String> query) {
        StringBuilder value = new StringBuilder(relativePath);
        if (query != null && !query.isEmpty()) {
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                value.append(encodeQuery(entry.getKey())).append('=').append(encodeQuery(entry.getValue()));
                first = false;
            }
        }
        return configuration.baseUri().resolve(value.toString());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ModrinthApiException("Failed to serialize Modrinth request", -1, false, e);
        }
    }

    private <T> T readJson(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw invalidJsonResponse(body, e);
        }
    }

    private <T> T readJson(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw invalidJsonResponse(body, e);
        }
    }

    private static ModrinthApiException invalidJsonResponse(String body, JsonProcessingException cause) {
        return new ModrinthApiException("Invalid Modrinth JSON response", 200, false, cause);
    }

    private static List<String> normalizeHashes(Collection<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            throw new IllegalArgumentException("hashes must not be empty");
        }
        return hashes.stream()
                .map(hash -> requireText(hash, "hash"))
                .map(hash -> hash.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String normalizeAlgorithm(String algorithm) {
        String normalized = requireText(algorithm, "algorithm").toLowerCase(Locale.ROOT);
        if (!normalized.equals("sha1") && !normalized.equals("sha512")) {
            throw new IllegalArgumentException("algorithm must be sha1 or sha512");
        }
        return normalized;
    }

    private static List<String> normalizeTexts(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return values.stream().map(value -> requireText(value, name + " entry")).distinct().toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI parseUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : nullToEmpty(second);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> void completeFrom(CompletableFuture<T> target, T value, Throwable error) {
        if (error == null) {
            target.complete(value);
        } else {
            target.completeExceptionally(unwrap(error));
        }
    }

    @Override
    public void close() {
        cache.clear();
        inFlightGets.values().forEach(future -> future.cancel(true));
        inFlightGets.clear();
        if (ownsScheduler) {
            retryScheduler.shutdownNow();
        }
    }

    private record CacheEntry(HttpUtil.Response response, Instant expiresAt) {
    }
}
