package cn.jia.agent.mapper;

import cn.jia.agent.entity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;

/** Dedicated four-table JDBC mapper. Dynamic names originate only from fixed column allowlists. */
public final class AgentRuntimeV2InternalOutboxMapper {
    private final JdbcTemplate jdbc;
    public AgentRuntimeV2InternalOutboxMapper(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("current write transaction required");}
    public Map<String,Object> delivery(long id){return one("agent_command_delivery","id",id,true);}
    public Map<String,Object> source(long id){return one("agent_outbox_event","id",id,true);}
    public Map<String,Object> inbox(long id,boolean lock){return one("agent_consumer_inbox","id",id,lock);}
    public List<Map<String,Object>> sources(long id){transaction();return jdbc.queryForList("SELECT * FROM agent_outbox_event WHERE delivery_id=? ORDER BY id FOR UPDATE",id);}
    private Map<String,Object> one(String table,String column,Object id,boolean lock){if(lock)transaction();var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE "+column+"=?"+(lock?" FOR UPDATE":""),id);if(rows.size()>1)throw new SecurityException("ambiguous stable ID");if(rows.isEmpty())return null;var r=rows.getFirst();if(!same(r.get(column),id))throw new SecurityException("nonexact stable ID");return r;}
    public AgentRuntimeV2InternalOutboxRow internal(String column,Object id,boolean lock){if(!Set.of("internal_outbox_id","inbox_id").contains(column))throw new IllegalArgumentException("internal key");var r=one("agent_runtime_v2_internal_outbox",column,id,lock);return r==null?null:new AgentRuntimeV2InternalOutboxRow(r);}
    public List<String> due(String owner,long now,int limit){bounded(limit);return jdbc.queryForList("SELECT internal_outbox_id FROM agent_runtime_v2_internal_outbox WHERE socket_owner_instance_id=? AND expires_at>? AND ((status IN ('PENDING','RETRY') AND next_attempt_at<=?) OR (status='CLAIMED' AND claim_until<=?)) ORDER BY internal_outbox_id LIMIT ?",String.class,owner,now,now,now,limit);}
    public List<String> expired(long now,int limit){bounded(limit);return jdbc.queryForList("SELECT internal_outbox_id FROM agent_runtime_v2_internal_outbox WHERE expires_at<=? AND status IN ('PENDING','RETRY','CLAIMED') ORDER BY internal_outbox_id LIMIT ?",String.class,now,limit);}
    public List<String> stale(long now,int limit){bounded(limit);return jdbc.queryForList("SELECT internal_outbox_id FROM agent_runtime_v2_internal_outbox WHERE prepared_socket_owner_lease_until<=? AND expires_at>? AND status IN ('PENDING','RETRY','CLAIMED') ORDER BY internal_outbox_id LIMIT ?",String.class,now,now,limit);}
    private static void bounded(int limit){if(limit<1||limit>100)throw new IllegalArgumentException("poll bound");}
    public void insert(AgentRuntimeV2InternalOutboxRow r){transaction();var v=r.values();if(jdbc.update("INSERT INTO agent_runtime_v2_internal_outbox ("+String.join(",",v.keySet())+") VALUES ("+String.join(",",Collections.nCopies(v.size(),"?"))+")",v.values().toArray())!=1)throw new IllegalStateException("internal insert");}
    public boolean transition(AgentRuntimeV2InternalOutboxRow r,Map<String,Object> changes){
        transaction();if(!AgentRuntimeV2InternalOutboxRow.MUTABLE.containsAll(changes.keySet()))throw new IllegalArgumentException("immutable internal update");
        var v=r.values();v.putAll(changes);v.put("version",Math.addExact(r.number("version"),1));var next=new AgentRuntimeV2InternalOutboxRow(v);
        var set=new LinkedHashMap<String,Object>();for(String k:AgentRuntimeV2InternalOutboxRow.COLUMNS.subList(39,50))set.put(k,next.get(k));
        var fence=row("internal_outbox_id",r.get("internal_outbox_id"),"version",r.get("version"),"status",r.get("status"),"claim_id",r.get("claim_id"),"claim_until",r.get("claim_until"));
        for(String k:AgentRuntimeV2InternalOutboxRow.COLUMNS.subList(0,39))fence.put(k,r.get(k));
        return update("agent_runtime_v2_internal_outbox",set,fence)==1;
    }
    public void handoff(AgentInboxClaimToken t,AgentRuntimeV2InternalOutboxRow r,long now){
        transaction();Math.addExact(t.inboxVersion(),1);Math.addExact(t.deliveryVersion(),1);
        var inboxFence=row("id",t.inboxId(),"version",t.inboxVersion(),"status","PROCESSING","lease_owner",t.leaseOwner(),"lease_until",t.leaseUntil(),"active_attempt",t.activeAttempt(),"claim_inbox_version",t.inboxVersion(),"claim_delivery_version",t.deliveryVersion(),"claimant_deployment_id",t.claimantDeploymentId(),"claimant_worker_generation",t.claimantWorkerGeneration(),"runtime_dispatch_id",null);
        if(update("agent_consumer_inbox",row("runtime_dispatch_id",r.get("runtime_dispatch_id"),"runtime_binding_id",r.get("runtime_binding_id"),"runtime_binding_generation",r.get("dispatch_binding_generation"),"dispatch_runtime_instance_id",r.get("runtime_instance_id"),"dispatch_runtime_credential_client_id",r.get("runtime_credential_client_id"),"dispatch_receiver_registration_revision",r.get("receiver_registration_revision"),"dispatch_generation_fact_id",r.get("dispatch_generation_fact_id"),"dispatch_generation_snapshot_sha256",r.get("dispatch_generation_snapshot_sha256"),"execution_origin_snapshot_sha256",r.get("execution_origin_snapshot_sha256"),"dispatch_prepared_at",now,"status","PROCESSED","result_status","SENT","lease_owner",null,"lease_until",null,"next_retry_at",null,"last_error",null,"processed_at",now,"version",t.inboxVersion()+1,"update_time",now),inboxFence)!=1)throw new IllegalStateException("inbox handoff CAS");
        if(update("agent_command_delivery",row("status","SENT","lease_owner",null,"lease_until",null,"next_retry_at",null,"last_error",null,"version",t.deliveryVersion()+1,"update_time",now),row("id",t.deliveryId(),"version",t.deliveryVersion(),"status","CONSUMED","active_message_id",t.messageId(),"active_attempt",t.deliveryActiveAttempt(),"claimed_deployment_id",null,"claimed_worker_generation",null))!=1)throw new IllegalStateException("delivery handoff CAS");
    }
    private int update(String table,Map<String,Object> set,Map<String,Object> fence){var args=new ArrayList<Object>();var assignments=new ArrayList<String>();set.forEach((k,v)->{assignments.add(k+"=?");args.add(v);});var predicates=new ArrayList<String>();fence.forEach((k,v)->{predicates.add(k+(v==null?" IS NULL":"=?"));if(v!=null)args.add(v);});return jdbc.update("UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE "+String.join(" AND ",predicates),args.toArray());}
}
