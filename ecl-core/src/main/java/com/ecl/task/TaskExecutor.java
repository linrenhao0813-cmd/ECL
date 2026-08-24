package com.ecl.task;

import com.ecl.util.ThreadFactories;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link Task} instances, resolving their dependencies and forwarding lifecycle events to a
 * {@link TaskListener}.
 *
 * <p>Dependencies of a task run first, on the same worker thread, in declaration order. This gives
 * deterministic ordering (a composite's body always observes its prerequisites' side effects) while
 * the enclosing executor can still run independent top-level tasks concurrently. A failed or
 * cancelled dependency aborts the remainder of the graph: dependent tasks never start.</p>
 *
 * <p>Callers cancel work through {@link #cancel(TaskFuture)} or {@link #cancel(Task)}, which both
 * interrupt the worker thread and set the task's cooperative cancellation flag. A task body reacting
 * to interruption or polling {@link Task#isCancelled()} is expected to stop and throw
 * {@link TaskCancellationException}; {@link InterruptedException} is mapped to it as well.</p>
 */
public final class TaskExecutor implements AutoCloseable {

    private final ExecutorService executor;
    private final TaskListener listener;
    private final AtomicInteger completionGate = new AtomicInteger();

    public TaskExecutor() {
        this(null, null);
    }

    public TaskExecutor(TaskListener listener) {
        this(null, listener);
    }

    /**
     * @param delegate executor used to run tasks; when null a daemon cached thread pool is created
     * @param listener lifecycle listener; progress events are forwarded here, may be null
     */
    public TaskExecutor(Executor delegate, TaskListener listener) {
        this.executor = delegate == null
                ? Executors.newCachedThreadPool(ThreadFactories.daemon("ecl-task"))
                : adaptExecutor(delegate);
        this.listener = listener;
    }

    private static ExecutorService adaptExecutor(Executor delegate) {
        if (delegate instanceof ExecutorService service) {
            return service;
        }
        // A plain Executor has no lifecycle; track one here so shutdown semantics stay uniform.
        ExecutorService control = Executors.newSingleThreadExecutor();
        control.shutdown();
        return new Adapter(control, delegate);
    }

    /** Submit a task graph for execution. The root's dependencies run before the root itself. */
    public <T> TaskFuture<T> submit(Task<T> task) {
        dispatch(TaskEvent.queued(task));
        FutureTask<T> future = new FutureTask<>(new TaskRunner<>(task));
        task.attach(this);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException rejected) {
            task.detach();
            dispatch(TaskEvent.failed(task, rejected));
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(rejected);
            return new TaskFuture<>(task, failed);
        }
        return new TaskFuture<>(task, future);
    }

    /** Submit a task and block until it completes, unwrapping failures. */
    public <T> T await(Task<T> task) {
        return submit(task).awaitUnchecked();
    }

    /** Cancel a running task: interrupts the worker and sets the cooperative flag. */
    public boolean cancel(TaskFuture<?> future) {
        return future != null && future.cancel(true);
    }

    /** Set the cooperative cancellation flag on a task not yet submitted or still starting. */
    public boolean cancel(Task<?> task) {
        if (task == null) {
            return false;
        }
        task.cancel();
        return task.isCancelled();
    }

    /** Number of tasks (roots and dependencies) that reached a terminal state. Diagnostic aid only. */
    public int completedTaskCount() {
        return completionGate.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (executor instanceof Adapter adapter) {
            adapter.shutdown();
        }
    }

    /** Route a progress report for {@code task} to the shared listener. Called from {@link Task}. */
    void notifyProgress(Task<?> task, double fraction) {
        dispatch(TaskEvent.progress(task, fraction));
    }

    private void dispatch(TaskEvent event) {
        TaskListener target = listener;
        if (target == null) {
            return;
        }
        Task<?> task = event.task();
        switch (event.phase()) {
            case QUEUED -> target.onQueued(task);
            case STARTED -> target.onStarted(task);
            case PROGRESS -> target.onProgress(task, event.progressFraction());
            case FINISHED -> target.onFinished(task);
            case FAILED -> target.onFailed(task, event.failure());
            case CANCELLED -> target.onCancelled(task);
        }
    }

    private static boolean isCancellation(Throwable failure) {
        return failure instanceof TaskCancellationException
                || failure instanceof InterruptedException;
    }

    private final class TaskRunner<T> implements Callable<T> {
        private final Task<T> task;
        private final java.util.Set<Task<?>> executed = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());
        private final java.util.Set<Task<?>> inProgress = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());

        TaskRunner(Task<T> task) {
            this.task = task;
        }

        @Override
        public T call() throws Exception {
            try {
                @SuppressWarnings("unchecked")
                T result = (T) finishAndExecute(task);
                return result;
            } finally {
                completionGate.incrementAndGet();
                task.detach();
            }
        }

        /**
         * Traverse the dependency sub-graph of {@code node}: prerequisites first (recursively),
         * then the node itself. A node already run in this graph is skipped, so shared dependencies
         * execute exactly once. Terminal events ({@code finished}/{@code failed}/{@code cancelled})
         * are dispatched here, for the root and every dependency alike.
         */
        private Object finishAndExecute(Task<?> node) throws Exception {
            if (executed.contains(node)) {
                return null;
            }
            if (!inProgress.add(node)) {
                throw new TaskCancellationException("Cyclic task dependency at " + node.name());
            }
            CompletableFuture<Object> shared = node.executionFuture();
            if (!node.claimExecution()) {
                try {
                    return shared.get();
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw rethrow(failed.getCause());
                } finally {
                    inProgress.remove(node);
                    executed.add(node);
                }
            }
            try {
                if (node.isCancelled()) {
                    throw TaskCancellationException.of(node);
                }
                for (Task<?> dependency : node.dependencySnapshot()) {
                    runDependency(dependency);
                }
                if (node.isCancelled()) {
                    throw TaskCancellationException.of(node);
                }
                dispatch(TaskEvent.started(node));
                node.attach(TaskExecutor.this);
                node.beforeExecute();
                Object result = node.execute();
                if (node.isCancelled()) {
                    throw TaskCancellationException.of(node);
                }
                node.afterExecute();
                dispatch(TaskEvent.finished(node));
                shared.complete(result);
                return result;
            } catch (Throwable failure) {
                shared.completeExceptionally(failure);
                if (isCancellation(failure)) {
                    dispatch(TaskEvent.cancelled(node));
                    if (failure instanceof InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new TaskCancellationException(
                                "Interrupted task: " + node.name(), interrupted);
                    }
                    throw failure instanceof TaskCancellationException cancellation
                            ? cancellation : TaskCancellationException.of(node);
                }
                dispatch(TaskEvent.failed(node, failure));
                throw rethrow(failure);
            } finally {
                inProgress.remove(node);
                executed.add(node);
                node.detach();
            }
        }

        /** Execute a dependency of the current node (with its own prerequisites). */
        private void runDependency(Task<?> dependency) throws Exception {
            finishAndExecute(dependency);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    /** Bridges an arbitrary non-service {@link Executor} into the ExecutorService contract. */
    private static final class Adapter implements ExecutorService {
        private final ExecutorService control;
        private final Executor delegate;

        Adapter(ExecutorService control, Executor delegate) {
            this.control = control;
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            control.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return control.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return control.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return control.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return control.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> callable) {
            FutureTask<T> future = new FutureTask<>(callable);
            delegate.execute(future);
            return future;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable runnable, T result) {
            FutureTask<T> future = new FutureTask<>(runnable, result);
            delegate.execute(future);
            return future;
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable runnable) {
            FutureTask<Void> future = new FutureTask<>(runnable, null);
            delegate.execute(future);
            return future;
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks,
                               long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
