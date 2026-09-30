package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
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

class AgentSelectedOutputFinalizationSchemaInitializerTest {
    @Test
    void ddlIsAdditiveBinaryScopedAndKeepsLeaseAndSourceAuthorityPrivate(){
        String ddl=AgentSelectedOutputFinalizationSchemaInitializer.ddl();String n=ddl.toLowerCase();
        assertTrue(n.contains("create table if not exists agent_selected_output_finalization"));
        assertTrue(n.contains("utf8mb4_0900_bin"));assertTrue(n.contains("source_facts_digest"));
        assertTrue(n.contains("conversation_generation"));assertTrue(n.contains("lease_token"));
        assertTrue(n.contains("chk_asof_lease"));assertTrue(n.contains("9007199254740991"));
        assertFalse(n.contains("alter table"));assertFalse(n.contains("provider"));
    }

    @Test
    void leasedAndReadyAuthorityCannotPassMysqlCheckWithNullLeaseVersion(){
        String sql=AgentSelectedOutputFinalizationSchemaInitializer.ddl().toLowerCase()
                .replaceAll("\\s+"," ");
        assertTrue(sql.contains("phase in ('leased','ready') and lease_token is not null"
                +" and lease_work_item_version is not null and lease_work_item_version>=0"));
    }

    @Test
    void initializingBeanRunsOnlyAfterExistingFormalDeliveryInitializingBean() throws Exception {
        var method=AgentSelectedOutputFinalizationConfiguration.class.getDeclaredMethod(
                "agentSelectedOutputFinalizationSchemaInitializer",JdbcTemplate.class);
        DependsOn order=method.getAnnotation(DependsOn.class);
        assertNotNull(order);assertArrayEquals(new String[]{"agentTaskFormalDeliverySchemaInitializer"},order.value());
    }

    @Test
    void nullableMysqlIndexMetadataUsesFullColumnsAndCaseInsensitiveLabels() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(null,false));
        invokeIndexes(jdbc);
    }

    @Test
    void actualPrefixIndexStillFailsClosedInsteadOfTreatingItAsMissing() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(16,false));
        var failure=assertThrows(IllegalStateException.class,()->invokeIndexes(jdbc));
        assertTrue(failure.getMessage().contains("prefix index drift"));
    }

    @Test
    void fullColumnOrderDriftStillFailsClosed() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(indexRows(null,true));
        var failure=assertThrows(IllegalStateException.class,()->invokeIndexes(jdbc));
        assertTrue(failure.getMessage().contains("index drift"));
    }

    @Test
    void nullableMetadataHelperReturnsNullButPreservesNonNullPrefixLength() throws Exception {
        var method=AgentSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("value",Map.class,String.class);
        method.setAccessible(true);
        Map<String,Object> row=new LinkedHashMap<>();row.put("SUB_PART",null);
        assertNull(method.invoke(null,row,"sub_part"));assertNull(method.invoke(null,row,"absent"));
        row.put("SUB_PART",16);assertEquals(16,method.invoke(null,row,"sub_part"));
    }

    @Test
    void actualRepairedMysqlCheckDefinitionsAreAcceptedDespiteCaseInsensitiveMetadataLabels() throws Exception {
        invokeChecks(actualCheckRows(1,null,null));
    }

    @Test
    void actualOldSameNamedEnforcedMysqlChecksFailBeforeActivation() throws Exception {
        var failure=assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(0,null,null)));
        assertTrue(failure.getMessage().contains("check definition drift"));
    }

    @Test
    void requiredGuardTextInsideAlwaysTrueExpressionCannotHideDefinitionDrift() throws Exception {
        var rows=actualCheckRows(1,"chk_asof_lease",clause(1,"chk_asof_lease")+" OR 1=1");
        assertThrows(IllegalStateException.class,()->invokeChecks(rows));
    }

    @Test
    void nullCheckDefinitionNeverCountsAsEnforcedRequiredInvariant() throws Exception {
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_asof_lease",null)));
    }

    @Test
    void checkLiteralCaseAndWhitespaceAreNotErasedByMetadataNormalization() throws Exception {
        String original=clause(1,"chk_asof_lease");
        String changed=original.replace("LEASED","leased");
        assertNotEquals(original,changed);
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_asof_lease",changed)));
        assertThrows(IllegalStateException.class,()->invokeChecks(actualCheckRows(1,"chk_asof_lease",original.replace("LEASED","LE ASED"))));
    }

    @Test
    void unenforcedCorrectDefinitionStillFailsClosed() throws Exception {
        var rows=actualCheckRows(1,null,null);rows.getFirst().put("ENFORCED","NO");
        assertThrows(IllegalStateException.class,()->invokeChecks(rows));
    }

    private static String clause(int snapshot,String check) throws Exception {
        try(var in=new org.springframework.core.io.ClassPathResource("mmd-finalization-schema-checks-mysql8021.json").getInputStream()){
            return new tools.jackson.databind.ObjectMapper().readTree(in).get("snapshots").get(snapshot)
                    .get("check_clauses").get(check).textValue();
        }
    }
    private static List<Map<String,Object>> actualCheckRows(int snapshot,String overrideCheck,String overrideValue) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String name:List.of("chk_asof_versions", "chk_asof_digest", "chk_asof_phase", "chk_asof_lease")){
            Map<String,Object> row=new LinkedHashMap<>();row.put("CONSTRAINT_NAME",name);row.put("ENFORCED","YES");
            String value=name.equals("chk_asof_lease")?clause(snapshot,name):null;
            row.put("CHECK_CLAUSE",name.equals(overrideCheck)?overrideValue:value);rows.add(row);
        }return rows;
    }
    private static void invokeChecks(List<Map<String,Object>> rows) throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(rows);
        var method=AgentSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("validateChecks",JdbcTemplate.class);
        method.setAccessible(true);
        try{method.invoke(null,jdbc);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw failure;
        }
    }

    private static void invokeIndexes(JdbcTemplate jdbc) throws Exception {
        var method=AgentSelectedOutputFinalizationSchemaInitializer.class.getDeclaredMethod("validateIndexes",JdbcTemplate.class);
        method.setAccessible(true);
        try{method.invoke(null,jdbc);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw failure;
        }
    }

    private static List<Map<String,Object>> indexRows(Integer prefix,boolean drift) {
        Map<String,List<String>> indexes=new LinkedHashMap<>();
        indexes.put("PRIMARY",List.of("tenant_id","client_id","owner_jiacn","operation_id"));
        indexes.put("uk_asof_delivery",List.of("tenant_id","client_id","owner_jiacn","delivery_id"));
        indexes.put("idx_asof_task",List.of("tenant_id","client_id","owner_jiacn","task_id","phase"));
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
