package com.ecl.game.companion;

import com.ecl.game.DefaultGameRepository;
import com.ecl.game.WorldSave;
import com.ecl.util.FileUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Detects the exact Fabric mod and bridge protocol in the instance that owns a save. */
public final class CompanionBridgeDetector {
    public static final String MOD_ID = "minecraft-ai-companion";
    public static final int CURRENT_PROTOCOL_VERSION = CompanionTask.CURRENT_SCHEMA_VERSION;
    private static final long MAX_MANIFEST_BYTES = 1_048_576;

    public CompanionBridgeState detect(WorldSave save, DefaultGameRepository repository) {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(repository, "repository");
        if (!"fabric".equalsIgnoreCase(save.modLoader())) {
            return new CompanionBridgeState(CompanionBridgeState.Status.INCOMPATIBLE, "", 0,
                    null, "", "AI Companion 桥接需要 Fabric 实例", save.sharedDirectory());
        }
        try {
            Path runDirectory = repository.runDirectory(save.instanceId());
            CompanionBridgeState detected = detect(runDirectory, save.minecraftVersion(),
                    save.modLoaderVersion(), save.sharedDirectory());
            return withBinding(detected, new CompanionTaskStore(save.directory()));
        } catch (IOException | RuntimeException error) {
            return new CompanionBridgeState(CompanionBridgeState.Status.NOT_INSTALLED, "", 0,
                    null, "", "无法读取实例模组目录: " + safeMessage(error), save.sharedDirectory());
        }
    }

    public CompanionBridgeState detect(Path runDirectory, String minecraftVersion,
                                        String fabricLoaderVersion, boolean sharedDirectory) {
        if (runDirectory == null || !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return new CompanionBridgeState(CompanionBridgeState.Status.NOT_INSTALLED, "", 0,
                    null, "", "未找到实例目录", sharedDirectory);
        }
        Path mods = runDirectory.toAbsolutePath().normalize().resolve("mods");
        try {
            FileUtil.validateExistingAncestors(runDirectory, mods);
        } catch (IOException unsafe) {
            return new CompanionBridgeState(CompanionBridgeState.Status.INCOMPATIBLE, "", 0,
                    null, "", "mods 路径不安全", sharedDirectory);
        }
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            return new CompanionBridgeState(CompanionBridgeState.Status.NOT_INSTALLED, "", 0,
                    null, "", "未安装 Minecraft AI Companion", sharedDirectory);
        }
        boolean found = false;
        try (Stream<Path> files = Files.list(mods)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path)).toList()) {
                Manifest manifest = readManifest(file);
                if (manifest == null || !MOD_ID.equals(manifest.id())) {
                    continue;
                }
                found = true;
                if (manifest.protocolVersion() != CURRENT_PROTOCOL_VERSION
                        || !compatibleMinecraft(manifest.minecraftRequirement(), minecraftVersion)
                        || !manifest.hasFabricLoaderDependency()
                        || !compatibleMinecraft(manifest.fabricLoaderRequirement(), fabricLoaderVersion)) {
                    return new CompanionBridgeState(CompanionBridgeState.Status.INCOMPATIBLE,
                            manifest.version(), manifest.protocolVersion(), null, "",
                            "Minecraft AI Companion 或桥接协议版本不兼容", sharedDirectory);
                }
                return new CompanionBridgeState(CompanionBridgeState.Status.INSTALLED,
                        manifest.version(), manifest.protocolVersion(), null, "",
                        "已安装，可加入离线任务", sharedDirectory);
            }
        } catch (IOException | RuntimeException error) {
            return new CompanionBridgeState(CompanionBridgeState.Status.INCOMPATIBLE, "", 0,
                    null, "", "无法读取模组清单: " + safeMessage(error), sharedDirectory);
        }
        return new CompanionBridgeState(found ? CompanionBridgeState.Status.INCOMPATIBLE
                : CompanionBridgeState.Status.NOT_INSTALLED, "", 0, null, "",
                found ? "模组清单无效" : "未安装 Minecraft AI Companion", sharedDirectory);
    }

    private static CompanionBridgeState withBinding(CompanionBridgeState state,
                                                     CompanionTaskStore store) {
        if (!state.canSubmit() || state.status() == CompanionBridgeState.Status.NOT_INSTALLED) {
            return state;
        }
        try {
            Path binding = store.bridgeDirectory().resolve("binding.json");
            if (!Files.isRegularFile(binding, LinkOption.NOFOLLOW_LINKS)) {
                return new CompanionBridgeState(CompanionBridgeState.Status.UNBOUND,
                        state.modVersion(), state.protocolVersion(), null, "",
                        state.sharedDirectory() ? "尚未绑定玩家；共享目录请先确认实际 Fabric 实例"
                                : "尚未绑定玩家；下次进入此存档时将绑定第一个本地玩家",
                        state.sharedDirectory());
            }
            JsonObject json = JsonParser.parseString(Files.readString(binding, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            UUID uuid = UUID.fromString(json.get("playerUuid").getAsString());
            String name = json.has("playerName") ? json.get("playerName").getAsString() : "";
            return new CompanionBridgeState(CompanionBridgeState.Status.INSTALLED,
                    state.modVersion(), state.protocolVersion(), uuid, name,
                    "已绑定玩家: " + name, state.sharedDirectory());
        } catch (IOException | RuntimeException invalid) {
            return new CompanionBridgeState(CompanionBridgeState.Status.UNBOUND,
                    state.modVersion(), state.protocolVersion(), null, "",
                    "玩家绑定文件损坏，将在下次进入时重新绑定", state.sharedDirectory());
        }
    }

    private static Manifest readManifest(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > MAX_MANIFEST_BYTES) {
                return null;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    return null;
                }
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject custom = object(json, "custom");
                JsonObject depends = object(json, "depends");
                int protocol = custom == null || !custom.has("eclBridgeProtocol")
                        ? 0 : custom.get("eclBridgeProtocol").getAsInt();
                String minecraft = depends == null || !depends.has("minecraft")
                        ? "" : depends.get("minecraft").getAsString();
                String fabricLoader = depends == null || !depends.has("fabricloader")
                        ? "" : depends.get("fabricloader").getAsString();
                return new Manifest(string(json, "id"), string(json, "version"), protocol,
                        minecraft, fabricLoader, depends != null && depends.has("fabricloader"));
            }
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean compatibleMinecraft(String requirement, String actual) {
        if (requirement == null || requirement.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        String normalizedRequirement = requirement.trim();
        String normalizedActual = actual.trim();
        if (normalizedRequirement.equals("*")) {
            return true;
        }
        if (normalizedRequirement.startsWith("~")) {
            String prefix = normalizedRequirement.substring(1);
            int lastDot = prefix.lastIndexOf('.');
            return lastDot > 0 && normalizedActual.startsWith(prefix.substring(0, lastDot + 1));
        }
        if (normalizedRequirement.startsWith(">=")) {
            return compareVersions(normalizedActual, normalizedRequirement.substring(2)) >= 0;
        }
        return normalizedActual.equals(normalizedRequirement);
    }

    private static int compareVersions(String left, String right) {
        String[] l = left.replaceAll("[^0-9.]", "").split("\\.");
        String[] r = right.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(l.length, r.length); i++) {
            int lv = i < l.length && !l[i].isBlank() ? Integer.parseInt(l[i]) : 0;
            int rv = i < r.length && !r[i].isBlank() ? Integer.parseInt(r[i]) : 0;
            if (lv != rv) return Integer.compare(lv, rv);
        }
        return 0;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Manifest(String id, String version, int protocolVersion,
                            String minecraftRequirement, String fabricLoaderRequirement,
                            boolean hasFabricLoaderDependency) {
    }
}
