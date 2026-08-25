package com.ecl.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Executes synchronous and asynchronous HTTP requests. */
final class HttpRequestExecutor {
    static final int DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private HttpRequestExecutor() {
    }

    static HttpUtil.Response request(String method, String url, String contentType, String body,
                                     Map<String, String> headers) throws IOException {
        return request(method, url, contentType, body, headers,
                HttpClientProvider.DEFAULT_CONNECT_TIMEOUT_MS,
                HttpClientProvider.DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES);
    }

    static HttpUtil.Response request(String method, String url, String contentType, String body,
                                     Map<String, String> headers, int connectTimeout,
                                     int readTimeout) throws IOException {
        return request(method, url, contentType, body, headers,
                connectTimeout, readTimeout, DEFAULT_MAX_RESPONSE_BYTES);
    }

    static HttpUtil.Response request(String method, String url, String contentType, String body,
                                     Map<String, String> headers, int connectTimeout,
                                     int readTimeout, int maxResponseBytes) throws IOException {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        DownloadRateLimiter.checkInterrupted();
        HttpRequest.Builder builder = baseRequest(url, readTimeout, headers);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        applyMethod(builder, method, body);
        try {
            HttpResponse<InputStream> response = HttpClientProvider
                    .forConnectTimeout(connectTimeout)
                    .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            URI resolvedUri = response.uri();
            long declaredLength = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (declaredLength > maxResponseBytes) {
                response.body().close();
                throw new IOException("HTTP response exceeds " + maxResponseBytes + " bytes: " + url);
            }
            return new HttpUtil.Response(response.statusCode(),
                    readStream(response.body(), maxResponseBytes),
                    resolvedUri == null ? url : resolvedUri.toString(),
                    response.headers().map());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", interrupted);
        }
    }

    static byte[] getBytes(String url, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        DownloadRateLimiter.checkInterrupted();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(HttpClientProvider.DEFAULT_READ_TIMEOUT_MS))
                .header("User-Agent", "ECL/1.0")
                .header("Accept",
                        "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.1")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HttpClientProvider.defaultClient().send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                long declaredLength = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declaredLength > maxBytes) {
                    throw new IOException("Response exceeds " + maxBytes + " bytes: " + url);
                }
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IOException("Response exceeds " + maxBytes + " bytes: " + url);
                }
                return bytes;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", interrupted);
        }
    }

    static HttpUtil.Response postMultipart(String url, String boundary, byte[] body,
                                           Map<String, String> headers) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Multipart boundary is blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("Multipart body is null");
        }
        DownloadRateLimiter.checkInterrupted();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(HttpClientProvider.DEFAULT_READ_TIMEOUT_MS))
                .header("User-Agent", "ECL/1.0")
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        try {
            HttpResponse<InputStream> response = HttpClientProvider.defaultClient().send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            return boundedResponse(response, url, DEFAULT_MAX_RESPONSE_BYTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", interrupted);
        }
    }

    static CompletableFuture<HttpUtil.Response> requestAsync(
            String method, String url, String contentType, String body,
            Map<String, String> headers, Duration requestTimeout) {
        return requestAsync(method, url, contentType, body, headers, requestTimeout,
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    static CompletableFuture<HttpUtil.Response> requestAsync(
            String method, String url, String contentType, String body,
            Map<String, String> headers, Duration requestTimeout, int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("maxResponseBytes must be positive"));
        }
        HttpRequest request;
        try {
            DownloadRateLimiter.checkInterrupted();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout == null
                            ? Duration.ofMillis(HttpClientProvider.DEFAULT_READ_TIMEOUT_MS)
                            : requestTimeout);
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
            applyMethod(builder, method, body);
            request = builder.build();
        } catch (RuntimeException | IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<HttpResponse<InputStream>> upstream = HttpClientProvider.defaultClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<HttpUtil.Response> result = new CompletableFuture<>();
        upstream.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(new IOException(
                        "HTTP request failed: " + url, error));
                return;
            }
            try {
                result.complete(boundedResponse(response, url, maxResponseBytes));
            } catch (IOException failure) {
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                upstream.cancel(true);
            }
        });
        return result;
    }

    private static HttpUtil.Response boundedResponse(
            HttpResponse<InputStream> response, String originalUrl, int maxResponseBytes)
            throws IOException {
        long declaredLength = response.headers().firstValueAsLong("Content-Length")
                .orElse(-1L);
        if (declaredLength > maxResponseBytes) {
            response.body().close();
            throw new IOException("HTTP response exceeds " + maxResponseBytes
                    + " bytes: " + originalUrl);
        }
        return new HttpUtil.Response(response.statusCode(),
                readStream(response.body(), maxResponseBytes),
                response.uri() == null ? originalUrl : response.uri().toString(),
                response.headers().map());
    }

    /** Reads and closes a response stream. */
    static String readStream(InputStream inputStream) throws IOException {
        return readStream(inputStream, DEFAULT_MAX_RESPONSE_BYTES);
    }

    static String readStream(InputStream inputStream, int maxBytes) throws IOException {
        if (inputStream == null) {
            return "";
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        try (InputStream input = inputStream) {
            byte[] buffer = new byte[Math.min(64 * 1024, maxBytes + 1)];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(
                    Math.min(maxBytes, 64 * 1024));
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (read > maxBytes - total) {
                    throw new IOException("HTTP response exceeds " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static HttpRequest.Builder baseRequest(String url, int timeout,
                                                   Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeout))
                .header("User-Agent", "ECL/1.0")
                .header("Accept", "application/json");
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder;
    }

    private static void applyMethod(HttpRequest.Builder builder, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        switch (method == null ? "GET" : method.toUpperCase()) {
            case "POST" -> builder.POST(publisher);
            case "PUT" -> builder.PUT(publisher);
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }
    }

    private static boolean containsHeader(Map<String, String> headers, String expectedName) {
        return headers != null && !headers.isEmpty()
                && headers.keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }
}
