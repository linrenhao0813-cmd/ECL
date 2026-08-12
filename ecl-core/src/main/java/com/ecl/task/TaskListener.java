package com.ecl.task;

/**
 * Receives lifecycle and progress notifications for a {@link Task} run by a {@link TaskExecutor}.
 *
 * <p>All notifications are delivered on the executor thread that runs the task. Implementations
 * that update the user interface are responsible for hopping to the UI thread themselves; they
 * must also be prepared for a handler to be invoked from more than one executor thread at once.</p>
 */
public interface TaskListener {

    /** The task has been accepted by the executor and is waiting to run. */
    default void onQueued(Task<?> task) {
    }

    /** The task body is about to execute. */
    default void onStarted(Task<?> task) {
    }

    /** The task reported a progress update via {@link Task#reportProgress}. */
    default void onProgress(Task<?> task, double fraction) {
    }

    /** The task completed normally. */
    default void onFinished(Task<?> task) {
    }

    /** The task failed; {@code failure} carries the cause. */
    default void onFailed(Task<?> task, Throwable failure) {
    }

    /** The task was cancelled before or during its execution. */
    default void onCancelled(Task<?> task) {
    }
}