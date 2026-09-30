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

class ChatSelectedOutputFinalizationSchemaInitializerTest {
    @Test
    void schemaIsAdditiveBinaryScopedAndTerminalInvariantIsDatabaseChecked(){
        var ddl=ChatSelectedOutputFinalizationSchemaInitializer.ddl();assertEquals(2,ddl.size());
        String sql=String.join("\n",ddl).toLowerCase();assertTrue(sql.contains("utf8mb4_0900_bin"));
        assertTrue(sql.contains("uk_csof_request"));assertTrue(sql.contains("state_version>0"));
        assertTrue(sql.contains("between 8 and 160"));assertTrue(sql.contains("9007199254740991"));
        assertTrue(sql.contains("chk_csof_progress"));assertTrue(sql.contains("chk_csof_outcome"));
        assertTrue(sql.contains("delivery_state='accepted'"));assertTrue(sql.contains("task_state='completed'"));
        assertTrue(sql.contains("changes_requested"));assertFalse(sql.contains("alter table"));
    }

    @Test
    void submittedAcceptingAndCompletedRejectUnknownNullDeliveryState(){
        String sql=String.join(" ",ChatSelectedOutputFinalizationSchemaInitializer.ddl()).toLowerCase()
                .replaceAll("\\s+"," ");
        for(String stage:List.of("submitted","accepting","task_completed"))
            assertTrue(sql.contains("stage='"+stage+"' and delivery_id is not null and delivery_state is not null"));
        assertTrue(sql.contains("and delivery_state is not null and delivery_state='accepted' and task_state='completed'"));
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
        }else{
            indexes.put("PRIMARY",List.of("tenant_id","owner_jiacn","client_id","operation_id","item_order"));
            indexes.put("uk_csofi_source",List.of("tenant_id","owner_jiacn","client_id","operation_id","request_id","step_id","output_id"));
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
