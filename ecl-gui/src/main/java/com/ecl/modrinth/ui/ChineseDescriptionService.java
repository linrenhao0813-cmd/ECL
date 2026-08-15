package com.ecl.modrinth.ui;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Asynchronous Chinese translation for public content summaries with shared session caching. */
public final class ChineseDescriptionService {
    private static final int MAX_SOURCE_LENGTH = 450;
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Map<String, CompletableFuture<String>> CACHE = new ConcurrentHashMap<>();

    private ChineseDescriptionService() {
    }

    public static CompletableFuture<String> translate(String source) {
        String normalized = source == null ? "" : source.strip();
        if (normalized.isBlank() || HAN.matcher(normalized).find()) {
            return CompletableFuture.completedFuture(normalized);
        }
        String shortSource = normalized.length() > MAX_SOURCE_LENGTH
                ? normalized.substring(0, MAX_SOURCE_LENGTH) : normalized;
        return CACHE.computeIfAbsent(shortSource, ChineseDescriptionService::requestTranslation)
                .exceptionally(error -> normalized);
    }

    private static CompletableFuture<String> requestTranslation(String source) {
        String encoded = URLEncoder.encode(source, StandardCharsets.UTF_8);
        String google = "https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + encoded;
        return get(google).thenApply(ChineseDescriptionService::parseGoogle)
                .thenCompose(translated -> translated.isBlank()
                        ? requestMyMemory(source, encoded)
                        : CompletableFuture.completedFuture(translated))
                .exceptionallyCompose(error -> requestMyMemory(source, encoded));
    }

    private static CompletableFuture<String> requestMyMemory(String source, String encoded) {
        String url = "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=en|zh-CN";
        return get(url).thenApply(ChineseDescriptionService::parseMyMemory)
                .thenApply(value -> value.isBlank() ? source : value)
                .exceptionally(error -> source);
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
}
