package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact scoped current-state persistence. Caller owns the task-root transaction/lock. */
final class AgentTaskCurrentRequirementStore {
    private final JdbcTemplate jdbc;
    AgentTaskCurrentRequirementStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    Snapshot read(Scope scope,String taskId) {
        var rows=jdbc.query("SELECT revision,title,description,content_sha256,source FROM agent_task_current_requirement "
                        + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",
                (r,n)->new Snapshot(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                        r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        try { return rows.isEmpty()?null:AgentTaskRequirementSnapshotServiceImpl.verified(rows.getFirst(),scope,taskId); }
        catch (IllegalStateException corrupt) {throw new AgentTaskRequirementSnapshotServiceImpl.SnapshotIntegrityException(corrupt); }
    }
    void insert(Snapshot row) {
        if (jdbc.update("INSERT INTO agent_task_current_requirement "
                + "(tenant_id,client_id,owner_jiacn,task_id,revision,title,description,content_sha256,source) VALUES (?,?,?,?,?,?,?,?,?)",
                row.tenantId(),row.clientId(),row.ownerJiacn(),row.taskId(),row.revision(),row.title(),
                row.description(),row.sha256(),row.source())!=1) throw new IllegalStateException("Current requirement insert failed");
    }
    Snapshot replace(Scope scope,String taskId,long expectedRevision,String title,String description) {
        AgentTaskRequirementSnapshotServiceImpl.validateOriginalInput(title,description);
        long revision=Math.addExact(expectedRevision,1);
        String hash=AgentTaskRequirementSnapshotServiceImpl.digest(scope,taskId,revision,"RECONFIRM",title,description);
        if (jdbc.update("UPDATE agent_task_current_requirement SET revision=?,title=?,description=?,content_sha256=?,source=? "
                + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND revision=?",
                revision,title,description,hash,"RECONFIRM",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,expectedRevision)!=1)
            throw new IllegalStateException("Current requirement changed");
        return new Snapshot(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,revision,title,description,hash,"RECONFIRM");
    }
}
