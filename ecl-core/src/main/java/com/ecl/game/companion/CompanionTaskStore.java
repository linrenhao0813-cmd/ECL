package com.ecl.game.companion;

import com.ecl.util.FileUtil;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** File-backed bridge storage for one world; malformed files are ignored safely. */
public final class CompanionTaskStore {
    public static final String BRIDGE_DIRECTORY = ".playwithai/ecl-bridge";
    private final Path worldDirectory;
    private final Path bridgeDirectory;
    private final Path inboxDirectory;
    private final Path statusDirectory;
    private final Path controlDirectory;

    public CompanionTaskStore(Path worldDirectory) {
        this.worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath().normalize();
        this.bridgeDirectory = this.worldDirectory.resolve(BRIDGE_DIRECTORY).normalize();
        this.inboxDirectory = bridgeDirectory.resolve("inbox");
        this.statusDirectory = bridgeDirectory.resolve("status");
        this.controlDirectory = bridgeDirectory.resolve("control");
    }

    public Path worldDirectory() {
        return worldDirectory;
    }

    public Path bridgeDirectory() {
        return bridgeDirectory;
    }

    public void ensureDirectories() throws IOException {
        FileUtil.validateExistingAncestors(worldDirectory, bridgeDirectory);
        Files.createDirectories(inboxDirectory);
        Files.createDirectories(statusDirectory);
        Files.createDirectories(controlDirectory);
        FileUtil.validateExistingAncestors(worldDirectory, bridgeDirectory);
    }

    public CompanionTask enqueue(String instruction, UUID targetPlayerUuid, boolean autoSummon)
            throws IOException {
        CompanionTask task = CompanionTask.create(instruction, targetPlayerUuid, autoSummon);
        writeTask(task);
        writeStatus(CompanionTaskResult.queued(task));
        return task;
    }

    public void writeTask(CompanionTask task) throws IOException {
        Objects.requireNonNull(task, "task");
        ensureDirectories();
        writeAtomically(taskPath(task.taskId()), task.toJson());
    }

    public List<CompanionTask> listTasks() throws IOException {
        ensureDirectories();
        try (Stream<Path> files = Files.list(inboxDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(this::readTaskQuietly)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(CompanionTask::createdAt)
                            .thenComparing(task -> task.taskId().toString()))
                    .toList();
        }
    }

    public Optional<CompanionTask> readTask(UUID taskId) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Path file = taskPath(taskId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readTaskFile(file));
        } catch (RuntimeException | IOException invalid) {
            return Optional.empty();
        }
    }

    public CompanionTaskResult readStatus(CompanionTask task) throws IOException {
        Objects.requireNonNull(task, "task");
        Path file = statusPath(task.taskId());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return CompanionTaskResult.queued(task);
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            CompanionTaskResult result = CompanionTaskResult.fromJson(json);
            if (!task.taskId().equals(result.taskId())
                    || task.requestedActions() != result.requestedActions()) {
                throw new IllegalArgumentException("状态任务 ID 不匹配");
            }
            return result;
        } catch (RuntimeException | IOException invalid) {
            return new CompanionTaskResult(CompanionTask.CURRENT_SCHEMA_VERSION, task.taskId(),
                    CompanionTaskStatus.REJECTED, task.requestedActions(), 0,
                    "状态文件损坏或不兼容", "", Instant.now().toString(), "");
        }
    }

    public void writeStatus(CompanionTaskResult result) throws IOException {
        Objects.requireNonNull(result, "result");
        ensureDirectories();
        writeAtomically(statusPath(result.taskId()), result.toJson());
    }

    public void cancel(UUID taskId) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        ensureDirectories();
        writeAtomically(controlPath(taskId), "cancel".getBytes(StandardCharsets.UTF_8));
    }

    public boolean isCancellationRequested(UUID taskId) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        ensureDirectories();
        return Files.isRegularFile(controlPath(taskId), LinkOption.NOFOLLOW_LINKS);
    }

    public void clearCancellation(UUID taskId) throws IOException {
        Files.deleteIfExists(controlPath(taskId));
    }

    public Path taskPath(UUID taskId) {
        return inboxDirectory.resolve(taskId.toString() + ".json");
    }

    public Path statusPath(UUID taskId) {
        return statusDirectory.resolve(taskId.toString() + ".json");
    }

    public Path controlPath(UUID taskId) {
        return controlDirectory.resolve(taskId.toString() + ".cancel");
    }

    private Optional<CompanionTask> readTaskQuietly(Path file) {
        try {
            return Optional.of(readTaskFile(file));
        } catch (RuntimeException | IOException invalid) {
            return Optional.empty();
        }
    }

    private static CompanionTask readTaskFile(Path file) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        return CompanionTask.fromJson(json);
    }

    private static void writeAtomically(Path target, JsonObject object) throws IOException {
        writeAtomically(target, GsonProvider.pretty().toJson(object).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".bridge-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
