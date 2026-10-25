package com.ecl.download;

import com.ecl.download.GameInstallationVerifier.Category;
import com.ecl.download.GameInstallationVerifier.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameInstallationVerifierTest {
    @TempDir Path temp;

    @Test
    void verifiesInheritedClientLibrariesAssetsAndLoaderDependencies() throws Exception {
        Fixture fixture = createInstallation();

        GameInstallationVerifier.Report report = fixture.verifier().inspect("fabric-profile");

        assertTrue(report.complete(), report.checks().toString());
        assertTrue(report.count(Status.VERIFIED) >= 6);
        assertTrue(report.checks().stream().anyMatch(check ->
                check.category() == Category.LOADER_DEPENDENCY && check.status() == Status.VERIFIED));
        assertTrue(report.checks().stream().anyMatch(check ->
                check.category() == Category.CLIENT && check.status() == Status.VERIFIED));
        assertFalse(report.fingerprint().isBlank());
    }

    @Test
    void distinguishesMissingCorruptAndUnverifiableFiles() throws Exception {
        Fixture fixture = createInstallation();
        Files.writeString(fixture.library(), "corrupt");
        Files.delete(fixture.asset());
        Path unverifiedLibrary = fixture.libraries().resolve("custom/custom/1/custom-1.jar");
        Files.createDirectories(unverifiedLibrary.getParent());
        Files.writeString(unverifiedLibrary, "present but unverifiable");
        var child = com.google.gson.JsonParser.parseString(Files.readString(fixture.profile()))
                .getAsJsonObject();
        child.add("libraries", new com.google.gson.JsonArray());
        child.getAsJsonArray("libraries").add(com.google.gson.JsonParser.parseString(
                "{\"name\":\"custom:custom:1\",\"url\":\"https://repo.example/\"}"));
        Files.writeString(fixture.profile(), child.toString());

        GameInstallationVerifier.Report report = fixture.verifier().inspect("fabric-profile");

        assertEquals(1, report.count(Status.CORRUPT));
        assertTrue(report.count(Status.MISSING) >= 1, report.checks().toString());
        assertTrue(report.count(Status.UNVERIFIABLE) >= 1);
        assertFalse(report.complete());
    }

    @Test
    void reportsTheLegacyClientLoggingConfigAsItsOwnCategory() throws Exception {
        Fixture fixture = createInstallation();

        GameInstallationVerifier.Report healthy = fixture.verifier().inspect("fabric-profile");
        assertEquals(0, healthy.checks().stream()
                .filter(check -> check.category() == Category.LOGGING).count(),
                "versions without a logging block must not gain a LOGGING check");

        byte[] config = "log-config".getBytes(StandardCharsets.UTF_8);
        String configHash = sha1(config);
        var profile = com.google.gson.JsonParser.parseString(Files.readString(fixture.profile()))
                .getAsJsonObject();
        profile.add("logging", com.google.gson.JsonParser.parseString("""
                {"client":{"argument":"-Dlog4j.configurationFile=${path}",
                 "file":{"id":"client-1.7.2.xml","sha1":"%s","size":%d,
                  "url":"https://launcher.example/client-1.7.2.xml"}}}
                """.formatted(configHash, config.length)).getAsJsonObject());
        Files.writeString(fixture.profile(), profile.toString());

        GameInstallationVerifier.Report missing = fixture.verifier().inspect("fabric-profile");
        GameInstallationVerifier.Check missingConfig = missing.checks().stream()
                .filter(check -> check.category() == Category.LOGGING).findFirst()
                .orElseThrow(() -> new AssertionError(missing.checks().toString()));
        assertEquals(Status.MISSING, missingConfig.status());
        assertTrue(missingConfig.repairable());
        assertTrue(missingConfig.path().replace('\\', '/').endsWith("log_configs/client-1.7.2.xml"),
                missingConfig.path());

        Path configFile = fixture.assets().resolve("log_configs/client-1.7.2.xml");
        Files.createDirectories(configFile.getParent());
        Files.write(configFile, config);
        GameInstallationVerifier.Report verified = fixture.verifier().inspect("fabric-profile");
        assertEquals(Status.VERIFIED, verified.checks().stream()
                .filter(check -> check.category() == Category.LOGGING).findFirst().orElseThrow().status());

        Files.write(configFile, "tampered".getBytes(StandardCharsets.UTF_8));
        GameInstallationVerifier.Report corrupt = fixture.verifier().inspect("fabric-profile");
        GameInstallationVerifier.Check corruptConfig = corrupt.checks().stream()
                .filter(check -> check.category() == Category.LOGGING).findFirst().orElseThrow();
        assertEquals(Status.CORRUPT, corruptConfig.status());
        assertTrue(corruptConfig.repairable());

        Files.writeString(configFile, "different-size");
        profile.remove("logging");
        profile.add("logging", com.google.gson.JsonParser.parseString("""
                {"client":{"argument":"-Dlog4j.configurationFile=${path}",
                 "file":{"id":"client-1.7.2.xml","sha1":"not-a-sha1","size":0,
                  "url":"https://launcher.example/client-1.7.2.xml"}}}
                """).getAsJsonObject());
        Files.writeString(fixture.profile(), profile.toString());
        GameInstallationVerifier.Report unverifiable = fixture.verifier().inspect("fabric-profile");
        assertFalse(unverifiable.complete());
        assertTrue(unverifiable.checks().stream().anyMatch(check ->
                check.category() == Category.LOGGING && check.status() == Status.UNVERIFIABLE));
        assertTrue(unverifiable.checks().stream().anyMatch(check -> check.category() == Category.CLIENT));
    }

    @Test
    void malformedInheritanceIsReportedInsteadOfPassing() throws Exception {
        Path versions = temp.resolve("versions");
        Files.createDirectories(versions.resolve("bad-profile"));
        Files.writeString(versions.resolve("bad-profile/bad-profile.json"), "{invalid");
        GameInstallationVerifier verifier = new GameInstallationVerifier(
                versions, temp.resolve("libraries"), temp.resolve("assets"));

        GameInstallationVerifier.Report report = verifier.inspect("bad-profile");

        assertFalse(report.complete());
        assertEquals(1, report.count(Status.CORRUPT));
        assertEquals(Category.METADATA, report.issues().getFirst().category());
    }

    @Test
    void bareMavenMissingAndPresentAreNeverAutomaticallyRepairable() throws Exception {
        Fixture fixture = createInstallation();
        var child = com.google.gson.JsonParser.parseString(Files.readString(fixture.profile())).getAsJsonObject();
        child.add("libraries", com.google.gson.JsonParser.parseString("""
                [{"name":"net.fabricmc:fabric-loader:9","url":"https://maven.fabricmc.net/"}]
                """));
        Files.writeString(fixture.profile(), child.toString());
        var missing = fixture.verifier().inspect("fabric-profile");
        var check = missing.checks().stream().filter(c -> c.category() == Category.LOADER_DEPENDENCY).findFirst().orElseThrow();
        assertFalse(check.repairable(), check.toString());
        Files.createDirectories(Path.of(check.path()).getParent());
        Files.writeString(Path.of(check.path()), "local");
        assertFalse(fixture.verifier().inspect("fabric-profile").checks().stream()
                .anyMatch(c -> c.category() == Category.LOADER_DEPENDENCY && c.repairable()));
    }

    @Test
    void invalidLoggingDoesNotHideOtherDiagnostics() throws Exception {
        Fixture fixture = createInstallation();
        var child = com.google.gson.JsonParser.parseString(Files.readString(fixture.profile())).getAsJsonObject();
        child.add("logging", com.google.gson.JsonParser.parseString("""
                {"client":{"argument":"-javaagent:${path}","file":{}}}
                """));
        Files.writeString(fixture.profile(), child.toString());
        Files.delete(fixture.library());
        var report = fixture.verifier().inspect("fabric-profile");
        assertTrue(report.checks().stream().anyMatch(c -> c.category() == Category.LOGGING
                && c.status() == Status.UNVERIFIABLE && !c.repairable()));
        assertTrue(report.checks().stream().anyMatch(c -> c.category() == Category.CLIENT && c.status() == Status.VERIFIED));
        assertTrue(report.checks().stream().anyMatch(c -> c.category() == Category.LIBRARY && c.status() == Status.MISSING));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new com.ecl.game.VersionRepository(fixture.versions().toFile()).resolve("fabric-profile"));
    }

    @Test
    void localHintUsesInstanceLibraryDirectoryAndCannotRepairSharedFiles() throws Exception {
        Fixture fixture = createInstallation();
        var profile = com.google.gson.JsonParser.parseString(Files.readString(fixture.profile())).getAsJsonObject();
        var library = profile.getAsJsonArray("libraries").get(0).getAsJsonObject();
        library.addProperty("hint", "local");
        Path instances = temp.resolve("actual-game/versions");
        GameInstallationVerifier verifier = new GameInstallationVerifier(fixture.versions(), fixture.libraries(), fixture.assets(), instances);
        Path local = instances.resolve("fabric-profile/libraries/net/fabricmc/fabric-loader/1/loader-1.jar");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "loader");
        Files.writeString(fixture.profile(), profile.toString());
        var healthy = verifier.inspect("fabric-profile");
        assertTrue(healthy.checks().stream().anyMatch(c -> c.path().equals(local.toString()) && c.status() == Status.VERIFIED));
        Files.delete(local);
        var missing = verifier.inspect("fabric-profile");
        assertTrue(missing.checks().stream().anyMatch(c -> c.path().equals(local.toString())
                && c.status() == Status.MISSING && !c.repairable()));
    }

    @Test
    void repairEligibilityMatchesTheActualMetadataTarget() throws Exception {
        Fixture fixture = createInstallation();
        var target = new com.ecl.launcher.VersionManager.VersionDownloadTarget("fabric-profile", "1.20.1",
                "https://piston-meta.mojang.com/version.json", "a".repeat(40));
        Files.writeString(fixture.profile(), "{invalid");
        assertFalse(GameInstallationRepairService.canRepair(fixture.verifier().inspect("fabric-profile"), target, fixture.versions()));
        Files.writeString(fixture.profile(), "{\"inheritsFrom\":\"1.20.1\"}");
        Files.writeString(fixture.versions().resolve("1.20.1/1.20.1.json"), "{invalid");
        assertTrue(GameInstallationRepairService.canRepair(fixture.verifier().inspect("fabric-profile"), target, fixture.versions()));
        var invalidDigest = new com.ecl.launcher.VersionManager.VersionDownloadTarget("fabric-profile", "1.20.1",
                "https://piston-meta.mojang.com/version.json", "");
        assertFalse(GameInstallationRepairService.canRepair(fixture.verifier().inspect("fabric-profile"), invalidDigest, fixture.versions()));
    }

    private Fixture createInstallation() throws Exception {
        Path versions = temp.resolve("versions");
        Path libraries = temp.resolve("libraries");
        Path assets = temp.resolve("assets");
        byte[] clientBytes = bytes("client");
        byte[] libBytes = bytes("library");
        byte[] loaderBytes = bytes("loader");
        byte[] assetBytes = bytes("asset");
        String clientHash = sha1(clientBytes);
        String libHash = sha1(libBytes);
        String loaderHash = sha1(loaderBytes);
        String assetHash = sha1(assetBytes);

        Path client = versions.resolve("1.20.1/1.20.1.jar");
        Path library = libraries.resolve("org/example/lib/1/lib-1.jar");
        Path loader = libraries.resolve("net/fabricmc/fabric-loader/1/loader-1.jar");
        Path asset = assets.resolve("objects").resolve(assetHash.substring(0, 2)).resolve(assetHash);
        Path assetIndex = assets.resolve("indexes/test.json");
        for (Path path : java.util.List.of(client, library, loader, asset, assetIndex)) {
            Files.createDirectories(path.getParent());
        }
        Files.write(client, clientBytes);
        Files.write(library, libBytes);
        Files.write(loader, loaderBytes);
        Files.write(asset, assetBytes);
        byte[] indexBytes = ("{\"objects\":{\"sound\":{\"hash\":\"" + assetHash
                + "\",\"size\":" + assetBytes.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Files.write(assetIndex, indexBytes);
        Path baseProfile = versions.resolve("1.20.1/1.20.1.json");
        Files.writeString(baseProfile, """
                {"id":"1.20.1","downloads":{"client":{"url":"https://launcher.example/client.jar",
                 "sha1":"%s","size":%d}},"assetIndex":{"id":"test","url":"https://launcher.example/index.json",
                 "sha1":"%s","size":%d},"libraries":[{"name":"org.example:lib:1",
                 "downloads":{"artifact":{"path":"org/example/lib/1/lib-1.jar","url":"https://repo.example/lib.jar",
                 "sha1":"%s","size":%d}}}]}
                """.formatted(clientHash, clientBytes.length, sha1(indexBytes), indexBytes.length,
                libHash, libBytes.length));
        Path profile = versions.resolve("fabric-profile/fabric-profile.json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, """
                {"id":"fabric-profile","inheritsFrom":"1.20.1","eclMinecraftVersion":"1.20.1",
                 "eclModLoader":"fabric","eclModLoaderVersion":"1","libraries":[
                 {"name":"net.fabricmc:fabric-loader:1","downloads":{"artifact":{
                 "path":"net/fabricmc/fabric-loader/1/loader-1.jar","url":"https://repo.example/loader.jar",
                 "sha1":"%s","size":%d}}}]}
                """.formatted(loaderHash, loaderBytes.length));
        return new Fixture(versions, libraries, assets, profile, library, asset,
                new GameInstallationVerifier(versions, libraries, assets));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private record Fixture(Path versions, Path libraries, Path assets, Path profile,
                           Path library, Path asset, GameInstallationVerifier verifier) { }
}
