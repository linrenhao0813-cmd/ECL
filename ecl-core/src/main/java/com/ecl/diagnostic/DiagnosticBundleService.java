package com.ecl.diagnostic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;

/** Creates a compact support archive while removing credentials and personal paths. */
public final class DiagnosticBundleService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_LOG_BYTES = 512 * 1024;
    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "token", "accesstoken", "refreshtoken", "clienttoken", "password", "secret",
            "authorization", "apikey", "api_key", "session", "cookie");
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?im)^((?:proxy-)?authorization\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern COOKIE_HEADER = Pattern.compile(
            "(?im)^((?:set-)?cookie\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"[^\\\"\\r\\n]*(?:access[-_]?token|refresh[-_]?token|client[-_]?token|"
                    + "password|secret|authorization|session|cookie|api[-_]?key)[^\\\"\\r\\n]*\\\""
                    + "\\s*:\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|\\[[^]\\r\\n]*]|[^,}\\r\\n]+)");
    private static final String SECRET_OPTION =
            "--(?:access[-_]?token|refresh[-_]?token|client[-_]?token|api[-_]?key|session|password|secret)";
    private static final Pattern COMMAND_SECRET = Pattern.compile(
            "(?i)(" + SECRET_OPTION + "(?:\\s*=\\s*|\\s+))(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,}\\]]+)");
    private static final Pattern JSON_ARGUMENT_SECRET = Pattern.compile(
            "(?i)((?:\\\"" + SECRET_OPTION + "\\\"|'" + SECRET_OPTION + "')\\s*,\\s*)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*')");

    public Path export(Path target, Path baseDirectory, Path gameDirectory) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) parent = Path.of(".").toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, "ecl-diagnostics-", ".zip.tmp");
        try (OutputStream output = Files.newOutputStream(staging);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeSystemInfo(zip);
            addTextFile(zip, baseDirectory.resolve("settings.json"), "settings.json");
            addRecentLogs(zip, baseDirectory.resolve("logs"), "launcher-logs");
            addTextFile(zip, gameDirectory.resolve("logs").resolve("latest.log"), "game/latest.log");
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(staging);
            throw failure;
        }
        try {
            verifyRedactedArchive(staging);
            try {
                Files.move(staging, absoluteTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        return absoluteTarget;
    }

    private static void writeSystemInfo(ZipOutputStream zip) throws IOException {
        JsonObject info = new JsonObject();
        info.addProperty("generatedAt", Instant.now().toString());
        info.addProperty("os", System.getProperty("os.name", "unknown"));
        info.addProperty("osVersion", System.getProperty("os.version", "unknown"));
        info.addProperty("architecture", System.getProperty("os.arch", "unknown"));
        info.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        info.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        writeEntry(zip, "system.json", GSON.toJson(info));
    }

    private static void addRecentLogs(ZipOutputStream zip, Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) return;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(DiagnosticBundleService::lastModified).reversed())
                    .limit(3)
                    .forEach(files::add);
        }
        for (Path file : files) {
            addTextFile(zip, file, prefix + "/" + safeName(file.getFileName().toString()));
        }
    }

    private static void addTextFile(ZipOutputStream zip, Path file, String entryName) throws IOException {
        if (!Files.isRegularFile(file)) return;
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_LOG_BYTES);
        }
        writeEntry(zip, entryName, redact(new String(bytes, StandardCharsets.UTF_8)));
    }

    static String redact(String input) {
        String result = AUTH_HEADER.matcher(input).replaceAll("$1<redacted>");
        result = COOKIE_HEADER.matcher(result).replaceAll("$1<redacted>");
        result = JSON_SECRET.matcher(result).replaceAll("$1\"<redacted>\"");
        result = JSON_ARGUMENT_SECRET.matcher(result).replaceAll("$1\"<redacted>\"");
        result = COMMAND_SECRET.matcher(result).replaceAll("$1<redacted>");
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) result = result.replace(home, "<user-home>");
        for (String name : SENSITIVE_NAMES) {
            result = result.replaceAll("(?i)(" + Pattern.quote(name) + "\\s*[=:]\\s*)\\S+", "$1<redacted>");
        }
        return result;
    }

    private static void verifyRedactedArchive(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String content;
                try (InputStream input = zip.getInputStream(entry)) {
                    content = new String(input.readNBytes(MAX_LOG_BYTES + 1), StandardCharsets.UTF_8);
                }
                if (!redact(content).equals(content)) {
                    throw new IOException("Diagnostic archive still contains a credential pattern: "
                            + entry.getName());
                }
            }
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.ROOT);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
