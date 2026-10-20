package foundry.veil.impl;

import foundry.veil.Veil;
import foundry.veil.api.TickTaskScheduler;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
public class TickTaskSchedulerImpl implements TickTaskScheduler {

    private final Queue<Task<?>> tasks;
    private long tick;
    private volatile boolean stopped;

    public TickTaskSchedulerImpl() {
        this.tasks = new PriorityBlockingQueue<>();
        this.tick = 0;
        this.stopped = false;
    }

    /**
     * Runs a single tick and executes all pending tasks for that time.
     */
    public void run() {
        Iterator<Task<?>> iterator = this.tasks.iterator();
        while (iterator.hasNext()) {
            Task<?> task = iterator.next();
            if (task.isDone()) {
                iterator.remove();
                continue;
            }
            if (this.tick < task.executionTick) {
                break;
            }

            try {
                task.runnable.run();
                task.finish(null);
            } catch (Throwable t) {
                Veil.LOGGER.error("Failed to execute task", t);
                task.finish(t);
            }
            iterator.remove();
        }
        this.tick++;
    }

    /**
     * Stops the scheduler and runs all pending tasks as quickly as possible.
     */
    public void shutdown() {
        this.stopped = true;

        Iterator<Task<?>> iterator = this.tasks.iterator();
        while (iterator.hasNext()) {
            Task<?> task = iterator.next();
            if (task.isDone()) {
                iterator.remove();
                continue;
            }

            try {
                task.runnable.run();
                task.finish(null);
            } catch (Throwable t) {
                Veil.LOGGER.error("Failed to execute task", t);
                task.finish(t);
            }
            iterator.remove();
        }
        if (!this.tasks.isEmpty()) {
            throw new IllegalStateException(this.tasks.size() + " tasks were first over!");
        }
    }

    private void validate(Object command) {
        Objects.requireNonNull(command);
        if (this.stopped) {
            throw new RejectedExecutionException();
        }
    }

    @Override
    public void execute(@NotNull Runnable command) {
        this.validate(command);
        this.tasks.add(new Task<>(command, 0));
    }

    @Override
    public TickTask<?> schedule(@NotNull Runnable command, long delay) {
        this.validate(command);
        if (delay < 0) {
            throw new IllegalArgumentException();
        }

        TickTaskImpl<?> tickTask = new TickTaskImpl<>();
        Task<?> task = new Task<>(() -> {
            try {
                command.run();
            } catch (Throwable t) {
                tickTask.future.completeExceptionally(t);
            }
        }, this.tick + delay);
        this.tasks.add(task);
        tickTask.setTask(task);

        tickTask.future.exceptionally(e -> {
            if (tickTask.future.isCancelled()) {
                task.cancel(false);
            }
            return null;
        });
        return tickTask;
    }

    @Override
    public <V> TickTask<V> schedule(@NotNull Callable<V> callable, long delay) {
        this.validate(callable);
        if (delay < 0) {
            throw new IllegalArgumentException();
        }

        TickTaskImpl<V> tickTask = new TickTaskImpl<>();
        Task<?> task = new Task<>(() -> {
            try {
                tickTask.future.complete(callable.call());
            } catch (Throwable t) {
                tickTask.future.completeExceptionally(t);
            }
        }, this.tick + delay);
        this.tasks.add(task);
        tickTask.setTask(task);

        tickTask.future.exceptionally(e -> {
            if (tickTask.future.isCancelled()) {
                task.cancel(false);
            }
            return null;
        });
        return tickTask;
    }

    @Override
    public TickTask<?> scheduleAtFixedRate(@NotNull Runnable command, long initialDelay, long period) {
        this.validate(command);
        if (initialDelay < 0 || period < 0) {
            throw new IllegalArgumentException();
        }

        TickTaskImpl<?> tickTask = new TickTaskImpl<>();
        Task<?> task = this.schedule(tickTask, command, new AtomicBoolean(), initialDelay, period);
        this.tasks.add(task);
        tickTask.setTask(task);

        tickTask.future.exceptionally(e -> {
            if (tickTask.future.isCancelled()) {
                task.cancel(false);
            }
            return null;
        });
        return tickTask;
    }

    private <V> Task<V> schedule(TickTaskImpl<V> tickTask, Runnable command, AtomicBoolean cancelled, long delay, long period) {
        return new Task<>(() -> {
            try {
                command.run();
                if (!this.stopped) {
                    Task<V> task = this.schedule(tickTask, command, cancelled, period, period);
                    this.tasks.add(task);
                    tickTask.setTask(task);
                }
            } catch (Throwable t) {
                tickTask.future.completeExceptionally(t);
            }
        }, cancelled, this.tick + delay);
    }

    @Override
    public boolean isShutdown() {
        return this.stopped;
    }

    private class TickTaskImpl<V> implements TickTask<V> {

        private final CompletableFuture<V> future;
        private Task<?> task;

        private TickTaskImpl() {
            this.future = new CompletableFuture<>();
            this.task = null;
        }

        @Override
        public long getDelay() {
            return this.task.getDelay();
        }

        @Override
        public CompletableFuture<V> toCompletableFuture() {
            return this.future;
        }

        @Override
        public long getDelay(@NotNull TimeUnit unit) {
            return this.task.getDelay(unit);
        }

        @Override
        public int compareTo(@NotNull Delayed o) {
            return this.task.compareTo(o);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return this.task.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return this.task.isCancelled();
        }

        @Override
        public boolean isDone() {
            return this.task.isDone();
        }

        @SuppressWarnings("unchecked")
        @Override
        public V get() throws ExecutionException {
            return (V) this.task.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public V get(long timeout, @NotNull TimeUnit unit) throws ExecutionException {
            return (V) this.task.get(timeout, unit);
        }

        public void setTask(Task<?> task) {
            this.task = task;
        }
    }

    private class Task<V> implements ScheduledFuture<V> {

        private final Runnable runnable;
        private final AtomicBoolean cancelled;
        private final long executionTick;
        private boolean complete;
        private Throwable error;

        private Task(Runnable task, long executionTick) {
            this(task, new AtomicBoolean(), executionTick);
        }

        private Task(Runnable task, AtomicBoolean cancelled, long executionTick) {
            this.runnable = task;
            this.cancelled = cancelled;
            this.executionTick = executionTick;
        }

        public void finish(@Nullable Throwable error) {
            this.complete = true;
            this.error = error;
        }

        public long getDelay() {
            return this.executionTick - TickTaskSchedulerImpl.this.tick;
        }

        @Override
        public long getDelay(@NotNull TimeUnit unit) {
            return TimeUnit.MILLISECONDS.convert((this.executionTick - TickTaskSchedulerImpl.this.tick) * 50L, unit);
        }

        @Override
        public int compareTo(@NotNull Delayed o) {
            return Long.compareUnsigned(this.executionTick, ((Task<?>) o).executionTick);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return this.cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled.get();
        }

        @Override
        public boolean isDone() {
            return this.complete || this.cancelled.get();
        }

        @Override
        public V get() throws ExecutionException {
            if (this.error != null) {
                throw new ExecutionException(this.error);
            }
            return null;
        }

        @Override
        public V get(long timeout, @NotNull TimeUnit unit) throws ExecutionException {
            return this.get();
        }
    }
}
