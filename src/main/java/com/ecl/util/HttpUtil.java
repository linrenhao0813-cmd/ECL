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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class HttpUtil {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String get(String urlStr) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, "GET", 15000, 15000);
        try {
            ensureSuccess(conn);
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    public static String getWithMirrors(String urlStr, SourceCallback callback) throws IOException {
        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(urlStr)) {
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(callback, urlStr, candidate, mirror);
            try {
                HttpURLConnection conn = openConnection(candidate, "GET", timeoutFor(mirror), timeoutFor(mirror));
                try {
                    ensureSuccess(conn);
                    return readStream(conn.getInputStream());
                } finally {
                    conn.disconnect();
                }
            } catch (IOException e) {
                lastError = e;
                notifyFailure(callback, candidate, e);
            }
        }
        throw lastError == null ? new IOException("No download source available: " + urlStr) : lastError;
    }

    public static String postJson(String urlStr, String body) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, "POST", 15000, 15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        try {
            ensureSuccess(conn);
            return readStream(conn.getInputStream());
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
            boolean mirror = DownloadSourceUtil.isMirror(urlStr, candidate);
            notifySource(callback, urlStr, candidate, mirror);
            HttpURLConnection conn = openConnection(candidate, "GET", timeoutFor(mirror), timeoutFor(mirror));
            try {
                ensureSuccess(conn);
                try (InputStream is = conn.getInputStream()) {
                    Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                lastError = e;
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
            writer.write(GSON.toJson(obj));
        }
    }

    public static JsonObject readJson(File file) throws IOException {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static HttpURLConnection openConnection(String urlStr, String method, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "ECL/1.0");
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
        throw new IOException("HTTP " + code + " for " + conn.getURL() + ": " + abbreviate(errorBody));
    }

    private static String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 240 ? text.substring(0, 237) + "..." : text;
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
