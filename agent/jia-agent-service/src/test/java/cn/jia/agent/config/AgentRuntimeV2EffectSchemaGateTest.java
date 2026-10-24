package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.Catalog;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.IndexDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.CheckDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.TableDefinition;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual disposable H2 CHECK enforcement of the authored manual SQL clauses.
 * Not production schema-gate acceptance, live MySQL, or permission to execute the manual DDL.
 */
class AgentRuntimeV2EffectSchemaGateTest {
    private static Catalog exactCatalog(){
        Catalog catalog=mock(Catalog.class);when(catalog.databaseProduct()).thenReturn("MySQL");
        AgentRuntimeV2EffectConfiguration.expected().forEach((table,contract)->{
            when(catalog.table(table)).thenReturn(new TableDefinition("InnoDB","utf8mb4_bin"));
            when(catalog.columns(table)).thenReturn(contract.columns());when(catalog.indexes(table)).thenReturn(contract.indexes());
            var checks=new LinkedHashMap<String,CheckDefinition>();contract.checks().forEach((name,clause)->checks.put(name,new CheckDefinition(clause,true)));
            when(catalog.checks(table)).thenReturn(checks);when(catalog.participatingForeignKeyCount(table)).thenReturn(0);
        });return catalog;
    }
    private static JdbcTemplate noTriggers(){
        var jdbc=mock(JdbcTemplate.class);
        for(String table:AgentRuntimeV2EffectConfiguration.expected().keySet())when(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,table)).thenReturn(0L);
        return jdbc;
    }
    @Test void reviewedSchemaMetadataParsesAllFiveTablesInExactMapperColumnOrderWithoutExecutingDdl(){
        var expected=AgentRuntimeV2EffectConfiguration.expected();assertEquals(5,expected.size());
        assertEquals("varchar(14)",expected.get(cn.jia.agent.dao.AgentRuntimeV2EffectStore.REQUEST).columns().stream().filter(column->column.name().equals("subject_kind")).findFirst().orElseThrow().columnType());
        expected.forEach((table,contract)->assertEquals(cn.jia.agent.mapper.AgentRuntimeV2EffectMapper.DEFINITIONS.get(table).columns(),contract.columns().stream().map(ColumnDefinition::name).toList()));
        var jdbc=noTriggers();int[] baseline={0};var gate=new AgentRuntimeV2EffectConfiguration.CatalogGate(jdbc,exactCatalog(),()->baseline[0]++);
        assertFalse(gate.catalogVerified());gate.afterPropertiesSet();assertTrue(gate.catalogVerified());assertEquals(1,baseline[0]);
        verify(jdbc,times(5)).queryForObject(eq("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?"),eq(Long.class),anyString());verifyNoMoreInteractions(jdbc);
    }
    @Test void oldSubjectWidthOrCheckFailsExactCatalogInsteadOfSilentlyTruncatingSuccessLookup(){
        String table=cn.jia.agent.dao.AgentRuntimeV2EffectStore.REQUEST;var contract=AgentRuntimeV2EffectConfiguration.expected().get(table);
        for(boolean oldWidth:List.of(false,true)){
            var catalog=exactCatalog();
            if(oldWidth){var columns=new ArrayList<>(contract.columns());for(int i=0;i<columns.size();i++){var c=columns.get(i);if(c.name().equals("subject_kind"))columns.set(i,new ColumnDefinition(c.name(),c.dataType(),"varchar(12)",c.nullable(),c.defaultValue(),c.collation(),c.extra(),c.generationExpression()));}when(catalog.columns(table)).thenReturn(columns);}
            else{var checks=new LinkedHashMap<String,CheckDefinition>();contract.checks().forEach((name,clause)->checks.put(name,new CheckDefinition(name.equals("ck_r4_request_subject")?clause.replace(",'EFFECT_SUCCESS'",""):clause,true)));when(catalog.checks(table)).thenReturn(checks);}
            var gate=new AgentRuntimeV2EffectConfiguration.CatalogGate(noTriggers(),catalog,()->{});assertThrows(IllegalStateException.class,gate::afterPropertiesSet);assertFalse(gate.catalogVerified());
        }
    }
    @Test void exactMetadataRejectsMissingTablesForeignKeysTriggersColumnsIndexesChecksAndNonMysql(){
        String table=cn.jia.agent.dao.AgentRuntimeV2EffectStore.REQUEST;var contract=AgentRuntimeV2EffectConfiguration.expected().get(table);
        for(String fault:List.of("mysql","missing","engine","collation","foreign-key","column-missing","column-extra","column-type","column-null","column-order","index-missing","index-extra","index-identity","check-missing","check-extra","check-clause","check-unenforced","trigger","null-trigger","baseline")){
            Catalog catalog=exactCatalog();var jdbc=noTriggers();Runnable baseline=()->{};
            switch(fault){
                case "mysql" -> when(catalog.databaseProduct()).thenReturn("H2");
                case "missing" -> when(catalog.table(table)).thenReturn(null);
                case "engine" -> when(catalog.table(table)).thenReturn(new TableDefinition("MyISAM","utf8mb4_bin"));
                case "collation" -> when(catalog.table(table)).thenReturn(new TableDefinition("InnoDB","utf8mb4_general_ci"));
                case "foreign-key" -> when(catalog.participatingForeignKeyCount(table)).thenReturn(1);
                case "column-missing","column-extra","column-type","column-null","column-order" -> {
                    var columns=new ArrayList<>(contract.columns());var old=columns.getFirst();
                    if(fault.equals("column-missing"))columns.removeLast();
                    if(fault.equals("column-extra"))columns.add(new ColumnDefinition("extra","bigint","bigint",true,null,null,"",""));
                    if(fault.equals("column-type"))columns.set(0,new ColumnDefinition(old.name(),old.dataType(),"char(35)",old.nullable(),old.defaultValue(),old.collation(),old.extra(),old.generationExpression()));
                    if(fault.equals("column-null"))columns.set(0,new ColumnDefinition(old.name(),old.dataType(),old.columnType(),!old.nullable(),old.defaultValue(),old.collation(),old.extra(),old.generationExpression()));
                    if(fault.equals("column-order"))java.util.Collections.swap(columns,0,1);
                    when(catalog.columns(table)).thenReturn(columns);
                }
                case "index-missing","index-extra","index-identity" -> {
                    var indexes=new LinkedHashMap<>(contract.indexes());
                    if(fault.equals("index-missing"))indexes.remove("PRIMARY");
                    if(fault.equals("index-extra"))indexes.put("invented",new IndexDefinition(false,List.of("request_id")));
                    if(fault.equals("index-identity"))indexes.put("PRIMARY",new IndexDefinition(false,List.of("runtime_dispatch_id")));
                    when(catalog.indexes(table)).thenReturn(indexes);
                }
                case "check-missing","check-extra","check-clause","check-unenforced" -> {
                    var checks=new LinkedHashMap<String,CheckDefinition>();contract.checks().forEach((name,clause)->checks.put(name,new CheckDefinition(clause,true)));String first=checks.keySet().iterator().next();
                    if(fault.equals("check-missing"))checks.remove(first);
                    if(fault.equals("check-extra"))checks.put("invented",new CheckDefinition("1=1",true));
                    if(fault.equals("check-clause"))checks.put(first,new CheckDefinition("1=1",true));
                    if(fault.equals("check-unenforced"))checks.put(first,new CheckDefinition(checks.get(first).clause(),false));
                    when(catalog.checks(table)).thenReturn(checks);
                }
                case "trigger" -> when(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,table)).thenReturn(1L);
                case "null-trigger" -> when(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,table)).thenReturn(null);
                case "baseline" -> baseline=()->{throw new IllegalStateException("baseline graph invalid");};
                default -> fail("uncovered catalog mutation");
            }
            var gate=new AgentRuntimeV2EffectConfiguration.CatalogGate(jdbc,catalog,baseline);assertThrows(IllegalStateException.class,gate::afterPropertiesSet,fault);assertFalse(gate.catalogVerified(),fault);
        }
        var catalog=exactCatalog();var gate=new AgentRuntimeV2EffectConfiguration.CatalogGate(noTriggers(),catalog,()->{});gate.afterPropertiesSet();assertTrue(gate.catalogVerified());when(catalog.databaseProduct()).thenReturn("H2");assertThrows(IllegalStateException.class,gate::afterPropertiesSet);assertFalse(gate.catalogVerified(),"a failed recheck cannot retain an earlier true metadata flag");
    }
    private static String clause(String name) throws Exception {
        try (var stream=AgentRuntimeV2EffectSchemaGateTest.class.getResourceAsStream("/db/agent-runtime-v2-effects-manual-20261003.sql")) {
            assertNotNull(stream,"manual SQL resource");
            var sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            var prefix="CONSTRAINT "+name+" CHECK (";
            var matches=sql.lines().map(String::strip).filter(line->line.startsWith(prefix)).toList();
            assertEquals(1,matches.size(),"unique exact authored constraint");
            var line=matches.getFirst();assertTrue(line.endsWith(")")||line.endsWith("),"));
            return line.substring(prefix.length(),line.lastIndexOf(')'));
        }
    }
    private record CheckDatabase(SingleConnectionDataSource source,JdbcTemplate jdbc) implements AutoCloseable {
        public void close(){source.destroy();}
    }
    private static CheckDatabase database() {
        // H2 2.4's compiled string IN expression can retain the DDL session.
        // Keep that real session alive for this isolated constraint fixture;
        // destroy it after the test. No production connection policy changes.
        var source=new SingleConnectionDataSource("jdbc:h2:mem:r4check"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","",true);
        return new CheckDatabase(source,new JdbcTemplate(source));
    }
    @Test void allTicketNullCombinationsAreActuallyRejectedExceptAbsentOrComplete() throws Exception {
        try(var database=database()) {
        var jdbc=database.jdbc();
        jdbc.execute("CREATE TABLE test_ticket(admission_request_id VARCHAR(36),admitted_at BIGINT,valid_until BIGINT,CONSTRAINT ck_ticket CHECK ("+clause("ck_r4_effect_ticket")+"))");
        for(int mask=0;mask<8;mask++) {
            Object id=(mask&1)==0?null:UUID.randomUUID().toString(),admitted=(mask&2)==0?null:1000L,until=(mask&4)==0?null:2000L;
            int combination=mask;
            if(mask==0||mask==7)assertEquals(1,jdbc.update("INSERT INTO test_ticket VALUES (?,?,?)",id,admitted,until));
            else assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_ticket VALUES (?,?,?)",id,admitted,until),"partial NULL mask="+combination);
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM test_ticket",Integer.class));
        for(long[] values:new long[][]{{0,1},{-1,1},{1000,1000},{1000,999},{1000,2001}})
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_ticket VALUES (?,?,?)",UUID.randomUUID().toString(),values[0],values[1]));
        assertEquals(1,jdbc.update("INSERT INTO test_ticket VALUES (?,?,?)",UUID.randomUUID().toString(),1000L,1001L));
        }
    }
    @Test void allSubjectNullCombinationsAreCheckedAgainstEachRequestFamily() throws Exception {
        try(var database=database()) {
        var jdbc=database.jdbc();
        jdbc.execute("CREATE TABLE test_subject(request_family VARCHAR(12) NOT NULL,effect_id VARCHAR(68),subject_kind VARCHAR(14),subject_id VARCHAR(100),CONSTRAINT ck_subject CHECK ("+clause("ck_r4_request_subject")+"))");
        int expected=0;
        for(String family:new String[]{"CONTROL","EFFECT","RECONCILE"})for(int mask=0;mask<8;mask++) {
            Object effect=(mask&1)==0?null:"r4e_"+"a".repeat(64),kind=(mask&2)==0?null:"RENEWAL",subject=(mask&4)==0?null:UUID.randomUUID().toString();
            boolean allowed=family.equals("CONTROL")?mask==0:family.equals("EFFECT")?mask==1:(mask&6)==6;
            int combination=mask;
            if(allowed){assertEquals(1,jdbc.update("INSERT INTO test_subject VALUES (?,?,?,?)",family,effect,kind,subject));expected++;}
            else assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_subject VALUES (?,?,?,?)",family,effect,kind,subject),family+" partial NULL mask="+combination);
        }
        assertEquals(expected,jdbc.queryForObject("SELECT COUNT(*) FROM test_subject",Integer.class));
        for(String kind:new String[]{"EXECUTION","EFFECT","EFFECT_SUCCESS"})assertEquals(1,jdbc.update("INSERT INTO test_subject VALUES ('RECONCILE',NULL,?,?)",kind,UUID.randomUUID().toString()));
        for(String kind:new String[]{"renewal","UNKNOWN",""})assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_subject VALUES ('RECONCILE',NULL,?,?)",kind,UUID.randomUUID().toString()));
        }
    }
    private static void actorTable(JdbcTemplate jdbc) throws Exception {
        // Intentionally nullable mandatory columns here: the actual authored
        // CHECK must reject UNKNOWN on its own, in addition to real NOT NULL.
        jdbc.execute("CREATE TABLE test_actor(request_family VARCHAR(12) NOT NULL,actor_registration_fact_id VARCHAR(36),actor_runtime_binding_id VARCHAR(36),actor_runtime_instance_id VARCHAR(100),actor_binding_generation BIGINT,actor_credential_reference VARCHAR(100),actor_session_fence VARCHAR(100),actor_socket_owner_instance_id VARCHAR(100),actor_socket_owner_epoch BIGINT,created_at BIGINT,CONSTRAINT ck_actor CHECK ("+clause("ck_r4_request_actor")+"))");
    }
    @Test void actualNewProcessEpochZeroRouteAllowsOnlyCompleteOrReconcileAbsentActorAcrossAll48Masks() throws Exception {
        try(var database=database()) {
            var jdbc=database.jdbc();actorTable(jdbc);int accepted=0;
            for(String family:List.of("CONTROL","EFFECT","RECONCILE"))for(int mask=0;mask<16;mask++) {
                Object fact=(mask&1)==0?null:UUID.randomUUID().toString(),binding=(mask&2)==0?null:UUID.randomUUID().toString(),instance=(mask&4)==0?null:"new-process",generation=(mask&8)==0?null:0L;
                boolean allowed=mask==15||family.equals("RECONCILE")&&mask==0;int combination=mask;
                if(allowed){assertEquals(1,jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",family,fact,binding,instance,generation,"current-key","actual-fence","actual-server",0L,1000L));accepted++;}
                else assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",family,fact,binding,instance,generation,"current-key","actual-fence","actual-server",0L,1000L),family+" actor NULL mask="+combination);
            }
            assertEquals(4,accepted);assertEquals(accepted,jdbc.queryForObject("SELECT COUNT(*) FROM test_actor",Integer.class));
        }
    }
    @Test void mandatoryTrustedRouteNullsNegativeEpochAndGenerationNeverPassAsSqlUnknown() throws Exception {
        try(var database=database()) {
            var jdbc=database.jdbc();actorTable(jdbc);
            Object[] complete={"RECONCILE",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"actual-process",0L,"current-key","actual-fence","actual-server",0L,1000L};
            assertEquals(1,jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",complete));
            for(int column:new int[]{5,6,7,8,9}) {
                var altered=complete.clone();altered[column]=null;
                assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",altered),"mandatory actor/route NULL column="+column);
            }
            for(int column:new int[]{4,8}) {
                var altered=complete.clone();altered[column]=-1L;
                assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",altered),"negative distinct generation/epoch column="+column);
            }
            var absent=complete.clone();for(int column=1;column<=4;column++)absent[column]=null;
            assertEquals(1,jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",absent));
            for(int column:new int[]{5,6,7,8,9}) {
                var altered=absent.clone();altered[column]=null;
                assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",altered),"audit-only mandatory route NULL column="+column);
            }
            for(long epoch:new long[]{1,9007199254740993L}) {
                var actual=absent.clone();actual[8]=epoch;assertEquals(1,jdbc.update("INSERT INTO test_actor VALUES (?,?,?,?,?,?,?,?,?,?)",actual));
            }
            assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM test_actor",Integer.class));
        }
    }
}
