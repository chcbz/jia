package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentRuntimeV2ExecutionStoreImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentRuntimeV2ExecutionMapper;
import cn.jia.agent.service.impl.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.Catalog;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.IndexDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog;
import static cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.check;

/** Dormant read-only exact additive gate. Never executes the bundled manual SQL. */
public final class AgentRuntimeV2ExecutionSchemaGate implements InitializingBean {
    private final JdbcTemplate jdbc;private final Catalog catalog;private final Runnable baseline;private volatile boolean verified;
    public AgentRuntimeV2ExecutionSchemaGate(JdbcTemplate jdbc,AgentRuntimeV2DispatchSchemaGate baseline){this(jdbc,new JdbcCatalog(jdbc),baseline::afterPropertiesSet);}
    AgentRuntimeV2ExecutionSchemaGate(JdbcTemplate jdbc,Catalog catalog,Runnable baseline){this.jdbc=jdbc;this.catalog=catalog;this.baseline=baseline;}
    public boolean verified(){return verified;}
    static List<ColumnDefinition> extensionColumns(){return List.of(new ColumnDefinition("request_wire_bytes","varbinary","varbinary(512)",true,null,null,"",""),new ColumnDefinition("previous_execution_grant_id","varchar","varchar(36)",true,null,"utf8mb4_0900_bin","",""),new ColumnDefinition("previous_execution_grant_sha256","binary","binary(32)",true,null,null,"",""));}
    static String checkClause(){String sql=sql();return sql.substring(sql.indexOf("CHECK (")+7,sql.lastIndexOf("  );")).trim();}
    static String sql(){try(var stream=AgentRuntimeV2ExecutionSchemaGate.class.getResourceAsStream("/db/agent-runtime-v2-execution-manual-20261001.sql")){if(stream==null)throw new IllegalStateException("execution manual SQL absent");return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    @Override public void afterPropertiesSet(){verified=false;baseline.run();check(catalog.databaseProduct().toLowerCase(Locale.ROOT).contains("mysql"));var table=catalog.table(REQUEST);check(table!=null&&"InnoDB".equalsIgnoreCase(table.engine())&&"utf8mb4_0900_bin".equals(table.collation())&&catalog.participatingForeignKeyCount(REQUEST)==0);
        var columns=catalog.columns(REQUEST);check(columns.size()>=3&&columns.subList(columns.size()-3,columns.size()).equals(extensionColumns()));
        var indexes=catalog.indexes(REQUEST);check(new IndexDefinition(true,List.of("execution_request_id")).equals(indexes.get("uk_execution_request_id"))&&new IndexDefinition(false,List.of("runtime_dispatch_id","started_receipt_id","requested_holder_binding_generation")).equals(indexes.get("idx_execution_request_anchor")));
        var c=catalog.checks(REQUEST).get("chk_execution_request_raw");check(c!=null&&c.enforced()&&AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(checkClause()).equals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(c.clause())));
        check(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,REQUEST)==0);
        validateGraph(jdbc);verified=true;
    }
    static void validateGraph(JdbcTemplate jdbc){
        var store=new AgentRuntimeV2ExecutionStoreImpl(new AgentRuntimeV2ExecutionMapper(jdbc));var validator=new AgentRuntimeV2ExecutionEvidenceValidator(store);var seen=new HashSet<Object>();
        for(var values:jdbc.queryForList("SELECT * FROM "+INTERNAL+" ORDER BY internal_outbox_id")){var r=new AgentRuntimeV2InternalOutboxRow(values);var d=store.hint(DELIVERY,row("id",r.get("delivery_id")));var o=store.hint(OUTBOX,row("id",r.get("source_outbox_id")));var i=store.hint(INBOX,row("id",r.get("inbox_id")));check(d!=null&&o!=null&&i!=null);validator.inspect(new AgentRuntimeV2DispatchSource.Locked(d,o,i),r,false);seen.add(r.get("runtime_dispatch_id"));}
        for(var request:jdbc.queryForList("SELECT * FROM "+REQUEST)){boolean raw=request.get("request_wire_bytes")!=null;if(raw)check(seen.contains(request.get("runtime_dispatch_id")));else check(request.get("previous_execution_grant_id")==null&&request.get("previous_execution_grant_sha256")==null);}
        for(String table:List.of(RECEIPT,FACT,ANCHOR,GRANT))for(var history:jdbc.queryForList("SELECT runtime_dispatch_id FROM "+table+" WHERE evidence_provenance='R2B1_ENFORCED'"))check(seen.contains(history.get("runtime_dispatch_id")));
    }
}
