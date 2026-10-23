package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class ArchiveMaintenanceStore {
    public record Job(String id,String operationKey,String requestDigest,String agentId,String state,
            String specification,String resultManifest,String resultDigest) { }
    private final JdbcTemplate jdbc;
    private static final String WHERE=" tenant_id=? AND client_id=? AND owner_jiacn=? ";
    private static final RowMapper<Job> MAP=(r,n)->new Job(r.getString("job_id"),r.getString("operation_key"),
            r.getString("request_digest"),r.getString("agent_id"),r.getString("state"),r.getString("specification"),
            r.getString("result_manifest"),r.getString("result_digest"));
    public ArchiveMaintenanceStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Job> list(ArchiveOwnerScope s){return jdbc.query("SELECT * FROM archive_maintenance_job WHERE "+WHERE+" ORDER BY created_at DESC",MAP,s.tenantId(),s.clientId(),s.ownerJiacn());}
    public Job find(ArchiveOwnerScope s,String id,boolean lock){return first(jdbc.query("SELECT * FROM archive_maintenance_job WHERE "+WHERE+" AND job_id=?"+(lock?" FOR UPDATE":""),MAP,s.tenantId(),s.clientId(),s.ownerJiacn(),id));}
    public Job operation(ArchiveOwnerScope s,String key){return first(jdbc.query("SELECT * FROM archive_maintenance_job WHERE "+WHERE+" AND operation_key=?",MAP,s.tenantId(),s.clientId(),s.ownerJiacn(),key));}
    public void insert(ArchiveOwnerScope s,Job j){jdbc.update("INSERT INTO archive_maintenance_job(tenant_id,client_id,owner_jiacn,job_id,operation_key,request_digest,agent_id,state,specification) VALUES(?,?,?,?,?,?,?,?,?)",s.tenantId(),s.clientId(),s.ownerJiacn(),j.id(),j.operationKey(),j.requestDigest(),j.agentId(),j.state(),j.specification());}
    public int complete(ArchiveOwnerScope s,String id,String state,String manifest,String digest){return jdbc.update("UPDATE archive_maintenance_job SET state=?,result_manifest=?,result_digest=?,completed_at=CURRENT_TIMESTAMP(6) WHERE "+WHERE+" AND job_id=? AND state='WAITING'",state,manifest,digest,s.tenantId(),s.clientId(),s.ownerJiacn(),id);}
    private static Job first(List<Job> rows){return rows.isEmpty()?null:rows.getFirst();}
}
