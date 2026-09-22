package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HallPrivateMarkSchemaInitializerTest {
    @Test void schemaIsAdditivePrivateOnlyAndKeepsImmutableOperationAndScopedRevisionKeys() {
        var statements=HallPrivateMarkSchemaInitializer.ddlStatements();
        assertEquals(1,statements.size());String sql=statements.getFirst();
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS hall_private_mark"));
        assertTrue(sql.contains("PRIMARY KEY (tenant_id,client_id,owner_jiacn,source_type,source_id,revision)"));
        assertTrue(sql.contains("(tenant_id,client_id,owner_jiacn,operation_key)"));
        assertTrue(sql.contains("source_type IN ('PRIVATE_CASE','LEGACY_EXECUTION')"));
        assertTrue(sql.contains("viewed_execution_id IS NULL AND viewed_manifest_id IS NULL"));
        assertTrue(sql.contains("COMMENT='Private organization only; not execution, acceptance or funding state'"));
        assertFalse(sql.contains("UPDATE "));assertFalse(sql.contains("DELETE "));assertFalse(sql.contains("'TASK'"));
    }
}
