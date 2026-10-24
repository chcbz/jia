package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.*;
import static cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact production gates with MySQL-shaped metadata and real isolated H2 graphs, not live MySQL. */
class AgentRuntimeV2ExecutionSchemaGateTest {
    static class CatalogFixture implements Catalog {
        JdbcTemplate activationJdbc;
        final AgentRuntimeV2PersistenceSchemaGateTest.FakeCatalog persistence=AgentRuntimeV2PersistenceSchemaGateTest.registrationCanonical();
        final Map<String,List<ColumnDefinition>> columns=new LinkedHashMap<>();
        final Map<String,Map<String,IndexDefinition>> indexes=new LinkedHashMap<>();
        final Map<String,Map<String,CheckDefinition>> checks=new LinkedHashMap<>();
        CatalogFixture(){
            persistence.columns.clear();AgentRuntimeV2PersistenceSchemaGate.executionExtendedColumns().forEach((t,c)->persistence.columns.put(t,new ArrayList<>(c)));
            persistence.checks.get(REQUEST).put("chk_execution_request_raw",new AgentRuntimeV2PersistenceSchemaGate.CheckDefinition(AgentRuntimeV2ExecutionSchemaGate.checkClause(),true));
            persistence.checks.get(AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT).put("chk_binding_generation_fact_canonical",new AgentRuntimeV2PersistenceSchemaGate.CheckDefinition(AgentRuntimeV2RegistrationDeltaSchemaGate.DISCONNECT_CHECK,true));
            persistence.columns.forEach((t,c)->columns.put(t,new ArrayList<>(c.stream().map(v->new ColumnDefinition(v.name(),v.dataType(),v.columnType(),v.nullable(),v.defaultValue(),v.collation(),v.extra(),v.generationExpression())).toList())));
            persistence.indexes.forEach((t,c)->{var m=new LinkedHashMap<String,IndexDefinition>();c.forEach((n,v)->m.put(n,new IndexDefinition(v.unique(),v.columns())));indexes.put(t,m);});
            persistence.checks.forEach((t,c)->{var m=new LinkedHashMap<String,CheckDefinition>();c.forEach((n,v)->m.put(n,new CheckDefinition(v.clause(),v.enforced())));checks.put(t,m);});
            for(String t:AgentRuntimeV2RegistrationSchemaGate.NEW_TABLES){columns.put(t,AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(t));indexes.put(t,AgentRuntimeV2RegistrationSchemaGate.expectedIndexes().get(t));var m=new LinkedHashMap<String,CheckDefinition>();AgentRuntimeV2RegistrationSchemaGate.expectedChecks().get(t).forEach((n,c)->m.put(n,new CheckDefinition(c,true)));checks.put(t,m);}
            AgentRuntimeV2RegistrationDeltaSchemaGate.expectedColumns().forEach(columns::put);AgentRuntimeV2RegistrationDeltaSchemaGate.expectedIndexes().forEach(indexes::put);AgentRuntimeV2RegistrationDeltaSchemaGate.expectedChecks().forEach((t,c)->{var m=new LinkedHashMap<String,CheckDefinition>();c.forEach((n,v)->m.put(n,new CheckDefinition(v,true)));checks.put(t,m);});
            columns.put(INTERNAL,AgentRuntimeV2DispatchSchemaGate.expectedColumns());indexes.put(INTERNAL,AgentRuntimeV2DispatchSchemaGate.expectedIndexes());var m=new LinkedHashMap<String,CheckDefinition>();AgentRuntimeV2DispatchSchemaGate.expectedChecks().forEach((n,c)->m.put(n,new CheckDefinition(c,true)));checks.put(INTERNAL,m);
        }
        public String databaseProduct(){return "MySQL";}public List<String> presentTables(){return List.copyOf(AgentRuntimeV2RegistrationSchemaGate.TABLES);}
        public TableDefinition table(String t){return new TableDefinition("InnoDB","utf8mb4_0900_bin");}
        public List<ColumnDefinition> columns(String t){return columns.get(t);}public Map<String,IndexDefinition> indexes(String t){return indexes.get(t);}
        public int participatingForeignKeyCount(String t){return 0;}public Map<String,CheckDefinition> checks(String t){return checks.get(t);}
        public long activationReadinessViolationCount(){return new JdbcCatalog(Objects.requireNonNull(activationJdbc)).activationReadinessViolationCount();}
        void mutate(String drift){
            var c=new ArrayList<>(persistence.columns.get(REQUEST));var old=c.getLast();
            switch(drift){case "missing"->c.removeLast();case "order"->Collections.swap(c,c.size()-1,c.size()-2);case "extra"->c.add(old);
                case "type","nullable","default","collation"->c.set(c.size()-1,new AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition(old.name(),old.dataType(),drift.equals("type")?"binary(16)":old.columnType(),!drift.equals("nullable"),drift.equals("default")?"fake":old.defaultValue(),drift.equals("collation")?"utf8mb4_general_ci":old.collation(),old.extra(),old.generationExpression()));
                default->{} }
            persistence.columns.put(REQUEST,c);
            if(Set.of("check","unenforced","missing-check","extra-check").contains(drift)){var m=persistence.checks.get(REQUEST);if(drift.equals("missing-check"))m.remove("chk_execution_request_raw");else m.put(drift.equals("extra-check")?"attacker":"chk_execution_request_raw",new AgentRuntimeV2PersistenceSchemaGate.CheckDefinition(drift.equals("check")||drift.equals("extra-check")?"1=1":AgentRuntimeV2ExecutionSchemaGate.checkClause(),!drift.equals("unenforced")));}
        }
    }
    static JdbcTemplate jdbc(AgentRuntimeV2ExecutionTransactionTest.Fixture f){var jdbc=spy(f.f.jdbc);for(String table:List.of(INTERNAL,REQUEST))doReturn(0L).when(jdbc).queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,table);return jdbc;}
    static AgentRuntimeV2ExecutionSchemaGate fullChain(JdbcTemplate jdbc,CatalogFixture c,AtomicInteger calls){
        c.activationJdbc=jdbc;
        var persistence=new AgentRuntimeV2PersistenceSchemaGate(c.persistence);var registration=new AgentRuntimeV2RegistrationSchemaGate(c);
        var delta=new AgentRuntimeV2RegistrationDeltaSchemaGate(jdbc,c,()->{persistence.afterPropertiesSet();registration.afterPropertiesSet();calls.incrementAndGet();});
        var dispatch=new AgentRuntimeV2DispatchSchemaGate(jdbc,c,delta::afterPropertiesSet);
        return new AgentRuntimeV2ExecutionSchemaGate(jdbc,c,dispatch::afterPropertiesSet);
    }
    @Test void exactExtensionTraversesAllFiveRealProductionGatesReadOnly() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.reconnect();assertFalse(f.service.continueExecution(f.f.identity,f.socket,f.continuation(UUID.randomUUID().toString())).close());f.f.clock.value+=1000;assertFalse(f.service.renewExecution(f.f.identity,f.socket,f.renewal(UUID.randomUUID().toString())).close());assertFalse(f.ack(f.ack("SUCCEEDED")).close());
        var before=f.f.snapshot();var calls=new AtomicInteger();var gate=fullChain(jdbc(f),new CatalogFixture(),calls);gate.afterPropertiesSet();assertTrue(gate.verified());assertEquals(1,calls.get());assertEquals(before,f.f.snapshot());
    }
    @Test void fullRealBaselineDeniesEveryPartialWrongOrAdditionalShape() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();for(String drift:List.of("missing","order","type","nullable","default","collation","extra","check","unenforced","missing-check","extra-check")){var c=new CatalogFixture();c.mutate(drift);var before=f.f.snapshot();var calls=new AtomicInteger();var gate=fullChain(jdbc(f),c,calls);assertThrows(RuntimeException.class,gate::afterPropertiesSet,drift);assertFalse(gate.verified());assertEquals(0,calls.get());assertEquals(before,f.f.snapshot());}
    }
    @Test void rawSqlNullEvidenceLengthKindAndCanonicalPredecessorAreEnforcedInFixture() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.clock.value+=1000;f.service.renewExecution(f.f.identity,f.socket,f.renewal(UUID.randomUUID().toString()));
        for(String mutation:List.of("previous_execution_grant_id=NULL","previous_execution_grant_sha256=NULL","request_wire_bytes=CAST('x' AS VARBINARY)","previous_execution_grant_id='NONCANONICAL'","request_kind='CONTINUATION'"))assertThrows(RuntimeException.class,()->f.f.jdbc.update("UPDATE "+REQUEST+" SET "+mutation));
        // Historical all-NULL extension survives DDL, but is not current raw authority.
        f.f.jdbc.update("UPDATE "+REQUEST+" SET request_wire_bytes=NULL,previous_execution_grant_id=NULL,previous_execution_grant_sha256=NULL");var before=f.f.snapshot();assertThrows(SecurityException.class,()->AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.f.jdbc));assertEquals(before,f.f.snapshot());
    }
    @Test void unreachableLegacyNullExtensionIsAuditOnlyAndNeverGuessedOrRewritten() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.service.continueExecution(f.f.identity,f.socket,f.continuation(UUID.randomUUID().toString()));
        f.f.jdbc.update("UPDATE "+REQUEST+" SET runtime_dispatch_id=?,evidence_provenance='LEGACY_PRE_R2B1',execution_origin_snapshot_sha256=NULL,requested_holder_generation_fact_id=NULL,requested_holder_generation_snapshot_sha256=NULL,request_wire_bytes=NULL",UUID.randomUUID().toString());var before=f.f.snapshot();assertDoesNotThrow(()->AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.f.jdbc));assertEquals(before,f.f.snapshot());
        // A raw request without an enforced reachable internal root blocks readiness.
        f.f.jdbc.update("UPDATE "+REQUEST+" SET request_wire_bytes=?",f.continuation(UUID.randomUUID().toString()));assertThrows(SecurityException.class,()->AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.f.jdbc));
    }
}
