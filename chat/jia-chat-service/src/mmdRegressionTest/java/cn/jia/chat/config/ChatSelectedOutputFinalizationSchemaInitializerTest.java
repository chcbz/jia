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

    @Test
    void submissionDefinitionsAreAcceptedAndHistoricalMysqlSnapshotIsUpgradeOnly() throws Exception {
        invokeChecks(submissionCheckRows());
    }

    @Test
    void actualOldSameNamedEnforcedMysqlChecksFailBeforeActivation() throws Exception {
        var failure=assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(0,null,null)));
        assertTrue(failure.getMessage().contains("check definition drift"));
    }

    @Test
    void requiredGuardTextInsideAlwaysTrueExpressionCannotHideDefinitionDrift() throws Exception {
        var rows=actualCheckRows(1,"chk_csof_progress",clause(1,"chk_csof_progress")+" OR 1=1");
        assertThrows(IllegalStateException.class,()->invokeChecks(rows));
    }

    @Test
    void nullCheckDefinitionNeverCountsAsEnforcedRequiredInvariant() throws Exception {
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_csof_progress",null)));
    }

    @Test
    void checkLiteralCaseAndWhitespaceAreNotErasedByMetadataNormalization() throws Exception {
        String original=clause(1,"chk_csof_progress");
        String changed=original.replace("SUBMITTED","submitted");
        assertNotEquals(original,changed);
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_csof_progress",changed)));
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_csof_progress",original.replace("SUBMITTED","SUB MITTED"))));
    }

    @Test
    void unenforcedCorrectDefinitionStillFailsClosed() throws Exception {
        var rows=actualCheckRows(1,null,null);rows.getFirst().put("ENFORCED","NO");
        assertThrows(IllegalStateException.class,()->invokeChecks(rows));
    }

    @Test void textSourceSqlUsesExistingItemTableAndExplicitExclusiveRealMessageReferences() throws Exception {
        String ddl=String.join("\n",ChatSelectedOutputFinalizationSchemaInitializer.ddl());
        for(String condition:List.of("source_kind='OUTPUT' AND step_id IS NOT NULL AND output_id IS NOT NULL",
                "source_kind='COMPLETED_MESSAGE' AND step_id IS NULL AND output_id IS NULL",
                "turn_id IS NOT NULL AND message_id IS NOT NULL AND snapshot_id IS NOT NULL AND final_digest IS NOT NULL",
                "uk_csofi_message","9223372036854775807"))assertTrue(ddl.contains(condition),condition);
        String upgrade=new org.springframework.core.io.ClassPathResource("db/chat-selected-output-finalization-completed-message-v1.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(upgrade.contains("ALTER TABLE chat_selected_output_finalization_item"));assertFalse(upgrade.contains("CREATE TABLE"));
        assertFalse(upgrade.contains("UPDATE "));assertFalse(upgrade.contains("DROP "));
    }

    @Test void knownPredecessorIsUpgradedButTargetIsNoOpAndUnknownDriftFailsBeforeDdl() throws Exception {
        var old=mock(JdbcTemplate.class);
        when(old.queryForList(anyString())).thenReturn(actualCheckRows(1,null,null));
        ChatSelectedOutputFinalizationSchemaInitializer.upgradeSubmissionChecks(old);
        verify(old).execute(org.mockito.ArgumentMatchers.contains("ALTER TABLE chat_selected_output_finalization"));
        var current=mock(JdbcTemplate.class);
        when(current.queryForList(anyString())).thenReturn(submissionCheckRows());
        ChatSelectedOutputFinalizationSchemaInitializer.upgradeSubmissionChecks(current);
        verify(current,never()).execute(anyString());
        for(var rows:List.of(actualCheckRows(0,null,null),actualCheckRows(1,"chk_csof_terminal","1=1"),List.<Map<String,Object>>of())) {
            var drift=mock(JdbcTemplate.class);when(drift.queryForList(anyString())).thenReturn(rows);
            assertThrows(IllegalStateException.class,()->ChatSelectedOutputFinalizationSchemaInitializer.upgradeSubmissionChecks(drift));
            verify(drift,never()).execute(anyString());
        }
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
            if(sql.contains("information_schema.check_constraints")) {
                String ddl=ChatSelectedOutputFinalizationSchemaInitializer.ddl().get(item?1:0);
                if(item && !source)try {ddl=new org.springframework.core.io.ClassPathResource("db/chat-selected-output-finalization-completed-message-v1.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new AssertionError(e);}
                var clauses=ChatTaskDeliveryReviewSchemaInitializer.checks(ddl);List<Map<String,Object>> result=new ArrayList<>();
                String name=item?"chk_csofi_source":"chk_csof_contract";
                if(item || contract || "orphan-contract".equals(drift))result.add(Map.of("constraint_name",name,"check_clause",
                        "check".equals(drift)&&item?"1=1":extractCheck(ddl,name),"enforced","unenforced".equals(drift)&&item?"NO":"YES"));
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
        static String extractCheck(String ddl,String name) {
            // Return an actual SQL expression, never the normalized Boolean-tree representation.
            String marker="CONSTRAINT "+name+" CHECK ";int start=ddl.indexOf(marker)+marker.length();
            int depth=0;boolean quote=false;
            for(int i=start;i<ddl.length();i++) {char c=ddl.charAt(i);if(c=='\'')quote=!quote;if(quote)continue;
                if(c=='(')depth++;else if(c==')' && --depth==0)return ddl.substring(start,i+1);}
            throw new AssertionError(name);
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
        for(String drift:List.of("partial","orphan-contract","orphan-index","check","unenforced","contract-type","source-type","source-collation","index-order","index-unique","index-prefix")) {
            var jdbc=new V2Jdbc();jdbc.drift=drift;
            jdbc.contract="contract-type".equals(drift);
            jdbc.source=List.of("source-type","source-collation","index-order","index-unique","index-prefix").contains(drift);
            assertThrows(IllegalStateException.class,()->ChatSelectedOutputFinalizationSchemaInitializer.upgradeV2(jdbc),drift);
            assertTrue(jdbc.writes.isEmpty(),drift);
        }
    }

    // Target canonical expression derived from predecessor, not claimed as a new MySQL capture.
    private static List<Map<String,Object>> submissionCheckRows() throws Exception {
        var rows=actualCheckRows(1,null,null);
        for(var row:rows) {
            String name=(String)row.get("CONSTRAINT_NAME");String value=(String)row.get("CHECK_CLAUSE");
            if(value!=null)value=value.replace("\\'", "'");
            if("chk_csof_progress".equals(name))value=value.replace("_utf8mb4'submitted',_utf8mb4'changes_requested'",
                    "_utf8mb4'submitted',_utf8mb4'accepted',_utf8mb4'changes_requested'");
            if("chk_csof_terminal".equals(name)) value="(((`state` = _utf8mb4'completed') and (`stage` = _utf8mb4'SUBMITTED') and (`delivery_id` is not null) and (`delivery_state` is not null) and (`delivery_state` in (_utf8mb4'submitted',_utf8mb4'accepted',_utf8mb4'changes_requested')) and (`error_code` is null) and (`retryable` = 0)) or "+value.substring(1);
            row.put("CHECK_CLAUSE",value);
        }
        return rows;
    }

    private static String clause(int snapshot,String check) throws Exception {
        try(var in=new org.springframework.core.io.ClassPathResource("mmd-finalization-schema-checks-mysql8021.json").getInputStream()){
            return new tools.jackson.databind.ObjectMapper().readTree(in).get("snapshots").get(snapshot)
                    .get("check_clauses").get(check).textValue();
        }
    }
    private static List<Map<String,Object>> actualCheckRows(int snapshot,String overrideCheck,String overrideValue) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String name:List.of("chk_csof_versions", "chk_csof_key", "chk_csof_delivery_state", "chk_csof_state", "chk_csof_stage", "chk_csof_progress", "chk_csof_outcome", "chk_csof_terminal")){
            Map<String,Object> row=new LinkedHashMap<>();row.put("CONSTRAINT_NAME",name);row.put("ENFORCED","YES");
            String value=name.equals("chk_csof_progress")||name.equals("chk_csof_terminal")?clause(snapshot,name):null;
            row.put("CHECK_CLAUSE",name.equals(overrideCheck)?overrideValue:value);rows.add(row);
        }return rows;
    }
    private static void invokeChecks(List<Map<String,Object>> rows) throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(rows);
        var method=ChatSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("validateChecks",JdbcTemplate.class,String.class);
        method.setAccessible(true);
        try{method.invoke(null,jdbc,"chat_selected_output_finalization");}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw failure;
        }
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
