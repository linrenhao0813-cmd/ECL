package com.ecl.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.jar.JarFile;

/** Imports and manages explicitly trusted server JARs without executing a shell. */
public final class LocalServerManager {
    private static final Object PROCESS_LOCK = new Object();
    private static final Map<Path, Process> PROCESSES = new HashMap<>();
    private static final int MAX_LOG_BYTES = 262144;
    private final Path root;

    public LocalServerManager(Path dataDirectory) {
        root = dataDirectory.toAbsolutePath().normalize().resolve("servers");
    }

    public List<LocalServerProfile> list() throws IOException {
        synchronized (PROCESS_LOCK) {
            LocalServerFiles.safe(root);
            if (!Files.exists(root)) return List.of();
            List<LocalServerProfile> result = new ArrayList<>();
            try (var children = Files.list(root)) {
                for (Path child : children.toList()) {
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            && child.getFileName().toString().matches("[a-f0-9-]{36}")) {
                        result.add(profile(child.getFileName().toString()));
                    }
                }
            }
            result.sort(Comparator.comparing(LocalServerProfile::name).thenComparing(LocalServerProfile::id));
            return List.copyOf(result);
        }
    }

    public LocalServerProfile importJar(String name, Path sourceJar, Path javaExecutable, int memoryMb) throws IOException {
        Path source = LocalServerFiles.safe(sourceJar);
        validateJar(source);
        Path java = validateJava(javaExecutable);
        String id = UUID.randomUUID().toString();
        LocalServerProfile settings = new LocalServerProfile(id, name, java.toString(), memoryMb, false);
        synchronized (PROCESS_LOCK) {
            LocalServerFiles.safe(root);
            Files.createDirectories(root);
            Path target = directory(id);
            Files.createDirectory(target);
            try {
                Files.copy(source, target.resolve("server.jar"));
                validateJar(LocalServerFiles.safe(target.resolve("server.jar")));
                LocalServerFiles.write(target.resolve("profile.json"), settings);
                LocalServerFiles.writeText(target.resolve("eula.txt"), "eula=false\n");
                return settings;
            } catch (IOException error) {
                try {
                    LocalServerFiles.safe(target);
                    for (String file : List.of("server.jar", "profile.json", "eula.txt")) {
                        Files.deleteIfExists(LocalServerFiles.safe(target.resolve(file)));
                    }
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
                throw error;
            }
        }
    }

    public LocalServerProfile saveSettings(String id, String name, Path javaExecutable, int memoryMb) throws IOException {
        return locked(id, () -> {
            requireStopped(id);
            LocalServerProfile previous = profile(id);
            LocalServerProfile updated = new LocalServerProfile(id, name, validateJava(javaExecutable).toString(), memoryMb,
                    previous.eulaAccepted());
            LocalServerFiles.write(directory(id).resolve("profile.json"), updated);
            return updated;
        });
    }

    /** The caller must obtain a deliberate user choice before passing true. */
    public void setEulaAccepted(String id, boolean accepted) throws IOException {
        locked(id, () -> {
            requireStopped(id);
            LocalServerProfile previous = profile(id);
            Path folder = directory(id);
            LocalServerFiles.writeText(folder.resolve("eula.txt"), "eula=" + accepted + "\n");
            LocalServerFiles.write(folder.resolve("profile.json"), new LocalServerProfile(id, previous.name(),
                    previous.javaPath(), previous.memoryMb(), accepted));
            return null;
        });
    }

    public void start(String id) throws IOException {
        locked(id, () -> {
            requireStopped(id);
            LocalServerProfile settings = profile(id);
            Path folder = directory(id);
            if (!settings.eulaAccepted() || !eulaAccepted(folder)) {
                throw new IOException("Accept the Minecraft EULA before starting this server");
            }
            Path java = validateJava(Path.of(settings.javaPath()));
            validateJar(LocalServerFiles.safe(folder.resolve("server.jar")));
            Path log = LocalServerFiles.safe(folder.resolve("console.log"));
            Process process = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx" + settings.memoryMb() + "M",
                    "-jar", "server.jar", "nogui").directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            try {
                long started = process.info().startInstant().orElseThrow(() -> new IOException("Cannot verify server process identity"))
                        .toEpochMilli();
                LocalServerFiles.write(folder.resolve("process.json"), new RunningProcess(process.pid(), started));
            } catch (IOException error) {
                process.destroyForcibly();
                throw error;
            }
            PROCESSES.put(folder, process);
            process.onExit().thenRun(() -> {
                synchronized (PROCESS_LOCK) {
                    PROCESSES.remove(folder, process);
                }
            });
            return null;
        });
    }

    public void stop(String id) throws IOException {
        sendCommand(id, "stop");
    }

    public void sendCommand(String id, String command) throws IOException {
        if (command == null || command.isBlank() || command.length() > 4096 || command.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Server command must be one printable line, at most 4096 characters");
        }
        locked(id, () -> {
            if (!isRunning(id)) throw new IOException("Server is not running");
            Process process = PROCESSES.get(directory(id));
            if (process == null || !canControl(id)) {
                throw new IOException("Console input is unavailable because this server was started by a previous ECL session");
            }
            process.getOutputStream().write((command.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            return null;
        });
    }

    public boolean isRunning(String id) throws IOException {
        Path folder = directory(id);
        synchronized (PROCESS_LOCK) {
            Process attached = PROCESSES.get(folder);
            if (attached != null && attached.isAlive()) return true;
        }
        Path marker = folder.resolve("process.json");
        LocalServerFiles.safe(marker);
        if (!Files.exists(marker)) return false;
        RunningProcess record = LocalServerFiles.read(marker, RunningProcess.class);
        if (record.pid() <= 0 || record.startedAtEpochMillis() <= 0) throw new IOException("Invalid server process identity");
        var process = ProcessHandle.of(record.pid());
        if (process.isEmpty() || !process.get().isAlive()) return false;
        var started = process.get().info().startInstant();
        if (started.isEmpty()) throw new IOException("Cannot verify the existing server process identity");
        return started.get().toEpochMilli() == record.startedAtEpochMillis();
    }

    public boolean canControl(String id) throws IOException {
        synchronized (PROCESS_LOCK) {
            Process process = PROCESSES.get(directory(id));
            return process != null && process.isAlive();
        }
    }

    /** Reads at most 256 KiB and returns at most 2000 lines, even when the server emits unlimited output. */
    public List<String> readLogTail(String id, int maxLines) throws IOException {
        if (maxLines < 1 || maxLines > 2000) throw new IllegalArgumentException("Log line count must be between 1 and 2000");
        Path log = LocalServerFiles.safe(directory(id).resolve("console.log"));
        if (!Files.exists(log)) return List.of();
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0, size - MAX_LOG_BYTES);
            channel.position(start);
            ByteBuffer bytes = ByteBuffer.allocate((int) (size - start));
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            bytes.flip();
            List<String> lines = StandardCharsets.UTF_8.decode(bytes).toString().lines().toList();
            int first = Math.max(start > 0 ? 1 : 0, lines.size() - maxLines);
            return List.copyOf(lines.subList(Math.min(first, lines.size()), lines.size()));
        }
    }

    public Path directory(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9-]{36}") || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid local server ID");
        }
        return LocalServerFiles.safe(root.resolve(id));
    }

    private LocalServerProfile profile(String id) throws IOException {
        LocalServerProfile settings = LocalServerFiles.read(directory(id).resolve("profile.json"), LocalServerProfile.class);
        if (!settings.id().equals(id)) throw new IOException("Server profile ID does not match its directory");
        return settings;
    }

    private void requireStopped(String id) throws IOException {
        if (isRunning(id)) throw new IOException("Stop the server before changing its files or settings");
    }

    private <T> T locked(String id, IoOperation<T> operation) throws IOException {
        synchronized (PROCESS_LOCK) {
            Path folder = directory(id);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Server directory does not exist");
            Path lock = LocalServerFiles.safe(folder.resolve("manager.lock"));
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock lease = channel.lock()) {
                if (!lease.isValid()) throw new IOException("Could not lock server directory");
                return operation.run();
            }
        }
    }

    private static Path validateJava(Path javaExecutable) throws IOException {
        Path java = LocalServerFiles.safe(javaExecutable);
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(java)) {
            throw new IOException("Java executable does not exist or is not executable: " + java);
        }
        return java;
    }

    private static boolean eulaAccepted(Path folder) throws IOException {
        Path eula = LocalServerFiles.safe(folder.resolve("eula.txt"));
        if (!Files.isRegularFile(eula, LinkOption.NOFOLLOW_LINKS) || Files.size(eula) > 16384) return false;
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(eula, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return "true".equals(values.getProperty("eula", "false").trim());
    }

    private static void validateJar(Path jar) throws IOException {
        if (!jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Select a regular server JAR file");
        try (JarFile archive = new JarFile(jar.toFile())) {
            String mainClass = archive.getManifest() == null ? null : archive.getManifest().getMainAttributes().getValue("Main-Class");
            if (mainClass == null || mainClass.isBlank()) {
                throw new IOException("Server JAR has no executable Main-Class manifest");
            }
        }
    }

    private record RunningProcess(long pid, long startedAtEpochMillis) { }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
