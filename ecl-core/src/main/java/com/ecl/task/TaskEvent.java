package com.ecl.task;

import java.util.Objects;

/**
 * Immutable snapshot of a state transition within a task's lifecycle.
 * Captured by the {@link TaskExecutor} and forwarded to the {@link TaskListener}.
 */
public final class TaskEvent {

    public enum Phase {
        QUEUED, STARTED, PROGRESS, FINISHED, FAILED, CANCELLED
    }

    private final Phase phase;
    private final Task<?> task;
    private final double progressFraction;
    private final Throwable failure;

    private TaskEvent(Phase phase, Task<?> task, double progressFraction, Throwable failure) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.task = task;
        this.progressFraction = progressFraction;
        this.failure = failure;
    }

    static TaskEvent queued(Task<?> task) {
        return new TaskEvent(Phase.QUEUED, task, 0.0, null);
    }

    static TaskEvent started(Task<?> task) {
        return new TaskEvent(Phase.STARTED, task, 0.0, null);
    }

    static TaskEvent progress(Task<?> task, double fraction) {
        return new TaskEvent(Phase.PROGRESS, task, fraction, null);
    }

    static TaskEvent finished(Task<?> task) {
        return new TaskEvent(Phase.FINISHED, task, task == null ? 0.0 : task.progressFraction(), null);
    }

    static TaskEvent failed(Task<?> task, Throwable failure) {
        return new TaskEvent(Phase.FAILED, task, task == null ? 0.0 : task.progressFraction(), failure);
    }

    static TaskEvent cancelled(Task<?> task) {
        return new TaskEvent(Phase.CANCELLED, task, task == null ? 0.0 : task.progressFraction(), null);
    }

    public Phase phase() {
        return phase;
    }

    public Task<?> task() {
        return task;
    }

    /** Progress fraction between 0.0 and 1.0; meaningful for {@code PROGRESS}. */
    public double progressFraction() {
        return progressFraction;
    }

    /** The failure for {@code FAILED} events; null otherwise. */
    public Throwable failure() {
        return failure;
    }

    public String taskName() {
        return task == null ? "(none)" : task.name();
    }
}