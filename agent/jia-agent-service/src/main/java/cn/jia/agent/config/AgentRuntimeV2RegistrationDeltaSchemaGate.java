package cn.jia.agent.config;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.Catalog;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.CheckDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.IndexDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.check;

/** Dormant R2b2 exact catalog and reachable evidence gate. Read-only: never creates or repairs evidence. */
public final class AgentRuntimeV2RegistrationDeltaSchemaGate implements InitializingBean {
    static final Set<String> TABLES=Set.of(OPERATION,COMPLETION,DECISION);
    private static final String BIN="utf8mb4_0900_bin";
    private final JdbcTemplate jdbc;
    private final Catalog catalog;
    private final Runnable baseline;
    private volatile boolean verified;
    public AgentRuntimeV2RegistrationDeltaSchemaGate(JdbcTemplate jdbc,AgentRuntimeV2RegistrationSchemaGate baseline) {
        this(jdbc,new JdbcCatalog(jdbc),baseline::afterPropertiesSet);
    }
    AgentRuntimeV2RegistrationDeltaSchemaGate(JdbcTemplate jdbc,Catalog catalog,Runnable baseline) {this.jdbc=jdbc;this.catalog=catalog;this.baseline=baseline;}
    public boolean verified(){return verified;}
    @Override public void afterPropertiesSet() {
        verified=false;baseline.run();
        check(catalog.databaseProduct().toLowerCase(Locale.ROOT).contains("mysql"));
        for(String table:TABLES) {
            var definition=catalog.table(table);check(definition!=null&&"InnoDB".equalsIgnoreCase(definition.engine())&&BIN.equals(definition.collation()));
            check(catalog.participatingForeignKeyCount(table)==0);
            List<ColumnDefinition> columns=catalog.columns(table),expected=expectedColumns().get(table);check(columns.size()==expected.size());
            for(int i=0;i<expected.size();i++) {var actual=columns.get(i);var want=expected.get(i);check(actual.name().equals(want.name())&&actual.dataType().equals(want.dataType())&&actual.columnType().equals(want.columnType())&&actual.nullable()==want.nullable()&&Objects.equals(actual.defaultValue(),want.defaultValue())&&Objects.equals(actual.collation(),want.collation())&&actual.extra().equals(want.extra())&&canonical(actual.generationExpression()).equals(canonical(want.generationExpression())));}
            check(expectedIndexes().get(table).equals(catalog.indexes(table)));
            Map<String,CheckDefinition> checks=catalog.checks(table);Map<String,String> clauses=expectedChecks().get(table);check(checks.keySet().equals(clauses.keySet()));
            clauses.forEach((name,clause)->check(checks.get(name).enforced()&&canonical(clause).equals(canonical(checks.get(name).clause()))));
        }
        var reason=catalog.checks(FACT).get("chk_binding_generation_fact_canonical");check(reason!=null&&reason.enforced()&&canonical(DISCONNECT_CHECK).equals(canonical(reason.clause())));
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));tx.setReadOnly(true);
        tx.executeWithoutResult(status->validateGraph(jdbc));verified=true;
    }
    private static String canonical(String value){return AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(value);}
    static Map<String,List<ColumnDefinition>> expectedColumns() {
        return Map.of(OPERATION,columns("""
            drain_operation_id varchar(36)
            operation_scope varchar(20)
            tenant_id varchar(50)
            module_id varchar(64)
            operator_id varchar(128)
            audit_reason varchar(500)
            source_tenant_operator_id varchar(128) ?
            source_tenant_idempotency_key varchar(128) ?
            tenant_state varchar(32)
            tenant_control_revision bigint
            tenant_session_epoch bigint
            tenant_admission_epoch bigint
            tenant_drain_epoch bigint
            module_mode_state varchar(16)
            module_mode_revision bigint
            module_migration_epoch bigint
            module_schema_version bigint
            module_mapping_digest char(64) ?
            tenant_cutoff_at bigint ?
            tenant_deadline_at bigint ?
            module_cutoff_at bigint ?
            module_deadline_at bigint ?
            cutoff_at bigint
            deadline_at bigint
            issued_at bigint
            operation_snapshot_sha256 binary(32)
            status varchar(20)
            sealed_at bigint ?
            revoked_at bigint ?
            revoke_reason varchar(500) ?
            version bigint
            update_time bigint
            live_module_key varchar(120) generated
            """),COMPLETION,columns("""
            drain_operation_id varchar(36)
            runtime_binding_id varchar(36)
            tenant_id varchar(50)
            module_id varchar(64)
            registration_subject_sha256 binary(32)
            registration_authority_id varchar(36)
            runtime_instance_id varchar(36)
            process_public_key_sha256 binary(32)
            claimant_deployment_id varchar(100)
            claimant_worker_generation bigint
            frozen_connected_fact_id varchar(36)
            frozen_connected_fact_sha256 binary(32)
            frozen_registration_receipt_id varchar(36)
            frozen_registration_receipt_sha256 binary(32)
            operation_snapshot_sha256 binary(32)
            completion_snapshot_sha256 binary(32)
            issued_at bigint
            status varchar(20)
            revoked_at bigint ?
            revoke_reason varchar(500) ?
            version bigint
            update_time bigint
            """),DECISION,columns("""
            challenge_request_id varchar(36)
            contract_version smallint
            credential_reference varchar(200)
            tenant_id varchar(50)
            runtime_credential_client_id varchar(50)
            owner_jiacn varchar(100)
            agent_id varchar(100)
            registration_subject_sha256 binary(32)
            registration_authority_id varchar(36)
            registration_authority_revision bigint
            registration_authority_sha256 binary(32) ?
            request_wire_bytes varbinary(2048)
            request_wire_sha256 binary(32)
            request_context_sha256 binary(32)
            runtime_binding_id varchar(36)
            prior_registration_receipt_id varchar(36)
            prior_registration_receipt_sha256 binary(32)
            runtime_instance_id varchar(36)
            provisional_session_fence varchar(36)
            issuer_socket_owner_instance_id varchar(36)
            issuer_socket_owner_epoch bigint
            decision varchar(20)
            challenge_id varchar(36) ?
            response_wire_bytes varbinary(2048)
            response_wire_sha256 binary(32)
            denial_code varchar(40) ?
            decided_at bigint
            """));
    }
    private static List<ColumnDefinition> columns(String specs) {return specs.lines().filter(s->!s.isBlank()).map(s->{String[] f=s.trim().split(" +");boolean generated=f.length==3&&"generated".equals(f[2]);String type=f[1].replaceAll("\\(.*","");return new ColumnDefinition(f[0],type,f[1],f.length==3,null,Set.of("varchar","char").contains(type)?BIN:null,generated?"STORED GENERATED":"",generated?"case when (status = 'OPEN') then concat(tenant_id,':',module_id) else NULL end":"");}).toList();}
    static Map<String,Map<String,IndexDefinition>> expectedIndexes() {return Map.of(
        OPERATION,Map.of("PRIMARY",index(true,"drain_operation_id"),"uk_runtime_drain_live_module",index(true,"live_module_key"),"idx_runtime_drain_scope",index(false,"tenant_id","module_id","status","drain_operation_id")),
        COMPLETION,Map.of("PRIMARY",index(true,"drain_operation_id","runtime_binding_id"),"idx_runtime_drain_completion_binding",index(false,"runtime_binding_id","drain_operation_id")),
        DECISION,Map.of("PRIMARY",index(true,"challenge_request_id"),"uk_challenge_decision_challenge",index(true,"challenge_id"),"idx_challenge_decision_subject",index(false,"registration_subject_sha256","decided_at")));}
    private static IndexDefinition index(boolean unique,String... fields){return new IndexDefinition(unique,List.of(fields));}
    static Map<String,Map<String,String>> expectedChecks(){return Map.of(
        OPERATION,Map.of("chk_runtime_drain_identity",OPERATION_IDENTITY,"chk_runtime_drain_source",OPERATION_SOURCE,"chk_runtime_drain_lifecycle",OPERATION_LIFECYCLE),
        COMPLETION,Map.of("chk_runtime_drain_completion_identity",COMPLETION_IDENTITY,"chk_runtime_drain_completion_lifecycle",COMPLETION_LIFECYCLE),
        DECISION,Map.of("chk_challenge_decision_identity",DECISION_IDENTITY,"chk_challenge_decision_bytes",DECISION_BYTES,"chk_challenge_decision_result",DECISION_RESULT));}
    static void validateGraph(JdbcTemplate jdbc) {
        Map<String,Map<String,Object>> challenges=by(jdbc.queryForList("SELECT * FROM "+CHALLENGE),"challenge_request_id");
        Map<String,Map<String,Object>> decisions=by(jdbc.queryForList("SELECT * FROM "+DECISION),"challenge_request_id");
        for(var d:decisions.values()) {
            var subject=new AgentRuntimeV2RegistrationFingerprints.Subject("API_KEY",text(d,"credential_reference"),text(d,"tenant_id"),text(d,"runtime_credential_client_id"),text(d,"owner_jiacn"),text(d,"agent_id"));check(same(AgentRuntimeV2RegistrationFingerprints.subject(subject),d.get("registration_subject_sha256")));
            var c=challenges.get(d.get("challenge_request_id"));AgentRuntimeV2RegistrationServiceImpl.verifyDecision(d,c);
        }
        for(var c:challenges.values())if(reachable(jdbc,text(c,"runtime_binding_id")))check(decisions.containsKey(c.get("challenge_request_id")));
        Map<String,Map<String,Object>> operations=by(jdbc.queryForList("SELECT * FROM "+OPERATION),"drain_operation_id");
        for(var op:operations.values())if("OPEN".equals(op.get("status")))validateOperation(jdbc,op);
        for(var grant:jdbc.queryForList("SELECT * FROM "+COMPLETION))if("ACTIVE".equals(grant.get("status"))&&reachable(jdbc,text(grant,"runtime_binding_id"))) {
            var op=required(operations.get(grant.get("drain_operation_id")));check(same(AgentRuntimeV2RegistrationDeltaHashes.operation(op),op.get("operation_snapshot_sha256"))&&same(AgentRuntimeV2RegistrationDeltaHashes.completion(grant),grant.get("completion_snapshot_sha256"))&&same(op.get("operation_snapshot_sha256"),grant.get("operation_snapshot_sha256")));
            check(number(grant,"issued_at")>=number(op,"issued_at")&&number(grant,"issued_at")<number(op,"deadline_at"));
            var fact=one(jdbc,FACT,"binding_generation_fact_id",grant.get("frozen_connected_fact_id"));var receipt=one(jdbc,RECEIPT,"registration_receipt_id",grant.get("frozen_registration_receipt_id"));
            AgentRuntimeV2RegistrationServiceImpl.validateReceipt(receipt);AgentRuntimeV2RegistrationServiceImpl.validateHolder(fact,receipt);
            check(same(fact.get("generation_snapshot_sha256"),grant.get("frozen_connected_fact_sha256"))&&same(receipt.get("receipt_wire_sha256"),grant.get("frozen_registration_receipt_sha256"))&&number(fact,"effective_at")<number(op,"cutoff_at"));
            for(String name:List.of("runtime_binding_id","tenant_id","module_id","registration_subject_sha256","registration_authority_id","runtime_instance_id","process_public_key_sha256","claimant_deployment_id","claimant_worker_generation"))check(same(fact.get(name),grant.get(name)));
            validateChain(jdbc,text(grant,"runtime_binding_id"),op,fact);
        }
        for(var fact:jdbc.queryForList("SELECT * FROM "+FACT+" WHERE evidence_provenance='R2B1_ENFORCED' AND connection_admission_kind='FROZEN_DRAIN'"))if(reachable(jdbc,text(fact,"runtime_binding_id"))) {
            var grants=jdbc.queryForList("SELECT * FROM "+COMPLETION+" WHERE drain_operation_id=? AND runtime_binding_id=?",fact.get("drain_control_operation_id"),fact.get("runtime_binding_id"));check(grants.size()==1);var op=required(operations.get(fact.get("drain_control_operation_id")));check(number(fact,"effective_at")>=number(op,"issued_at")&&number(fact,"effective_at")<number(op,"deadline_at"));
        }
    }
    private static void validateOperation(JdbcTemplate jdbc,Map<String,Object> op) {
        check(same(AgentRuntimeV2RegistrationDeltaHashes.operation(op),op.get("operation_snapshot_sha256")));
        var tenant=one(jdbc,"core_tenant_control","tenant_id",op.get("tenant_id"));var module=jdbc.queryForList("SELECT * FROM core_tenant_module_control WHERE tenant_id=? AND module_id=?",op.get("tenant_id"),op.get("module_id"));check(module.size()==1);var m=module.getFirst();
        for(String[] pair:new String[][]{{"tenant_state","state"},{"tenant_control_revision","control_revision"},{"tenant_session_epoch","tenant_session_epoch"},{"tenant_admission_epoch","admission_epoch"},{"tenant_drain_epoch","drain_epoch"}})check(same(op.get(pair[0]),tenant.get(pair[1])));
        for(String[] pair:new String[][]{{"module_mode_state","mode_state"},{"module_mode_revision","mode_revision"},{"module_migration_epoch","migration_epoch"},{"module_schema_version","schema_version"},{"module_mapping_digest","mapping_digest"}})check(same(op.get(pair[0]),m.get(pair[1])));
        String scope=text(op,"operation_scope");check(List.of("TENANT_DRAIN","MODULE_QUIESCE","COMBINED").contains(scope));
        if("TENANT_DRAIN".equals(scope))check("DOMAIN".equals(m.get("mode_state"))&&cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.flag(m,"drain_sealed")&&number(m,"schema_version")>0&&m.get("mapping_digest") instanceof String digest&&digest.matches("[0-9a-f]{64}"));
        else check("QUIESCING".equals(m.get("mode_state"))&&!cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.flag(m,"drain_sealed"));
        if("TENANT_DRAIN".equals(scope))check(op.get("module_cutoff_at")==null&&op.get("module_deadline_at")==null);
        if("MODULE_QUIESCE".equals(scope))check(op.get("tenant_cutoff_at")==null&&op.get("tenant_deadline_at")==null&&op.get("source_tenant_operator_id")==null&&op.get("source_tenant_idempotency_key")==null);
        long cutoff=Long.MAX_VALUE,deadline=Long.MAX_VALUE;
        for(String lane:List.of("tenant","module"))if(op.get(lane+"_cutoff_at")!=null){long start=number(op,lane+"_cutoff_at"),end=number(op,lane+"_deadline_at");check(start>0&&end>start);cutoff=Math.min(cutoff,start);deadline=Math.min(deadline,end);}
        check(cutoff==number(op,"cutoff_at")&&deadline==number(op,"deadline_at")&&number(op,"issued_at")>=cutoff&&number(op,"issued_at")<deadline);
        if(!"MODULE_QUIESCE".equals(scope)) {
            check("DRAINING".equals(tenant.get("state")));var sources=jdbc.queryForList("SELECT * FROM core_tenant_operation WHERE tenant_id=? AND operator_id=? AND idempotency_key=?",op.get("tenant_id"),op.get("source_tenant_operator_id"),op.get("source_tenant_idempotency_key"));check(sources.size()==1);var s=sources.getFirst();check("DRAINING".equals(s.get("to_state"))&&same(s.get("result_revision"),op.get("tenant_control_revision"))&&same(s.get("result_snapshot"),"DRAINING|"+op.get("tenant_control_revision")+"|"+op.get("tenant_session_epoch")+"|"+op.get("tenant_admission_epoch")));
            check(same(op.get("tenant_cutoff_at"),cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.millis(tenant.get("drain_started_at")))&&same(op.get("tenant_deadline_at"),cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.millis(tenant.get("drain_deadline"))));
        } else check(List.of("ENABLED","AUTH_CLOSED").contains(tenant.get("state")));
        if(!"TENANT_DRAIN".equals(scope))check(same(op.get("module_cutoff_at"),cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.millis(m.get("drain_cutoff")))&&same(op.get("module_deadline_at"),cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.millis(m.get("drain_deadline"))));
    }
    private static void validateChain(JdbcTemplate jdbc,String binding,Map<String,Object> op,Map<String,Object> frozen) {
        String previous=null;long sequence=0,generation=-1;boolean found=false;Map<String,Object> priorReceipt=null;
        for(var f:jdbc.queryForList("SELECT * FROM "+FACT+" WHERE runtime_binding_id=? ORDER BY fact_sequence",binding)) {
            check(number(f,"fact_sequence")==sequence++&&same(f.get("previous_fact_id"),previous));previous=text(f,"binding_generation_fact_id");if(same(previous,frozen.get("binding_generation_fact_id")))found=true;
            if("CONNECTED".equals(f.get("event_type"))) {
                var r=one(jdbc,RECEIPT,"registration_receipt_id",f.get("registration_receipt_id"));AgentRuntimeV2RegistrationServiceImpl.validateReceipt(r);AgentRuntimeV2RegistrationServiceImpl.validateHolder(f,r);check(number(f,"binding_generation")==++generation);
                check(priorReceipt==null?r.get("previous_registration_receipt_id")==null&&r.get("previous_registration_receipt_sha256")==null:same(r.get("previous_registration_receipt_id"),priorReceipt.get("registration_receipt_id"))&&same(r.get("previous_registration_receipt_sha256"),priorReceipt.get("receipt_wire_sha256")));priorReceipt=r;
                if(found) {for(String name:List.of("tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","runtime_instance_id","process_public_key_sha256","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","registration_subject_sha256","registration_authority_id"))check(same(f.get(name),frozen.get(name)));if(!same(previous,frozen.get("binding_generation_fact_id")))check("FROZEN_DRAIN".equals(f.get("connection_admission_kind"))&&same(f.get("drain_control_operation_id"),op.get("drain_operation_id")));}
            } else check(number(f,"binding_generation")==generation);
        }
        check(found);
    }
    private static boolean reachable(JdbcTemplate jdbc,String binding) {
        // Immutable registration history alone is not a root. Enforced V2 execution evidence is,
        // even after routing revocation; holder transfer can make its binding differ from origin.
        Long count=jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM agent_runtime_v2_binding WHERE runtime_binding_id=? AND status<>'REVOKED')
              + (SELECT COUNT(*) FROM agent_consumer_inbox WHERE evidence_provenance='R2B1_ENFORCED'
                  AND protocol_version=2 AND runtime_dispatch_id IS NOT NULL AND (runtime_binding_id=? OR execution_holder_binding_id=?))
              + (SELECT COUNT(*) FROM agent_command_v2_ack_receipt WHERE runtime_binding_id=? AND contract_version=2 AND evidence_provenance='R2B1_ENFORCED')
              + (SELECT COUNT(*) FROM agent_command_v2_execution_lease_fact WHERE runtime_binding_id=? AND evidence_provenance='R2B1_ENFORCED')
              + (SELECT COUNT(*) FROM agent_command_v2_execution_grant WHERE runtime_binding_id=? AND evidence_provenance='R2B1_ENFORCED')
              + (SELECT COUNT(*) FROM agent_command_v2_execution_request WHERE runtime_binding_id=? AND evidence_provenance='R2B1_ENFORCED')
            """,Long.class,binding,binding,binding,binding,binding,binding,binding);return count!=null&&count>0;
    }
    private static Map<String,Map<String,Object>> by(List<Map<String,Object>> rows,String key) {Map<String,Map<String,Object>> map=new HashMap<>();for(var r:rows)check(map.put(text(r,key),r)==null);return map;}
    private static Map<String,Object> one(JdbcTemplate jdbc,String table,String key,Object value){var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE "+key+"=?",value);check(rows.size()==1);return rows.getFirst();}
    private static Map<String,Object> required(Map<String,Object> row){check(row!=null);return row;}

    static final String DISCONNECT_CHECK="""
        binding_generation_fact_id IS NOT NULL AND binding_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND event_type IS NOT NULL AND event_type IN ('CONNECTED','DISCONNECTED','REVOKED')
        AND fact_sequence IS NOT NULL AND fact_sequence>=0 AND binding_generation IS NOT NULL AND binding_generation>=0
        AND ((fact_sequence=0 AND previous_fact_id IS NULL AND event_type='CONNECTED' AND binding_generation=0)
          OR (fact_sequence>0 AND previous_fact_id IS NOT NULL AND previous_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
        AND claimant_worker_generation IS NOT NULL AND claimant_worker_generation>=0
        AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision>=0
        AND admission_epoch IS NOT NULL AND admission_epoch>=0 AND migration_epoch IS NOT NULL AND migration_epoch>=0
        AND socket_owner_epoch IS NOT NULL AND socket_owner_epoch>=0 AND socket_owner_lease_until IS NOT NULL AND socket_owner_lease_until>0
        AND effective_at IS NOT NULL AND effective_at>0
        AND ((event_type='REVOKED' AND reason IS NOT NULL AND OCTET_LENGTH(reason) BETWEEN 1 AND 500)
          OR (event_type='CONNECTED' AND reason IS NULL)
          OR (event_type='DISCONNECTED' AND (reason IS NULL OR reason IN ('SOCKET_CLOSED','PROTOCOL_CLOSED','ROUTING_LEASE_EXPIRED','REPLACED_BY_AUTHENTICATED_RECONNECT'))))
        """;
    private static final String OPERATION_IDENTITY="""
        drain_operation_id IS NOT NULL AND drain_operation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
        AND module_id IS NOT NULL AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
        AND operator_id IS NOT NULL AND OCTET_LENGTH(operator_id) BETWEEN 1 AND 128
        AND audit_reason IS NOT NULL AND OCTET_LENGTH(audit_reason) BETWEEN 1 AND 500
        AND (source_tenant_operator_id IS NULL OR OCTET_LENGTH(source_tenant_operator_id) BETWEEN 1 AND 128)
        AND (source_tenant_idempotency_key IS NULL OR OCTET_LENGTH(source_tenant_idempotency_key) BETWEEN 1 AND 128)
        AND operation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(operation_snapshot_sha256)=32
        AND (module_mapping_digest IS NULL OR module_mapping_digest REGEXP '^[0-9a-f]{64}$')
        AND tenant_control_revision IS NOT NULL AND tenant_control_revision>=0
        AND tenant_session_epoch IS NOT NULL AND tenant_session_epoch>=0
        AND tenant_admission_epoch IS NOT NULL AND tenant_admission_epoch>=0
        AND tenant_drain_epoch IS NOT NULL AND tenant_drain_epoch>=0
        AND module_mode_revision IS NOT NULL AND module_mode_revision>=0
        AND module_migration_epoch IS NOT NULL AND module_migration_epoch>=0
        AND module_schema_version IS NOT NULL AND module_schema_version>=0
        AND version IS NOT NULL AND version>=0
        """;
    private static final String OPERATION_SOURCE="""
        operation_scope IS NOT NULL AND tenant_state IS NOT NULL AND module_mode_state IS NOT NULL
        AND ((operation_scope='TENANT_DRAIN' AND tenant_state='DRAINING' AND module_mode_state='DOMAIN'
             AND source_tenant_operator_id IS NOT NULL AND source_tenant_idempotency_key IS NOT NULL
             AND tenant_cutoff_at IS NOT NULL AND tenant_deadline_at IS NOT NULL AND tenant_cutoff_at>0 AND tenant_deadline_at>tenant_cutoff_at
             AND module_cutoff_at IS NULL AND module_deadline_at IS NULL AND cutoff_at=tenant_cutoff_at AND deadline_at=tenant_deadline_at)
         OR (operation_scope='MODULE_QUIESCE' AND tenant_state IN ('ENABLED','AUTH_CLOSED') AND module_mode_state='QUIESCING'
             AND source_tenant_operator_id IS NULL AND source_tenant_idempotency_key IS NULL
             AND tenant_cutoff_at IS NULL AND tenant_deadline_at IS NULL
             AND module_cutoff_at IS NOT NULL AND module_deadline_at IS NOT NULL AND module_cutoff_at>0 AND module_deadline_at>module_cutoff_at
             AND cutoff_at=module_cutoff_at AND deadline_at=module_deadline_at)
         OR (operation_scope='COMBINED' AND tenant_state='DRAINING' AND module_mode_state='QUIESCING'
             AND source_tenant_operator_id IS NOT NULL AND source_tenant_idempotency_key IS NOT NULL
             AND tenant_cutoff_at IS NOT NULL AND tenant_deadline_at IS NOT NULL AND module_cutoff_at IS NOT NULL AND module_deadline_at IS NOT NULL
             AND tenant_cutoff_at>0 AND tenant_deadline_at>tenant_cutoff_at AND module_cutoff_at>0 AND module_deadline_at>module_cutoff_at
             AND cutoff_at=LEAST(tenant_cutoff_at,module_cutoff_at) AND deadline_at=LEAST(tenant_deadline_at,module_deadline_at)))
        AND cutoff_at IS NOT NULL AND deadline_at IS NOT NULL AND issued_at IS NOT NULL
        AND cutoff_at>0 AND deadline_at>cutoff_at AND issued_at>=cutoff_at AND issued_at<deadline_at
        """;
    private static final String OPERATION_LIFECYCLE="""
        status IS NOT NULL AND update_time IS NOT NULL AND update_time>=issued_at
        AND ((status='OPEN' AND sealed_at IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
         OR (status='SEALED' AND sealed_at IS NOT NULL AND sealed_at>=issued_at AND sealed_at<=update_time AND revoked_at IS NULL AND revoke_reason IS NULL)
         OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at>=issued_at AND revoked_at<=update_time
             AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500
             AND (sealed_at IS NULL OR (sealed_at>=issued_at AND sealed_at<=revoked_at))))
        """;
    private static final String COMPLETION_IDENTITY="""
        drain_operation_id IS NOT NULL AND drain_operation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND runtime_instance_id IS NOT NULL AND runtime_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND frozen_connected_fact_id IS NOT NULL AND frozen_connected_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND frozen_registration_receipt_id IS NOT NULL AND frozen_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
        AND module_id IS NOT NULL AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
        AND claimant_deployment_id IS NOT NULL AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
        AND claimant_worker_generation IS NOT NULL AND claimant_worker_generation>=0
        AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
        AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
        AND frozen_connected_fact_sha256 IS NOT NULL AND OCTET_LENGTH(frozen_connected_fact_sha256)=32
        AND frozen_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(frozen_registration_receipt_sha256)=32
        AND operation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(operation_snapshot_sha256)=32
        AND completion_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(completion_snapshot_sha256)=32
        AND issued_at IS NOT NULL AND issued_at>0 AND version IS NOT NULL AND version>=0
        """;
    private static final String COMPLETION_LIFECYCLE="""
        status IS NOT NULL AND update_time IS NOT NULL AND update_time>=issued_at
        AND ((status='ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
         OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at>=issued_at AND revoked_at<=update_time
             AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500))
        """;
    private static final String DECISION_IDENTITY="""
        challenge_request_id IS NOT NULL AND challenge_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND prior_registration_receipt_id IS NOT NULL AND prior_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND runtime_instance_id IS NOT NULL AND runtime_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND provisional_session_fence IS NOT NULL AND provisional_session_fence REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND issuer_socket_owner_instance_id IS NOT NULL AND issuer_socket_owner_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
        AND credential_reference IS NOT NULL AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200
        AND runtime_credential_client_id IS NOT NULL AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
        AND owner_jiacn IS NOT NULL AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
        AND agent_id IS NOT NULL AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
        AND registration_authority_revision IS NOT NULL AND registration_authority_revision>=0
        AND issuer_socket_owner_epoch IS NOT NULL AND issuer_socket_owner_epoch>=0
        AND contract_version IS NOT NULL AND contract_version=2 AND decided_at IS NOT NULL AND decided_at>0
        """;
    private static final String DECISION_BYTES="""
        registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
        AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32
        AND request_context_sha256 IS NOT NULL AND OCTET_LENGTH(request_context_sha256)=32
        AND prior_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(prior_registration_receipt_sha256)=32
        AND response_wire_sha256 IS NOT NULL AND OCTET_LENGTH(response_wire_sha256)=32
        AND request_wire_bytes IS NOT NULL AND OCTET_LENGTH(request_wire_bytes) BETWEEN 1 AND 2048
        AND response_wire_bytes IS NOT NULL AND OCTET_LENGTH(response_wire_bytes) BETWEEN 1 AND 2048
        AND (registration_authority_sha256 IS NULL OR OCTET_LENGTH(registration_authority_sha256)=32)
        """;
    private static final String DECISION_RESULT="""
        decision IS NOT NULL
        AND ((decision='ISSUED' AND challenge_id IS NOT NULL
               AND challenge_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
               AND registration_authority_sha256 IS NOT NULL AND denial_code IS NULL)
          OR (decision='DENIED' AND challenge_id IS NULL AND denial_code IS NOT NULL AND denial_code='NOT_AUTHORIZED'))
        """;
}
