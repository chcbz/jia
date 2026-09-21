package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallRequestDraftSchemaInitializerTest {
    @Test
    void additiveSchemaFreezesScopeLifecycleIdempotencyAndRecoveryIndexes() {
        String ddl = HallRequestDraftSchemaInitializer.ddlStatement();
        String lower = ddl.toLowerCase(Locale.ROOT);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS hall_request_draft ("));
        assertTrue(ddl.contains("PRIMARY KEY (draft_id)"));
        assertTrue(ddl.contains("UNIQUE KEY uk_hall_draft_create (tenant_id, client_id, owner_jiacn, create_key)"));
        assertTrue(ddl.contains("UNIQUE KEY uk_hall_draft_discard (tenant_id, client_id, owner_jiacn, discard_key)"));
        assertTrue(ddl.contains("KEY idx_hall_draft_recovery (tenant_id, client_id, owner_jiacn, state, updated_at, draft_id)"));
        assertTrue(ddl.contains("CONSTRAINT chk_hall_draft_revision CHECK (revision >= 1 AND revision <= 9007199254740991)"));
        assertTrue(ddl.contains("CONSTRAINT chk_hall_draft_kind_refs CHECK"));
        assertTrue(ddl.contains("source_type IN ('CONVERSATION','TASK','EXECUTION_OUTPUT')"));
        assertTrue(ddl.contains("state='DISCARDED'"));
        assertTrue(ddl.contains("instruction               MEDIUMTEXT"));
        assertFalse(ddl.contains("MEDIUMTEXT DEFAULT NULL"));
        assertFalse(ddl.contains("TEXT DEFAULT NULL"));
        assertTrue(ddl.contains("COLLATE=utf8mb4_0900_bin"));
        assertFalse(lower.contains(" insert "));
        assertFalse(lower.contains(" update "));
        assertFalse(lower.contains(" delete "));
        assertFalse(lower.contains(" drop "));
        assertFalse(lower.contains(" create trigger "));
        assertEquals(0, ddl.chars().filter(character -> character == ';').count());
    }
}
