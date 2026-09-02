package com.ecl.game.companion;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionTaskStoreTest {
    @Test
    void persistsTasksStatusesAndCancellationMarker(@TempDir Path world) throws Exception {
        CompanionTaskStore store = new CompanionTaskStore(world);
        CompanionTask task = store.enqueue("挖3格", null, true);

        assertEquals(1, store.listTasks().size());
        assertEquals(CompanionTaskStatus.QUEUED, store.readStatus(task).status());
        assertTrue(Files.isRegularFile(store.taskPath(task.taskId())));
        assertFalse(Files.readString(store.taskPath(task.taskId())).contains("apiKey"));

        CompanionTaskResult running = new CompanionTaskResult(1, task.taskId(),
                CompanionTaskStatus.RUNNING, 3, 1, "正在寻找附近矿物",
                Instant.now().toString(), Instant.now().toString(), "");
        store.writeStatus(running);
        assertEquals(1, store.readStatus(task).completedActions());

        store.cancel(task.taskId());
        assertTrue(store.isCancellationRequested(task.taskId()));
        store.clearCancellation(task.taskId());
        assertFalse(store.isCancellationRequested(task.taskId()));
    }

    @Test
    void ignoresMalformedOrUnsafeTaskJson(@TempDir Path world) throws Exception {
        CompanionTaskStore store = new CompanionTaskStore(world);
        store.ensureDirectories();
        Files.writeString(store.bridgeDirectory().resolve("inbox/not-a-uuid.json"), "{bad json");
        JsonObject unsupported = new JsonObject();
        unsupported.addProperty("schemaVersion", 99);
        unsupported.addProperty("taskId", UUID.randomUUID().toString());
        unsupported.addProperty("instruction", "/give @p diamond");
        unsupported.addProperty("createdAt", Instant.now().toString());
        Files.writeString(store.bridgeDirectory().resolve("inbox/unsupported.json"), unsupported.toString());

        assertTrue(store.listTasks().isEmpty());
    }

    @Test
    void keepsOnlySupportedDeterministicInstructions() {
        assertTrue(CompanionTask.isSupportedInstruction("mine 5 blocks"));
        assertTrue(CompanionTask.isSupportedInstruction("帮我做3个铁锭"));
        assertFalse(CompanionTask.isSupportedInstruction("/give @p diamond"));
        assertFalse(CompanionTask.isSupportedInstruction("挖到钻石了"));
        assertEquals(5, CompanionTask.create("mine 5 blocks", null, true).requestedActions());
    }

    @Test
    void rejectsTasksFromAnotherSource() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionTask(1,
                UUID.randomUUID(), "挖3格", Instant.now().toString(),
                CompanionTask.TargetPolicy.BOUND_PLAYER, null, true, "other"));
    }
}
