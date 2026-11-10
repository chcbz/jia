package cn.jia.task.scheduling;

/** Trusted module API; no HTTP registration or automatic activation of JobHandler.getCron(). */
public interface CronScheduleService {
    /** Same definition preserves its durable cursor on restart. A changed definition affects future work only. */
    void register(CronSchedule schedule);
    /** Stop future occurrences; already committed occurrences and running business work are not interrupted. */
    void cancel(String clientId, String handler, String targetNode);
}
