package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModLoader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads Fabric, Quilt, Forge, and NeoForge metadata from a local mod JAR. */
final class LocalModMetadataReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalModMetadataReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long MAX_METADATA_BYTES = 1024 * 1024;
    private static final List<String> METADATA_FILES = List.of(
            "fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml", "mcmod.info");
    private static final Pattern TOML_ID = tomlValue("modId");
    private static final Pattern TOML_NAME = tomlValue("displayName");
    private static final Pattern TOML_VERSION = tomlValue("version");

    private LocalModMetadataReader() {
    }

    static Inspection inspectJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            for (String metadataName : METADATA_FILES) {
                JarEntry entry = jar.getJarEntry(metadataName);
                if (entry == null) {
                    continue;
                }
                if (entry.getSize() > MAX_METADATA_BYTES) {
                    return new Inspection("模组元数据超过安全读取上限", LocalModMeta.unknown());
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    byte[] content = input.readNBytes((int) MAX_METADATA_BYTES + 1);
                    if (content.length > MAX_METADATA_BYTES) {
                        return new Inspection("模组元数据解压后超过安全读取上限",
                                LocalModMeta.unknown());
                    }
                    return new Inspection(null, parseMetadata(metadataName,
                            new String(content, StandardCharsets.UTF_8)));
                }
            }
            return new Inspection(null, LocalModMeta.unknown());
        } catch (IOException error) {
            return new Inspection("JAR 损坏或无法读取: " + error.getMessage(), LocalModMeta.unknown());
        } catch (RuntimeException invalidMetadata) {
            LOGGER.debug("Cannot parse local mod metadata from {}", file, invalidMetadata);
            return new Inspection(null, LocalModMeta.unknown());
        }
    }

    private static LocalModMeta parseMetadata(String entryName, String content) throws IOException {
        return switch (entryName) {
            case "fabric.mod.json" -> jsonMetadata(content, ModLoader.FABRIC, false);
            case "quilt.mod.json" -> jsonMetadata(content, ModLoader.QUILT, true);
            case "META-INF/mods.toml" -> tomlMetadata(content, ModLoader.FORGE);
            case "META-INF/neoforge.mods.toml" -> tomlMetadata(content, ModLoader.NEOFORGE);
            case "mcmod.info" -> legacyForgeMetadata(content);
            default -> LocalModMeta.unknown();
        };
    }

    private static LocalModMeta jsonMetadata(String content, ModLoader loader, boolean quilt)
            throws IOException {
        JsonNode root = MAPPER.readTree(content);
        JsonNode identity = quilt ? root.path("quilt_loader") : root;
        JsonNode metadata = quilt ? identity.path("metadata") : root;
        String id = text(identity.path("id"));
        String name = text(metadata.path("name"));
        String version = text(identity.path("version"));
        return new LocalModMeta(id, firstNonBlank(name, id), cleanVersion(version), loader, true);
    }

    private static LocalModMeta legacyForgeMetadata(String content) throws IOException {
        JsonNode root = MAPPER.readTree(content);
        JsonNode mod = root.isArray() && !root.isEmpty() ? root.get(0) : root;
        String id = text(mod.path("modid"));
        String name = text(mod.path("name"));
        return new LocalModMeta(id, firstNonBlank(name, id),
                cleanVersion(text(mod.path("version"))), ModLoader.FORGE, true);
    }

    private static LocalModMeta tomlMetadata(String content, ModLoader loader) {
        String id = match(TOML_ID, content);
        String name = match(TOML_NAME, content);
        String version = cleanVersion(match(TOML_VERSION, content));
        return new LocalModMeta(id, firstNonBlank(name, id), version, loader, true);
    }

    private static Pattern tomlValue(String key) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + "\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String text(JsonNode node) {
        return node == null || !node.isValueNode() ? "" : node.asText("").trim();
    }

    private static String cleanVersion(String version) {
        return version != null && version.contains("${") ? "" : firstNonBlank(version, "");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    record Inspection(String damage, LocalModMeta metadata) {
    }
}
