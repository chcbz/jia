package cn.jia.core.deadline;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Thread-confined request deadline scope with explicit capture for executor hand-off. */
public final class RequestDeadlineContext {
    private static final ThreadLocal<RequestDeadline> CURRENT = new ThreadLocal<>();

    private RequestDeadlineContext() {
    }

    public static Optional<RequestDeadline> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope open(RequestDeadline deadline) {
        return install(Objects.requireNonNull(deadline, "deadline"));
    }

    /** Captures the current value, including an intentionally empty context, for a later executor hand-off. */
    public static Snapshot capture() {
        return new Snapshot(CURRENT.get());
    }

    public static Runnable wrap(Runnable task) {
        return capture().wrap(task);
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        return capture().wrap(task);
    }

    private static Scope install(RequestDeadline deadline) {
        RequestDeadline previous = CURRENT.get();
        if (deadline == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(deadline);
        }
        return new Scope(previous, Thread.currentThread());
    }

    public static final class Snapshot {
        private final RequestDeadline captured;

        private Snapshot(RequestDeadline captured) {
            this.captured = captured;
        }

        public Runnable wrap(Runnable task) {
            if (task == null) {
                throw new IllegalArgumentException("task must not be null");
            }
            return () -> {
                try (Scope ignored = install(captured)) {
                    task.run();
                }
            };
        }

        public <T> Callable<T> wrap(Callable<T> task) {
            if (task == null) {
                throw new IllegalArgumentException("task must not be null");
            }
            return () -> {
                try (Scope ignored = install(captured)) {
                    return task.call();
                }
            };
        }
    }

    public static final class Scope implements AutoCloseable {
        private final RequestDeadline previous;
        private final Thread owner;
        private boolean closed;

        private Scope(RequestDeadline previous, Thread owner) {
            this.previous = previous;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("request deadline scope must be closed by its owner thread");
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
