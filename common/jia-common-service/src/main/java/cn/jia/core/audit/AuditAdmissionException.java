package cn.jia.core.audit;

import java.util.concurrent.RejectedExecutionException;

/** Raised before business execution when an audit record cannot be admitted without blocking. */
public final class AuditAdmissionException extends RejectedExecutionException {
    public AuditAdmissionException(String message) {
        super(message);
    }
}
