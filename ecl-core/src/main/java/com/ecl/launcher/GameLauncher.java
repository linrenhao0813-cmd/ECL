package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineAuth;
import com.ecl.game.MavenCoordinates;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.RuleEvaluator;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.PlatformUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameLauncher implements LaunchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameLauncher.class);
    private static final String NATIVES_EXTRACTION_MARKER = ".ecl-natives-extracted";
    private static final Pattern RELEASE_VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+_].*)?$");
    private static final Pattern SNAPSHOT_VERSION_PATTERN =
            Pattern.compile("^(\\d{2})w(\\d{2})([a-z].*)?$", Pattern.CASE_INSENSITIVE);
    private AuthProvider auth;
    private String versionId;
    private int maxMemory = 2048;
    private int minMemory = 512;
    private File gameDir;
    private File instanceDir;
    private String jvmArgs = "";
    private String javaPath = "";
    private int gameWidth = 1280;
    private int gameHeight = 720;
    private boolean fullscreen;
    private String serverAddress = "";
    private int processorCount;

    public GameLauncher() {
        this.auth = new OfflineAuth("Player");
        this.gameDir = ECLConfig.getGameDir();
    }

    public void setAuth(AuthProvider auth) {
        this.auth = auth;
    }

    public void setVersion(String versionId) {
        this.versionId = versionId;
    }

    public void setMaxMemory(int mb) {
        this.maxMemory = mb;
    }

    public void setMinMemory(int mb) {
        this.minMemory = mb;
    }

    public void setGameDir(File dir) {
        this.gameDir = dir;
    }

    @Override
    public void setInstanceDir(File dir) {
        this.instanceDir = dir;
    }

    public void setJvmArgs(String args) {
        this.jvmArgs = args;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath == null ? "" : javaPath.trim();
    }

    @Override
    public void setGameResolution(int width, int height) {
        this.gameWidth = Math.max(320, width);
        this.gameHeight = Math.max(240, height);
    }

    @Override
    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    @Override
    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress == null ? "" : serverAddress.trim();
    }

    @Override
    public void setProcessorCount(int processorCount) {
        this.processorCount = Math.max(0, processorCount);
    }

    public Process launch() throws IOException {
        if (versionId == null || versionId.isBlank()) {
            throw new IOException("未选择游戏版本");
        }

        JsonObject versionJson = loadVersionJsonWithInheritance();
        if (versionJson == null) {
            throw new IOException("无法加载版本JSON: " + versionId);
        }

        File launchDirectory = gameDir == null ? ECLConfig.getGameDir() : gameDir;
        launchDirectory.mkdirs();
        gameDir = launchDirectory;
        if (instanceDir == null) {
            instanceDir = launchDirectory;
        }

        int requiredJavaMajor = determineRequiredJavaMajor(versionJson);
        String resolvedJavaPath = JavaRuntimeUtil.resolveOrDownloadJavaExecutable(
                javaPath, requiredJavaMajor,
                message -> LOGGER.info("{}", message),
                (downloaded, total) -> { });
        List<String> command = buildCommand(resolvedJavaPath, versionJson);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(launchDirectory);

        File appDataDir = launchDirectory.getParentFile();
        if (appDataDir != null) {
            pb.environment().put("APPDATA", appDataDir.getAbsolutePath());
        }
        pb.environment().put("INST_DIR", instanceDir.getAbsolutePath());
        pb.environment().put("INST_MC_DIR", launchDirectory.getAbsolutePath());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    private int determineRequiredJavaMajor(JsonObject versionJson) {
        if (versionJson != null && versionJson.has("javaVersion")) {
            JsonObject javaVersion = versionJson.getAsJsonObject("javaVersion");
            if (javaVersion.has("majorVersion")) {
                try {
                    return javaVersion.get("majorVersion").getAsInt();
                } catch (NumberFormatException | IllegalStateException ignored) {
                    LOGGER.debug("Could not parse javaVersion.majorVersion from version JSON", ignored);
                }
            }
        }

        return inferRequiredJavaMajorFromVersionId();
    }

    private int inferRequiredJavaMajorFromVersionId() {
        int[] release = parseReleaseVersion(versionId);
        if (release != null) {
            int minor = release[1];
            int patch = release[2];
            if (minor > 20 || minor == 20 && patch >= 5) {
                return 21;
            }
            if (minor >= 18) {
                return 17;
            }
            return 8;
        }

        int[] snapshot = parseSnapshotVersion(versionId);
        if (snapshot == null) {
            return 8;
        }

        int year = snapshot[0];
        int week = snapshot[1];
        if (year > 24 || year == 24 && week >= 14) {
            return 21;
        }
        if (year > 21 || year == 21 && week >= 37) {
            return 17;
        }
        return 8;
    }

    private int[] parseReleaseVersion(String id) {
        if (id == null) {
            return null;
        }

        Matcher matcher = RELEASE_VERSION_PATTERN.matcher(id.trim());
        if (!matcher.matches()) {
            return null;
        }

        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return new int[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int[] parseSnapshotVersion(String id) {
        if (id == null) {
            return null;
        }
        Matcher matcher = SNAPSHOT_VERSION_PATTERN.matcher(id.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private List<String> buildCommand(String javaExecutable, JsonObject versionJson) throws IOException {
        String mainClass = requireMainClass(versionJson);
        List<String> cmd = new ArrayList<>();
        Map<String, String> variables = buildLaunchVariables(versionJson);
        cmd.add(javaExecutable);
        cmd.add("-Xms" + minMemory + "m");
        cmd.add("-Xmx" + maxMemory + "m");
        if (processorCount > 0) {
            cmd.add("-XX:ActiveProcessorCount=" + processorCount);
        }

        if (jvmArgs != null && !jvmArgs.isEmpty()) {
            for (String arg : jvmArgs.split("\\s+")) {
                if (!arg.isEmpty()) {
                    cmd.add(arg);
                }
            }
        }

        cmd.addAll(parseJVMArguments(versionJson, variables));
        cmd.add("-cp");
        cmd.add(buildClassPath(versionJson));
        cmd.add(mainClass);
        cmd.addAll(parseGameArguments(versionJson, variables));
        cmd.add("--width");
        cmd.add(Integer.toString(gameWidth));
        cmd.add("--height");
        cmd.add(Integer.toString(gameHeight));
        if (fullscreen) {
            cmd.add("--fullscreen");
        }
        appendServerArguments(cmd);
        return cmd;
    }

    private void appendServerArguments(List<String> command) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return;
        }
        String host = serverAddress.trim();
        String port = null;
        if (host.startsWith("[") && host.contains("]")) {
            int bracket = host.indexOf(']');
            if (bracket + 1 < host.length() && host.charAt(bracket + 1) == ':') {
                port = host.substring(bracket + 2);
                host = host.substring(1, bracket);
            }
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        command.add("--server");
        command.add(host);
        if (port != null && port.matches("\\d{1,5}")) {
            command.add("--port");
            command.add(port);
        }
    }

    String requireMainClass(JsonObject versionJson) throws IOException {
        if (versionJson == null || !versionJson.has("mainClass")) {
            throw invalidMainClass();
        }
        JsonElement element = versionJson.get("mainClass");
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw invalidMainClass();
        }
        String mainClass = element.getAsString().trim();
        if (mainClass.isEmpty()) {
            throw invalidMainClass();
        }
        return mainClass;
    }

    private IOException invalidMainClass() {
        return new IOException("版本 JSON 的 mainClass 字段缺失或不是非空字符串: " + versionId);
    }

    private List<String> parseJVMArguments(JsonObject versionJson, Map<String, String> variables) {
        List<String> args = new ArrayList<>();

        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("jvm")) {
            JsonArray jvmArray = versionJson.getAsJsonObject("arguments").getAsJsonArray("jvm");
            for (JsonElement el : jvmArray) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString(), variables);
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject argObj = el.getAsJsonObject();
                    if (evaluateRules(argObj)) {
                        parseArgumentValue(argObj, args, variables);
                    }
                }
            }
        }

        if (args.isEmpty() && versionJson.has("jvmArguments")) {
            JsonArray arr = versionJson.getAsJsonArray("jvmArguments");
            for (JsonElement el : arr) {
                String arg = replaceVariables(el.getAsString(), variables);
                if (!arg.isEmpty()) {
                    args.add(arg);
                }
            }
        }

        return args;
    }

    private List<String> parseGameArguments(JsonObject versionJson, Map<String, String> variables) {
        List<String> args = new ArrayList<>();

        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("game")) {
            JsonArray gameArray = versionJson.getAsJsonObject("arguments").getAsJsonArray("game");
            for (JsonElement el : gameArray) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString(), variables);
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject argObj = el.getAsJsonObject();
                    if (evaluateRules(argObj)) {
                        parseArgumentValue(argObj, args, variables);
                    }
                }
            }
        }

        if (args.isEmpty() && versionJson.has("minecraftArguments")) {
            String[] rawArgs = versionJson.get("minecraftArguments").getAsString().trim().split("\\s+");
            for (String arg : rawArgs) {
                String replaced = replaceVariables(arg, variables);
                if (!replaced.isEmpty()) {
                    args.add(replaced);
                }
            }
        }

        return args;
    }

    private void parseArgumentValue(JsonObject argObj, List<String> target, Map<String, String> variables) {
        if (!argObj.has("value")) {
            return;
        }

        JsonElement valueEl = argObj.get("value");
        if (valueEl.isJsonPrimitive()) {
            String arg = replaceVariables(valueEl.getAsString(), variables);
            if (!arg.isEmpty()) {
                target.add(arg);
            }
        } else if (valueEl.isJsonArray()) {
            for (JsonElement el : valueEl.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString(), variables);
                    if (!arg.isEmpty()) {
                        target.add(arg);
                    }
                }
            }
        }
    }

    private boolean evaluateRules(JsonObject argObj) {
        return !argObj.has("rules") || RuleEvaluator.isAllowed(argObj.getAsJsonArray("rules"));
    }

    private Map<String, String> buildLaunchVariables(JsonObject versionJson) {
        Map<String, String> vars = new HashMap<>();
        vars.put("${auth_player_name}", auth.getUsername());
        vars.put("${auth_session}", auth.getAccessToken());
        vars.put("${auth_uuid}", auth.getUUID());
        vars.put("${auth_access_token}", auth.getAccessToken());
        vars.put("${version_name}", versionId);
        vars.put("${version_type}", ECLConfig.LAUNCHER_NAME);
        vars.put("${game_directory}", gameDir.getAbsolutePath());
        vars.put("${assets_root}", ECLConfig.getAssetsDir().getAbsolutePath());
        vars.put("${assets_index_name}", getAssetIndexName(versionJson));
        vars.put("${user_type}", auth.getType().name().toLowerCase());
        vars.put("${natives_directory}", nativesDirectory().getAbsolutePath());
        vars.put("${library_directory}", ECLConfig.getLibrariesDir().getAbsolutePath());
        vars.put("${classpath_separator}", File.pathSeparator);
        vars.put("${launcher_name}", ECLConfig.LAUNCHER_NAME);
        vars.put("${launcher_version}", ECLConfig.LAUNCHER_VERSION);
        return vars;
    }

    private String replaceVariables(String arg, Map<String, String> variables) {
        if (arg == null || arg.indexOf("${") < 0) {
            return arg;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() != null && arg.indexOf(entry.getKey()) >= 0) {
                arg = arg.replace(entry.getKey(), entry.getValue());
            }
        }
        return arg;
    }

    private String buildClassPath(JsonObject versionJson) throws IOException {
        Set<String> classpath = new LinkedHashSet<>();
        String nativeClassifier = FileUtil.getNativeClassifier();

        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement el : libraries) {
                JsonObject lib = el.getAsJsonObject();
                if (lib.has("rules") && !RuleEvaluator.isAllowed(lib.getAsJsonArray("rules"))) {
                    continue;
                }

                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        String path = artifact.get("path").getAsString();
                        File file = FileUtil.safeResolveUnder(libraryDirectory(lib), path);
                        if (!file.exists()) {
                            throw new IOException("缺少依赖库: " + path);
                        }
                        classpath.add(file.getAbsolutePath());
                    }
                } else if (MavenCoordinates.isSimpleCoordinate(
                        JsonUtil.getString(lib, "name", ""))
                        && !JsonUtil.getString(lib, "url", "").isBlank()) {
                    // Fabric/Quilt style: bare Maven coordinate with a repository URL.
                    String path = MavenCoordinates.repositoryPath(
                            JsonUtil.getString(lib, "name", ""));
                    File file = FileUtil.safeResolveUnder(libraryDirectory(lib), path);
                    if (!file.exists()) {
                        throw new IOException("缺少依赖库: " + path);
                    }
                    classpath.add(file.getAbsolutePath());
                }
            }
        }

        String clientJarId = getEffectiveClientJarId(versionJson);
        File clientJar = new File(ECLConfig.getVersionsDir(), clientJarId + "/" + clientJarId + ".jar");
        if (!clientJar.exists()) {
            throw new IOException("Missing client JAR for version " + versionId + ": " + clientJar.getAbsolutePath());
        }
        classpath.add(clientJar.getAbsolutePath());

        extractNatives(versionJson, nativeClassifier);
        return String.join(File.pathSeparator, classpath);
    }

    private void extractNatives(JsonObject versionJson, String nativeClassifier) throws IOException {
        File nativesDir = nativesDirectory();
        Files.createDirectories(nativesDir.toPath());

        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries == null) {
            return;
        }

        String osArch = getOsArch(nativeClassifier);
        String architectureBits = getArchitectureBits();

        Set<File> nativeFiles = new LinkedHashSet<>();
        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !RuleEvaluator.isAllowed(lib.getAsJsonArray("rules"))) {
                continue;
            }
            if (!lib.has("downloads")) {
                continue;
            }

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("classifiers")) {
                continue;
            }

            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            Set<String> nativeKeys = getNativeClassifierKeys(lib, nativeClassifier, osArch, architectureBits);
            for (String key : nativeKeys) {
                if (classifiers.has(key)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(key);
                    if (nativeArtifact.has("path")) {
                        String path = nativeArtifact.get("path").getAsString();
                        File nativeFile = new File(libraryDirectory(lib), path);
                        nativeFiles.add(nativeFile);
                    }
                    break;
                }
            }
        }

        File marker = new File(nativesDir, NATIVES_EXTRACTION_MARKER);
        String sourceFingerprint = buildNativesFingerprint(nativeFiles);
        if (isNativesExtractionCurrent(marker, sourceFingerprint, nativesDir.toPath())) {
            return;
        }

        clearNativesDirectory(nativesDir.toPath(), marker.toPath());
        ExtractionBudget extractionBudget = new ExtractionBudget(new ExtractionLimits(
                MAX_EXTRACTED_TOTAL_BYTES, MAX_EXTRACTED_SINGLE_BYTES, MAX_EXTRACTED_ENTRIES));
        for (File nativeFile : nativeFiles) {
            if (nativeFile.isFile()) {
                extractJar(nativeFile, nativesDir, extractionBudget);
            }
        }
        try {
            writeMarkerAtomically(marker.toPath(),
                    buildNativesMarker(sourceFingerprint, nativesDir.toPath(), marker.toPath()));
        } catch (IOException e) {
            LOGGER.warn("Failed to write natives extraction marker for version {}", versionId, e);
        }
    }

    private File nativesDirectory() {
        File root = instanceDir == null ? new File(ECLConfig.getVersionsDir(), versionId) : instanceDir;
        return new File(root, "natives-" + PlatformUtil.current().minecraftName());
    }

    private File libraryDirectory(JsonObject library) {
        String hint = library.has("hint") ? library.get("hint").getAsString()
                : library.has("eclHint") ? library.get("eclHint").getAsString() : "";
        if ("local".equalsIgnoreCase(hint) && instanceDir != null) {
            return new File(instanceDir, "libraries");
        }
        return ECLConfig.getLibrariesDir();
    }

    private String getOsArch(String nativeClassifier) {
        if (nativeClassifier == null || nativeClassifier.isBlank()) {
            return PlatformUtil.current().minecraftName();
        }
        int separator = nativeClassifier.indexOf('-');
        return separator > 0 ? nativeClassifier.substring(0, separator) : nativeClassifier;
    }

    private String getArchitectureBits() {
        String architecture = System.getProperty("os.arch", "").toLowerCase();
        return architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
    }

    private Set<String> getNativeClassifierKeys(JsonObject library, String nativeClassifier,
                                                 String osArch, String architectureBits) {
        Set<String> keys = new LinkedHashSet<>();
        if (library.has("natives") && library.get("natives").isJsonObject()) {
            JsonObject natives = library.getAsJsonObject("natives");
            if (natives.has(osArch) && natives.get(osArch).isJsonPrimitive()) {
                keys.add(natives.get(osArch).getAsString().replace("${arch}", architectureBits));
            }
        }
        keys.add("natives-" + osArch);
        if (nativeClassifier != null && !nativeClassifier.isBlank()) {
            keys.add(nativeClassifier);
        }
        keys.add(osArch);
        return keys;
    }

    private boolean isNativesExtractionCurrent(File marker, String sourceFingerprint, Path nativesDir) {
        if (!marker.isFile()) {
            return false;
        }
        try {
            String currentFingerprint = buildNativesMarker(sourceFingerprint, nativesDir, marker.toPath());
            return currentFingerprint.equals(Files.readString(marker.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.debug("Failed to read natives extraction marker for version {}", versionId, e);
            return false;
        }
    }

    private String buildNativesFingerprint(Iterable<File> nativeFiles) throws IOException {
        StringBuilder fingerprint = new StringBuilder();
        for (File nativeFile : nativeFiles) {
            fingerprint.append(nativeFile.getAbsolutePath())
                    .append('|').append(nativeFile.isFile() ? FileUtil.sha1(nativeFile) : "missing")
                    .append('\n');
        }
        return fingerprint.toString();
    }

    private String buildNativesMarker(String sourceFingerprint, Path nativesDir, Path marker) throws IOException {
        StringBuilder fingerprint = new StringBuilder("sources\n")
                .append(sourceFingerprint)
                .append("extracted\n");
        try (var entries = Files.walk(nativesDir)) {
            for (Path entry : entries.filter(Files::isRegularFile).sorted().toList()) {
                if (entry.equals(marker)) {
                    continue;
                }
                fingerprint.append(nativesDir.relativize(entry).toString().replace(File.separatorChar, '/'))
                        .append('|').append(FileUtil.sha1(entry.toFile()))
                        .append('\n');
            }
        }
        return fingerprint.toString();
    }

    private void clearNativesDirectory(Path nativesDir, Path marker) throws IOException {
        if (!Files.isDirectory(nativesDir)) {
            return;
        }
        try (var entries = Files.list(nativesDir)) {
            for (Path entry : entries.toList()) {
                if (entry.equals(marker)) {
                    Files.deleteIfExists(entry);
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    FileUtil.deleteDirectory(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private void writeMarkerAtomically(Path marker, String fingerprint) throws IOException {
        Files.createDirectories(marker.getParent());
        Path tempFile = Files.createTempFile(marker.getParent(), "natives-", ".marker.tmp");
        try {
            Files.writeString(tempFile, fingerprint, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // 安全限制：防止 ZIP 炸弹
    private static final long MAX_EXTRACTED_TOTAL_BYTES = 500 * 1024 * 1024L;  // 500 MB
    private static final long MAX_EXTRACTED_SINGLE_BYTES = 100 * 1024 * 1024L; // 100 MB 单个文件
    private static final int MAX_EXTRACTED_ENTRIES = 10_000;                    // 最多 1 万个文件

    void extractJar(File jarFile, File targetDir, ExtractionBudget budget) throws IOException {
        Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/") || entry.isDirectory()) {
                    continue;
                }

                if (++budget.entryCount > budget.limits.maxEntries()) {
                    throw new IOException("ZIP 炸弹防护: 解压文件数超过上限 "
                            + budget.limits.maxEntries() + ": " + jarFile);
                }

                long declaredSize = entry.getSize();
                if (declaredSize > budget.limits.maxSingleBytes()) {
                    throw new IOException("ZIP 炸弹防护: 单个解压文件声明大小超过限制 (" + declaredSize + " bytes): " + entry.getName());
                }
                if (declaredSize >= 0
                        && declaredSize > budget.limits.maxTotalBytes() - budget.totalBytes) {
                    throw new IOException("ZIP 炸弹防护: 解压总大小超过上限 "
                            + budget.limits.maxTotalBytes() / 1024 / 1024 + " MB: " + jarFile);
                }

                Path outPath = targetRoot.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetRoot)) {
                    throw new IOException("Native entry escapes extraction directory: " + entry.getName());
                }
                Path parent = outPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // 手动缓冲复制，边读边检查大小，防止 ZIP 炸弹在数据落盘后才被发现
                long entryBytes = 0;
                try (InputStream is = jar.getInputStream(entry);
                     OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(outPath), 64 * 1024)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        if (read > budget.limits.maxSingleBytes() - entryBytes) {
                            throw new IOException("ZIP 炸弹防护: 解压时单文件超过限制 ("
                                    + budget.limits.maxSingleBytes() / 1024 / 1024 + " MB): " + entry.getName());
                        }
                        if (read > budget.limits.maxTotalBytes() - budget.totalBytes) {
                            throw new IOException("ZIP 炸弹防护: 解压总大小超过上限 "
                                    + budget.limits.maxTotalBytes() / 1024 / 1024 + " MB: " + jarFile);
                        }
                        os.write(buffer, 0, read);
                        entryBytes += read;
                        budget.totalBytes += read;
                    }
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(outPath);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                    throw e;
                }
            }
        }
    }

    record ExtractionLimits(long maxTotalBytes, long maxSingleBytes, int maxEntries) {
        ExtractionLimits {
            if (maxTotalBytes <= 0 || maxSingleBytes <= 0 || maxEntries <= 0
                    || maxSingleBytes > maxTotalBytes) {
                throw new IllegalArgumentException("Invalid native extraction limits");
            }
        }
    }

    static final class ExtractionBudget {
        private final ExtractionLimits limits;
        private long totalBytes;
        private int entryCount;

        ExtractionBudget(ExtractionLimits limits) {
            this.limits = limits;
        }
    }

    private JsonObject loadVersionJsonWithInheritance() throws IOException {
        return loadVersionJsonWithInheritance(versionId, new LinkedHashSet<>());
    }

    private JsonObject loadVersionJsonWithInheritance(String vid, LinkedHashSet<String> inheritanceChain) throws IOException {
        if (vid == null || vid.isBlank()) {
            throw new IOException("Version inheritance contains an empty version id");
        }
        if (!inheritanceChain.add(vid)) {
            throw new IOException("Circular version inheritance: "
                    + String.join(" -> ", inheritanceChain) + " -> " + vid);
        }

        File file = new File(ECLConfig.getVersionsDir(), vid + "/" + vid + ".json");
        if (!file.isFile()) {
            throw new IOException("Missing version JSON in inheritance chain: " + file.getAbsolutePath());
        }

        try {
            JsonObject versionJson = HttpUtil.readJson(file);
            if (!versionJson.has("inheritsFrom")) {
                return versionJson;
            }

            JsonElement parentElement = versionJson.get("inheritsFrom");
            if (!parentElement.isJsonPrimitive()) {
                throw new IOException("Invalid inheritsFrom in version " + vid);
            }
            String parentId = parentElement.getAsString();
            JsonObject parentJson = loadVersionJsonWithInheritance(parentId, inheritanceChain);
            if (!versionJson.has("jar")) {
                versionJson.addProperty("jar", getEffectiveClientJarId(parentJson, parentId));
            }
            return mergeJson(parentJson, versionJson);
        } finally {
            inheritanceChain.remove(vid);
        }
    }

    private JsonObject mergeJson(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            if ("inheritsFrom".equals(key)) {
                continue;
            }

            JsonElement childValue = entry.getValue();
            JsonElement parentValue = result.get(key);
            if ("libraries".equals(key) && parentValue != null
                    && parentValue.isJsonArray() && childValue.isJsonArray()) {
                result.add(key, mergeLibraries(parentValue.getAsJsonArray(), childValue.getAsJsonArray()));
            } else if ("arguments".equals(key) && parentValue != null
                    && parentValue.isJsonObject() && childValue.isJsonObject()) {
                result.add(key, mergeArgumentObjects(parentValue.getAsJsonObject(), childValue.getAsJsonObject()));
            } else {
                result.add(key, childValue.deepCopy());
            }
        }
        return result;
    }

    private JsonArray mergeLibraries(JsonArray parent, JsonArray child) {
        List<JsonElement> merged = new ArrayList<>();
        Map<String, Integer> keyedIndexes = new HashMap<>();

        for (JsonElement element : parent) {
            addOrReplaceLibrary(merged, keyedIndexes, element);
        }
        for (JsonElement element : child) {
            addOrReplaceLibrary(merged, keyedIndexes, element);
        }

        JsonArray result = new JsonArray();
        merged.forEach(element -> result.add(element.deepCopy()));
        return result;
    }

    private void addOrReplaceLibrary(List<JsonElement> merged, Map<String, Integer> keyedIndexes,
                                     JsonElement library) {
        String key = getLibraryIdentity(library);
        Integer existingIndex = key == null ? null : keyedIndexes.get(key);
        if (existingIndex != null) {
            merged.set(existingIndex, library);
            return;
        }

        if (key != null) {
            keyedIndexes.put(key, merged.size());
        }
        merged.add(library);
    }

    /** Maven group/artifact plus classifier identifies the classpath slot; the child supplies the version. */
    private String getLibraryIdentity(JsonElement library) {
        if (!library.isJsonObject()) {
            return null;
        }
        JsonObject object = library.getAsJsonObject();
        if (!object.has("name") || !object.get("name").isJsonPrimitive()) {
            return null;
        }

        String[] coordinate = object.get("name").getAsString().split(":", -1);
        if (coordinate.length < 3 || coordinate[0].isBlank() || coordinate[1].isBlank()) {
            return null;
        }

        StringBuilder identity = new StringBuilder(coordinate[0]).append(':').append(coordinate[1]);
        for (int i = 3; i < coordinate.length; i++) {
            identity.append(':').append(coordinate[i]);
        }
        return identity.toString();
    }

    private JsonObject mergeArgumentObjects(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            JsonElement childValue = entry.getValue();
            JsonElement parentValue = result.get(key);
            if (parentValue != null && parentValue.isJsonArray() && childValue.isJsonArray()) {
                result.add(key, concatenateArrays(parentValue.getAsJsonArray(), childValue.getAsJsonArray()));
            } else if (parentValue != null && parentValue.isJsonObject() && childValue.isJsonObject()) {
                result.add(key, mergeArgumentObjects(parentValue.getAsJsonObject(), childValue.getAsJsonObject()));
            } else {
                result.add(key, childValue.deepCopy());
            }
        }
        return result;
    }

    private JsonArray concatenateArrays(JsonArray parent, JsonArray child) {
        JsonArray result = parent.deepCopy();
        for (JsonElement element : child) {
            result.add(element.deepCopy());
        }
        return result;
    }

    private String getEffectiveClientJarId(JsonObject versionJson) {
        return getEffectiveClientJarId(versionJson, versionId);
    }

    private String getEffectiveClientJarId(JsonObject versionJson, String fallback) {
        if (versionJson != null && versionJson.has("jar") && versionJson.get("jar").isJsonPrimitive()) {
            String jarId = versionJson.get("jar").getAsString();
            if (!jarId.isBlank()) {
                return jarId;
            }
        }
        return fallback;
    }

    private String getAssetIndexName(JsonObject versionJson) {
        if (versionJson.has("assetIndex") && versionJson.get("assetIndex").isJsonObject()) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            if (assetIndex.has("id") && assetIndex.get("id").isJsonPrimitive()) {
                return assetIndex.get("id").getAsString();
            }
        }
        return versionId;
    }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
