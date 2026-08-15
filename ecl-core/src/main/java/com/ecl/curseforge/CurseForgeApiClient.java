package com.ecl.curseforge;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.modrinth.api.ModSearchIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Small, proxy-aware client for the official CurseForge v1 REST API. */
public final class CurseForgeApiClient {
    public static final int MINECRAFT_GAME_ID = 432;
    private static final String DEFAULT_API_BASE = "https://api.curseforge.com/v1";
    private final Supplier<String> apiKeySupplier;
    private final String apiBase;

    public CurseForgeApiClient(Supplier<String> apiKeySupplier) {
        this(apiKeySupplier, DEFAULT_API_BASE);
    }

    CurseForgeApiClient(Supplier<String> apiKeySupplier, String apiBase) {
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase").replaceAll("/+$", "");
    }

    public boolean isConfigured() {
        String key = apiKeySupplier.get();
        return key != null && !key.isBlank();
    }

    public ApiSearchResult search(String query, String gameVersion, String projectType,
                                  String loader, int offset, int limit, ModSearchIndex sort)
            throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("gameId", Integer.toString(MINECRAFT_GAME_ID));
        parameters.put("classId", Integer.toString(classId(projectType)));
        if (gameVersion != null && !gameVersion.isBlank()) parameters.put("gameVersion", gameVersion);
        if (query != null && !query.isBlank()) parameters.put("searchFilter", query.trim());
        Integer loaderType = loaderType(loader);
        if (loaderType != null && loaderType > 0 && gameVersion != null && !gameVersion.isBlank()) {
            parameters.put("modLoaderType", loaderType.toString());
        }
        parameters.put("sortField", Integer.toString(sortField(sort)));
        parameters.put("sortOrder", "desc");
        parameters.put("index", Integer.toString(Math.max(0, offset)));
        parameters.put("pageSize", Integer.toString(Math.max(1, Math.min(50, limit))));
        JsonObject response = get("/mods/search", parameters);
        List<ApiProject> projects = new ArrayList<>();
        for (JsonElement element : array(response, "data")) {
            if (element.isJsonObject()) projects.add(project(element.getAsJsonObject(), projectType));
        }
        JsonObject pagination = response.has("pagination") && response.get("pagination").isJsonObject()
                ? response.getAsJsonObject("pagination") : new JsonObject();
        int totalCount = paginationTotal(pagination, projects.size());
        return new ApiSearchResult(List.copyOf(projects), totalCount);
    }

    public ApiSearchResult search(String query, String gameVersion, String projectType,
                                  String loader, int offset, int limit, boolean popular)
            throws IOException {
        return search(query, gameVersion, projectType, loader, offset, limit,
                popular ? ModSearchIndex.DOWNLOADS : ModSearchIndex.RELEVANCE);
    }

    public ApiProject getProject(String projectId) throws IOException {
        JsonObject response = get("/mods/" + numericId(projectId, "projectId"), Map.of());
        JsonObject data = object(response, "data");
        return project(data, projectTypeForClass(JsonUtil.getInt(data, "classId", 0)));
    }

    public List<ApiFile> getFiles(String projectId, String gameVersion, String loader) throws IOException {
        long numericProjectId = numericId(projectId, "projectId");
        List<ApiFile> files = new ArrayList<>();
        int index = 0;
        while (index < 10_000) {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (gameVersion != null && !gameVersion.isBlank()) {
                parameters.put("gameVersion", gameVersion);
            }
            Integer type = loaderType(loader);
            if (type != null && type > 0) parameters.put("modLoaderType", type.toString());
            parameters.put("index", Integer.toString(index));
            parameters.put("pageSize", "50");
            JsonObject response = get("/mods/" + numericProjectId + "/files", parameters);
            JsonArray page = array(response, "data");
            for (JsonElement element : page) {
                if (element.isJsonObject()) files.add(file(element.getAsJsonObject()));
            }
            JsonObject pagination = response.has("pagination")
                    && response.get("pagination").isJsonObject()
                    ? response.getAsJsonObject("pagination") : new JsonObject();
            int totalCount = JsonUtil.getInt(pagination, "totalCount", 0);
            if (page.isEmpty() || page.size() < 50
                    || (totalCount > 0 && files.size() >= totalCount)) {
                break;
            }
            index += page.size();
        }
        return List.copyOf(files);
    }

    public ApiFile getFile(String projectId, String fileId) throws IOException {
        JsonObject response = get("/mods/" + numericId(projectId, "projectId")
                + "/files/" + numericId(fileId, "fileId"), Map.of());
        return file(object(response, "data"));
    }

    public String getDownloadUrl(ApiFile file) throws IOException {
        if (file.downloadUrl() != null && !file.downloadUrl().isBlank()) return file.downloadUrl();
        return getDownloadUrl(file.projectId(), file.id());
    }

    public String getDownloadUrl(String projectId, String fileId) throws IOException {
        JsonObject response = get("/mods/" + numericId(projectId, "projectId") + "/files/"
                + numericId(fileId, "fileId")
                + "/download-url", Map.of());
        String url = JsonUtil.getString(response, "data", "");
        if (url.isBlank()) {
            throw new IOException("CurseForge 文件不允许第三方分发，无法取得下载地址: " + fileId);
        }
        return url;
    }

    public Map<Long, ApiFile> matchFingerprints(Collection<Long> fingerprints) throws IOException {
        if (fingerprints == null || fingerprints.isEmpty()) {
            return Map.of();
        }
        JsonArray values = new JsonArray();
        fingerprints.stream().filter(Objects::nonNull).distinct().forEach(values::add);
        if (values.isEmpty()) {
            return Map.of();
        }
        JsonObject body = new JsonObject();
        body.add("fingerprints", values);
        JsonObject response = request("POST", "/fingerprints/" + MINECRAFT_GAME_ID,
                Map.of(), body.toString());
        JsonObject data = object(response, "data");
        JsonArray exactFingerprints = array(data, "exactFingerprints");
        Map<Long, ApiFile> result = new LinkedHashMap<>();
        JsonArray matches = array(data, "exactMatches");
        for (int index = 0; index < matches.size(); index++) {
            JsonElement element = matches.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject match = element.getAsJsonObject();
            if (!match.has("file") || !match.get("file").isJsonObject()) continue;
            ApiFile matchedFile = file(match.getAsJsonObject("file"));
            long fingerprint = matchedFile.fingerprint();
            if (fingerprint == 0 && index < exactFingerprints.size()
                    && exactFingerprints.get(index).isJsonPrimitive()) {
                fingerprint = exactFingerprints.get(index).getAsLong();
            }
            if (fingerprint != 0) {
                result.put(fingerprint, matchedFile);
            }
        }
        return Map.copyOf(result);
    }

    private JsonObject get(String path, Map<String, String> parameters) throws IOException {
        return request("GET", path, parameters, null);
    }

    private JsonObject request(String method, String path, Map<String, String> parameters,
                               String body) throws IOException {
        String key = apiKeySupplier.get();
        if (key == null || key.isBlank()) {
            throw new IOException("CurseForge API Key 未配置；请在高级设置中填写，或设置 CURSEFORGE_API_KEY");
        }
        StringBuilder url = new StringBuilder(apiBase).append(path);
        if (parameters != null && !parameters.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        HttpUtil.Response response = HttpUtil.request(method, url.toString(),
                body == null ? null : "application/json", body,
                Map.of("x-api-key", key.trim()));
        if (!response.isSuccess()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IOException("CurseForge API Key 无效或无权访问（HTTP "
                        + response.statusCode() + "）");
            }
            response.requireSuccess();
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("CurseForge 返回了无效 JSON", error);
        }
    }

    private static ApiProject project(JsonObject value, String fallbackType) {
        JsonArray authors = array(value, "authors");
        String author = authors.isEmpty() || !authors.get(0).isJsonObject()
                ? "" : JsonUtil.getString(authors.get(0).getAsJsonObject(), "name", "");
        JsonObject logo = value.has("logo") && value.get("logo").isJsonObject()
                ? value.getAsJsonObject("logo") : new JsonObject();
        JsonObject links = value.has("links") && value.get("links").isJsonObject()
                ? value.getAsJsonObject("links") : new JsonObject();
        List<String> categories = new ArrayList<>();
        for (JsonElement category : array(value, "categories")) {
            if (category.isJsonObject()) {
                String slug = JsonUtil.getString(category.getAsJsonObject(), "slug", "");
                if (!slug.isBlank()) categories.add(slug);
            }
        }
        return new ApiProject(
                Integer.toString(JsonUtil.getInt(value, "id", 0)),
                JsonUtil.getString(value, "slug", ""),
                JsonUtil.getString(value, "name", ""), author,
                JsonUtil.getString(value, "summary", ""),
                JsonUtil.getLong(value, "downloadCount", 0),
                JsonUtil.getString(logo, "thumbnailUrl", JsonUtil.getString(logo, "url", "")),
                JsonUtil.getString(links, "websiteUrl", ""),
                JsonUtil.getString(links, "sourceUrl", ""),
                JsonUtil.getString(links, "issuesUrl", ""),
                parseInstant(JsonUtil.getString(value, "dateModified", "")),
                List.copyOf(categories), fallbackType == null ? "mod" : fallbackType);
    }

    private static ApiFile file(JsonObject value) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (JsonElement hashElement : array(value, "hashes")) {
            if (!hashElement.isJsonObject()) continue;
            JsonObject hash = hashElement.getAsJsonObject();
            String name = switch (JsonUtil.getInt(hash, "algo", 0)) {
                case 1 -> "sha1";
                case 2 -> "md5";
                default -> "";
            };
            String text = JsonUtil.getString(hash, "value", "");
            if (!name.isBlank() && !text.isBlank()) hashes.put(name, text);
        }
        List<String> versions = new ArrayList<>();
        for (JsonElement element : array(value, "gameVersions")) {
            if (element.isJsonPrimitive()) versions.add(element.getAsString());
        }
        List<ApiDependency> dependencies = new ArrayList<>();
        for (JsonElement element : array(value, "dependencies")) {
            if (!element.isJsonObject()) continue;
            JsonObject dependency = element.getAsJsonObject();
            dependencies.add(new ApiDependency(
                    Integer.toString(JsonUtil.getInt(dependency, "modId", 0)),
                    JsonUtil.getInt(dependency, "relationType", 0)));
        }
        int releaseType = JsonUtil.getInt(value, "releaseType", 1);
        return new ApiFile(
                Integer.toString(JsonUtil.getInt(value, "id", 0)),
                Integer.toString(JsonUtil.getInt(value, "modId", 0)),
                JsonUtil.getString(value, "displayName", ""),
                JsonUtil.getString(value, "fileName", ""),
                switch (releaseType) { case 2 -> "beta"; case 3 -> "alpha"; default -> "release"; },
                parseInstant(JsonUtil.getString(value, "fileDate", "")),
                JsonUtil.getLong(value, "fileLength", 0),
                JsonUtil.getString(value, "downloadUrl", ""),
                JsonUtil.getLong(value, "fileFingerprint", 0), Map.copyOf(hashes),
                List.copyOf(versions), List.copyOf(dependencies));
    }

    public static int classId(String projectType) throws IOException {
        return switch (projectType == null ? "" : projectType.toLowerCase(Locale.ROOT)) {
            case "mod" -> 6;
            case "resourcepack" -> 12;
            case "modpack" -> 4471;
            case "shader" -> 6552;
            default -> throw new IOException("CurseForge 不支持的内容类型: " + projectType);
        };
    }

    public static Integer loaderType(String loader) {
        if (loader == null || loader.isBlank()) return null;
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> null;
        };
    }

    public static int sortField(ModSearchIndex sort) {
        return switch (sort == null ? ModSearchIndex.RELEVANCE : sort) {
            case DOWNLOADS -> 6;
            case NEWEST -> 11;
            case UPDATED -> 3;
            case RELEVANCE, FOLLOWS -> 2;
        };
    }

    static int paginationTotal(JsonObject pagination, int resultCount) {
        return Math.max(resultCount, JsonUtil.getInt(pagination, "totalCount", resultCount));
    }

    private static String projectTypeForClass(int classId) {
        return switch (classId) {
            case 12 -> "resourcepack";
            case 4471 -> "modpack";
            case 6552 -> "shader";
            default -> "mod";
        };
    }

    private static long numericId(String value, String name) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("CurseForge " + name + " 无效: " + value, error);
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) throws IOException {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            throw new IOException("CurseForge 响应缺少 " + key);
        }
        return object.getAsJsonObject(key);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record ApiProject(String id, String slug, String title, String author, String summary,
                             long downloads, String iconUrl, String websiteUrl, String sourceUrl,
                             String issuesUrl, Instant updatedAt, List<String> categories,
                             String projectType) { }

    public record ApiFile(String id, String projectId, String displayName, String fileName,
                          String releaseType, Instant publishedAt, long size, String downloadUrl,
                          long fingerprint, Map<String, String> hashes, List<String> gameVersions,
                          List<ApiDependency> dependencies) { }

    public record ApiSearchResult(List<ApiProject> projects, int totalCount) { }

    public record ApiDependency(String projectId, int relationType) { }
}
