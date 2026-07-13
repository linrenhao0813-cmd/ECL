package com.ecl.util;

import com.ecl.ECLConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.StringJoiner;

public class HttpUtil {
    private static final Gson COMPACT_GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

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
            encoded.add(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + java.net.URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
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
        return request(method, urlStr, contentType, body, headers, 15000, 30000);
    }

    private static Response request(String method, String urlStr, String contentType, String body,
                                    Map<String, String> headers, int connectTimeout, int readTimeout) throws IOException {
        checkInterrupted();
        HttpURLConnection conn = openConnection(urlStr, method, connectTimeout, readTimeout);
        conn.setRequestProperty("Accept", "application/json");
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
        if (headers != null) headers.forEach(conn::setRequestProperty);
        try {
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int statusCode = conn.getResponseCode();
            InputStream stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(statusCode, readStream(stream), conn.getURL().toString());
        } finally {
            conn.disconnect();
        }
    }

    public static void downloadFile(String urlStr, File target) throws IOException {
        downloadFile(urlStr, target, null);
    }

    public static void downloadFile(String urlStr, File target, SourceCallback callback) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            checkInterrupted();
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(callback, urlStr, candidate, mirror);
            HttpURLConnection conn = openConnection(candidate, "GET", timeoutFor(mirror), timeoutFor(mirror));
            try {
                ensureSuccess(conn);
                byte[] buffer = new byte[8192];
                try (InputStream is = conn.getInputStream();
                     OutputStream output = Files.newOutputStream(target.toPath())) {
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        checkInterrupted();
                        output.write(buffer, 0, read);
                    }
                }
                return;
            } catch (IOException e) {
                lastError = e;
                Files.deleteIfExists(target.toPath());
                notifyFailure(callback, candidate, e);
            } finally {
                conn.disconnect();
            }
        }

        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback) throws IOException {
        downloadFileWithProgress(urlStr, target, callback, null);
    }

    public static void downloadFileWithProgress(String urlStr, File target, ProgressCallback callback, SourceCallback sourceCallback) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            checkInterrupted();
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(sourceCallback, urlStr, candidate, mirror);
            HttpURLConnection conn = openConnection(candidate, "GET", timeoutFor(mirror), timeoutFor(mirror));

            try {
                ensureSuccess(conn);
                long contentLength = conn.getContentLengthLong();
                if (callback != null) {
                    callback.onStart(contentLength);
                }

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int read;
                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(target)) {
                    while ((read = is.read(buffer)) != -1) {
                        checkInterrupted();
                        fos.write(buffer, 0, read);
                        totalRead += read;
                        if (callback != null) {
                            callback.onProgress(totalRead, contentLength);
                        }
                    }
                }
                if (callback != null) {
                    callback.onComplete(target);
                }
                return;
            } catch (IOException e) {
                lastError = e;
                Files.deleteIfExists(target.toPath());
                notifyFailure(sourceCallback, candidate, e);
            } finally {
                conn.disconnect();
            }
        }

        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
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

    private static HttpURLConnection openConnection(String urlStr, String method, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "ECL/1.0");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        return conn;
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

    private static void ensureSuccess(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            return;
        }

        String errorBody = readStream(conn.getErrorStream());
        if (errorBody.isBlank()) {
            throw new IOException("HTTP " + code + " for " + conn.getURL());
        }
        throw new IOException("HTTP " + code + " for " + conn.getURL() + ": " + TextUtil.abbreviate(errorBody, 240));
    }

    private static String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                checkInterrupted();
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("HTTP operation interrupted");
        }
    }

    public record Response(int statusCode, String body, String url) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
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
