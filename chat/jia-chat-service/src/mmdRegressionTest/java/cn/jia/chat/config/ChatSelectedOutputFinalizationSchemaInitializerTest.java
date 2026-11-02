package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class ChatSelectedOutputFinalizationSchemaInitializerTest {
    @Test void schemaIsAdditiveBinaryScopedWithoutDatabaseChecks() {
        var ddl=ChatSelectedOutputFinalizationSchemaInitializer.ddl();assertEquals(2,ddl.size());
        String sql=String.join("\n",ddl).toLowerCase();
        assertTrue(sql.contains("utf8mb4_0900_bin"));
        assertTrue(sql.contains("uk_csof_request"));
        assertFalse(sql.contains("check ("));
        assertFalse(sql.contains("alter table"));
    }

    @Test
    void initializerRequiresExplicitCoreThenDeliberationReadinessBoundary() throws Exception {
        assertNotNull(ChatSelectedOutputFinalizationSchemaInitializer.class.getConstructor(
                JdbcTemplate.class,ChatSchemaReadiness.class));
    }

    @Test
    void bothMysqlTablesAcceptNullSubPartButValidateAllFullColumnIndexes() throws Exception {
        for(String table:List.of("chat_selected_output_finalization","chat_selected_output_finalization_item")){
            var jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(table,null,false));
            invokeIndexes(jdbc,table);
        }
    }

    @Test
    void actualPrefixIndexOnEitherTableStillFailsClosed() {
        for(String table:List.of("chat_selected_output_finalization","chat_selected_output_finalization_item")){
            var jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(table,16,false));
            var failure=assertThrows(IllegalStateException.class,()->invokeIndexes(jdbc,table));
            assertTrue(failure.getMessage().contains("prefix index drift"));
        }
    }

    @Test
    void fullColumnOrderDriftStillFailsClosed() {
        String table="chat_selected_output_finalization_item";var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(table,null,true));
        var failure=assertThrows(IllegalStateException.class,()->invokeIndexes(jdbc,table));
        assertTrue(failure.getMessage().contains("index drift"));
    }

    @Test
    void nullableMetadataHelperReturnsNullButPreservesActualPrefixValue() throws Exception {
        var method=ChatSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("value",Map.class,String.class);
        method.setAccessible(true);
        Map<String,Object> row=new LinkedHashMap<>();row.put("SUB_PART",null);
        assertNull(method.invoke(null,row,"sub_part"));assertNull(method.invoke(null,row,"absent"));
        row.put("SUB_PART",16);assertEquals(16,method.invoke(null,row,"sub_part"));
    }

    @Test void textSourceSqlUsesExistingItemTableAndExplicitExclusiveRealMessageReferences() throws Exception {
        String ddl=String.join("\n",ChatSelectedOutputFinalizationSchemaInitializer.ddl());
        for(String column:List.of("source_kind", "step_id", "output_id", "turn_id",
                "message_id", "snapshot_id", "final_digest", "uk_csofi_message"))
            assertTrue(ddl.contains(column),column);
        assertFalse(ddl.toUpperCase().contains("CHECK ("));
        String upgrade=new org.springframework.core.io.ClassPathResource("db/chat-selected-output-finalization-completed-message-v1.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(upgrade.contains("ALTER TABLE chat_selected_output_finalization_item"));assertFalse(upgrade.contains("CREATE TABLE"));
        assertFalse(upgrade.contains("UPDATE "));assertFalse(upgrade.contains("DROP "));
    }

    static final class V2Jdbc extends JdbcTemplate {
        boolean contract,source;String drift;final List<String> writes=new ArrayList<>();
        @Override public int update(String sql,Object...args){throw new AssertionError("No data rewriting");}
        @Override public void execute(String sql){
            writes.add(sql);
            if(sql.startsWith("ALTER TABLE chat_selected_output_finalization_item\n"))source=true;
            else if(sql.startsWith("ALTER TABLE chat_selected_output_finalization\n"))contract=true;
            else throw new AssertionError(sql);
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
            boolean item=args.length>0 && args[0].equals("chat_selected_output_finalization_item");
            if(sql.contains("information_schema.columns")) {
                List<Map<String,Object>> result=new ArrayList<>();
                if(!item) {if(contract)result.add(column("contract_version","contract-type".equals(drift)?"int":"smallint","NO",null));return result;}
                result.add(column("request_id","varchar(100)",source?"YES":"NO","utf8mb4_0900_bin"));
                if(source || "partial".equals(drift))result.add(column("source_delivery_id","varchar(100)","YES","source-collation".equals(drift)?"utf8mb4_general_ci":"utf8mb4_0900_bin"));
                if(source) {result.add(column("source_artifact_id","varchar(100)","YES","utf8mb4_0900_bin"));
                    result.add(column("source_artifact_version","source-type".equals(drift)?"bigint":"int","YES",null));}
                return result;
            }
            if(sql.contains("information_schema.statistics")) {
                if(!source && !"orphan-index".equals(drift))return List.of();
                List<Map<String,Object>> result=new ArrayList<>();
                for(String key:List.of("tenant_id","owner_jiacn","client_id","operation_id","source_delivery_id","source_artifact_id","source_artifact_version")) {
                    var row=new LinkedHashMap<String,Object>();row.put("column_name","index-order".equals(drift)&&key.equals("tenant_id")?"client_id":key);
                    row.put("non_unique","index-unique".equals(drift)?1:0);row.put("sub_part","index-prefix".equals(drift)?16:null);result.add(row);
                }
                return result;
            }
            throw new AssertionError(sql);
        }
        static Map<String,Object> column(String name,String type,String nullable,String collation) {
            var row=new LinkedHashMap<String,Object>();row.put("column_name",name);row.put("column_type",type);row.put("is_nullable",nullable);row.put("collation_name",collation);return row;
        }

    }
    @Test void v2FreshKnownUpgradeRepeatAndRestartAreDeterministicWithoutRewritingOldDigests() {
        for(boolean contract:List.of(false,true))for(boolean source:List.of(false,true)) {
            var jdbc=new V2Jdbc();jdbc.contract=contract;jdbc.source=source;
            ChatSelectedOutputFinalizationSchemaInitializer.upgradeV2(jdbc);
            ChatSelectedOutputFinalizationSchemaInitializer.validateV2(jdbc);
            assertEquals((contract?0:1)+(source?0:1),jdbc.writes.size());
            int writes=jdbc.writes.size();ChatSelectedOutputFinalizationSchemaInitializer.upgradeV2(jdbc);
            ChatSelectedOutputFinalizationSchemaInitializer.validateV2(jdbc);assertEquals(writes,jdbc.writes.size());
            for(String sql:jdbc.writes)assertFalse(sql.contains("UPDATE "));
        }
    }
    @Test void v2PreflightsBothTablesAndRejectsPartialUnknownOrWeakenedShapesBeforeEitherAlter() {
        for(String drift:List.of("partial","orphan-index","contract-type","source-type","source-collation","index-order","index-unique","index-prefix")) {
            var jdbc=new V2Jdbc();jdbc.drift=drift;
            jdbc.contract="contract-type".equals(drift);
            jdbc.source=List.of("source-type","source-collation","index-order","index-unique","index-prefix").contains(drift);
            assertThrows(IllegalStateException.class,()->ChatSelectedOutputFinalizationSchemaInitializer.upgradeV2(jdbc),drift);
            assertTrue(jdbc.writes.isEmpty(),drift);
        }
    }

    // Target canonical expression derived from predecessor, not claimed as a new MySQL capture.

    private static void invokeIndexes(JdbcTemplate jdbc,String table) throws Exception {
        var method=ChatSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("validateIndexes",JdbcTemplate.class,String.class);
        method.setAccessible(true);
        try{method.invoke(null,jdbc,table);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw failure;
        }
    }

    private static List<Map<String,Object>> indexRows(String table,Integer prefix,boolean drift) {
        Map<String,List<String>> indexes=new LinkedHashMap<>();
        if(table.equals("chat_selected_output_finalization")){
            indexes.put("PRIMARY",List.of("tenant_id","owner_jiacn","client_id","operation_id"));
            indexes.put("uk_csof_request",List.of("tenant_id","owner_jiacn","client_id","task_id","idempotency_key"));
            indexes.put("idx_csof_task",List.of("tenant_id","owner_jiacn","client_id","task_id","updated_at"));
            indexes.put("idx_csof_delivery",List.of("tenant_id","owner_jiacn","client_id","task_id","delivery_id"));
        }else{
            indexes.put("PRIMARY",List.of("tenant_id","owner_jiacn","client_id","operation_id","item_order"));
            indexes.put("uk_csofi_source",List.of("tenant_id","owner_jiacn","client_id","operation_id","request_id","step_id","output_id"));
            indexes.put("uk_csofi_message",List.of("tenant_id","owner_jiacn","client_id","operation_id","request_id","turn_id","message_id"));
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        indexes.forEach((name,columns)->{
            for(int i=0;i<columns.size();i++){
                Map<String,Object> row=new LinkedHashMap<>();row.put("INDEX_NAME",name);
                row.put("NON_UNIQUE",name.startsWith("idx_")?1:0);row.put("SEQ_IN_INDEX",i+1);
                row.put("COLUMN_NAME",drift&&name.equals("PRIMARY")&&i==0?"foreign_column":columns.get(i));
                row.put("SUB_PART",prefix);rows.add(row);
            }
        });return rows;
    }
}
