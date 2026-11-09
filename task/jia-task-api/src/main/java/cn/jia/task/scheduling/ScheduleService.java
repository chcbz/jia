package cn.jia.task.scheduling;

/** Same-database durable registration, callable from an existing business transaction. */
public interface ScheduleService {
    void signal(ScheduleKey key, long at);
    void reschedule(ScheduleKey key, long at);
    void cancel(ScheduleKey key);
}
