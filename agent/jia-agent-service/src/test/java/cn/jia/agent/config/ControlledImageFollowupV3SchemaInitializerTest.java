package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlledImageFollowupV3SchemaInitializerTest {

    @Test void featureIsDefaultOffAndDdlHasOnlyTwoExactCreateStatements() {
        ConditionalOnProperty condition=ControlledImageFollowupV3SchemaInitializer.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("agent.controlled-image-provider",condition.prefix());
        assertArrayEquals(new String[]{"followup-v3-enabled"},condition.name());
        assertEquals("true",condition.havingValue());
        assertFalse(condition.matchIfMissing());

        var statements=ControlledImageFollowupV3SchemaInitializer.createStatements();
        assertEquals(2,statements.size());
        String ddl=String.join("\n",statements).toLowerCase(Locale.ROOT);
        assertTrue(ddl.contains("create table if not exists agent_controlled_image_intent_operation_grant"));
        assertTrue(ddl.contains("create table if not exists agent_controlled_image_execution_source_v3"));
        for(String forbidden:List.of(" alter table "," drop "," update "," delete "," insert "," create trigger "))
            assertFalse((" "+ddl+" ").contains(forbidden),forbidden);
        assertFalse(ddl.contains("regexp binary"));
    }

    @Test void executionRestartAcceptsCompleteLegacyAndV3ColumnsAndIndexesWithoutChecks() {
        for (boolean v3 : List.of(false, true)) {
            assertDoesNotThrow(() -> ControlledImageFollowupV3SchemaInitializer
                    .validateExecutionRestartCatalog(executionColumns(v3), executionIndexes(v3)));
        }
        var partialColumns = executionColumns(true);
        partialColumns.removeLast();
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(partialColumns, executionIndexes(true)));
        var extraColumns = executionColumns(true);
        extraColumns.add(column("execution_protocol_shadow", "int", "YES", null, null));
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(extraColumns, executionIndexes(true)));
        var extraIndexes = executionIndexes(true);
        index(extraIndexes, "uk_pwex_operation_grant_shadow", 0, 1, "operation_grant_id");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), extraIndexes));
    }

    @Test void skillProtocolRestartRequiresCompleteColumnsAndIndexes() {
        for (String drift : List.of("none", "partialColumn", "nullableProtocol", "wrongDigestType", "wrongIndex")) {
            var columns = executionColumns(true);
            var indexes = executionIndexes(true);
            switch (drift) {
                case "partialColumn" -> columns.removeLast();
                case "nullableProtocol" -> columns.stream().filter(row -> "execution_protocol_version".equals(row.get("column_name")))
                        .findFirst().orElseThrow().put("is_nullable", "YES");
                case "wrongDigestType" -> columns.getLast().put("column_type", "varchar(64)");
                case "wrongIndex" -> indexes.getLast().put("column_name", "controlled_consent_id");
            }
            if (drift.equals("none")) assertDoesNotThrow(() -> ControlledImageFollowupV3SchemaInitializer
                    .validateExecutionRestartCatalog(columns, indexes));
            else assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                    .validateExecutionRestartCatalog(columns, indexes), drift);
        }
    }

    @Test void tableCatalogRejectsMissingExtraAndReorderedColumnsBeforeAcceptingRuntime() {
        var expected=ControlledImageFollowupV3SchemaInitializer.operationGrantColumnDefinitions();
        var pristine=columns(expected);
        assertDoesNotThrow(()->ControlledImageFollowupV3SchemaInitializer.validateExactColumns(
                "operation",pristine,expected));
        var missing=new ArrayList<>(pristine);missing.removeLast();
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",missing,expected));
        var reordered=new ArrayList<>(pristine);java.util.Collections.swap(reordered,0,1);
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",reordered,expected));
        var extra=new ArrayList<>(pristine);extra.add(column("unexpected","bigint","YES",null,null));
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",extra,expected));
    }

    private static List<Map<String,Object>> executionColumns(boolean v3) {
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(column("controlled_consent_id","varchar(100)","YES","utf8mb4_0900_bin",null));
        if(v3) {
            rows.add(column("execution_protocol_version","int","NO",null,"1"));
            rows.add(column("operation_grant_id","varchar(100)","YES","utf8mb4_0900_bin",null));
            rows.add(column("runtime_input_snapshot_digest","char(64)","YES","ascii_bin",null));
        }
        return rows;
    }

    private static List<Map<String,Object>> executionIndexes(boolean v3) {
        List<Map<String,Object>> rows=new ArrayList<>();
        int sequence=1;
        for(String column:List.of("tenant_id","client_id","owner_jiacn","controlled_consent_id"))
            index(rows,"uk_pwex_controlled_consent",0,sequence++,column);
        if(v3) {
            sequence=1;
            for(String column:List.of("tenant_id","client_id","owner_jiacn","operation_grant_id"))
                index(rows,"uk_pwex_operation_grant",0,sequence++,column);
        }
        return rows;
    }

    private static void index(List<Map<String,Object>> rows,String name,int nonUnique,
            int sequence,String column) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("index_name",name);
        row.put("non_unique",nonUnique);row.put("seq_in_index",sequence);
        row.put("column_name",column);row.put("sub_part",null);rows.add(row);
    }

    /** Literal CHECK_CLAUSE captured from MySQL 8.0.21; independent of production helpers. */

    private static List<Map<String,Object>> columns(
            Map<String,ControlledImageFollowupV3SchemaInitializer.Column> expected) {
        List<Map<String,Object>> rows=new ArrayList<>();
        expected.forEach((name,value)->rows.add(column(name,value.type(),value.nullable(),
                value.collation(),value.defaultValue())));return rows;
    }
    private static Map<String,Object> column(String name,String type,String nullable,
            String collation,String defaultValue) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("column_name",name);
        row.put("column_type",type);row.put("is_nullable",nullable);
        row.put("collation_name",collation);row.put("column_default",defaultValue);return row;
    }
}
