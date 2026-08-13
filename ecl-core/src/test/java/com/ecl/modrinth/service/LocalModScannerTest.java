package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LocalModScannerTest {
    @TempDir Path temp;
    private ModInstanceContext instance;
    private TestFixtures.FakeApi api;
    private HashVerifier hashes;

    @BeforeEach
    void setUp() {
        instance = TestFixtures.instance(temp);
        api = new TestFixtures.FakeApi();
        hashes = new HashVerifier();
    }

    @Test
    void recognizesModrinthFileAndPreservesUnknownJar() throws Exception {
        Path recognized = instance.modsDirectory().resolve("recognized.jar");
        Path unknown = instance.modsDirectory().resolve("unknown.jar");
        TestFixtures.createJar(recognized, "recognized");
        TestFixtures.createJar(unknown, "unknown");
        String sha1 = hashes.calculate(recognized).sha1();
        ModFile file = new ModFile(TestFixtures.file("recognized.jar", true).url(), "recognized.jar",
                java.util.Map.of("sha1", sha1), true, Files.size(recognized), "required-resource");
        ModVersion version = TestFixtures.version("recognized-v1", "recognized", "release", false,
                List.of("1.21.1"), List.of("fabric"), java.time.Instant.now(),
                List.of(file), List.of());
        api.hashes.put(sha1, version);

        LocalModScanResult result = scanner().scan(instance).join();
        assertEquals(2, result.items().size());
        assertTrue(result.items().stream().anyMatch(LocalModScanItem::recognized));
        assertTrue(result.installedMods().stream()
                .anyMatch(mod -> mod.projectId().startsWith("local:") && mod.fileName().equals("unknown.jar")));
        assertTrue(Files.exists(instance.gameDirectory().resolve("launcher-mods.json")));
    }

    @Test
    void damagedJarDoesNotAbortWholeScan() throws Exception {
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("broken.jar"), "not a zip");
        TestFixtures.createJar(instance.modsDirectory().resolve("healthy.jar"), "healthy");
        LocalModScanResult result = scanner().scan(instance).join();
        assertEquals(2, result.items().size());
        assertTrue(result.items().stream().anyMatch(LocalModScanItem::damaged));
        assertEquals(1, result.installedMods().size());
    }

    @Test
    void detectsSameProjectMultipleVersionsAndReusesPersistentScanCache() throws Exception {
        Path first = instance.modsDirectory().resolve("one.jar");
        Path second = instance.gameDirectory().resolve("disabled-mods/two.jar");
        TestFixtures.createJar(first, "same");
        TestFixtures.createJar(second, "same");
        ModVersion version = TestFixtures.fabricVersion("same-v1", "same", List.of());
        api.hashes.put(hashes.calculate(first).sha1(), version);
        api.hashes.put(hashes.calculate(second).sha1(), version);

        LocalModScanResult initial = scanner().scan(instance).join();
        assertEquals(List.of("same"), initial.duplicateProjects());
        Path cache = instance.gameDirectory().resolve("launcher-mod-scan.json");
        String firstCache = Files.readString(cache);

        LocalModScanResult repeated = scanner().scan(instance).join();
        assertEquals(2, repeated.installedMods().size());
        assertEquals(firstCache, Files.readString(cache));
        assertEquals(2, api.hashLookups);
    }

    @Test
    void readsFabricMetadataWithoutOnlineHashMatch() throws Exception {
        Path local = instance.modsDirectory().resolve("offline.jar");
        writeJarEntry(local, "fabric.mod.json", """
                {"schemaVersion":1,"id":"offline-tools","name":"Offline Tools","version":"2.4.1"}
                """);

        LocalModScanResult result = scanner().scan(instance).join();

        assertEquals(1, result.installedMods().size());
        assertEquals("Offline Tools", result.installedMods().get(0).displayName());
        assertEquals("2.4.1", result.installedMods().get(0).versionNumber());
        assertEquals("fabric", result.installedMods().get(0).loader());
        assertTrue(result.items().get(0).message().contains("JAR 元数据识别"));
        assertTrue(Files.readString(instance.gameDirectory().resolve("launcher-mod-scan.json"))
                .contains("offline-tools"));
    }

    private static void writeJarEntry(Path jar, String entryName, String content) throws Exception {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private DefaultLocalModScanner scanner() {
        return new DefaultLocalModScanner(api, new FileInstalledModRepository(), hashes,
                new DefaultModVersionSelector(), new DefaultInstanceOperationLock(),
                Runnable::run, ignored -> false);
    }
}
