package com.ecl.task;

/**
 * Thrown when a task is cancelled before or during execution. The executor reports this as a
 * cancellation (not a failure), so workflows can treat an interrupted install or launch as an
 * expected shutdown path rather than an error.
 */
public final class TaskCancellationException extends TaskExecutionException {

    public TaskCancellationException(String message) {
        super(message, null);
    }

    public TaskCancellationException(String message, Throwable cause) {
        super(message, cause);
    }

    static TaskCancellationException of(Task<?> task) {
        return new TaskCancellationException("Task cancelled: " + task.name());
    }
}