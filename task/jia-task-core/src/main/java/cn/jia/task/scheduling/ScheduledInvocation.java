package cn.jia.task.scheduling;

/** Trusted scheduler context; revision identifies this wakeup, not a financial charge period. */
public record ScheduledInvocation(String tenantId, ScheduleKey key, long revision) { }
