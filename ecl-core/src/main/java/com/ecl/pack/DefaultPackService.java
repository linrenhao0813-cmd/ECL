package com.ecl.pack;

import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;
import com.ecl.util.ZipUtil;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Safe, transactional import/export for ECL, MultiMC, CurseForge and Modrinth archives. */
public final class DefaultPackService implements PackService {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private final MrpackInstaller mrpackInstaller;

    public DefaultPackService() {
        this(new MrpackInstaller());
    }

    DefaultPackService(MrpackInstaller mrpackInstaller) {
        this.mrpackInstaller = Objects.requireNonNull(mrpackInstaller, "mrpackInstaller");
    }

    @Override
    public PackPreview preview(Path archive) throws IOException {
        Path source = requireArchive(archive);
        ZipUtil.validateArchive(source, ZipUtil.DEFAULT_EXTRACTION_LIMITS);
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            PackFormat format = detect(zip);
            JsonObject manifest = manifest(zip, format);
            String name = text(manifest, "name", stripExtension(source.getFileName().toString()));
            String version = minecraftVersion(manifest, format);
            int files = (int) zip.stream().filter(entry -> !entry.isDirectory()).count();
            List<String> warnings = new ArrayList<>();
            if (version.isBlank()) warnings.add("整合包未声明 Minecraft 版本");
            return new PackPreview(format, name, version, files, Files.size(source), warnings);
        }
    }

    @Override
    public PackImportResult importPack(Path archive, Path instancesRoot, String preferredName)
            throws IOException {
        PackPreview preview = preview(archive);
        Path root = Objects.requireNonNull(instancesRoot, "instancesRoot").toAbsolutePath().normalize();
        Files.createDirectories(root);
        String name = safeName(preferredName == null || preferredName.isBlank()
                ? preview.name() : preferredName);
        Path target = root.resolve(name).normalize();
        if (!target.getParent().equals(root) || Files.exists(target)) {
            throw new IOException("目标实例已存在或名称无效: " + name);
        }
        Path staging = Files.createTempDirectory(root, ".ecl-pack-");
        try {
            if (preview.format() == PackFormat.MRPACK) {
                Path payload = staging.resolve("instance");
                int installed = mrpackInstaller.installContents(archive.toFile(), payload, null);
                move(payload, target);
                return new PackImportResult(preview.format(), name, target, installed);
            }
            rejectUnsupportedRemoteManifest(archive, preview.format());
            List<ZipUtil.ArchivedFile> files = ZipUtil.extractSafely(archive, staging, null);
            Path payload = switch (preview.format()) {
                case ECL -> staging.resolve("instance");
                case MULTIMC -> Files.isDirectory(staging.resolve(".minecraft"))
                        ? staging.resolve(".minecraft") : staging.resolve("minecraft");
                case MRPACK, CURSEFORGE -> staging.resolve("overrides");
            };
            if (!Files.isDirectory(payload)) {
                throw new IOException("整合包缺少实例内容目录: " + payload.getFileName());
            }
            move(payload, target);
            return new PackImportResult(preview.format(), name, target, files.size());
        } finally {
            if (Files.exists(staging)) FileUtil.deleteDirectory(staging);
        }
    }

    private static void rejectUnsupportedRemoteManifest(Path archive, PackFormat format) throws IOException {
        if (format != PackFormat.CURSEFORGE) return;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            JsonObject value = manifest(zip, format);
            if (value.has("files") && value.get("files").isJsonArray()
                    && !value.getAsJsonArray("files").isEmpty()) {
                throw new IOException("CurseForge packs with remote file entries are not supported; "
                        + "the import was not changed");
            }
        }
    }

    @Override
    public Path exportInstance(Path instanceDirectory, String minecraftVersion,
                               PackFormat format, Path output) throws IOException {
        Path source = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(source) || Files.isSymbolicLink(source)) {
            throw new IOException("实例目录不可用: " + source);
        }
        Path archive = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        if (archive.getParent() != null) Files.createDirectories(archive.getParent());
        PackFormat effective = format == null ? PackFormat.ECL : format;
        String prefix = switch (effective) {
            case ECL -> "instance/";
            case MULTIMC -> "minecraft/";
            case MRPACK, CURSEFORGE -> "overrides/";
        };
        try (OutputStream raw = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            writeManifest(zip, source.getFileName().toString(), minecraftVersion, effective);
            addTree(zip, source, prefix, archive);
        } catch (IOException failure) {
            Files.deleteIfExists(archive);
            throw failure;
        }
        return archive;
    }

    private static void writeManifest(ZipOutputStream zip, String name, String version,
                                      PackFormat format) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("name", name);
        switch (format) {
            case ECL -> {
                manifest.addProperty("formatVersion", 1);
                manifest.addProperty("minecraftVersion", version);
                putJson(zip, "ecl-pack.json", manifest);
            }
            case MULTIMC -> {
                JsonObject component = new JsonObject();
                component.addProperty("uid", "net.minecraft");
                component.addProperty("version", version);
                var components = new com.google.gson.JsonArray();
                components.add(component);
                manifest.addProperty("formatVersion", 1);
                manifest.add("components", components);
                putJson(zip, "mmc-pack.json", manifest);
                putText(zip, "instance.cfg", "name=" + name + "\n");
            }
            case MRPACK -> {
                manifest.addProperty("formatVersion", 1);
                manifest.addProperty("game", "minecraft");
                manifest.addProperty("versionId", "1.0.0");
                manifest.addProperty("summary", "Exported by ECL");
                manifest.add("files", new com.google.gson.JsonArray());
                JsonObject dependencies = new JsonObject();
                dependencies.addProperty("minecraft", version);
                manifest.add("dependencies", dependencies);
                putJson(zip, "modrinth.index.json", manifest);
            }
            case CURSEFORGE -> {
                manifest.addProperty("manifestType", "minecraftModpack");
                manifest.addProperty("manifestVersion", 1);
                manifest.addProperty("version", "1.0.0");
                manifest.addProperty("author", "ECL");
                JsonObject minecraft = new JsonObject();
                minecraft.addProperty("version", version);
                minecraft.add("modLoaders", new com.google.gson.JsonArray());
                manifest.add("minecraft", minecraft);
                manifest.add("files", new com.google.gson.JsonArray());
                manifest.addProperty("overrides", "overrides");
                putJson(zip, "manifest.json", manifest);
            }
        }
    }

    private static void addTree(ZipOutputStream zip, Path source, String prefix, Path archive)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("不支持符号链接: " + dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file) || file.equals(archive)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = prefix + source.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static PackFormat detect(ZipFile zip) throws IOException {
        if (zip.getEntry("modrinth.index.json") != null) return PackFormat.MRPACK;
        if (zip.getEntry("manifest.json") != null) return PackFormat.CURSEFORGE;
        if (zip.getEntry("mmc-pack.json") != null) return PackFormat.MULTIMC;
        if (zip.getEntry("ecl-pack.json") != null) return PackFormat.ECL;
        throw new IOException("无法识别整合包格式");
    }

    private static JsonObject manifest(ZipFile zip, PackFormat format) throws IOException {
        String name = switch (format) {
            case MRPACK -> "modrinth.index.json";
            case CURSEFORGE -> "manifest.json";
            case MULTIMC -> "mmc-pack.json";
            case ECL -> "ecl-pack.json";
        };
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > MAX_MANIFEST_BYTES) {
            throw new IOException("Pack manifest is missing or exceeds the safety limit: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Pack manifest exceeds the safety limit: " + name);
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("整合包清单损坏: " + name, failure);
        }
    }

    private static String minecraftVersion(JsonObject manifest, PackFormat format) {
        if (format == PackFormat.ECL) return text(manifest, "minecraftVersion", "");
        if (format == PackFormat.MRPACK && manifest.has("dependencies")) {
            return text(manifest.getAsJsonObject("dependencies"), "minecraft", "");
        }
        if (format == PackFormat.CURSEFORGE && manifest.has("minecraft")) {
            return text(manifest.getAsJsonObject("minecraft"), "version", "");
        }
        if (format == PackFormat.MULTIMC && manifest.has("components")) {
            for (var element : manifest.getAsJsonArray("components")) {
                JsonObject component = element.getAsJsonObject();
                if ("net.minecraft".equals(text(component, "uid", ""))) {
                    return text(component, "version", "");
                }
            }
        }
        return "";
    }

    private static void putJson(ZipOutputStream zip, String name, JsonObject value) throws IOException {
        putText(zip, name, GsonProvider.pretty().toJson(value));
    }

    private static void putText(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static Path requireArchive(Path archive) throws IOException {
        Path result = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(result)) throw new IOException("整合包文件不存在: " + result);
        return result;
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static String safeName(String name) throws IOException {
        String value = name == null ? "" : name.trim();
        FileUtil.requireSafeVersionId(value);
        return value;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
