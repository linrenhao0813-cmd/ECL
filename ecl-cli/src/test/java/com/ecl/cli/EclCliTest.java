package com.ecl.cli;

import com.ecl.auth.AuthProvider;
import com.ecl.auth.AuthType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EclCliTest {
    @Test
    void helpIsAvailableWithoutStartingJavaFx() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new EclCli());
        commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));

        assertEquals(0, commandLine.execute("--help"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("doctor"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("version"));
    }

    @Test
    void dryRunRedactionCoversSeparatedInlineAndEmbeddedSecrets() {
        AuthProvider auth = auth("real-token");
        List<String> redacted = EclCli.LaunchCommandLine.redactCommand(List.of(
                "java", "--accessToken", "real-token", "--session=other-secret",
                "prefix-real-token-suffix"), auth);

        String output = String.join(" ", redacted);
        assertFalse(output.contains("real-token"));
        assertFalse(output.contains("other-secret"));
        assertTrue(output.contains("<redacted>"));
        assertEquals("<redacted>", EclCli.LaunchCommandLine.redactEnvironment(
                Map.of("SESSION", "environment-secret"), auth).get("SESSION"));
    }

    @Test
    void jsonBusinessFailureIsOneMachineReadableObjectWithoutStackTrace() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CommandLine commandLine = EclCli.commandLine();
        commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));
        commandLine.setErr(new PrintWriter(errors, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute("--json", "version", "inspect", "definitely-missing");
        String json = output.toString(StandardCharsets.UTF_8);

        assertTrue(exitCode != 0);
        assertTrue(json.trim().startsWith("{"));
        assertTrue(json.contains("\"status\" : \"error\""));
        assertFalse(json.contains("Exception in thread"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void modToggleRefusesToOverwriteExistingFile(@TempDir Path temp) throws Exception {
        Path source = Files.writeString(temp.resolve("foo.jar.disabled"), "disabled");
        Path target = Files.writeString(temp.resolve("foo.jar"), "enabled");

        assertThrows(java.io.IOException.class,
                () -> EclCli.moveModWithoutOverwrite(source, target));
        assertEquals("disabled", Files.readString(source));
        assertEquals("enabled", Files.readString(target));
    }

    @Test
    void automaticMemoryUsesCalculatedValueInsteadOfMinimum() {
        assertEquals(com.ecl.ECLConfig.calculateAutoMemoryMb(),
                EclCli.LaunchCommandLine.selectMemory(null, 0));
        assertEquals(4096, EclCli.LaunchCommandLine.selectMemory(null, 4096));
        assertEquals(3072, EclCli.LaunchCommandLine.selectMemory(3072, 4096));
    }

    @Test
    void dryRunDoesNotCreateInstanceOrNatives(@TempDir Path temp) throws Exception {
        Field baseDir = com.ecl.ECLConfig.class.getDeclaredField("baseDir");
        baseDir.setAccessible(true);
        Object previous = baseDir.get(null);
        try {
            baseDir.set(null, temp.toFile());
            Path version = Files.createDirectories(temp.resolve("versions/test"));
            Files.writeString(version.resolve("test.json"), """
                    {"id":"test","mainClass":"example.Main","javaVersion":{"majorVersion":21},
                     "arguments":{"game":["--accessToken","${auth_access_token}"]},"libraries":[]}
                    """);
            Files.write(version.resolve("test.jar"), new byte[]{1});
            long before = fileCount(temp);

            assertEquals(0, EclCli.commandLine().execute("launch", "test", "--dry-run"));

            assertEquals(before, fileCount(temp));
            assertFalse(Files.exists(temp.resolve("game/versions/test")));
            assertFalse(Files.exists(version.resolve("natives")));
        } finally {
            baseDir.set(null, previous);
        }
    }

    private static long fileCount(Path root) throws java.io.IOException {
        try (var paths = Files.walk(root)) {
            return paths.count();
        }
    }

    private static AuthProvider auth(String token) {
        return new AuthProvider() {
            public String getUsername() { return "Alex"; }
            public String getUUID() { return "uuid"; }
            public String getAccessToken() { return token; }
            public AuthType getType() { return AuthType.MICROSOFT; }
            public boolean isLoggedIn() { return true; }
            public void login() { }
            public void logout() { }
        };
    }
}
