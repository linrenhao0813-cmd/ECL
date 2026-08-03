package com.ecl.modrinth;

import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static TestInstance instance(Path gameDirectory) {
        return new TestInstance(UUID.randomUUID(), "1.21.1-fabric", "1.21.1",
                ModLoader.FABRIC, gameDirectory);
    }

    public static ModFile file(String name, boolean primary) {
        return new ModFile(URI.create("https://example.invalid/" + name), name,
                Map.of("sha1", "01", "sha512", "02"), primary, 128, "required-resource");
    }

    public static ModVersion version(String id, String projectId, String type, boolean featured,
                                     List<String> games, List<String> loaders, Instant published,
                                     List<ModFile> files, List<ModDependency> dependencies) {
        return new ModVersion(id, projectId, projectId, id, type, featured, "listed",
                games, loaders, published, "", files, dependencies);
    }

    public static ModVersion fabricVersion(String id, String projectId, List<ModDependency> dependencies) {
        return version(id, projectId, "release", false, List.of("1.21.1"), List.of("fabric"),
                Instant.parse("2026-01-01T00:00:00Z"), List.of(file(projectId + ".jar", true)), dependencies);
    }

    public static void createJar(Path file, String id) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\"}").getBytes());
            output.closeEntry();
        }
    }

    public record TestInstance(UUID instanceId, String profileId, String minecraftVersion,
                               ModLoader loader, Path gameDirectory) implements ModInstanceContext {
        @Override
        public Path gameDirectory() {
            return gameDirectory.toAbsolutePath().normalize();
        }

        @Override
        public Path modsDirectory() {
            return gameDirectory().resolve("mods");
        }
    }

    public static final class FakeApi implements ModrinthApiClient {
        public final Map<String, ModVersion> versions = new HashMap<>();
        public final Map<String, List<ModVersion>> projectVersions = new HashMap<>();
        public final Map<String, ModVersion> hashes = new HashMap<>();
        public int hashLookups;

        @Override
        public CompletableFuture<ModSearchResult> searchMods(ModSearchQuery query) {
            return CompletableFuture.completedFuture(new ModSearchResult(List.of(), 0, 0, query.limit()));
        }

        @Override
        public CompletableFuture<ModProject> getProject(String projectIdOrSlug) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<ModVersion> getVersion(String versionId) {
            ModVersion version = versions.get(versionId);
            return version == null
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("missing " + versionId))
                    : CompletableFuture.completedFuture(version);
        }

        @Override
        public CompletableFuture<List<ModVersion>> getProjectVersions(
                String projectId, String minecraftVersion, String loader) {
            return CompletableFuture.completedFuture(projectVersions.getOrDefault(projectId, List.of()));
        }

        @Override
        public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
                Collection<String> requestedHashes, String algorithm) {
            hashLookups++;
            Map<String, ModVersion> result = new HashMap<>();
            requestedHashes.forEach(hash -> {
                if (hashes.containsKey(hash)) {
                    result.put(hash, hashes.get(hash));
                }
            });
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
                Collection<String> hashes, String algorithm, List<String> loaders, List<String> gameVersions) {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public void close() {
        }
    }
}
