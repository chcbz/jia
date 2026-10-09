package cn.jia.agent.config;

import cn.jia.agent.mapper.AgentRuntimeV2EffectMapper;
import cn.jia.agent.mapper.AgentRuntimeV2ExecutionMapper;
import cn.jia.agent.entity.AgentRuntimeV2InternalOutboxRow;
import cn.jia.agent.entity.ExecutionOriginSnapshot;
import cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2EffectStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh actual same-datasource mapper transactions on disposable H2 only.
 * These persistence seams are not composite ACL/resource reducer acceptance.
 */
class AgentRuntimeV2EffectTransactionTest {
    // Exact old expression, using the SAME configured production mapper.
    // No reflection mutation or bypass of the final row's constructor checks.
    private static String originalHistoricalStableKey(AgentRuntimeV2InternalOutboxRow origin) throws Exception {
        var field=AgentRuntimeV2HistoricalSettlementEvidenceValidator.class.getDeclaredField("JSON");field.setAccessible(true);
        var JSON=(tools.jackson.databind.ObjectMapper)field.get(null);
        return JSON.writeValueAsString(List.of(origin.text("tenant_id"),origin.text("data_domain_id"),origin.text("execution_pool_id"),origin.text("command_id"),Long.toString(origin.number("delivery_id"))));
    }
    private static AgentRuntimeV2InternalOutboxRow historicalStableKeyOrigin(AgentRuntimeV2InternalOutboxRow base,String tenant,String domain,String pool,String command,long delivery){
        var values=base.values();values.put("tenant_id",tenant);values.put("data_domain_id",domain);values.put("execution_pool_id",pool);values.put("command_id",command);values.put("delivery_id",delivery);
        var prior=base.origin();
        var snapshot=new ExecutionOriginSnapshot(prior.runtimeDispatchId(),prior.inboxId(),delivery,tenant,domain,pool,prior.commandCallerClientId(),prior.runtimeCredentialClientId(),prior.ownerJiacn(),prior.agentId(),prior.producerDeploymentId(),prior.claimantDeploymentId(),prior.claimantWorkerGeneration(),prior.receiverRegistrationRevision(),prior.admissionEpoch(),prior.moduleId(),prior.migrationEpoch(),prior.runtimeBindingId(),prior.dispatchBindingGeneration(),prior.runtimeInstanceId(),prior.dispatchGenerationFactId(),prior.dispatchGenerationSnapshotSha256());
        values.put("execution_origin_snapshot_sha256",snapshot.sha256());
        // A standalone, completely validated origin-row input, not a rewritten
        // persisted origin/receipt or a claimed accepted execution graph.
        return new AgentRuntimeV2InternalOutboxRow(values);
    }
    private static List<AgentRuntimeV2InternalOutboxRow> historicalStableKeyOrigins(AgentRuntimeV2InternalOutboxRow base){
        var origins=new ArrayList<AgentRuntimeV2InternalOutboxRow>();origins.add(base);
        origins.add(historicalStableKeyOrigin(base,"0","cyf","local-experiment-a","command",1));
        origins.add(historicalStableKeyOrigin(base,"9".repeat(50),"d"+"9".repeat(63),"p".repeat(100),"x".repeat(100),Long.MAX_VALUE));
        for(String command:List.of("quote-\"-backslash-\\-slash-/","cmd-中文-é-e\u0301-😀-𐀀-end","cmd-\u2028-\u2029-end","</script>&/'\"\\[]","😀".repeat(25),"e\u0301".repeat(33)+"x")){
            origins.add(historicalStableKeyOrigin(base,"1","a-9","p-1",command,Long.MAX_VALUE-1));
        }
        return List.copyOf(origins);
    }
    private static void historicalStableKeyEquivalent(AgentRuntimeV2InternalOutboxRow origin) throws Exception {
        String expected=originalHistoricalStableKey(origin),actual=AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origin);
        assertEquals(expected,actual,"exact original configured String, not parsed-tree equivalence");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8),"exact immutable identity UTF-8 bytes");
    }
    private static Throwable uninstrumentedHistoricalStableKeyNullFailure() throws Exception {
        // Other original tests spy this validator. Read its unchanged compiled
        // bytes into an independent test-only type so Mockito's copied local
        // slots cannot rewrite the VM's helpful-NPE message under comparison.
        String name=AgentRuntimeV2HistoricalSettlementEvidenceValidator.class.getName();final byte[] bytecode;
        try(var input=AgentRuntimeV2HistoricalSettlementEvidenceValidator.class.getResourceAsStream("/"+name.replace('.','/')+".class")){bytecode=Objects.requireNonNull(input).readAllBytes();}
        var loader=new ClassLoader(AgentRuntimeV2HistoricalSettlementEvidenceValidator.class.getClassLoader()){Class<?> isolated(){return defineClass(name,bytecode,0,bytecode.length);}};
        var method=loader.isolated().getMethod("stableKey",AgentRuntimeV2InternalOutboxRow.class);
        return assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,new Object[]{null})).getCause();
    }
    @Test void historicalStableKeyGeneratorMatchesExactConfiguredOracleForValidOriginBoundaries() throws Exception {
        try(var fixture=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var origins=historicalStableKeyOrigins(fixture.origin());
            assertEquals("[\"0\",\"cyf\",\"local-experiment-a\",\"command\",\"1\"]",AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origins.get(1)),"original five-field order and decimal string delivery");
            for(var origin:origins)for(int repeat=0;repeat<8;repeat++)historicalStableKeyEquivalent(origin);
            assertTrue(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origins.get(2)).endsWith(",\"9223372036854775807\"]"));
            assertEquals(100,origins.get(7).text("command_id").getBytes(StandardCharsets.UTF_8).length);
        }
    }
    @Test void historicalStableKeyGeneratorUsesIndependentLocalWritersForConcurrentRepeatedCalls() throws Exception {
        final List<AgentRuntimeV2InternalOutboxRow> origins;
        try(var fixture=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){origins=historicalStableKeyOrigins(fixture.origin());}
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{
            var calls=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int worker=0;worker<16;worker++){final int offset=worker;calls.add(()->{for(int repeat=0;repeat<64;repeat++)historicalStableKeyEquivalent(origins.get((offset+repeat)%origins.size()));return null;});}
            for(var result:pool.invokeAll(calls))assertNull(result.get());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void historicalStableKeyRejectsInvalidOriginBeforeGenerationAndRetainsNullFailure() throws Exception {
        var oldFailure=assertThrows(NullPointerException.class,()->originalHistoricalStableKey(null));
        var newFailure=assertInstanceOf(NullPointerException.class,uninstrumentedHistoricalStableKeyNullFailure());
        assertEquals(oldFailure.getClass(),newFailure.getClass());assertEquals(oldFailure.getMessage(),newFailure.getMessage());assertEquals(oldFailure.getCause(),newFailure.getCause());
        try(var fixture=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var base=fixture.origin();
            // Invalid/missing fields, noncanonical tenant/domain/pool, zero or
            // negative delivery, controls and unpaired surrogates never reach
            // either serializer: the unchanged final origin row rejects them.
            var rejected=new LinkedHashMap<String,List<Object>>();
            rejected.put("tenant_id",Arrays.asList(null,"","00","-1","9".repeat(51)));
            rejected.put("data_domain_id",Arrays.asList(null,"","Cyf","d".repeat(65)));
            rejected.put("execution_pool_id",Arrays.asList(null,"","pool space","p".repeat(101)));
            rejected.put("command_id",Arrays.asList(null,""," leading","trailing ","a\nb","a\u0000b","\uD800","\uDC00","a".repeat(101),"😀".repeat(26)));
            rejected.put("delivery_id",Arrays.asList(null,0L,-1L));
            rejected.forEach((name,invalids)->invalids.forEach(invalid->{var values=base.values();values.put(name,invalid);assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2InternalOutboxRow(values),name+" rejected before stableKey");}));
            historicalStableKeyEquivalent(base);
        }
    }
    @Test void retainedLockObserverExecutesBothActualJdbcApisOnceAndKeepsExactRawOrder() {
        var source=new AgentRuntimeV2RegistrationTransactionTest.TrackingDataSource("jdbc:h2:mem:r4lockobserver"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new AgentRuntimeV2RegistrationTransactionTest.TrackingJdbc(source);
        try {
            jdbc.execute("CREATE TABLE lock_observer_probe (id BIGINT PRIMARY KEY,payload VARCHAR(32))");
            jdbc.execute("CREATE SEQUENCE lock_observer_read START WITH 1 INCREMENT BY 1");
            assertEquals(1,jdbc.update("INSERT INTO lock_observer_probe (id,payload) VALUES (?,?)",1L,"before"));
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                String mapSql="SELECT id,payload,NEXT VALUE FOR lock_observer_read AS read_number FROM lock_observer_probe WHERE id=? FOR UPDATE";
                var maps=jdbc.queryForList(mapSql,1L);assertEquals(1,maps.size());assertEquals(1L,maps.getFirst().get("id"));assertEquals("before",maps.getFirst().get("payload"));assertEquals(1L,maps.getFirst().get("read_number"));assertEquals(List.of(mapSql),jdbc.locks);
                assertEquals(1,jdbc.update("UPDATE lock_observer_probe SET payload=? WHERE id=?","after",1L));
                String ordinalSql="SELECT payload,id,NEXT VALUE FOR lock_observer_read AS read_number FROM lock_observer_probe WHERE id=? FOR UPDATE";
                var ordinals=jdbc.query(ordinalSql,(result,index)->row("payload",result.getString(1),"id",result.getLong(2),"read_number",result.getLong(3)),1L);
                assertEquals(List.of(row("payload","after","id",1L,"read_number",2L)),ordinals);assertEquals(List.of(mapSql,ordinalSql),jdbc.locks);
                // The real H2 sequence proves exactly two executed reads, not
                // fabricated trace entries or a duplicate superclass query.
                assertEquals(3L,jdbc.queryForObject("SELECT NEXT VALUE FOR lock_observer_read",Long.class));
                assertEquals("after",jdbc.queryForList("SELECT payload FROM lock_observer_probe WHERE id=?",1L).getFirst().get("payload"));
                assertEquals(List.of(mapSql,ordinalSql),jdbc.locks,"non-lock reads cannot fabricate lock trace entries");
            });
        } finally {jdbc.execute("SHUTDOWN");}
    }
    private static final Map<String,String> EXECUTION_IDENTITIES=Map.of(
        "agent_command_v2_ack_receipt","receipt_id","agent_command_v2_execution_lease_fact","lease_fact_id",
        "agent_command_v2_execution_continuation_anchor","execution_grant_id","agent_command_v2_execution_request","execution_request_id",
        "agent_command_v2_execution_grant","execution_grant_id","agent_runtime_v2_internal_outbox","internal_outbox_id",
        "agent_command_delivery","id","agent_outbox_event","id","agent_consumer_inbox","id");
    private static final List<String> EXECUTION_HISTORIES=List.of("agent_command_v2_ack_receipt","agent_command_v2_execution_lease_fact","agent_command_v2_execution_continuation_anchor","agent_command_v2_execution_request","agent_command_v2_execution_grant");
    private record ExecutionColumn(String name,String type,boolean nullable) {}
    private static Map<String,List<ExecutionColumn>> executionSchemas() {
        var schemas=new LinkedHashMap<String,List<ExecutionColumn>>();
        var persistence=AgentRuntimeV2PersistenceSchemaGate.executionExtendedColumns();
        for(String table:List.of("agent_command_v2_ack_receipt","agent_command_v2_execution_lease_fact","agent_command_v2_execution_continuation_anchor","agent_command_v2_execution_request","agent_command_v2_execution_grant","agent_consumer_inbox"))schemas.put(table,persistence.get(table).stream().map(c->new ExecutionColumn(c.name(),c.columnType(),c.nullable())).toList());
        schemas.put("agent_runtime_v2_internal_outbox",AgentRuntimeV2DispatchSchemaGate.expectedColumns().stream().map(c->new ExecutionColumn(c.name(),c.columnType(),c.nullable())).toList());
        for(String table:List.of("agent_command_delivery","agent_outbox_event"))schemas.put(table,AgentOutboxRelayV2SchemaGate.expectedColumns(table).stream().map(c->new ExecutionColumn(c.name(),c.columnType(),c.nullable())).toList());
        return schemas;
    }
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        final List<String> selects=new ArrayList<>();
        RecordingJdbcTemplate(SingleConnectionDataSource source){super(source);}
        @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args){selects.add(sql);return super.query(sql,mapper,args);}
    }
    /** Full schema-column/type fixture, not the smaller reducer graph fixture.
     * H2 adapts only MEDIUMBLOB to BLOB; no claim of MySQL constraints or timing.
     */
    private static final class ExecutionFixture implements AutoCloseable {
        final SingleConnectionDataSource source;
        final RecordingJdbcTemplate jdbc;
        final AgentRuntimeV2ExecutionMapper mapper;
        final TransactionTemplate tx;
        final Map<String,List<ExecutionColumn>> schemas=executionSchemas();
        ExecutionFixture(){
            source=new SingleConnectionDataSource("jdbc:h2:mem:r4executionmapper"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","",true);
            jdbc=new RecordingJdbcTemplate(source);mapper=new AgentRuntimeV2ExecutionMapper(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            schemas.forEach((table,columns)->jdbc.execute("CREATE TABLE "+table+" ("+columns.stream().map(c->c.name()+" "+(c.type().equals("mediumblob")?"BLOB":c.type())+(c.nullable()?" NULL":" NOT NULL")).collect(java.util.stream.Collectors.joining(","))+")"));
        }
        void insert(String table,Map<String,Object> values){assertEquals(1,jdbc.update("INSERT INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray()));}
        public void close(){source.destroy();}
    }
    private static Object executionValue(ExecutionColumn column,int seed){
        if(column.type().equals("bigint"))return Long.valueOf(seed);
        if(column.type().equals("int"))return Integer.valueOf(seed);
        if(column.type().equals("smallint"))return Short.valueOf((short)seed);
        if(column.type().startsWith("varchar("))return "v"+seed;
        if(column.type().startsWith("binary(")){int length=Integer.parseInt(column.type().substring(7,column.type().length()-1));byte[] value=new byte[length];Arrays.fill(value,(byte)seed);return value;}
        if(column.type().startsWith("varbinary(")||column.type().equals("mediumblob"))return new byte[]{(byte)seed,(byte)(seed+1),(byte)(seed+2)};
        throw new AssertionError("unhandled actual execution schema type: "+column.type());
    }
    private static Map<String,Object> executionRow(List<ExecutionColumn> columns,int seed,boolean nullableNull){
        var result=new LinkedHashMap<String,Object>();columns.forEach(c->result.put(c.name(),nullableNull&&c.nullable()?null:executionValue(c,seed)));return result;
    }
    private static void exactExecutionPhysicalRow(List<ExecutionColumn> columns,Map<String,Object> expected,Map<String,Object> actual){
        assertNotNull(actual);assertEquals(columns.stream().map(ExecutionColumn::name).toList(),new ArrayList<>(expected.keySet()));assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.keySet()));
        expected.forEach((name,value)->{if(value!=null)assertEquals(value.getClass(),actual.get(name).getClass(),name+" JDBC type");if(value instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])actual.get(name),name);else assertEquals(value,actual.get(name),name);});
    }
    @Test void executionFixedColumnsMatchEveryFullGatePhysicalValueTypeAndSameTransactionUpdate() throws Exception {
        var field=AgentRuntimeV2ExecutionMapper.class.getDeclaredField("COLUMNS");field.setAccessible(true);
        @SuppressWarnings("unchecked") var fixed=(Map<String,List<String>>)field.get(null);
        try(var f=new ExecutionFixture()){
            assertEquals(f.schemas.keySet(),fixed.keySet());assertEquals(390,f.schemas.values().stream().mapToInt(List::size).sum());
            f.tx.executeWithoutResult(status->{
                f.schemas.forEach((table,columns)->{
                    assertEquals(columns.stream().map(ExecutionColumn::name).toList(),fixed.get(table),table+" complete schema order");
                    f.insert(table,executionRow(columns,1,true));String identity=EXECUTION_IDENTITIES.get(table);
                    var physical=f.jdbc.queryForMap("SELECT * FROM "+table);var key=row(identity,physical.get(identity));
                    f.jdbc.selects.clear();exactExecutionPhysicalRow(columns,physical,f.mapper.select(table,key,false));exactExecutionPhysicalRow(columns,physical,f.mapper.select(table,key,true));
                    String select="SELECT "+String.join(",",fixed.get(table))+" FROM "+table+" WHERE "+identity+"=?";assertEquals(List.of(select,select+" FOR UPDATE"),f.jdbc.selects);
                    var nullable=columns.stream().filter(ExecutionColumn::nullable).findFirst();if(nullable.isPresent())exactExecutionPhysicalRow(columns,physical,f.mapper.select(table,row(nullable.get().name(),null),true));
                    // Independently alter every actual physical field, including
                    // stable IDs, NULL/binary/provenance and execution extensions.
                    // A prior read in this SAME transaction cannot hide the change.
                    for(int index=0;index<columns.size();index++){
                        var column=columns.get(index);Object beforeIdentity=physical.get(identity),updated=executionValue(column,1000+index);
                        assertEquals(1,f.jdbc.update("UPDATE "+table+" SET "+column.name()+"=? WHERE "+identity+"=?",updated,beforeIdentity));
                        Object afterIdentity=column.name().equals(identity)?updated:beforeIdentity;
                        physical=f.jdbc.queryForMap("SELECT * FROM "+table+" WHERE "+identity+"=?",afterIdentity);key=row(identity,afterIdentity);
                        exactExecutionPhysicalRow(columns,physical,f.mapper.select(table,key,false));exactExecutionPhysicalRow(columns,physical,f.mapper.select(table,key,true));
                    }
                });
            });
        }
    }
    @Test void executionFullHistoriesKeepOriginalOrderScopeAndReadGuards() throws Exception {
        try(var f=new ExecutionFixture()){
            for(String table:f.schemas.keySet()){
                assertThrows(IllegalStateException.class,()->f.mapper.select(table,row(EXECUTION_IDENTITIES.get(table),1L),true));
                assertThrows(IllegalArgumentException.class,()->f.mapper.select(table,Map.of(),false));
                assertThrows(IllegalArgumentException.class,()->f.mapper.select(table,row("bad;column",1),false));
                assertNull(f.mapper.select(table,row(EXECUTION_IDENTITIES.get(table),"id".equals(EXECUTION_IDENTITIES.get(table))?Long.valueOf(99):"missing"),false));
                if(!EXECUTION_HISTORIES.contains(table))assertThrows(IllegalArgumentException.class,()->f.mapper.rows(table,"scope",false));
            }
            assertThrows(IllegalArgumentException.class,()->f.mapper.select("attacker_table",row("id",1),false));
            assertThrows(IllegalArgumentException.class,()->f.mapper.rows("attacker_table","scope",false));
            assertThrows(NullPointerException.class,()->f.mapper.select("agent_command_delivery",null,false));
            f.tx.executeWithoutResult(status->{
                for(String table:EXECUTION_HISTORIES){
                    var columns=f.schemas.get(table);String identity=EXECUTION_IDENTITIES.get(table);
                    for(int seed=1;seed<=3;seed++){var values=executionRow(columns,seed,false);values.put(identity,seed==1?"z":seed==2?"a":"foreign");values.put("runtime_dispatch_id",seed<3?"scope":"outside");f.insert(table,values);}
                    for(boolean lock:List.of(false,true)){
                        var physical=f.jdbc.queryForList("SELECT * FROM "+table+" WHERE runtime_dispatch_id=? ORDER BY "+identity+(lock?" FOR UPDATE":""),"scope");
                        f.jdbc.selects.clear();var actual=f.mapper.rows(table,"scope",lock);assertEquals(2,actual.size());for(int index=0;index<physical.size();index++)exactExecutionPhysicalRow(columns,physical.get(index),actual.get(index));
                        assertEquals(List.of("SELECT "+String.join(",",columns.stream().map(ExecutionColumn::name).toList())+" FROM "+table+" WHERE runtime_dispatch_id=? ORDER BY "+identity+(lock?" FOR UPDATE":"")),f.jdbc.selects);
                        assertTrue(f.mapper.rows(table,"missing",lock).isEmpty());assertTrue(f.mapper.rows(table,null,lock).isEmpty());
                    }
                    assertEquals(1,f.jdbc.update("UPDATE "+table+" SET runtime_dispatch_id='new-scope' WHERE "+identity+"='a'"));assertEquals(1,f.mapper.rows(table,"scope",true).size());assertEquals(1,f.mapper.rows(table,"new-scope",true).size());
                    var duplicate=executionRow(columns,4,false);duplicate.put(identity,"z");duplicate.put("runtime_dispatch_id","other-scope");f.insert(table,duplicate);
                    assertThrows(SecurityException.class,()->f.mapper.select(table,row(identity,"z"),false));assertThrows(SecurityException.class,()->f.mapper.select(table,row(identity,"z"),true));
                }
            });
            for(String table:EXECUTION_HISTORIES)assertThrows(IllegalStateException.class,()->f.mapper.rows(table,"scope",true));
            var readonly=new TransactionTemplate(new DataSourceTransactionManager(f.source));readonly.setReadOnly(true);
            assertThrows(IllegalStateException.class,()->readonly.executeWithoutResult(status->f.mapper.select("agent_command_delivery",row("id",1L),true)));
        }
    }
    @Test void executionColumnPatternIsExactlyEquivalentToOriginalMatchesValidation() throws Exception {
        var column=AgentRuntimeV2ExecutionMapper.class.getDeclaredMethod("column",String.class);column.setAccessible(true);
        var inputs=new ArrayList<>(List.of("a","column_1","id","runtime_dispatch_id","","1id","_id","ID","a.b","a-b","a b","a\n","a\u0000","a;drop","é","a$","a\\b"));
        executionSchemas().values().forEach(columns->columns.forEach(c->inputs.add(c.name())));
        for(String input:inputs)if(input.matches("[a-z][a-z0-9_]*"))assertDoesNotThrow(()->column.invoke(null,input));else assertInstanceOf(IllegalArgumentException.class,assertThrows(java.lang.reflect.InvocationTargetException.class,()->column.invoke(null,input)).getCause());
        assertInstanceOf(NullPointerException.class,assertThrows(java.lang.reflect.InvocationTargetException.class,()->column.invoke(null,(Object)null)).getCause());
    }
    @Test void executionStableKeyRevalidationKeepsExactBinaryNumberNullAndTextRules(){
        var returned=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        // Deliberately mismatched database-return seam: SQL collation/value
        // selection is never accepted as a substitute for the stable-key check.
        var jdbc=new JdbcTemplate(){
            @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object...args){return List.of((T)returned.get());}
        };
        var mapper=new AgentRuntimeV2ExecutionMapper(jdbc);byte[] bytes={1,2,3};
        returned.set(row("id",1L,"wire_payload_hash",bytes,"event_id","Exact","lease_owner",null));
        assertSame(returned.get(),mapper.select("agent_outbox_event",row("id",Integer.valueOf(1),"wire_payload_hash",bytes.clone(),"lease_owner",null),false));
        assertThrows(SecurityException.class,()->mapper.select("agent_outbox_event",row("id",2L),false));
        assertThrows(SecurityException.class,()->mapper.select("agent_outbox_event",row("wire_payload_hash",new byte[]{1,2,4}),false));
        assertThrows(SecurityException.class,()->mapper.select("agent_outbox_event",row("event_id","exact"),false));
        assertThrows(SecurityException.class,()->mapper.select("agent_outbox_event",row("event_id",null),false));
        assertThrows(SecurityException.class,()->mapper.select("agent_outbox_event",row("lease_owner","owner"),false));
    }
    @Test void executionOrdinalExtractionUsesAllColumnsWithoutMetadataAndPreservesJdbcBlobNullTypes() throws Exception {
        var method=AgentRuntimeV2ExecutionMapper.class.getDeclaredMethod("rowMapper",String.class);method.setAccessible(true);
        for(var entry:executionSchemas().entrySet()){
            @SuppressWarnings("unchecked") var mapper=(org.springframework.jdbc.core.RowMapper<Map<String,Object>>)method.invoke(null,entry.getKey());
            var columns=entry.getValue();var expected=executionRow(columns,2,true);Object[] values=expected.values().toArray();
            for(int index=0;index<columns.size();index++)if(columns.get(index).type().equals("mediumblob"))values[index]=new javax.sql.rowset.serial.SerialBlob((byte[])values[index]);
            var visited=new ArrayList<Integer>();var resultSet=(java.sql.ResultSet)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(proxy,called,args)->{
                assertEquals("getObject",called.getName());assertInstanceOf(Integer.class,args[0]);int ordinal=(Integer)args[0];visited.add(ordinal);return values[ordinal-1];
            });
            exactExecutionPhysicalRow(columns,expected,mapper.mapRow(resultSet,0));assertEquals(java.util.stream.IntStream.rangeClosed(1,columns.size()).boxed().toList(),visited);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final SingleConnectionDataSource source;
        final JdbcTemplate jdbc;
        final AgentRuntimeV2EffectMapper store;
        final TransactionTemplate tx;
        Fixture() throws Exception {
            source=new SingleConnectionDataSource("jdbc:h2:mem:r4mapper"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","",true);
            jdbc=new JdbcTemplate(source);store=new AgentRuntimeV2EffectMapper(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            try(var input=getClass().getResourceAsStream("/db/agent-runtime-v2-effects-manual-20261003.sql")) {
                assertNotNull(input);
                // Exact authored CHECKs/columns/keys, with only the MySQL table
                // engine/collation suffix removed for the explicitly H2 test.
                String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^--[^\\r\\n]*(?:\\r?\\n|$)","")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
                int statements=0;for(String statement:sql.split(";"))if(!statement.isBlank()){assertTrue(statement.strip().startsWith("CREATE TABLE agent_runtime_v2_effect"));jdbc.execute(statement);statements++;}
                assertEquals(5,statements);
            }
        }
        public void close(){source.destroy();}
    }
    private static Map<String,Object> run(String dispatch,String token) {
        return row("runtime_dispatch_id",dispatch,"run_token",token,"execution_origin_snapshot_sha256",new byte[32],"dispatch_wire_sha256",new byte[32],"started_receipt_id",UUID.randomUUID().toString(),"started_receipt_sha256",new byte[32],"original_start_lease_fact_id",UUID.randomUUID().toString(),"disposition","ACTIVE","retired",0,"version",0L,"created_at",1000L,"updated_at",1000L);
    }
    private static Map<String,Object> effect(String dispatch,String token,String id) {
        return row("effect_id",id,"runtime_dispatch_id",dispatch,"run_token",token,"effect_kind","PROCESS_START","resource_wire_bytes",new byte[]{1},"resource_sha256",new byte[32],"execution_grant_id",UUID.randomUUID().toString(),"execution_grant_sha256",new byte[32],"holder_generation_fact_id",UUID.randomUUID().toString(),"current_lease_fact_id",UUID.randomUUID().toString(),"admission_request_id",null,"admitted_at",null,"valid_until",null,"disposition","DESCRIBED","commit_receipt_id",null,"result_wire_bytes",null,"result_sha256",null,"version",0L,"created_at",1000L,"updated_at",1000L);
    }
    private static Map<String,Object> request(String dispatch,String family) {
        boolean reconcile="RECONCILE".equals(family);
        return row("request_id",UUID.randomUUID().toString(),"runtime_dispatch_id",dispatch,"request_family",family,"effect_id","EFFECT".equals(family)?"eff_"+"c".repeat(64):null,"subject_kind",reconcile?"EXECUTION":null,"subject_id",reconcile?dispatch:null,"request_wire_bytes",new byte[]{1},"request_sha256",new byte[32],"response_wire_bytes",new byte[]{2},"response_sha256",new byte[32],"actor_credential_reference","current-key","actor_registration_fact_id",UUID.randomUUID().toString(),"actor_runtime_binding_id",UUID.randomUUID().toString(),"actor_runtime_instance_id","actual-process","actor_binding_generation",0L,"actor_session_fence","actual-fence","actor_socket_owner_instance_id","actual-server","actor_socket_owner_epoch",0L,"created_at",1000L);
    }
    private static void exactPhysicalRow(String table,Map<String,Object> expected,Map<String,Object> actual) {
        assertEquals(new HashSet<>(AgentRuntimeV2EffectMapper.DEFINITIONS.get(table).columns()),actual.keySet());assertEquals(expected.keySet(),actual.keySet());
        assertEquals(AgentRuntimeV2EffectMapper.DEFINITIONS.get(table).columns(),new ArrayList<>(actual.keySet()));
        expected.forEach((name,value)->{if(value!=null)assertEquals(value.getClass(),actual.get(name).getClass(),table+"."+name+" JDBC type");if(value instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])actual.get(name),table+"."+name);else assertEquals(value,actual.get(name),table+"."+name);});
    }
    @Test void fixedOrdinalMapperRetainsNullNumberAndBlobTypesWithoutColumnMetadata() throws Exception {
        var definition=new AgentRuntimeV2EffectMapper.Definition(List.of("identity"),List.of("identity","small_number","large_number","binary_value","blob_value","nullable"),List.of(),"identity");
        var method=AgentRuntimeV2EffectMapper.class.getDeclaredMethod("rowMapper",AgentRuntimeV2EffectMapper.Definition.class);method.setAccessible(true);
        @SuppressWarnings("unchecked") var mapper=(org.springframework.jdbc.core.RowMapper<Map<String,Object>>)method.invoke(null,definition);
        byte[] binary={1,2,3},blob={4,5,6};Object[] values={"stable",Short.valueOf((short)2),Long.valueOf(7),binary,new javax.sql.rowset.serial.SerialBlob(blob),null};
        var visited=new ArrayList<Integer>();
        var resultSet=(java.sql.ResultSet)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(proxy,called,args)->{
            assertEquals("getObject",called.getName(),"fixed extraction must not discover column metadata or names");
            assertInstanceOf(Integer.class,args[0]);int ordinal=(Integer)args[0];visited.add(ordinal);return values[ordinal-1];
        });
        var result=mapper.mapRow(resultSet,0);assertNotNull(result);assertEquals(definition.columns(),new ArrayList<>(result.keySet()));assertEquals(List.of(1,2,3,4,5,6),visited);
        assertEquals("stable",result.get("identity"));assertInstanceOf(Short.class,result.get("small_number"));assertInstanceOf(Long.class,result.get("large_number"));assertArrayEquals(binary,(byte[])result.get("binary_value"));assertArrayEquals(blob,(byte[])result.get("blob_value"));assertTrue(result.containsKey("nullable"));assertNull(result.get("nullable"));
    }
    @Test void fixedCompleteProjectionMatchesEveryPhysicalColumnAndFreshReadsInsideSameTransaction() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),id="eff_"+"d".repeat(64);
            var request=request(dispatch,"EFFECT");request.put("effect_id",id);
            var event=row("effect_id",id,"event_sequence",0L,"runtime_dispatch_id",dispatch,"run_token",token,"request_id",request.get("request_id"),"previous_sha256",null,"event_kind","REQUEST_SENT","evidence_wire_bytes",new byte[]{5,6},"evidence_sha256",new byte[32],"event_sha256",new byte[32],"created_at",1000L);
            var blob=row("effect_id",id,"chunk_offset",0L,"chunk_length",2L,"total_length",2L,"content_sha256",new byte[32],"chunk_sha256",new byte[32],"chunk_bytes",new byte[]{7,8},"created_at",1000L);
            f.tx.executeWithoutResult(status->{
                f.store.append(RUN,run(dispatch,token));f.store.append(EFFECT,effect(dispatch,token,id));f.store.append(REQUEST,request);f.store.append(EVENT,event);f.store.append(BLOB,blob);
                for(String table:List.of(RUN,EFFECT,REQUEST,EVENT,BLOB)) {
                    var physical=f.jdbc.queryForMap("SELECT * FROM "+table);var key=new LinkedHashMap<String,Object>();AgentRuntimeV2EffectMapper.DEFINITIONS.get(table).identity().forEach(name->key.put(name,physical.get(name)));
                    exactPhysicalRow(table,physical,f.store.hint(table,key));exactPhysicalRow(table,physical,f.store.lock(table,key));
                    if(List.of(EFFECT,REQUEST,EVENT).contains(table)){exactPhysicalRow(table,physical,f.store.history(table,dispatch,false).getFirst());exactPhysicalRow(table,physical,f.store.history(table,dispatch,true).getFirst());}
                    if(BLOB.equals(table)){exactPhysicalRow(table,physical,f.store.blobs(id,false).getFirst());exactPhysicalRow(table,physical,f.store.blobs(id,true).getFirst());exactPhysicalRow(table,physical,f.store.dispatchBlobs(dispatch,false).getFirst());exactPhysicalRow(table,physical,f.store.dispatchBlobs(dispatch,true).getFirst());}
                }
                // Actual changes after the prior reads cannot be hidden behind
                // a projection/result cache, even within this same transaction.
                assertEquals(1,f.jdbc.update("UPDATE "+RUN+" SET disposition='CANCELLING',retired=1 WHERE runtime_dispatch_id=?",dispatch));
                assertEquals("CANCELLING",f.store.lock(RUN,row("runtime_dispatch_id",dispatch)).get("disposition"));
                assertEquals(1,f.jdbc.update("UPDATE "+BLOB+" SET chunk_bytes=? WHERE effect_id=? AND chunk_offset=0",new byte[]{9,10},id));
                assertArrayEquals(new byte[]{9,10},(byte[])f.store.dispatchBlobs(dispatch,true).getFirst().get("chunk_bytes"));
            });
        }
    }
    @Test void runAndEffectCasRequireBoundWriteTransactionAndCannotChangeImmutableProvenance() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),id="eff_"+"a".repeat(64);
            var run=run(dispatch,token);var effect=effect(dispatch,token,id);
            assertThrows(IllegalStateException.class,()->f.store.append(RUN,run));assertThrows(IllegalStateException.class,()->f.store.lock(RUN,row("runtime_dispatch_id",dispatch)));
            f.tx.executeWithoutResult(status->{f.store.append(RUN,run);f.store.append(EFFECT,effect);});
            assertEquals(token,f.store.hint(RUN,row("runtime_dispatch_id",dispatch)).get("run_token"));
            assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.cas(RUN,run,row("run_token",UUID.randomUUID().toString()))));
            var shortRow=new LinkedHashMap<>(run);shortRow.remove("dispatch_wire_sha256");
            assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.append(RUN,shortRow)));
            f.tx.executeWithoutResult(status->{var before=f.store.lock(RUN,row("runtime_dispatch_id",dispatch));f.store.cas(RUN,before,row("retired",1,"disposition","CANCELLING","updated_at",1001L));});
            assertEquals(1L,((Number)f.store.hint(RUN,row("runtime_dispatch_id",dispatch)).get("version")).longValue());
            assertThrows(IllegalStateException.class,()->f.tx.executeWithoutResult(status->f.store.cas(RUN,run,row("retired",1))));
            assertEquals("CANCELLING",f.store.hint(RUN,row("runtime_dispatch_id",dispatch)).get("disposition"));
            var readonly=new TransactionTemplate(new DataSourceTransactionManager(f.source));readonly.setReadOnly(true);
            assertThrows(IllegalStateException.class,()->readonly.executeWithoutResult(status->f.store.lock(EFFECT,row("effect_id",id))));
            f.tx.executeWithoutResult(status->{var before=f.store.lock(EFFECT,row("effect_id",id));f.store.cas(EFFECT,before,row("admission_request_id",UUID.randomUUID().toString(),"admitted_at",1001L,"valid_until",2001L,"disposition","ADMITTED_IN_FLIGHT","updated_at",1001L));});
            assertEquals("ADMITTED_IN_FLIGHT",f.store.hint(EFFECT,row("effect_id",id)).get("disposition"));
            assertEquals(1,f.store.history(EFFECT,dispatch,false).size());assertTrue(f.store.history(EFFECT,UUID.randomUUID().toString(),false).isEmpty());
            assertThrows(IllegalArgumentException.class,()->f.store.hint("attacker_table",row("effect_id",id)));
        }
    }
    @Test void requestEventAndBlobBytesAreActuallyDurableImmutableAndRolledBackTogether() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),id="eff_"+"b".repeat(64),request=UUID.randomUUID().toString();
            var requestRow=row("request_id",request,"runtime_dispatch_id",dispatch,"request_family","EFFECT","effect_id",id,"subject_kind",null,"subject_id",null,"request_wire_bytes",new byte[]{1,2},"request_sha256",new byte[32],"response_wire_bytes",new byte[]{3,4},"response_sha256",new byte[32],"actor_credential_reference","credential-C","actor_registration_fact_id",UUID.randomUUID().toString(),"actor_runtime_binding_id",UUID.randomUUID().toString(),"actor_runtime_instance_id","instance-a","actor_binding_generation",0L,"actor_session_fence","fence-a","actor_socket_owner_instance_id","server-a","actor_socket_owner_epoch",1L,"created_at",1000L);
            var event=row("effect_id",id,"event_sequence",0L,"runtime_dispatch_id",dispatch,"run_token",token,"request_id",request,"previous_sha256",null,"event_kind","REQUEST_SENT","evidence_wire_bytes",new byte[]{5,6},"evidence_sha256",new byte[32],"event_sha256",new byte[32],"created_at",1000L);
            var blob=row("effect_id",id,"chunk_offset",0L,"chunk_length",2L,"total_length",2L,"content_sha256",new byte[32],"chunk_sha256",new byte[32],"chunk_bytes",new byte[]{7,8},"created_at",1000L);
            f.tx.executeWithoutResult(status->{f.store.append(REQUEST,requestRow);f.store.append(EVENT,event);f.store.append(BLOB,blob);});
            assertArrayEquals(new byte[]{1,2},(byte[])f.store.hint(REQUEST,row("request_id",request)).get("request_wire_bytes"));
            f.tx.executeWithoutResult(status->assertArrayEquals(new byte[]{5,6},(byte[])f.store.history(EVENT,dispatch,true).getFirst().get("evidence_wire_bytes")));
            assertArrayEquals(new byte[]{7,8},(byte[])f.store.blobs(id,false).getFirst().get("chunk_bytes"));
            for(String table:List.of(REQUEST,EVENT,BLOB))assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.cas(table,Map.of(),row("created_at",2000L))));
            assertThrows(RuntimeException.class,()->f.tx.executeWithoutResult(status->{f.store.append(EVENT,new LinkedHashMap<>(event));}));
            var rolledBack=new LinkedHashMap<>(event);rolledBack.put("event_sequence",1L);rolledBack.put("previous_sha256",new byte[32]);
            assertThrows(IllegalStateException.class,()->f.tx.executeWithoutResult(status->{f.store.append(EVENT,rolledBack);throw new IllegalStateException("isolated rollback");}));
            assertEquals(1,f.store.history(EVENT,dispatch,false).size());assertEquals(1,f.store.history(REQUEST,dispatch,false).size());
            assertThrows(IllegalStateException.class,()->f.store.history(EVENT,dispatch,true));
        }
    }
    @Test void auditOnlyRequestKeepsActualEpochZeroRouteImmutableAndCreatesNoRunEffectEventOrBlob() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString();var original=request(dispatch,"RECONCILE");
            for(String column:List.of("actor_registration_fact_id","actor_runtime_binding_id","actor_runtime_instance_id","actor_binding_generation"))original.put(column,null);
            f.tx.executeWithoutResult(status->f.store.append(REQUEST,original));
            var saved=f.store.hint(REQUEST,row("request_id",original.get("request_id")));
            assertEquals("actual-fence",saved.get("actor_session_fence"));assertEquals(0L,((Number)saved.get("actor_socket_owner_epoch")).longValue());assertNull(saved.get("actor_runtime_binding_id"));
            assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.cas(REQUEST,saved,row("actor_session_fence","replacement-fence"))));
            assertThrows(RuntimeException.class,()->f.tx.executeWithoutResult(status->{var duplicate=new LinkedHashMap<>(original);duplicate.put("actor_session_fence","replacement-fence");f.store.append(REQUEST,duplicate);}));
            assertEquals("actual-fence",f.store.hint(REQUEST,row("request_id",original.get("request_id"))).get("actor_session_fence"));
            for(String table:List.of(RUN,EFFECT,EVENT,BLOB))assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),"descriptive actor row creates no "+table);
        }
    }
    @Test void completeDispatchBlobReadKeepsNonStageRowsExactScopeAndRejectsTotal4097WithoutTruncation() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString(),foreign=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
            String first="eff_"+"1".repeat(64),second="eff_"+"2".repeat(64),other="eff_"+"3".repeat(64);
            assertThrows(IllegalStateException.class,()->f.store.dispatchBlobs(dispatch,true));
            f.tx.executeWithoutResult(status->{
                f.store.append(EFFECT,effect(dispatch,token,first));f.store.append(EFFECT,effect(dispatch,token,second));f.store.append(EFFECT,effect(foreign,token,other));
                for(String id:List.of(first,second,other))for(long offset=0;offset<(id.equals(other)?1:2048);offset++)f.store.append(BLOB,row("effect_id",id,"chunk_offset",offset,"chunk_length",1L,"total_length",4097L,"content_sha256",new byte[32],"chunk_sha256",new byte[32],"chunk_bytes",new byte[]{7},"created_at",1000L));
            });
            var rows=f.tx.execute(status->f.store.dispatchBlobs(dispatch,true));assertNotNull(rows);assertEquals(4096,rows.size());
            assertEquals(Set.of(first,second),rows.stream().map(value->value.get("effect_id")).collect(java.util.stream.Collectors.toSet()),"all associated non-stage blobs remain visible to graph rejection");
            assertEquals(first,rows.getFirst().get("effect_id"));assertEquals(second,rows.getLast().get("effect_id"));assertEquals(1,f.store.dispatchBlobs(foreign,false).size());assertTrue(f.store.dispatchBlobs(UUID.randomUUID().toString(),false).isEmpty());
            f.tx.executeWithoutResult(status->f.store.append(BLOB,row("effect_id",first,"chunk_offset",2048L,"chunk_length",1L,"total_length",4097L,"content_sha256",new byte[32],"chunk_sha256",new byte[32],"chunk_bytes",new byte[]{7},"created_at",1000L)));
            assertThrows(SecurityException.class,()->f.store.dispatchBlobs(dispatch,false));assertThrows(SecurityException.class,()->f.tx.execute(status->f.store.dispatchBlobs(dispatch,true)));assertEquals(4097,f.store.blobs(first,false).size()+f.store.blobs(second,false).size());
        }
    }
    @Test void mapperAlsoRejectsAllPartialActorMasksAndAbsentControlEffectBeforeAnyInsert() throws Exception {
        try(var f=new Fixture()) {
            String dispatch=UUID.randomUUID().toString();int accepted=0;
            var columns=List.of("actor_registration_fact_id","actor_runtime_binding_id","actor_runtime_instance_id","actor_binding_generation");
            for(String family:List.of("CONTROL","EFFECT","RECONCILE"))for(int mask=0;mask<16;mask++) {
                var candidate=request(dispatch,family);for(int bit=0;bit<4;bit++)if((mask&(1<<bit))==0)candidate.put(columns.get(bit),null);
                if(mask==15||family.equals("RECONCILE")&&mask==0){f.tx.executeWithoutResult(status->f.store.append(REQUEST,candidate));accepted++;}
                else assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.append(REQUEST,candidate)),family+" actor mask="+mask);
            }
            assertEquals(4,accepted);assertEquals(accepted,f.jdbc.queryForObject("SELECT COUNT(*) FROM "+REQUEST,Integer.class));
            for(String column:List.of("actor_credential_reference","actor_session_fence","actor_socket_owner_instance_id","actor_socket_owner_epoch")){
                var invalid=request(dispatch,"RECONCILE");invalid.put(column,null);assertThrows(IllegalArgumentException.class,()->f.tx.executeWithoutResult(status->f.store.append(REQUEST,invalid)));
            }
            for(String column:List.of("actor_binding_generation","actor_socket_owner_epoch")){
                var invalid=request(dispatch,"RECONCILE");invalid.put(column,-1L);assertThrows(SecurityException.class,()->f.tx.executeWithoutResult(status->f.store.append(REQUEST,invalid)),"shared persisted-number validation rejects negative "+column);
            }
            assertEquals(accepted,f.jdbc.queryForObject("SELECT COUNT(*) FROM "+REQUEST,Integer.class));
        }
    }
}
