package cn.jia.core.ldap;

import java.util.concurrent.TimeUnit;

/**
 * Thread-confined LDAP operation budget for a single controller invocation.
 * It only starts an LDAP operation when the remaining budget can still cover
 * its configured worst-case phases; it does not interrupt an operation already sent.
 */
public final class LdapRequestBudgetContext {
    private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();

    private LdapRequestBudgetContext() {
    }

    public static Scope open(long totalMillis, long safetyMarginMillis, long maximumOperationMillis) {
        if (totalMillis <= 0 || safetyMarginMillis < 0 || maximumOperationMillis <= 0
                || safetyMarginMillis >= totalMillis
                || maximumOperationMillis > totalMillis - safetyMarginMillis) {
            throw new IllegalArgumentException("LDAP budget must leave time for its longest operation");
        }
        Budget previous = CURRENT.get();
        CURRENT.set(new Budget(totalMillis, safetyMarginMillis, maximumOperationMillis, System.nanoTime()));
        return new Scope(previous, Thread.currentThread());
    }

    public static void requireBudgetBeforeNewOperation() {
        Budget budget = CURRENT.get();
        if (budget != null && budget.remainingForNewOperationMillis() <= 0) {
            throw new LdapBudgetExhaustedException();
        }
    }

    public static final class LdapBudgetExhaustedException extends RuntimeException {
        public LdapBudgetExhaustedException() {
            super("LDAP request budget exhausted before a new operation");
        }
    }

    private static final class Budget {
        private final long totalNanos;
        private final long safetyMarginNanos;
        private final long maximumOperationNanos;
        private final long startedNanos;

        private Budget(long totalMillis, long safetyMarginMillis, long maximumOperationMillis, long startedNanos) {
            this.totalNanos = TimeUnit.MILLISECONDS.toNanos(totalMillis);
            this.safetyMarginNanos = TimeUnit.MILLISECONDS.toNanos(safetyMarginMillis);
            this.maximumOperationNanos = TimeUnit.MILLISECONDS.toNanos(maximumOperationMillis);
            this.startedNanos = startedNanos;
        }

        private long remainingForNewOperationMillis() {
            long elapsedNanos = System.nanoTime() - startedNanos;
            long remainingNanos = totalNanos - Math.max(0L, elapsedNanos) - safetyMarginNanos;
            if (remainingNanos < maximumOperationNanos) {
                return 0;
            }
            return TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Budget previous;
        private final Thread owner;
        private boolean closed;

        private Scope(Budget previous, Thread owner) {
            this.previous = previous;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("LDAP budget scope must be closed by its owner thread");
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
