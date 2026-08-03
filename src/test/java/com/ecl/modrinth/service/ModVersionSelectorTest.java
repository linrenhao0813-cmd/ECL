package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.model.ModCompatibility;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModVersionSelectorTest {
    private final DefaultModVersionSelector selector = new DefaultModVersionSelector();
    private final ModCompatibility fabric = new ModCompatibility("1.21.1", ModLoader.FABRIC);

    @Test
    void requiresExactMinecraftVersionAndLoader() {
        ModVersion wrongGame = version("wrong-game", "release", false, "1.21", "fabric", file("a.jar", true));
        ModVersion wrongLoader = version("wrong-loader", "release", false, "1.21.1", "forge", file("b.jar", true));
        ModVersion exact = version("exact", "release", false, "1.21.1", "fabric", file("c.jar", true));
        assertEquals("exact", selector.selectBestVersion(
                List.of(wrongGame, wrongLoader, exact), fabric, ReleaseChannel.ALL).orElseThrow().id());
        assertTrue(selector.selectBestVersion(List.of(wrongGame, wrongLoader), fabric, ReleaseChannel.ALL).isEmpty());
    }

    @Test
    void supportsExactForgeMatch() {
        ModCompatibility forge = new ModCompatibility("1.21.1", ModLoader.FORGE);
        assertEquals("forge", selector.selectBestVersion(List.of(
                version("fabric", "release", false, "1.21.1", "fabric", file("f.jar", true)),
                version("forge", "release", false, "1.21.1", "forge", file("g.jar", true))),
                forge, ReleaseChannel.RELEASE_ONLY).orElseThrow().id());
    }

    @Test
    void releaseHasPriorityAndChannelSwitchesWork() {
        ModVersion release = version("release", "release", false, "1.21.1", "fabric", file("r.jar", true));
        ModVersion beta = version("beta", "beta", false, "1.21.1", "fabric", file("b.jar", true));
        ModVersion alpha = version("alpha", "alpha", false, "1.21.1", "fabric", file("a.jar", true));
        assertEquals("release", selector.selectBestVersion(
                List.of(alpha, beta, release), fabric, ReleaseChannel.ALL).orElseThrow().id());
        assertTrue(selector.selectBestVersion(List.of(beta), fabric, ReleaseChannel.RELEASE_ONLY).isEmpty());
        assertEquals("beta", selector.selectBestVersion(
                List.of(beta), fabric, ReleaseChannel.RELEASE_AND_BETA).orElseThrow().id());
        assertTrue(selector.selectBestVersion(
                List.of(alpha), fabric, ReleaseChannel.RELEASE_AND_BETA).isEmpty());
        assertEquals("alpha", selector.selectBestVersion(
                List.of(alpha), fabric, ReleaseChannel.ALL).orElseThrow().id());
    }

    @Test
    void featuredThenNewestWinsWithinSameChannel() {
        ModVersion oldFeatured = version("featured", "release", true, "1.21.1", "fabric",
                file("f.jar", true), Instant.parse("2025-01-01T00:00:00Z"));
        ModVersion newPlain = version("new", "release", false, "1.21.1", "fabric",
                file("n.jar", true), Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals("featured", selector.selectBestVersion(
                List.of(newPlain, oldFeatured), fabric, ReleaseChannel.ALL).orElseThrow().id());
    }

    @Test
    void selectsPrimaryAndExcludesDevelopmentFiles() {
        ModFile sources = file("example-sources.jar", true);
        ModFile runtime = file("example.jar", false);
        ModVersion version = version("files", "release", false, "1.21.1", "fabric", sources, runtime);
        assertEquals("example.jar", selector.selectInstallFile(version).orElseThrow().fileName());
        assertTrue(selector.selectBestVersion(List.of(
                version("only-sources", "release", false, "1.21.1", "fabric", sources)),
                fabric, ReleaseChannel.ALL).isEmpty());
    }

    @Test
    void acceptsLegacyMissingStatusButRejectsUnlistedAndUnknownStatuses() {
        ModVersion listed = withStatus(version(
                "listed", "release", false, "1.21.1", "fabric", file("listed.jar", true)), "listed");
        ModVersion missing = withStatus(version(
                "missing", "release", false, "1.21.1", "fabric", file("missing.jar", true)), null);
        ModVersion blank = withStatus(version(
                "blank", "release", false, "1.21.1", "fabric", file("blank.jar", true)), " ");
        ModVersion unlisted = withStatus(version(
                "unlisted", "release", false, "1.21.1", "fabric", file("unlisted.jar", true)), "unlisted");
        ModVersion futureUnknown = withStatus(version(
                "future", "release", false, "1.21.1", "fabric", file("future.jar", true)), "approved");

        assertEquals("listed", selector.selectBestVersion(
                List.of(listed), fabric, ReleaseChannel.RELEASE_ONLY).orElseThrow().id());
        assertEquals("missing", selector.selectBestVersion(
                List.of(missing), fabric, ReleaseChannel.RELEASE_ONLY).orElseThrow().id());
        assertEquals("blank", selector.selectBestVersion(
                List.of(blank), fabric, ReleaseChannel.RELEASE_ONLY).orElseThrow().id());
        assertTrue(selector.selectBestVersion(
                List.of(unlisted), fabric, ReleaseChannel.RELEASE_ONLY).isEmpty());
        assertTrue(selector.selectBestVersion(
                List.of(futureUnknown), fabric, ReleaseChannel.RELEASE_ONLY).isEmpty());
    }

    private static ModFile file(String name, boolean primary) {
        return TestFixtures.file(name, primary);
    }

    private static ModVersion version(String id, String type, boolean featured, String game, String loader,
                                      ModFile... files) {
        return version(id, type, featured, game, loader, files, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ModVersion version(String id, String type, boolean featured, String game, String loader,
                                      ModFile file, Instant published) {
        return version(id, type, featured, game, loader, new ModFile[]{file}, published);
    }

    private static ModVersion version(String id, String type, boolean featured, String game, String loader,
                                      ModFile[] files, Instant published) {
        return TestFixtures.version(id, id, type, featured, List.of(game), List.of(loader),
                published, List.of(files), List.of());
    }

    private static ModVersion withStatus(ModVersion version, String status) {
        return new ModVersion(
                version.id(), version.projectId(), version.name(), version.versionNumber(),
                version.versionType(), version.featured(), status, version.gameVersions(),
                version.loaders(), version.publishedAt(), version.changelog(),
                version.files(), version.dependencies());
    }
}
