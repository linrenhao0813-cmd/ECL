package com.ecl.task;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Result handle for a task accepted by a {@link TaskExecutor}. Wraps the underlying JDK future
 * and exposes the originating {@link Task} so cancellation and progress can be coordinated.
 */
public final class TaskFuture<T> implements Future<T> {

    private final Task<T> task;
    private final Future<T> delegate;

    TaskFuture(Task<T> task, Future<T> delegate) {
        this.task = task;
        this.delegate = delegate;
    }

    /** The task this future belongs to. Never null. */
    public Task<T> task() {
        return task;
    }

    /** Block until the task completes and return its result. Cancellation propagates. */
    public T await() throws InterruptedException, ExecutionException, TaskCancellationException {
        try {
            return delegate.get();
        } catch (CancellationException cancelled) {
            throw TaskCancellationException.of(task);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof TaskCancellationException
                    || failed.getCause() instanceof InterruptedException) {
                throw TaskCancellationException.of(task);
            }
            throw failed;
        }
    }

    /** Block until the task completes, wrapping failures in {@link TaskExecutionException}. */
    public T awaitUnchecked() throws TaskExecutionException {
        try {
            return await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException("Interrupted while waiting for task: " + task.name(), interrupted);
        } catch (ExecutionException failed) {
            throw new TaskExecutionException(task.name(), failed.getCause());
        } catch (TaskCancellationException cancelled) {
            throw new TaskExecutionException(cancelled.getMessage(), cancelled);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // Ensure the cooperative flag is set even when the delegate is not cancelable yet
        // (for example while the runner is still queued ahead of an earlier task).
        task.cancel();
        return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
        return delegate.isDone();
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        return delegate.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.get(timeout, unit);
    }
}