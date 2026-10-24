package cn.jia.agent.config;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.check;

/** Dormant exact read-only catalog/graph gate. No DDL, seed or repair. */
public final class AgentRuntimeV2DispatchSchemaGate implements InitializingBean {
    public static final String TABLE="agent_runtime_v2_internal_outbox";
    private static final String BIN="utf8mb4_0900_bin";
    private static final String SQL=sql();
    private final JdbcTemplate jdbc;
    private final Catalog catalog;
    private final Runnable baseline;
    private volatile boolean verified;
    public AgentRuntimeV2DispatchSchemaGate(JdbcTemplate jdbc,AgentRuntimeV2RegistrationDeltaSchemaGate baseline){this(jdbc,new JdbcCatalog(jdbc),baseline::afterPropertiesSet);}
    AgentRuntimeV2DispatchSchemaGate(JdbcTemplate jdbc,Catalog catalog,Runnable baseline){this.jdbc=jdbc;this.catalog=catalog;this.baseline=baseline;}
    public boolean verified(){return verified;}
    @Override public void afterPropertiesSet(){
        verified=false;baseline.run();check(catalog.databaseProduct().toLowerCase(Locale.ROOT).contains("mysql"));
        var table=catalog.table(TABLE);check(table!=null&&table.engine().equalsIgnoreCase("InnoDB")&&table.collation().equals(BIN)&&catalog.participatingForeignKeyCount(TABLE)==0);
        check(expectedColumns().equals(catalog.columns(TABLE)));check(expectedIndexes().equals(catalog.indexes(TABLE)));
        var checks=catalog.checks(TABLE);check(checks.keySet().equals(expectedChecks().keySet()));expectedChecks().forEach((name,clause)->check(checks.get(name).enforced()&&AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(clause).equals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(checks.get(name).clause()))));
        check(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,TABLE)==0);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));tx.setReadOnly(true);tx.executeWithoutResult(status->validateGraph(jdbc));verified=true;
    }
    static List<ColumnDefinition> expectedColumns(){
        var columns=new ArrayList<ColumnDefinition>();var matcher=Pattern.compile("(?m)^  ([a-z][a-z0-9_]*) ([A-Z]+(?:\\(\\d+\\))?) (NOT NULL|NULL),$").matcher(SQL);
        while(matcher.find()){String type=matcher.group(2).toLowerCase(Locale.ROOT),data=type.replaceAll("\\(.*","");columns.add(new ColumnDefinition(matcher.group(1),data,type,matcher.group(3).equals("NULL"),null,data.equals("varchar")?BIN:null,"",""));}
        check(columns.stream().map(ColumnDefinition::name).toList().equals(AgentRuntimeV2InternalOutboxRow.COLUMNS));return List.copyOf(columns);
    }
    static Map<String,IndexDefinition> expectedIndexes(){return Map.of("PRIMARY",new IndexDefinition(true,List.of("internal_outbox_id")),"uk_runtime_internal_dispatch",new IndexDefinition(true,List.of("runtime_dispatch_id")),"uk_runtime_internal_inbox",new IndexDefinition(true,List.of("inbox_id")),"idx_runtime_internal_owner_due",new IndexDefinition(false,List.of("socket_owner_instance_id","status","next_attempt_at","internal_outbox_id")),"idx_runtime_internal_claim_expiry",new IndexDefinition(false,List.of("status","claim_until","internal_outbox_id")),"idx_runtime_internal_expiry",new IndexDefinition(false,List.of("expires_at","status","internal_outbox_id")));}
    static Map<String,String> expectedChecks(){var checks=new LinkedHashMap<String,String>();var matcher=Pattern.compile("(?ms)^  CONSTRAINT ([a-z0-9_]+) CHECK \\(\\n(.*?)^  \\)(?:,)?$").matcher(SQL);while(matcher.find())checks.put(matcher.group(1),matcher.group(2));check(checks.size()==4);return Map.copyOf(checks);}
    private static String sql(){try(var stream=AgentRuntimeV2DispatchSchemaGate.class.getResourceAsStream("/db/agent-runtime-v2-dispatch-manual-20261001.sql")){if(stream==null)throw new IllegalStateException("dispatch manual SQL absent");return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    static void validateGraph(JdbcTemplate jdbc){
        var dispatches=new HashSet<String>();var inboxes=new HashSet<Long>();
        for(var persisted:jdbc.queryForList("SELECT * FROM "+TABLE+" ORDER BY internal_outbox_id")){
            var r=new AgentRuntimeV2InternalOutboxRow(persisted);check(dispatches.add(r.text("runtime_dispatch_id"))&&inboxes.add(r.number("inbox_id")));
            var i=jdbc.queryForMap("SELECT * FROM agent_consumer_inbox WHERE id=?",r.number("inbox_id"));var d=jdbc.queryForMap("SELECT * FROM agent_command_delivery WHERE id=?",r.number("delivery_id"));var o=jdbc.queryForMap("SELECT * FROM agent_outbox_event WHERE id=?",r.number("source_outbox_id"));
            check("R2B1_ENFORCED".equals(i.get("evidence_provenance"))&&"PROCESSED".equals(i.get("status"))&&"SENT".equals(i.get("result_status")));
            for(String[] pair:new String[][]{{"runtime_dispatch_id","runtime_dispatch_id"},{"runtime_binding_id","runtime_binding_id"},{"runtime_binding_generation","dispatch_binding_generation"},{"dispatch_runtime_instance_id","runtime_instance_id"},{"dispatch_runtime_credential_client_id","runtime_credential_client_id"},{"dispatch_receiver_registration_revision","receiver_registration_revision"},{"dispatch_generation_fact_id","dispatch_generation_fact_id"},{"dispatch_generation_snapshot_sha256","dispatch_generation_snapshot_sha256"},{"execution_origin_snapshot_sha256","execution_origin_snapshot_sha256"},{"dispatch_prepared_at","prepared_at"},{"delivery_id","delivery_id"},{"message_id","source_message_id"},{"event_id","source_event_id"},{"command_id","command_id"},{"claimant_deployment_id","claimant_deployment_id"},{"claimant_worker_generation","claimant_worker_generation"}})check(same(i.get(pair[0]),r.get(pair[1])));
            for(var source:List.of(d,o,i))for(String[] pair:new String[][]{{"protocol_version","contract_version"},{"tenant_id","tenant_id"},{"data_domain_id","data_domain_id"},{"execution_pool_id","execution_pool_id"},{"client_id","command_caller_client_id"},{"producer_deployment_id","producer_deployment_id"},{"admission_epoch","admission_epoch"},{"module_id","module_id"},{"migration_epoch","migration_epoch"},{"command_id","command_id"},{"expires_at","expires_at"}})check(same(source.get(pair[0]),r.get(pair[1])));
            for(var source:List.of(o,i))check(same(source.get("wire_payload"),r.get("command_wire_bytes"))&&same(source.get("wire_payload_hash"),r.get("command_wire_sha256")));
            check(same(o.get("delivery_id"),r.get("delivery_id"))&&same(o.get("event_id"),r.get("source_event_id"))&&same(o.get("message_id"),r.get("source_message_id"))&&"PUBLISHED".equals(o.get("status")));
            check(same(d.get("active_message_id"),r.get("source_message_id"))&&same(d.get("target_agent_id"),r.get("agent_id"))&&same(d.get("owner_jiacn"),r.get("owner_jiacn"))&&same(d.get("command_payload_hash"),AgentRuntimeV2RegistrationFingerprints.sha256((byte[])d.get("command_payload"))));
            AgentRuntimeV2DispatchSource.canonical(d,r.binary("command_wire_bytes"),r.text("source_message_id"),r.number("delivery_id"),Math.toIntExact(number(d,"active_attempt")));
            var fact=jdbc.queryForMap("SELECT * FROM agent_runtime_v2_binding_generation_fact WHERE binding_generation_fact_id=?",r.text("dispatch_generation_fact_id"));var receipt=jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=?",fact.get("registration_receipt_id"));
            AgentRuntimeV2RegistrationServiceImpl.validateReceipt(receipt);AgentRuntimeV2RegistrationServiceImpl.validateHolder(fact,receipt);
            for(String[] pair:new String[][]{{"runtime_binding_id","runtime_binding_id"},{"binding_generation","dispatch_binding_generation"},{"runtime_instance_id","runtime_instance_id"},{"generation_snapshot_sha256","dispatch_generation_snapshot_sha256"},{"tenant_id","tenant_id"},{"data_domain_id","data_domain_id"},{"execution_pool_id","execution_pool_id"},{"runtime_credential_client_id","runtime_credential_client_id"},{"owner_jiacn","owner_jiacn"},{"agent_id","agent_id"},{"claimant_deployment_id","claimant_deployment_id"},{"claimant_worker_generation","claimant_worker_generation"},{"receiver_registration_revision","receiver_registration_revision"},{"admission_epoch","admission_epoch"},{"module_id","module_id"},{"migration_epoch","migration_epoch"},{"session_fence","session_fence"},{"socket_owner_instance_id","socket_owner_instance_id"},{"socket_owner_epoch","socket_owner_epoch"}})check(same(fact.get(pair[0]),r.get(pair[1])));
        }
        for(var i:jdbc.queryForList("SELECT id,runtime_dispatch_id,dispatch_generation_fact_id,dispatch_generation_snapshot_sha256,execution_origin_snapshot_sha256 FROM agent_consumer_inbox WHERE protocol_version=2 AND evidence_provenance='R2B1_ENFORCED'")){
            boolean prepared=i.get("runtime_dispatch_id")!=null;check(prepared==(i.get("dispatch_generation_fact_id")!=null)&&prepared==(i.get("dispatch_generation_snapshot_sha256")!=null)&&prepared==(i.get("execution_origin_snapshot_sha256")!=null));if(prepared)check(dispatches.contains(i.get("runtime_dispatch_id"))&&inboxes.contains(number(i,"id")));
        }
    }
}
