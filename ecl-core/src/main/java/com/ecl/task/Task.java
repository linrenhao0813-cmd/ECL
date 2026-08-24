package com.ecl.task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A unit of asynchronous work with an optional dependency graph.
 *
 * <p>A task declares a {@link #execute()} body and pumps state transitions to the
 * {@link TaskListener} of the {@link TaskExecutor} that runs it. Tasks run once; after a task
 * has finished, failed, or been cancelled it must not be submitted again.</p>
 *
 * <p>Two kinds of tasks exist in a workflow: <em>leaves</em> that carry out real work
 * (a download, a filesystem copy) and <em>composites</em> that declare other tasks as
 * dependencies and orchestrate them. Composites register children through
 * {@link #dependsOn(Task...)}; the executor guarantees every dependency completes (or fails)
 * before the composite body runs.</p>
 *
 * @param <T> the result type produced by {@link #execute()}
 */
public abstract class Task<T> {

    private final String name;
    private final double weight;
    private final List<Task<?>> dependencies = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile double progressFraction;
    private transient TaskExecutor executor;
    private transient CompletableFuture<Object> executionFuture;
    private transient boolean executionClaimed;

    protected Task(String name) {
        this(name, 1.0);
    }

    /**
     * @param weight relative cost used for overall-work estimation; must be positive
     */
    protected Task(String name, double weight) {
        this.name = name;
        if (!(weight > 0.0) || Double.isNaN(weight) || Double.isInfinite(weight)) {
            throw new IllegalArgumentException("Task weight must be a positive finite number");
        }
        this.weight = weight;
    }

    /** Repeatedly execute the task body; must not throw checked exceptions it cannot explain. */
    protected abstract T execute() throws Exception;

    /** Called once before {@link #execute()} when the task is still pending. Subclasses may override. */
    protected void beforeExecute() {
    }

    /** Called once after {@link #execute()} returns normally. Subclasses may override. */
    protected void afterExecute() {
    }

    /** Human-readable description shown in logs and progress reports. */
    public String name() {
        return name;
    }

    /** Relative cost used when estimating the share of overall work this task represents. */
    public double weight() {
        return weight;
    }

    /** Register prerequisite tasks. Dependencies complete before this task is executed. */
    public final Task<T> dependsOn(Task<?>... dependsOn) {
        if (dependsOn != null) {
            synchronized (dependencies) {
                for (Task<?> dependency : dependsOn) {
                    if (dependency != null && dependency != this) {
                        dependencies.add(dependency);
                    }
                }
            }
        }
        return this;
    }

    /** Snapshot of the current dependency list; the executor reads this exactly once. */
    final List<Task<?>> dependencySnapshot() {
        synchronized (dependencies) {
            return List.copyOf(dependencies);
        }
    }

    /** Request cooperative cancellation. A running body should poll {@link #isCancelled()}. */
    public final void cancel() {
        cancelled = true;
    }

    /** True once {@link #cancel()} has been called. */
    public final boolean isCancelled() {
        return cancelled;
    }

    /**
     * Report overall progress as a fraction of this task's own work.
     * Only progress between {@code 0.0} and {@code 1.0} is forwarded.
     */
    protected final void reportProgress(double fraction) {
        if (Double.isNaN(fraction)) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        progressFraction = clamped;
        TaskExecutor current = executor;
        if (current != null) {
            current.notifyProgress(this, clamped);
        }
    }

    /** Convenience progress reporting in done/total units. */
    protected final void reportProgress(long done, long total) {
        reportProgress(total <= 0 ? 0.0 : (double) done / total);
    }

    /** Last reported progress; defaults to 0.0. */
    public double progressFraction() {
        return progressFraction;
    }

    /** Binds this task to the executor that runs it so progress events can be routed. */
    final void attach(TaskExecutor executor) {
        this.executor = executor;
    }

    final void detach() {
        this.executor = null;
    }

    final synchronized CompletableFuture<Object> executionFuture() {
        if (executionFuture == null) {
            executionFuture = new CompletableFuture<>();
        }
        return executionFuture;
    }

    final synchronized boolean claimExecution() {
        if (executionClaimed) {
            return false;
        }
        executionClaimed = true;
        return true;
    }
}
