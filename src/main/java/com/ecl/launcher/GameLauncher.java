package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineAuth;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.RuleEvaluator;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.PlatformUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameLauncher.class);
    private static final String NATIVES_EXTRACTION_MARKER = ".ecl-natives-extracted";
    private AuthProvider auth;
    private String versionId;
    private int maxMemory = 2048;
    private int minMemory = 512;
    private File gameDir;
    private String jvmArgs = "";
    private String javaPath = "";

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

    public void setJvmArgs(String args) {
        this.jvmArgs = args;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath == null ? "" : javaPath.trim();
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

        String resolvedJavaPath = JavaRuntimeUtil.resolveJavaExecutable(javaPath);
        List<String> command = buildCommand(resolvedJavaPath, versionJson);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(launchDirectory);

        File appDataDir = launchDirectory.getParentFile();
        if (appDataDir != null) {
            pb.environment().put("APPDATA", appDataDir.getAbsolutePath());
        }
        pb.redirectErrorStream(true);

        return pb.start();
    }

    private List<String> buildCommand(String javaExecutable, JsonObject versionJson) throws IOException {
        List<String> cmd = new ArrayList<>();
        Map<String, String> variables = buildLaunchVariables(versionJson);
        cmd.add(javaExecutable);
        cmd.add("-Xms" + minMemory + "m");
        cmd.add("-Xmx" + maxMemory + "m");

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
        cmd.add(versionJson.get("mainClass").getAsString());
        cmd.addAll(parseGameArguments(versionJson, variables));
        return cmd;
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
        vars.put("${natives_directory}", new File(ECLConfig.getVersionsDir(), versionId + "/natives").getAbsolutePath());
        vars.put("${library_directory}", ECLConfig.getLibrariesDir().getAbsolutePath());
        vars.put("${classpath_separator}", File.pathSeparator);
        vars.put("${launcher_name}", ECLConfig.LAUNCHER_NAME);
        vars.put("${launcher_version}", ECLConfig.LAUNCHER_VERSION);
        return vars;
    }

    private String replaceVariables(String arg, Map<String, String> variables) {
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() != null) {
                arg = arg.replace(entry.getKey(), entry.getValue());
            }
        }
        return arg;
    }

    private String buildClassPath(JsonObject versionJson) throws IOException {
        List<String> classpath = new ArrayList<>();
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
                        File file = new File(ECLConfig.getLibrariesDir(), path);
                        if (file.exists()) {
                            classpath.add(file.getAbsolutePath());
                        }
                    }
                }
            }
        }

        String clientJarId = getEffectiveClientJarId(versionJson);
        File clientJar = new File(ECLConfig.getVersionsDir(), clientJarId + "/" + clientJarId + ".jar");
        if (clientJar.exists()) {
            classpath.add(clientJar.getAbsolutePath());
        } else {
            throw new IOException("Missing client JAR for version " + versionId + ": " + clientJar.getAbsolutePath());
        }

        extractNatives(versionJson, nativeClassifier);
        return String.join(File.pathSeparator, classpath);
    }

    private void extractNatives(JsonObject versionJson, String nativeClassifier) throws IOException {
        File nativesDir = new File(ECLConfig.getVersionsDir(), versionId + "/natives");
        nativesDir.mkdirs();

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
                        File nativeFile = new File(ECLConfig.getLibrariesDir(), path);
                        nativeFiles.add(nativeFile);
                    }
                    break;
                }
            }
        }

        File marker = new File(nativesDir, NATIVES_EXTRACTION_MARKER);
        String fingerprint = buildNativesFingerprint(nativeFiles);
        if (isNativesExtractionCurrent(marker, fingerprint)) {
            return;
        }

        for (File nativeFile : nativeFiles) {
            if (nativeFile.isFile()) {
                extractJar(nativeFile, nativesDir);
            }
        }
        try {
            Files.writeString(marker.toPath(), fingerprint, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write natives extraction marker for version {}", versionId, e);
        }
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

    private boolean isNativesExtractionCurrent(File marker, String fingerprint) {
        if (!marker.isFile()) {
            return false;
        }
        try {
            return fingerprint.equals(Files.readString(marker.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.debug("Failed to read natives extraction marker for version {}", versionId, e);
            return false;
        }
    }

    private String buildNativesFingerprint(Iterable<File> nativeFiles) {
        StringBuilder fingerprint = new StringBuilder();
        for (File nativeFile : nativeFiles) {
            fingerprint.append(nativeFile.getAbsolutePath())
                    .append('|').append(nativeFile.isFile() ? nativeFile.length() : -1)
                    .append('|').append(nativeFile.isFile() ? nativeFile.lastModified() : -1)
                    .append('\n');
        }
        return fingerprint.toString();
    }

    private void extractJar(File jarFile, File targetDir) throws IOException {
        Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/") || entry.isDirectory()) {
                    continue;
                }

                Path outPath = targetRoot.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetRoot)) {
                    throw new IOException("Native entry escapes extraction directory: " + entry.getName());
                }
                Path parent = outPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
}
