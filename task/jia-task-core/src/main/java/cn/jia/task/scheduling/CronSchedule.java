package cn.jia.task.scheduling;

/** Explicit internal registration: one cron per client/handler/node, never inferred from legacy metadata. */
public record CronSchedule(String clientId, String handler, String targetNode,
        String expression, String zoneId, Misfire misfire) {
    public enum Misfire {
        /** Reconcile once for the oldest pending occurrence, then schedule after the current DB time. */
        COALESCE,
        /** Drop an occurrence only once a later occurrence is also due; ordinary dispatch latency is not a miss. */
        SKIP
    }
    public ScheduleKey occurrence(long scheduledAt) {
        return new ScheduleKey(clientId, handler, "cron:" + scheduledAt, targetNode);
    }
}
