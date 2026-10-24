package cn.jia.common.mybatis;

/** Fail-closed exception raised when a statement cannot be proven tenant-safe. */
public final class TenantIsolationException extends IllegalStateException {
    public TenantIsolationException(String message) {
        super(message);
    }

    public TenantIsolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
