package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineAuth;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JavaRuntimeUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class GameLauncher {
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
        pb.inheritIO();

        return pb.start();
    }

    private List<String> buildCommand(String javaExecutable, JsonObject versionJson) throws IOException {
        List<String> cmd = new ArrayList<>();
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

        cmd.addAll(parseJVMArguments(versionJson));
        cmd.add("-cp");
        cmd.add(buildClassPath(versionJson));
        cmd.add(versionJson.get("mainClass").getAsString());
        cmd.addAll(parseGameArguments(versionJson));
        return cmd;
    }

    private List<String> parseJVMArguments(JsonObject versionJson) {
        List<String> args = new ArrayList<>();

        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("jvm")) {
            JsonArray jvmArray = versionJson.getAsJsonObject("arguments").getAsJsonArray("jvm");
            for (JsonElement el : jvmArray) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString());
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject argObj = el.getAsJsonObject();
                    if (evaluateRules(argObj)) {
                        parseArgumentValue(argObj, args);
                    }
                }
            }
        }

        if (args.isEmpty() && versionJson.has("jvmArguments")) {
            JsonArray arr = versionJson.getAsJsonArray("jvmArguments");
            for (JsonElement el : arr) {
                String arg = replaceVariables(el.getAsString());
                if (!arg.isEmpty()) {
                    args.add(arg);
                }
            }
        }

        return args;
    }

    private List<String> parseGameArguments(JsonObject versionJson) {
        List<String> args = new ArrayList<>();

        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("game")) {
            JsonArray gameArray = versionJson.getAsJsonObject("arguments").getAsJsonArray("game");
            for (JsonElement el : gameArray) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString());
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject argObj = el.getAsJsonObject();
                    if (evaluateRules(argObj)) {
                        parseArgumentValue(argObj, args);
                    }
                }
            }
        }

        if (args.isEmpty() && versionJson.has("minecraftArguments")) {
            String[] rawArgs = versionJson.get("minecraftArguments").getAsString().trim().split("\\s+");
            for (String arg : rawArgs) {
                String replaced = replaceVariables(arg);
                if (!replaced.isEmpty()) {
                    args.add(replaced);
                }
            }
        }

        return args;
    }

    private void parseArgumentValue(JsonObject argObj, List<String> target) {
        if (!argObj.has("value")) {
            return;
        }

        JsonElement valueEl = argObj.get("value");
        if (valueEl.isJsonPrimitive()) {
            String arg = replaceVariables(valueEl.getAsString());
            if (!arg.isEmpty()) {
                target.add(arg);
            }
        } else if (valueEl.isJsonArray()) {
            for (JsonElement el : valueEl.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    String arg = replaceVariables(el.getAsString());
                    if (!arg.isEmpty()) {
                        target.add(arg);
                    }
                }
            }
        }
    }

    private boolean evaluateRules(JsonObject argObj) {
        if (!argObj.has("rules")) {
            return true;
        }

        JsonArray rules = argObj.getAsJsonArray("rules");
        boolean allowed = rules.isEmpty();

        for (JsonElement ruleEl : rules) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean conditionsMet = true;

            if (rule.has("os")) {
                conditionsMet &= evaluateOsCondition(rule.getAsJsonObject("os"));
            }
            if (rule.has("features")) {
                conditionsMet &= evaluateFeaturesCondition(rule.getAsJsonObject("features"));
            }

            if ("allow".equals(action) && conditionsMet) {
                allowed = true;
            }
            if ("disallow".equals(action) && conditionsMet) {
                allowed = false;
            }
        }

        return allowed;
    }

    private boolean evaluateOsCondition(JsonObject osCondition) {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        boolean match = true;

        if (osCondition.has("name")) {
            String name = osCondition.get("name").getAsString();
            if (name.equals("windows") && !osName.contains("win")) {
                match = false;
            }
            if (name.equals("osx") && !osName.contains("mac")) {
                match = false;
            }
            if (name.equals("linux") && !osName.contains("linux")) {
                match = false;
            }
        }

        if (osCondition.has("arch")) {
            String arch = osCondition.get("arch").getAsString();
            if (arch.equals("x86") && osArch.contains("64")) {
                match = false;
            }
            if (arch.equals("x86_64") && !osArch.contains("64")) {
                match = false;
            }
        }

        if (osCondition.has("version")) {
            String versionPattern = osCondition.get("version").getAsString();
            if (versionPattern.startsWith("^") && !System.getProperty("os.version", "").startsWith(versionPattern.substring(1))) {
                match = false;
            }
        }

        return match;
    }

    private boolean evaluateFeaturesCondition(JsonObject featuresCondition) {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);

        for (String key : featuresCondition.keySet()) {
            boolean expected = featuresCondition.get(key).getAsBoolean();
            boolean actual = features.getOrDefault(key, false);
            if (actual != expected) {
                return false;
            }
        }
        return true;
    }

    private String replaceVariables(String arg) {
        Map<String, String> vars = new HashMap<>();
        vars.put("${auth_player_name}", auth.getUsername());
        vars.put("${auth_session}", auth.getAccessToken());
        vars.put("${auth_uuid}", auth.getUUID());
        vars.put("${auth_access_token}", auth.getAccessToken());
        vars.put("${version_name}", versionId);
        vars.put("${version_type}", ECLConfig.LAUNCHER_NAME);
        vars.put("${game_directory}", gameDir.getAbsolutePath());
        vars.put("${assets_root}", ECLConfig.getAssetsDir().getAbsolutePath());
        vars.put("${assets_index_name}", getAssetIndexName());
        vars.put("${user_type}", auth.getType().name().toLowerCase());
        vars.put("${natives_directory}", new File(ECLConfig.getVersionsDir(), versionId + "/natives").getAbsolutePath());
        vars.put("${library_directory}", ECLConfig.getLibrariesDir().getAbsolutePath());
        vars.put("${classpath_separator}", File.pathSeparator);
        vars.put("${launcher_name}", ECLConfig.LAUNCHER_NAME);
        vars.put("${launcher_version}", ECLConfig.LAUNCHER_VERSION);

        for (Map.Entry<String, String> entry : vars.entrySet()) {
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
                if (lib.has("rules") && !checkRules(lib.getAsJsonArray("rules"))) {
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

        File clientJar = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".jar");
        if (clientJar.exists()) {
            classpath.add(clientJar.getAbsolutePath());
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

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("downloads")) {
                continue;
            }

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("classifiers")) {
                continue;
            }

            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            String[] nativeKeys = {
                    "natives-" + nativeClassifier.split("-")[0],
                    nativeClassifier,
                    nativeClassifier.split("-")[0]
            };
            for (String key : nativeKeys) {
                if (classifiers.has(key)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(key);
                    if (nativeArtifact.has("path")) {
                        String path = nativeArtifact.get("path").getAsString();
                        File nativeFile = new File(ECLConfig.getLibrariesDir(), path);
                        if (nativeFile.exists()) {
                            extractJar(nativeFile, nativesDir);
                        }
                    }
                    break;
                }
            }
        }
    }

    private void extractJar(File jarFile, File targetDir) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().contains("META-INF") || entry.isDirectory()) {
                    continue;
                }

                File outFile = new File(targetDir, entry.getName());
                if (outFile.exists()) {
                    continue;
                }
                outFile.getParentFile().mkdirs();

                try (InputStream is = jar.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private boolean checkRules(JsonArray rules) {
        boolean allowed = rules.isEmpty();
        String osName = System.getProperty("os.name").toLowerCase();

        for (JsonElement ruleEl : rules) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean osMatch = true;

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                String name = os.has("name") ? os.get("name").getAsString() : "";
                if (name.equals("windows") && !osName.contains("win")) {
                    osMatch = false;
                }
                if (name.equals("osx") && !osName.contains("mac")) {
                    osMatch = false;
                }
                if (name.equals("linux") && !osName.contains("linux")) {
                    osMatch = false;
                }
            }

            if ("allow".equals(action) && osMatch) {
                allowed = true;
            }
            if ("disallow".equals(action) && osMatch) {
                allowed = false;
            }
        }
        return allowed;
    }

    private JsonObject loadVersionJsonWithInheritance() throws IOException {
        File baseFile = new File(ECLConfig.getVersionsDir(), versionId + "/" + versionId + ".json");
        if (!baseFile.exists()) {
            return null;
        }

        JsonObject versionJson = HttpUtil.readJson(baseFile);
        if (versionJson.has("inheritsFrom")) {
            String parentId = versionJson.get("inheritsFrom").getAsString();
            JsonObject parentJson = loadVersionJsonWithInheritance(parentId);
            if (parentJson != null) {
                versionJson = mergeJson(parentJson, versionJson);
            }
        }

        return versionJson;
    }

    private JsonObject loadVersionJsonWithInheritance(String vid) throws IOException {
        File file = new File(ECLConfig.getVersionsDir(), vid + "/" + vid + ".json");
        if (!file.exists()) {
            return null;
        }
        return HttpUtil.readJson(file);
    }

    private JsonObject mergeJson(JsonObject base, JsonObject override) {
        JsonObject result = new JsonObject();
        for (String key : base.keySet()) {
            result.add(key, base.get(key));
        }
        for (String key : override.keySet()) {
            if (!"inheritsFrom".equals(key)) {
                result.add(key, override.get(key));
            }
        }
        return result;
    }

    private String getAssetIndexName() {
        try {
            JsonObject versionJson = loadVersionJsonWithInheritance();
            if (versionJson != null && versionJson.has("assetIndex")) {
                return versionJson.getAsJsonObject("assetIndex").get("id").getAsString();
            }
        } catch (IOException ignored) {
        }
        return versionId;
    }
}