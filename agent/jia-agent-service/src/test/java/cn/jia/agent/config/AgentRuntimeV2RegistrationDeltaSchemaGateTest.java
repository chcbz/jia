package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.Catalog;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.CheckDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.IndexDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.TableDefinition;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2RegistrationDeltaSchemaGateTest {
    static class CatalogFixture implements Catalog {
        String product="MySQL";int foreignKeys;boolean checkEnforced=true;String engine="InnoDB",collation="utf8mb4_0900_bin";
        final Map<String,List<ColumnDefinition>> columns=new HashMap<>(AgentRuntimeV2RegistrationDeltaSchemaGate.expectedColumns());
        final Map<String,Map<String,IndexDefinition>> indexes=new HashMap<>(AgentRuntimeV2RegistrationDeltaSchemaGate.expectedIndexes());
        final Map<String,Map<String,String>> checks=new HashMap<>(AgentRuntimeV2RegistrationDeltaSchemaGate.expectedChecks());
        public String databaseProduct(){return product;}public List<String> presentTables(){return List.copyOf(AgentRuntimeV2RegistrationDeltaSchemaGate.TABLES);}
        public TableDefinition table(String table){return new TableDefinition(engine,collation);}public List<ColumnDefinition> columns(String table){return columns.get(table);}
        public Map<String,IndexDefinition> indexes(String table){return indexes.get(table);}public int participatingForeignKeyCount(String table){return foreignKeys;}
        public Map<String,CheckDefinition> checks(String table){Map<String,CheckDefinition> result=new HashMap<>();if(FACT.equals(table))result.put("chk_binding_generation_fact_canonical",new CheckDefinition(AgentRuntimeV2RegistrationDeltaSchemaGate.DISCONNECT_CHECK,checkEnforced));else checks.get(table).forEach((key,value)->result.put(key,new CheckDefinition(value,checkEnforced)));return result;}
        public long activationReadinessViolationCount(){return 0;}
    }
    @Test void exactCatalogBaselineIsRequiredAndEveryDriftFailsBeforeGraphRead() throws Exception {
        var fixture=new AgentRuntimeV2RegistrationTransactionTest.Fixture();var catalog=new CatalogFixture();var calls=new AtomicInteger();var gate=new AgentRuntimeV2RegistrationDeltaSchemaGate(fixture.jdbc,catalog,calls::incrementAndGet);gate.afterPropertiesSet();assertTrue(gate.verified());assertEquals(1,calls.get());
        for(int mutation=0;mutation<7;mutation++){var changed=new CatalogFixture();switch(mutation){case 0->changed.product="H2";case 1->changed.engine="MyISAM";case 2->changed.collation="utf8mb4_0900_ai_ci";case 3->changed.foreignKeys=1;case 4->changed.checkEnforced=false;case 5->changed.columns.put(DECISION,List.of());default->changed.indexes.put(COMPLETION,Map.of());}var invalid=new AgentRuntimeV2RegistrationDeltaSchemaGate(fixture.jdbc,changed,()->{});assertThrows(RuntimeException.class,invalid::afterPropertiesSet);assertFalse(invalid.verified());}
        for(String table:AgentRuntimeV2RegistrationDeltaSchemaGate.TABLES)for(String name:AgentRuntimeV2RegistrationDeltaSchemaGate.expectedChecks().get(table).keySet()){var changed=new CatalogFixture();var clauses=new HashMap<>(changed.checks.get(table));clauses.put(name,"1=1");changed.checks.put(table,clauses);assertThrows(RuntimeException.class,()->new AgentRuntimeV2RegistrationDeltaSchemaGate(fixture.jdbc,changed,()->{}).afterPropertiesSet());}
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2RegistrationDeltaSchemaGate(fixture.jdbc,new CatalogFixture(),()->{throw new IllegalStateException("R2b1 not ready");}).afterPropertiesSet());
    }
    @Test void manuallyDeliveredSqlChecksMatchIndependentLiteralContractsAndNoDmlOrFk() throws Exception {
        Path agent=Path.of("../jia-agent-mapper/src/main/resources/db/agent-runtime-v2-challenge-decision-manual-20261001.sql");Path oauth=Path.of("../../oauth/jia-oauth-mapper/src/main/resources/db/runtime-v2-drain-authority-manual-20261001.sql");
        String source=Files.readString(agent)+Files.readString(oauth);String withoutComments=source.replaceAll("(?m)--.*$","");assertFalse(Pattern.compile("(?i)\\b(insert|update|delete|replace|foreign\\s+key)\\b").matcher(withoutComments).find());
        for(var checks:AgentRuntimeV2RegistrationDeltaSchemaGate.expectedChecks().values())checks.forEach((name,expected)->assertEquals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(expected),AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(checkClause(source,name)),name));
        assertEquals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(AgentRuntimeV2RegistrationDeltaSchemaGate.DISCONNECT_CHECK),AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(checkClause(source,"chk_binding_generation_fact_canonical")));
    }
    @Test void mysqlShapedGeneratedMetadataIsEquivalentButChangedCaseLiteralOrExpressionIsNot() throws Exception {
        var fixture=new AgentRuntimeV2RegistrationTransactionTest.Fixture();
        for(String expression:List.of("(case when (`status` = _utf8mb4'OPEN') then concat(`tenant_id`,_utf8mb4':',`module_id`) else NULL end)","(case when (`status` = _utf8mb4'open') then concat(`tenant_id`,_utf8mb4':',`module_id`) else NULL end)","(case when (`status` = _utf8mb4'OPEN') then concat(`tenant_id`,_utf8mb4'/',`module_id`) else NULL end)")) {
            var catalog=new CatalogFixture();var columns=new ArrayList<>(catalog.columns.get(OPERATION));var old=columns.getLast();columns.set(columns.size()-1,new ColumnDefinition(old.name(),old.dataType(),old.columnType(),old.nullable(),old.defaultValue(),old.collation(),old.extra(),expression));catalog.columns.put(OPERATION,columns);var gate=new AgentRuntimeV2RegistrationDeltaSchemaGate(fixture.jdbc,catalog,()->{});if(expression.contains("'open'")||expression.contains("'/'"))assertThrows(SecurityException.class,gate::afterPropertiesSet);else{gate.afterPropertiesSet();assertTrue(gate.verified());}
        }
    }
    @Test void reachableChallengeOrphanBlocksButRetiredUnreachableHistoryIsRetained() throws Exception {
        var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();var r=f.receipt(f.create());byte[] raw=f.challengeWire(r,UUID.randomUUID().toString());f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,raw);AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc);
        f.jdbc.update("DELETE FROM "+DECISION);assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));
        f.jdbc.update("UPDATE "+BINDING+" SET status='REVOKED'");assertDoesNotThrow(()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));assertEquals(1,f.count(CHALLENGE));
        f.jdbc.update("INSERT INTO agent_command_v2_execution_grant VALUES(?,'R2B1_ENFORCED')",r.runtimeBindingId());assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));
    }
    @Test void everyEnforcedExecutionEvidenceRootMakesRetiredOrphanReachable() throws Exception {
        for(String table:List.of("agent_consumer_inbox","agent_command_v2_ack_receipt","agent_command_v2_execution_lease_fact","agent_command_v2_execution_request")) {
            var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();var r=f.receipt(f.create());f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.challengeWire(r,UUID.randomUUID().toString()));f.jdbc.update("DELETE FROM "+DECISION);f.jdbc.update("UPDATE "+BINDING+" SET status='REVOKED'");assertDoesNotThrow(()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));
            if("agent_consumer_inbox".equals(table))f.jdbc.update("INSERT INTO "+table+" VALUES(NULL,?,2,?,'R2B1_ENFORCED')",r.runtimeBindingId(),UUID.randomUUID().toString());else if("agent_command_v2_ack_receipt".equals(table))f.jdbc.update("INSERT INTO "+table+" VALUES(?,2,'R2B1_ENFORCED')",r.runtimeBindingId());else f.jdbc.update("INSERT INTO "+table+" VALUES(?,'R2B1_ENFORCED')",r.runtimeBindingId());assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));
        }
    }
    @Test void graphRecomputesDecisionCipherMetadataWireSubjectAndContextHashes() throws Exception {
        for(String mutation:List.of("UPDATE "+DECISION+" SET request_context_sha256=CAST(REPEAT('x',32) AS BINARY(32))","UPDATE "+DECISION+" SET response_wire_sha256=CAST(REPEAT('x',32) AS BINARY(32))","UPDATE "+CHALLENGE+" SET expires_at=expires_at+1","UPDATE "+CHALLENGE+" SET semantic_sha256=CAST(REPEAT('x',32) AS BINARY(32))","UPDATE "+CHALLENGE+" SET status='DENIED'","UPDATE "+CHALLENGE+" SET consumed_at=issued_at","UPDATE "+DECISION+" SET denial_code='NOT_AUTHORIZED'")) {
            var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();var r=f.receipt(f.create());f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.challengeWire(r,UUID.randomUUID().toString()));f.jdbc.update(mutation);assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc));
        }
    }
    private static String checkClause(String sql,String name){Matcher start=Pattern.compile("(?i)ADD CONSTRAINT\\s+"+name+"\\s+CHECK\\s*\\(|CONSTRAINT\\s+"+name+"\\s+CHECK\\s*\\(").matcher(sql);assertTrue(start.find(),name);int begin=start.end(),depth=1;boolean quote=false;for(int i=begin;i<sql.length();i++){char c=sql.charAt(i);if(c=='\'')quote=!quote;else if(!quote&&c=='(')depth++;else if(!quote&&c==')'&&--depth==0)return sql.substring(begin,i);}throw new AssertionError(name);}
}
