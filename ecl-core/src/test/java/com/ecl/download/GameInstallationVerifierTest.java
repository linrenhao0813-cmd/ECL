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
