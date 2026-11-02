package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskProviderCostConsentSchemaInitializerTest {
    @Test void ddlIsSingleAdditiveBinaryScopedLifecycleTable() {
        String ddl=AgentTaskProviderCostConsentSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("(?m)^\\s*--.*$"," ").replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("create table if not exists agent_task_provider_cost_consent "));
        assertFalse(normalized.contains(";"));
        assertFalse(normalized.contains("check ("));
        for(String forbidden:new String[]{" alter table "," drop "," insert "," update "," delete "," replace "})
            assertFalse((" "+normalized+" ").contains(forbidden),forbidden);
        for(String required:new String[]{"engine=innodb","utf8mb4_0900_bin","uk_atpcc_scope_consent",
                "uk_atpcc_scope_key"})
            assertTrue(normalized.contains(required),required);
    }

    @Test void catalogAcceptsOnlyExactPristineV1OrCompleteFollowupV3RestartShape() {
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(false), indexRows(false)));
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(true), indexRows(true)));

        List<Map<String,Object>> partial=columnRows(true);
        partial.removeLast();
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        partial,indexRows(true)));

        List<Map<String,Object>> extraColumn=columnRows(true);
        extraColumn.add(column("unexpected_column","bigint","YES",null,null));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        extraColumn,indexRows(true)));

        List<Map<String,Object>> partialIndex=indexRows(true);
        partialIndex.removeLast();
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),partialIndex));

        List<Map<String,Object>> extraIndex=indexRows(true);
        extraIndex.add(index("unexpected_index",1,1,"task_id"));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),extraIndex));
    }

    @Test void completeV3CatalogRejectsPurposeDefaultDrift() {
        List<Map<String,Object>> rows=columnRows(true);
        rows.stream().filter(row -> "consent_purpose".equals(row.get("column_name")))
                .findFirst().orElseThrow().put("column_default","FOLLOWUP_EXECUTE");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        rows,indexRows(true)));
    }

    private static List<Map<String,Object>> columnRows(boolean extended) {
        var definitions=extended
                ? AgentTaskProviderCostConsentSchemaInitializer.v3ColumnDefinitions()
                : AgentTaskProviderCostConsentSchemaInitializer.v1ColumnDefinitions();
        List<Map<String,Object>> rows=new ArrayList<>();
        definitions.forEach((name,value)->rows.add(column(name,value.type(),value.nullable(),
                value.collation(),value.defaultValue())));
        return rows;
    }

    private static List<Map<String,Object>> indexRows(boolean extended) {
        var definitions=extended
                ? AgentTaskProviderCostConsentSchemaInitializer.v3IndexDefinitions()
                : AgentTaskProviderCostConsentSchemaInitializer.v1IndexDefinitions();
        List<Map<String,Object>> rows=new ArrayList<>();
        definitions.forEach((name,value)->{
            for(int ordinal=0;ordinal<value.columns().size();ordinal++) {
                rows.add(index(name,value.nonUnique(),ordinal+1,value.columns().get(ordinal)));
            }
        });
        return rows;
    }

    private static Map<String,Object> column(String name,String type,String nullable,
            String collation,String defaultValue) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("column_name",name);
        row.put("column_type",type);row.put("is_nullable",nullable);
        row.put("collation_name",collation);row.put("column_default",defaultValue);return row;
    }
    private static Map<String,Object> index(String name,int nonUnique,int sequence,String column) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("index_name",name);
        row.put("non_unique",nonUnique);row.put("seq_in_index",sequence);
        row.put("column_name",column);row.put("sub_part",null);return row;
    }

}
