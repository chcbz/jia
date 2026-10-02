package cn.jia.agent.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix="agent.platform-skills",name="enabled",havingValue="true")
public class JdbcPlatformInstallationStore implements PlatformInstallationStore {
    private final JdbcTemplate jdbc;
    public JdbcPlatformInstallationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<Installation> ROW=(r,n)->new Installation(r.getString("installation_id"),
            new Scope(r.getString("tenant_id"),r.getString("client_id"),r.getString("owner_jiacn")),
            r.getString("actor_id"),r.getString("request_key"),r.getString("request_sha256"),
            r.getString("agent_id"),r.getLong("binding_id"),r.getString("runtime_instance_id"),r.getBytes("registration_hash"),
            r.getString("skill_key"),r.getString("skill_version"),r.getString("package_sha256"),r.getString("challenge_id"),
            r.getString("command_id"),r.getString("state"),r.getString("result_sha256"),r.getString("error_code"),
            r.getLong("revision"),r.getLong("created_at"));
    record CandidateQuery(String sql,Object[] arguments) { }
    static CandidateQuery terminalCandidateQuery(ScanCursor after,long now,int limit) {
        if(now<0)throw new IllegalArgumentException("Reconciliation time must be non-negative");
        if(limit<1 || limit>100)throw new IllegalArgumentException("Reconciliation batch must be between 1 and 100");
        String keyset=after==null?"":" AND (i.created_at>? OR (i.created_at=? AND i.installation_id>?))";
        String sql="""
            SELECT i.* FROM agent_platform_skill_installation i
            JOIN agent_command_delivery d ON d.tenant_id=i.tenant_id AND d.client_id=i.client_id
                AND d.owner_jiacn=i.owner_jiacn AND d.command_id=i.command_id
                AND d.task_id=i.installation_id AND d.target_agent_id=i.agent_id
            WHERE i.origin='PLATFORM_PROVISIONED' AND i.state='REQUESTED' AND i.result_sha256 IS NULL
                AND d.command_type='PLATFORM_SKILL_INSTALL'
                AND (d.status IN ('DEAD','EXPIRED','CANCELLED','FAILED')
                    OR (d.status='SUCCEEDED' AND d.expires_at IS NOT NULL AND d.expires_at<=?))
            """+keyset+" ORDER BY i.created_at,i.installation_id LIMIT ?";
        return after==null
                ?new CandidateQuery(sql,new Object[]{now,limit})
                :new CandidateQuery(sql,new Object[]{now,after.createdAt(),after.createdAt(),after.installationId(),limit});
    }
    static CandidateQuery verifiedCandidateQuery(ResolutionKey key) {
        return resolutionQuery(key,"""
                AND i.state='SUCCEEDED' AND i.result_sha256 REGEXP '^[0-9a-f]{64}$'
                AND i.error_code IS NULL AND d.command_type='PLATFORM_SKILL_INSTALL'
            ORDER BY i.created_at DESC,i.installation_id DESC LIMIT 1
            """,new Object[0]);
    }
    static CandidateQuery pendingCandidateQuery(ResolutionKey key,long now) {
        if(now<0) throw new IllegalArgumentException("Resolution time must be non-negative");
        return resolutionQuery(key,"""
                AND i.state='REQUESTED' AND i.result_sha256 IS NULL AND i.error_code IS NULL
                AND d.command_type='PLATFORM_SKILL_INSTALL'
                AND d.status IN ('PENDING','CONSUMED','SENT','RECEIVED','STARTED','SUCCEEDED')
                AND d.expires_at IS NOT NULL AND d.expires_at>?
            ORDER BY i.created_at DESC,i.installation_id DESC LIMIT 1
            """,new Object[]{now});
    }
    private static CandidateQuery resolutionQuery(ResolutionKey key,String suffix,Object[] extra) {
        if(key==null || key.scope()==null) throw new IllegalArgumentException("Resolution key is required");
        String sql="""
            SELECT i.*,d.command_type AS delivery_command_type,d.status AS delivery_status,
                d.expires_at AS delivery_expires_at
            FROM agent_platform_skill_installation i
            JOIN agent_command_delivery d ON d.tenant_id=i.tenant_id AND d.client_id=i.client_id
                AND d.owner_jiacn=i.owner_jiacn AND d.command_id=i.command_id
                AND d.task_id=i.installation_id AND d.target_agent_id=i.agent_id
            WHERE i.tenant_id=? AND i.client_id=? AND i.owner_jiacn=? AND i.origin=?
                AND i.agent_id=? AND i.skill_key=? AND i.skill_version=? AND i.package_sha256=?
                AND i.binding_id=? AND i.runtime_instance_id=? AND i.registration_hash=?
            """+suffix;
        var scope=key.scope();
        Object[] arguments=new Object[11+extra.length];
        Object[] fixed={scope.tenant(),scope.client(),scope.owner(),key.origin(),key.agentId(),key.skillKey(),
                key.skillVersion(),key.packageSha(),key.bindingId(),key.runtimeInstanceId(),key.registrationHash()};
        System.arraycopy(fixed,0,arguments,0,fixed.length);
        System.arraycopy(extra,0,arguments,fixed.length,extra.length);
        return new CandidateQuery(sql,arguments);
    }
    @Override public List<Installation> terminalDeliveryCandidates(ScanCursor after,long now,int limit) {
        var query=terminalCandidateQuery(after,now,limit);
        return jdbc.query(query.sql(),ROW,query.arguments());
    }
    @Override public ResolutionCandidate verifiedCandidate(ResolutionKey key) {
        return oneCandidate(verifiedCandidateQuery(key));
    }
    @Override public ResolutionCandidate pendingCandidate(ResolutionKey key,long now) {
        return oneCandidate(pendingCandidateQuery(key,now));
    }
    private ResolutionCandidate oneCandidate(CandidateQuery query) {
        var rows=jdbc.query(query.sql(),(r,n)->new ResolutionCandidate(ROW.mapRow(r,n),r.getString("delivery_command_type"),
                r.getString("delivery_status"),r.getObject("delivery_expires_at",Long.class)),query.arguments());
        if(rows.size()>1) throw new IllegalStateException("Platform installation resolution is not unique");
        return rows.isEmpty()?null:rows.getFirst();
    }
    static CandidateQuery historicalCandidateQuery(ResolutionKey key) {
        if(key==null || key.scope()==null) throw new IllegalArgumentException("Resolution key is required");
        var s=key.scope();
        return new CandidateQuery("""
            SELECT * FROM agent_platform_skill_installation
            WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND origin=? AND agent_id=?
                AND skill_key=? AND skill_version=? AND package_sha256=?
                AND binding_id=? AND runtime_instance_id=? AND registration_hash=?
            ORDER BY created_at DESC,installation_id DESC LIMIT 1
            """,new Object[]{s.tenant(),s.client(),s.owner(),key.origin(),key.agentId(),key.skillKey(),
                key.skillVersion(),key.packageSha(),key.bindingId(),key.runtimeInstanceId(),key.registrationHash()});
    }
    @Override public Installation latestHistorical(ResolutionKey key) {
        var query=historicalCandidateQuery(key);
        return one(jdbc.query(query.sql(),ROW,query.arguments()));
    }
    @Override public void lockScope(Scope s) {
        jdbc.update("INSERT IGNORE INTO agent_platform_skill_scope (tenant_id,client_id,owner_jiacn) VALUES (?,?,?)",s.tenant(),s.client(),s.owner());
        jdbc.queryForObject("SELECT owner_jiacn FROM agent_platform_skill_scope WHERE tenant_id=? AND client_id=? AND owner_jiacn=? FOR UPDATE",
                String.class,s.tenant(),s.client(),s.owner());
    }
    @Override public Installation byKey(Scope s,String actor,String key) {
        return one(jdbc.query("SELECT * FROM agent_platform_skill_installation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND actor_id=? AND request_key=? FOR UPDATE",
                ROW,s.tenant(),s.client(),s.owner(),actor,key));
    }
    @Override public Installation find(Scope s,String id,boolean lock) {
        return one(jdbc.query("SELECT * FROM agent_platform_skill_installation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND installation_id=?"+(lock?" FOR UPDATE":""),
                ROW,s.tenant(),s.client(),s.owner(),id));
    }
    private static Installation one(List<Installation> rows) {
        if(rows.size()>1) throw new IllegalStateException("Platform installation scope is not unique");
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public void insert(Installation i) {
        int n=jdbc.update("""
            INSERT INTO agent_platform_skill_installation
            (installation_id,tenant_id,client_id,owner_jiacn,actor_id,request_key,request_sha256,
             agent_id,binding_id,runtime_instance_id,registration_hash,skill_key,skill_version,package_sha256,
             challenge_id,command_id,origin,state,revision,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PLATFORM_PROVISIONED','REQUESTED',1,?)
            """,i.id(),i.scope().tenant(),i.scope().client(),i.scope().owner(),i.actorId(),i.requestKey(),i.requestSha(),
            i.agentId(),i.bindingId(),i.runtimeInstanceId(),i.registrationHash(),i.skillKey(),i.skillVersion(),i.packageSha(),
            i.challengeId(),i.commandId(),i.createdAt());
        if(n!=1) throw new IllegalStateException("Platform installation insert failed");
    }
    @Override public int finish(Scope s,String id,long revision,String outcome,String sha,String error) {
        return jdbc.update("UPDATE agent_platform_skill_installation SET state=?,result_sha256=?,error_code=?,revision=revision+1 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND installation_id=? AND revision=? AND state='REQUESTED'",
                outcome,sha,error,s.tenant(),s.client(),s.owner(),id,revision);
    }
}
