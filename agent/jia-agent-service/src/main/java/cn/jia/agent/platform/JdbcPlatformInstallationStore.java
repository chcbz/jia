package cn.jia.agent.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
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
    static CandidateQuery verifiedCandidateQuery(ResolutionKey key) { return verifiedCandidateQuery(key,false); }
    static CandidateQuery verifiedCandidateQuery(ResolutionKey key,boolean lock) {
        return resolutionQuery(key,"""
                AND i.state='SUCCEEDED' AND i.result_sha256 REGEXP '^[0-9a-f]{64}$'
                AND i.error_code IS NULL AND d.command_type='PLATFORM_SKILL_INSTALL'
            ORDER BY i.created_at DESC,i.installation_id DESC LIMIT 1
            """+(lock?" FOR UPDATE":""),new Object[0]);
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
    @Override public ResolutionCandidate verifiedCandidateForUpdate(ResolutionKey key) {
        return oneCandidate(verifiedCandidateQuery(key,true));
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
    static CandidateQuery currentGrantReferenceQuery(Installation current,String installationId) {
        Scope s=current.scope();
        return new CandidateQuery("""
                SELECT grant_ref FROM archive_execution_grant
                WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?
                  AND installation_ref=? AND state IN ('ACTIVE','READ_ONLY')
                LIMIT 1 FOR SHARE
                """,new Object[]{s.tenant(),s.client(),s.owner(),current.agentId(),installationId});
    }
    record ReclaimBatch(int formatVersion, ScanCursor cursor, List<String> items) { }
    private static final JsonMapper RECLAIM_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    static ReclaimBatch decodeReclaimBatch(String json) {
        try {
            var tree=RECLAIM_JSON.readTree(json);
            if (tree==null || !tree.isObject() || tree.size()!=3 || !tree.has("formatVersion")
                    || !tree.has("cursor") || !tree.has("items")) throw new IllegalStateException("Invalid reclaim journal shape");
            if (!tree.get("formatVersion").isIntegralNumber() || !tree.get("formatVersion").canConvertToInt()
                    || !tree.get("items").isArray()) throw new IllegalStateException("Invalid reclaim journal types");
            for(var item:tree.get("items")) if(!item.isTextual()) throw new IllegalStateException("Invalid reclaim journal item type");
            var cursor=tree.get("cursor");
            if (!cursor.isNull() && (!cursor.isObject() || cursor.size()!=2
                    || !cursor.has("createdAt") || !cursor.has("installationId")
                    || !cursor.get("createdAt").isIntegralNumber() || !cursor.get("createdAt").canConvertToLong()
                    || !cursor.get("installationId").isTextual()))
                throw new IllegalStateException("Invalid reclaim journal cursor shape");
            var batch=RECLAIM_JSON.treeToValue(tree,ReclaimBatch.class);
            if(batch.formatVersion()!=1 || batch.items()==null || batch.items().size()>32
                    || new java.util.HashSet<>(batch.items()).size()!=batch.items().size())
                throw new IllegalStateException("Invalid reclaim journal batch");
            for(String id:batch.items()) if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))
                throw new IllegalStateException("Invalid reclaim journal identity");
            if(batch.cursor()!=null && (batch.cursor().createdAt()<0 || batch.cursor().installationId()==null
                    || !batch.cursor().installationId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")))
                throw new IllegalStateException("Invalid reclaim journal cursor");
            return new ReclaimBatch(1,batch.cursor(),List.copyOf(batch.items()));
        } catch(Exception failure) { throw new IllegalStateException("Invalid durable reclaim journal",failure); }
    }
    static String encodeReclaimBatch(ReclaimBatch batch) {
        try { return RECLAIM_JSON.writeValueAsString(batch); }
        catch(Exception failure) { throw new IllegalStateException("Cannot persist reclaim journal",failure); }
    }
    static CandidateQuery reclaimCandidatesQuery(Installation current,String state,ScanCursor after,int limit) {
        if(current==null || limit<1 || limit>32 || !("SUCCEEDED".equals(state)||"RECLAIMABLE".equals(state)))
            throw new IllegalArgumentException("Platform reclaim batch is invalid");
        Scope s=current.scope();
        var args=new java.util.ArrayList<Object>(java.util.Arrays.asList(s.tenant(),s.client(),s.owner(),
                current.agentId(),current.bindingId(),current.runtimeInstanceId(),current.registrationHash(),
                current.skillKey(),current.skillVersion(),current.packageSha(),state,current.id()));
        String keyset="";
        if(after!=null) {
            keyset=" AND (i.created_at>? OR (i.created_at=? AND i.installation_id>?))";
            args.add(after.createdAt());args.add(after.createdAt());args.add(after.installationId());
        }
        args.add(limit);
        return new CandidateQuery("""
                SELECT i.* FROM agent_platform_skill_installation i
                LEFT JOIN archive_execution_grant g ON g.tenant_id=i.tenant_id
                  AND g.client_id=i.client_id AND g.owner_jiacn=i.owner_jiacn AND g.agent_id=i.agent_id
                  AND g.installation_ref=i.installation_id AND g.state IN ('ACTIVE','READ_ONLY')
                WHERE i.tenant_id=? AND i.client_id=? AND i.owner_jiacn=?
                  AND i.origin='PLATFORM_PROVISIONED' AND i.agent_id=?
                  AND i.binding_id=? AND i.runtime_instance_id=? AND i.registration_hash=?
                  AND i.skill_key=? AND i.skill_version=? AND i.package_sha256=?
                  AND i.state=? AND i.result_sha256 REGEXP '^[0-9a-f]{64}$'
                  AND i.error_code IS NULL AND i.installation_id<>?
                  AND g.grant_ref IS NULL
                """+keyset+" ORDER BY i.created_at,i.installation_id LIMIT ? FOR UPDATE",args.toArray());
    }
    private List<Installation> reclaimCandidates(Installation current,String state,ScanCursor after,int limit) {
        var query=reclaimCandidatesQuery(current,state,after,limit);
        return jdbc.query(query.sql(),ROW,query.arguments());
    }
    @Override public List<String> reclaimableInstallationIds(Installation current,int limit) {
        if(current==null || limit<1 || limit>32) throw new IllegalArgumentException("Platform reclaim batch is invalid");
        Scope s=current.scope();
        // The result transaction owns scope/current installation locks. Persist one exact
        // bounded receipt batch so response loss or replay cannot skip its authorization.
        String saved=jdbc.queryForObject("SELECT reclaim_batch_json FROM agent_platform_skill_installation "
                + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND installation_id=? FOR UPDATE",
                String.class,s.tenant(),s.client(),s.owner(),current.id());
        if(saved!=null) {
            var batch=decodeReclaimBatch(saved);
            if(batch.items().size()>limit) throw new IllegalStateException("Reclaim replay batch limit changed");
            return batch.items();
        }
        Integer grants=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='archive_execution_grant'",Integer.class);
        if(grants==null || grants!=1) return List.of();
        // Previous receipt progress is scoped to this exact stable runtime/package proof.
        List<String> previous=jdbc.queryForList("""
                SELECT reclaim_batch_json FROM agent_platform_skill_installation
                WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND origin='PLATFORM_PROVISIONED'
                  AND agent_id=? AND binding_id=? AND runtime_instance_id=? AND registration_hash=?
                  AND skill_key=? AND skill_version=? AND package_sha256=? AND installation_id<>?
                  AND reclaim_batch_json IS NOT NULL
                ORDER BY created_at DESC,installation_id DESC LIMIT 1 FOR SHARE
                """,String.class,s.tenant(),s.client(),s.owner(),current.agentId(),current.bindingId(),
                current.runtimeInstanceId(),current.registrationHash(),current.skillKey(),current.skillVersion(),
                current.packageSha(),current.id());
        ScanCursor cursor=previous.isEmpty()?null:decodeReclaimBatch(previous.getFirst()).cursor();
        // Never-offered successful objects have priority. Old permanently fenced tombstones
        // cannot consume all 32 slots and strand a live unreferenced copy before the next install.
        var candidates=new java.util.ArrayList<>(reclaimCandidates(current,"SUCCEEDED",null,limit));
        if(candidates.size()<limit) {
            var older=reclaimCandidates(current,"RECLAIMABLE",cursor,limit-candidates.size());
            if(older.isEmpty() && cursor!=null) older=reclaimCandidates(current,"RECLAIMABLE",null,limit-candidates.size());
            if(!older.isEmpty()) { var last=older.getLast(); cursor=new ScanCursor(last.createdAt(),last.id()); }
            candidates.addAll(older);
        }
        java.util.ArrayList<String> fencedCandidates=new java.util.ArrayList<>(candidates.size());
        for(Installation candidate:candidates) {
            String id=candidate.id();
            // Only the current locking grant read proves no protected reference after an
            // admission committed while this RR transaction waited for the installation lock.
            CandidateQuery references=currentGrantReferenceQuery(current,id);
            if(!jdbc.queryForList(references.sql(),String.class,references.arguments()).isEmpty()) continue;
            int fenced=jdbc.update("UPDATE agent_platform_skill_installation SET state='RECLAIMABLE',revision=revision+1 WHERE installation_id=? AND state='SUCCEEDED'",id);
            if(fenced==0) {
                String state=jdbc.queryForObject("SELECT state FROM agent_platform_skill_installation WHERE installation_id=? FOR UPDATE",String.class,id);
                if(!"RECLAIMABLE".equals(state)) throw new IllegalStateException("Platform installation reclaim fence raced");
            }
            fencedCandidates.add(id);
        }
        var batch=new ReclaimBatch(1,cursor,List.copyOf(fencedCandidates));
        if(jdbc.update("UPDATE agent_platform_skill_installation SET reclaim_batch_json=? WHERE tenant_id=? "
                + "AND client_id=? AND owner_jiacn=? AND installation_id=? AND state='SUCCEEDED' AND reclaim_batch_json IS NULL",
                encodeReclaimBatch(batch),s.tenant(),s.client(),s.owner(),current.id())!=1)
            throw new IllegalStateException("Platform installation reclaim journal raced");
        return batch.items();
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
