package cn.jia.task.scheduling;

import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;

/** Explicit opt-in for durable callbacks. Must reconcile authoritative business state idempotently.
 * Never implement this by blindly replaying an external paid operation with an unknown result.
 * Existing JobHandlers are not automatically migrated/enabled by their cron metadata.
 */
public interface ScheduledJobHandler extends JobHandler {
    /** CONTROL is reserved for short database-only callbacks; external I/O remains GENERAL. */
    enum ExecutionClass { CONTROL, GENERAL }
    default ExecutionClass executionClass() { return ExecutionClass.GENERAL; }
    void executeScheduled(ScheduledInvocation invocation) throws Exception;
    @Override default String execute(JobContext context) {
        throw new UnsupportedOperationException("Durable job requires scheduler-owned context");
    }
}
