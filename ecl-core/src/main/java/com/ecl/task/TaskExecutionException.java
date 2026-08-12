package com.ecl.task;

/**
 * Unchecked wrapper for a task that failed. Carries the underlying cause so callers of
 * {@link TaskFuture#awaitUnchecked()} do not have to trap checked exceptions.
 */
public class TaskExecutionException extends RuntimeException {

    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}