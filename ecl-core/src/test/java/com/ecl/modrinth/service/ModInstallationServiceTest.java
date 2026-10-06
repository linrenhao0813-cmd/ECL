package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModInstallationServiceTest {
    @TempDir Path temp;
    private HttpServer server;
    private ExecutorService executor;
    private AutoCloseable downloadPolicy;
    private ModInstanceContext instance;
    private FileInstalledModRepository repository;
    private TestFixtures.FakeApi api;
    private DefaultModDependencyResolver resolver;
    private ModInstallationService installer;
    private DefaultModManagementService management;

    @BeforeEach
    void setUp() throws Exception {
        downloadPolicy = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        executor = Executors.newSingleThreadExecutor();
        instance = TestFixtures.instance(temp);
        repository = new FileInstalledModRepository();
        api = new TestFixtures.FakeApi();
        resolver = new DefaultModDependencyResolver(api, new DefaultModVersionSelector(), ignored -> {
            try {
                return repository.findAll(instance);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }, 16, 32);
        var lock = new InstanceOperationCoordinator();
        installer = new ModInstallationService(repository, new ModFileDownloadService(executor, new HashVerifier()),
                lock, Runnable::run, ignored -> false);
        management = new DefaultModManagementService(repository, lock, Runnable::run, ignored -> false, new HashVerifier());
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.stop(0);
        downloadPolicy.close();
    }

    @Test
    void sameFilenameBelongingToAnotherProjectCannotBeOverwritten() throws Exception {
        install(version("a-v1", "a", "same.jar"));
        String before = Files.readString(instance.modsDirectory().resolve("same.jar"));

        assertThrows(CompletionException.class, () -> install(version("b-v1", "b", "same.jar")));

        assertEquals(before, Files.readString(instance.modsDirectory().resolve("same.jar")));
        assertEquals(List.of("a"), repository.findAll(instance).stream().map(mod -> mod.projectId()).toList());
    }

    @Test
    void reusedSharedDependencyRetainsAllOwnersIncludingAfterUpdatingItAsRoot() throws Exception {
        api.projectVersions.put("shared", List.of(version("shared-v1", "shared", "shared.jar")));
        install(version("first-v1", "first", "first.jar", required("shared")));
        install(version("second-v1", "second", "second.jar", required("shared")));
        assertEquals(Set.of("first", "second"), repository.findByProjectId(instance, "shared").orElseThrow().requiredByProjectIds());

        install(version("shared-v2", "shared", "shared-new.jar"));

        var shared = repository.findByProjectId(instance, "shared").orElseThrow();
        assertEquals(Set.of("first", "second"), shared.requiredByProjectIds());
        assertTrue(shared.dependency());
        assertThrows(CompletionException.class, () -> management.uninstall(instance, List.of("first", "shared")).join());
        assertTrue(Files.isRegularFile(instance.modsDirectory().resolve("shared-new.jar")));
    }

    @Test
    void updatingAnOwnerRemovesItsObsoleteRequirementWithoutRemovingOtherOwners() throws Exception {
        api.projectVersions.put("shared", List.of(version("shared-v1", "shared", "shared.jar")));
        install(version("first-v1", "first", "first.jar", required("shared")));
        install(version("second-v1", "second", "second.jar", required("shared")));

        install(version("first-v2", "first", "first.jar"));

        assertEquals(Set.of("second"), repository.findByProjectId(instance, "shared").orElseThrow().requiredByProjectIds());
        assertThrows(CompletionException.class, () -> management.uninstall(instance, List.of("first", "shared")).join());
    }

    private void install(ModVersion version) {
        var plan = new InstallationPlanBuilder().build(instance, version, resolver.resolve(instance, version).join());
        installer.install(plan, null).join();
    }

    private ModVersion version(String id, String project, String filename, ModDependency... dependencies) throws Exception {
        byte[] content = ("/" + id).getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
        ModFile file = new ModFile(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + id), filename,
                Map.of("sha512", hash), true, content.length, "");
        return TestFixtures.version(id, project, "release", false, List.of(instance.minecraftVersion()),
                List.of(instance.loaderName()), Instant.now(), List.of(file), List.of(dependencies));
    }

    private static ModDependency required(String project) {
        return new ModDependency("", project, "", DependencyType.REQUIRED);
    }
}
