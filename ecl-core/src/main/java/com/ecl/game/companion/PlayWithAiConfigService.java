package com.ecl.game.companion;

import com.ecl.game.DefaultGameRepository;
import com.ecl.launch.GameProcessMarker;
import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;
import com.ecl.util.InstanceOperationLease;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Reads and writes the playwithAI client configuration for an ECL instance. */
public final class PlayWithAiConfigService {
    public static final String CONFIG_RELATIVE_PATH = "config/minecraft-ai-companion.json";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final long MAX_CONFIG_BYTES = 1_048_576;

    private final Predicate<String> instanceRunning;

    public PlayWithAiConfigService() {
        this(ignored -> false);
    }

    /** Creates a service that refuses configuration writes while the owning instance is running. */
    public PlayWithAiConfigService(Predicate<String> instanceRunning) {
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    public Config load(DefaultGameRepository repository, String instanceId) throws IOException {
        Objects.requireNonNull(repository, "repository");
        String safeInstanceId = requireInstanceId(instanceId);
        Path runDirectory = repository.runDirectory(safeInstanceId).toAbsolutePath().normalize();
        Path path = configPath(runDirectory, safeInstanceId);
        boolean running = isRunning(safeInstanceId, runDirectory);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Config.defaults(path, running);
        }
        JsonObject json = readObject(path);
        validateSupportedSchema(json);
        return Config.from(path, json, running);
    }

    public void save(DefaultGameRepository repository, String instanceId,
                     AiProvider provider, String apiKey, String baseUrl, String model)
            throws IOException {
        Objects.requireNonNull(provider, "provider");
        String safeInstanceId = requireInstanceId(instanceId);
        Path runDirectory = Objects.requireNonNull(repository, "repository")
                .runDirectory(safeInstanceId).toAbsolutePath().normalize();
        ensureNotRunning(safeInstanceId, runDirectory);
        Path path = configPath(runDirectory, safeInstanceId);
        try (InstanceOperationLease lease = InstanceOperationLease.tryAcquire(runDirectory)) {
            if (lease == null) {
                throw new IOException("实例正在运行，不能修改 AI 设置: " + safeInstanceId);
            }
            ensureNotRunning(safeInstanceId, runDirectory);
            JsonObject json = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ? readObject(path) : defaultsJson();
            validateSupportedSchema(json);
            String safeBaseUrl = normalizeBaseUrl(provider, baseUrl);
            String safeModel = normalizeModel(provider, model);
            validateBaseUrl(safeBaseUrl);
            if (safeModel.isBlank()) {
                throw new IOException("AI 模型不能为空");
            }
            json.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
            json.addProperty("aiProvider", provider.name());
            json.addProperty("apiKey", safe(apiKey));
            json.addProperty("baseUrl", safeBaseUrl);
            json.addProperty("model", safeModel);
            writeAtomically(path, json);
        }
    }

    public Path configPath(DefaultGameRepository repository, String instanceId) throws IOException {
        Objects.requireNonNull(repository, "repository");
        String safeInstanceId = requireInstanceId(instanceId);
        return configPath(repository.runDirectory(safeInstanceId), safeInstanceId);
    }

    public static void validateBaseUrl(String rawUrl) throws IOException {
        String value = safe(rawUrl);
        if (value.isBlank()) {
            throw new IOException("API Base URL 不能为空");
        }
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("API Base URL 格式无效", invalid);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("API Base URL 必须使用 HTTP 或 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("API Base URL 必须包含有效主机名");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IOException("API Base URL 不能包含用户名、密码或片段标识符");
        }
    }

    private static Path configPath(Path runDirectory, String instanceId) throws IOException {
        Path root = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        Path path = root.resolve(CONFIG_RELATIVE_PATH).normalize();
        FileUtil.validateExistingAncestors(root, path);
        return path;
    }

    private boolean isRunning(String instanceId, Path runDirectory) throws IOException {
        return instanceRunning.test(instanceId) || GameProcessMarker.isRunning(runDirectory);
    }

    private void ensureNotRunning(String instanceId, Path runDirectory) throws IOException {
        if (isRunning(instanceId, runDirectory)) {
            throw new IOException("实例正在运行，不能修改 AI 设置: " + instanceId);
        }
    }

    private static void validateSupportedSchema(JsonObject json) throws IOException {
        int schema = schemaVersion(json);
        if (schema > CURRENT_SCHEMA_VERSION) {
            throw new IOException("playwithAI 配置版本过新，当前 ECL 不能安全修改: " + schema);
        }
    }

    private static int schemaVersion(JsonObject json) throws IOException {
        JsonElement value = json.get("schemaVersion");
        if (value == null || value.isJsonNull()) return 0;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("playwithAI 配置版本无效");
        }
        try {
            int schema = new BigDecimal(value.getAsString()).intValueExact();
            if (schema < 0) throw new IOException("playwithAI 配置版本无效");
            return schema;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IOException("playwithAI 配置版本无效", invalid);
        }
    }

    private static String requireInstanceId(String instanceId) throws IOException {
        FileUtil.requireSafeVersionId(instanceId);
        return instanceId.trim();
    }

    private static JsonObject readObject(Path path) throws IOException {
        if (Files.size(path) > MAX_CONFIG_BYTES) {
            throw new IOException("playwithAI 配置文件过大");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("playwithAI 配置文件必须是 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException invalid) {
            throw new IOException("无法读取 playwithAI 配置，请修复配置文件后重试", invalid);
        }
    }

    private static JsonObject defaultsJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        json.addProperty("aiProvider", AiProvider.OPENAI.name());
        json.addProperty("apiKey", "");
        json.addProperty("baseUrl", AiProvider.OPENAI.defaultBaseUrl);
        json.addProperty("model", AiProvider.OPENAI.defaultModel);
        json.addProperty("oauthProvider", "GOOGLE");
        json.addProperty("oauthClientId", "");
        json.addProperty("oauthUser", "");
        return json;
    }

    private static void writeAtomically(Path target, JsonObject json) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        FileUtil.validateExistingAncestors(parent, target);
        Path temporary = Files.createTempFile(parent, ".minecraft-ai-companion-", ".tmp");
        try {
            Files.writeString(temporary, GsonProvider.pretty().toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String normalizeBaseUrl(AiProvider provider, String value) {
        String safeValue = safe(value);
        return safeValue.isBlank() ? provider.defaultBaseUrl : safeValue;
    }

    private static String normalizeModel(AiProvider provider, String value) {
        String safeValue = safe(value);
        if (provider == AiProvider.DEEPSEEK && safeValue.equals("deepseek-chat")) {
            return provider.defaultModel;
        }
        return safeValue.isBlank() ? provider.defaultModel : safeValue;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public enum AiProvider {
        OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash"),
        CUSTOM("Custom", "", "");

        public final String label;
        public final String defaultBaseUrl;
        public final String defaultModel;

        AiProvider(String label, String defaultBaseUrl, String defaultModel) {
            this.label = label;
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
        }

        public static AiProvider parse(String value) {
            if (value == null) return OPENAI;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return OPENAI;
            }
        }
    }

    public record Config(Path path, AiProvider aiProvider, String apiKey, String baseUrl, String model,
                         boolean fileExists, boolean instanceRunning) {
        public Config {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            aiProvider = aiProvider == null ? AiProvider.OPENAI : aiProvider;
            apiKey = safe(apiKey);
            baseUrl = normalizeBaseUrl(aiProvider, baseUrl);
            model = normalizeModel(aiProvider, model);
        }

        static Config defaults(Path path, boolean instanceRunning) {
            return new Config(path, AiProvider.OPENAI, "", AiProvider.OPENAI.defaultBaseUrl,
                    AiProvider.OPENAI.defaultModel, false, instanceRunning);
        }

        static Config from(Path path, JsonObject json, boolean instanceRunning) {
            AiProvider provider = AiProvider.parse(string(json, "aiProvider", "OPENAI"));
            return new Config(path, provider, string(json, "apiKey", ""),
                    string(json, "baseUrl", ""), string(json, "model", ""), true,
                    instanceRunning);
        }

        public boolean hasApiKey() {
            return !apiKey.isBlank();
        }

        public String maskedApiKey() {
            if (!hasApiKey()) return "未配置";
            if (apiKey.length() <= 4) return "已配置";
            return "已配置（末尾 " + apiKey.substring(apiKey.length() - 4) + "）";
        }
    }

    private static String string(JsonObject json, String name, String fallback) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
