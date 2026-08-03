package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.api.ModConflictException;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.repository.InstalledModRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ModManagementServiceTest {
    @TempDir Path temp;
    private ModInstanceContext instance;
    private InstalledModRepository repository;
    private DefaultModManagementService service;

    @BeforeEach
    void setUp() {
        instance = TestFixtures.instance(temp);
        repository = new FileInstalledModRepository();
        service = new DefaultModManagementService(repository, new DefaultInstanceOperationLock(),
                Runnable::run, ignored -> false, new HashVerifier());
    }

    @Test
    void disablesAndEnablesUsingDedicatedDirectory() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));

        InstalledMod disabled = service.setEnabled(instance, List.of("example"), false).join().getFirst();
        assertFalse(disabled.enabled());
        assertFalse(Files.exists(instance.modsDirectory().resolve("example.jar")));
        assertTrue(Files.exists(instance.gameDirectory().resolve("disabled-mods/example.jar")));

        InstalledMod enabled = service.setEnabled(instance, List.of("example"), true).join().getFirst();
        assertTrue(enabled.enabled());
        assertTrue(Files.exists(instance.modsDirectory().resolve("example.jar")));
    }

    @Test
    void refusesEnableWhenTargetFileExists() throws Exception {
        createModFile("example.jar", false);
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("example.jar"), "collision");
        repository.saveAll(instance, List.of(record("example", false, false, "")));
        assertConflict(() -> service.setEnabled(instance, List.of("example"), true).join());
        assertTrue(Files.exists(instance.gameDirectory().resolve("disabled-mods/example.jar")));
    }

    @Test
    void requiredDependencyCannotBeDisabledOrUninstalledWhileOwnerEnabled() throws Exception {
        createModFile("root.jar", true);
        createModFile("api.jar", true);
        repository.saveAll(instance, List.of(
                record("root", true, false, ""),
                record("api", true, true, "root")));

        assertConflict(() -> service.setEnabled(instance, List.of("api"), false).join());
        assertConflict(() -> service.uninstall(instance, List.of("api")).join());
        assertTrue(Files.exists(instance.modsDirectory().resolve("api.jar")));
    }

    @Test
    void uninstallRemovesOnlySelectedUnreferencedFile() throws Exception {
        createModFile("first.jar", true);
        createModFile("second.jar", true);
        repository.saveAll(instance, List.of(
                record("first", true, false, ""),
                record("second", true, false, "")));

        List<InstalledMod> remaining = service.uninstall(instance, List.of("first")).join();
        assertEquals(List.of("second"), remaining.stream().map(InstalledMod::projectId).toList());
        assertFalse(Files.exists(instance.modsDirectory().resolve("first.jar")));
        assertTrue(Files.exists(instance.modsDirectory().resolve("second.jar")));
    }

    @Test
    void enablingOwnerRequiresItsDependencyOrBatchEnable() throws Exception {
        createModFile("root.jar", false);
        createModFile("api.jar", false);
        repository.saveAll(instance, List.of(
                record("root", false, false, ""),
                record("api", false, true, "root")));
        assertConflict(() -> service.setEnabled(instance, List.of("root"), true).join());
        List<InstalledMod> result = service.setEnabled(instance, List.of("root", "api"), true).join();
        assertTrue(result.stream().allMatch(InstalledMod::enabled));
    }

    private void createModFile(String name, boolean enabled) throws Exception {
        Path directory = enabled ? instance.modsDirectory() : instance.gameDirectory().resolve("disabled-mods");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), name);
    }

    private InstalledMod record(String project, boolean enabled, boolean dependency, String requiredBy) {
        Instant now = Instant.now();
        Path relative = Path.of(enabled ? "mods" : "disabled-mods", project + ".jar");
        return new InstalledMod(instance.instanceId(), project, project + "-v1", project, project,
                "1.0", project + ".jar", relative, "", "", project.length(),
                instance.minecraftVersion(), instance.loaderName(), "release", enabled,
                dependency, requiredBy, now, now);
    }

    private static void assertConflict(Runnable action) {
        CompletionException thrown = assertThrows(CompletionException.class, action::run);
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertInstanceOf(ModConflictException.class, cause);
    }
}
