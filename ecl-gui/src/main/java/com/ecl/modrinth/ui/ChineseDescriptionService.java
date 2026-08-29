package com.ecl.modrinth.ui;

import com.ecl.util.BoundedCache;
import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Asynchronous Chinese translation for public content summaries with shared session caching.
 * Providers are tried in order: Tencent Transmart (reachable from mainland China),
 * Google Translate, then MyMemory as a final fallback.
 */
public final class ChineseDescriptionService {
    private static final int MAX_SOURCE_LENGTH = 450;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final BoundedCache<String, CompletableFuture<String>> CACHE =
            new BoundedCache<>(MAX_CACHE_ENTRIES, CACHE_TTL);

    private ChineseDescriptionService() {
    }

    public static CompletableFuture<String> translate(String source) {
        String normalized = source == null ? "" : source.strip();
        if (normalized.isBlank() || HAN.matcher(normalized).find()) {
            return CompletableFuture.completedFuture(normalized);
        }
        String shortSource = normalized.length() > MAX_SOURCE_LENGTH
                ? normalized.substring(0, MAX_SOURCE_LENGTH) : normalized;
        CompletableFuture<String> cached = CACHE.get(shortSource);
        if (cached == null) {
            cached = requestTranslation(shortSource);
            CACHE.put(shortSource, cached);
        }
        return cached
                .thenApply(translated -> translated == null || translated.isBlank()
                        ? normalized : translated)
                .exceptionally(error -> normalized);
    }

    private static CompletableFuture<String> requestTranslation(String source) {
        CompletableFuture<String> chain = requestTencent(source)
                .thenCompose(translated -> translated.isBlank()
                        ? requestGoogle(source)
                        : CompletableFuture.completedFuture(translated))
                .thenCompose(translated -> translated.isBlank()
                        ? requestMyMemory(source)
                        : CompletableFuture.completedFuture(translated));
        chain.whenComplete((translated, error) -> {
            if (error != null || translated == null || translated.isBlank()) {
                // Evict so a later request can retry instead of replaying the failure.
                CACHE.remove(source, chain);
            }
        });
        return chain;
    }

    private static CompletableFuture<String> requestTencent(String source) {
        String payload = "{\"header\":{\"fn\":\"auto_translation\",\"session\":\"\","
                + "\"client_key\":\"browser-chromium-Win64-ab-124.0\"},"
                + "\"source\":{\"text_list\":[" + gsonString(source) + "],\"lang\":\"auto\"},"
                + "\"target\":{\"lang\":\"zh\"},\"media\":\"\",\"index\":0,\"model\":0}";
        return HttpUtil.requestAsync("POST", "https://transmart.qq.com/api/imt",
                        "application/json", payload, Map.of(), Duration.ofSeconds(8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP " + response.statusCode());
                    }
                    return parseTencent(response.body());
                });
    }

    private static CompletableFuture<String> requestGoogle(String source) {
        String encoded = URLEncoder.encode(source, StandardCharsets.UTF_8);
        String google = "https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + encoded;
        return get(google).thenApply(ChineseDescriptionService::parseGoogle);
    }

    private static CompletableFuture<String> requestMyMemory(String source) {
        String encoded = URLEncoder.encode(source, StandardCharsets.UTF_8);
        String url = "https://api.mymemory.translated.net/get?q=" + encoded
                + "&langpair=en|zh-CN&de=ecl-launcher@example.com";
        return get(url).thenApply(ChineseDescriptionService::parseMyMemory)
                .exceptionally(error -> "");
    }

    private static CompletableFuture<String> get(String url) {
        return HttpUtil.requestAsync("GET", url, null, null, Map.of(), Duration.ofSeconds(8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }

    static String parseTencent(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject header = root.getAsJsonObject("header");
        JsonElement retCode = header == null ? null : header.get("ret_code");
        if (retCode == null || retCode.isJsonNull()
                || !"succ".equals(retCode.getAsString())) {
            return "";
        }
        JsonArray translations = root.getAsJsonArray("auto_translation");
        if (translations == null || translations.isEmpty() || translations.get(0).isJsonNull()) {
            return "";
        }
        return translations.get(0).getAsString().strip();
    }

    static String parseGoogle(String json) {
        JsonArray root = JsonParser.parseString(json).getAsJsonArray();
        JsonArray segments = root.get(0).getAsJsonArray();
        StringBuilder result = new StringBuilder();
        for (JsonElement element : segments) {
            JsonArray segment = element.getAsJsonArray();
            if (!segment.isEmpty() && !segment.get(0).isJsonNull()) {
                result.append(segment.get(0).getAsString());
            }
        }
        return result.toString().strip();
    }

    static String parseMyMemory(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject responseData = root.getAsJsonObject("responseData");
        if (responseData == null || !responseData.has("translatedText")) return "";
        return responseData.get("translatedText").getAsString().strip();
    }

    /** Minimal JSON string encoder for request payloads built by hand. */
    private static String gsonString(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
