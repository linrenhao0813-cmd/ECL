package com.ecl.launch;

import com.ecl.auth.OfflineAuth;
import com.ecl.game.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCommandBuilderTest {

    @TempDir
    Path tempDir;

    private static final String LIBRARY_PATH = "org/example/dep/1.0/dep.jar";

    private VersionRepository repository;
    private LaunchEnvironment environment;
    private Path versionsDir;
    private Path librariesDir;
    private File gameDir;

    @BeforeEach
    void setUp() throws Exception {
        versionsDir = Files.createDirectories(tempDir.resolve("versions"));
        librariesDir = Files.createDirectories(tempDir.resolve("libraries"));
        Path assetsDir = Files.createDirectories(tempDir.resolve("assets"));
        gameDir = Files.createDirectories(tempDir.resolve("game")).toFile();
        environment = new LaunchEnvironment(versionsDir.toFile(), librariesDir.toFile(),
                assetsDir.toFile(), "ECL", "1.0.0");
        repository = new VersionRepository(versionsDir.toFile());

        writeVersion("1.21", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "arguments":{
                   "jvm":["-Dfabric.skipMcProvider=true"],
                   "game":["--username","${auth_player_name}","--uuid","${auth_uuid}"]
                 },
                 "libraries":[{"name":"org.example:dep:1.0",
                   "downloads":{"artifact":{"url":"https://example/dep.jar",
                     "path":"%s","sha1":"abc"}}}]}
                """.formatted(LIBRARY_PATH));
        writeClientJar("1.21");
        writeLibraryFile(LIBRARY_PATH);
    }

    private void writeVersion(String id, String json) throws IOException {
        Path versionDirectory = Files.createDirectories(versionsDir.resolve(id));
        Files.writeString(versionDirectory.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private void writeClientJar(String id) throws IOException {
        Path versionDirectory = Files.createDirectories(versionsDir.resolve(id));
        Files.write(versionDirectory.resolve(id + ".jar"), new byte[]{1, 2, 3});
    }

    private void writeLibraryFile(String path) throws IOException {
        Path file = librariesDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{4, 5, 6});
    }

    private LaunchCommand build(LaunchOptions.Builder optionsBuilder) throws IOException {
        throw new UnsupportedOperationException("use LaunchCommandBuilder directly in tests");
    }

    @Test
    void assemblesACompleteCommandLine() throws Exception {
        LaunchOptions options = options()
                .gameResolution(1600, 900)
                .fullscreen(true)
                .serverAddress("play.example.com:25570")
                .processorCount(6)
                .jvmArguments(List.of("-Duser.timezone=Asia/Shanghai"))
                .build();

        LaunchCommand command = new LaunchCommandBuilder().build(
                options, repository.resolve("1.21"), "C:\\Java\\bin\\java.exe");

        assertEquals("C:\\Java\\bin\\java.exe", command.executable());
        List<String> args = command.arguments();
        assertEquals("-Xms512m", args.get(0));
        assertEquals("-Xmx2048m", args.get(1));
        assertTrue(args.contains("-XX:ActiveProcessorCount=6"));
        assertTrue(args.contains("-Dfabric.skipMcProvider=true"));
        assertTrue(args.contains("-Duser.timezone=Asia/Shanghai"));

        int cpIndex = args.indexOf("-cp");
        String classpath = args.get(cpIndex + 1);
        assertTrue(classpath.contains(librariesDir.resolve(LIBRARY_PATH).toString()));
        assertTrue(classpath.contains(versionsDir.resolve("1.21/1.21.jar").toString()));

        assertEquals("net.minecraft.client.main.Main", args.get(cpIndex + 2));
        List<String> tail = args.subList(cpIndex + 3, args.size());
        assertTrue(tail.contains("--username"));
        assertTrue(tail.contains("Player"));
        assertTrue(tail.contains("--uuid"));
        assertTrue(tail.stream().anyMatch(v -> v.length() == 32), "uuid token expanded to 32 hex chars");
        assertTrue(tail.contains("--width"));
        assertEquals("1600", args.get(args.indexOf("--width") + 1));
        assertEquals("900", args.get(args.indexOf("--height") + 1));
        assertTrue(args.contains("--fullscreen"));
        assertTrue(args.contains("--server"));
        assertEquals("play.example.com", args.get(args.indexOf("--server") + 1));
        assertEquals("25570", args.get(args.indexOf("--port") + 1));

        assertEquals(gameDir, command.workingDirectory());
        assertEquals(gameDir.getParentFile().getAbsolutePath(), command.environment().get("APPDATA"));
    }

    @Test
    void withoutServerOrFullscreenThoseFlagsAreOmitted() throws Exception {
        LaunchOptions options = options().build();
        LaunchCommand command = new LaunchCommandBuilder().build(
                options, repository.resolve("1.21"), "java");

        assertTrue(command.arguments().stream().noneMatch("--server"::equals));
        assertTrue(command.arguments().stream().noneMatch("--fullscreen"::equals));
        assertTrue(command.environment().isEmpty() || command.environment().containsKey("APPDATA"));
    }

    @Test
    void blankMainClassIsRejected() throws Exception {
        writeVersion("broken", """
                {"libraries":[{"name":"x:y:1","downloads":{"artifact":{"url":"https://e",
                 "path":"x/y/1/y.jar","sha1":"z"}}}]}
                """);
        writeLibraryFile("x/y/1/y.jar");
        LaunchOptions options = options().versionId("broken").build();

        LaunchException error = assertThrows(LaunchException.class,
                () -> new LaunchCommandBuilder().build(options, repository.resolve("broken"), "java"));

        assertEquals(LaunchException.Kind.VERSION_INVALID, error.kind());
    }

    @Test
    void fabricStyleMavenLibrariesArePlacedOnClasspath() throws Exception {
        writeVersion("fabric-1.21", """
                {"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries":[{"name":"net.fabricmc:fabric-loader:0.16.9",
                   "url":"https://maven.fabricmc.net/"}]}
                """);
        writeClientJar("fabric-1.21");
        writeLibraryFile("net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar");
        LaunchOptions options = options().versionId("fabric-1.21").build();

        LaunchCommand command = new LaunchCommandBuilder().build(
                options, repository.resolve("fabric-1.21"), "java");

        int cpIndex = command.arguments().indexOf("-cp");
        String classpath = command.arguments().get(cpIndex + 1);
        assertTrue(classpath.contains(librariesDir.resolve(
                "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar").toString()));
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient",
                command.arguments().get(cpIndex + 2));
    }

    @Test
    void missingFabricStyleMavenLibraryProducesMISSING_FILES() throws Exception {
        writeVersion("fabric-broken", """
                {"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries":[{"name":"net.fabricmc:fabric-loader:0.16.9",
                   "url":"https://maven.fabricmc.net/"}]}
                """);
        writeClientJar("fabric-broken");
        LaunchOptions options = options().versionId("fabric-broken").build();

        LaunchException error = assertThrows(LaunchException.class,
                () -> new LaunchCommandBuilder().build(
                        options, repository.resolve("fabric-broken"), "java"));

        assertEquals(LaunchException.Kind.MISSING_FILES, error.kind());
        assertTrue(error.getMessage().contains(
                "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar"));
    }

    @Test
    void missingClientJarProducesMISSING_FILES() throws Exception {
        LaunchOptions options = options().build();
        repository.invalidate("1.21");
        Files.delete(versionsDir.resolve("1.21/1.21.jar"));

        LaunchException error = assertThrows(LaunchException.class,
                () -> new LaunchCommandBuilder().build(options, repository.resolve("1.21"), "java"));

        assertEquals(LaunchException.Kind.MISSING_FILES, error.kind());
        assertTrue(error.getMessage().contains("1.21.jar"));
    }

    @Test
    void rejectsClientJarIdThatEscapesVersionsDirectory() throws Exception {
        writeVersion("malicious", """
                {"mainClass":"net.minecraft.client.main.Main","jar":"../outside"}
                """);
        LaunchOptions options = options().versionId("malicious").build();

        assertThrows(IOException.class, () -> new LaunchCommandBuilder().build(
                options, repository.resolve("malicious"), "java"));
    }

    @Test
    void missingLibraryProducesMISSING_FILES() throws Exception {
        writeVersion("1.22", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "libraries":[{"name":"org.example:missing:1.0",
                   "downloads":{"artifact":{"url":"https://example/m.jar",
                     "path":"org/example/missing/1.0/m.jar","sha1":"d"}}}]}
                """);
        writeClientJar("1.22");
        LaunchOptions options = options().versionId("1.22").build();

        LaunchException error = assertThrows(LaunchException.class,
                () -> new LaunchCommandBuilder().build(options, repository.resolve("1.22"), "java"));

        assertEquals(LaunchException.Kind.MISSING_FILES, error.kind());
        assertTrue(error.getMessage().contains("org/example/missing/1.0/m.jar"));
    }

    @Test
    void clientJarAppearsOnlyOnceInClasspath() throws Exception {
        LaunchOptions options = options().build();
        LaunchCommand command = new LaunchCommandBuilder().build(
                options, repository.resolve("1.21"), "java");

        int cpIndex = command.arguments().indexOf("-cp");
        String classpath = command.arguments().get(cpIndex + 1);
        String clientJarPath = versionsDir.resolve("1.21/1.21.jar").toString();
        assertEquals(1, countOf(classpath, clientJarPath));
    }

    @Test
    void committedCommandLineStartsWithExecutable() throws Exception {
        LaunchCommand command = new LaunchCommandBuilder().build(
                options().build(), repository.resolve("1.21"), "C:/jdk/bin/java");

        assertEquals("C:/jdk/bin/java", command.commandLine().get(0));
        assertEquals(command.arguments().size() + 1, command.commandLine().size());
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    void rejectsUserSuppliedJavaAgentsButKeepsLauncherAgents() throws Exception {
        LaunchOptions unsafe = options()
                .jvmArguments(List.of("-javaagent:evil.jar"))
                .build();
        LaunchException failure = assertThrows(LaunchException.class, () ->
                new LaunchCommandBuilder().build(unsafe, repository.resolve("1.21"), "java"));
        assertTrue(failure.getMessage().contains("不允许"));
    }

    @Test
    void includesExtraJvmArgsInCommandOrder() throws Exception {
        LaunchOptions options = options().build();
        LaunchCommand command = new LaunchCommandBuilder().build(
                options, repository.resolve("1.21"), "java",
                List.of("-javaagent:agent.jar=http://127.0.0.1:9999", "-Dauthlibinjector.side=client"));

        List<String> args = command.arguments();
        assertTrue(args.contains("-javaagent:agent.jar=http://127.0.0.1:9999"));
        assertTrue(args.contains("-Dauthlibinjector.side=client"));
        // Extra JVM arguments must land before -cp / mainClass and not break argument order
        int agentIndex = args.indexOf("-javaagent:agent.jar=http://127.0.0.1:9999");
        int cpIndex = args.indexOf("-cp");
        assertTrue(agentIndex >= 0 && agentIndex < cpIndex);
    }

    private LaunchOptions.Builder options() {
        return LaunchOptions.builder()
                .versionId("1.21")
                .auth(new OfflineAuth("Player"))
                .gameDirectory(gameDir)
                .environment(environment)
                .maxMemoryMb(2048)
                .minMemoryMb(512);
    }
}
