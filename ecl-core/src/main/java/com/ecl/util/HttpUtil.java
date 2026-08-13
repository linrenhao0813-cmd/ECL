package com.ecl.util;

import com.ecl.ECLConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP utility with connection pooling via {@link java.net.http.HttpClient}.
 * All methods are thread-safe; the underlying HttpClient is shared.
 */
public class HttpUtil {
    private static final Gson COMPACT_GSON = GsonProvider.compact();
    private static final Gson PRETTY_GSON = GsonProvider.pretty();

    private static final HttpClient HTTP_CLIENT = createHttpClient();

    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private static HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        proxySelectorFor(System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"),
                System.getenv("ALL_PROXY")).ifPresent(builder::proxy);
        return builder.build();
    }

    static Optional<ProxySelector> proxySelectorFor(String httpsProxy, String httpProxy,
                                                     String allProxy) {
        // Use a String[] instead of List.of(...): List.of rejects null elements, but
        // System.getenv returns null for unset proxy variables, which crashed GUI
        // startup with an ExceptionInInitializerError ("Failed to launch JVM").
        for (String value : new String[]{httpsProxy, httpProxy, allProxy}) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                String normalized = value.contains("://") ? value : "http://" + value;
                URI uri = URI.create(normalized.trim());
                String host = uri.getHost();
                int port = uri.getPort();
                if (host != null && !host.isBlank() && port > 0 && port <= 65535) {
                    return Optional.of(ProxySelector.of(new InetSocketAddress(host, port)));
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next standard proxy variable instead of preventing startup.
            }
        }
        return Optional.empty();
    }

    public static String get(String urlStr) throws IOException {
        Response response = request("GET", urlStr, null, null, Map.of());
        response.requireSuccess();
        return response.body();
    }

    /**
     * Fetch a small binary resource through the same proxy-aware client used by API requests.
     * The size limit is enforced while reading so remote icons cannot consume unbounded memory.
     */
    public static byte[] getBytes(String urlStr, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        checkInterrupted();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofMillis(DEFAULT_READ_TIMEOUT_MS))
                .header("User-Agent", "ECL/1.0")
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.1")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + urlStr);
                }
                long declaredLength = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declaredLength > maxBytes) {
                    throw new IOException("Response exceeds " + maxBytes + " bytes: " + urlStr);
                }
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IOException("Response exceeds " + maxBytes + " bytes: " + urlStr);
                }
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    public static String getWithMirrors(String urlStr, SourceCallback callback) throws IOException {
        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(callback, urlStr, candidate, mirror);
            try {
                Response response = request("GET", candidate, null, null, Map.of(),
                        timeoutFor(mirror), timeoutFor(mirror));
                response.requireSuccess();
                return response.body();
            } catch (IOException e) {
                lastError = e;
                notifyFailure(callback, candidate, e);
            }
        }
        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
    }

    public static String postJson(String urlStr, String body) throws IOException {
        Response response = request("POST", urlStr, "application/json", body, Map.of());
        response.requireSuccess();
        return response.body();
    }

    public static String postJson(String urlStr, JsonObject body) throws IOException {
        return postJson(urlStr, COMPACT_GSON.toJson(body));
    }

    public static Response postForm(String urlStr, Map<String, String> form) throws IOException {
        StringJoiner encoded = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            encoded.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        return request("POST", urlStr, "application/x-www-form-urlencoded", encoded.toString(), Map.of());
    }

    public static Response getBearer(String urlStr, String bearerToken) throws IOException {
        return request("GET", urlStr, null, null, Map.of("Authorization", "Bearer " + bearerToken));
    }

    public static Response postJsonResponse(String urlStr, JsonObject body) throws IOException {
        return request("POST", urlStr, "application/json", COMPACT_GSON.toJson(body), Map.of());
    }

    /** Sends a bounded caller-built multipart body without converting PNG bytes through text. */
    public static Response postMultipart(String urlStr, String boundary, byte[] body,
                                         Map<String, String> headers) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Multipart boundary is blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("Multipart body is null");
        }
        checkInterrupted();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofMillis(DEFAULT_READ_TIMEOUT_MS))
                .header("User-Agent", "ECL/1.0")
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (headers != null) headers.forEach(builder::header);
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body(),
                    response.uri() == null ? urlStr : response.uri().toString(),
                    response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    public static Response request(String method, String urlStr, String contentType, String body,
                                   Map<String, String> headers) throws IOException {
        return request(method, urlStr, contentType, body, headers, 15000, DEFAULT_READ_TIMEOUT_MS);
    }

    private static Response request(String method, String urlStr, String contentType, String body,
                                    Map<String, String> headers, int connectTimeout, int readTimeout) throws IOException {
        checkInterrupted();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofMillis(Math.max(connectTimeout, readTimeout)))
                .header("User-Agent", "ECL/1.0")
                .header("Accept", "application/json");

        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpRequest.BodyPublisher bodyPublisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

        switch (method.toUpperCase()) {
            case "POST" -> builder.POST(bodyPublisher);
            case "PUT" -> builder.PUT(bodyPublisher);
            default -> builder.GET();
        }

        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            String responseBody = readStream(response.body());
            URI resolvedUri = response.uri();
            return new Response(statusCode, responseBody,
                    resolvedUri == null ? urlStr : resolvedUri.toString(),
                    response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    /**
     * Asynchronous transport entry point used by cancellable API clients.
     * Existing synchronous callers continue to use {@link #request(String, String, String, String, Map)}.
     */
    public static CompletableFuture<Response> requestAsync(String method, String urlStr, String contentType,
                                                           String body, Map<String, String> headers,
                                                           Duration requestTimeout) {
        HttpRequest request;
        try {
            checkInterrupted();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(requestTimeout == null ? Duration.ofMillis(DEFAULT_READ_TIMEOUT_MS) : requestTimeout);
            if (!containsHeader(headers, "User-Agent")) {
                builder.header("User-Agent", "ECL/1.0");
            }
            if (!containsHeader(headers, "Accept")) {
                builder.header("Accept", "application/json");
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            if (headers != null) {
                headers.forEach(builder::header);
            }

            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            switch (method == null ? "GET" : method.toUpperCase()) {
                case "POST" -> builder.POST(publisher);
                case "PUT" -> builder.PUT(publisher);
                case "DELETE" -> builder.DELETE();
                default -> builder.GET();
            }
            request = builder.build();
        } catch (RuntimeException | IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<HttpResponse<String>> upstream =
                HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<Response> result = new CompletableFuture<>();
        upstream.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(new IOException("HTTP request failed: " + urlStr, error));
                return;
            }
            result.complete(new Response(
                    response.statusCode(),
                    response.body(),
                    response.uri() == null ? urlStr : response.uri().toString(),
                    response.headers().map()));
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                upstream.cancel(true);
            }
        });
        return result;
    }

    public static void downloadFile(String urlStr, File target) throws IOException {
        downloadFile(urlStr, target, null);
    }

    public static void downloadFile(String urlStr, File target, SourceCallback callback) throws IOException {
        downloadFileInternal(urlStr, target, null, callback, Long.MAX_VALUE);
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback) throws IOException {
        downloadFileWithProgress(urlStr, target, callback, null);
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback, SourceCallback sourceCallback) throws IOException {
        downloadFileInternal(urlStr, target, callback, sourceCallback, Long.MAX_VALUE);
    }

    /** Download while refusing to write more than {@code maxBytes}, including resumed bytes. */
    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback,
                                                SourceCallback sourceCallback, long maxBytes)
            throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Download byte limit must not be negative");
        downloadFileInternal(urlStr, target, callback, sourceCallback, maxBytes);
    }

    public static JsonObject getJson(String urlStr) throws IOException {
        return JsonParser.parseString(get(urlStr)).getAsJsonObject();
    }

    public static JsonObject getJsonWithMirrors(String urlStr, SourceCallback callback) throws IOException {
        return JsonParser.parseString(getWithMirrors(urlStr, callback)).getAsJsonObject();
    }

    public static void writeJson(File file, JsonObject obj) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(PRETTY_GSON.toJson(obj));
        }
    }

    public static JsonObject readJson(File file) throws IOException {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static void downloadFileInternal(String urlStr, File target, ProgressCallback progressCallback,
                                             SourceCallback sourceCallback, long maxBytes) throws IOException {
        ensureParentDirectory(target);
        File partial = new File(target.getAbsolutePath() + ".part");
        File partialMetadataFile = new File(target.getAbsolutePath() + ".part.meta");

        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            checkInterrupted();
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(sourceCallback, urlStr, candidate, mirror);
            try {
                PartialMetadata metadata = readPartialMetadata(partialMetadataFile);
                boolean sameSource = metadata != null && candidate.equals(metadata.source())
                        && !metadata.validator().isBlank();
                if (!sameSource) {
                    Files.deleteIfExists(partial.toPath());
                    Files.deleteIfExists(partialMetadataFile.toPath());
                    metadata = null;
                }
                long existingBytes = sameSource && partial.isFile() ? partial.length() : 0;
                if (existingBytes > maxBytes) {
                    throw new DownloadLimitExceededException("Partial download exceeds byte limit");
                }
                while (true) {
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(candidate))
                            .timeout(Duration.ofMillis(timeoutFor(mirror)))
                            .header("User-Agent", "ECL/1.0")
                            .GET();
                    if (existingBytes > 0) {
                        requestBuilder.header("Range", "bytes=" + existingBytes + "-");
                        String validator = metadata == null ? "" : metadata.validator();
                        if (!validator.isBlank()) requestBuilder.header("If-Range", validator);
                    }

                    HttpResponse<InputStream> response = HTTP_CLIENT.send(requestBuilder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    int statusCode = response.statusCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        String errorBody = readStream(response.body());
                        if (errorBody.isBlank()) {
                            throw new IOException("HTTP " + statusCode + " for " + candidate);
                        }
                        throw new IOException("HTTP " + statusCode + " for " + candidate + ": "
                                + TextUtil.abbreviate(errorBody, 240));
                    }

                    long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    long totalLength;
                    boolean resumed = statusCode == 206 && existingBytes > 0;
                    if (resumed) {
                        totalLength = validatedRangeTotal(
                                response, existingBytes, responseLength, metadata);
                        if (totalLength < 0) {
                            response.body().close();
                            Files.deleteIfExists(partial.toPath());
                            Files.deleteIfExists(partialMetadataFile.toPath());
                            existingBytes = 0;
                            metadata = null;
                            continue;
                        }
                    } else {
                        if (statusCode == 206) {
                            response.body().close();
                            throw new IOException("Unexpected partial response without a resume request: "
                                    + candidate);
                        }
                        existingBytes = 0;
                        totalLength = responseLength;
                    }
                    if (totalLength > maxBytes) {
                        response.body().close();
                        throw new DownloadLimitExceededException(
                                "Download exceeds byte limit: " + totalLength + " > " + maxBytes);
                    }
                    PartialMetadata current = new PartialMetadata(candidate,
                            response.headers().firstValue("ETag").orElse(""),
                            response.headers().firstValue("Last-Modified").orElse(""));
                    writePartialMetadata(partialMetadataFile, current);
                    if (progressCallback != null) progressCallback.onStart(totalLength);
                    try (InputStream input = response.body();
                         OutputStream output = resumed
                                 ? Files.newOutputStream(partial.toPath(), StandardOpenOption.APPEND)
                                 : Files.newOutputStream(partial.toPath(), StandardOpenOption.CREATE,
                                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        copyToFile(input, output, existingBytes, totalLength, progressCallback, maxBytes);
                    }
                    if (totalLength >= 0 && partial.length() != totalLength) {
                        throw new IOException("Downloaded size does not match HTTP response: expected "
                                + totalLength + ", got " + partial.length());
                    }
                    try {
                        Files.move(partial.toPath(), target.toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.deleteIfExists(partialMetadataFile.toPath());
                    if (progressCallback != null) progressCallback.onComplete(target);
                    return;
                }
            } catch (DownloadLimitExceededException e) {
                Files.deleteIfExists(partial.toPath());
                Files.deleteIfExists(partialMetadataFile.toPath());
                notifyFailure(sourceCallback, candidate, e);
                throw e;
            } catch (IOException e) {
                lastError = e;
                notifyFailure(sourceCallback, candidate, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = new IOException("Download interrupted", e);
            }
        }

        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
    }

    private static long validatedRangeTotal(HttpResponse<?> response, long existingBytes,
                                            long responseLength, PartialMetadata metadata) {
        String header = response.headers().firstValue("Content-Range").orElse("");
        Matcher matcher = CONTENT_RANGE.matcher(header);
        if (!matcher.matches()) return -1;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            long rangeLength = Math.addExact(Math.subtractExact(end, start), 1L);
            boolean validatorMatches = metadata != null && metadata.matches(response);
            return start == existingBytes && end >= start && total > end && validatorMatches
                    && (responseLength < 0 || responseLength == rangeLength) ? total : -1;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return -1;
        }
    }

    private static PartialMetadata readPartialMetadata(File file) {
        if (!file.isFile()) return null;
        try {
            JsonObject json = readJson(file);
            return new PartialMetadata(JsonUtil.getString(json, "source", ""),
                    JsonUtil.getString(json, "etag", ""),
                    JsonUtil.getString(json, "lastModified", ""));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writePartialMetadata(File file, PartialMetadata metadata) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("source", metadata.source());
        json.addProperty("etag", metadata.etag());
        json.addProperty("lastModified", metadata.lastModified());
        Path target = file.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Partial metadata has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".part-meta-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                PRETTY_GSON.toJson(json, writer);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record PartialMetadata(String source, String etag, String lastModified) {
        String validator() {
            return etag == null || etag.isBlank() ? lastModified == null ? "" : lastModified : etag;
        }

        boolean matches(HttpResponse<?> response) {
            if (etag != null && !etag.isBlank()) {
                return etag.equals(response.headers().firstValue("ETag").orElse(""));
            }
            return lastModified != null && !lastModified.isBlank()
                    && lastModified.equals(response.headers().firstValue("Last-Modified").orElse(""));
        }
    }

    private static final class DownloadLimitExceededException extends IOException {
        DownloadLimitExceededException(String message) {
            super(message);
        }
    }

    private static int timeoutFor(boolean mirror) {
        return mirror ? ECLConfig.MIRROR_SOURCE_TIMEOUT_MS : ECLConfig.OFFICIAL_SOURCE_TIMEOUT_MS;
    }

    private static void notifySource(SourceCallback callback, String originalUrl, String candidateUrl, boolean mirror) {
        if (callback != null) {
            callback.onSource(originalUrl, candidateUrl, mirror, DownloadSourceUtil.sourceName(candidateUrl));
        }
    }

    private static void notifyFailure(SourceCallback callback, String candidateUrl, IOException error) {
        if (callback != null) {
            callback.onFailure(candidateUrl, error);
        }
    }

    private static void ensureParentDirectory(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Failed to create directory: " + parent);
        }
    }

    /** Read an InputStream fully into a String. Closes the stream. */
    static String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (InputStream in = inputStream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void copyToFile(InputStream input, OutputStream output, long initialBytes, long contentLength,
                                   ProgressCallback progressCallback, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long totalRead = initialBytes;
        long lastReportedAt = 0;
        long lastReportedBytes = initialBytes;
        long reportIntervalNs = 100_000_000L; // 100ms
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkInterrupted();
            if (read > maxBytes - totalRead) {
                throw new DownloadLimitExceededException("Download exceeded byte limit while streaming");
            }
            output.write(buffer, 0, read);
            totalRead += read;
            if (progressCallback != null) {
                long now = System.nanoTime();
                boolean shouldReport = now - lastReportedAt >= reportIntervalNs
                        || totalRead == contentLength
                        || totalRead >= lastReportedBytes * 2;
                if (shouldReport) {
                    progressCallback.onProgress(totalRead, contentLength);
                    lastReportedAt = now;
                    lastReportedBytes = totalRead;
                }
            }
        }
        if (progressCallback != null && totalRead != lastReportedBytes) {
            progressCallback.onProgress(totalRead, contentLength);
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("HTTP operation interrupted");
        }
    }

    private static boolean containsHeader(Map<String, String> headers, String expectedName) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }

    public record Response(int statusCode, String body, String url, Map<String, List<String>> headers) {
        public Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public String firstHeader(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        public void requireSuccess() throws IOException {
            if (!isSuccess()) {
                String suffix = body == null || body.isBlank() ? "" : ": " + TextUtil.abbreviate(body, 240);
                throw new IOException("HTTP " + statusCode + " for " + url + suffix);
            }
        }
    }

    public interface ProgressCallback {
        void onStart(long total);
        void onProgress(long downloaded, long total);
        void onComplete(File file);
    }

    public interface SourceCallback {
        void onSource(String originalUrl, String candidateUrl, boolean mirror, String sourceName);
        void onFailure(String candidateUrl, IOException error);
    }
}
