package cn.jia.core.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Request or worker-local tenant scope established by a trusted authentication boundary.
 *
 * <p>The context has no fallback tenant and deliberately uses a plain {@link ThreadLocal}:
 * child and pooled worker threads must establish their own authenticated scope. Callers can
 * only install a scope for the lifetime of a lexical operation; request fields are never read
 * here.</p>
 */
public final class TrustedTenantContext {
    private static final ThreadLocal<BusinessScope> CURRENT = new ThreadLocal<>();

    private TrustedTenantContext() {
    }

    public static Optional<BusinessScope> currentScope() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static BusinessScope requireScope() {
        BusinessScope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("Trusted tenant scope is unavailable");
        }
        return scope;
    }

    public static TenantId requireTenantId() {
        return requireScope().tenantId();
    }

    public static <T> T withScope(BusinessScope scope, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        BusinessScope previous = install(scope);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWithScope(BusinessScope scope, Runnable work) {
        Objects.requireNonNull(work, "work");
        withScope(scope, () -> {
            work.run();
            return null;
        });
    }

    /** Runs checked boundary work without exposing a transferable mutable scope handle. */
    public static void runWithScopeChecked(BusinessScope scope, CheckedWork work) throws Exception {
        Objects.requireNonNull(work, "work");
        BusinessScope previous = install(scope);
        try {
            work.run();
        } finally {
            restore(previous);
        }
    }

    /** Temporarily removes a trusted scope, restoring it only after the nested work exits. */
    public static void runWithoutScopeChecked(CheckedWork work) throws Exception {
        Objects.requireNonNull(work, "work");
        BusinessScope previous = CURRENT.get();
        CURRENT.remove();
        try {
            work.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * Clears the current thread at a top-level trusted boundary.
     *
     * <p>This cannot grant access and is used before and after HTTP processing so a stale pooled
     * thread value is never restored into a later request.</p>
     */
    public static void clear() {
        CURRENT.remove();
    }

    private static BusinessScope install(BusinessScope scope) {
        BusinessScope previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(scope, "scope"));
        return previous;
    }

    private static void restore(BusinessScope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    @FunctionalInterface
    public interface CheckedWork {
        void run() throws Exception;
    }

}
