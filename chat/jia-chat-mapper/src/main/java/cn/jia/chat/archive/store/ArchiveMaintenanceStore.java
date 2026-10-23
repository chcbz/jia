package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import jakarta.inject.Named;
import java.util.List;

@Named
public class ArchiveMaintenanceStore {
    public record Job(String id,String operationKey,String requestDigest,String agentId,String state,
            String specification,String resultManifest,String resultDigest,String errorCode) {
        public Job(String id,String operationKey,String requestDigest,String agentId,String state,String specification,String resultManifest,String resultDigest) {
            this(id,operationKey,requestDigest,agentId,state,specification,resultManifest,resultDigest,null);
        }
    }
    private final JdbcTemplate jdbc;
    private static final String WHERE=" tenant_id=? AND client_id=? AND owner_jiacn=? ";
    private static final RowMapper<Job> MAP=(r,n)->new Job(r.getString("job_id"),r.getString("operation_key"),
            r.getString("request_digest"),r.getString("agent_id"),r.getString("state"),r.getString("specification"),
            r.getString("result_manifest"),r.getString("result_digest"),r.getString("error_code"));
    public ArchiveMaintenanceStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Job> list(ArchiveOwnerScope s){return jdbc.query("SELECT * FROM archive_skill_maintenance_job WHERE "+WHERE+" ORDER BY created_at DESC",MAP,s.tenantId(),s.clientId(),s.ownerJiacn());}
    public Job find(ArchiveOwnerScope s,String id,boolean lock){return first(jdbc.query("SELECT * FROM archive_skill_maintenance_job WHERE "+WHERE+" AND job_id=?"+(lock?" FOR UPDATE":""),MAP,s.tenantId(),s.clientId(),s.ownerJiacn(),id));}
    public Job operation(ArchiveOwnerScope s,String key){return operation(s,key,false);}
    public Job operation(ArchiveOwnerScope s,String key,boolean lock){return first(jdbc.query("SELECT * FROM archive_skill_maintenance_job WHERE "+WHERE+" AND operation_key=?"+(lock?" FOR UPDATE":""),MAP,s.tenantId(),s.clientId(),s.ownerJiacn(),key));}
    public void insert(ArchiveOwnerScope s,Job j){jdbc.update("INSERT INTO archive_skill_maintenance_job(tenant_id,client_id,owner_jiacn,job_id,operation_key,request_digest,agent_id,state,specification) VALUES(?,?,?,?,?,?,?,?,?)",s.tenantId(),s.clientId(),s.ownerJiacn(),j.id(),j.operationKey(),j.requestDigest(),j.agentId(),j.state(),j.specification());}
    public int complete(ArchiveOwnerScope s,String id,String state,String manifest,String digest){return jdbc.update("UPDATE archive_skill_maintenance_job SET state=?,result_manifest=?,result_digest=?,completed_at=CURRENT_TIMESTAMP(6) WHERE "+WHERE+" AND job_id=? AND state='WAITING'",state,manifest,digest,s.tenantId(),s.clientId(),s.ownerJiacn(),id);}
    public int fail(ArchiveOwnerScope s,String id,String code,String digest){return jdbc.update("UPDATE archive_skill_maintenance_job SET state='FAILED',error_code=?,result_digest=?,completed_at=CURRENT_TIMESTAMP(6) WHERE "+WHERE+" AND job_id=? AND state='WAITING'",code,digest,s.tenantId(),s.clientId(),s.ownerJiacn(),id);}
    public String publicationOperation(ArchiveOwnerScope s,String key){return publicationOperation(s,key,false);}
    public String publicationOperation(ArchiveOwnerScope s,String key,boolean lock){var rows=jdbc.query("SELECT job_id FROM archive_skill_maintenance_action WHERE "+WHERE+" AND operation_key=?"+(lock?" FOR UPDATE":""),(r,n)->r.getString(1),s.tenantId(),s.clientId(),s.ownerJiacn(),key);return rows.isEmpty()?null:rows.getFirst();}
    public void publicationOperation(ArchiveOwnerScope s,String key,String id){jdbc.update("INSERT INTO archive_skill_maintenance_action(tenant_id,client_id,owner_jiacn,operation_key,job_id,action_key) VALUES(?,?,?,?,?,'PUBLISH_DRAFT')",s.tenantId(),s.clientId(),s.ownerJiacn(),key,id);}
    public int publishDraft(ArchiveOwnerScope s,String id){return jdbc.update("UPDATE archive_skill_maintenance_job SET state='PUBLISHED',completed_at=CURRENT_TIMESTAMP(6) WHERE "+WHERE+" AND job_id=? AND state='DRAFT'",s.tenantId(),s.clientId(),s.ownerJiacn(),id);}
    private static Job first(List<Job> rows){return rows.isEmpty()?null:rows.getFirst();}
}
