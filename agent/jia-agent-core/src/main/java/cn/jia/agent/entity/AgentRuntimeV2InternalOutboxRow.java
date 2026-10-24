package cn.jia.agent.entity;

import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;

/** Exact persisted row shape with defensive binary ownership and validated NULL state shapes. */
public final class AgentRuntimeV2InternalOutboxRow {
    public static final List<String> COLUMNS=List.of(("internal_outbox_id,contract_version,runtime_dispatch_id,inbox_id,delivery_id,source_outbox_id,source_event_id,source_message_id,command_id,tenant_id,data_domain_id,execution_pool_id,command_caller_client_id,runtime_credential_client_id,owner_jiacn,agent_id,producer_deployment_id,claimant_deployment_id,claimant_worker_generation,receiver_registration_revision,admission_epoch,module_id,migration_epoch,runtime_binding_id,dispatch_binding_generation,runtime_instance_id,dispatch_generation_fact_id,dispatch_generation_snapshot_sha256,execution_origin_snapshot_sha256,session_fence,socket_owner_instance_id,socket_owner_epoch,prepared_socket_owner_lease_until,command_wire_bytes,command_wire_sha256,dispatch_wire_bytes,dispatch_wire_sha256,prepared_at,expires_at,status,attempt_count,next_attempt_at,claim_id,claim_until,last_attempt_at,sent_at,finished_at,last_error_code,version,update_time").split(","));
    public static final Set<String> MUTABLE=Set.copyOf(COLUMNS.subList(39,50));
    private final Map<String,Object> values;
    public AgentRuntimeV2InternalOutboxRow(Map<String,Object> row){
        if(!row.keySet().equals(new LinkedHashSet<>(COLUMNS)))throw new IllegalArgumentException("internal row columns");
        var copy=new LinkedHashMap<String,Object>();COLUMNS.forEach(k->copy.put(k,cloneValue(row.get(k))));
        this.values=Collections.unmodifiableMap(copy);validate();
    }
    private static Object cloneValue(Object value){return value instanceof byte[] bytes?bytes.clone():value;}
    public Object get(String field){return cloneValue(values.get(field));}
    public String text(String field){Object v=get(field);if(!(v instanceof String s))throw new IllegalArgumentException("missing "+field);return s;}
    public long number(String field){Object v=get(field);if(!(v instanceof Number n)||n.longValue()<0)throw new IllegalArgumentException("invalid "+field);return n.longValue();}
    public byte[] binary(String field){Object v=get(field);if(!(v instanceof byte[] b))throw new IllegalArgumentException("missing "+field);return b;}
    public Map<String,Object> values(){var copy=new LinkedHashMap<String,Object>();values.forEach((k,v)->copy.put(k,cloneValue(v)));return copy;}
    public boolean sameImmutableAs(AgentRuntimeV2InternalOutboxRow other){return other!=null&&COLUMNS.subList(0,39).stream().allMatch(k->Objects.deepEquals(get(k),other.get(k)));}
    public AgentRuntimeV2SocketTuple tuple(){return new AgentRuntimeV2SocketTuple(text("runtime_binding_id"),number("dispatch_binding_generation"),text("runtime_instance_id"),text("session_fence"),text("socket_owner_instance_id"),number("socket_owner_epoch"));}
    public ExecutionOriginSnapshot origin(){return new ExecutionOriginSnapshot(text("runtime_dispatch_id"),number("inbox_id"),number("delivery_id"),text("tenant_id"),text("data_domain_id"),text("execution_pool_id"),text("command_caller_client_id"),text("runtime_credential_client_id"),text("owner_jiacn"),text("agent_id"),text("producer_deployment_id"),text("claimant_deployment_id"),number("claimant_worker_generation"),number("receiver_registration_revision"),number("admission_epoch"),text("module_id"),number("migration_epoch"),text("runtime_binding_id"),number("dispatch_binding_generation"),text("runtime_instance_id"),text("dispatch_generation_fact_id"),binary("dispatch_generation_snapshot_sha256"));}
    private void validate(){
        for(String k:COLUMNS.subList(0,41))if(get(k)==null)throw new IllegalArgumentException("required "+k);
        if(number("contract_version")!=2)throw new IllegalArgumentException("contract version");
        for(String k:List.of("internal_outbox_id","runtime_dispatch_id","runtime_binding_id","runtime_instance_id","dispatch_generation_fact_id","session_fence","socket_owner_instance_id"))uuid(text(k),k);
        for(String k:List.of("inbox_id","delivery_id","source_outbox_id","prepared_at"))positive(number(k),k);
        tenant(text("tenant_id"));
        cn.jia.core.tenant.DataDomainId.of(text("data_domain_id"));
        if(!text("execution_pool_id").matches("[a-z0-9-]{1,100}")||!text("module_id").matches("[a-z][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("pool/module");
        for(String k:List.of("source_event_id","source_message_id","command_id","agent_id","producer_deployment_id","claimant_deployment_id"))exact(text(k),100,k);
        for(String k:List.of("command_caller_client_id","runtime_credential_client_id","owner_jiacn"))exact(text(k),50,k);
        for(String k:List.of("claimant_worker_generation","receiver_registration_revision","admission_epoch","migration_epoch","dispatch_binding_generation","socket_owner_epoch","version","attempt_count"))number(k);
        long prepared=number("prepared_at"),expiry=number("expires_at"),attempt=number("attempt_count");
        if(expiry<=prepared||number("prepared_socket_owner_lease_until")<=prepared||number("update_time")<prepared)throw new IllegalArgumentException("internal time");
        for(String k:List.of("next_attempt_at","claim_until","last_attempt_at","sent_at","finished_at"))if(get(k)!=null&&number(k)<=0)throw new IllegalArgumentException(k);
        if(get("last_attempt_at")!=null&&number("last_attempt_at")<prepared||get("finished_at")!=null&&number("finished_at")<prepared)throw new IllegalArgumentException("internal time order");
        for(String k:List.of("dispatch_generation_snapshot_sha256","execution_origin_snapshot_sha256","command_wire_sha256","dispatch_wire_sha256"))copy32(binary(k),k);
        var delivery=AgentRuntimeV2DispatchCodec.decode(binary("dispatch_wire_bytes"));
        if(!delivery.runtimeDispatchId().equals(text("runtime_dispatch_id"))||!Arrays.equals(delivery.commandBytes(),binary("command_wire_bytes"))||!constantTimeEquals(sha256(binary("command_wire_bytes")),binary("command_wire_sha256"))||!constantTimeEquals(sha256(binary("dispatch_wire_bytes")),binary("dispatch_wire_sha256"))||!constantTimeEquals(origin().sha256(),binary("execution_origin_snapshot_sha256")))throw new IllegalArgumentException("internal immutable hash chain");
        switch(text("status")){
            case "PENDING" -> {require(attempt==0&&number("next_attempt_at")==prepared);nil("claim_id","claim_until","last_attempt_at","sent_at","finished_at","last_error_code");}
            case "CLAIMED" -> {require(attempt>=1);uuid(text("claim_id"),"claim_id");require(number("last_attempt_at")<number("claim_until")&&number("claim_until")<=expiry);nil("next_attempt_at","sent_at","finished_at","last_error_code");}
            case "RETRY" -> {require(attempt>=1&&number("last_attempt_at")<number("next_attempt_at")&&number("next_attempt_at")<=expiry&&Set.of("SEND_FAILED","LOCAL_ROUTE_UNAVAILABLE","CLAIM_EXPIRED").contains(text("last_error_code")));nil("claim_id","claim_until","sent_at","finished_at");}
            case "SENT" -> {require(attempt>=1&&number("last_attempt_at")<=number("sent_at")&&number("sent_at")>=prepared&&number("sent_at")<=number("finished_at"));nil("claim_id","claim_until","next_attempt_at","last_error_code");}
            case "FENCED","EXPIRED" -> {require(get("finished_at")!=null&&((attempt==0)==(get("last_attempt_at")==null)));nil("claim_id","claim_until","next_attempt_at","sent_at");if(text("status").equals("EXPIRED"))require(number("finished_at")>=expiry&&text("last_error_code").equals("MESSAGE_EXPIRED"));else require(Set.of("TARGET_FENCED","AUTHORITY_REVOKED","CONTROL_NOT_ADMITTED").contains(text("last_error_code")));}
            default -> throw new IllegalArgumentException("internal status");
        }
    }
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("internal state shape");}
    private void nil(String... columns){for(String k:columns)require(get(k)==null);}
}
