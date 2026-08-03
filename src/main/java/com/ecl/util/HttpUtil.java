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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP utility with connection pooling via {@link java.net.http.HttpClient}.
 * All methods are thread-safe; the underlying HttpClient is shared.
 */
public class HttpUtil {
    private static final Gson COMPACT_GSON = GsonProvider.compact();
    private static final Gson PRETTY_GSON = GsonProvider.pretty();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    public static String get(String urlStr) throws IOException {
        Response response = request("GET", urlStr, null, null, Map.of());
        response.requireSuccess();
        return response.body();
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
        downloadFileInternal(urlStr, target, null, callback);
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback) throws IOException {
        downloadFileWithProgress(urlStr, target, callback, null);
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback, SourceCallback sourceCallback) throws IOException {
        downloadFileInternal(urlStr, target, callback, sourceCallback);
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
                                             SourceCallback sourceCallback) throws IOException {
        ensureParentDirectory(target);

        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            checkInterrupted();
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(sourceCallback, urlStr, candidate, mirror);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(candidate))
                        .timeout(Duration.ofMillis(timeoutFor(mirror)))
                        .header("User-Agent", "ECL/1.0")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
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

                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (progressCallback != null) {
                    progressCallback.onStart(contentLength);
                }
                try (InputStream input = response.body();
                     OutputStream output = Files.newOutputStream(target.toPath())) {
                    copyToFile(input, output, contentLength, progressCallback);
                }
                if (progressCallback != null) {
                    progressCallback.onComplete(target);
                }
                return;
            } catch (IOException e) {
                lastError = e;
                Files.deleteIfExists(target.toPath());
                notifyFailure(sourceCallback, candidate, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = new IOException("Download interrupted", e);
                Files.deleteIfExists(target.toPath());
            }
        }

        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
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

    private static void copyToFile(InputStream input, OutputStream output, long contentLength,
                                   ProgressCallback progressCallback) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long totalRead = 0;
        long lastReportedAt = 0;
        long lastReportedBytes = 0;
        long reportIntervalNs = 100_000_000L; // 100ms
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkInterrupted();
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
