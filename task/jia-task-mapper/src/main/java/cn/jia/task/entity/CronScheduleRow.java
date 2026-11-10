package cn.jia.task.entity;

import cn.jia.task.scheduling.CronSchedule;
import lombok.Data;

/** Durable cron cursor, separate from the execution queue's claims and retry revisions. */
@Data
public class CronScheduleRow {
    private String tenantId;
    private String clientId;
    private String handler;
    private String targetNode;
    private String expression;
    private String zoneId;
    private String misfire;
    private boolean enabled;
    private Long nextFireAt;

    public CronSchedule definition() {
        return new CronSchedule(clientId, handler, targetNode, expression, zoneId, CronSchedule.Misfire.valueOf(misfire));
    }
}
