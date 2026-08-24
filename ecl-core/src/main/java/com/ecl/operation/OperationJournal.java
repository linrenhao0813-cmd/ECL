package com.ecl.operation;

import com.ecl.util.GsonProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable lifecycle record for one instance-mutating operation. */
public record OperationJournal(
        int schemaVersion,
        UUID operationId,
        UUID instanceId,
        Kind kind,
        String description,
        Status status,
        String startedAt,
        String finishedAt,
        String failureType,
        String failureMessage
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 2_000;

    public OperationJournal {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported operation journal schema: " + schemaVersion);
        }
        operationId = Objects.requireNonNull(operationId, "operationId");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        kind = Objects.requireNonNull(kind, "kind");
        description = description == null ? "" : description.trim();
        status = Objects.requireNonNull(status, "status");
        startedAt = requireText(startedAt, "startedAt");
        finishedAt = finishedAt == null ? "" : finishedAt;
        failureType = failureType == null ? "" : failureType;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    public static OperationJournal started(UUID operationId, UUID instanceId, Kind kind,
                                           String description, Instant now) {
        return new OperationJournal(CURRENT_SCHEMA_VERSION, operationId, instanceId, kind,
                description, Status.RUNNING, now.toString(), "", "", "");
    }

    public OperationJournal succeeded(Instant now) {
        return terminal(Status.SUCCEEDED, now, "", "");
    }

    public OperationJournal failed(Instant now, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.length() > MAX_FAILURE_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
        }
        return terminal(Status.FAILED, now, failure.getClass().getName(), message);
    }

    public void write(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "operation-", ".json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(toJson(), writer);
            }
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static OperationJournal read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return new OperationJournal(
                    json.get("schemaVersion").getAsInt(),
                    UUID.fromString(json.get("operationId").getAsString()),
                    UUID.fromString(json.get("instanceId").getAsString()),
                    Kind.valueOf(json.get("kind").getAsString()),
                    json.get("description").getAsString(),
                    Status.valueOf(json.get("status").getAsString()),
                    json.get("startedAt").getAsString(),
                    json.get("finishedAt").getAsString(),
                    json.get("failureType").getAsString(),
                    json.get("failureMessage").getAsString());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid operation journal: " + file, invalid);
        }
    }

    private OperationJournal terminal(Status terminalStatus, Instant now,
                                      String terminalFailureType, String terminalFailureMessage) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Operation journal is already terminal");
        }
        return new OperationJournal(schemaVersion, operationId, instanceId, kind, description,
                terminalStatus, startedAt, now.toString(), terminalFailureType, terminalFailureMessage);
    }

    private JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("operationId", operationId.toString());
        json.addProperty("instanceId", instanceId.toString());
        json.addProperty("kind", kind.name());
        json.addProperty("description", description);
        json.addProperty("status", status.name());
        json.addProperty("startedAt", startedAt);
        json.addProperty("finishedAt", finishedAt);
        json.addProperty("failureType", failureType);
        json.addProperty("failureMessage", failureMessage);
        return json;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public enum Kind {
        MOD_INSTALL,
        AUTO_REPAIR,
        BACKUP_RESTORE,
        MODPACK_UPDATE,
        OTHER
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
