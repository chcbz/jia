package cn.jia.core.audit;

/**
 * Bounded dispatch contract for already-sanitized audit writes.
 *
 * <p>{@link #dispatch(Runnable)} is fail-closed and is suitable for access audit admission before
 * downstream side effects. {@link #notifyAfterCommit(Runnable)} is only a best-effort wake-up for
 * a durable transactional outbox; the outbox, never this callback, must remain the source of truth.
 */
public interface AuditDispatcher {
    void dispatch(Runnable auditWrite);

    void notifyAfterCommit(Runnable durableOutboxWakeUp);
}
