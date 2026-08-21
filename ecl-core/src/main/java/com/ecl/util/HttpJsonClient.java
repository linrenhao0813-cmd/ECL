package com.ecl.util;

import com.ecl.ECLConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/** High-level text and JSON HTTP operations, including mirror fallback. */
final class HttpJsonClient {
    private HttpJsonClient() {
    }

    static String get(String url) throws IOException {
        HttpUtil.Response response = HttpRequestExecutor.request(
                "GET", url, null, null, Map.of());
        response.requireSuccess();
        return response.body();
    }

    static String getWithMirrors(String url, DownloadSourceCallback callback)
            throws IOException {
        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(url)) {
            boolean mirror = DownloadSourceUtil.isMirror(url, candidate);
            DownloadSourceCallbacks.notifySource(callback, url, candidate, mirror);
            try {
                int timeout = timeoutFor(mirror);
                HttpUtil.Response response = HttpRequestExecutor.request(
                        "GET", candidate, null, null, Map.of(), timeout, timeout);
                response.requireSuccess();
                return response.body();
            } catch (IOException failure) {
                lastError = failure;
                DownloadSourceCallbacks.notifyFailure(callback, candidate, failure);
            }
        }
        throw lastError == null
                ? new IOException("No download source available: " + url)
                : lastError;
    }

    static String postJson(String url, String body) throws IOException {
        HttpUtil.Response response = HttpRequestExecutor.request(
                "POST", url, "application/json", body, Map.of());
        response.requireSuccess();
        return response.body();
    }

    static HttpUtil.Response postForm(String url, Map<String, String> form)
            throws IOException {
        StringJoiner encoded = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            encoded.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                    StandardCharsets.UTF_8));
        }
        return HttpRequestExecutor.request("POST", url,
                "application/x-www-form-urlencoded", encoded.toString(), Map.of());
    }

    static JsonObject getJson(String url) throws IOException {
        return JsonParser.parseString(get(url)).getAsJsonObject();
    }

    static JsonObject getJsonWithMirrors(String url, DownloadSourceCallback callback)
            throws IOException {
        return JsonParser.parseString(getWithMirrors(url, callback)).getAsJsonObject();
    }

    private static int timeoutFor(boolean mirror) {
        return mirror ? ECLConfig.MIRROR_SOURCE_TIMEOUT_MS
                : ECLConfig.OFFICIAL_SOURCE_TIMEOUT_MS;
    }
}
