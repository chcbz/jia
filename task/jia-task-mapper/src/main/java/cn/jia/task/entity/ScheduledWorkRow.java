package cn.jia.task.entity;

import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduledWork;
import lombok.Data;

/** Storage shape only; transition semantics belong to ScheduledWork. */
@Data
public class ScheduledWorkRow {
    private String tenantId;
    private String clientId;
    private String handler;
    private String businessKey;
    private String targetNode;
    private long version;
    private long requestedRevision;
    private Long dueAt;
    private String claimToken;
    private Long claimRevision;
    private Long claimUntil;
    private Long nextWakeAt;

    public ScheduleKey key() { return new ScheduleKey(clientId, handler, businessKey, targetNode); }
    public ScheduledWork state() {
        return new ScheduledWork(version, requestedRevision, dueAt,
                claimToken == null ? null : new ScheduledWork.Claim(claimToken, claimRevision, claimUntil));
    }
    public static ScheduledWorkRow of(String tenantId, ScheduleKey key, ScheduledWork state) {
        var row = new ScheduledWorkRow();
        row.tenantId = tenantId; row.clientId = key.clientId(); row.handler = key.handler();
        row.businessKey = key.businessKey(); row.targetNode = key.targetNode();
        row.version = state.version(); row.requestedRevision = state.requestedRevision();
        row.dueAt = state.dueAt(); row.nextWakeAt = state.nextWakeAt();
        if (state.claim() != null) {
            row.claimToken = state.claim().token(); row.claimRevision = state.claim().revision();
            row.claimUntil = state.claim().expiresAt();
        }
        return row;
    }
}
