package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.jia.agent.config.AgentRuntimeV2DispatchSchemaGate.TABLE;

class AgentRuntimeV2DispatchSchemaGateTest {
    static class CatalogFixture implements Catalog {
        List<ColumnDefinition> columns=AgentRuntimeV2DispatchSchemaGate.expectedColumns();Map<String,IndexDefinition> indexes=AgentRuntimeV2DispatchSchemaGate.expectedIndexes();Map<String,CheckDefinition> checks=new HashMap<>();int foreign;
        CatalogFixture(){AgentRuntimeV2DispatchSchemaGate.expectedChecks().forEach((n,c)->checks.put(n,new CheckDefinition(c,true)));}
        public String databaseProduct(){return "MySQL";}public List<String> presentTables(){return List.of(TABLE);}public TableDefinition table(String t){return new TableDefinition("InnoDB","utf8mb4_0900_bin");}public List<ColumnDefinition> columns(String t){return columns;}public Map<String,IndexDefinition> indexes(String t){return indexes;}public int participatingForeignKeyCount(String t){return foreign;}public Map<String,CheckDefinition> checks(String t){return checks;}public long activationReadinessViolationCount(){return 0;}
    }
    @Test void fullOrderedManualContractAndReadOnlyRealGraphPassWithoutRepair() throws Exception {
        var f=new AgentRuntimeV2DispatchTransactionTest.Fixture();f.prepared();var catalog=new CatalogFixture();var jdbc=spy(f.f.jdbc);doReturn(0L).when(jdbc).queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,TABLE);var before=f.f.snapshot();var gate=new AgentRuntimeV2DispatchSchemaGate(jdbc,catalog,()->{});gate.afterPropertiesSet();assertTrue(gate.verified());assertEquals(before,f.f.snapshot());assertEquals(50,catalog.columns.size());assertEquals(6,catalog.indexes.size());assertEquals(4,catalog.checks.size());
    }
    @Test void catalogDefaultNullabilityOrderIndexesChecksFkAndTriggersFailClosed() throws Exception {
        for(String drift:List.of("default","nullable","order","index","check","foreign","trigger")){
            var f=new AgentRuntimeV2DispatchTransactionTest.Fixture();var c=new CatalogFixture();var jdbc=spy(f.f.jdbc);doReturn(drift.equals("trigger")?1L:0L).when(jdbc).queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,TABLE);
            if(drift.equals("default")||drift.equals("nullable")){var columns=new ArrayList<>(c.columns);var old=columns.getFirst();columns.set(0,new ColumnDefinition(old.name(),old.dataType(),old.columnType(),drift.equals("nullable"),drift.equals("default")?"fake":null,old.collation(),old.extra(),old.generationExpression()));c.columns=columns;}
            if(drift.equals("order")){var columns=new ArrayList<>(c.columns);Collections.swap(columns,0,1);c.columns=columns;}
            if(drift.equals("index")){var indexes=new HashMap<>(c.indexes);indexes.remove("idx_runtime_internal_expiry");c.indexes=indexes;}
            if(drift.equals("check")){var old=c.checks.get("chk_runtime_internal_state");c.checks.put("chk_runtime_internal_state",new CheckDefinition(old.clause(),false));}
            if(drift.equals("foreign"))c.foreign=1;var gate=new AgentRuntimeV2DispatchSchemaGate(jdbc,c,()->{});assertThrows(SecurityException.class,gate::afterPropertiesSet,drift);assertFalse(gate.verified());
        }
    }
}
