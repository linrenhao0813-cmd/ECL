package com.ecl.game.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.UUID;

/** Structured progress/result data written by playWithAI and read by ECL. */
public record CompanionTaskResult(int schemaVersion, UUID taskId, CompanionTaskStatus status,
                                  int requestedActions, int completedActions, String message,
                                  String startedAt, String updatedAt, String finishedAt) {
    public CompanionTaskResult {
        if (schemaVersion != CompanionTask.CURRENT_SCHEMA_VERSION || taskId == null || status == null) {
            throw new IllegalArgumentException("无效的 Companion 状态");
        }
        requestedActions = Math.max(0, Math.min(64, requestedActions));
        completedActions = Math.max(0, Math.min(requestedActions, completedActions));
        message = message == null ? "" : message.length() > 2_000 ? message.substring(0, 2_000) : message;
        startedAt = blankToEmpty(startedAt);
        updatedAt = blankToEmpty(updatedAt);
        finishedAt = blankToEmpty(finishedAt);
    }

    public static CompanionTaskResult queued(CompanionTask task) {
        return new CompanionTaskResult(CompanionTask.CURRENT_SCHEMA_VERSION, task.taskId(),
                CompanionTaskStatus.QUEUED, task.requestedActions(), 0,
                "等待进入此存档后执行", "", task.createdAt(), "");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("taskId", taskId.toString());
        json.addProperty("status", status.name());
        json.addProperty("requestedActions", requestedActions);
        json.addProperty("completedActions", completedActions);
        json.addProperty("message", message);
        json.addProperty("startedAt", startedAt);
        json.addProperty("updatedAt", updatedAt);
        json.addProperty("finishedAt", finishedAt);
        return json;
    }

    public static CompanionTaskResult fromJson(JsonObject json) {
        return new CompanionTaskResult(
                required(json, "schemaVersion").getAsInt(),
                UUID.fromString(required(json, "taskId").getAsString()),
                CompanionTaskStatus.parse(required(json, "status").getAsString()),
                required(json, "requestedActions").getAsInt(),
                required(json, "completedActions").getAsInt(),
                optionalString(json, "message"),
                optionalString(json, "startedAt"),
                optionalString(json, "updatedAt"),
                optionalString(json, "finishedAt"));
    }

    private static JsonElement required(JsonObject json, String name) {
        JsonElement value = json == null ? null : json.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("状态缺少字段: " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement value = json == null ? null : json.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
