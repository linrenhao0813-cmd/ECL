package com.ecl.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Compatibility facade for ECL HTTP operations.
 * Concrete request, JSON and download features live in dedicated classes.
 */
public class HttpUtil {
    private static final Gson COMPACT_GSON = GsonProvider.compact();

    private HttpUtil() {
    }

    static HttpClient httpClientForConnectTimeout(int connectTimeoutMs) {
        return HttpClientProvider.forConnectTimeout(connectTimeoutMs);
    }

    static Optional<ProxySelector> proxySelectorFor(String httpsProxy, String httpProxy,
                                                     String allProxy) {
        return HttpClientProvider.proxySelectorFor(httpsProxy, httpProxy, allProxy);
    }

    /** Configure a process-wide download limit. Zero disables throttling. */
    public static void setDownloadRateLimitBytesPerSecond(long bytesPerSecond) {
        DownloadRateLimiter.setDefaultBytesPerSecond(bytesPerSecond);
    }

    public static long getDownloadRateLimitBytesPerSecond() {
        return DownloadRateLimiter.getDefaultBytesPerSecond();
    }

    /** Configure the process-wide number of concurrent binary transfers. */
    public static void setDownloadMaxConcurrent(int maxConcurrent) {
        DownloadConcurrencyGate.setLimit(maxConcurrent);
    }

    public static int getDownloadMaxConcurrent() {
        return DownloadConcurrencyGate.limit();
    }

    public static String get(String urlStr) throws IOException {
        return HttpJsonClient.get(urlStr);
    }

    public static byte[] getBytes(String urlStr, int maxBytes) throws IOException {
        return HttpRequestExecutor.getBytes(urlStr, maxBytes);
    }

    public static String getWithMirrors(String urlStr, SourceCallback callback)
            throws IOException {
        return HttpJsonClient.getWithMirrors(urlStr, adapt(callback));
    }

    public static String postJson(String urlStr, String body) throws IOException {
        return HttpJsonClient.postJson(urlStr, body);
    }

    public static String postJson(String urlStr, JsonObject body) throws IOException {
        return postJson(urlStr, COMPACT_GSON.toJson(body));
    }

    public static Response postForm(String urlStr, Map<String, String> form)
            throws IOException {
        return HttpJsonClient.postForm(urlStr, form);
    }

    public static Response getBearer(String urlStr, String bearerToken) throws IOException {
        return request("GET", urlStr, null, null,
                Map.of("Authorization", "Bearer " + bearerToken));
    }

    public static Response postJsonResponse(String urlStr, JsonObject body)
            throws IOException {
        return request("POST", urlStr, "application/json",
                COMPACT_GSON.toJson(body), Map.of());
    }

    public static Response postMultipart(String urlStr, String boundary, byte[] body,
                                         Map<String, String> headers) throws IOException {
        return HttpRequestExecutor.postMultipart(urlStr, boundary, body, headers);
    }

    public static Response request(String method, String urlStr, String contentType, String body,
                                   Map<String, String> headers) throws IOException {
        return HttpRequestExecutor.request(method, urlStr, contentType, body, headers);
    }

    public static CompletableFuture<Response> requestAsync(
            String method, String urlStr, String contentType, String body,
            Map<String, String> headers, Duration requestTimeout) {
        return HttpRequestExecutor.requestAsync(
                method, urlStr, contentType, body, headers, requestTimeout);
    }

    public static void downloadFile(String urlStr, File target) throws IOException {
        downloadFile(urlStr, target, null);
    }

    public static void downloadFile(String urlStr, File target, SourceCallback callback)
            throws IOException {
        try (DownloadConcurrencyGate.Permit ignored = DownloadConcurrencyGate.acquire()) {
            ResumableFileDownloader.download(
                    urlStr, target, null, adapt(callback), Long.MAX_VALUE);
        }
    }

    public static void downloadFileWithProgress(
            String urlStr, File target, ProgressCallback callback) throws IOException {
        downloadFileWithProgress(urlStr, target, callback, null);
    }

    public static void downloadFileWithProgress(
            String urlStr, File target, ProgressCallback callback,
            SourceCallback sourceCallback) throws IOException {
        try (DownloadConcurrencyGate.Permit ignored = DownloadConcurrencyGate.acquire()) {
            ResumableFileDownloader.download(urlStr, target, adapt(callback),
                    adapt(sourceCallback), Long.MAX_VALUE);
        }
    }

    /** Download while refusing to write more than {@code maxBytes}, including resumed bytes. */
    public static void downloadFileWithProgress(
            String urlStr, File target, ProgressCallback callback,
            SourceCallback sourceCallback, long maxBytes) throws IOException {
        try (DownloadConcurrencyGate.Permit ignored = DownloadConcurrencyGate.acquire()) {
            ResumableFileDownloader.download(urlStr, target, adapt(callback),
                    adapt(sourceCallback), maxBytes);
        }
    }

    public static JsonObject getJson(String urlStr) throws IOException {
        return HttpJsonClient.getJson(urlStr);
    }

    public static JsonObject getJsonWithMirrors(String urlStr, SourceCallback callback)
            throws IOException {
        return HttpJsonClient.getJsonWithMirrors(urlStr, adapt(callback));
    }

    public static void writeJson(File file, JsonObject obj) throws IOException {
        JsonFileStore.write(file, obj);
    }

    public static JsonObject readJson(File file) throws IOException {
        return JsonFileStore.read(file);
    }

    static String readStream(InputStream inputStream) throws IOException {
        return HttpRequestExecutor.readStream(inputStream);
    }

    private static DownloadProgressCallback adapt(ProgressCallback callback) {
        if (callback == null) {
            return null;
        }
        return new DownloadProgressCallback() {
            @Override
            public void onStart(long total) {
                callback.onStart(total);
            }

            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(downloaded, total);
            }

            @Override
            public void onComplete(File file) {
                callback.onComplete(file);
            }
        };
    }

    private static DownloadSourceCallback adapt(SourceCallback callback) {
        if (callback == null) {
            return null;
        }
        return new DownloadSourceCallback() {
            @Override
            public void onSource(String originalUrl, String candidateUrl, boolean mirror,
                                 String sourceName) {
                callback.onSource(originalUrl, candidateUrl, mirror, sourceName);
            }

            @Override
            public void onFailure(String candidateUrl, IOException error) {
                callback.onFailure(candidateUrl, error);
            }
        };
    }

    public record Response(int statusCode, String body, String url,
                           Map<String, List<String>> headers) {
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
                String suffix = body == null || body.isBlank()
                        ? "" : ": " + TextUtil.abbreviate(body, 240);
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
