package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallPrivateCaseSchemaInitializerTest {
    @Test
    void additiveSchemaFreezesOwnerScopeLineageAndNoDataMutation() {
        List<String> ddl = HallPrivateCaseSchemaInitializer.ddlStatements();
        assertEquals(2, ddl.size());
        String joined = String.join("\n", ddl);
        String lower = joined.toLowerCase(Locale.ROOT);
        assertTrue(ddl.get(0).startsWith("CREATE TABLE IF NOT EXISTS hall_private_case ("));
        assertTrue(ddl.get(1).startsWith("CREATE TABLE IF NOT EXISTS hall_case_execution ("));
        assertTrue(joined.contains("UNIQUE KEY uk_hall_case_execution_scope (tenant_id, client_id, owner_jiacn, execution_id)"));
        assertTrue(joined.contains("UNIQUE KEY uk_hall_case_revision_scope (tenant_id, client_id, owner_jiacn, case_id, revision_no)"));
        assertTrue(joined.contains("revision_no=1 AND parent_execution_id IS NULL AND source_output_ref_json IS NULL"));
        assertTrue(joined.contains("revision_no>1 AND parent_execution_id IS NOT NULL AND source_output_ref_json IS NOT NULL"));
        assertTrue(joined.contains("COLLATE=utf8mb4_0900_bin"));
        assertFalse(joined.contains("TEXT DEFAULT NULL"));
        for (String forbidden : List.of(" alter table ", " insert ", " update ",
                " delete ", " drop ", " create trigger ")) {
            assertFalse(lower.contains(forbidden));
        }
    }
}
