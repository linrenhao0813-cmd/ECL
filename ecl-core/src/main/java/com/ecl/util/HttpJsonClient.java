package com.ecl.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/** High-level text and JSON HTTP operations. */
final class HttpJsonClient {
    private HttpJsonClient() {
    }

    static String get(String url) throws IOException {
        HttpUtil.Response response = HttpRequestExecutor.request(
                "GET", url, null, null, Map.of());
        response.requireSuccess();
        return response.body();
    }

    static String postJson(String url, String body) throws IOException {
        HttpUtil.Response response = HttpRequestExecutor.request(
                "POST", url, "application/json", body, Map.of());
        response.requireSuccess();
        return response.body();
    }

    static String postJsonBytes(String url, byte[] body) throws IOException {
        HttpUtil.Response response = HttpRequestExecutor.postBytes(
                url, "application/json", body);
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
        try {
            return JsonParser.parseString(get(url)).getAsJsonObject();
        } catch (RuntimeException parseFailure) {
            throw new IOException("Invalid JSON response from " + url, parseFailure);
        }
    }
}
